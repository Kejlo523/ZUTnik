package pl.kejlo.zutnik;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Schedules the official Google Play review card after meaningful app usage. */
final class InAppReviewPrompter {

    private static final String PREFS_NAME = "in_app_review_prompt";
    private static final String KEY_FIRST_SEEN_AT = "first_seen_at";
    private static final String KEY_SESSION_COUNT = "session_count";
    private static final String KEY_NEXT_ELIGIBLE_AT = "next_eligible_at";
    private static final String KEY_LAST_ATTEMPT_AT = "last_attempt_at";
    private static final String KEY_SUPPRESSED = "suppressed";

    static final int MIN_SESSION_COUNT = 5;
    static final long MIN_INSTALL_AGE_MS = TimeUnit.DAYS.toMillis(7);
    static final long MIN_ATTEMPT_INTERVAL_MS = TimeUnit.DAYS.toMillis(14);

    private static final long INITIAL_JITTER_MS = TimeUnit.DAYS.toMillis(3);
    private static final long INTERVAL_JITTER_MS = TimeUnit.DAYS.toMillis(3);
    private static final long MIN_SESSION_DELAY_MS = TimeUnit.SECONDS.toMillis(45);
    private static final long MAX_SESSION_DELAY_MS = TimeUnit.SECONDS.toMillis(120);
    private static final long WINDOW_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    private final Activity activity;
    private final SharedPreferences preferences;
    private final ReviewManager reviewManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable reviewRunnable = this::requestReviewIfReady;

    private boolean resumed;
    private boolean requestInFlight;

    InAppReviewPrompter(Activity activity, boolean freshAppLaunch) {
        this.activity = activity;
        preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        reviewManager = ReviewManagerFactory.create(activity);
        initializeState(freshAppLaunch);
    }

    void onResume() {
        resumed = true;
        scheduleIfEligible();
    }

    void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(reviewRunnable);
    }

    void onDestroy() {
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
    }

    static void suppressAfterManualStoreOpen(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SUPPRESSED, true)
                .apply();
    }

    static boolean isEligible(
            long now,
            long firstSeenAt,
            int sessionCount,
            long nextEligibleAt,
            boolean suppressed) {
        return !suppressed
                && firstSeenAt > 0L
                && now - firstSeenAt >= MIN_INSTALL_AGE_MS
                && sessionCount >= MIN_SESSION_COUNT
                && now >= nextEligibleAt;
    }

    private void initializeState(boolean freshAppLaunch) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences.edit();

        if (!preferences.contains(KEY_FIRST_SEEN_AT)) {
            editor.putLong(KEY_FIRST_SEEN_AT, now);
            editor.putLong(
                    KEY_NEXT_ELIGIBLE_AT,
                    now + MIN_INSTALL_AGE_MS + randomDuration(INITIAL_JITTER_MS));
        }
        if (freshAppLaunch) {
            editor.putInt(KEY_SESSION_COUNT, preferences.getInt(KEY_SESSION_COUNT, 0) + 1);
        }
        editor.apply();
    }

    private void scheduleIfEligible() {
        mainHandler.removeCallbacks(reviewRunnable);
        if (!resumed || requestInFlight || !isCurrentlyEligible()) {
            return;
        }
        long delay = MIN_SESSION_DELAY_MS
                + randomDuration(MAX_SESSION_DELAY_MS - MIN_SESSION_DELAY_MS);
        mainHandler.postDelayed(reviewRunnable, delay);
    }

    private boolean isCurrentlyEligible() {
        return isEligible(
                System.currentTimeMillis(),
                preferences.getLong(KEY_FIRST_SEEN_AT, 0L),
                preferences.getInt(KEY_SESSION_COUNT, 0),
                preferences.getLong(KEY_NEXT_ELIGIBLE_AT, Long.MAX_VALUE),
                preferences.getBoolean(KEY_SUPPRESSED, false));
    }

    private void requestReviewIfReady() {
        if (!resumed || requestInFlight || !isCurrentlyEligible()) {
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) {
            mainHandler.postDelayed(reviewRunnable, WINDOW_RETRY_DELAY_MS);
            return;
        }

        long now = System.currentTimeMillis();
        long nextEligibleAt = now
                + MIN_ATTEMPT_INTERVAL_MS
                + randomDuration(INTERVAL_JITTER_MS);
        preferences.edit()
                .putLong(KEY_LAST_ATTEMPT_AT, now)
                .putLong(KEY_NEXT_ELIGIBLE_AT, nextEligibleAt)
                .apply();

        requestInFlight = true;
        reviewManager.requestReviewFlow().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || !isActivityReady()) {
                requestInFlight = false;
                return;
            }

            ReviewInfo reviewInfo = task.getResult();
            reviewManager.launchReviewFlow(activity, reviewInfo)
                    .addOnCompleteListener(ignored -> requestInFlight = false);
        });
    }

    private boolean isActivityReady() {
        return resumed
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && activity.hasWindowFocus();
    }

    private long randomDuration(long maxInclusive) {
        if (maxInclusive <= 0L) {
            return 0L;
        }
        return (long) (random.nextDouble() * (maxInclusive + 1L));
    }
}
