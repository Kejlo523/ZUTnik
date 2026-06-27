package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PlanSubjectFilterHelper {

    static final String PREFS_NAME = "mzut_plan";
    static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    private PlanSubjectFilterHelper() {
    }

    static Set<String> loadHiddenSubjectKeys(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>()));
    }

    static List<PlanRepository.SubjectFilterItem> loadAllFilterItems(Context context, boolean forceRefresh)
            throws IOException, JSONException {
        return new PlanRepository(context).loadSubjectsForFilter(forceRefresh);
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
        for (PlanRepository.SubjectFilterItem item : visibleItems) {
            if (item == null || item.label == null) {
                continue;
            }
            if (!normalizedGradeSubject.equals(normalizeFilterString(item.label))) {
                continue;
            }
            if (gradeTypeKey == null || gradeTypeKey.equals(item.typeKey)) {
                return true;
            }
        }
        return false;
    }

    static boolean gradeMatchesFilterItem(
            Grade grade,
            PlanRepository.SubjectFilterItem item,
            List<PlanRepository.SubjectFilterItem> visibleItems) {
        if (grade == null || item == null || visibleItems == null || visibleItems.isEmpty()) {
            return false;
        }
        return gradeMatchesVisiblePlan(grade, java.util.Collections.singletonList(item));
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
            default:
                return null;
        }
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
