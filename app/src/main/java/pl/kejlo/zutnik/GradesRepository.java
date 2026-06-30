package pl.kejlo.zutnik;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GradesRepository {

    private static final long STUDIES_TTL_MS = CachePolicy.STUDIES_TTL_MS;
    private static final long SEMESTERS_TTL_MS = CachePolicy.SEMESTERS_TTL_MS;

    private static final String PROGRAMME_FIELDS =
            "id|programme[id|name|description|faculty[id|name]|mode_of_studies|level_of_studies|level]|status|admission_date|is_primary|stages[id|name]";
    private static final String PROGRAMME_FALLBACK_FIELDS =
            "id|programme[id|name|description|mode_of_studies|level_of_studies|level]|status|admission_date|is_primary|stages[id|name]";
    private static final String PROGRAMME_MIN_FIELDS =
            "id|programme|status|admission_date|is_primary|stages";
    private static final String COURSE_FIELDS =
            "course_editions[course_id|course_name|term_id]|terms";
    private static final String COURSE_WITH_GRADES_FIELDS =
            "course_editions[course_id|course_name|term_id|grades[value_symbol|passes|value_description|exam_id|exam_session_number|date_modified|date_acquisition|counts_into_average|grade_type_id]]|terms";
    private static final String GRADE_FIELDS =
            "value_symbol|passes|value_description|exam_id|exam_session_number|date_modified|date_acquisition|counts_into_average|grade_type_id";
    private static final String GRADE_WITH_CONTEXT_FIELDS =
            "value_symbol|passes|value_description|date_modified|date_acquisition|counts_into_average|grade_type_id|course_edition[course_id|term_id|course[id|name|ects_credits_simplified]|ects_credits_simplified]|course[id|name|ects_credits_simplified]";

    public static final class CreditSummary {
        public final String studentProgrammeId;
        public final Double programmeUsed;
        public final Double overallUsed;
        public final List<ProgrammeCredit> programmeCredits;

        CreditSummary(String studentProgrammeId, Double programmeUsed, Double overallUsed) {
            this(studentProgrammeId, programmeUsed, overallUsed, Collections.emptyList());
        }

        CreditSummary(
                String studentProgrammeId,
                Double programmeUsed,
                Double overallUsed,
                List<ProgrammeCredit> programmeCredits) {
            this.studentProgrammeId = studentProgrammeId;
            this.programmeUsed = programmeUsed;
            this.overallUsed = overallUsed;
            this.programmeCredits = programmeCredits != null
                    ? Collections.unmodifiableList(new ArrayList<>(programmeCredits))
                    : Collections.emptyList();
        }
    }

    public static final class ProgrammeCredit {
        public final String studentProgrammeId;
        public final String label;
        public final Double used;

        ProgrammeCredit(String studentProgrammeId, String label, Double used) {
            this.studentProgrammeId = studentProgrammeId;
            this.label = label;
            this.used = used;
        }
    }

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

    private static final class CourseInfo {
        String id;
        String name;
        String activityType;
        double ects;
        final List<JSONObject> embeddedGrades = new ArrayList<>();
    }

    private static StudiesCacheEntry sStudiesCache;
    private static final Map<String, SemesterCacheEntry> sSemCacheByStudy =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void invalidateMemoryCache() {
        sStudiesCache = null;
        sSemCacheByStudy.clear();
    }

    private boolean isNetworkAvailable() {
        Context appContext = ZutnikSession.getAppContextOrNull();
        if (appContext == null) {
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

    private String localizedValue(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            return firstNonEmpty(
                    obj.optString("pl", ""),
                    obj.optString("en", ""),
                    obj.optString("name", ""));
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        return firstNonEmpty(value instanceof String ? (String) value : "");
    }

    private String localizedField(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        return localizedValue(obj.opt(key));
    }

    private double parseFlexibleDouble(Object raw) {
        if (raw == null) {
            return 0.0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        text = text.replace("zł", "")
                .replace("PLN", "")
                .replace("pln", "")
                .replace(" ", "")
                .replace(",", ".");
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private Double parseNullableFlexibleDouble(Object raw) {
        if (raw == null || JSONObject.NULL.equals(raw)) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        text = text.replace("zł", "")
                .replace("PLN", "")
                .replace("pln", "")
                .replace("\"", "")
                .replace(" ", "")
                .replace(",", ".");
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeStudyMode(Object value) {
        String text = localizedValue(value);
        if (!text.isEmpty()) {
            return text;
        }

        if (value instanceof Number) {
            int num = ((Number) value).intValue();
            if (num == 1) return "Stacjonarne";
            if (num == 2) return "Niestacjonarne";
        }
        return "";
    }

    private String mapStudyStatus(String status) {
        switch (String.valueOf(status)) {
            case "active":
                return "Aktywny";
            case "cancelled":
                return "Anulowany";
            case "graduated_diploma":
                return "Absolwent";
            case "graduated_end_of_study":
            case "graduated_before_diploma":
                return "Absolwent (ukończone)";
            default:
                return firstNonEmpty(status);
        }
    }

    private JSONArray fetchStudentProgrammesRaw() throws IOException, JSONException {
        Map<String, String> baseParams = new HashMap<>();
        baseParams.put("active_only", "false");
        baseParams.put("old_programs", "false");

        String[] fields = { PROGRAMME_FIELDS, PROGRAMME_FALLBACK_FIELDS, PROGRAMME_MIN_FIELDS };
        for (String selector : fields) {
            try {
                Map<String, String> params = new HashMap<>(baseParams);
                params.put("fields", selector);
                return UsosApi.getArray("services/progs/student", params);
            } catch (IOException | JSONException ignored) {
                // Some USOS installations reject richer selectors. Try a simpler one.
            }
        }
        return new JSONArray();
    }

    private JSONObject fetchCoursesResponse(boolean includeGrades, String activeTermsOnly)
            throws IOException, JSONException {
        Map<String, String> params = new HashMap<>();
        params.put("fields", includeGrades ? COURSE_WITH_GRADES_FIELDS : COURSE_FIELDS);
        params.put("active_terms_only", activeTermsOnly);
        try {
            return UsosApi.get("services/courses/user", params);
        } catch (IOException | JSONException e) {
            if (includeGrades) {
                params.put("fields", COURSE_FIELDS);
                return UsosApi.get("services/courses/user", params);
            }
            throw e;
        }
    }

    private String termSeason(JSONObject term) {
        String id = firstNonEmpty(term != null ? term.optString("id", "") : "");
        String upperId = id.toUpperCase(Locale.ROOT);
        String name = localizedField(term, "name").toLowerCase(Locale.ROOT);
        if (upperId.endsWith("L") || name.contains("letni") || name.contains("summer")) {
            return "Letni";
        }
        if (upperId.endsWith("Z") || name.contains("zimowy") || name.contains("winter")) {
            return "Zimowy";
        }
        return "";
    }

    private String academicYearFromTerm(JSONObject term) {
        String name = localizedField(term, "name");
        java.util.regex.Matcher nameYear = java.util.regex.Pattern
                .compile("20\\d{2}\\s*[/\\\\-]\\s*(?:20)?\\d{2}")
                .matcher(name);
        if (nameYear.find()) {
            return nameYear.group().replaceAll("\\s+", "");
        }

        String id = firstNonEmpty(term != null ? term.optString("id", "") : "");
        java.util.regex.Matcher idYear = java.util.regex.Pattern
                .compile("(20\\d{2})\\D?(\\d{2})?")
                .matcher(id);
        if (idYear.find()) {
            String start = idYear.group(1);
            String end = idYear.group(2) != null
                    ? "20" + idYear.group(2)
                    : String.valueOf(Integer.parseInt(start) + 1);
            return start + "/" + end;
        }

        return firstNonEmpty(name, id);
    }

    private List<String> getTermIdsForCourses(JSONObject coursesResponse) {
        Set<String> ids = new HashSet<>();
        JSONArray terms = coursesResponse != null ? coursesResponse.optJSONArray("terms") : null;
        if (terms != null) {
            for (int i = 0; i < terms.length(); i++) {
                JSONObject term = terms.optJSONObject(i);
                String id = firstNonEmpty(term != null ? term.optString("id", "") : "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }

        JSONObject editions = coursesResponse != null ? coursesResponse.optJSONObject("course_editions") : null;
        if (editions != null) {
            Iterator<String> keys = editions.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id.trim());
                }
            }
        }

        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return sorted;
    }

    private List<String> getCourseIdsForTerm(JSONObject coursesResponse, String termId) {
        Set<String> ids = new HashSet<>();
        JSONObject editions = coursesResponse != null ? coursesResponse.optJSONObject("course_editions") : null;
        JSONArray termEditions = editions != null ? editions.optJSONArray(termId) : null;
        if (termEditions == null) {
            return new ArrayList<>();
        }

        for (int i = 0; i < termEditions.length(); i++) {
            JSONObject edition = termEditions.optJSONObject(i);
            if (edition == null) {
                continue;
            }
            JSONObject course = edition.optJSONObject("course");
            String courseId = firstNonEmpty(
                    course != null ? course.optString("id", "") : "",
                    edition.optString("course_id", ""),
                    edition.optString("id", ""));
            if (!courseId.isEmpty()) {
                ids.add(courseId);
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> getCourseIdsForTerms(JSONObject coursesResponse, List<String> termIds) {
        Set<String> ids = new HashSet<>();
        if (termIds == null || termIds.isEmpty()) {
            return new ArrayList<>();
        }
        for (String termId : termIds) {
            ids.addAll(getCourseIdsForTerm(coursesResponse, termId));
        }
        return new ArrayList<>(ids);
    }

    private List<Semester> mapSemesters(JSONObject coursesResponse) {
        Map<String, JSONObject> termsById = new HashMap<>();
        JSONArray terms = coursesResponse != null ? coursesResponse.optJSONArray("terms") : null;
        if (terms != null) {
            for (int i = 0; i < terms.length(); i++) {
                JSONObject term = terms.optJSONObject(i);
                if (term == null) {
                    continue;
                }
                String id = firstNonEmpty(term.optString("id", ""));
                if (!id.isEmpty()) {
                    termsById.put(id, term);
                }
            }
        }

        JSONObject editions = coursesResponse != null ? coursesResponse.optJSONObject("course_editions") : null;
        if (editions != null) {
            Iterator<String> keys = editions.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                if (!termsById.containsKey(id)) {
                    JSONObject placeholder = new JSONObject();
                    try {
                        placeholder.put("id", id);
                    } catch (JSONException ignored) {
                    }
                    termsById.put(id, placeholder);
                }
            }
        }

        List<JSONObject> orderedTerms = new ArrayList<>(termsById.values());
        orderedTerms.sort((left, right) -> {
            String leftKey = firstNonEmpty(left.optString("start_date", ""), left.optString("id", ""));
            String rightKey = firstNonEmpty(right.optString("start_date", ""), right.optString("id", ""));
            return leftKey.compareTo(rightKey);
        });

        List<Semester> semesters = new ArrayList<>(orderedTerms.size());
        for (int i = 0; i < orderedTerms.size(); i++) {
            JSONObject term = orderedTerms.get(i);
            Semester semester = new Semester();
            semester.listaSemestrowId = firstNonEmpty(term.optString("id", ""));
            semester.nrSemestru = String.valueOf(i + 1);
            semester.pora = termSeason(term);
            semester.rokAkademicki = academicYearFromTerm(term);
            semester.status = term.optBoolean("is_active", false) ? "Aktywny" : "Zakończony";
            semesters.add(semester);
        }
        return semesters;
    }

    private boolean hasGradeValue(JSONObject entry) {
        if (entry == null) {
            return false;
        }
        return !extractGradeValue(entry).isEmpty()
                || entry.has("passes")
                || entry.has("exam_id")
                || entry.has("exam_session_number")
                || entry.has("date_acquisition")
                || entry.has("date_modified")
                || entry.has("grade_type_id")
                || (entry.opt("grade") instanceof JSONObject);
    }

    private String extractGradeValue(JSONObject entry) {
        if (entry == null) {
            return "";
        }

        JSONObject grade = entry.optJSONObject("grade");
        return firstNonEmpty(
                entry.optString("value_symbol", ""),
                localizedField(entry, "value_description"),
                entry.optString("value", ""),
                entry.optString("symbol", ""),
                grade != null ? grade.optString("value_symbol", "") : "",
                grade != null ? localizedField(grade, "value_description") : "",
                grade != null ? grade.optString("value", "") : "",
                grade != null ? grade.optString("symbol", "") : "");
    }

    private boolean isGradeEntryLike(Object value) {
        if (!(value instanceof JSONObject)) {
            return false;
        }
        JSONObject entry = (JSONObject) value;
        return hasGradeValue(entry);
    }

    private List<JSONObject> flattenGradeEntries(Object value) {
        List<JSONObject> result = new ArrayList<>();
        if (value == null) {
            return result;
        }

        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                result.addAll(flattenGradeEntries(arr.opt(i)));
            }
            return result;
        }

        if (!(value instanceof JSONObject)) {
            return result;
        }

        JSONObject obj = (JSONObject) value;
        if (isGradeEntryLike(obj)) {
            result.add(obj);
            return result;
        }

        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            result.addAll(flattenGradeEntries(obj.opt(keys.next())));
        }
        return result;
    }

    private String courseActivityLabel(String courseId) {
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("-([A-Z]{2})$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(firstNonEmpty(courseId));
        if (!match.find()) {
            return "";
        }
        String suffix = match.group(1).toUpperCase(Locale.ROOT);
        switch (suffix) {
            case "CW":
                return "Ćwiczenia";
            case "LB":
                return "Laboratorium";
            case "WK":
                return "Wykład";
            default:
                return "";
        }
    }

    private Map<String, CourseInfo> buildCourseMapForTerm(JSONObject coursesResponse, String termId) {
        Map<String, CourseInfo> courseMap = new HashMap<>();
        JSONObject editions = coursesResponse != null ? coursesResponse.optJSONObject("course_editions") : null;
        JSONArray termEditions = editions != null ? editions.optJSONArray(termId) : null;
        if (termEditions == null) {
            return courseMap;
        }

        for (int i = 0; i < termEditions.length(); i++) {
            JSONObject edition = termEditions.optJSONObject(i);
            if (edition == null) {
                continue;
            }

            JSONObject course = edition.optJSONObject("course");
            String id = firstNonEmpty(
                    course != null ? course.optString("id", "") : "",
                    edition.optString("course_id", ""),
                    edition.optString("id", ""));
            if (id.isEmpty()) {
                continue;
            }

            CourseInfo info = new CourseInfo();
            info.id = id;
            info.name = firstNonEmpty(
                    localizedField(course, "name"),
                    localizedField(edition, "course_name"),
                    edition.optString("course_name", ""),
                    id);
            info.activityType = courseActivityLabel(id);
            info.ects = parseFlexibleDouble(
                    edition.has("ects_credits_simplified")
                            ? edition.opt("ects_credits_simplified")
                            : (course != null ? course.opt("ects_credits_simplified") : null));

            for (JSONObject embedded : flattenGradeEntries(edition.opt("grades"))) {
                info.embeddedGrades.add(embedded);
            }

            courseMap.put(id, info);
        }
        return courseMap;
    }

    private String gradeTypeLabel(JSONObject entry, String fallback) {
        String raw = firstNonEmpty(localizedField(entry, "grade_type_id"), entry.optString("grade_type_id", ""));
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.contains("course") || normalized.contains("final") || normalized.contains("konc")) {
            return "Ocena końcowa";
        }
        return fallback;
    }

    private Grade mapSingleGrade(JSONObject entry, CourseInfo course, String fallbackType, double ects) {
        String value = extractGradeValue(entry);
        if (value.isEmpty()) {
            return null;
        }

        Grade grade = new Grade();
        grade.subjectName = firstNonEmpty(course != null ? course.name : "", course != null ? course.id : "");
        grade.courseId = course != null ? course.id : "";
        grade.grade = value;
        grade.weight = ects;
        grade.type = firstNonEmpty(
                course != null ? course.activityType : "",
                gradeTypeLabel(entry, fallbackType));
        grade.gradeDescription = localizedField(entry, "value_description");
        grade.passes = entry.optBoolean("passes", false);
        grade.countsIntoAverage = entry.optBoolean("counts_into_average", false);
        grade.comment = firstNonEmpty(entry.optString("comment", ""));
        grade.dateAcquisition = firstNonEmpty(entry.optString("date_acquisition", ""));
        grade.dateModified = firstNonEmpty(entry.optString("date_modified", ""));
        grade.examId = firstNonEmpty(entry.optString("exam_id", ""));
        grade.examSessionNumber = entry.optInt("exam_session_number", 0);

        String date = firstNonEmpty(grade.dateAcquisition, grade.dateModified);
        grade.date = date.contains("T")
                ? date.split("T")[0]
                : (date.contains(" ") ? date.split(" ")[0] : date);
        return grade;
    }

    private String latestGradeTermId(JSONObject entry) {
        if (entry == null) {
            return "";
        }
        JSONObject edition = entry.optJSONObject("course_edition");
        return firstNonEmpty(
                edition != null ? edition.optString("term_id", "") : "",
                entry.optString("term_id", ""));
    }

    private CourseInfo latestGradeCourse(JSONObject entry) {
        CourseInfo info = new CourseInfo();
        if (entry == null) {
            return info;
        }
        JSONObject edition = entry.optJSONObject("course_edition");
        JSONObject course = null;
        if (edition != null) {
            course = edition.optJSONObject("course");
        }
        if (course == null) {
            course = entry.optJSONObject("course");
        }

        info.id = firstNonEmpty(
                course != null ? course.optString("id", "") : "",
                edition != null ? edition.optString("course_id", "") : "",
                entry.optString("course_id", ""));
        info.name = firstNonEmpty(
                localizedField(course, "name"),
                localizedField(edition, "course_name"),
                edition != null ? edition.optString("course_name", "") : "",
                info.id);
        info.activityType = courseActivityLabel(info.id);
        info.ects = parseFlexibleDouble(
                edition != null && edition.has("ects_credits_simplified")
                        ? edition.opt("ects_credits_simplified")
                        : (course != null ? course.opt("ects_credits_simplified") : null));
        return info;
    }

    private Grade mapLatestGrade(JSONObject entry, JSONObject ectsResponse) {
        CourseInfo course = latestGradeCourse(entry);
        if (firstNonEmpty(course.id, course.name).isEmpty()) {
            return null;
        }
        String termId = latestGradeTermId(entry);
        JSONObject ectsByCourse = ectsResponse != null ? ectsResponse.optJSONObject(termId) : null;
        double ects = parseFlexibleDouble(ectsByCourse != null ? ectsByCourse.opt(course.id) : null);
        if (ects <= 0.0) {
            ects = course.ects;
        }
        return mapSingleGrade(entry, course, course.activityType.isEmpty() ? "Ocena końcowa" : course.activityType, ects);
    }

    private String buildGradeDedupeKey(Grade grade) {
        return (
                firstNonEmpty(grade.subjectName) + "|" +
                firstNonEmpty(grade.grade) + "|" +
                firstNonEmpty(grade.type) + "|" +
                firstNonEmpty(grade.date) + "|" +
                grade.weight
        ).toLowerCase(Locale.ROOT);
    }

    private int countGradesForTerm(JSONObject gradesResponse, String termId) {
        JSONObject termGrades = gradesResponse != null ? gradesResponse.optJSONObject(termId) : null;
        if (termGrades == null) {
            return 0;
        }

        int count = 0;
        Iterator<String> courseIds = termGrades.keys();
        while (courseIds.hasNext()) {
            JSONObject courseGradeData = termGrades.optJSONObject(courseIds.next());
            if (courseGradeData == null) {
                continue;
            }

            for (JSONObject entry : flattenGradeEntries(courseGradeData.opt("course_grades"))) {
                if (hasGradeValue(entry)) {
                    count++;
                }
            }

            JSONObject unitGrades = courseGradeData.optJSONObject("course_units_grades");
            if (unitGrades == null) {
                continue;
            }
            Iterator<String> unitIds = unitGrades.keys();
            while (unitIds.hasNext()) {
                for (JSONObject entry : flattenGradeEntries(unitGrades.opt(unitIds.next()))) {
                    if (hasGradeValue(entry)) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private int countGradesForTerms(JSONObject gradesResponse, List<String> termIds) {
        if (termIds == null || termIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String termId : termIds) {
            count += countGradesForTerm(gradesResponse, termId);
        }
        return count;
    }

    private JSONObject fetchGradesTerms2(String termId, List<String> courseIds)
            throws IOException, JSONException {
        Map<String, String> params = new HashMap<>();
        params.put("term_ids", termId);
        if (courseIds != null && !courseIds.isEmpty()) {
            params.put("course_ids", joinWithPipe(courseIds));
        }
        params.put("fields", GRADE_FIELDS);
        return UsosApi.get("services/grades/terms2", params);
    }

    private boolean isUsosGatewayError(IOException error) {
        String message = error != null ? error.getMessage() : null;
        if (message == null || message.isEmpty()) {
            return false;
        }
        return message.contains("HTTP 502")
                || message.contains("HTTP 503")
                || message.contains("HTTP 504")
                || message.contains("HTTP 500");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(750L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JSONObject tryFetchGradesTerms2(String termIds, List<String> courseIds) {
        if (Thread.currentThread().isInterrupted()) {
            return new JSONObject();
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                sleepBeforeRetry();
                if (Thread.currentThread().isInterrupted()) {
                    return new JSONObject();
                }
            }
            try {
                return fetchGradesTerms2(termIds, courseIds);
            } catch (IOException e) {
                if (!isUsosGatewayError(e)) {
                    break;
                }
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        if (courseIds != null && !courseIds.isEmpty()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    sleepBeforeRetry();
                    if (Thread.currentThread().isInterrupted()) {
                        return new JSONObject();
                    }
                }
                try {
                    return fetchGradesTerms2(termIds, Collections.emptyList());
                } catch (IOException e) {
                    if (!isUsosGatewayError(e)) {
                        break;
                    }
                } catch (JSONException e) {
                    return new JSONObject();
                }
            }
        }

        return new JSONObject();
    }

    private JSONObject resolveGradesForTerm(String termId, List<String> courseIds) {
        if (termId == null || termId.trim().isEmpty()) {
            return new JSONObject();
        }

        JSONObject gradesResponse = tryFetchGradesTerms2(termId, courseIds);
        if (countGradesForTerm(gradesResponse, termId) > 0) {
            return gradesResponse;
        }

        if (courseIds != null && !courseIds.isEmpty()) {
            try {
                JSONObject byCourse = fetchCourseEditionGradesByCourse(termId, courseIds);
                if (countGradesForTerm(byCourse, termId) > 0) {
                    return byCourse;
                }
            } catch (IOException | JSONException ignored) {
                // Fall back to embedded grades from courses/user when available.
            }
        }

        return gradesResponse;
    }

    private JSONObject resolveGradesForTerms(List<String> termIds, JSONObject coursesResponse) {
        if (termIds == null || termIds.isEmpty()) {
            return new JSONObject();
        }

        List<String> courseIds = getCourseIdsForTerms(coursesResponse, termIds);
        JSONObject gradesResponse = tryFetchGradesTerms2(joinWithPipe(termIds), courseIds);
        if (countGradesForTerms(gradesResponse, termIds) > 0) {
            return gradesResponse;
        }

        JSONObject merged = new JSONObject();
        for (String termId : termIds) {
            List<String> termCourseIds = getCourseIdsForTerm(coursesResponse, termId);
            if (termCourseIds.isEmpty()) {
                continue;
            }
            try {
                JSONObject byCourse = fetchCourseEditionGradesByCourse(termId, termCourseIds);
                JSONObject termData = byCourse.optJSONObject(termId);
                if (termData != null && termData.length() > 0) {
                    merged.put(termId, termData);
                }
            } catch (IOException | JSONException ignored) {
                // Try remaining terms.
            }
        }

        if (countGradesForTerms(merged, termIds) > 0) {
            return merged;
        }

        return gradesResponse.length() > 0 ? gradesResponse : merged;
    }

    private JSONObject fetchCourseEditionGradesByCourse(String termId, List<String> courseIds)
            throws IOException, JSONException {
        JSONObject termData = new JSONObject();
        for (String courseId : courseIds) {
            if (courseId == null || courseId.trim().isEmpty()) {
                continue;
            }

            try {
                Map<String, String> params = new HashMap<>();
                params.put("course_id", courseId);
                params.put("term_id", termId);
                params.put("fields", GRADE_FIELDS);
                JSONObject courseData = UsosApi.get("services/grades/course_edition2", params);
                if (courseData != null && courseData.length() > 0) {
                    termData.put(courseId, courseData);
                }
            } catch (IOException | JSONException ignored) {
                // Leave this course empty and continue with the remaining ones.
            }
        }

        JSONObject wrapper = new JSONObject();
        wrapper.put(termId, termData);
        return wrapper;
    }

    private String joinWithPipe(List<String> values) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append('|');
            }
            sb.append(value.trim());
            first = false;
        }
        return sb.toString();
    }

    private List<Grade> mapGrades(
            String termId,
            JSONObject coursesResponse,
            JSONObject ectsResponse,
            JSONObject gradesResponse) {

        Map<String, CourseInfo> courses = buildCourseMapForTerm(coursesResponse, termId);
        JSONObject termGrades = gradesResponse != null ? gradesResponse.optJSONObject(termId) : null;
        if (termGrades == null) {
            termGrades = new JSONObject();
        }

        Iterator<String> termGradeKeys = termGrades.keys();
        while (termGradeKeys.hasNext()) {
            String courseId = termGradeKeys.next();
            if (courses.containsKey(courseId)) {
                continue;
            }
            CourseInfo info = new CourseInfo();
            info.id = courseId;
            info.name = courseId;
            info.activityType = courseActivityLabel(courseId);
            courses.put(courseId, info);
        }

        JSONObject ectsByCourse = ectsResponse != null ? ectsResponse.optJSONObject(termId) : null;
        List<CourseInfo> orderedCourses = new ArrayList<>(courses.values());
        orderedCourses.sort(Comparator.comparing(
                left -> firstNonEmpty(left != null ? left.name : "", left != null ? left.id : ""),
                String::compareToIgnoreCase));

        List<Grade> grades = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (CourseInfo course : orderedCourses) {
            JSONObject courseGradeData = termGrades.optJSONObject(course.id);
            double ects = parseFlexibleDouble(ectsByCourse != null ? ectsByCourse.opt(course.id) : null);
            if (ects <= 0.0) {
                ects = course.ects;
            }

            int beforeCount = grades.size();
            if (courseGradeData != null) {
                for (JSONObject entry : flattenGradeEntries(courseGradeData.opt("course_grades"))) {
                    pushGrade(grades, seen, mapSingleGrade(entry, course,
                            course.activityType.isEmpty() ? "Ocena końcowa" : course.activityType, ects));
                }

                JSONObject unitGrades = courseGradeData.optJSONObject("course_units_grades");
                if (unitGrades != null) {
                    Iterator<String> unitIds = unitGrades.keys();
                    while (unitIds.hasNext()) {
                        for (JSONObject entry : flattenGradeEntries(unitGrades.opt(unitIds.next()))) {
                            pushGrade(grades, seen, mapSingleGrade(entry, course, "Zaliczenie", ects));
                        }
                    }
                }
            }

            if (grades.size() == beforeCount && !course.embeddedGrades.isEmpty()) {
                for (JSONObject entry : course.embeddedGrades) {
                    pushGrade(grades, seen, mapSingleGrade(entry, course,
                            course.activityType.isEmpty() ? "Ocena końcowa" : course.activityType, ects));
                }
            }
        }

        grades.sort((left, right) -> {
            int subjectOrder = firstNonEmpty(left.subjectName).compareToIgnoreCase(firstNonEmpty(right.subjectName));
            if (subjectOrder != 0) {
                return subjectOrder;
            }
            int leftFinal = "Ocena końcowa".equalsIgnoreCase(firstNonEmpty(left.type)) ? 0 : 1;
            int rightFinal = "Ocena końcowa".equalsIgnoreCase(firstNonEmpty(right.type)) ? 0 : 1;
            if (leftFinal != rightFinal) {
                return leftFinal - rightFinal;
            }
            int typeOrder = firstNonEmpty(left.type).compareToIgnoreCase(firstNonEmpty(right.type));
            if (typeOrder != 0) {
                return typeOrder;
            }
            return firstNonEmpty(left.date).compareToIgnoreCase(firstNonEmpty(right.date));
        });
        return grades;
    }

    private List<Grade> mapGradesForTerms(
            List<String> termIds,
            JSONObject coursesResponse,
            JSONObject ectsResponse,
            JSONObject gradesResponse,
            JSONArray latestGrades) {
        if (termIds == null || termIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Grade> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> termScope = new HashSet<>(termIds);

        for (String termId : termIds) {
            for (Grade grade : mapGrades(termId, coursesResponse, ectsResponse, gradesResponse)) {
                pushGrade(out, seen, grade);
            }
        }

        if (latestGrades != null) {
            for (int i = 0; i < latestGrades.length(); i++) {
                JSONObject entry = latestGrades.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String termId = latestGradeTermId(entry);
                if (!termScope.contains(termId)) {
                    continue;
                }
                pushGrade(out, seen, mapLatestGrade(entry, ectsResponse));
            }
        }

        out.sort((left, right) -> {
            int subjectOrder = firstNonEmpty(left.subjectName).compareToIgnoreCase(firstNonEmpty(right.subjectName));
            if (subjectOrder != 0) {
                return subjectOrder;
            }
            int typeOrder = firstNonEmpty(left.type).compareToIgnoreCase(firstNonEmpty(right.type));
            if (typeOrder != 0) {
                return typeOrder;
            }
            return firstNonEmpty(left.date).compareToIgnoreCase(firstNonEmpty(right.date));
        });
        return out;
    }

    private void pushGrade(List<Grade> grades, Set<String> seen, Grade grade) {
        if (grade == null || firstNonEmpty(grade.grade).isEmpty()) {
            return;
        }
        String key = buildGradeDedupeKey(grade);
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        grades.add(grade);
    }

    public List<Study> loadStudies() throws IOException, JSONException {
        return loadStudies(false);
    }

    public List<Study> loadStudies(boolean forceRefresh) throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (session.isDemoLogin()) {
            List<Study> demoStudies = DemoDataProvider.createStudies();
            session.setStudies(demoStudies);
            return demoStudies;
        }
        if (!session.isUsosLogin() || session.getUserId() == null) {
            return Collections.emptyList();
        }

        String userId = session.getUserId();
        long now = System.currentTimeMillis();
        boolean online = isNetworkAvailable();

        if (!forceRefresh
                && sStudiesCache != null
                && userId.equals(sStudiesCache.userId)
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
            JSONArray programmes = fetchStudentProgrammesRaw();
            List<Study> studies = new ArrayList<>();
            for (int i = 0; i < programmes.length(); i++) {
                JSONObject row = programmes.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                JSONObject programme = row.optJSONObject("programme");
                String studyId = firstNonEmpty(
                        row.optString("id", ""),
                        programme != null ? programme.optString("id", "") : "");
                if (studyId.isEmpty()) {
                    continue;
                }

                String label = firstNonEmpty(
                        localizedField(programme, "name"),
                        localizedField(programme, "description"),
                        programme != null ? programme.optString("id", "") : "",
                        studyId);
                String mode = normalizeStudyMode(programme != null ? programme.opt("mode_of_studies") : null);
                if (!mode.isEmpty()) {
                    label += " (" + mode.toLowerCase(new Locale("pl", "PL")) + ")";
                }

                Study study = new Study();
                study.przynaleznoscId = studyId;
                study.label = label;
                studies.add(study);
            }

            if (studies.isEmpty()) {
                Study fallback = new Study();
                fallback.przynaleznoscId = "usos-profile";
                fallback.label = "Profil USOS";
                studies.add(fallback);
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
        ZutnikSession session = ZutnikSession.getInstance();
        if (session.isDemoLogin()) {
            return DemoDataProvider.createSemesters();
        }
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
            activeStudyId = "usos-profile";
        }

        long now = System.currentTimeMillis();
        String semesterCacheKey = session.getUserId() + "_" + activeStudyId;
        SemesterCacheEntry cachedSemesters = sSemCacheByStudy.get(semesterCacheKey);
        if (!forceRefresh
                && cachedSemesters != null
                && (now - cachedSemesters.ts) < SEMESTERS_TTL_MS) {
            return copySemesters(cachedSemesters.list);
        }

        try {
            JSONObject coursesResponse = fetchCoursesResponse(false, "false");
            List<Semester> semesters = mapSemesters(coursesResponse);

            SemesterCacheEntry entry = new SemesterCacheEntry();
            entry.ts = now;
            entry.przynaleznoscId = activeStudyId;
            entry.list = copySemesters(semesters);
            sSemCacheByStudy.put(semesterCacheKey, entry);
            return copySemesters(semesters);
        } catch (IOException | JSONException e) {
            if (!forceRefresh && cachedSemesters != null && cachedSemesters.list != null) {
                return copySemesters(cachedSemesters.list);
            }
            throw e;
        }
    }

    private JSONArray fetchLatestGradesForTerms(List<String> termIds) {
        if (termIds == null || termIds.isEmpty()) {
            return new JSONArray();
        }
        try {
            Map<String, String> params = new HashMap<>();
            params.put("days", "4000");
            params.put("fields", GRADE_WITH_CONTEXT_FIELDS);
            JSONArray raw = UsosApi.getArray("services/grades/latest", params);
            JSONArray filtered = new JSONArray();
            Set<String> scope = new HashSet<>(termIds);
            for (int i = 0; i < raw.length(); i++) {
                JSONObject entry = raw.optJSONObject(i);
                if (entry != null && scope.contains(latestGradeTermId(entry))) {
                    filtered.put(entry);
                }
            }
            return filtered;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    public List<Grade> loadCurrentGrades() throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (session.isDemoLogin()) {
            return DemoDataProvider.loadCurrentGrades();
        }
        if (!session.isUsosLogin() || session.getUserId() == null) {
            return Collections.emptyList();
        }

        if (Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        JSONObject coursesResponse = fetchCoursesResponse(true, "true");
        List<String> termIds = getTermIdsForCourses(coursesResponse);
        if (termIds.isEmpty()) {
            return Collections.emptyList();
        }
        JSONObject ectsResponse;
        try {
            ectsResponse = UsosApi.get("services/courses/user_ects_points", null);
        } catch (IOException | JSONException ignored) {
            ectsResponse = new JSONObject();
        }

        JSONObject gradesTermsResponse = resolveGradesForTerms(termIds, coursesResponse);
        JSONArray latestGrades = countGradesForTerms(gradesTermsResponse, termIds) > 0
                ? new JSONArray()
                : fetchLatestGradesForTerms(termIds);

        return mapGradesForTerms(termIds, coursesResponse, ectsResponse, gradesTermsResponse, latestGrades);
    }

    private Double parseCreditsResponse(String raw) {
        String trimmed = raw != null ? raw.trim() : "";
        if (trimmed.isEmpty()) {
            return null;
        }
        Double scalar = parseNullableFlexibleDouble(trimmed);
        if (scalar != null) {
            return scalar;
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            String[] keys = { "used", "used_sum", "sum", "value", "credits", "ects", "points", "total" };
            for (String key : keys) {
                if (!obj.has(key)) {
                    continue;
                }
                Double parsed = parseNullableFlexibleDouble(obj.opt(key));
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    public CreditSummary loadCreditSummary() throws IOException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (session.isDemoLogin()) {
            return DemoDataProvider.loadCreditSummary();
        }
        if (!session.isUsosLogin() || session.getUserId() == null) {
            return new CreditSummary("", null, null);
        }

        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            try {
                studies = loadStudies(false);
            } catch (Exception ignored) {
                studies = Collections.emptyList();
            }
        }

        Study active = session.getActiveStudy();
        String studyId = active != null ? normalizeStudyId(active.przynaleznoscId) : null;
        Double programmeUsed = null;
        List<ProgrammeCredit> programmeCredits = new ArrayList<>();

        if (studies != null) {
            for (Study study : studies) {
                if (study == null) {
                    continue;
                }
                String id = normalizeStudyId(study.przynaleznoscId);
                if (id == null || "usos-profile".equals(id)) {
                    continue;
                }
                Double used = null;
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("students_programme_id", id);
                    used = parseCreditsResponse(UsosApi.getRaw("services/credits/used_sum", params));
                } catch (Exception ignored) {
                }
                programmeCredits.add(new ProgrammeCredit(id, firstNonEmpty(study.label, id), used));
                if (id.equals(studyId)) {
                    programmeUsed = used;
                }
            }
        }

        if (programmeUsed == null && studyId != null && !"usos-profile".equals(studyId)) {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("students_programme_id", studyId);
                programmeUsed = parseCreditsResponse(UsosApi.getRaw("services/credits/used_sum", params));
            } catch (Exception ignored) {
            }
        }

        Double overallUsed = null;
        try {
            overallUsed = parseCreditsResponse(UsosApi.getRaw("services/credits/used_sum", null));
        } catch (Exception ignored) {
        }

        if (overallUsed == null) {
            double sum = 0.0;
            boolean hasAny = false;
            for (ProgrammeCredit credit : programmeCredits) {
                if (credit.used != null && credit.used >= 0.0 && !Double.isNaN(credit.used)) {
                    sum += credit.used;
                    hasAny = true;
                }
            }
            if (hasAny) {
                overallUsed = sum;
            }
        }

        return new CreditSummary(firstNonEmpty(studyId), programmeUsed, overallUsed, programmeCredits);
    }

    public List<Grade> loadGradesForSemester(Semester semester) throws IOException, JSONException {
        if (semester == null) {
            return Collections.emptyList();
        }
        return loadGradesForSemester(semester.listaSemestrowId);
    }

    public List<Grade> loadGradesForSemester(String listaSemestrowId)
            throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (session.isDemoLogin()) {
            return DemoDataProvider.loadGradesForSemester(listaSemestrowId);
        }
        if (!session.isUsosLogin()
                || session.getUserId() == null
                || listaSemestrowId == null
                || listaSemestrowId.trim().isEmpty()) {
            return Collections.emptyList();
        }

        if (Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        JSONObject coursesResponse = fetchCoursesResponse(false, "false");
        List<String> courseIds = getCourseIdsForTerm(coursesResponse, listaSemestrowId);

        JSONObject ectsResponse;
        try {
            ectsResponse = UsosApi.get("services/courses/user_ects_points", null);
        } catch (IOException | JSONException ignored) {
            ectsResponse = new JSONObject();
        }

        JSONObject gradesResponse = resolveGradesForTerm(listaSemestrowId, courseIds);
        List<Grade> grades = mapGrades(listaSemestrowId, coursesResponse, ectsResponse, gradesResponse);
        if (!grades.isEmpty()) {
            return grades;
        }

        try {
            JSONObject coursesWithEmbeddedGrades = fetchCoursesResponse(true, "false");
            grades = mapGrades(listaSemestrowId, coursesWithEmbeddedGrades, ectsResponse, gradesResponse);
        } catch (IOException | JSONException ignored) {
            // Keep the lightweight courses response result.
        }
        return grades;
    }
}
