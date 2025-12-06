package pl.kejlo.mzutv2;

import android.content.Context;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import okhttp3.Request;
import okhttp3.Response;

public class PlanRepository {

    private static final String TAG = "mZUTv2-PLAN";

    // Disk cache file
    private static final String CACHE_FILE_NAME = "plan_cache_v1.json";

    // Scope TTL: 1h
    private static final long SCOPE_CACHE_TTL_MS = 60L * 60L * 1000L;

    // Global context (null disables file cache)
    private static Context appContext;

    // In-memory cache structure
    private static class FullPlanCache {
        String album;
        long timestampMs;
        Map<LocalDate, List<PlanEventRaw>> byDate = new HashMap<>();
        // Scope refresh timestamps
        Map<String, Long> scopeTimestamps = new HashMap<>();
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

    private static final DateTimeFormatter ISO_LOCAL_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final DateTimeFormatter HOUR_MIN =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String sCachedAlbum;
    private static long sCachedAlbumTs;
    private static final long ALBUM_TTL_MS = 24L * 60L * 60L * 1000L; // 24h

    private static String fmtPlDate(LocalDate date) {
        // Use Resources if context available to avoid hardcoding strings
        String dz;
        if (appContext != null) {
            switch (date.getDayOfWeek()) {
                case MONDAY: dz = appContext.getString(R.string.plan_header_mon_short); break;
                case TUESDAY: dz = appContext.getString(R.string.plan_header_tue_short); break;
                case WEDNESDAY: dz = appContext.getString(R.string.plan_header_wed_short); break;
                case THURSDAY: dz = appContext.getString(R.string.plan_header_thu_short); break;
                case FRIDAY: dz = appContext.getString(R.string.plan_header_fri_short); break;
                case SATURDAY: dz = appContext.getString(R.string.plan_header_sat_short); break;
                case SUNDAY: default: dz = appContext.getString(R.string.plan_header_sun_short); break;
            }
        } else {
            // Fallback if context is missing (should not happen in normal usage)
            String[] dniPl = {"Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So"};
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
            OffsetDateTime odt = OffsetDateTime.parse(iso);
            return odt.toLocalDateTime();
        } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(iso, ISO_LOCAL_DT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse date: " + iso, e);
            return null;
        }
    }

    private static int minutesFromMidnight(LocalDateTime dt) {
        return dt.getHour() * 60 + dt.getMinute();
    }

    // Helpers – ZUT API

    // Simple GET to plan.zut replaced with OkHttp for custom SSL support
    private JSONArray httpGetJsonArray(String urlStr, PlanDebug debug) throws IOException, JSONException {
        PlanDebug.RequestDebug rd = new PlanDebug.RequestDebug();
        rd.url = urlStr;

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

    // Resolve album number (Active study)
    private String resolveAlbumNumber() throws IOException, JSONException {
        long now = System.currentTimeMillis();

        if (sCachedAlbum != null && (now - sCachedAlbumTs) < ALBUM_TTL_MS) {
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

    // --- Search Functionality ---

    public PlanResult searchPlan(String viewMode, LocalDate currentDate, SearchParams search) throws IOException, JSONException {
        if (currentDate == null) currentDate = LocalDate.now();
        if (viewMode == null) viewMode = "week";

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
            if (currentDate.equals(r.today) && currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                weekStart = currentDate.plusDays(1);
            } else {
                weekStart = currentDate;
                int dow = weekStart.getDayOfWeek().getValue();
                if (dow > 1) weekStart = weekStart.minusDays(dow - 1);
            }
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
        r.debug.requests.add(new PlanDebug.RequestDebug());
        r.debug.requests.get(0).url = url;

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
                    if (ev != null) rawEvents.add(ev);
                }
            }
        }

        Map<LocalDate, List<PlanEventRaw>> byDate = groupByDay(rawEvents);

        if ("day".equals(viewMode) || "week".equals(viewMode)) {
            LocalDate iter = rangeStart;
            boolean any = false;
            while (!iter.isAfter(rangeEnd)) {
                DayColumn col = new DayColumn();
                col.date = iter;
                List<PlanEventRaw> dailyRaw = byDate.getOrDefault(iter, Collections.emptyList());
                col.events = buildDayLayout(dailyRaw);
                if (!col.events.isEmpty()) any = true;
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

    private String buildSearchUrl(SearchParams params, LocalDate start, LocalDate end) throws UnsupportedEncodingException {
        String queryEncoded = URLEncoder.encode(params.query, "UTF-8");

        // Format required by schedule_student.php
        String startStr = start.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String endStr = end.plusDays(1).atStartOfDay().minusSeconds(1).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();

        startStr = URLEncoder.encode(startStr, "UTF-8");
        endStr = URLEncoder.encode(endStr, "UTF-8");

        String baseUrl = "https://plan.zut.edu.pl/schedule_student.php?";
        String commonParams = "&start=" + startStr + "&end=" + endStr;

        // Note: All requests go through schedule_student.php
        // These are Internal Keys, not Display Strings
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

    // --- End Search ---

    // Fetch range
    private List<PlanEventRaw> fetchPlanRangeByAlbum(
            String album,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            PlanDebug debug
    ) throws IOException, JSONException {

        LocalDate filterStart = rangeStart;
        LocalDate filterEnd = rangeEnd;

        LocalDateTime apiStart;
        LocalDateTime apiEnd;
        if (rangeStart.equals(rangeEnd)) {
            apiStart = rangeStart.atStartOfDay();
            apiEnd = rangeEnd.plusDays(1).atTime(23, 59, 59);
        } else {
            apiStart = filterStart.atStartOfDay();
            apiEnd = filterEnd.plusDays(1).atTime(23, 59, 59);
        }

        String startA = apiStart.toLocalDate().format(YMD) + "T00:00:00";
        String endA = apiEnd.toLocalDate().format(YMD) + "T23:59:59";
        String startB = apiStart.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String endB = apiEnd.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();

        String base = "https://plan.zut.edu.pl/schedule_student.php?number=" + album;

        List<String> urls = Arrays.asList(
                base + "&start=" + startA + "&end=" + endA,
                base + "&start=" + startB + "&end=" + endB,
                base
        );

        JSONArray arr = null;
        for (String u : urls) {
            try {
                arr = httpGetJsonArray(u, debug);
                if (arr != null && arr.length() > 0) {
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error fetching plan.zut (" + u + "): " + e.getMessage());
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
            if (e == null) {
                continue;
            }

            PlanEventRaw r = parsePlanEventRaw(e);
            if (r == null) {
                continue;
            }

            String eventDateStr;
            if (r.start != null && r.start.length() >= 10) {
                eventDateStr = r.start.substring(0, 10);
            } else {
                LocalDateTime dt = parseIsoLocal(r.start);
                if (dt == null) {
                    continue;
                }
                eventDateStr = dt.toLocalDate().format(YMD);
            }

            if (eventDateStr.compareTo(filterStartStr) < 0
                    || eventDateStr.compareTo(filterEndStr) > 0) {
                continue;
            }

            out.add(r);
        }

        return out;
    }

    // Fetch full plan
    private List<PlanEventRaw> fetchFullPlanByAlbum(
            String album,
            PlanDebug debug
    ) throws IOException, JSONException {

        String base = "https://plan.zut.edu.pl/schedule_student.php?number=" + album;

        JSONArray arr = null;
        try {
            arr = httpGetJsonArray(base, debug);
        } catch (Exception e) {
            Log.w(TAG, "Error fetching full plan (" + base + "): " + e.getMessage());
        }

        if (arr == null) {
            return new ArrayList<>();
        }

        List<PlanEventRaw> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }
            PlanEventRaw r = parsePlanEventRaw(e);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private PlanEventRaw parsePlanEventRaw(JSONObject e) {
        if (e == null) {
            return null;
        }

        String start = e.optString("start", null);
        String end = e.optString("end", null);
        if (start == null || end == null) {
            return null;
        }

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

    // Event Classification

    private String eventTypeClass(PlanEventRaw e) {
        String statusShort = lower(e.lessonStatusShort);
        String formFull = lower(e.lessonForm);
        String formShort = lower(e.lessonFormShort);
        String subject = lower(e.subject != null && !e.subject.isEmpty() ? e.subject : e.title);

        if ("e".equals(statusShort)) return "week-event-type-exam";
        if ("ez".equals(statusShort)) return "week-event-type-exam-remote";
        if ("o".equals(statusShort)) return "week-event-type-cancelled";
        if ("r".equals(statusShort)) return "week-event-type-rector";
        if ("dz".equals(statusShort)) return "week-event-type-dean";
        if ("zz".equals(statusShort)) return "week-event-type-remote";

        String hay = formFull + " " + subject;

        if (hay.contains("egzamin zdalny")) return "week-event-type-exam-remote";
        if (hay.contains("egzamin")) return "week-event-type-exam";
        if (hay.contains("odwołane")) return "week-event-type-cancelled";
        if (hay.contains("rektorskie")) return "week-event-type-rector";
        if (hay.contains("dziekańskie") || hay.contains("godziny dziekańskie")) return "week-event-type-dean";

        if (hay.contains("zajęcia zdalne") || hay.contains("zdalne")) {
            return "week-event-type-remote";
        }

        if (hay.contains("zaliczenie zdalne poprawkowe") || "zalzdp".equals(formShort)) {
            return "week-event-type-pass-remote-retake";
        }
        if (hay.contains("zaliczenie zdalne") || "zalzd".equals(formShort)) {
            return "week-event-type-pass-remote";
        }
        if (hay.contains("zaliczenie poprawkow") || "zalp".equals(formShort)) {
            return "week-event-type-pass-retake";
        }
        if (hay.contains("zaliczenie") || "zal".equals(formShort)) {
            return "week-event-type-pass";
        }

        if (hay.contains("seminarium dyplomowe") || "sd".equals(formShort)) return "week-event-type-diploma-seminar";
        if (hay.contains("seminarium") || "s".equals(formShort)) return "week-event-type-seminar";
        if (hay.contains("praca dyplomowa") || "pd".equals(formShort)) return "week-event-type-diploma";
        if (hay.contains("projekt") || "p".equals(formShort)) return "week-event-type-project";
        if (hay.contains("lektorat") || "lek".equals(formShort)) return "week-event-type-lectorate";
        if (hay.contains("konserwatorium") || "k".equals(formShort)) return "week-event-type-conservatory";
        if (hay.contains("konsultacje") || "kons".equals(formShort)) return "week-event-type-consultation";
        if (hay.contains("terenowe") || "t".equals(formShort)) return "week-event-type-field";

        if (hay.contains("laboratorium") || "l".equals(formShort)) {
            return "week-event-type-lab";
        }
        if (hay.contains("audytoryjne") || "a".equals(formShort)) {
            return "week-event-type-auditory";
        }
        if (hay.contains("wykład") || "w".equals(formShort)) {
            return "week-event-type-lecture";
        }

        if ("z".equals(formShort)) return "week-event-type-class";

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
                if (!form.isEmpty()) {
                    return form;
                }
                return "";
        }
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
            if (dtStart == null) {
                continue;
            }
            LocalDate d = dtStart.toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(e);
        }

        for (List<PlanEventRaw> list : byDate.values()) {
            list.sort((a, b) -> {
                LocalDateTime da = parseIsoLocal(a.start);
                LocalDateTime db = parseIsoLocal(b.start);
                if (da == null || db == null) {
                    return 0;
                }
                return da.compareTo(db);
            });
        }

        return byDate;
    }

    // Build UI layout for a single day
    private List<PlanEventUi> buildDayLayout(List<PlanEventRaw> list) {
        List<PlanEventUi> result = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return result;
        }

        List<Map<String, Object>> events = new ArrayList<>();

        for (PlanEventRaw e : list) {
            LocalDateTime dtS = parseIsoLocal(e.start);
            LocalDateTime dtE = parseIsoLocal(e.end);
            if (dtS == null || dtE == null) {
                continue;
            }

            int startMin = minutesFromMidnight(dtS);
            int endMin = minutesFromMidnight(dtE);

            int calStart = START_HOUR * 60;
            int calEnd = END_HOUR * 60;

            if (endMin <= calStart || startMin >= calEnd) {
                continue;
            }

            int startClamped = Math.max(startMin, calStart);
            int endClamped = Math.min(endMin, calEnd);
            int duration = Math.max(endClamped - startClamped, 15);

            int offsetMin = startClamped - calStart;
            float topPx = (offsetMin / 60f) * HOUR_HEIGHT_PX;
            float heightPx = (duration / 60f) * HOUR_HEIGHT_PX;
            if (heightPx < 22) {
                heightPx = 22;
            }

            String subjectName = (e.subject != null && !e.subject.isEmpty())
                    ? e.subject
                    : (e.title != null ? e.title : "");

            String formShort = e.lessonFormShort != null ? e.lessonFormShort.trim() : "";
            String fullTitle = subjectName;
            if (!subjectName.isEmpty() && !formShort.isEmpty()) {
                fullTitle += " (" + formShort + ")";
            }

            String room = e.room != null ? e.room : "";
            String group = e.groupName != null ? e.groupName : "";
            String teacher = (e.workerTitle != null && !e.workerTitle.isEmpty())
                    ? e.workerTitle
                    : (e.worker != null ? e.worker : "");

            String startStr = dtS.format(HOUR_MIN);
            String endStr = dtE.format(HOUR_MIN);

            String tooltip = fullTitle
                    + " | " + startStr + " - " + endStr
                    + (room.isEmpty() ? "" : " | sala: " + room)
                    + (group.isEmpty() ? "" : " | grupa: " + group)
                    + (teacher.isEmpty() ? "" : " | " + teacher);

            String formShortLower = lower(formShort);
            String typeKey = null;
            if ("l".equals(formShortLower)) {
                typeKey = "lab";
            } else if ("a".equals(formShortLower)) {
                typeKey = "aud";
            } else if ("w".equals(formShortLower)) {
                typeKey = "lec";
            }

            String subjectKey = "";
            if (!subjectName.isEmpty() && typeKey != null) {
                subjectKey = subjectName + "||" + typeKey;
            }

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
                int eA = (int) a.get("endMin");
                int eB = (int) b.get("endMin");
                return Integer.compare(eA, eB);
            }
            return Integer.compare(sA, sB);
        });

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
                if (endMin > clusterEnd) {
                    clusterEnd = endMin;
                }
            } else {
                clusters.add(currentCluster);
                currentCluster = new ArrayList<>();
                currentCluster.add(ev);
                clusterEnd = endMin;
            }
        }
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        List<Map<String, Object>> finalEvents = new ArrayList<>();

        for (List<Map<String, Object>> cluster : clusters) {
            List<Integer> lanes = new ArrayList<>();

            for (Map<String, Object> ev : cluster) {
                int startMin = (int) ev.get("startMin");
                int endMin = (int) ev.get("endMin");

                boolean assigned = false;
                for (int laneIdx = 0; laneIdx < lanes.size(); laneIdx++) {
                    int laneEnd = lanes.get(laneIdx);
                    if (startMin >= laneEnd) {
                        lanes.set(laneIdx, endMin);
                        ev.put("lane", laneIdx);
                        assigned = true;
                        break;
                    }
                }
                if (!assigned) {
                    int laneIdx = lanes.size();
                    lanes.add(endMin);
                    ev.put("lane", laneIdx);
                }
            }

            int laneCount = Math.max(1, lanes.size());
            float laneWidth = 100f / laneCount;

            for (Map<String, Object> ev : cluster) {
                int laneIdx = (int) ev.get("lane");
                float leftPct = laneIdx * laneWidth;
                float widthPct = laneWidth;

                ev.put("leftPct", leftPct);
                ev.put("widthPct", widthPct);
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

    // Month grid
    private List<List<MonthCell>> buildMonthGrid(LocalDate monthDate, Set<LocalDate> daysWithPlan) {
        List<List<MonthCell>> grid = new ArrayList<>();

        LocalDate monthStart = monthDate.withDayOfMonth(1);
        LocalDate monthEnd = monthDate.withDayOfMonth(monthDate.lengthOfMonth());

        LocalDate cursor = monthStart;
        int firstDow = cursor.getDayOfWeek().getValue();
        int col = firstDow - 1;

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
        for (MonthCell c : week) {
            if (c != null) {
                any = true;
                break;
            }
        }
        if (any) {
            grid.add(new ArrayList<>(week));
        }

        return grid;
    }

    // Cache management

    private String buildScopeKey(String viewMode, LocalDate rangeStart, LocalDate rangeEnd) {
        if ("day".equals(viewMode)) {
            return "day:" + rangeStart.format(YMD);
        } else if ("week".equals(viewMode)) {
            return "week:" + rangeStart.format(YMD);
        } else {
            LocalDate m = rangeStart.withDayOfMonth(1);
            return "month:" + m.format(YMD);
        }
    }

    private Map<LocalDate, List<PlanEventRaw>> ensureScopeData(
            String album,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            String viewMode,
            boolean forceScopeRefresh,
            PlanDebug debug
    ) throws IOException, JSONException {

        long now = System.currentTimeMillis();

        synchronized (PlanRepository.class) {

            if (sFullPlanCache == null || album == null || !album.equals(sFullPlanCache.album)) {
                FullPlanCache fromDisk = readCacheFromDisk();
                if (fromDisk != null && album != null && album.equals(fromDisk.album)) {
                    if (fromDisk.scopeTimestamps == null) {
                        fromDisk.scopeTimestamps = new HashMap<>();
                    }
                    sFullPlanCache = fromDisk;
                } else {
                    FullPlanCache newCache = new FullPlanCache();
                    newCache.album = album;
                    newCache.timestampMs = now;
                    newCache.byDate = new HashMap<>();
                    newCache.scopeTimestamps = new HashMap<>();
                    sFullPlanCache = newCache;
                }
            }

            if (sFullPlanCache.scopeTimestamps == null) {
                sFullPlanCache.scopeTimestamps = new HashMap<>();
            }

            String scopeKey = buildScopeKey(viewMode, rangeStart, rangeEnd);
            Long lastScopeMs = sFullPlanCache.scopeTimestamps.get(scopeKey);

            boolean needRefresh;
            if (forceScopeRefresh) {
                needRefresh = true;
            } else {
                needRefresh = (lastScopeMs == null) || ((now - lastScopeMs) > SCOPE_CACHE_TTL_MS);
            }

            if (needRefresh) {
                List<PlanEventRaw> fresh = fetchPlanRangeByAlbum(album, rangeStart, rangeEnd, debug);

                // If API returns empty, preserve cache
                if (fresh == null || fresh.isEmpty()) {
                    Log.w(TAG, "No data from plan.zut for range "
                            + rangeStart + " - " + rangeEnd + " (view=" + viewMode + "). "
                            + "Keeping cache.");
                    return sFullPlanCache.byDate;
                }

                Map<LocalDate, List<PlanEventRaw>> tmp = groupByDay(fresh);

                LocalDate d = rangeStart;
                while (!d.isAfter(rangeEnd)) {
                    List<PlanEventRaw> list = tmp.get(d);
                    if (list != null && !list.isEmpty()) {
                        sFullPlanCache.byDate.put(d, list);
                    }
                    d = d.plusDays(1);
                }

                sFullPlanCache.scopeTimestamps.put(scopeKey, now);
                sFullPlanCache.timestampMs = now;

                writeCacheToDisk(sFullPlanCache);
            }

            return sFullPlanCache.byDate;
        }
    }

    private FullPlanCache readCacheFromDisk() {
        if (appContext == null) {
            return null;
        }

        FileInputStream fis = null;
        try {
            fis = appContext.openFileInput(CACHE_FILE_NAME);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            FullPlanCache cache = new FullPlanCache();
            cache.album = root.optString("album", null);
            cache.timestampMs = root.optLong("timestamp", 0L);

            JSONObject eventsByDate = root.optJSONObject("eventsByDate");
            if (eventsByDate == null) {
                return null;
            }

            Map<LocalDate, List<PlanEventRaw>> map = new HashMap<>();
            Iterator<String> keys = eventsByDate.keys();
            while (keys.hasNext()) {
                String dateStr = keys.next();
                JSONArray arr = eventsByDate.optJSONArray(dateStr);
                if (arr == null) {
                    continue;
                }

                LocalDate d;
                try {
                    d = LocalDate.parse(dateStr, YMD);
                } catch (Exception ex) {
                    continue;
                }

                List<PlanEventRaw> dayList = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    PlanEventRaw r = parsePlanEventRaw(obj);
                    if (r != null) {
                        dayList.add(r);
                    }
                }
                map.put(d, dayList);
            }

            cache.byDate = map;

            JSONObject scopesJson = root.optJSONObject("scopeTimestamps");
            Map<String, Long> scopesMap = new HashMap<>();
            if (scopesJson != null) {
                Iterator<String> it2 = scopesJson.keys();
                while (it2.hasNext()) {
                    String key = it2.next();
                    long ts = scopesJson.optLong(key, 0L);
                    if (ts > 0L) {
                        scopesMap.put(key, ts);
                    }
                }
            }
            cache.scopeTimestamps = scopesMap;

            return cache;

        } catch (Exception e) {
            Log.w(TAG, "Failed to read cache: " + e.getMessage());
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignore) {}
            }
        }
    }

    private void writeCacheToDisk(FullPlanCache cache) {
        if (appContext == null || cache == null) {
            return;
        }

        FileOutputStream fos = null;
        try {
            JSONObject root = new JSONObject();
            root.put("album", cache.album);
            root.put("timestamp", cache.timestampMs);

            JSONObject eventsByDate = new JSONObject();
            for (Map.Entry<LocalDate, List<PlanEventRaw>> entry : cache.byDate.entrySet()) {
                LocalDate date = entry.getKey();
                List<PlanEventRaw> list = entry.getValue();
                JSONArray arr = new JSONArray();
                if (list != null) {
                    for (PlanEventRaw r : list) {
                        JSONObject obj = new JSONObject();
                        obj.put("title", r.title != null ? r.title : "");
                        obj.put("description", r.description != null ? r.description : "");
                        obj.put("start", r.start != null ? r.start : "");
                        obj.put("end", r.end != null ? r.end : "");
                        obj.put("worker_title", r.workerTitle != null ? r.workerTitle : "");
                        obj.put("worker", r.worker != null ? r.worker : "");
                        obj.put("lesson_form", r.lessonForm != null ? r.lessonForm : "");
                        obj.put("lesson_form_short", r.lessonFormShort != null ? r.lessonFormShort : "");
                        obj.put("group_name", r.groupName != null ? r.groupName : "");
                        obj.put("tok_name", r.tokName != null ? r.tokName : "");
                        obj.put("room", r.room != null ? r.room : "");
                        obj.put("lesson_status", r.lessonStatus != null ? r.lessonStatus : "");
                        obj.put("lesson_status_short", r.lessonStatusShort != null ? r.lessonStatusShort : "");
                        obj.put("subject", r.subject != null ? r.subject : "");
                        obj.put("hours", r.hours != null ? r.hours : "");
                        obj.put("color", r.color != null ? r.color : "");
                        obj.put("borderColor", r.borderColor != null ? r.borderColor : "");
                        arr.put(obj);
                    }
                }
                eventsByDate.put(date.format(YMD), arr);
            }
            root.put("eventsByDate", eventsByDate);

            JSONObject scopesJson = new JSONObject();
            if (cache.scopeTimestamps != null) {
                for (Map.Entry<String, Long> e : cache.scopeTimestamps.entrySet()) {
                    scopesJson.put(e.getKey(), e.getValue());
                }
            }
            root.put("scopeTimestamps", scopesJson);

            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            fos = appContext.openFileOutput(CACHE_FILE_NAME, Context.MODE_PRIVATE);
            fos.write(bytes);
            fos.flush();

        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache: " + e.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignore) {}
            }
        }
    }

    // Public API

    // Load plan (cached or fresh based on TTL)
    public PlanResult loadPlan(String viewMode, LocalDate currentDate) throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, false, false);
    }

    // Load plan (optional full refresh)
    public PlanResult loadPlan(String viewMode, LocalDate currentDate, boolean forceFullRefresh) throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, forceFullRefresh, false);
    }

    // Reload scope only
    public PlanResult reloadScope(String viewMode, LocalDate currentDate) throws IOException, JSONException {
        return loadPlanInternal(viewMode, currentDate, false, true);
    }

    // Internal load logic
    private PlanResult loadPlanInternal(String viewMode,
                                        LocalDate currentDate,
                                        boolean forceFullRefresh,
                                        boolean forceScopeRefresh) throws IOException, JSONException {

        if (currentDate == null) {
            currentDate = LocalDate.now();
        }
        if (!"day".equals(viewMode) && !"week".equals(viewMode) && !"month".equals(viewMode)) {
            viewMode = "week";
        }

        PlanResult r = new PlanResult();
        r.viewMode = viewMode;
        r.current = currentDate;

        LocalDate today = LocalDate.now();
        r.today = today;

        String album = resolveAlbumNumber();
        r.debug.album = album;
        r.debug.view = viewMode;

        if (album == null) {
            return r;
        }

        LocalDate rangeStart = currentDate;
        LocalDate rangeEnd = currentDate;

        if ("day".equals(viewMode)) {
            r.headerLabel = fmtPlDate(currentDate);

        } else if ("week".equals(viewMode)) {
            LocalDate weekStart;

            if (currentDate.equals(today) && currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                weekStart = currentDate.plusDays(1);
            } else {
                weekStart = currentDate;
                int dow = weekStart.getDayOfWeek().getValue();
                if (dow > 1) {
                    weekStart = weekStart.minusDays(dow - 1);
                }
            }

            LocalDate weekEnd = weekStart.plusDays(6);
            rangeStart = weekStart;
            rangeEnd = weekEnd;
            r.headerLabel = weekStart.format(DateTimeFormatter.ofPattern("dd.MM"))
                    + " – " + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

        } else {
            LocalDate monthStart = currentDate.withDayOfMonth(1);
            LocalDate monthEnd = currentDate.withDayOfMonth(currentDate.lengthOfMonth());
            rangeStart = monthStart;
            rangeEnd = monthEnd;
            r.headerLabel = monthStart.getMonth().name() + " " + monthStart.getYear();
        }

        r.rangeStart = rangeStart;
        r.rangeEnd = rangeEnd;
        r.debug.rangeStart = rangeStart.format(YMD);
        r.debug.rangeEnd = rangeEnd.format(YMD);

        Map<LocalDate, List<PlanEventRaw>> byDate;

        if (forceFullRefresh) {
            long now = System.currentTimeMillis();

            List<PlanEventRaw> allEvents = fetchFullPlanByAlbum(album, r.debug);
            Map<LocalDate, List<PlanEventRaw>> grouped = groupByDay(allEvents);

            synchronized (PlanRepository.class) {
                FullPlanCache newCache = new FullPlanCache();
                newCache.album = album;
                newCache.timestampMs = now;
                newCache.byDate = grouped;
                newCache.scopeTimestamps = new HashMap<>();
                sFullPlanCache = newCache;
                writeCacheToDisk(newCache);
            }

            byDate = grouped;

        } else {
            byDate = ensureScopeData(album, rangeStart, rangeEnd, viewMode, forceScopeRefresh, r.debug);
        }

        List<PlanEventRaw> entries = new ArrayList<>();
        LocalDate iterDate = rangeStart;
        while (!iterDate.isAfter(rangeEnd)) {
            List<PlanEventRaw> dayList = byDate.get(iterDate);
            if (dayList != null && !dayList.isEmpty()) {
                entries.addAll(dayList);
            }
            iterDate = iterDate.plusDays(1);
        }

        r.debug.entriesTotal = entries.size();

        Map<LocalDate, List<PlanEventRaw>> byDateRange = groupByDay(entries);
        List<String> daysWithDataStr = new ArrayList<>();
        for (LocalDate d : byDateRange.keySet()) {
            daysWithDataStr.add(d.format(YMD));
        }
        Collections.sort(daysWithDataStr);
        r.debug.daysWithData = daysWithDataStr;

        if ("day".equals(viewMode) || "week".equals(viewMode)) {
            List<LocalDate> days = new ArrayList<>();
            LocalDate iter = rangeStart;
            while (!iter.isAfter(rangeEnd)) {
                days.add(iter);
                iter = iter.plusDays(1);
            }

            boolean any = false;
            for (LocalDate d : days) {
                DayColumn col = new DayColumn();
                col.date = d;
                List<PlanEventRaw> rawList = byDateRange.getOrDefault(d, Collections.emptyList());
                List<PlanEventUi> uiList = buildDayLayout(rawList);
                if (!uiList.isEmpty()) {
                    any = true;
                }
                col.events = uiList;
                r.dayColumns.add(col);
            }
            r.hasAnyEventsInRange = any;

        } else {
            Set<LocalDate> daysWithPlan = new HashSet<>(byDateRange.keySet());
            r.monthGrid = buildMonthGrid(currentDate, daysWithPlan);
        }

        LocalDate prev = currentDate;
        LocalDate next = currentDate;
        if ("day".equals(viewMode)) {
            prev = currentDate.minusDays(1);
            next = currentDate.plusDays(1);
        } else if ("week".equals(viewMode)) {
            prev = currentDate.minusWeeks(1);
            next = currentDate.plusWeeks(1);
        } else {
            prev = currentDate.minusMonths(1);
            next = currentDate.plusMonths(1);
        }
        r.prev = prev;
        r.next = next;

        return r;
    }

    public List<SubjectFilterItem> loadSubjectsForFilter() throws IOException, JSONException {
        String album = resolveAlbumNumber();
        if (album == null) {
            return Collections.emptyList();
        }

        PlanDebug debug = new PlanDebug();

        // Logic matches PHP backend
        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        String authKey = s.getAuthKey();
        if (userId == null || authKey == null) {
            return Collections.emptyList();
        }

        // Step 1: getPlan
        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        JSONObject daysResp = MzutApi.callApi("getPlan", params);
        if (daysResp == null || !daysResp.has("Plan")) {
            return Collections.emptyList();
        }

        Object block = daysResp.get("Plan");
        JSONArray arr;
        if (block instanceof JSONArray) {
            arr = (JSONArray) block;
        } else {
            arr = new JSONArray();
            arr.put(block);
        }

        Map<String, String> daysList = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            String data = row.optString("dataZajec", "");
            if (data.isEmpty()) {
                continue;
            }

            String dataClean = data.replace('.', '-');
            LocalDate d = null;

            try {
                if (dataClean.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    d = LocalDate.parse(dataClean);
                } else {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                    d = LocalDate.parse(dataClean, f);
                }
            } catch (Exception ignore) {}

            if (d == null) {
                continue;
            }

            String dateStr = d.format(YMD);
            String dayKey = data.replace(".", "");
            dayKey = dayKey.replace("-", "");
            daysList.put(dateStr, dayKey);
        }

        Map<String, Map<String, String>> subjectsAll = new TreeMap<>();

        // Step 2: fetch individual days
        for (String dateStr : daysList.keySet()) {
            String dayKey = daysList.get(dateStr);
            HashMap<String, String> paramsDay = new HashMap<>();
            paramsDay.put("login", userId);
            paramsDay.put("token", authKey);
            paramsDay.put("day", dayKey);

            JSONObject planResp = MzutApi.callApi("getPlan", paramsDay);
            if (planResp == null || !planResp.has("Plan")) {
                continue;
            }

            Object block2 = planResp.get("Plan");
            JSONArray arr2;
            if (block2 instanceof JSONArray) {
                arr2 = (JSONArray) block2;
            } else {
                arr2 = new JSONArray();
                arr2.put(block2);
            }

            for (int i = 0; i < arr2.length(); i++) {
                JSONObject row = arr2.getJSONObject(i);

                String subject = row.optString(
                        "przedmiot",
                        row.optString("przedmiotO", "")
                ).trim();
                if (subject.isEmpty()) {
                    continue;
                }

                String forma = row.optString(
                        "formaZajec",
                        row.optString("formaZajecO", "")
                ).trim().toLowerCase(Locale.ROOT);
                if (forma.isEmpty()) {
                    continue;
                }

                String typeKey = null;
                if (forma.contains("laboratorium")) {
                    typeKey = "lab";
                } else if (forma.contains("audytoryjne")) {
                    typeKey = "aud";
                } else if (forma.contains("wykład")) {
                    typeKey = "lec";
                }

                if (typeKey == null) {
                    continue;
                }

                Map<String, String> types = subjectsAll.get(subject);
                if (types == null) {
                    types = new HashMap<>();
                    subjectsAll.put(subject, types);
                }

                String filterKey = subject + "||" + typeKey;
                types.put(typeKey, filterKey);
            }
        }

        List<SubjectFilterItem> items = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : subjectsAll.entrySet()) {
            String subject = entry.getKey();
            Map<String, String> types = entry.getValue();

            for (Map.Entry<String, String> t : types.entrySet()) {
                String typeKey = t.getKey();
                String filterKey = t.getValue();

                String typeLabel;
                if (appContext != null) {
                    switch (typeKey) {
                        case "lab": typeLabel = appContext.getString(R.string.plan_type_lab); break;
                        case "aud": typeLabel = appContext.getString(R.string.plan_type_auditory); break;
                        default: typeLabel = appContext.getString(R.string.plan_type_lecture); break;
                    }
                } else {
                    switch (typeKey) {
                        case "lab": typeLabel = "Laboratorium"; break;
                        case "aud": typeLabel = "Audytoryjne"; break;
                        default: typeLabel = "Wykład"; break;
                    }
                }

                SubjectFilterItem si = new SubjectFilterItem();
                si.label = subject;
                si.typeKey = typeKey;
                si.typeLabel = typeLabel;
                si.filterKey = filterKey;
                items.add(si);
            }
        }

        return items;
    }
}