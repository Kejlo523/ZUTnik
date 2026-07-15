package pl.kejlo.zutnik;

import android.content.Context;

import java.text.Normalizer;
import java.util.Locale;

final class GradesTextUtils {

    private GradesTextUtils() {
    }

    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return ("null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)) ? "" : trimmed;
    }

    static String normalizeKey(String value) {
        String lower = clean(value).toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    static boolean isFinalGradeLabel(String value) {
        return hasFinalGradeMarker(normalizeKey(value));
    }

    static boolean hasFinalGradeMarker(String normalizedValue) {
        return normalizedValue.contains("ocena koncowa")
                || normalizedValue.contains("koncowa")
                || normalizedValue.contains("final")
                || normalizedValue.contains("abschluss");
    }

    static String extractBaseSubject(String label) {
        if (label == null) {
            return "";
        }
        String name = label.trim();
        int parenIdx = name.lastIndexOf(" (");
        if (parenIdx > 0 && name.endsWith(")")) {
            name = name.substring(0, parenIdx);
        }
        return name.trim();
    }

    static String extractTypeFromSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String name = subject.trim();
        int start = name.lastIndexOf(" (");
        if (start > 0 && name.endsWith(")")) {
            return name.substring(start + 2, name.length() - 1).trim();
        }
        return "";
    }

    static String formatTypeDisplay(Context context, String value) {
        String raw = clean(value);
        if (raw.isEmpty()) {
            return "";
        }

        String normalized = normalizeKey(raw);
        if (normalized.contains("wyklad") || normalized.contains("lecture")) {
            return context.getString(R.string.plan_type_lecture);
        }
        if (normalized.contains("laboratorium") || normalized.contains("laboratory")
                || "lab".equals(normalized)) {
            return context.getString(R.string.plan_type_lab);
        }
        if (normalized.contains("audytoryjne") || normalized.contains("exercise")
                || normalized.contains("tutorial")) {
            return context.getString(R.string.plan_type_auditory);
        }
        if (normalized.contains("egzamin")) {
            return context.getString(R.string.plan_type_exam);
        }
        if (normalized.contains("zaliczen")) {
            return context.getString(R.string.plan_type_pass);
        }
        return toTitleCase(raw);
    }

    static String toTitleCase(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.toLowerCase(Locale.getDefault()).split("\\s+");
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
