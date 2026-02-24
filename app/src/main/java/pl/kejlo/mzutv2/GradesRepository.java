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

    private String extractLocalized(JSONObject obj, String key) {
        if (obj == null) return "";
        JSONObject localized = obj.optJSONObject(key);
        if (localized != null) {
            return localized.optString("pl", localized.optString("en", ""));
        }
        return obj.optString(key, "");
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

    /**
     * Fetches course names from services/courses/user for a given term.
     */
    private Map<String, String> fetchUsosCourseNames(String termId) {
        Map<String, String> names = new HashMap<>();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("active_terms_only", "false");
            JSONObject resp = UsosApi.get("services/courses/user", params);
            JSONObject editions = resp != null ? resp.optJSONObject("course_editions") : null;
            if (editions != null) {
                JSONArray courses = editions.optJSONArray(termId);
                if (courses != null) {
                    for (int i = 0; i < courses.length(); i++) {
                        JSONObject c = courses.optJSONObject(i);
                        if (c == null) continue;
                        String cid = c.optString("course_id", "");
                        if (!cid.isEmpty()) {
                            names.put(cid, extractLocalized(c, "course_name"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GradesRepository", "Could not fetch course names", e);
        }
        return names;
    }

    /**
     * Fetches ECTS points from services/courses/user_ects_points for a given term.
     */
    private Map<String, Double> fetchUsosEcts(String termId) {
        Map<String, Double> ects = new HashMap<>();
        try {
            JSONObject resp = UsosApi.get("services/courses/user_ects_points", null);
            JSONObject termEcts = resp != null ? resp.optJSONObject(termId) : null;
            if (termEcts != null) {
                java.util.Iterator<String> keys = termEcts.keys();
                while (keys.hasNext()) {
                    String cid = keys.next();
                    ects.put(cid, parseFlexibleDouble(termEcts.opt(cid)));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GradesRepository", "Could not fetch ECTS", e);
        }
        return ects;
    }

    /**
     * Parses a single USOS grade JSON object into a Grade domain object.
     */
    private Grade buildUsosGrade(JSONObject gradeObj, String courseName, double ects, String gradeType) {
        if (gradeObj == null) return null;

        Grade g = new Grade();
        g.subjectName = courseName;
        g.weight = ects;
        g.type = gradeType;
        g.teacher = "";
        g.grade = gradeObj.optString("value_symbol", "");
        g.gradeDescription = extractLocalized(gradeObj, "value_description");
        g.passes = gradeObj.optBoolean("passes", false);
        g.countsIntoAverage = gradeObj.optBoolean("counts_into_average", false);
        g.comment = gradeObj.optString("comment", "");

        // Date — prefer date_acquisition, fallback to date_modified
        String dateAcq = gradeObj.optString("date_acquisition", "");
        String dateMod = gradeObj.optString("date_modified", "");
        if (dateAcq != null && !dateAcq.isEmpty()) {
            g.date = dateAcq.contains(" ") ? dateAcq.split(" ")[0] : dateAcq;
        } else if (dateMod != null && !dateMod.isEmpty()) {
            g.date = dateMod.contains(" ") ? dateMod.split(" ")[0] : dateMod;
        } else {
            g.date = "";
        }

        return g;
    }

    public List<Study> loadStudies() throws IOException, JSONException {
        return loadStudies(false);
    }

    public List<Study> loadStudies(boolean forceRefresh) throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || (!session.isUsosLogin() && authKey == null)) {
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
            List<Study> studies = new ArrayList<>();
            if (session.isUsosLogin()) {
                // Fetch real student programmes from USOS.
                // programme.id (e.g. "S1-INF") is used as przynaleznoscId so that
                // StudiesInfoRepository can look up details for the selected programme.
                // active_only=false includes past/completed programmes.
                Map<String, String> params = new HashMap<>();
                params.put("fields", "programme[id|description|mode_of_studies|level_of_studies]|status");
                params.put("active_only", "false");
                JSONArray arr = UsosApi.getArray("services/progs/student", params);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.getJSONObject(i);
                    JSONObject prog = row.optJSONObject("programme");
                    if (prog == null) continue;

                    Study s = new Study();
                    s.przynaleznoscId = prog.optString("id", "");
                    if (s.przynaleznoscId == null || s.przynaleznoscId.isEmpty()) continue;

                    JSONObject desc = prog.optJSONObject("description");
                    String label = desc != null
                            ? desc.optString("pl", desc.optString("en", s.przynaleznoscId))
                            : s.przynaleznoscId;

                    int mode = prog.optInt("mode_of_studies", 0);
                    if (mode > 0) {
                        label += " (" + (mode == 1 ? "stacjonarne" : "niestacjonarne") + ")";
                    }
                    s.label = label;
                    studies.add(s);
                }
            } else {
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
        if (userId == null || (!session.isUsosLogin() && authKey == null)) {
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
            List<Semester> list = new ArrayList<>();
            
            if (session.isUsosLogin()) {
                // Step 1: get all term IDs the student is enrolled in via course_editions map keys.
                // active_terms_only=false ensures past terms are included.
                Map<String, String> ceParams = new HashMap<>();
                ceParams.put("active_terms_only", "false");
                ceParams.put("fields", "course_editions");
                JSONObject ceResp = UsosApi.get("services/courses/user", ceParams);
                JSONObject editions = ceResp != null ? ceResp.optJSONObject("course_editions") : null;

                if (editions != null) {
                    List<String> termIds = new ArrayList<>();
                    java.util.Iterator<String> keys = editions.keys();
                    while (keys.hasNext()) {
                        termIds.add(keys.next());
                    }
                    Collections.sort(termIds); // ascending (oldest first)

                    if (!termIds.isEmpty()) {
                        // Step 2: batch-fetch term details (name + is_active).
                        StringBuilder tids = new StringBuilder();
                        for (int i = 0; i < termIds.size(); i++) {
                            if (i > 0) tids.append("|");
                            tids.append(termIds.get(i));
                        }
                        Map<String, String> tParams = new HashMap<>();
                        tParams.put("term_ids", tids.toString());
                        tParams.put("fields", "id|name|is_active");
                        JSONObject tDetails = UsosApi.get("services/terms/terms", tParams);

                        for (String tid : termIds) {
                            JSONObject tObj = tDetails != null ? tDetails.optJSONObject(tid) : null;
                            Semester s = new Semester();
                            s.listaSemestrowId = tid;
                            s.nrSemestru = tid;
                            if (tObj != null) {
                                String fullName = extractLocalized(tObj, "name");
                                s.rokAkademicki = !fullName.isEmpty() ? fullName : tid;
                                s.status = tObj.optBoolean("is_active", false) ? "Aktywny" : "Zakończony";
                            } else {
                                s.rokAkademicki = tid;
                                s.status = "";
                            }
                            s.pora = tid.endsWith("Z") ? "Zimowy" : (tid.endsWith("L") ? "Letni" : "");
                            list.add(s);
                        }
                    }
                }
            } else {
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

    /**
     * Loads grades for a given semester using USOS API (services/grades/terms2).
     * For USOS login only. Original ZUT API path is unchanged.
     */
    public List<Grade> loadGradesForSemester(String listaSemestrowId)
            throws IOException, JSONException {

        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || (!session.isUsosLogin() && authKey == null) || listaSemestrowId == null) {
            return Collections.emptyList();
        }

        List<Grade> grades = new ArrayList<>();
        
        if (session.isUsosLogin()) {
            // Bail out immediately if this task has already been cancelled by the caller
            // (e.g. the user changed semester). Without this check the OkHttp calls below
            // would throw InterruptedIOException and show a confusing error toast.
            if (Thread.currentThread().isInterrupted()) {
                return Collections.emptyList();
            }

            // 1. Fetch course names and ECTS for this term
            Map<String, String> courseNames = fetchUsosCourseNames(listaSemestrowId);
            Map<String, Double> courseEcts = fetchUsosEcts(listaSemestrowId);

            // 2. Fetch grades via services/grades/terms2
            //    Response: { term_id: { course_id: { course_grades: [...], course_units_grades: { unit_id: [...] } } } }
            //    course_grades = ARRAY of grade objects
            //    course_units_grades = DICT of unit_id → ARRAY of grade objects
            Map<String, String> gradeParams = new HashMap<>();
            gradeParams.put("term_ids", listaSemestrowId);
            gradeParams.put("fields", "value_symbol|passes|value_description|counts_into_average|date_modified|date_acquisition|comment");

            JSONObject resp = UsosApi.get("services/grades/terms2", gradeParams);
            JSONObject termData = resp.optJSONObject(listaSemestrowId);

            if (termData != null) {
                java.util.Iterator<String> courseIdIter = termData.keys();
                while (courseIdIter.hasNext()) {
                    String courseId = courseIdIter.next();
                    JSONObject courseData = termData.optJSONObject(courseId);
                    if (courseData == null) continue;

                    String name = courseNames.containsKey(courseId) ? courseNames.get(courseId) : courseId;
                    double ects = courseEcts.containsKey(courseId) ? courseEcts.get(courseId) : 0.0;
                    boolean hasAnyGrade = false;

                    // course_grades is an ARRAY of grade objects (course-level / final grades)
                    JSONArray courseGradesArr = courseData.optJSONArray("course_grades");
                    if (courseGradesArr != null && courseGradesArr.length() > 0) {
                        for (int i = 0; i < courseGradesArr.length(); i++) {
                            JSONObject gradeObj = courseGradesArr.optJSONObject(i);
                            Grade g = buildUsosGrade(gradeObj, name, ects, "Ocena końcowa");
                            if (g != null) {
                                grades.add(g);
                                hasAnyGrade = true;
                            }
                        }
                    }

                    // course_units_grades is a DICT: unit_id → ARRAY of grade objects
                    JSONObject unitGradesDict = courseData.optJSONObject("course_units_grades");
                    if (unitGradesDict != null) {
                        java.util.Iterator<String> unitIds = unitGradesDict.keys();
                        while (unitIds.hasNext()) {
                            String unitId = unitIds.next();
                            JSONArray unitGradesArr = unitGradesDict.optJSONArray(unitId);
                            if (unitGradesArr == null || unitGradesArr.length() == 0) continue;

                            for (int i = 0; i < unitGradesArr.length(); i++) {
                                JSONObject gradeObj = unitGradesArr.optJSONObject(i);
                                Grade g = buildUsosGrade(gradeObj, name, ects, "Zaliczenie");
                                if (g != null) {
                                    grades.add(g);
                                    hasAnyGrade = true;
                                }
                            }
                        }
                    }

                    // Placeholder for courses with no grades yet
                    if (!hasAnyGrade) {
                        Grade placeholder = new Grade();
                        placeholder.subjectName = name;
                        placeholder.weight = ects;
                        placeholder.grade = "";
                        placeholder.type = "";
                        placeholder.teacher = "";
                        placeholder.date = "";
                        grades.add(placeholder);
                    }
                }
            }
        } else {
            // Original ZUT API path - unchanged
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
        }

        return grades;
    }

}
