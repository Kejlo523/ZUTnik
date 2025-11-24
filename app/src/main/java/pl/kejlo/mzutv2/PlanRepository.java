package pl.kejlo.mzutv2;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.time.DayOfWeek;

/**
 * Odpowiednik plan.php – cała logika po stronie Androida.
 * Tu NIC nie rysujemy – tylko liczymy dane, które potem PlanActivity narysuje.
 */
public class PlanRepository {

    private static final String TAG = "mZUTv2-PLAN";

    /* =======================
     *   MODELE DANYCH
     * ======================= */

    /** Surowy event z plan.zut – 1:1 z JSONem. */
    public static class PlanEventRaw {
        public String title;
        public String description;
        public String start;   // ISO string
        public String end;     // ISO string
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

    /** Event przygotowany do rysowania (day/week) – z pozycją/top/width itd. */
    public static class PlanEventUi {
        public int startMin;   // minuty od północy
        public int endMin;
        public float topPx;
        public float heightPx;
        public float leftPct;
        public float widthPct;

        public String title;      // "Przedmiot (L/A/W)"
        public String room;
        public String group;
        public String startStr;   // "HH:mm"
        public String endStr;
        public String tooltip;
        public String typeClass;  // week-event-type-...
        public String subjectKey; // "Przedmiot||lab" / "Przedmiot||aud" / "Przedmiot||lec"
        public String teacher;
    }

    /** Plan jednego dnia w widoku tygodniowym/dziennym. */
    public static class DayColumn {
        public LocalDate date;
        public List<PlanEventUi> events = new ArrayList<>();
    }

    /** Element kalendarza miesiąca. */
    public static class MonthCell {
        public LocalDate date;
        public boolean hasPlan;
    }

    /** Jeden wiersz listy „filtrów przedmiotów”. */
    public static class SubjectFilterItem {
        public String label;      // nazwa przedmiotu
        public String typeKey;    // lab / aud / lec
        public String typeLabel;  // "Laboratorium" / "Audytoryjne" / "Wykład"
        public String filterKey;  // label||typeKey
    }

    /** Debug – odpowiednik $PLAN_DEBUG. */
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

    /** Wynik jednego ładowania planu (day/week/month). */
    public static class PlanResult {
        public String viewMode;        // "day" / "week" / "month"
        public LocalDate current;
        public LocalDate rangeStart;
        public LocalDate rangeEnd;

        // day/week:
        public List<DayColumn> dayColumns = new ArrayList<>();
        public boolean hasAnyEventsInRange;

        // month:
        public List<List<MonthCell>> monthGrid = new ArrayList<>();

        // daty do nawigacji:
        public LocalDate prev;
        public LocalDate next;
        public LocalDate today;

        // opis nagłówka (jak w PHP: day_label/week_label/month_label)
        public String headerLabel;

        public PlanDebug debug = new PlanDebug();
    }

    /* =======================
     *   FORMATERY DAT
     * ======================= */

    private static final DateTimeFormatter ISO_LOCAL_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final DateTimeFormatter HOUR_MIN =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] DNI_PL = {"Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So"};

    private static String fmtPlDate(LocalDate date) {
        int dow = date.getDayOfWeek().getValue() % 7; // 1..7 -> 1..6,0
        String dz = DNI_PL[dow];
        return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " (" + dz + ")";
    }

    private static LocalDateTime parseIsoLocal(String iso) {
        if (iso == null || iso.trim().isEmpty()) return null;
        try {
            // Próba 1: z offsetem czasu
            OffsetDateTime odt = OffsetDateTime.parse(iso);
            return odt.toLocalDateTime();
        } catch (Exception ignored) { }
        try {
            // Próba 2: bez offsetu
            return LocalDateTime.parse(iso, ISO_LOCAL_DT);
        } catch (Exception e) {
            Log.w(TAG, "Nie udało się sparsować daty: " + iso, e);
            return null;
        }
    }

    private static int minutesFromMidnight(LocalDateTime dt) {
        return dt.getHour() * 60 + dt.getMinute();
    }

    /* =======================
     *   POMOCNICZE – API ZUT
     * ======================= */

    /**
     * Wywołanie GET na plan.zut.edu.pl i zwrot JSON-a jako JSONArray.
     */
    private JSONArray httpGetJsonArray(String urlStr, PlanDebug debug) throws IOException, JSONException {
        HttpURLConnection conn = null;
        InputStream is = null;
        PlanDebug.RequestDebug rd = new PlanDebug.RequestDebug();
        rd.url = urlStr;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "mZUTv2-Android-Plan/1.0");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            rd.httpCode = code;

            if (code != 200) {
                throw new IOException("HTTP " + code + " from plan.zut");
            }

            is = new BufferedInputStream(conn.getInputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            String body = sb.toString();
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
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Pobranie numeru albumu dla aktywnego kierunku (getStudy).
     */
    private String resolveAlbumNumber() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) return null;

        // jaki kierunek jest aktywny?
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            GradesRepository gr = new GradesRepository();
            studies = gr.loadStudies();
        }
        if (studies == null || studies.isEmpty()) return null;

        int idx = session.getActiveStudyIndex();
        if (idx < 0 || idx >= studies.size()) idx = 0;
        Study active = studies.get(idx);
        if (active.przynaleznoscId == null) return null;

        // getStudy
        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", active.przynaleznoscId);

        JSONObject resp = MzutApi.callApi("getStudy", params);
        if (resp == null) return null;

        String album = resp.optString("album", null);
        if (album != null) album = album.trim();
        return (album == null || album.isEmpty()) ? null : album;
    }

    /* =======================
     *   POBRANIE PLANU Z PLAN.ZUT
     * ======================= */

    /**
     * Pełen odpowiednik plan_zut_fetch_range_by_album z PHP.
     * Zwraca listę surowych eventów w podanym zakresie (włącznie).
     */
    private List<PlanEventRaw> fetchPlanRangeByAlbum(
            String album,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            PlanDebug debug
    ) throws IOException, JSONException {

        // zakres kalendarzowy do filtrowania
        LocalDate filterStart = rangeStart;
        LocalDate filterEnd = rangeEnd;

        // zakres do API
        LocalDateTime apiStart;
        LocalDateTime apiEnd;
        if (rangeStart.equals(rangeEnd)) {
            // widok jednego dnia – [D 00:00, (D+1) 23:59]
            apiStart = rangeStart.atStartOfDay();
            apiEnd = rangeEnd.plusDays(1).atTime(23, 59, 59);
        } else {
            // wielodniowy
            apiStart = filterStart.atStartOfDay();
            apiEnd = filterEnd.plusDays(1).atTime(23, 59, 59);
        }

        String startA = apiStart.toLocalDate().format(YMD) + "T00:00:00";
        String endA   = apiEnd.toLocalDate().format(YMD)   + "T23:59:59";
        String startB = apiStart.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        String endB   = apiEnd.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();

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
                // próbujemy kolejny wariant
                Log.w(TAG, "Błąd pobierania plan.zut (" + u + "): " + e.getMessage());
            }
        }

        List<PlanEventRaw> out = new ArrayList<>();
        if (arr == null) return out;

        String filterStartStr = filterStart.format(YMD);
        String filterEndStr   = filterEnd.format(YMD);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;

            String start = e.optString("start", null);
            String end   = e.optString("end", null);
            if (start == null || end == null) continue;

            // data "YYYY-MM-DD" z pola start
            String eventDateStr;
            if (start.length() >= 10) {
                eventDateStr = start.substring(0, 10);
            } else {
                LocalDateTime dt = parseIsoLocal(start);
                if (dt == null) continue;
                eventDateStr = dt.toLocalDate().format(YMD);
            }

            // poza zakresem?
            if (eventDateStr.compareTo(filterStartStr) < 0
                    || eventDateStr.compareTo(filterEndStr) > 0) {
                continue;
            }

            PlanEventRaw r = new PlanEventRaw();
            r.title               = e.optString("title", "");
            r.description         = e.optString("description", "");
            r.start               = start;
            r.end                 = end;
            r.workerTitle         = e.optString("worker_title", "");
            r.worker              = e.optString("worker", "");
            r.lessonForm          = e.optString("lesson_form", "");
            r.lessonFormShort     = e.optString("lesson_form_short", "");
            r.groupName           = e.optString("group_name", "");
            r.tokName             = e.optString("tok_name", "");
            r.room                = e.optString("room", "");
            r.lessonStatus        = e.optString("lesson_status", "");
            r.lessonStatusShort   = e.optString("lesson_status_short", "");
            r.subject             = e.optString("subject", "");
            r.hours               = e.optString("hours", "");
            r.color               = e.optString("color", "");
            r.borderColor         = e.optString("borderColor", "");

            out.add(r);
        }

        return out;
    }

    private List<PlanEventRaw> fetchPlanDayByAlbum(
            String album,
            LocalDate day,
            PlanDebug debug
    ) throws IOException, JSONException {
        return fetchPlanRangeByAlbum(album, day, day, debug);
    }

    /* =======================
     *   KLASYFIKACJA TYPU ZAJĘĆ
     * ======================= */

    private String eventTypeClass(PlanEventRaw e) {
        String statusShort = lower(e.lessonStatusShort);
        String formFull    = lower(e.lessonForm);
        String formShort   = lower(e.lessonFormShort);
        String subject     = lower(e.subject != null && !e.subject.isEmpty() ? e.subject : e.title);

        if ("e".equals(statusShort))  return "week-event-type-exam";
        if ("o".equals(statusShort))  return "week-event-type-cancelled";
        if ("r".equals(statusShort))  return "week-event-type-rector";
        if ("zz".equals(statusShort)) return "week-event-type-remote";

        String hay = formFull + " " + subject;
        if (hay.contains("egzamin")) return "week-event-type-exam";
        if (hay.contains("odwołane")) return "week-event-type-cancelled";
        if (hay.contains("rektorskie")) return "week-event-type-rector";

        if (hay.contains("zajęcia zdalne") || hay.contains("zdalne"))
            return "week-event-type-remote";

        if (hay.contains("zaliczenie zdalne poprawkowe"))
            return "week-event-type-pass-remote-retake";

        if (hay.contains("zaliczenie zdalne"))
            return "week-event-type-pass-remote";

        if (hay.contains("zaliczenie poprawkow") || hay.contains("zaliczenie poprawkowe"))
            return "week-event-type-pass-retake";

        if (hay.contains("zaliczenie"))
            return "week-event-type-pass";

        if (hay.contains("laboratorium") || "l".equals(formShort))
            return "week-event-type-lab";

        if (hay.contains("audytoryjne") || "a".equals(formShort))
            return "week-event-type-auditory";

        if (hay.contains("wykład") || "w".equals(formShort))
            return "week-event-type-lecture";

        return "";
    }

    private static String lower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }

    /* =======================
     *   BUDOWANIE STRUKTUR: BY DATE + CLUSTERS
     * ======================= */

    private static final int START_HOUR = 6;
    private static final int END_HOUR   = 22;
    private static final float HOUR_HEIGHT_PX = 48f;

    private Map<LocalDate, List<PlanEventRaw>> groupByDay(List<PlanEventRaw> events) {
        Map<LocalDate, List<PlanEventRaw>> byDate = new HashMap<>();

        for (PlanEventRaw e : events) {
            LocalDateTime dtStart = parseIsoLocal(e.start);
            if (dtStart == null) continue;
            LocalDate d = dtStart.toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(e);
        }

        // sort po godzinie
        for (List<PlanEventRaw> list : byDate.values()) {
            list.sort((a, b) -> {
                LocalDateTime da = parseIsoLocal(a.start);
                LocalDateTime db = parseIsoLocal(b.start);
                if (da == null || db == null) return 0;
                return da.compareTo(db);
            });
        }

        return byDate;
    }

    /**
     * Dla jednego dnia przygotowuje listę PlanEventUi – z top/height oraz
     * lane’ami i widthPct/leftPct (jak w PHP w klastrach).
     */
    private List<PlanEventUi> buildDayLayout(List<PlanEventRaw> list) {
        List<PlanEventUi> result = new ArrayList<>();
        if (list == null || list.isEmpty()) return result;

        List<Map<String, Object>> events = new ArrayList<>();

        for (PlanEventRaw e : list) {
            LocalDateTime dtS = parseIsoLocal(e.start);
            LocalDateTime dtE = parseIsoLocal(e.end);
            if (dtS == null || dtE == null) continue;

            int startMin = minutesFromMidnight(dtS);
            int endMin   = minutesFromMidnight(dtE);

            int calStart = START_HOUR * 60;
            int calEnd   = END_HOUR   * 60;

            if (endMin <= calStart || startMin >= calEnd) continue;

            int startClamped = Math.max(startMin, calStart);
            int endClamped   = Math.min(endMin, calEnd);
            int duration = Math.max(endClamped - startClamped, 15);

            int offsetMin = startClamped - calStart;
            float topPx   = (offsetMin / 60f) * HOUR_HEIGHT_PX;
            float heightPx= (duration  / 60f) * HOUR_HEIGHT_PX;
            if (heightPx < 22) heightPx = 22;

            String subjectName = (e.subject != null && !e.subject.isEmpty())
                    ? e.subject
                    : (e.title != null ? e.title : "");

            String formShort = e.lessonFormShort != null ? e.lessonFormShort.trim() : "";
            String fullTitle = subjectName;
            if (!subjectName.isEmpty() && !formShort.isEmpty()) {
                fullTitle += " (" + formShort + ")";
            }

            String room  = e.room != null ? e.room : "";
            String group = e.groupName != null ? e.groupName : "";
            String teacher = (e.workerTitle != null && !e.workerTitle.isEmpty())
                    ? e.workerTitle
                    : (e.worker != null ? e.worker : "");

            String startStr = dtS.format(HOUR_MIN);
            String endStr   = dtE.format(HOUR_MIN);

            String tooltip = fullTitle
                    + " | " + startStr + " - " + endStr
                    + (room.isEmpty() ? "" : " | sala: " + room)
                    + (group.isEmpty() ? "" : " | grupa: " + group)
                    + (teacher.isEmpty() ? "" : " | " + teacher);

            // typKey do filtra
            String formShortLower = lower(formShort);
            String typeKey = null;
            if ("l".equals(formShortLower)) typeKey = "lab";
            else if ("a".equals(formShortLower)) typeKey = "aud";
            else if ("w".equals(formShortLower)) typeKey = "lec";

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
            ev.put("subjectKey", subjectKey);
            ev.put("teacher", teacher);

            events.add(ev);
        }

        // sort po początku (jak w PHP)
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

        // podział na klastry
        List<List<Map<String,Object>>> clusters = new ArrayList<>();
        List<Map<String,Object>> currentCluster = new ArrayList<>();
        Integer clusterEnd = null;

        for (Map<String,Object> ev : events) {
            int startMin = (int) ev.get("startMin");
            int endMin   = (int) ev.get("endMin");

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

        // lanes w klastrach
        List<Map<String,Object>> finalEvents = new ArrayList<>();

        for (List<Map<String,Object>> cluster : clusters) {
            List<Integer> lanes = new ArrayList<>(); // laneIndex -> lastEndMin

            for (Map<String,Object> ev : cluster) {
                int startMin = (int) ev.get("startMin");
                int endMin   = (int) ev.get("endMin");

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

            for (Map<String,Object> ev : cluster) {
                int laneIdx = (int) ev.get("lane");
                float leftPct = laneIdx * laneWidth;
                float widthPct = laneWidth;

                ev.put("leftPct", leftPct);
                ev.put("widthPct", widthPct);
                ev.remove("lane");
                finalEvents.add(ev);
            }
        }

        // zrzut do PlanEventUi
        for (Map<String,Object> ev : finalEvents) {
            PlanEventUi ui = new PlanEventUi();
            ui.startMin   = (int) ev.get("startMin");
            ui.endMin     = (int) ev.get("endMin");
            ui.topPx      = (float) ev.get("topPx");
            ui.heightPx   = (float) ev.get("heightPx");
            ui.leftPct    = (float) ev.get("leftPct");
            ui.widthPct   = (float) ev.get("widthPct");
            ui.title      = (String) ev.get("title");
            ui.room       = (String) ev.get("room");
            ui.group      = (String) ev.get("group");
            ui.startStr   = (String) ev.get("startStr");
            ui.endStr     = (String) ev.get("endStr");
            ui.tooltip    = (String) ev.get("tooltip");
            ui.typeClass  = (String) ev.get("typeClass");
            ui.subjectKey = (String) ev.get("subjectKey");
            ui.teacher    = (String) ev.get("teacher");
            result.add(ui);
        }

        return result;
    }

    /* =======================
     *   MIESIĄC – SIATKA
     * ======================= */

    private List<List<MonthCell>> buildMonthGrid(LocalDate monthDate, Set<LocalDate> daysWithPlan) {
        List<List<MonthCell>> grid = new ArrayList<>();

        LocalDate monthStart = monthDate.withDayOfMonth(1);
        LocalDate monthEnd   = monthDate.withDayOfMonth(monthDate.lengthOfMonth());

        LocalDate cursor = monthStart;
        int firstDow = cursor.getDayOfWeek().getValue(); // 1..7 (pon=1)
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

        // ostatni tydzień
        boolean any = false;
        for (MonthCell c : week) {
            if (c != null) { any = true; break; }
        }
        if (any) {
            grid.add(new ArrayList<>(week));
        }

        return grid;
    }

    /* =======================
     *   PUBLICZNE API – PLAN
     * ======================= */

    /**
     * Główna metoda: ładuje plan dla danego widoku (day/week/month) i daty.
     *
     * @param viewMode "day" / "week" / "month"
     * @param currentDate LocalDate aktualnej pozycji (jeśli null -> today)
     */
    public PlanResult loadPlan(String viewMode, LocalDate currentDate) throws IOException, JSONException {
        if (currentDate == null) currentDate = LocalDate.now();
        if (!"day".equals(viewMode) && !"week".equals(viewMode) && !"month".equals(viewMode)) {
            viewMode = "week";
        }

        PlanResult r = new PlanResult();
        r.viewMode = viewMode;
        r.current  = currentDate;

        LocalDate today = LocalDate.now();
        r.today = today;

        // album
        String album = resolveAlbumNumber();
        r.debug.album = album;
        r.debug.view  = viewMode;

        if (album == null) {
            // brak albumu – nie da się pobrać planu
            return r;
        }

        // zakres kalendarzowy
        LocalDate rangeStart = currentDate;
        LocalDate rangeEnd   = currentDate;

        if ("day".equals(viewMode)) {
            // widok jednego dnia
            r.headerLabel = fmtPlDate(currentDate);

        } else if ("week".equals(viewMode)) {
            LocalDate weekStart;

            // ✅ TYLKO jeśli JEST DZISIAJ I JEST NIEDZIELA -> pokazujemy KOLEJNY tydzień
            if (currentDate.equals(today) && currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                weekStart = currentDate.plusDays(1); // poniedziałek następnego tygodnia
            } else {
                // standardowo – tydzień od poniedziałku z bieżącej daty
                weekStart = currentDate;
                int dow = weekStart.getDayOfWeek().getValue(); // 1..7 (pon=1)
                if (dow > 1) {
                    weekStart = weekStart.minusDays(dow - 1);
                }
            }

            LocalDate weekEnd = weekStart.plusDays(6);
            rangeStart = weekStart;
            rangeEnd   = weekEnd;
            r.headerLabel = weekStart.format(DateTimeFormatter.ofPattern("dd.MM"))
                    + " – " + weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

        } else {
            // widok miesiąca
            LocalDate monthStart = currentDate.withDayOfMonth(1);
            LocalDate monthEnd   = currentDate.withDayOfMonth(currentDate.lengthOfMonth());
            rangeStart = monthStart;
            rangeEnd   = monthEnd;
            r.headerLabel = monthStart.getMonth().name() + " " + monthStart.getYear();
        }

        r.rangeStart = rangeStart;
        r.rangeEnd   = rangeEnd;
        r.debug.rangeStart = rangeStart.format(YMD);
        r.debug.rangeEnd   = rangeEnd.format(YMD);

        // pobranie danych z plan.zut
        List<PlanEventRaw> entries;
        if ("day".equals(viewMode)) {
            entries = fetchPlanDayByAlbum(album, rangeStart, r.debug);
        } else {
            entries = fetchPlanRangeByAlbum(album, rangeStart, rangeEnd, r.debug);
        }
        r.debug.entriesTotal = entries.size();

        // grupowanie po dniu
        Map<LocalDate, List<PlanEventRaw>> byDate = groupByDay(entries);
        List<String> daysWithDataStr = new ArrayList<>();
        for (LocalDate d : byDate.keySet()) {
            daysWithDataStr.add(d.format(YMD));
        }
        Collections.sort(daysWithDataStr);
        r.debug.daysWithData = daysWithDataStr;

        // widok day/week
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
                List<PlanEventRaw> rawList = byDate.getOrDefault(d, Collections.emptyList());
                List<PlanEventUi> uiList = buildDayLayout(rawList);
                if (!uiList.isEmpty()) any = true;
                col.events = uiList;
                r.dayColumns.add(col);
            }
            r.hasAnyEventsInRange = any;

        } else {
            // widok month – zaznaczamy dni, w których coś jest
            Set<LocalDate> daysWithPlan = new HashSet<>(byDate.keySet());
            r.monthGrid = buildMonthGrid(currentDate, daysWithPlan);
        }

        // prev/next – dalej liczone po currentDate (bez kombinacji z niedzielą)
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

    /* =======================
     *   FILTR PRZEDMIOTÓW (Lab/Aud/Wykład)
     * ======================= */

    /**
     * Odpowiednik plan_collect_all_subjects_for_filter.
     * Pobieramy CAŁY plan (dla albumu) i robimy listę unikalnych subject + type.
     *
     * Uwaga: tutaj nie robimy rozbudowanego cache w sesji (jak PHP),
     * ale w razie potrzeby można później dopisać do MzutSession.
     */
    public List<SubjectFilterItem> loadSubjectsForFilter() throws IOException, JSONException {
        String album = resolveAlbumNumber();
        if (album == null) return Collections.emptyList();

        PlanDebug debug = new PlanDebug();

        // tak jak w PHP: najpierw getPlan bez day, potem każdy dzień z osobna
        MzutSession s = MzutSession.getInstance();
        String userId  = s.getUserId();
        String authKey = s.getAuthKey();
        if (userId == null || authKey == null) return Collections.emptyList();

        // krok 1: getPlan (lista dni; to w PHP)
        HashMap<String,String> params = new HashMap<>();
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

        // dataZajec -> dayKey
        Map<String,String> daysList = new HashMap<>(); // "Y-m-d" -> "20112025"
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            String data = row.optString("dataZajec", "");
            if (data.isEmpty()) continue;

            String dataClean = data.replace('.', '-');
            LocalDate d = null;
            // próby formatu
            try {
                if (dataClean.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    d = LocalDate.parse(dataClean);
                } else {
                    // 20-11-2025
                    DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                    d = LocalDate.parse(dataClean, f);
                }
            } catch (Exception ignore) { }
            if (d == null) continue;

            String dateStr = d.format(YMD);
            String dayKey  = data.replace(".", "");
            dayKey = dayKey.replace("-", "");
            daysList.put(dateStr, dayKey);
        }

        Map<String, Map<String,String>> subjectsAll = new TreeMap<>();

        // krok 2: każdy dzień osobno (getPlan z parametrem "day")
        for (String dateStr : daysList.keySet()) {
            String dayKey = daysList.get(dateStr);
            HashMap<String,String> paramsDay = new HashMap<>();
            paramsDay.put("login", userId);
            paramsDay.put("token", authKey);
            paramsDay.put("day", dayKey);
            JSONObject planResp = MzutApi.callApi("getPlan", paramsDay);
            if (planResp == null || !planResp.has("Plan")) continue;

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
                String subject = row.optString("przedmiot",
                        row.optString("przedmiotO", "")).trim();
                if (subject.isEmpty()) continue;

                String forma = row.optString("formaZajec",
                        row.optString("formaZajecO", "")).trim().toLowerCase(Locale.ROOT);
                if (forma.isEmpty()) continue;

                String typeKey = null;
                if (forma.contains("laboratorium")) {
                    typeKey = "lab";
                } else if (forma.contains("audytoryjne")) {
                    typeKey = "aud";
                } else if (forma.contains("wykład")) {
                    typeKey = "lec";
                }
                if (typeKey == null) continue;

                Map<String,String> types = subjectsAll.get(subject);
                if (types == null) {
                    types = new HashMap<>();
                    subjectsAll.put(subject, types);
                }
                String filterKey = subject + "||" + typeKey;
                types.put(typeKey, filterKey);
            }
        }

        List<SubjectFilterItem> items = new ArrayList<>();
        for (Map.Entry<String, Map<String,String>> entry : subjectsAll.entrySet()) {
            String subject = entry.getKey();
            Map<String,String> types = entry.getValue();
            for (Map.Entry<String,String> t : types.entrySet()) {
                String typeKey = t.getKey();
                String filterKey = t.getValue();
                String typeLabel;
                switch (typeKey) {
                    case "lab":
                        typeLabel = "Laboratorium";
                        break;
                    case "aud":
                        typeLabel = "Audytoryjne";
                        break;
                    default:
                        typeLabel = "Wykład";
                        break;
                }
                SubjectFilterItem si = new SubjectFilterItem();
                si.label     = subject;
                si.typeKey   = typeKey;
                si.typeLabel = typeLabel;
                si.filterKey = filterKey;
                items.add(si);
            }
        }

        return items;
    }
}
