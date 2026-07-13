package pl.kejlo.zutnik;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class InAppReviewPrompterTest {

    private static final long NOW = TimeUnit.DAYS.toMillis(30);

    @Test
    public void eligibleAfterEnoughTimeAndSessions() {
        assertTrue(InAppReviewPrompter.isEligible(
                NOW,
                NOW - TimeUnit.DAYS.toMillis(8),
                5,
                NOW,
                false));
    }

    @Test
    public void notEligibleDuringFirstWeek() {
        assertFalse(InAppReviewPrompter.isEligible(
                NOW,
                NOW - TimeUnit.DAYS.toMillis(6),
                10,
                0L,
                false));
    }

    @Test
    public void notEligibleBeforeFifthSession() {
        assertFalse(InAppReviewPrompter.isEligible(
                NOW,
                NOW - TimeUnit.DAYS.toMillis(10),
                4,
                0L,
                false));
    }

    @Test
    public void notEligibleBeforeNextScheduledAttempt() {
        assertFalse(InAppReviewPrompter.isEligible(
                NOW,
                NOW - TimeUnit.DAYS.toMillis(20),
                10,
                NOW + 1L,
                false));
    }

    @Test
    public void manualStoreOpenSuppressesAutomaticPrompt() {
        assertFalse(InAppReviewPrompter.isEligible(
                NOW,
                NOW - TimeUnit.DAYS.toMillis(20),
                10,
                0L,
                true));
    }
}
