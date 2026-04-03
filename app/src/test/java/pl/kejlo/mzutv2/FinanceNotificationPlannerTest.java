package pl.kejlo.mzutv2;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FinanceNotificationPlannerTest {

    @Test
    public void findDueRemindersReturnsOnlyOpenPaymentsFiveDaysAhead() {
        LocalDate today = LocalDate.of(2026, 4, 3);

        FinanceRecord dueSoon = financeRecord(null, "Czesne", "08.04.2026", false);
        FinanceRecord alreadyPaid = financeRecord(null, "Opłata rekrutacyjna", "08.04.2026", true);
        FinanceRecord differentDate = financeRecord(null, "Legitymacja", "09.04.2026", false);

        List<FinanceRecord> result = FinanceNotificationPlanner.findDueReminders(
                Arrays.asList(dueSoon, alreadyPaid, differentDate),
                today);

        assertEquals(1, result.size());
        assertEquals("Czesne", result.get(0).getSafeTitle());
    }

    @Test
    public void findNewlyBookedDetectsTransitionFromDueToPaid() {
        FinanceRecord previous = financeRecord("fee-1", "Czesne", "08.04.2026", false);
        FinanceRecord current = financeRecord("fee-1", "Czesne", "08.04.2026", true);

        List<FinanceRecord> result = FinanceNotificationPlanner.findNewlyBooked(
                Collections.singletonList(previous),
                Collections.singletonList(current));

        assertEquals(1, result.size());
        assertEquals("fee-1", result.get(0).recordId);
    }

    @Test
    public void stableKeyUsesApiIdentifierWhenAvailable() {
        FinanceRecord previous = financeRecord("fee-1", "Czesne semestr letni", "08.04.2026", false);
        FinanceRecord current = financeRecord("fee-1", "Czesne", "08.04.2026", true);

        List<FinanceRecord> result = FinanceNotificationPlanner.findNewlyBooked(
                Collections.singletonList(previous),
                Collections.singletonList(current));

        assertEquals(1, result.size());
        assertEquals(previous.getStableKey(), current.getStableKey());
    }

    private FinanceRecord financeRecord(String recordId, String title, String dueDate, boolean paid) {
        FinanceRecord record = new FinanceRecord();
        record.recordId = recordId;
        record.title = title;
        record.amountText = "100";
        record.dueDateText = dueDate;
        record.paidText = paid ? "100" : "0";
        record.balanceText = paid ? "0" : "-100";
        record.amountValue = 100.0d;
        record.paidValue = paid ? 100.0d : 0.0d;
        record.balanceValue = paid ? 0.0d : -100.0d;
        return record;
    }
}
