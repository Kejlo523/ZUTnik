package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PlanSubjectFilterHelper {

    static final String PREFS_NAME = "zutnik_plan";
    static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";
    private static final String KEY_FILTER_CACHE_JSON = "plan_filters_cache_json";
    private static final String KEY_FILTER_CACHE_TS = "plan_filters_cache_ts";
    private static final long FILTER_CACHE_TTL_MS = CachePolicy.PLAN_FILTER_TTL_MS;

    private PlanSubjectFilterHelper() {
    }

    static Set<String> loadHiddenSubjectKeys(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>()));
    }

    static List<PlanRepository.SubjectFilterItem> loadAllFilterItems(Context context, boolean forceRefresh)
            throws IOException, JSONException {
        Context appContext = context.getApplicationContext();
        if (!forceRefresh) {
            List<PlanRepository.SubjectFilterItem> cached = loadFilterCache(appContext);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        List<PlanRepository.SubjectFilterItem> loaded = new PlanRepository(appContext).loadSubjectsForFilter(forceRefresh);
        if (loaded != null && !loaded.isEmpty()) {
            saveFilterCache(appContext, loaded);
        }
        return loaded != null ? loaded : new ArrayList<>();
    }

    static List<PlanRepository.SubjectFilterItem> filterVisibleItems(
            List<PlanRepository.SubjectFilterItem> items,
            Set<String> hiddenSubjectKeys) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> hidden = hiddenSubjectKeys != null ? hiddenSubjectKeys : new HashSet<>();
        List<PlanRepository.SubjectFilterItem> visible = new ArrayList<>();
        for (PlanRepository.SubjectFilterItem item : items) {
            if (item == null || item.filterKey == null || item.filterKey.isEmpty()) {
                continue;
            }
            if (!hidden.contains(item.filterKey)) {
                visible.add(item);
            }
        }
        return visible;
    }

    static String formatDisplayLabel(PlanRepository.SubjectFilterItem item) {
        if (item == null) {
            return "";
        }
        String label = GradesTextUtils.clean(item.label);
        String typeLabel = GradesTextUtils.clean(item.typeLabel);
        if (label.isEmpty()) {
            return typeLabel;
        }
        if (typeLabel.isEmpty()) {
            return label;
        }
        return label + " (" + typeLabel + ")";
    }

    static boolean gradeMatchesVisiblePlan(Grade grade, List<PlanRepository.SubjectFilterItem> visibleItems) {
        if (grade == null || visibleItems == null || visibleItems.isEmpty()) {
            return false;
        }

        String gradeSubject = GradesTextUtils.extractBaseSubject(grade.subjectName);
        if (gradeSubject.isEmpty()) {
            gradeSubject = GradesTextUtils.clean(grade.subjectName);
        }
        String normalizedGradeSubject = normalizeFilterString(gradeSubject);
        if (normalizedGradeSubject.isEmpty()) {
            return false;
        }

        String gradeTypeKey = resolveGradeTypeKey(grade);
        boolean subjectMatched = false;
        for (PlanRepository.SubjectFilterItem item : visibleItems) {
            if (item == null || item.label == null) {
                continue;
            }
            if (!normalizedGradeSubject.equals(normalizeFilterString(item.label))) {
                continue;
            }
            subjectMatched = true;
            if (gradeTypeKey == null || gradeTypeKey.equals(item.typeKey)) {
                return true;
            }
        }
        return subjectMatched;
    }

    static boolean gradeMatchesHiddenFilterItem(
            Grade grade,
            PlanRepository.SubjectFilterItem item) {
        if (grade == null || item == null) {
            return false;
        }
        String gradeSubject = GradesTextUtils.extractBaseSubject(grade.subjectName);
        if (gradeSubject.isEmpty()) {
            gradeSubject = GradesTextUtils.clean(grade.subjectName);
        }
        if (!normalizeFilterString(gradeSubject).equals(normalizeFilterString(item.label))) {
            return false;
        }

        String gradeTypeKey = resolveGradeTypeKey(grade);
        return gradeTypeKey == null || item.typeKey == null || item.typeKey.isEmpty()
                || gradeTypeKey.equals(item.typeKey);
    }

    static String resolveGradeTypeKey(Grade grade) {
        if (grade == null) {
            return null;
        }

        String fromCourseId = resolveTypeKeyFromCourseId(grade.courseId);
        if (fromCourseId != null) {
            return fromCourseId;
        }

        String type = normalizeFilterString(grade.type);
        if (type.isEmpty()) {
            type = normalizeFilterString(GradesTextUtils.extractTypeFromSubject(grade.subjectName));
        }
        if (type.isEmpty()) {
            return null;
        }

        if (isGenericGradeTypeLabel(type)) {
            return null;
        }

        if ("l".equals(type) || type.contains("lab")) {
            return "lab";
        }
        if ("a".equals(type) || type.contains("aud")) {
            return "aud";
        }
        if ("w".equals(type) || type.contains("wyk") || type.contains("lec")) {
            return "lec";
        }
        if (type.contains("laboratorium") || type.contains("laboratory")) {
            return "lab";
        }
        if (type.contains("audytoryjne") || type.contains("auditory") || type.contains("auditorium")) {
            return "aud";
        }
        if (type.contains("wyklad") || type.contains("lecture")) {
            return "lec";
        }
        if (type.contains("lektorat") || type.contains("lectorate")
                || type.contains("language course") || type.contains("angiels")) {
            return "lek";
        }
        if (type.contains("cwiczen")) {
            return "aud";
        }
        return null;
    }

    private static boolean isGenericGradeTypeLabel(String normalizedType) {
        return normalizedType.contains("koncowa")
                || normalizedType.contains("final")
                || normalizedType.contains("zaliczen")
                || normalizedType.contains("egzamin")
                || normalizedType.contains("exam");
    }

    private static String resolveTypeKeyFromCourseId(String courseId) {
        if (courseId == null || courseId.trim().isEmpty()) {
            return null;
        }
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("-([A-Z]{2})$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(courseId.trim());
        if (!match.find()) {
            return null;
        }
        switch (match.group(1).toUpperCase(Locale.ROOT)) {
            case "WK":
                return "lec";
            case "LB":
                return "lab";
            case "CW":
                return "aud";
            case "LE":
            case "LK":
                return "lek";
            default:
                return null;
        }
    }

    private static List<PlanRepository.SubjectFilterItem> loadFilterCache(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long ts = prefs.getLong(getFilterCacheTsKey(context), 0L);
        if (ts <= 0L || (System.currentTimeMillis() - ts) > FILTER_CACHE_TTL_MS) {
            return null;
        }

        String json = SecureLocalData.readString(
                context,
                prefs,
                getFilterCacheJsonKey(context),
                null);
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            JSONArray arr = new JSONArray(json);
            List<PlanRepository.SubjectFilterItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                PlanRepository.SubjectFilterItem item = new PlanRepository.SubjectFilterItem();
                item.label = obj.optString("label", "");
                item.typeLabel = obj.optString("typeLabel", "");
                item.filterKey = obj.optString("filterKey", "");
                item.typeKey = obj.optString("typeKey", "");
                if (item.typeKey.trim().isEmpty()) {
                    item.typeKey = extractTypeKeyFromFilterKey(item.filterKey);
                }
                if (!item.filterKey.isEmpty()) {
                    items.add(item);
                }
            }
            return items;
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static void saveFilterCache(Context context, List<PlanRepository.SubjectFilterItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (PlanRepository.SubjectFilterItem item : items) {
                if (item == null || item.filterKey == null || item.filterKey.trim().isEmpty()) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("label", item.label != null ? item.label : "");
                obj.put("typeLabel", item.typeLabel != null ? item.typeLabel : "");
                obj.put("filterKey", item.filterKey);
                obj.put("typeKey", item.typeKey != null ? item.typeKey : "");
                arr.put(obj);
            }
            SharedPreferences preferences = context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE);
            SecureLocalData.putString(
                    context,
                    preferences,
                    getFilterCacheJsonKey(context),
                    arr.toString());
            preferences.edit()
                    .putLong(getFilterCacheTsKey(context), System.currentTimeMillis())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private static String getFilterCacheJsonKey(Context context) {
        return KEY_FILTER_CACHE_JSON + "_" + getFilterCacheScopeKey(context);
    }

    private static String getFilterCacheTsKey(Context context) {
        return KEY_FILTER_CACHE_TS + "_" + getFilterCacheScopeKey(context);
    }

    private static String getFilterCacheScopeKey(Context context) {
        ZutnikSession session = ZutnikSession.getInstance();
        String userId = session.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            userId = "unknown";
        }

        String studyId = "default";
        Study active = session.getActiveStudy();
        if (active != null && active.przynaleznoscId != null && !active.przynaleznoscId.trim().isEmpty()) {
            studyId = active.przynaleznoscId.trim();
        }

        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        int academicYearStart = (month >= 10) ? now.getYear() : (now.getYear() - 1);
        int academicYearEnd = academicYearStart + 1;
        String term = (month >= 10 || month <= 2) ? "winter" : "summer";
        String language = LocaleManager.getLanguage(context);
        if (language == null || language.trim().isEmpty()) {
            language = "default";
        }

        return userId + "_" + studyId + "_" + academicYearStart + "_" + academicYearEnd + "_" + term + "_" + language;
    }

    private static String extractTypeKeyFromFilterKey(String filterKey) {
        if (filterKey == null) {
            return "";
        }
        int sep = filterKey.lastIndexOf("||");
        if (sep < 0 || sep >= filterKey.length() - 2) {
            return "";
        }
        String suffix = filterKey.substring(sep + 2).trim();
        if ("lec".equals(suffix) || "aud".equals(suffix) || "lab".equals(suffix) || "lek".equals(suffix)) {
            return suffix;
        }
        return "";
    }

    static String normalizeFilterString(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }

        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized
                .replace('ł', 'l')
                .replace('Ł', 'l')
                .replace('đ', 'd')
                .replace('Đ', 'd');
    }
}
