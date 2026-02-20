package pl.kejlo.mzutv2;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradesRepository {

    private static final long STUDIES_TTL_MS = CachePolicy.STUDIES_TTL_MS;
    private static final long SEMESTERS_TTL_MS = CachePolicy.SEMESTERS_TTL_MS;

    private static class StudiesCacheEntry {
        long ts;
        String userId;
        List<Study> list;
    }

    private static class SemesterCacheEntry {
        long ts;
        String przynaleznoscId;
        List<Semester> list;
    }

    private static StudiesCacheEntry sStudiesCache;
    private static final Map<String, SemesterCacheEntry> sSemCacheByStudy = new java.util.concurrent.ConcurrentHashMap<>();

    public static void invalidateMemoryCache() {
        sStudiesCache = null;
        sSemCacheByStudy.clear();
    }

    private boolean isNetworkAvailable() {
        Context appContext = MzutSession.getAppContextOrNull();
        if (appContext == null) {
            // If context is unavailable, prefer network flow and rely on fallback handling.
            return true;
        }
        return NetworkStatusHelper.isNetworkAvailable(appContext);
    }

    private List<Study> copyStudies(List<Study> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<Study> copy = new ArrayList<>(source.size());
        for (Study src : source) {
            if (src == null) {
                continue;
            }
            Study s = new Study();
            s.przynaleznoscId = normalizeStudyId(src.przynaleznoscId);
            s.label = src.label;
            copy.add(s);
        }
        return copy;
    }

    private List<Semester> copySemesters(List<Semester> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<Semester> copy = new ArrayList<>(source.size());
        for (Semester src : source) {
            if (src == null) {
                continue;
            }
            Semester s = new Semester();
            s.listaSemestrowId = src.listaSemestrowId;
            s.nrSemestru = src.nrSemestru;
            s.pora = src.pora;
            s.rokAkademicki = src.rokAkademicki;
            s.status = src.status;
            copy.add(s);
        }
        return copy;
    }

    private void updateStudiesCache(String userId, List<Study> studies, long ts) {
        StudiesCacheEntry ce = new StudiesCacheEntry();
        ce.ts = ts;
        ce.userId = userId;
        ce.list = copyStudies(studies);
        sStudiesCache = ce;
    }

    private String normalizeStudyId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String normalized = rawId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonEmpty(String... args) {
        if (args == null) {
            return "";
        }
        for (String s : args) {
            if (s != null && !s.trim().isEmpty()) {
                return s.trim();
            }
        }
        return "";
    }

    private double parseFlexibleDouble(Object raw) {
        if (raw == null) {
            return 0.0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) {
                return 0.0;
            }
            text = text.replace(",", ".");
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private double parseEcts(JSONObject row) {
        if (row == null) {
            return 0.0;
        }

        double value = parseFlexibleDouble(row.opt("ects"));
        if (value > 0.0) {
            return value;
        }

        String[] keys = { "ectsO", "ECTS", "punktyEcts", "punkty_ects", "punktyEctsO" };
        for (String key : keys) {
            value = parseFlexibleDouble(row.opt(key));
            if (value > 0.0) {
                return value;
            }
        }
        return 0.0;
    }

    public List<Study> loadStudies() throws IOException, JSONException {
        return loadStudies(false);
    }

    public List<Study> loadStudies(boolean forceRefresh) throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        boolean online = isNetworkAvailable();

        if (!forceRefresh
                && sStudiesCache != null
                && sStudiesCache.userId != null
                && sStudiesCache.userId.equals(userId)
                && (now - sStudiesCache.ts) < STUDIES_TTL_MS) {

            List<Study> cached = copyStudies(sStudiesCache.list);
            session.setStudies(cached);
            return cached;
        }

        if (!forceRefresh && sStudiesCache == null) {
            List<Study> sessionStudies = session.getStudies();
            if (sessionStudies != null && !sessionStudies.isEmpty()) {
                List<Study> bootstrap = copyStudies(sessionStudies);
                updateStudiesCache(userId, bootstrap, now);
                return bootstrap;
            }
        }

        if (!forceRefresh && !online) {
            List<Study> sessionStudies = session.getStudies();
            if (sessionStudies != null && !sessionStudies.isEmpty()) {
                List<Study> fallback = copyStudies(sessionStudies);
                updateStudiesCache(userId, fallback, now);
                return fallback;
            }
        }

        try {
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
                s.przynaleznoscId = normalizeStudyId(row.optString("przynaleznoscId", null));

                String nazwa = row.optString("nazwa", "").trim();
                String poziom = row.optString("poziom", "").trim();

                String label = nazwa;
                if (!poziom.isEmpty()) {
                    if (!label.isEmpty()) {
                        label += " ";
                    }
                    label += "(" + poziom + ")";
                }
                if (label.isEmpty()) {
                    label = s.przynaleznoscId;
                }

                s.label = label;
                studies.add(s);
            }

            List<Study> result = copyStudies(studies);
            updateStudiesCache(userId, result, now);
            session.setStudies(result);
            session.saveToPreferences();
            return result;
        } catch (IOException | JSONException e) {
            if (!online) {
                List<Study> sessionStudies = session.getStudies();
                if (sessionStudies != null && !sessionStudies.isEmpty()) {
                    List<Study> fallback = copyStudies(sessionStudies);
                    updateStudiesCache(userId, fallback, now);
                    return fallback;
                }
            }
            throw e;
        }
    }

    public List<Semester> loadSemesters() throws IOException, JSONException {
        return loadSemesters(false);
    }

    public List<Semester> loadSemesters(boolean forceRefresh) throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = loadStudies(forceRefresh);
        if (studies == null || studies.isEmpty()) {
            return Collections.emptyList();
        }

        Study active = session.getActiveStudy();
        if (active == null) {
            session.setActiveStudyIndex(0);
            active = session.getActiveStudy();
        }
        String activeStudyId = active != null ? normalizeStudyId(active.przynaleznoscId) : null;
        if (activeStudyId == null) {
            return Collections.emptyList();
        }

        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        String semesterCacheKey = userId + "_" + activeStudyId;
        SemesterCacheEntry cachedSemesters = sSemCacheByStudy.get(semesterCacheKey);
        if (!forceRefresh
                && cachedSemesters != null
                && cachedSemesters.przynaleznoscId != null
                && cachedSemesters.przynaleznoscId.equals(activeStudyId)
                && (now - cachedSemesters.ts) < SEMESTERS_TTL_MS) {
            return copySemesters(cachedSemesters.list);
        }

        try {
            HashMap<String, String> params = new HashMap<>();
            params.put("login", userId);
            params.put("token", authKey);
            params.put("przynaleznoscId", activeStudyId);
            params.put("oceny", "true");

            JSONObject resp = MzutApi.callApi("getStudies", params);
            if (resp == null || !resp.has("Przebieg")) {
                return Collections.emptyList();
            }

            Object block = resp.get("Przebieg");
            JSONArray arr = block instanceof JSONArray
                    ? (JSONArray) block
                    : new JSONArray().put(block);

            List<Semester> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);

                Semester s = new Semester();
                s.listaSemestrowId = row.optString("listaSemestrowId", null);
                s.nrSemestru = row.optString("nrSemestru", "");
                s.pora = row.optString("pora", "");
                s.rokAkademicki = row.optString("rokAkademicki", "");
                s.status = row.optString("status", row.optString("statusO", ""));
                list.add(s);
            }

            SemesterCacheEntry ce2 = new SemesterCacheEntry();
            ce2.ts = now;
            ce2.przynaleznoscId = activeStudyId;
            ce2.list = copySemesters(list);
            sSemCacheByStudy.put(semesterCacheKey, ce2);
            return copySemesters(list);
        } catch (IOException | JSONException e) {
            if (!forceRefresh && cachedSemesters != null && cachedSemesters.list != null && !cachedSemesters.list.isEmpty()) {
                return copySemesters(cachedSemesters.list);
            }
            throw e;
        }
    }

    public List<Grade> loadGradesForSemester(Semester semester)
            throws IOException, JSONException {
        if (semester == null) {
            return Collections.emptyList();
        }
        return loadGradesForSemester(semester.listaSemestrowId);
    }

    public List<Grade> loadGradesForSemester(String listaSemestrowId)
            throws IOException, JSONException {

        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null || listaSemestrowId == null) {
            return Collections.emptyList();
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("listaSemestrowId", listaSemestrowId);

        JSONObject resp = MzutApi.callApi("getGrade", params);
        if (resp == null || !resp.has("Ocena")) {
            return Collections.emptyList();
        }

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
                    row.optString("przedmiotO", ""));

            String forma = firstNonEmpty(
                    row.optString("formaZajec", ""),
                    row.optString("formaZajecO", ""));

            if (!forma.isEmpty()) {
                if (!przedmiot.isEmpty()) {
                    przedmiot += " ";
                }
                przedmiot += "(" + forma + ")";
            }

            g.subjectName = przedmiot;
            g.grade = row.optString("ocena", "");
            g.weight = parseEcts(row);
            g.type = forma;
            g.teacher = row.optString("pracownik", "");

            String termin = firstNonEmpty(
                    row.optString("termin", ""),
                    row.optString("terminO", ""));
            String data = row.optString("data", "");

            g.date = termin.isEmpty()
                    ? data
                    : (data.isEmpty() ? termin : (termin + " " + data));

            grades.add(g);
        }

        return grades;
    }
}
