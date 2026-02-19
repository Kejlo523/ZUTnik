package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Request;
import okhttp3.Response;

public class PlanRepository {

    private static final String TAG = "mZUTv2-PLAN";

    // Disk cache file
    private static final String CACHE_FILE_NAME = "plan_cache_v1.json";

    // Global context (null disables file cache)
    private static Context appContext;

    // In-memory cache structure
    private static class FullPlanCache {
        String album;
        long timestampMs;
        // ConcurrentHashMap for thread safety in case of background updates
        Map<LocalDate, List<PlanEventRaw>> byDate = new ConcurrentHashMap<>();
        // Scope refresh timestamps
        Map<String, Long> scopeTimestamps = new ConcurrentHashMap<>();
    }

    private static FullPlanCache sFullPlanCache;

    // Constructors

    public PlanRepository(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    // Data models

    // Raw JSON event model
    public static class PlanEventRaw {
        public String title;
        public String description;
        public String start;
        public String end;
        public String workerTitle;
        public String worker;
        public String lessonForm;
        public String lessonFormShort;
        public String groupName;
        public String tokName;
        public String room;
        public String lessonStatus;
        public String lessonStatusShort;
        public String subject;
        public String hours;
        public String color;
        public String borderColor;
    }

    // UI event model
    public static class PlanEventUi {
        public int startMin;
        public int endMin;
        public float topPx;
        public float heightPx;
        public float leftPct;
        public float widthPct;

        public String title;
        public String room;
        public String group;
        public String startStr;
        public String endStr;
        public String tooltip;
        public String typeClass;
        public String typeLabel;
        public String subjectKey;
        public String teacher;

        // Custom event fields
        public boolean isCustomEvent = false; // True if this is a standalone custom event
        public String customEventType = null; // "exam", "pass", "test"
        public boolean hasCustomOverlay = false; // True if official event has custom overlay
        public String customOverlayLabel = null; // "EGZAMIN!", "KOLOKWIUM!", etc.
        public String customEventId = null; // ID of the custom event (for editing/deleting)
    }

    // Day column model
    public static class DayColumn {
        public LocalDate date;
        public List<PlanEventUi> events = new ArrayList<>();
    }

    // Month cell model
    public static class MonthCell {
        public LocalDate date;
        public boolean hasPlan;
    }

    // Subject filter model
    public static class SubjectFilterItem {
        public String label;
        public String typeKey;
        public String typeLabel;
        public String filterKey;
    }

    // Search params
    public static class SearchParams {
        public String category;
        public String query;
    }

    // Debug info
    public static class PlanDebug {
        public String album;
        public String view;
        public String rangeStart;
        public String rangeEnd;
        public int entriesTotal;
        public List<String> daysWithData = new ArrayList<>();

        public static class RequestDebug {
            public String url;
            public int httpCode;
            public boolean jsonOk;
            public Integer jsonCount;
        }

        public List<RequestDebug> requests = new ArrayList<>();
    }

    // Load result
    public static class PlanResult {
        public String viewMode;
        public LocalDate current;
        public LocalDate rangeStart;
        public LocalDate rangeEnd;

        // Day/Week
        public List<DayColumn> dayColumns = new ArrayList<>();
        public boolean hasAnyEventsInRange;

        // Month
        public List<List<MonthCell>> monthGrid = new ArrayList<>();

        // Navigation
        public LocalDate prev;
        public LocalDate next;
        public LocalDate today;

        public String headerLabel;

        public PlanDebug debug = new PlanDebug();
    }

    // Date formatters

    private static final DateTimeFormatter ISO_LOCAL_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final DateTimeFormatter HOUR_MIN = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String sCachedAlbum;
    private static long sCachedAlbumTs;
    private static String sCachedAlbumStudyId;
    private static final long ALBUM_TTL_MS = 24L * 60L * 60L * 1000L; // 24h

    private static String fmtPlDate(LocalDate date) {
        String dz;
        if (appContext != null) {
            switch (date.getDayOfWeek()) {
                case MONDAY:
                    dz = appContext.getString(R.string.plan_header_mon_short);
                    break;
                case TUESDAY:
                    dz = appContext.getString(R.string.plan_header_tue_short);
                    break;
                case WEDNESDAY:
                    dz = appContext.getString(R.string.plan_header_wed_short);
                    break;
                case THURSDAY:
                    dz = appContext.getString(R.string.plan_header_thu_short);
                    break;
                case FRIDAY:
                    dz = appContext.getString(R.string.plan_header_fri_short);
                    break;
                case SATURDAY:
                    dz = appContext.getString(R.string.plan_header_sat_short);
                    break;
                case SUNDAY:
                default:
                    dz = appContext.getString(R.string.plan_header_sun_short);
                    break;
            }
        } else {
            String[] dniPl = { "Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So" };
            int dow = date.getDayOfWeek().getValue() % 7;
            dz = dniPl[dow];
        }
        return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " (" + dz + ")";
    }

    private static LocalDateTime parseIsoLocal(String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        try {
            // Priority: Try OffsetDateTime first as it handles 'Z' or offsets
            OffsetDateTime odt = OffsetDateTime.parse(iso);
            return odt.toLocalDateTime();
        } catch (Exception ignored) {
        }
        try {
            // Fallback: Local
            return LocalDateTime.parse(iso, ISO_LOCAL_DT);
        } catch (Exception e) {
            // Last resort: simple date? NO, event needs time.
            return null;
        }
    }

    private static int minutesFromMidnight(LocalDateTime dt) {
        return dt.getHour() * 60 + dt.getMinute();
    }

    // Helpers - ZUT API

    private JSONArray httpGetJsonArray(String urlStr, PlanDebug debug) throws IOException, JSONException {
        PlanDebug.RequestDebug rd = new PlanDebug.RequestDebug();
        rd.url = urlStr;
        // Log.d(TAG, "GET " + urlStr);

        Request request = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", "mZUTv2-Android-Plan/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            int code = response.code();
            rd.httpCode = code;

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + code + " from plan.zut");
            }

            String body = response.body().string();
            JSONArray arr = new JSONArray(body);

            rd.jsonOk = true;
            rd.jsonCount = arr.length();
            debug.requests.add(rd);
            return arr;

        } catch (IOException | JSONException e) {
            rd.jsonOk = false;
            rd.jsonCount = null;
            debug.requests.add(rd);
            throw e;
        }
    }

    private String resolveAlbumNumber() throws IOException, JSONException {
        long now = System.currentTimeMillis();

        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) {
            return null;
        }

        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            GradesRepository gr = new GradesRepository();
            studies = gr.loadStudies();
        }
        if (studies == null || studies.isEmpty()) {
            return null;
        }

        int idx = session.getActiveStudyIndex();
        if (idx < 0 || idx >= studies.size()) {
            idx = 0;
        }
        Study active = studies.get(idx);
        if (active.przynaleznoscId == null) {
            return null;
        }
        String activeStudyId = active.przynaleznoscId.trim();
        if (activeStudyId.isEmpty()) {
            return null;
        }

        if (sCachedAlbum != null
                && sCachedAlbumStudyId != null
                && sCachedAlbumStudyId.equals(activeStudyId)
                && (now - sCachedAlbumTs) < ALBUM_TTL_MS) {
            return sCachedAlbum;
        }

        // Disk cache fallback is safe only when user has exactly one direction.
        if (studies.size() == 1) {
            if (sFullPlanCache == null) {
                FullPlanCache fromDisk = readCacheFromDisk();
                if (fromDisk != null) {
                    sFullPlanCache = fromDisk;
                }
            }
            if (sFullPlanCache != null && sFullPlanCache.album != null && !sFullPlanCache.album.isEmpty()) {
                sCachedAlbum = sFullPlanCache.album;
                sCachedAlbumTs = now;
                sCachedAlbumStudyId = activeStudyId;
                return sCachedAlbum;
            }
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", activeStudyId);

        JSONObject resp = MzutApi.callApi("getStudy", params);
        if (resp == null) {
            return null;
        }

        String album = resp.optString("album", null);
        if (album != null) {
            album = album.trim();
        }
        if (album == null || album.isEmpty()) {
            return null;
        }

        sCachedAlbum = album;
        sCachedAlbumTs = now;
        sCachedAlbumStudyId = activeStudyId;

        return album;
    }
    // Search functionality

    public PlanResult searchPlan(String viewMode, LocalDate currentDate, SearchParams search)
            throws IOException, JSONException {
        if (currentDate == null)
            currentDate = LocalDate.now();
        if (viewMode == null)
            viewMode = "week";

        PlanResult r = new PlanResult();
        r.viewMode = viewMode;
        r.current = currentDate;
        r.today = LocalDate.now();
        r.debug.view = viewMode + " (SEARCH)";

        LocalDate rangeStart, rangeEnd;
        if ("day".equals(viewMode)) {
            rangeStart = currentDate;
            rangeEnd = currentDate;
            r.headerLabel = fmtPlDate(currentDate);
        } else if ("week".equals(viewMode)) {
            LocalDate weekStart;
            weekStart = currentDate;
            int dow = weekStart.getDayOfWeek().getValue();
            if (dow > 1)
                weekStart = weekStart.minusDays(dow - 1);
            LocalDate weekEnd = weekStart.plusDays(6);
            rangeStart = weekStart;
            rangeEnd = weekEnd;
            r.headerLabel = weekStart.format(DateTimeFormatter.ofPattern("dd.MM"))
                    + " - " + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } else {
            rangeStart = currentDate.withDayOfMonth(1);
            rangeEnd = currentDate.withDayOfMonth(currentDate.lengthOfMonth());
            r.headerLabel = rangeStart.getMonth().name() + " " + rangeStart.getYear();
        }

        r.rangeStart = rangeStart;
        r.rangeEnd = rangeEnd;

        String url = buildSearchUrl(search, rangeStart, rangeEnd);

        JSONArray arr = null;
        try {
            arr = httpGetJsonArray(url, r.debug);
        } catch (Exception e) {
            Log.w(TAG, "Search error: " + e.getMessage());
        }

        List<PlanEventRaw> rawEvents = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    PlanEventRaw ev = parsePlanEventRaw(obj);
                    if (ev != null)
                        rawEvents.add(ev);
                }
            }
        }

        Map<LocalDate, List<PlanEventRaw>> byDate = groupByDay(rawEvents);

        // Search results are not cached in the main cache to avoid polluting the user's
        // plan
        // We just return them directly

        if ("day".equals(viewMode) || "week".equals(viewMode)) {
            LocalDate iter = rangeStart;
            boolean any = false;
            while (!iter.isAfter(rangeEnd)) {
                DayColumn col = new DayColumn();
                col.date = iter;
                List<PlanEventRaw> dailyRaw = byDate.getOrDefault(iter, Collections.emptyList());
                col.events = buildDayLayout(dailyRaw);
                if (!col.events.isEmpty())
                    any = true;
                r.dayColumns.add(col);
                iter = iter.plusDays(1);
            }
            r.hasAnyEventsInRange = any;
        } else {
            r.monthGrid = buildMonthGrid(currentDate, byDate.keySet());
        }

        if ("day".equals(viewMode)) {
            r.prev = currentDate.minusDays(1);
            r.next = currentDate.plusDays(1);
        } else if ("week".equals(viewMode)) {
            r.prev = currentDate.minusWeeks(1);
            r.next = currentDate.plusWeeks(1);
        } else {
            r.prev = currentDate.minusMonths(1);
            r.next = currentDate.plusMonths(1);
        }

        return r;
    }

    private String buildSearchUrl(SearchParams params, LocalDate start, LocalDate end) {
        String queryEncoded = encodeUtf8(params.query);

        String startStr = start.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        // End date should be inclusive of the day, so use end of day or start of next
        // day
        String endStr = end.plusDays(1).atStartOfDay().minusSeconds(1).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                .toString();

        startStr = encodeUtf8(startStr);
        endStr = encodeUtf8(endStr);

        String baseUrl = "https://plan.zut.edu.pl/schedule_student.php?";
        String commonParams = "&start=" + startStr + "&end=" + endStr;

        String cat = params.category != null ? params.category.trim() : "";
        String norm = cat.toLowerCase(java.util.Locale.ROOT);

        if (norm.equals("teacher") || norm.contains("wyk")) {
            return baseUrl + "teacher=" + queryEncoded + commonParams;
        }
        if (norm.equals("room") || norm.contains("sal")) {
            return baseUrl + "room=" + queryEncoded + commonParams;
        }
        if (norm.equals("group") || norm.contains("grup")) {
            return baseUrl + "group=" + queryEncoded + commonParams;
        }
        if (norm.equals("subject") || norm.contains("przedm")) {
            return baseUrl + "subject=" + queryEncoded + commonParams;
        }
        if (norm.equals("number") || norm.contains("numer") || norm.contains("album")) {
            return baseUrl + "number=" + queryEncoded + commonParams;
        }
        return baseUrl + "number=" + queryEncoded + commonParams;
    }

    /**
     * Fetches autocomplete suggestions for search queries.
     * 
     * @param kind  One of: "teacher", "room", "subject", "group"
     * @param query The search query (minimum 1 character)
     * @return List of suggestion strings (item names)
     */
    public List<String> fetchSearchSuggestions(String kind, String query) throws IOException, JSONException {
        List<String> suggestions = new ArrayList<>();
        if (kind == null || kind.isEmpty() || query == null || query.isEmpty()) {
            return suggestions;
        }

        String url = "https://plan.zut.edu.pl/schedule.php?kind="
                + encodeUtf8(kind)
                + "&query=" + encodeUtf8(query);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "mZUTv2-Android-Plan/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return suggestions;
            }

            String body = response.body().string();
            JSONArray arr = new JSONArray(body);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && obj.has("item")) {
                    String item = obj.optString("item", "");
                    if (!item.isEmpty()) {
                        suggestions.add(item);
                    }
                }
            }
        }

        return suggestions;
    }

    // Fetch range
    private List<PlanEventRaw> fetchPlanRangeByAlbum(
            String album,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            PlanDebug debug) throws IOException, JSONException {

        // Expand fetch range slightly to ensure timezone overlap is covered
        LocalDate apiFetchStart = rangeStart.minusDays(1);
        LocalDate apiFetchEnd = rangeEnd.plusDays(1);

        // Format used by FullCalendar (ISO-8601 with Offset)
        String startIso = apiFetchStart.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String endIso = apiFetchEnd.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();

        // Use encoded strings
        String startEnc = encodeUtf8(startIso);
        String endEnc = encodeUtf8(endIso);

        // Optimized: Single URL call. Standard schedule_student.php works best with ISO
        // dates.
        String url = "https://plan.zut.edu.pl/schedule_student.php?number=" + album + "&start=" + startEnc + "&end="
                + endEnc;

        JSONArray arr;
        try {
            arr = httpGetJsonArray(url, debug);
        } catch (Exception e) {
            // Fallback: If strict ISO fails (rare), try simple YMD
            String sSimple = apiFetchStart.format(YMD);
            String eSimple = apiFetchEnd.format(YMD);
            String url2 = "https://plan.zut.edu.pl/schedule_student.php?number=" + album + "&start=" + sSimple + "&end="
                    + eSimple;
            try {
                arr = httpGetJsonArray(url2, debug);
            } catch (Exception e2) {
                Log.w(TAG, "Error fetching plan (" + url + " and " + url2 + "): " + e2.getMessage());
                throw e2; // Re-throw to allow caller to decide (e.g. use cached)
            }
        }

        List<PlanEventRaw> out = new ArrayList<>();
        String filterStartStr = rangeStart.format(YMD);
        String filterEndStr = rangeEnd.format(YMD);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null)
                continue;

            PlanEventRaw r = parsePlanEventRaw(e);
            if (r == null)
                continue;

            String eventDateStr;
            if (r.start != null && r.start.length() >= 10) {
                eventDateStr = r.start.substring(0, 10);
            } else {
                continue;
            }

            if (eventDateStr.compareTo(filterStartStr) < 0 || eventDateStr.compareTo(filterEndStr) > 0) {
                continue;
            }

            out.add(r);
        }

        return out;
    }

    // Fetch full plan
    private List<PlanEventRaw> fetchFullPlanByAlbum(String album, PlanDebug debug) throws IOException, JSONException {
        // Warning: This returns thousands of events.
        String base = "https://plan.zut.edu.pl/schedule_student.php?number=" + album;
        JSONArray arr;
        try {
            arr = httpGetJsonArray(base, debug);
        } catch (Exception e) {
            Log.w(TAG, "Error fetching full plan (" + base + "): " + e.getMessage());
            throw e;
        }

        List<PlanEventRaw> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null)
                continue;
            PlanEventRaw r = parsePlanEventRaw(e);
            if (r != null)
                out.add(r);
        }
        return out;
    }

    private PlanEventRaw parsePlanEventRaw(JSONObject e) {
        if (e == null)
            return null;
        String start = e.optString("start", null);
        String end = e.optString("end", null);
        if (start == null || end == null || start.isEmpty() || end.isEmpty())
            return null;

        PlanEventRaw r = new PlanEventRaw();
        r.title = e.optString("title", "");
        r.description = e.optString("description", "");
        r.start = start;
        r.end = end;
        r.workerTitle = e.optString("worker_title", "");
        r.worker = e.optString("worker", "");
        r.lessonForm = e.optString("lesson_form", "");
        r.lessonFormShort = e.optString("lesson_form_short", "");
        r.groupName = e.optString("group_name", "");
        r.tokName = e.optString("tok_name", "");
        r.room = e.optString("room", "");
        r.lessonStatus = e.optString("lesson_status", "");
        r.lessonStatusShort = e.optString("lesson_status_short", "");
        r.subject = e.optString("subject", "");
        r.hours = e.optString("hours", "");
        r.color = e.optString("color", "");
        r.borderColor = e.optString("borderColor", "");
        return r;
    }

    private String eventTypeClass(PlanEventRaw e) {
        String statusShort = lower(e.lessonStatusShort);
        String formFull = lower(e.lessonForm);
        String formShort = lower(e.lessonFormShort);
        String subject = lower(e.subject != null && !e.subject.isEmpty() ? e.subject : e.title);

        switch (statusShort) {
            case "e":
                return "week-event-type-exam";
            case "ez":
                return "week-event-type-exam-remote";
            case "o":
                return "week-event-type-cancelled";
            case "r":
                return "week-event-type-rector";
            case "dz":
                return "week-event-type-dean";
            case "zz":
                return "week-event-type-remote";
            default:
                break;
        }

        String hay = formFull + " " + subject;

        if (hay.contains("egzamin zdalny"))
            return "week-event-type-exam-remote";
        if (hay.contains("egzamin"))
            return "week-event-type-exam";
        if (hay.contains("odwołane"))
            return "week-event-type-cancelled";
        if (hay.contains("rektorskie"))
            return "week-event-type-rector";
        if (hay.contains("dziekańskie") || hay.contains("godziny dziekańskie"))
            return "week-event-type-dean";
        if (hay.contains("zajęcia zdalne") || hay.contains("zdalne"))
            return "week-event-type-remote";

        // Pass types
        if (hay.contains("zaliczenie zdalne poprawkowe") || "zalzdp".equals(formShort))
            return "week-event-type-pass-remote-retake";
        if (hay.contains("zaliczenie zdalne") || "zalzd".equals(formShort))
            return "week-event-type-pass-remote";
        if (hay.contains("zaliczenie poprawkow") || "zalp".equals(formShort))
            return "week-event-type-pass-retake";
        if (hay.contains("zaliczenie") || "zal".equals(formShort))
            return "week-event-type-pass";

        if (hay.contains("seminarium dyplomowe") || "sd".equals(formShort))
            return "week-event-type-diploma-seminar";
        if (hay.contains("seminarium") || "s".equals(formShort))
            return "week-event-type-seminar";
        if (hay.contains("praca dyplomowa") || "pd".equals(formShort))
            return "week-event-type-diploma";
        if (hay.contains("projekt") || "p".equals(formShort))
            return "week-event-type-project";
        if (hay.contains("lektorat") || "lek".equals(formShort))
            return "week-event-type-lectorate";
        if (hay.contains("konserwatorium") || "k".equals(formShort))
            return "week-event-type-conservatory";
        if (hay.contains("konsultacje") || "kons".equals(formShort))
            return "week-event-type-consultation";
        if (hay.contains("terenowe") || "t".equals(formShort))
            return "week-event-type-field";

        if (hay.contains("laboratorium") || "l".equals(formShort))
            return "week-event-type-lab";
        if (hay.contains("audytoryjne") || "a".equals(formShort))
            return "week-event-type-auditory";
        if (hay.contains("wykład") || "w".equals(formShort))
            return "week-event-type-lecture";

        if ("z".equals(formShort))
            return "week-event-type-class";

        return "";
    }

    private String eventTypeLabel(PlanEventRaw e) {
        String cls = eventTypeClass(e);

        if (appContext == null) {
            return e.lessonForm != null ? e.lessonForm : "";
        }

        switch (cls) {
            case "week-event-type-lecture":
                return appContext.getString(R.string.plan_type_lecture);
            case "week-event-type-lab":
                return appContext.getString(R.string.plan_type_lab);
            case "week-event-type-auditory":
                return appContext.getString(R.string.plan_type_auditory);
            case "week-event-type-exam":
                return appContext.getString(R.string.plan_type_exam);
            case "week-event-type-cancelled":
                return appContext.getString(R.string.plan_type_cancelled);
            case "week-event-type-rector":
                return appContext.getString(R.string.plan_type_rector);
            case "week-event-type-remote":
                return appContext.getString(R.string.plan_type_remote);
            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake":
                return appContext.getString(R.string.plan_type_pass);
            default:
                String form = e.lessonForm != null ? e.lessonForm.trim() : "";
                return !form.isEmpty() ? form : "";
        }
    }

    // Saved search persistence

    public static class SavedSearch {
        public String label;
        public String catKey;
        public String catLabel;
        public String query;

        public SavedSearch(String l, String k, String cl, String q) {
            this.label = l;
            this.catKey = k;
            this.catLabel = cl;
            this.query = q;
        }
    }

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_SAVED_SEARCHES = "plan_saved_searches_json";

    public static List<SavedSearch> loadSavedSearches(Context context) {
        if (context == null)
            return new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SAVED_SEARCHES, null);
        List<SavedSearch> list = new ArrayList<>();
        if (json == null)
            return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new SavedSearch(o.optString("lbl"), o.optString("ck"), o.optString("cl"), o.optString("q")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveSavedSearches(Context context, List<SavedSearch> list) {
        if (context == null)
            return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (SavedSearch s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("lbl", s.label);
                o.put("ck", s.catKey);
                o.put("cl", s.catLabel);
                o.put("q", s.query);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_SAVED_SEARCHES, arr.toString()).apply();
    }

    private static String lower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String encodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value != null ? value : "";
        }
    }

    private static int mapInt(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        return raw instanceof Number ? ((Number) raw).intValue() : 0;
    }

    private static float mapFloat(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        return raw instanceof Number ? ((Number) raw).floatValue() : 0f;
    }

    // Data Structuring

    private static final int START_HOUR = 6;
    private static final int END_HOUR = 22;
    private static final float HOUR_HEIGHT_PX = 48f;

    private Map<LocalDate, List<PlanEventRaw>> groupByDay(List<PlanEventRaw> events) {
        Map<LocalDate, List<PlanEventRaw>> byDate = new HashMap<>();

        for (PlanEventRaw e : events) {
            LocalDateTime dtStart = parseIsoLocal(e.start);
            if (dtStart == null)
                continue;
            LocalDate d = dtStart.toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(e);
        }

        for (List<PlanEventRaw> list : byDate.values()) {
            list.sort((a, b) -> {
                LocalDateTime da = parseIsoLocal(a.start);
                LocalDateTime db = parseIsoLocal(b.start);
                if (da == null || db == null)
                    return 0;
                return da.compareTo(db);
            });
        }

        return byDate;
    }

    private List<PlanEventUi> buildDayLayout(List<PlanEventRaw> list) {
        List<PlanEventUi> result = new ArrayList<>();
        if (list == null || list.isEmpty())
            return result;

        List<Map<String, Object>> events = new ArrayList<>();

        for (PlanEventRaw e : list) {
            LocalDateTime dtS = parseIsoLocal(e.start);
            LocalDateTime dtE = parseIsoLocal(e.end);
            if (dtS == null || dtE == null)
                continue;

            int startMin = minutesFromMidnight(dtS);
            int endMin = minutesFromMidnight(dtE);

            int calStart = START_HOUR * 60;
            int calEnd = END_HOUR * 60;

            if (endMin <= calStart || startMin >= calEnd)
                continue;

            int startClamped = Math.max(startMin, calStart);
            int endClamped = Math.min(endMin, calEnd);
            int duration = Math.max(endClamped - startClamped, 15);

            int offsetMin = startClamped - calStart;
            float topPx = (offsetMin / 60f) * HOUR_HEIGHT_PX;
            float heightPx = (duration / 60f) * HOUR_HEIGHT_PX;
            if (heightPx < 22)
                heightPx = 22;

            String subjectName = (e.subject != null && !e.subject.isEmpty()) ? e.subject
                    : (e.title != null ? e.title : "");
            String formShort = e.lessonFormShort != null ? e.lessonFormShort.trim() : "";
            String fullTitle = subjectName;
            if (!subjectName.isEmpty() && !formShort.isEmpty())
                fullTitle += " (" + formShort + ")";

            String room = e.room != null ? e.room : "";
            String group = e.groupName != null ? e.groupName : "";
            String teacher = (e.workerTitle != null && !e.workerTitle.isEmpty()) ? e.workerTitle
                    : (e.worker != null ? e.worker : "");
            String startStr = dtS.format(HOUR_MIN);
            String endStr = dtE.format(HOUR_MIN);

            String tooltip = fullTitle + " | " + startStr + " - " + endStr
                    + (room.isEmpty() ? "" : " | sala: " + room)
                    + (group.isEmpty() ? "" : " | grupa: " + group)
                    + (teacher.isEmpty() ? "" : " | " + teacher);

            String formShortLower = lower(formShort);
            String typeKey = null;
            switch (formShortLower) {
                case "l":
                    typeKey = "lab";
                    break;
                case "a":
                    typeKey = "aud";
                    break;
                case "w":
                    typeKey = "lec";
                    break;
                default:
                    break;
            }

            String subjectKey = "";
            if (!subjectName.isEmpty() && typeKey != null)
                subjectKey = subjectName + "||" + typeKey;

            Map<String, Object> ev = new HashMap<>();
            ev.put("startMin", startMin);
            ev.put("endMin", endMin);
            ev.put("topPx", topPx);
            ev.put("heightPx", heightPx);
            ev.put("title", fullTitle);
            ev.put("room", room);
            ev.put("group", group);
            ev.put("startStr", startStr);
            ev.put("endStr", endStr);
            ev.put("tooltip", tooltip);
            ev.put("typeClass", eventTypeClass(e));
            ev.put("typeLabel", eventTypeLabel(e));
            ev.put("subjectKey", subjectKey);
            ev.put("teacher", teacher);

            events.add(ev);
        }

        events.sort((a, b) -> {
            int sA = mapInt(a, "startMin");
            int sB = mapInt(b, "startMin");
            if (sA == sB) {
                return Integer.compare(mapInt(a, "endMin"), mapInt(b, "endMin"));
            }
            return Integer.compare(sA, sB);
        });

        // Layout algorithm (simple greedy with clusters)
        List<List<Map<String, Object>>> clusters = new ArrayList<>();
        List<Map<String, Object>> currentCluster = new ArrayList<>();
        int clusterEnd = Integer.MIN_VALUE;

        for (Map<String, Object> ev : events) {
            int startMin = mapInt(ev, "startMin");
            int endMin = mapInt(ev, "endMin");

            if (currentCluster.isEmpty()) {
                currentCluster.add(ev);
                clusterEnd = endMin;
                continue;
            }

            if (startMin < clusterEnd) {
                currentCluster.add(ev);
                if (endMin > clusterEnd)
                    clusterEnd = endMin;
            } else {
                clusters.add(currentCluster);
                currentCluster = new ArrayList<>();
                currentCluster.add(ev);
                clusterEnd = endMin;
            }
        }
        if (!currentCluster.isEmpty())
            clusters.add(currentCluster);

        List<Map<String, Object>> finalEvents = new ArrayList<>();
        for (List<Map<String, Object>> cluster : clusters) {
            List<Integer> lanes = new ArrayList<>();
            for (Map<String, Object> ev : cluster) {
                int startMin = mapInt(ev, "startMin");
                int endMin = mapInt(ev, "endMin");
                boolean assigned = false;
                for (int laneIdx = 0; laneIdx < lanes.size(); laneIdx++) {
                    if (startMin >= lanes.get(laneIdx)) {
                        lanes.set(laneIdx, endMin);
                        ev.put("lane", laneIdx);
                        assigned = true;
                        break;
                    }
                }
                if (!assigned) {
                    ev.put("lane", lanes.size());
                    lanes.add(endMin);
                }
            }
            int laneCount = Math.max(1, lanes.size());
            float laneWidth = 100f / laneCount;
            for (Map<String, Object> ev : cluster) {
                int laneIdx = mapInt(ev, "lane");
                ev.put("leftPct", laneIdx * laneWidth);
                ev.put("widthPct", laneWidth);
                ev.remove("lane");
                finalEvents.add(ev);
            }
        }

        for (Map<String, Object> ev : finalEvents) {
            PlanEventUi ui = new PlanEventUi();
            ui.startMin = mapInt(ev, "startMin");
            ui.endMin = mapInt(ev, "endMin");
            ui.topPx = mapFloat(ev, "topPx");
            ui.heightPx = mapFloat(ev, "heightPx");
            ui.leftPct = mapFloat(ev, "leftPct");
            ui.widthPct = mapFloat(ev, "widthPct");
            ui.title = (String) ev.get("title");
            ui.room = (String) ev.get("room");
            ui.group = (String) ev.get("group");
            ui.startStr = (String) ev.get("startStr");
            ui.endStr = (String) ev.get("endStr");
            ui.tooltip = (String) ev.get("tooltip");
            ui.typeClass = (String) ev.get("typeClass");
            ui.typeLabel = (String) ev.get("typeLabel");
            ui.subjectKey = (String) ev.get("subjectKey");
            ui.teacher = (String) ev.get("teacher");
            result.add(ui);
        }
        return result;
    }

    private List<List<MonthCell>> buildMonthGrid(LocalDate monthDate, Set<LocalDate> daysWithPlan) {
        List<List<MonthCell>> grid = new ArrayList<>();
        LocalDate monthStart = monthDate.withDayOfMonth(1);
        LocalDate monthEnd = monthDate.withDayOfMonth(monthDate.lengthOfMonth());
        LocalDate cursor = monthStart;
        int col = cursor.getDayOfWeek().getValue() - 1;
        List<MonthCell> week = new ArrayList<>(Collections.nCopies(7, null));

        while (!cursor.isAfter(monthEnd)) {
            MonthCell cell = new MonthCell();
            cell.date = cursor;
            cell.hasPlan = daysWithPlan != null && daysWithPlan.contains(cursor);
            week.set(col, cell);
            col++;
            if (col >= 7) {
                grid.add(new ArrayList<>(week));
                week = new ArrayList<>(Collections.nCopies(7, null));
                col = 0;
            }
            cursor = cursor.plusDays(1);
        }
        boolean any = false;
        for (MonthCell c : week)
            if (c != null) {
                any = true;
                break;
            }
        if (any)
            grid.add(new ArrayList<>(week));
        return grid;
    }

    // Cache management

    private String buildScopeKey(String viewMode, LocalDate rangeStart) {
        // Simple key: mode + start date
        return viewMode + ":" + rangeStart.format(YMD);
    }

    private Map<LocalDate, List<PlanEventRaw>> ensureScopeData(
            String album,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            String viewMode,
            boolean forceScopeRefresh,
            PlanDebug debug) throws IOException, JSONException {
        long now = System.currentTimeMillis();

        synchronized (PlanRepository.class) {
            // Check again inside lock
            if (sFullPlanCache == null || album == null || !album.equals(sFullPlanCache.album)) {
                FullPlanCache fromDisk = readCacheFromDisk();
                if (fromDisk != null && album != null && album.equals(fromDisk.album)) {
                    sFullPlanCache = fromDisk;
                } else {
                    FullPlanCache newCache = new FullPlanCache();
                    newCache.album = album;
                    newCache.timestampMs = now;
                    sFullPlanCache = newCache;
                }
            }
            if (sFullPlanCache.scopeTimestamps == null)
                sFullPlanCache.scopeTimestamps = new ConcurrentHashMap<>();
            if (sFullPlanCache.byDate == null)
                sFullPlanCache.byDate = new ConcurrentHashMap<>();

            String scopeKey = buildScopeKey(viewMode, rangeStart);
            Long lastScopeMs = sFullPlanCache.scopeTimestamps.get(scopeKey);

            String userAlbum = resolveAlbumNumber();
            boolean isUserPlan = (userAlbum != null && userAlbum.equals(album));
            long ttl = isUserPlan ? 3_600_000L : 0L; // 1h cache

            boolean needRefresh = forceScopeRefresh || (lastScopeMs == null) || ((now - lastScopeMs) > ttl);

            // If offline, disable refresh to prevent errors and rely on cache
            if (appContext != null && !NetworkStatusHelper.isNetworkAvailable(appContext)) {
                needRefresh = false;
            }

            if (needRefresh) {
                try {
                    List<PlanEventRaw> fresh = fetchPlanRangeByAlbum(album, rangeStart, rangeEnd, debug);
                    // If we get data, update cache. If we get NOTHING (and not error), it might be
                    // empty period.
                    // But if fresh is empty, we still update timestamp to avoid re-fetching
                    // immediately empty range.
                    Map<LocalDate, List<PlanEventRaw>> tmp = groupByDay(fresh);
                    LocalDate d = rangeStart;
                    while (!d.isAfter(rangeEnd)) {
                        List<PlanEventRaw> list = tmp.get(d);
                        if (list != null)
                            sFullPlanCache.byDate.put(d, list);
                        else
                            sFullPlanCache.byDate.remove(d); // Clear empty days in range
                        d = d.plusDays(1);
                    }
                    sFullPlanCache.scopeTimestamps.put(scopeKey, now);
                    sFullPlanCache.timestampMs = now;
                    writeCacheToDisk(sFullPlanCache);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to refresh plan scope (offline?): " + e.getMessage());
                    // We suppress the error and return what we have in cache
                    // (sFullPlanCache.byDate)
                    // If cache is missing for this range, it will return null/empty, which is safer
                    // than crashing app
                }
            }

            return sFullPlanCache.byDate;
        }
    }

    private FullPlanCache readCacheFromDisk() {
        if (appContext == null)
            return null;
        FileInputStream fis = null;
        try {
            fis = appContext.openFileInput(CACHE_FILE_NAME);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);

            JSONObject root = new JSONObject(sb.toString());
            FullPlanCache cache = new FullPlanCache();
            cache.album = root.optString("album", null);
            cache.timestampMs = root.optLong("timestamp", 0L);

            JSONObject eventsByDate = root.optJSONObject("eventsByDate");
            Map<LocalDate, List<PlanEventRaw>> map = new ConcurrentHashMap<>();
            if (eventsByDate != null) {
                Iterator<String> keys = eventsByDate.keys();
                while (keys.hasNext()) {
                    String dateStr = keys.next();
                    JSONArray arr = eventsByDate.optJSONArray(dateStr);
                    if (arr == null)
                        continue;
                    LocalDate d;
                    try {
                        d = LocalDate.parse(dateStr, YMD);
                    } catch (Exception e) {
                        continue;
                    }
                    List<PlanEventRaw> dayList = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        PlanEventRaw r = parsePlanEventRaw(arr.optJSONObject(i));
                        if (r != null)
                            dayList.add(r);
                    }
                    map.put(d, dayList);
                }
            }
            cache.byDate = map;

            JSONObject scopesJson = root.optJSONObject("scopeTimestamps");
            Map<String, Long> scopesMap = new ConcurrentHashMap<>();
            if (scopesJson != null) {
                Iterator<String> keys = scopesJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    scopesMap.put(key, scopesJson.optLong(key));
                }
            }
            cache.scopeTimestamps = scopesMap;
            return cache;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read cache: " + e.getMessage());
            return null;
        } finally {
            if (fis != null)
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
        }
    }

    private void writeCacheToDisk(FullPlanCache cache) {
        if (appContext == null || cache == null)
            return;
        FileOutputStream fos = null;
        try {
            JSONObject root = new JSONObject();
            root.put("album", cache.album);
            root.put("timestamp", cache.timestampMs);

            JSONObject eventsByDate = new JSONObject();
            // Snapshot for writing
            for (Map.Entry<LocalDate, List<PlanEventRaw>> entry : cache.byDate.entrySet()) {
                JSONArray arr = new JSONArray();
                for (PlanEventRaw r : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("title", r.title);
                    obj.put("description", r.description);
                    obj.put("start", r.start);
                    obj.put("end", r.end);
                    obj.put("worker_title", r.workerTitle);
                    obj.put("worker", r.worker);
                    obj.put("lesson_form", r.lessonForm);
                    obj.put("lesson_form_short", r.lessonFormShort);
                    obj.put("group_name", r.groupName);
                    obj.put("tok_name", r.tokName);
                    obj.put("room", r.room);
                    obj.put("lesson_status", r.lessonStatus);
                    obj.put("lesson_status_short", r.lessonStatusShort);
                    obj.put("subject", r.subject);
                    obj.put("hours", r.hours);
                    obj.put("color", r.color);
                    obj.put("borderColor", r.borderColor);
                    arr.put(obj);
                }
                eventsByDate.put(entry.getKey().format(YMD), arr);
            }
            root.put("eventsByDate", eventsByDate);

            JSONObject scopesJson = new JSONObject();
            for (Map.Entry<String, Long> e : cache.scopeTimestamps.entrySet()) {
                scopesJson.put(e.getKey(), e.getValue());
            }
            root.put("scopeTimestamps", scopesJson);

            fos = appContext.openFileOutput(CACHE_FILE_NAME, Context.MODE_PRIVATE);
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache: " + e.getMessage());
        } finally {
            if (fos != null)
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
        }
    }

    // Public API

    public PlanResult loadPlan(String viewMode, LocalDate currentDate) throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, false, false);
    }

    public PlanResult loadPlan(String viewMode, LocalDate currentDate, boolean forceFullRefresh)
            throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, forceFullRefresh, false);
    }

    @SuppressWarnings("unused")
    public PlanResult reloadScope(String viewMode, LocalDate currentDate) throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, false, true);
    }

    private PlanResult loadPlanInternal(String viewMode, LocalDate currentDate, boolean forceFullRefresh,
            boolean forceScopeRefresh) throws IOException, JSONException {
        if (currentDate == null)
            currentDate = LocalDate.now();
        if (!"day".equals(viewMode) && !"week".equals(viewMode) && !"month".equals(viewMode))
            viewMode = "week";

        PlanResult r = new PlanResult();
        r.viewMode = viewMode;
        r.current = currentDate;
        r.today = LocalDate.now();
        String album = resolveAlbumNumber();
        r.debug.album = album;
        r.debug.view = viewMode;

        if (album == null)
            return r;

        LocalDate rangeStart, rangeEnd;
        if ("day".equals(viewMode)) {
            rangeStart = currentDate;
            rangeEnd = currentDate;
            r.headerLabel = fmtPlDate(currentDate);
        } else if ("week".equals(viewMode)) {
            LocalDate weekStart;
            weekStart = currentDate;
            int dow = weekStart.getDayOfWeek().getValue();
            if (dow > 1)
                weekStart = weekStart.minusDays(dow - 1);
            LocalDate weekEnd = weekStart.plusDays(6);
            rangeStart = weekStart;
            rangeEnd = weekEnd;
            r.headerLabel = weekStart.format(DateTimeFormatter.ofPattern("dd.MM")) + " - " + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } else {
            rangeStart = currentDate.withDayOfMonth(1);
            rangeEnd = currentDate.withDayOfMonth(currentDate.lengthOfMonth());
            r.headerLabel = rangeStart.getMonth().name() + " " + rangeStart.getYear();
        }

        r.rangeStart = rangeStart;
        r.rangeEnd = rangeEnd;
        r.debug.rangeStart = rangeStart.format(YMD);
        r.debug.rangeEnd = rangeEnd.format(YMD);

        if (forceFullRefresh) {
            try {
                long now = System.currentTimeMillis();
                List<PlanEventRaw> allEvents = fetchFullPlanByAlbum(album, r.debug);
                Map<LocalDate, List<PlanEventRaw>> grouped = groupByDay(allEvents);
                synchronized (PlanRepository.class) {
                    FullPlanCache newCache = new FullPlanCache();
                    newCache.album = album;
                    newCache.timestampMs = now;
                    newCache.byDate = new ConcurrentHashMap<>(grouped);
                    newCache.scopeTimestamps = new ConcurrentHashMap<>();
                    sFullPlanCache = newCache;
                    writeCacheToDisk(newCache);
                }
            } catch (Exception e) {
                Log.w(TAG, "Full refresh failed: " + e.getMessage());
            }
        }

        // Ensure we iterate through the requested dates to build the result
        // If not force refresh, ensureScopeData will fetch if needed
        Map<LocalDate, List<PlanEventRaw>> byDate = ensureScopeData(album, rangeStart, rangeEnd, viewMode,
                forceScopeRefresh, r.debug);

        List<PlanEventRaw> entries = new ArrayList<>();
        LocalDate iterDate = rangeStart;
        while (!iterDate.isAfter(rangeEnd)) {
            List<PlanEventRaw> dayList = byDate.get(iterDate);
            if (dayList != null)
                entries.addAll(dayList);
            iterDate = iterDate.plusDays(1);
        }
        r.debug.entriesTotal = entries.size();

        Map<LocalDate, List<PlanEventRaw>> byDateRange = groupByDay(entries);
        List<String> daysWithDataStr = new ArrayList<>();
        for (LocalDate d : byDateRange.keySet())
            daysWithDataStr.add(d.format(YMD));
        Collections.sort(daysWithDataStr);
        r.debug.daysWithData = daysWithDataStr;

        if ("day".equals(viewMode) || "week".equals(viewMode)) {
            LocalDate iter = rangeStart;
            boolean any = false;
            while (!iter.isAfter(rangeEnd)) {
                DayColumn col = new DayColumn();
                col.date = iter;
                List<PlanEventRaw> rawList = byDateRange.getOrDefault(iter, Collections.emptyList());
                col.events = buildDayLayout(rawList);

                // Integrate custom events for this day
                col.events = mergeCustomEvents(col.events, iter);

                if (!col.events.isEmpty())
                    any = true;
                r.dayColumns.add(col);
                iter = iter.plusDays(1);
            }
            r.hasAnyEventsInRange = any;
        } else {
            Set<LocalDate> daysWithPlan = new HashSet<>(byDateRange.keySet());
            r.monthGrid = buildMonthGrid(currentDate, daysWithPlan);
        }

        if ("day".equals(viewMode)) {
            r.prev = currentDate.minusDays(1);
            r.next = currentDate.plusDays(1);
        } else if ("week".equals(viewMode)) {
            r.prev = currentDate.minusWeeks(1);
            r.next = currentDate.plusWeeks(1);
        } else {
            r.prev = currentDate.minusMonths(1);
            r.next = currentDate.plusMonths(1);
        }
        return r;
    }

    // Optimized loadUpcomingEvents for Widget
    @SuppressWarnings("unused")
    public List<PlanEventUi> loadUpcomingEvents(int daysCount) throws IOException, JSONException {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(daysCount);

        String album = resolveAlbumNumber();
        if (album == null) {
            return Collections.emptyList();
        }

        // Side effect only: prefetch upcoming days into cache for faster widget reads.
        ensureScopeData(album, today, end, "widget", false, new PlanDebug());
        return Collections.emptyList();
    }

    public List<SubjectFilterItem> loadSubjectsForFilter() throws IOException, JSONException {
        return loadSubjectsForFilter(false);
    }

    public List<SubjectFilterItem> loadSubjectsForFilter(boolean forceRefresh) throws IOException, JSONException {
        String album = resolveAlbumNumber();
        if (album == null) {
            return Collections.emptyList();
        }

        AcademicRange range = resolveCurrentAcademicTermRange(LocalDate.now());
        Map<LocalDate, List<PlanEventRaw>> byDate = ensureScopeData(
                album,
                range.start,
                range.end,
                "filter_current",
                forceRefresh,
                new PlanDebug());

        if (byDate == null || byDate.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, String> normalizedToSubject = new HashMap<>();
        Map<String, Set<String>> normalizedToTypes = new HashMap<>();

        LocalDate iter = range.start;
        while (!iter.isAfter(range.end)) {
            List<PlanEventRaw> events = byDate.get(iter);
            if (events != null) {
                for (PlanEventRaw e : events) {
                    String subject = (e.subject != null ? e.subject : e.title);
                    if (subject == null) {
                        continue;
                    }
                    subject = subject.trim();
                    if (subject.isEmpty()) {
                        continue;
                    }

                    String typeKey = resolveFilterTypeKey(e);
                    if (typeKey == null) {
                        continue;
                    }

                    String normalizedSubject = normalizeFilterString(subject);
                    if (normalizedSubject.isEmpty()) {
                        continue;
                    }

                    normalizedToSubject.putIfAbsent(normalizedSubject, subject);
                    normalizedToTypes
                            .computeIfAbsent(normalizedSubject, k -> new HashSet<>())
                            .add(typeKey);
                }
            }
            iter = iter.plusDays(1);
        }

        if (normalizedToSubject.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> normalizedKeys = new ArrayList<>(normalizedToSubject.keySet());
        normalizedKeys.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                normalizedToSubject.getOrDefault(a, ""),
                normalizedToSubject.getOrDefault(b, "")));

        List<SubjectFilterItem> items = new ArrayList<>();
        for (String normalizedSubject : normalizedKeys) {
            String subject = normalizedToSubject.get(normalizedSubject);
            Set<String> types = normalizedToTypes.getOrDefault(normalizedSubject, Collections.emptySet());
            if (subject == null || subject.isEmpty() || types == null || types.isEmpty()) {
                continue;
            }

            if (types.contains("lec")) {
                SubjectFilterItem it = new SubjectFilterItem();
                it.label = subject;
                it.typeKey = "lec";
                it.typeLabel = getFilterTypeLabel("lec");
                it.filterKey = subject + "||lec";
                items.add(it);
            }
            if (types.contains("aud")) {
                SubjectFilterItem it = new SubjectFilterItem();
                it.label = subject;
                it.typeKey = "aud";
                it.typeLabel = getFilterTypeLabel("aud");
                it.filterKey = subject + "||aud";
                items.add(it);
            }
            if (types.contains("lab")) {
                SubjectFilterItem it = new SubjectFilterItem();
                it.label = subject;
                it.typeKey = "lab";
                it.typeLabel = getFilterTypeLabel("lab");
                it.filterKey = subject + "||lab";
                items.add(it);
            }
        }

        return items;
    }

    private static class AcademicRange {
        final LocalDate start;
        final LocalDate end;

        AcademicRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    private AcademicRange resolveCurrentAcademicTermRange(LocalDate date) {
        LocalDate now = date != null ? date : LocalDate.now();
        int month = now.getMonthValue();

        // Winter term: October -> February
        if (month >= 10) {
            int yStart = now.getYear();
            int yEnd = yStart + 1;
            LocalDate start = LocalDate.of(yStart, 10, 1);
            LocalDate end = LocalDate.of(yEnd, 2, LocalDate.of(yEnd, 2, 1).lengthOfMonth());
            return new AcademicRange(start, end);
        }
        if (month <= 2) {
            int yEnd = now.getYear();
            int yStart = yEnd - 1;
            LocalDate start = LocalDate.of(yStart, 10, 1);
            LocalDate end = LocalDate.of(yEnd, 2, LocalDate.of(yEnd, 2, 1).lengthOfMonth());
            return new AcademicRange(start, end);
        }

        // Summer term: March -> September
        int y = now.getYear();
        LocalDate start = LocalDate.of(y, 3, 1);
        LocalDate end = LocalDate.of(y, 9, 30);
        return new AcademicRange(start, end);
    }

    private String resolveFilterTypeKey(PlanEventRaw e) {
        if (e == null) {
            return null;
        }

        String formShort = normalizeFilterString(e.lessonFormShort);
        if ("l".equals(formShort) || formShort.contains("lab")) {
            return "lab";
        }
        if ("a".equals(formShort) || formShort.contains("aud")) {
            return "aud";
        }
        if ("w".equals(formShort) || formShort.contains("wyk") || formShort.contains("lec")) {
            return "lec";
        }

        String typeClass = lower(eventTypeClass(e));
        if (typeClass.endsWith("-lab")) {
            return "lab";
        }
        if (typeClass.endsWith("-auditory")) {
            return "aud";
        }
        if (typeClass.endsWith("-lecture")) {
            return "lec";
        }

        String form = normalizeFilterString(e.lessonForm);
        if (form.contains("laboratorium") || form.contains("laboratory")) {
            return "lab";
        }
        if (form.contains("audytoryjne") || form.contains("auditory") || form.contains("auditorium")) {
            return "aud";
        }
        if (form.contains("wyklad") || form.contains("lecture")) {
            return "lec";
        }

        return null;
    }

    private String getFilterTypeLabel(String typeKey) {
        if (appContext != null) {
            switch (typeKey) {
                case "lec":
                    return appContext.getString(R.string.plan_type_lecture);
                case "aud":
                    return appContext.getString(R.string.plan_type_auditory);
                case "lab":
                    return appContext.getString(R.string.plan_type_lab);
                default:
                    return "";
            }
        }
        switch (typeKey) {
            case "lec":
                return "Lecture";
            case "aud":
                return "Auditory";
            case "lab":
                return "Laboratory";
            default:
                return "";
        }
    }

    private String normalizeFilterString(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }

        String normalized = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized
                .replace('\u0142', 'l')
                .replace('\u0141', 'l')
                .replace('\u0111', 'd')
                .replace('\u0110', 'd');
    }
    /**
     * Merges custom events (exams, tests) with official plan events for a given
     * day.
     * - If a custom event matches an official event (same subject on same day, and
     *   start time if provided), add
     * overlay label
     * - If no matching official event, create a standalone custom event in the grid
     */
    private List<PlanEventUi> mergeCustomEvents(List<PlanEventUi> events, LocalDate date) {
        if (appContext == null)
            return events;

        CustomPlanEventRepository customRepo = new CustomPlanEventRepository(appContext);
        List<CustomPlanEvent> customEvents = customRepo.getEventsForDate(date);

        if (customEvents == null || customEvents.isEmpty()) {
            return events;
        }

        List<PlanEventUi> result = new ArrayList<>(events);

        for (CustomPlanEvent customEvent : customEvents) {
            boolean foundMatch = false;
            String subjectLower = customEvent.subjectName != null
                    ? customEvent.subjectName.toLowerCase(Locale.ROOT)
                    : "";

            // Check if any official event matches this custom event (by subject name AND
            // type)
            // Exam (egzamin) matches only lectures (W), Pass/Test matches labs/exercises
            for (PlanEventUi event : result) {
                if (event.title != null && event.title.toLowerCase(Locale.ROOT).contains(subjectLower)) {
                    // Check if type matches
                    boolean typeMatches = false;
                    String typeClass = event.typeClass != null ? event.typeClass.toLowerCase(Locale.ROOT) : "";

                    if (CustomPlanEvent.TYPE_EXAM.equals(customEvent.eventType)) {
                        // Exam only matches lectures: typeClass contains "lec" or title ends with "(W)"
                        typeMatches = typeClass.contains("lec") ||
                                (event.title != null && event.title.trim().endsWith("(W)"));
                    } else {
                        // Pass/Test matches exercises, labs, auditorium: not lecture
                        typeMatches = !typeClass.contains("lec") &&
                                (event.title == null || !event.title.trim().endsWith("(W)"));
                    }

                    boolean timeMatches = true;
                    if (customEvent.startTime != null) {
                        int customStartMin = customEvent.startTime.getHour() * 60 + customEvent.startTime.getMinute();
                        timeMatches = customStartMin == event.startMin;
                    }

                    if (typeMatches && timeMatches) {
                        // Found matching official event - add overlay
                        event.hasCustomOverlay = true;
                        event.customOverlayLabel = customEvent.getTypeShortLabel(appContext);
                        event.customEventId = String.valueOf(customEvent.id);
                        event.customEventType = customEvent.eventType;
                        // Transfer notes to tooltip for display
                        if (customEvent.notes != null && !customEvent.notes.isEmpty()) {
                            event.tooltip = customEvent.notes;
                        }
                        foundMatch = true;
                        break;
                    }
                }
            }

            // If no matching official event, create standalone custom event
            if (!foundMatch && customEvent.startTime != null) {
                PlanEventUi standaloneEvent = new PlanEventUi();
                standaloneEvent.isCustomEvent = true;
                standaloneEvent.customEventType = customEvent.eventType;
                standaloneEvent.customEventId = String.valueOf(customEvent.id);
                standaloneEvent.title = customEvent.subjectName;
                standaloneEvent.typeLabel = customEvent.getTypeLabel(appContext);
                standaloneEvent.typeClass = "custom-" + customEvent.eventType;

                // Calculate time
                int startMinutes = customEvent.startTime.getHour() * 60 + customEvent.startTime.getMinute();
                int endMinutes = customEvent.endTime != null
                        ? customEvent.endTime.getHour() * 60 + customEvent.endTime.getMinute()
                        : startMinutes + 90;

                standaloneEvent.startMin = startMinutes;
                standaloneEvent.endMin = endMinutes;
                standaloneEvent.startStr = customEvent.startTime
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                standaloneEvent.endStr = customEvent.endTime != null
                        ? customEvent.endTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        : "";

                // Calculate layout position (relative to START_HOUR = 6)
                int gridStartMin = 6 * 60;
                float hourHeightDp = 48f;
                float density = appContext.getResources().getDisplayMetrics().density;
                float hourHeightPx = hourHeightDp * density;
                float dayHeaderHeightPx = 48f * density;

                standaloneEvent.topPx = dayHeaderHeightPx + ((startMinutes - gridStartMin) / 60f) * hourHeightPx;
                standaloneEvent.heightPx = ((endMinutes - startMinutes) / 60f) * hourHeightPx;
                standaloneEvent.leftPct = 0f;
                standaloneEvent.widthPct = 100f;

                // Notes as tooltip
                if (customEvent.notes != null && !customEvent.notes.isEmpty()) {
                    standaloneEvent.tooltip = customEvent.notes;
                }

                result.add(standaloneEvent);
            }
        }

        return result;
    }
    // --- Session Dates ---

    public static class SessionPeriod {
        public String name; // canonical key, e.g. "sesja_zimowa", "wakacje_letnie"
        public LocalDate startDate;
        public LocalDate endDate;

        public SessionPeriod(String name, LocalDate startDate, LocalDate endDate) {
            this.name = name;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        public boolean contains(LocalDate date) {
            if (date == null || startDate == null || endDate == null) {
                return false;
            }
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    private static final String PERIOD_SESSION_WINTER = "sesja_zimowa";
    private static final String PERIOD_SESSION_SUMMER = "sesja_letnia";
    private static final String PERIOD_SESSION_RETAKE = "sesja_poprawkowa";
    private static final String PERIOD_BREAK_WINTER = "przerwa_dydaktyczna_zimowa";
    private static final String PERIOD_BREAK_SUMMER = "przerwa_dydaktyczna_letnia";
    private static final String PERIOD_BREAK_GENERIC = "przerwa_dydaktyczna";
    private static final String PERIOD_HOLIDAY_WINTER = "wakacje_zimowe";
    private static final String PERIOD_HOLIDAY_SUMMER = "wakacje_letnie";

    private static final String KEY_SESSION_CACHE_JSON = "session_dates_cache_json_v3";
    private static final String KEY_SESSION_CACHE_TS = "session_dates_cache_ts_v3";
    private static final long SESSION_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    private static List<SessionPeriod> sCachedSessions;
    private static long sCachedSessionsTs;

    public List<SessionPeriod> fetchSessionDates() {
        long now = System.currentTimeMillis();

        if (sCachedSessions != null && (now - sCachedSessionsTs) < SESSION_CACHE_TTL_MS) {
            return sCachedSessions;
        }

        if (appContext != null) {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
            long ts = sp.getLong(KEY_SESSION_CACHE_TS, 0);
            if ((now - ts) < SESSION_CACHE_TTL_MS) {
                String json = sp.getString(KEY_SESSION_CACHE_JSON, null);
                if (json != null) {
                    List<SessionPeriod> cached = parseSessionJson(json);
                    if (cached != null && !cached.isEmpty()) {
                        sCachedSessions = cached;
                        sCachedSessionsTs = now;
                        return cached;
                    }
                }
            }
        }

        List<SessionPeriod> sessions = scrapeSessionDates();
        if (sessions != null && !sessions.isEmpty()) {
            sCachedSessions = sessions;
            sCachedSessionsTs = now;
            if (appContext != null) {
                SharedPreferences sp = appContext.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                sp.edit()
                        .putString(KEY_SESSION_CACHE_JSON, sessionToJson(sessions))
                        .putLong(KEY_SESSION_CACHE_TS, now)
                        .apply();
            }
        }
        return sessions != null ? sessions : new ArrayList<>();
    }

    public static List<SessionPeriod> getCachedSessionDates(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        long now = System.currentTimeMillis();
        if (sCachedSessions != null && (now - sCachedSessionsTs) < SESSION_CACHE_TTL_MS) {
            return new ArrayList<>(sCachedSessions);
        }

        SharedPreferences sp = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_SESSION_CACHE_JSON, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        List<SessionPeriod> parsed = parseSessionJson(json);
        if (!parsed.isEmpty()) {
            sCachedSessions = parsed;
            sCachedSessionsTs = now;
        }
        return parsed;
    }

    public static SessionPeriod findActivePeriod(List<SessionPeriod> periods, LocalDate date, boolean noClassesOnly) {
        if (periods == null || periods.isEmpty() || date == null) {
            return null;
        }
        for (SessionPeriod period : periods) {
            if (period == null || !period.contains(date)) {
                continue;
            }
            if (!noClassesOnly || isNoClassesPeriodName(period.name)) {
                return period;
            }
        }
        return null;
    }

    public static boolean isSessionPeriodName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        return PERIOD_SESSION_WINTER.equals(n)
                || PERIOD_SESSION_SUMMER.equals(n)
                || PERIOD_SESSION_RETAKE.equals(n)
                || "zimowa".equals(n)
                || "letnia".equals(n)
                || "poprawkowa".equals(n);
    }

    public static boolean isNoClassesPeriodName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("przerwa_") || n.startsWith("wakacje_");
    }

    public static String getPeriodDisplayName(Context context, String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (context == null) {
            return n;
        }
        switch (n) {
            case PERIOD_SESSION_WINTER:
            case "zimowa":
                return context.getString(R.string.session_winter);
            case PERIOD_SESSION_SUMMER:
            case "letnia":
                return context.getString(R.string.session_summer);
            case PERIOD_SESSION_RETAKE:
            case "poprawkowa":
                return context.getString(R.string.session_retake);
            case PERIOD_BREAK_WINTER:
                return context.getString(R.string.period_break_winter_semester);
            case PERIOD_BREAK_SUMMER:
                return context.getString(R.string.period_break_summer_semester);
            case PERIOD_BREAK_GENERIC:
                return context.getString(R.string.period_break_generic);
            case PERIOD_HOLIDAY_WINTER:
                return context.getString(R.string.period_holiday_winter);
            case PERIOD_HOLIDAY_SUMMER:
                return context.getString(R.string.period_holiday_summer);
            default:
                return n;
        }
    }

    private List<SessionPeriod> scrapeSessionDates() {
        for (String url : buildCandidateCalendarUrls()) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "mZUTv2-Android-Plan/1.0")
                        .build();

                try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        continue;
                    }
                    String html = response.body() != null ? response.body().string() : "";
                    if (html.isEmpty()) {
                        continue;
                    }

                    List<SessionPeriod> sessions = parseSessionHtml(html);
                    if (sessions != null && !sessions.isEmpty()) {
                        return sessions;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Session scrape error (" + url + "): " + e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    private List<String> buildCandidateCalendarUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        // Stable fallback URL that is often redirected to latest academic year.
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego-20232024.html");
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego.html");

        int year = java.time.Year.now().getValue();
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego-" + year + (year + 1) + ".html");
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego-" + (year - 1) + year + ".html");
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego-" + (year + 1) + (year + 2) + ".html");
        urls.add("https://www.zut.edu.pl/zut-studenci/organizacja-roku-akademickiego-" + (year - 2) + (year - 1) + ".html");

        urls.addAll(discoverAcademicCalendarUrlsFromStudentsPage());
        return new ArrayList<>(urls);
    }

    private List<String> discoverAcademicCalendarUrlsFromStudentsPage() {
        List<String> links = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url("https://www.zut.edu.pl/zut-studenci/")
                    .header("User-Agent", "mZUTv2-Android-Plan/1.0")
                    .build();
            try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return links;
                }
                String html = response.body() != null ? response.body().string() : "";
                if (html.isEmpty()) {
                    return links;
                }
                links.addAll(extractAcademicCalendarLinks(html));
            }
        } catch (Exception ignored) {
        }
        return links;
    }

    private List<String> extractAcademicCalendarLinks(String html) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(https?://www\\.zut\\.edu\\.pl)?(/zut-studenci/organizacja-roku-akademickiego-\\d{8}\\.html)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(html);
        while (m.find()) {
            String host = m.group(1);
            String path = m.group(2);
            if (path == null || path.isEmpty()) {
                continue;
            }
            if (host == null || host.isEmpty()) {
                links.add("https://www.zut.edu.pl" + path);
            } else {
                links.add(host + path);
            }
        }
        return new ArrayList<>(links);
    }

    private List<SessionPeriod> parseSessionHtml(String html) {
        List<SessionPeriod> sessions = new ArrayList<>();
        DateTimeFormatter ddMMyyyy = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

        String normalized = normalizeCalendarHtml(html);
        String ascii = stripDiacritics(normalized.toLowerCase(Locale.ROOT));
        Set<String> dedup = new HashSet<>();

        collectPeriods(sessions, dedup, ascii, ddMMyyyy, PERIOD_SESSION_WINTER, "sesja\\s+zimowa");
        collectPeriods(sessions, dedup, ascii, ddMMyyyy, PERIOD_SESSION_SUMMER, "sesja\\s+letnia");
        collectPeriods(sessions, dedup, ascii, ddMMyyyy, PERIOD_SESSION_RETAKE, "sesja\\s+poprawkowa");

        collectPeriods(
                sessions,
                dedup,
                ascii,
                ddMMyyyy,
                PERIOD_BREAK_WINTER,
                "przerwa\\s+od\\s+zajec\\s+dydaktycznych\\s+w\\s+semestrze\\s+zimowym");
        collectPeriods(
                sessions,
                dedup,
                ascii,
                ddMMyyyy,
                PERIOD_BREAK_SUMMER,
                "przerwa\\s+od\\s+zajec\\s+dydaktycznych\\s+w\\s+semestrze\\s+letnim");

        boolean hasSpecificBreak = false;
        for (SessionPeriod period : sessions) {
            if (PERIOD_BREAK_WINTER.equals(period.name) || PERIOD_BREAK_SUMMER.equals(period.name)) {
                hasSpecificBreak = true;
                break;
            }
        }
        if (!hasSpecificBreak) {
            collectPeriods(
                    sessions,
                    dedup,
                    ascii,
                    ddMMyyyy,
                    PERIOD_BREAK_GENERIC,
                    "przerwa\\s+od\\s+zajec\\s+dydaktycznych");
        }

        collectPeriods(sessions, dedup, ascii, ddMMyyyy, PERIOD_HOLIDAY_WINTER, "(wakacje|ferie)\\s+zimowe");
        collectPeriods(sessions, dedup, ascii, ddMMyyyy, PERIOD_HOLIDAY_SUMMER, "wakacje\\s+letnie");

        sessions.sort(Comparator.comparing(sp -> sp.startDate));
        Log.d(TAG, "Parsed " + sessions.size() + " session periods from HTML");
        return sessions;
    }

    private String normalizeCalendarHtml(String html) {
        return html
                .replaceAll("<[^>]+>", " ")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-")
                .replace("&nbsp;", " ")
                .replace("\u00A0", " ")
                .replaceAll("\\s+", " ");
    }

    private String stripDiacritics(String input) {
        if (input == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized
                .replace('\u0142', 'l')
                .replace('\u0141', 'l')
                .replace('\u0111', 'd')
                .replace('\u0110', 'd');
    }

    private void collectPeriods(
            List<SessionPeriod> out,
            Set<String> dedup,
            String text,
            DateTimeFormatter formatter,
            String canonicalName,
            String labelRegex) {

        String pattern = labelRegex + "[^0-9]{0,60}(\\d{2}\\.\\d{2}\\.\\d{4})[^0-9]{1,30}(\\d{2}\\.\\d{2}\\.\\d{4})";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                pattern,
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(text);

        while (matcher.find()) {
            try {
                LocalDate start = LocalDate.parse(matcher.group(1), formatter);
                LocalDate end = LocalDate.parse(matcher.group(2), formatter);
                if (end.isBefore(start)) {
                    continue;
                }
                String dedupKey = canonicalName + "|" + start + "|" + end;
                if (dedup.add(dedupKey)) {
                    out.add(new SessionPeriod(canonicalName, start, end));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String sessionToJson(List<SessionPeriod> sessions) {
        try {
            JSONArray arr = new JSONArray();
            for (SessionPeriod sp : sessions) {
                JSONObject obj = new JSONObject();
                obj.put("name", sp.name);
                obj.put("start", sp.startDate.toString());
                obj.put("end", sp.endDate.toString());
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<SessionPeriod> parseSessionJson(String json) {
        List<SessionPeriod> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.getString("name");
                LocalDate start = LocalDate.parse(obj.getString("start"));
                LocalDate end = LocalDate.parse(obj.getString("end"));
                result.add(new SessionPeriod(name, start, end));
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
