package pl.kejlo.zutnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GradesNotificationTracker {

    private static final String ACTIVE_TERMS_SCOPE = "active_terms";
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LOCAL_DATE = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");

    private GradesNotificationTracker() {
    }

    static Map<String, String> buildSnapshot(List<Grade> grades) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (grades == null) {
            return snapshot;
        }
        for (Grade grade : grades) {
            if (grade == null || clean(grade.grade).isEmpty()) {
                continue;
            }
            snapshot.put(buildKey(grade), buildLabel(grade));
        }
        return snapshot;
    }

    static List<String> findAddedKeys(Set<String> previous, Map<String, String> current) {
        List<String> added = new ArrayList<>();
        if (current == null || current.isEmpty()) {
            return added;
        }
        for (String key : current.keySet()) {
            if (previous == null || !previous.contains(key)) {
                added.add(key);
            }
        }
        Collections.sort(added);
        return added;
    }

    static String buildKey(Grade grade) {
        if (grade == null) {
            return "";
        }
        return normalize(ACTIVE_TERMS_SCOPE)
                + "|" + normalize(grade.courseId)
                + "|" + normalize(grade.subjectName)
                + "|" + normalize(grade.grade)
                + "|" + normalize(normalizeGradeDate(grade))
                + "|" + normalize(grade.type)
                + "|" + normalize(grade.gradeDescription)
                + "|" + normalize(grade.comment);
    }

    private static String normalizeGradeDate(Grade grade) {
        String[] candidates = {
                grade.dateAcquisition,
                grade.dateModified,
                grade.date
        };
        for (String candidate : candidates) {
            String value = clean(candidate);
            if (value.isEmpty()) {
                continue;
            }
            Matcher isoMatcher = ISO_DATE.matcher(value);
            if (isoMatcher.find()) {
                return isoMatcher.group(1);
            }
            Matcher localMatcher = LOCAL_DATE.matcher(value);
            if (localMatcher.find()) {
                return localMatcher.group(1);
            }
            return value;
        }
        return "";
    }

    private static String buildLabel(Grade grade) {
        String subject = clean(grade.subjectName);
        String score = clean(grade.grade);
        String type = clean(grade.type);
        String date = clean(grade.date);
        StringBuilder label = new StringBuilder();
        if (!score.isEmpty()) {
            label.append(score).append(" - ");
        }
        label.append(subject.isEmpty() ? "Przedmiot" : subject);
        if (!type.isEmpty()) {
            label.append(" · ").append(type);
        }
        if (!date.isEmpty()) {
            label.append(" · ").append(date);
        }
        return label.toString();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
