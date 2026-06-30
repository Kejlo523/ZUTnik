package pl.kejlo.zutnik;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FinanceNotificationPlanner {

    public static final int DUE_REMINDER_DAYS = 5;

    private FinanceNotificationPlanner() {
    }

    public static List<FinanceRecord> findDueReminders(List<FinanceRecord> records, LocalDate today) {
        if (records == null || records.isEmpty() || today == null) {
            return Collections.emptyList();
        }

        LocalDate targetDate = today.plusDays(DUE_REMINDER_DAYS);
        List<FinanceRecord> matches = new ArrayList<>();
        for (FinanceRecord record : records) {
            if (record == null || record.isBookedInSystem()) {
                continue;
            }
            if (targetDate.equals(record.getDueDate())) {
                matches.add(record);
            }
        }
        sortRecords(matches);
        return matches;
    }

    public static List<FinanceRecord> findNewlyBooked(
            List<FinanceRecord> previousRecords,
            List<FinanceRecord> currentRecords) {
        if (currentRecords == null || currentRecords.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, FinanceRecord> previousByKey = indexByStableKey(previousRecords);
        List<FinanceRecord> matches = new ArrayList<>();
        for (FinanceRecord current : currentRecords) {
            if (current == null || !current.isBookedInSystem()) {
                continue;
            }
            FinanceRecord previous = previousByKey.get(current.getStableKey());
            if (previous == null || !previous.isBookedInSystem()) {
                matches.add(current);
            }
        }
        sortRecords(matches);
        return matches;
    }

    private static Map<String, FinanceRecord> indexByStableKey(List<FinanceRecord> records) {
        Map<String, FinanceRecord> indexed = new LinkedHashMap<>();
        if (records == null) {
            return indexed;
        }
        for (FinanceRecord record : records) {
            if (record == null) {
                continue;
            }
            indexed.put(record.getStableKey(), record);
        }
        return indexed;
    }

    private static void sortRecords(List<FinanceRecord> records) {
        records.sort(Comparator
                .comparingLong(FinanceRecord::getRelevantDateSortKey)
                .thenComparing(FinanceRecord::getSafeTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FinanceRecord::getStableKey));
    }
}
