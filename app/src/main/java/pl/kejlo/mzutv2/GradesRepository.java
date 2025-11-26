package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class GradesRepository {

    // ============================
    //  HELPER
    // ============================

    private String firstNonEmpty(String... args) {
        if (args == null) return "";
        for (String s : args) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    // ============================
    //  CACHE: STUDIES
    // ============================

    private static final long STUDIES_TTL_MS =
            7L * 24L * 60L * 60L * 1000L;

    private static class StudiesCacheEntry {
        long ts;
        String userId;
        List<Study> list;
    }

    private static StudiesCacheEntry sStudiesCache = null;

    // ============================
    //  CACHE: SEMESTERS
    // ============================

    private static final long SEMESTERS_TTL_MS =
            7L * 24L * 60L * 60L * 1000L;

    private static class SemesterCacheEntry {
        long ts;
        String przynaleznoscId;
        List<Semester> list;
    }

    private static SemesterCacheEntry sSemCache = null;

    // ============================
    //  ŁADOWANIE KIERUNKÓW + CACHE
    // ============================

    public List<Study> loadStudies() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();

        String userId  = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null)
            return Collections.emptyList();

        long now = System.currentTimeMillis();

        // 1) CACHE (globalny dla userId)
        if (sStudiesCache != null &&
                sStudiesCache.userId != null &&
                sStudiesCache.userId.equals(userId) &&
                (now - sStudiesCache.ts) < STUDIES_TTL_MS) {

            session.setStudies(sStudiesCache.list);
            return sStudiesCache.list;
        }

        // 2) CACHE z MzutSession
        if (session.getStudies() != null && !session.getStudies().isEmpty()) {
            // ale zapisujemy też do naszego RAM cache
            StudiesCacheEntry ce = new StudiesCacheEntry();
            ce.ts = now;
            ce.userId = userId;
            ce.list = session.getStudies();
            sStudiesCache = ce;

            return ce.list;
        }

        // 3) BRAK CACHE → API
        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);

        JSONObject menu = MzutApi.callApi("getMenuStudent", params);
        if (menu == null || !menu.has("Menu")) {
            return Collections.emptyList();
        }

        Object block = menu.get("Menu");
        JSONArray arr = block instanceof JSONArray
                ? (JSONArray) block
                : new JSONArray().put(block);

        List<Study> studies = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);

            Study s = new Study();
            s.przynaleznoscId = row.optString("przynaleznoscId", null);

            String nazwa  = row.optString("nazwa", "").trim();
            String poziom = row.optString("poziom", "").trim();

            String label = nazwa;
            if (!poziom.isEmpty()) {
                if (!label.isEmpty()) label += " ";
                label += "(" + poziom + ")";
            }
            if (label.isEmpty()) label = s.przynaleznoscId;

            s.label = label;
            studies.add(s);
        }

        // zapisz do cache RAM
        StudiesCacheEntry ce = new StudiesCacheEntry();
        ce.ts = now;
        ce.userId = userId;
        ce.list = studies;
        sStudiesCache = ce;

        // zapis do MzutSession
        session.setStudies(studies);
        session.setActiveStudyIndex(0);

        return studies;
    }

    // ============================
    //  ŁADOWANIE SEMESTRÓW + CACHE
    // ============================

    public List<Semester> loadSemesters() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();

        // 1) kierunki z cache/session
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty())
            studies = loadStudies();

        if (studies == null || studies.isEmpty())
            return Collections.emptyList();

        int idx = session.getActiveStudyIndex();
        if (idx < 0 || idx >= studies.size()) idx = 0;

        Study active = studies.get(idx);

        String userId  = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null || active.przynaleznoscId == null)
            return Collections.emptyList();

        long now = System.currentTimeMillis();

        // 2) CACHE semestrów
        if (sSemCache != null &&
                sSemCache.przynaleznoscId != null &&
                sSemCache.przynaleznoscId.equals(active.przynaleznoscId) &&
                (now - sSemCache.ts) < SEMESTERS_TTL_MS) {

            return sSemCache.list;
        }

        // 3) API – getStudies(oceny=true)
        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", active.przynaleznoscId);
        params.put("oceny", "true");

        JSONObject resp = MzutApi.callApi("getStudies", params);
        if (resp == null || !resp.has("Przebieg"))
            return Collections.emptyList();

        Object block = resp.get("Przebieg");
        JSONArray arr = block instanceof JSONArray
                ? (JSONArray) block
                : new JSONArray().put(block);

        List<Semester> list = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);

            Semester s = new Semester();
            s.listaSemestrowId = row.optString("listaSemestrowId", null);
            s.nrSemestru       = row.optString("nrSemestru", "");
            s.pora             = row.optString("pora", "");
            s.rokAkademicki    = row.optString("rokAkademicki", "");
            s.status           = row.optString("status", row.optString("statusO", ""));
            list.add(s);
        }

        // zapis cache
        SemesterCacheEntry ce2 = new SemesterCacheEntry();
        ce2.ts = now;
        ce2.przynaleznoscId = active.przynaleznoscId;
        ce2.list = list;
        sSemCache = ce2;

        return list;
    }

    // ============================
    //  ŁADOWANIE OCEN (bez cache)
    // ============================

    public List<Grade> loadGradesForSemester(Semester semester)
            throws IOException, JSONException {
        if (semester == null) return Collections.emptyList();
        return loadGradesForSemester(semester.listaSemestrowId);
    }

    public List<Grade> loadGradesForSemester(String listaSemestrowId)
            throws IOException, JSONException {

        MzutSession session = MzutSession.getInstance();
        String userId  = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null || listaSemestrowId == null)
            return Collections.emptyList();

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("listaSemestrowId", listaSemestrowId);

        JSONObject resp = MzutApi.callApi("getGrade", params);
        if (resp == null || !resp.has("Ocena"))
            return Collections.emptyList();

        Object block = resp.get("Ocena");
        JSONArray arr = block instanceof JSONArray
                ? (JSONArray) block
                : new JSONArray().put(block);

        List<Grade> grades = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);

            Grade g = new Grade();

            String przedmiot = firstNonEmpty(
                    row.optString("przedmiot", ""),
                    row.optString("przedmiotO", "")
            );

            String forma = firstNonEmpty(
                    row.optString("formaZajec", ""),
                    row.optString("formaZajecO", "")
            );

            if (!forma.isEmpty()) {
                if (!przedmiot.isEmpty()) przedmiot += " ";
                przedmiot += "(" + forma + ")";
            }

            g.subjectName = przedmiot;
            g.grade       = row.optString("ocena", "");
            g.weight      = row.optDouble("ects", 0.0);
            g.type        = forma;
            g.teacher     = row.optString("pracownik", "");

            String termin = firstNonEmpty(
                    row.optString("termin", ""),
                    row.optString("terminO", "")
            );
            String data = row.optString("data", "");

            g.date = termin.isEmpty()
                    ? data
                    : (data.isEmpty() ? termin : (termin + " " + data));

            grades.add(g);
        }

        return grades;
    }
}
