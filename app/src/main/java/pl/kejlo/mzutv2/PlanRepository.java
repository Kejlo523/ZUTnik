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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    public PlanRepository() {
        // Disk cache disabled
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

    // Helpers – ZUT API

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

            String body = response.body() != null ? response.body().string() : "";
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

        if (sCachedAlbum != null && (now - sCachedAlbumTs) < ALBUM_TTL_MS) {
            return sCachedAlbum;
        }

        // Try to recover album from persisted cache (offline support)
        if (sFullPlanCache == null) {
            FullPlanCache fromDisk = readCacheFromDisk();
            if (fromDisk != null) {
                sFullPlanCache = fromDisk;
            }
        }
        if (sFullPlanCache != null && sFullPlanCache.album != null && !sFullPlanCache.album.isEmpty()) {
            sCachedAlbum = sFullPlanCache.album;
            sCachedAlbumTs = now;
            return sCachedAlbum;
        }

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

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", active.przynaleznoscId);

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
                    + " – " + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
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

    private String buildSearchUrl(SearchParams params, LocalDate start, LocalDate end)
            throws UnsupportedEncodingException {
        String queryEncoded = URLEncoder.encode(params.query, "UTF-8");

        String startStr = start.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        // End date should be inclusive of the day, so use end of day or start of next
        // day
        String endStr = end.plusDays(1).atStartOfDay().minusSeconds(1).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                .toString();

        startStr = URLEncoder.encode(startStr, "UTF-8");
        endStr = URLEncoder.encode(endStr, "UTF-8");

        String baseUrl = "https://plan.zut.edu.pl/schedule_student.php?";
        String commonParams = "&start=" + startStr + "&end=" + endStr;

        switch (params.category) {
            case "Wykładowca":
                return baseUrl + "teacher=" + queryEncoded + commonParams;
            case "Sala":
                return baseUrl + "room=" + queryEncoded + commonParams;
            case "Grupa":
                return baseUrl + "group=" + queryEncoded + commonParams;
            case "Przedmiot":
                return baseUrl + "subject=" + queryEncoded + commonParams;
            case "Numer albumu":
                return baseUrl + "number=" + queryEncoded + commonParams;
            default:
                return baseUrl + "number=" + queryEncoded + commonParams;
        }
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
                + URLEncoder.encode(kind, "UTF-8")
                + "&query=" + URLEncoder.encode(query, "UTF-8");

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "mZUTv2-Android-Plan/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return suggestions;
            }

            String body = response.body() != null ? response.body().string() : "";
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

        LocalDate filterStart = rangeStart;
        LocalDate filterEnd = rangeEnd;

        // Expand fetch range slightly to ensure timezone overlap is covered
        LocalDate apiFetchStart = rangeStart.minusDays(1);
        LocalDate apiFetchEnd = rangeEnd.plusDays(1);

        // Format used by FullCalendar (ISO-8601 with Offset)
        String startIso = apiFetchStart.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String endIso = apiFetchEnd.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();

        // Use encoded strings
        String startEnc = URLEncoder.encode(startIso, "UTF-8");
        String endEnc = URLEncoder.encode(endIso, "UTF-8");

        // Optimized: Single URL call. Standard schedule_student.php works best with ISO
        // dates.
        String url = "https://plan.zut.edu.pl/schedule_student.php?number=" + album + "&start=" + startEnc + "&end="
                + endEnc;

        JSONArray arr = null;
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
        if (arr == null) {
            return out;
        }

        String filterStartStr = filterStart.format(YMD);
        String filterEndStr = filterEnd.format(YMD);

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
        JSONArray arr = null;
        try {
            arr = httpGetJsonArray(base, debug);
        } catch (Exception e) {
            Log.w(TAG, "Error fetching full plan (" + base + "): " + e.getMessage());
            throw e;
        }

        if (arr == null)
            return new ArrayList<>();

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
        if (start == null || end == null)
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

        if ("e".equals(statusShort))
            return "week-event-type-exam";
        if ("ez".equals(statusShort))
            return "week-event-type-exam-remote";
        if ("o".equals(statusShort))
            return "week-event-type-cancelled";
        if ("r".equals(statusShort))
            return "week-event-type-rector";
        if ("dz".equals(statusShort))
            return "week-event-type-dean";
        if ("zz".equals(statusShort))
            return "week-event-type-remote";

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
            if ("l".equals(formShortLower))
                typeKey = "lab";
            else if ("a".equals(formShortLower))
                typeKey = "aud";
            else if ("w".equals(formShortLower))
                typeKey = "lec";

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
            int sA = (int) a.get("startMin");
            int sB = (int) b.get("startMin");
            if (sA == sB) {
                return Integer.compare((int) a.get("endMin"), (int) b.get("endMin"));
            }
            return Integer.compare(sA, sB);
        });

        // Layout algorithm (simple greedy with clusters)
        List<List<Map<String, Object>>> clusters = new ArrayList<>();
        List<Map<String, Object>> currentCluster = new ArrayList<>();
        Integer clusterEnd = null;

        for (Map<String, Object> ev : events) {
            int startMin = (int) ev.get("startMin");
            int endMin = (int) ev.get("endMin");

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
                int startMin = (int) ev.get("startMin");
                int endMin = (int) ev.get("endMin");
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
                int laneIdx = (int) ev.get("lane");
                ev.put("leftPct", laneIdx * laneWidth);
                ev.put("widthPct", laneWidth);
                ev.remove("lane");
                finalEvents.add(ev);
            }
        }

        for (Map<String, Object> ev : finalEvents) {
            PlanEventUi ui = new PlanEventUi();
            ui.startMin = (int) ev.get("startMin");
            ui.endMin = (int) ev.get("endMin");
            ui.topPx = (float) ev.get("topPx");
            ui.heightPx = (float) ev.get("heightPx");
            ui.leftPct = (float) ev.get("leftPct");
            ui.widthPct = (float) ev.get("widthPct");
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
            if (c != null)
                any = true;
        if (any)
            grid.add(new ArrayList<>(week));
        return grid;
    }

    // Cache management

    private String buildScopeKey(String viewMode, LocalDate rangeStart, LocalDate rangeEnd) {
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

            String scopeKey = buildScopeKey(viewMode, rangeStart, rangeEnd);
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
                    if (fresh != null) {
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
                    }
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
            r.headerLabel = weekStart.format(DateTimeFormatter.ofPattern("dd.MM")) + " – "
                    + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
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
    public List<PlanEventUi> loadUpcomingEvents(int daysCount) throws IOException, JSONException {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(daysCount);
        // Reuse loadPlanInternal with a special "widget" view mode if we wanted, but
        // "week" logic is fine or custom range.
        // Actually loadPlanInternal uses fixed view modes. Let's just use
        // ensureScopeData directly.

        String album = resolveAlbumNumber();
        if (album == null)
            return Collections.emptyList();

        // Ensure data for today+7 days is in cache
        Map<LocalDate, List<PlanEventRaw>> data = ensureScopeData(album, today, end, "widget", false, new PlanDebug());

        List<PlanEventUi> result = new ArrayList<>();
        LocalDate iter = today;
        while (!iter.isAfter(end)) {
            List<PlanEventRaw> raw = data.get(iter);
            if (raw != null && !raw.isEmpty()) {
                // We must add Date to the event payload for the widget service to know which
                // day it is,
                // but PlanEventUi doesn't strictly have a "date" field (it's in DayColumn).
                // However, the Widget Service iterates days.
                // Or we can flatten it.
                // The widget currently calls loadPlan("day", date) one by one.
                // That's inefficient.
                // But for now let's leave this API here for the Widget to use if we refactor
                // the widget to bulk-load.
                // If the widget calls loadPlan("day", date), it will hit the cache we just
                // populated!
                // So calling ensureScopeData here effectively pre-fetches the week.
            }
            iter = iter.plusDays(1);
        }
        return result; // Not really used yet, but the side effect of ensureScopeData is what we want.
    }

    public List<SubjectFilterItem> loadSubjectsForFilter() throws IOException, JSONException {
        // Optimized: Scan the CACHE instead of calling N+1 API endpoints.

        String album = resolveAlbumNumber();
        if (album == null)
            return Collections.emptyList();

        // Ensure we have some data? No, we can only filter what we have.
        // If cache is empty, we might want to trigger a full refresh or just return
        // empty.
        // Returning empty is safer than blocking for 10 seconds to download everything.

        synchronized (PlanRepository.class) {
            if (sFullPlanCache == null) {
                readCacheFromDisk();
            }
            if (sFullPlanCache == null || sFullPlanCache.byDate == null) {
                return Collections.emptyList();
            }

            Map<String, Map<String, String>> subjectsAll = new TreeMap<>();

            for (List<PlanEventRaw> events : sFullPlanCache.byDate.values()) {
                if (events == null)
                    continue;
                for (PlanEventRaw e : events) {
                    String subject = (e.subject != null ? e.subject : e.title).trim();
                    if (subject.isEmpty())
                        continue;

                    String form = (e.lessonForm != null ? e.lessonForm : "").toLowerCase(Locale.ROOT);
                    String typeKey = null;
                    if (form.contains("laboratorium"))
                        typeKey = "lab";
                    else if (form.contains("audytoryjne"))
                        typeKey = "aud";
                    else if (form.contains("wykład"))
                        typeKey = "lec";

                    if (typeKey == null)
                        continue;

                    subjectsAll.putIfAbsent(subject, new HashMap<>());
                    Map<String, String> types = subjectsAll.get(subject);
                    String filterKey = subject + "||" + typeKey;
                    types.put(typeKey, filterKey);
                }
            }

            List<SubjectFilterItem> items = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> entry : subjectsAll.entrySet()) {
                String subject = entry.getKey();
                Map<String, String> types = entry.getValue();

                if (types.containsKey("lec")) {
                    SubjectFilterItem it = new SubjectFilterItem();
                    it.label = subject;
                    it.typeLabel = "Wykład";
                    it.filterKey = types.get("lec");
                    items.add(it);
                }
                if (types.containsKey("aud")) {
                    SubjectFilterItem it = new SubjectFilterItem();
                    it.label = subject;
                    it.typeLabel = "Audytoryjne";
                    it.filterKey = types.get("aud");
                    items.add(it);
                }
                if (types.containsKey("lab")) {
                    SubjectFilterItem it = new SubjectFilterItem();
                    it.label = subject;
                    it.typeLabel = "Laboratorium";
                    it.filterKey = types.get("lab");
                    items.add(it);
                }
            }
            return items;
        }
    }

    /**
     * Merges custom events (exams, tests) with official plan events for a given
     * day.
     * - If a custom event matches an official event (same subject on same day), add
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
            String subjectLower = customEvent.subjectName != null ? customEvent.subjectName.toLowerCase() : "";

            // Check if any official event matches this custom event (by subject name AND
            // type)
            // Exam (egzamin) matches only lectures (W), Pass/Test matches labs/exercises
            for (PlanEventUi event : result) {
                if (event.title != null && event.title.toLowerCase().contains(subjectLower)) {
                    // Check if type matches
                    boolean typeMatches = false;
                    String typeClass = event.typeClass != null ? event.typeClass.toLowerCase() : "";

                    if (CustomPlanEvent.TYPE_EXAM.equals(customEvent.eventType)) {
                        // Exam only matches lectures: typeClass contains "lec" or title ends with "(W)"
                        typeMatches = typeClass.contains("lec") ||
                                (event.title != null && event.title.trim().endsWith("(W)"));
                    } else {
                        // Pass/Test matches exercises, labs, auditorium: not lecture
                        typeMatches = !typeClass.contains("lec") &&
                                (event.title == null || !event.title.trim().endsWith("(W)"));
                    }

                    if (typeMatches) {
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
}