package pl.kejlo.mzutv2;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class FinanceRecord {

    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("dd.MM.yy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE
    };

    public enum Status {
        DUE,
        PAID,
        OVERPAID,
        UNKNOWN
    }

    public String recordId;
    public String title;
    public String amountText;
    public String paidText;
    public String dueDateText;
    public String paidDateText;
    public String balanceText;
    public String accountText;

    public double amountValue;
    public double paidValue;
    public double balanceValue;

    public Status getStatus() {
        if (balanceValue < -0.0001d) {
            return Status.DUE;
        }
        if (balanceValue > 0.0001d) {
            return Status.OVERPAID;
        }
        if (Math.abs(balanceValue) <= 0.0001d && paidValue > 0.0001d) {
            return Status.PAID;
        }
        return Status.UNKNOWN;
    }

    public boolean isBookedInSystem() {
        Status status = getStatus();
        return status == Status.PAID || status == Status.OVERPAID;
    }

    public boolean hasAccount() {
        return getCopyableAccount().length() >= 8;
    }

    public String getCopyableAccount() {
        if (accountText == null) {
            return "";
        }
        return accountText.replaceAll("\\s+", "");
    }

    public String getFormattedAccount() {
        String raw = getCopyableAccount();
        if (raw.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(raw.length() + raw.length() / 4 + 2);
        boolean digitsOnly = raw.matches("\\d+");
        int firstGroupSize = digitsOnly && raw.length() >= 10 ? 2 : 4;

        for (int i = 0; i < raw.length();) {
            int groupSize = i == 0 ? firstGroupSize : 4;
            int next = Math.min(i + groupSize, raw.length());
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(raw, i, next);
            i = next;
        }
        return sb.toString();
    }

    public String getSafeTitle() {
        if (title == null || title.trim().isEmpty()) {
            return "";
        }
        return title.trim();
    }

    public static String formatMoneyText(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }

        String compact = normalized
                .replace(" ", "")
                .replace("zl", "")
                .replace("zł", "")
                .trim();

        if (compact.matches("[-+]?\\d+(?:[.,]\\d+)?")) {
            return compact.replace(".", ",") + " zł";
        }
        if (normalized.contains("zł") || normalized.contains("zl")) {
            return normalized.replace("zl", "zł");
        }
        return normalized;
    }

    public boolean matchesFilter(Status filter) {
        if (filter == null) {
            return true;
        }
        return getStatus() == filter;
    }

    public LocalDate getDueDate() {
        return parseDate(dueDateText);
    }

    public LocalDate getPaidDate() {
        return parseDate(paidDateText);
    }

    public String getStableKey() {
        String normalizedId = normalizeKeyPart(recordId);
        if (!normalizedId.isEmpty()) {
            return "id:" + normalizedId;
        }
        return "row:"
                + normalizeKeyPart(getSafeTitle()) + "|"
                + normalizeKeyPart(amountText) + "|"
                + normalizeKeyPart(dueDateText) + "|"
                + normalizeKeyPart(getCopyableAccount());
    }

    public long getRelevantDateSortKey() {
        String preferredDate = getStatus() == Status.PAID
                ? firstNonEmpty(paidDateText, dueDateText)
                : firstNonEmpty(dueDateText, paidDateText);
        return parseDateSortKey(preferredDate);
    }

    public static double parseAmount(String raw) {
        if (raw == null) {
            return 0.0d;
        }
        String normalized = raw.trim()
                .replace("zl", "")
                .replace("zł", "")
                .replace(" ", "")
                .replace(",", ".")
                .trim();
        if (normalized.isEmpty()) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeLegacyTitle(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        String lowered = normalized.toLowerCase(Locale.getDefault());
        if (lowered.isEmpty()) {
            return lowered;
        }
        return Character.toUpperCase(lowered.charAt(0)) + lowered.substring(1);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("recordId", recordId);
        obj.put("title", title);
        obj.put("amountText", amountText);
        obj.put("paidText", paidText);
        obj.put("dueDateText", dueDateText);
        obj.put("paidDateText", paidDateText);
        obj.put("balanceText", balanceText);
        obj.put("accountText", accountText);
        obj.put("amountValue", amountValue);
        obj.put("paidValue", paidValue);
        obj.put("balanceValue", balanceValue);
        return obj;
    }

    public static FinanceRecord fromJson(JSONObject obj) {
        FinanceRecord record = new FinanceRecord();
        record.recordId = normalizeText(obj.optString("recordId", null));
        record.title = normalizeText(obj.optString("title", null));
        record.amountText = normalizeText(obj.optString("amountText", null));
        record.paidText = normalizeText(obj.optString("paidText", null));
        record.dueDateText = normalizeText(obj.optString("dueDateText", null));
        record.paidDateText = normalizeText(obj.optString("paidDateText", null));
        record.balanceText = normalizeText(obj.optString("balanceText", null));
        record.accountText = normalizeText(obj.optString("accountText", null));
        record.amountValue = obj.optDouble("amountValue", parseAmount(record.amountText));
        record.paidValue = obj.optDouble("paidValue", parseAmount(record.paidText));
        record.balanceValue = obj.optDouble("balanceValue", parseAmount(record.balanceText));
        return record;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalizeKeyPart(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        String normalized = raw.trim();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static long parseDateSortKey(String raw) {
        LocalDate date = parseDate(raw);
        return date != null ? date.toEpochDay() : Long.MAX_VALUE;
    }
}
