package pl.kejlo.zutnik;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GradesCorrectionHelper {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long FAIL_TO_PASS_CORRECTION_WINDOW_MS = 120L * DAY_MS;
    private static final long PAIR_CORRECTION_WINDOW_MS = 45L * DAY_MS;

    private GradesCorrectionHelper() {
    }

    static List<Grade> collapseCorrectedGrades(List<Grade> source) {
        List<Grade> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }

        Map<String, List<IndexedGrade>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < source.size(); i++) {
            Grade grade = source.get(i);
            if (grade == null) {
                continue;
            }
            String key = buildCorrectionKey(grade, i);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new IndexedGrade(grade, i));
        }

        for (List<IndexedGrade> entries : grouped.values()) {
            if (entries.size() > 1 && shouldCollapse(entries)) {
                out.add(mergeCorrectionChain(entries));
            } else {
                for (IndexedGrade entry : entries) {
                    out.add(copyGrade(entry.grade));
                }
            }
        }
        return out;
    }

    static boolean hasCorrection(Grade grade) {
        return !correctionLabel(grade).isEmpty();
    }

    static String correctionLabel(Grade grade) {
        if (grade == null || grade.gradeHistory == null || grade.gradeHistory.size() < 2) {
            return "";
        }

        String current = GradesTextUtils.clean(grade.grade);
        if (current.isEmpty()) {
            return "";
        }

        String previous = "";
        for (String item : grade.gradeHistory) {
            String value = GradesTextUtils.clean(item);
            if (!value.isEmpty() && !sameGradeValue(value, current)) {
                previous = value;
                break;
            }
        }
        return previous.isEmpty() ? "" : previous + " -> " + current;
    }

    static Grade copyGrade(Grade source) {
        Grade copy = new Grade();
        if (source == null) {
            return copy;
        }
        copy.subjectName = source.subjectName;
        copy.courseId = source.courseId;
        copy.grade = source.grade;
        copy.weight = source.weight;
        copy.gradeType = source.gradeType;
        copy.gradeDescription = source.gradeDescription;
        copy.passes = source.passes;
        copy.type = source.type;
        copy.teacher = source.teacher;
        copy.date = source.date;
        copy.comment = source.comment;
        copy.countsIntoAverage = source.countsIntoAverage;
        copy.examId = source.examId;
        copy.examSessionNumber = source.examSessionNumber;
        copy.dateModified = source.dateModified;
        copy.dateAcquisition = source.dateAcquisition;
        copy.modificationAuthor = source.modificationAuthor;
        copy.decimalValue = source.decimalValue;
        copy.gradeTypeId = source.gradeTypeId;
        copy.isNew = source.isNew;
        copy.gradeHistory = new ArrayList<>();
        if (source.gradeHistory != null) {
            copy.gradeHistory.addAll(source.gradeHistory);
        }
        return copy;
    }

    private static String buildCorrectionKey(Grade grade, int index) {
        String subject = GradesTextUtils.extractBaseSubject(grade.subjectName);
        if (subject.isEmpty()) {
            subject = GradesTextUtils.clean(grade.subjectName);
        }
        subject = PlanSubjectFilterHelper.normalizeFilterString(subject);
        if (subject.isEmpty()) {
            return "unique:" + index;
        }

        String typeKey = PlanSubjectFilterHelper.resolveGradeTypeKey(grade);
        if (typeKey == null || typeKey.isEmpty()) {
            String rawType = GradesTextUtils.clean(grade.type);
            if (rawType.isEmpty()) {
                rawType = GradesTextUtils.extractTypeFromSubject(grade.subjectName);
            }
            typeKey = PlanSubjectFilterHelper.normalizeFilterString(rawType);
        }
        if (typeKey == null || typeKey.isEmpty()) {
            typeKey = GradesTextUtils.isFinalGradeLabel(grade.type) ? "final" : "grade";
        }

        String courseId = PlanSubjectFilterHelper.normalizeFilterString(grade.courseId);
        return subject + "|" + courseId + "|" + typeKey;
    }

    private static boolean shouldCollapse(List<IndexedGrade> entries) {
        Set<String> values = new LinkedHashSet<>();
        Set<String> courseIds = new LinkedHashSet<>();
        Set<Integer> sessions = new LinkedHashSet<>();
        int numericCount = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;

        for (IndexedGrade entry : entries) {
            Grade grade = entry.grade;
            String value = GradesTextUtils.clean(grade.grade);
            if (!value.isEmpty()) {
                values.add(normalizeGradeValue(value));
            }

            String courseId = PlanSubjectFilterHelper.normalizeFilterString(grade.courseId);
            if (!courseId.isEmpty()) {
                courseIds.add(courseId);
            }

            if (grade.examSessionNumber > 0) {
                sessions.add(grade.examSessionNumber);
            }

            Double numeric = parseNumericGrade(value);
            if (numeric != null) {
                numericCount++;
                min = Math.min(min, numeric);
                max = Math.max(max, numeric);
            }

            long time = gradeTime(grade);
            if (time > 0L) {
                earliest = Math.min(earliest, time);
                latest = Math.max(latest, time);
            }
        }

        if (values.size() < 2 || numericCount < 2) {
            return false;
        }
        if (sessions.size() > 1) {
            return true;
        }
        if (min <= 2.0 && max > 2.0
                && isWithinCorrectionWindow(earliest, latest, FAIL_TO_PASS_CORRECTION_WINDOW_MS)) {
            return true;
        }
        return entries.size() == 2
                && courseIds.size() <= 1
                && isWithinCorrectionWindow(earliest, latest, PAIR_CORRECTION_WINDOW_MS);
    }

    private static boolean isWithinCorrectionWindow(long earliest, long latest, long windowMs) {
        return earliest > 0L
                && latest > 0L
                && latest >= earliest
                && (latest - earliest) <= windowMs;
    }

    private static Grade mergeCorrectionChain(List<IndexedGrade> entries) {
        List<IndexedGrade> ordered = new ArrayList<>(entries);
        ordered.sort(GradesCorrectionHelper::compareHistoryOrder);

        IndexedGrade active = chooseActive(ordered);
        ordered.remove(active);
        ordered.add(active);

        Grade merged = copyGrade(active.grade);
        merged.isNew = false;
        for (IndexedGrade entry : entries) {
            if (entry.grade.isNew) {
                merged.isNew = true;
                break;
            }
        }

        List<String> history = new ArrayList<>();
        for (IndexedGrade entry : ordered) {
            String value = GradesTextUtils.clean(entry.grade.grade);
            if (value.isEmpty()) {
                continue;
            }
            if (history.isEmpty() || !sameGradeValue(history.get(history.size() - 1), value)) {
                history.add(value);
            }
        }
        merged.gradeHistory = history;
        return merged;
    }

    private static IndexedGrade chooseActive(List<IndexedGrade> entries) {
        IndexedGrade best = entries.get(0);
        for (int i = 1; i < entries.size(); i++) {
            IndexedGrade candidate = entries.get(i);
            if (compareActive(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static int compareActive(IndexedGrade left, IndexedGrade right) {
        long leftTime = gradeTime(left.grade);
        long rightTime = gradeTime(right.grade);
        if (leftTime > 0L || rightTime > 0L) {
            int dateOrder = Long.compare(leftTime, rightTime);
            if (dateOrder != 0) {
                return dateOrder;
            }
        }

        int sessionOrder = Integer.compare(left.grade.examSessionNumber, right.grade.examSessionNumber);
        if (sessionOrder != 0) {
            return sessionOrder;
        }

        Double leftValue = parseNumericGrade(left.grade.grade);
        Double rightValue = parseNumericGrade(right.grade.grade);
        if (leftValue != null && rightValue != null) {
            int valueOrder = Double.compare(leftValue, rightValue);
            if (valueOrder != 0) {
                return valueOrder;
            }
        }
        return Integer.compare(left.index, right.index);
    }

    private static int compareHistoryOrder(IndexedGrade left, IndexedGrade right) {
        long leftTime = gradeTime(left.grade);
        long rightTime = gradeTime(right.grade);
        if (leftTime > 0L || rightTime > 0L) {
            int dateOrder = Long.compare(leftTime, rightTime);
            if (dateOrder != 0) {
                return dateOrder;
            }
        }

        int sessionOrder = Integer.compare(left.grade.examSessionNumber, right.grade.examSessionNumber);
        if (sessionOrder != 0) {
            return sessionOrder;
        }

        Double leftValue = parseNumericGrade(left.grade.grade);
        Double rightValue = parseNumericGrade(right.grade.grade);
        if (leftValue != null && rightValue != null) {
            int valueOrder = Double.compare(leftValue, rightValue);
            if (valueOrder != 0) {
                return valueOrder;
            }
        }
        return Integer.compare(left.index, right.index);
    }

    private static long gradeTime(Grade grade) {
        String[] candidates = {
                GradesTextUtils.clean(grade.dateAcquisition),
                GradesTextUtils.clean(grade.dateModified),
                GradesTextUtils.clean(grade.date)
        };

        for (String candidate : candidates) {
            long time = parseDateTime(candidate);
            if (time > 0L) {
                return time;
            }
        }
        return 0L;
    }

    private static long parseDateTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }

        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        if (trimmed.length() >= 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            try {
                return LocalDate.parse(trimmed.substring(0, 10))
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT))
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        if (trimmed.length() >= 10 && trimmed.charAt(2) == '.' && trimmed.charAt(5) == '.') {
            try {
                return LocalDate.parse(trimmed.substring(0, 10), DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT))
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        return 0L;
    }

    private static Double parseNumericGrade(String value) {
        String clean = GradesTextUtils.clean(value);
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(clean.replace(",", "."));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeGradeValue(String value) {
        Double numeric = parseNumericGrade(value);
        if (numeric != null) {
            return String.format(Locale.ROOT, "%.2f", numeric);
        }
        return GradesTextUtils.normalizeKey(value);
    }

    private static boolean sameGradeValue(String left, String right) {
        return normalizeGradeValue(left).equals(normalizeGradeValue(right));
    }

    private static final class IndexedGrade {
        final Grade grade;
        final int index;

        IndexedGrade(Grade grade, int index) {
            this.grade = grade;
            this.index = index;
        }
    }
}
