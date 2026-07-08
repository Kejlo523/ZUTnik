package pl.kejlo.zutnik;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class NotificationSyncManager {

    private NotificationSyncManager() {
    }

    public static final String UNIQUE_WORK_NAME = "zutnik_background_sync";
    public static final String UNIQUE_BOOTSTRAP_WORK_NAME = "zutnik_background_sync_bootstrap";
    public static final long SYNC_INTERVAL_MINUTES = 6L * 60L;
    private static final long SYNC_FLEX_MINUTES = 60L;
    private static final String PREFS_RUNTIME = "zutnik_sync_runtime";
    private static final String KEY_BOOTSTRAP_SYNC_SCOPE = "bootstrap_sync_scope_v2";
    private static final String PREFS_BG = "zutnik_background_sync_cache";
    private static final String KEY_GRADES_BASELINE_READY = "grades_baseline_ready_v2";
    private static final String KEY_PLAN_BASELINE_READY = "plan_baseline_ready_v3";
    private static final String KEY_FINANCE_BASELINE_READY = "finance_baseline_ready_v1";

    public static final String CHANNEL_GRADES = "zutnik_grades_changes";
    public static final String CHANNEL_PLAN = "zutnik_plan_changes";
    public static final String CHANNEL_FINANCE = "zutnik_finance_changes";
    public static final String CHANNEL_AUTH = "zutnik_session_auth";

    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isMasterEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_MASTER_ENABLED);
    }

    public static boolean isGradesEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_GRADES_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_GRADES_ENABLED);
    }

    public static boolean isPlanEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_ENABLED);
    }

    public static boolean isFinanceEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_ENABLED);
    }

    public static boolean isFinanceDueEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_DUE_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_DUE_ENABLED);
    }

    public static boolean isFinanceBookedEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_BOOKED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_BOOKED_ENABLED);
    }

    public static boolean isPlanMovedEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_MOVED_ENABLED);
    }

    public static boolean isPlanCancelledEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_CANCELLED_ENABLED);
    }

    public static boolean isPlanAddedEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_ADDED_ENABLED);
    }

    public static boolean isPlanRemovedEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        return prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_REMOVED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_REMOVED_ENABLED);
    }

    public static boolean isAnyPlanCategoryEnabled(Context context) {
        return isPlanMovedEnabled(context)
                || isPlanCancelledEnabled(context)
                || isPlanAddedEnabled(context)
                || isPlanRemovedEnabled(context);
    }

    public static boolean isAnyFinanceCategoryEnabled(Context context) {
        return isFinanceDueEnabled(context) || isFinanceBookedEnabled(context);
    }

    public static boolean isAnyFeatureEnabled(Context context) {
        if (!isMasterEnabled(context)) {
            return false;
        }
        boolean grades = isGradesEnabled(context);
        boolean plan = isPlanEnabled(context) && isAnyPlanCategoryEnabled(context);
        boolean finance = isFinanceEnabled(context) && isAnyFinanceCategoryEnabled(context);
        return grades || plan || finance;
    }

    public static void syncWorkerSchedule(Context context) {
        Context appContext = context.getApplicationContext();
        ensureChannels(appContext);

        ZutnikSession.initializeFromPreferences(appContext);
        ZutnikSession session = ZutnikSession.getInstance();
        boolean hasSession = session.isLoggedIn() && !session.isDemoLogin();

        if (!hasSession || !hasNotificationPermission(appContext) || !isAnyFeatureEnabled(appContext)) {
            cancelWorker(appContext);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                BackgroundSyncWorker.class,
                SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
                SYNC_FLEX_MINUTES,
                TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);

        enqueueBootstrapSyncIfNeeded(appContext);
    }

    public static void enqueueBootstrapSyncIfNeeded(Context context) {
        Context appContext = context.getApplicationContext();

        ZutnikSession.initializeFromPreferences(appContext);
        ZutnikSession session = ZutnikSession.getInstance();

        if (!session.isLoggedIn() || session.isDemoLogin()) {
            return;
        }
        if (!hasNotificationPermission(appContext) || !isAnyFeatureEnabled(appContext)) {
            return;
        }

        String scope = buildCurrentSyncScope(appContext);
        SharedPreferences runtimePrefs = appContext.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE);
        String syncedScope = runtimePrefs.getString(KEY_BOOTSTRAP_SYNC_SCOPE, "");
        SharedPreferences bgPrefs = appContext.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        boolean requiresGradesBaseline = isGradesEnabled(appContext);
        boolean requiresPlanBaseline = isPlanEnabled(appContext) && isAnyPlanCategoryEnabled(appContext);
        boolean requiresFinanceBaseline = isFinanceEnabled(appContext) && isAnyFinanceCategoryEnabled(appContext);
        boolean gradesBaselineReady = !requiresGradesBaseline
                || bgPrefs.getBoolean(scopedPrefKey(KEY_GRADES_BASELINE_READY, scope), false);
        boolean planBaselineReady = !requiresPlanBaseline
                || bgPrefs.getBoolean(scopedPrefKey(KEY_PLAN_BASELINE_READY, scope), false);
        boolean financeBaselineReady = !requiresFinanceBaseline
                || bgPrefs.getBoolean(scopedPrefKey(KEY_FINANCE_BASELINE_READY, scope), false);

        if (scope.equals(syncedScope)
                && gradesBaselineReady
                && planBaselineReady
                && financeBaselineReady) {
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackgroundSyncWorker.class)
                .setInputData(new Data.Builder()
                        .putBoolean(BackgroundSyncWorker.INPUT_BOOTSTRAP_PREFETCH, true)
                        .build())
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_BOOTSTRAP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request);

        runtimePrefs.edit().putString(KEY_BOOTSTRAP_SYNC_SCOPE, scope).apply();
    }

    public static void cancelWorker(Context context) {
        WorkManager wm = WorkManager.getInstance(context.getApplicationContext());
        wm.cancelUniqueWork(UNIQUE_WORK_NAME);
        wm.cancelUniqueWork(UNIQUE_BOOTSTRAP_WORK_NAME);
    }

    static String buildCurrentSyncScope(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext != null) {
            ZutnikSession.initializeFromPreferences(appContext);
        }

        ZutnikSession session = ZutnikSession.getInstance();
        String userId = safeScopePart(session.getUserId());
        String studyId = safeScopePart(session.getActiveStudyId());
        if (studyId.isEmpty()) {
            Study active = session.getActiveStudy();
            if (active != null) {
                studyId = safeScopePart(active.przynaleznoscId);
            }
        }

        return "u:" + userId + "|s:" + studyId;
    }

    static String scopedPrefKey(String baseKey, String scope) {
        return baseKey + "_" + scopeHash(scope);
    }

    private static String safeScopePart(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static String scopeHash(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        return Integer.toHexString(normalized.hashCode());
    }

    public static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        NotificationChannel grades = new NotificationChannel(
                CHANNEL_GRADES,
                context.getString(R.string.notif_channel_grades_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        grades.setDescription(context.getString(R.string.notif_channel_grades_desc));

        NotificationChannel plan = new NotificationChannel(
                CHANNEL_PLAN,
                context.getString(R.string.notif_channel_plan_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        plan.setDescription(context.getString(R.string.notif_channel_plan_desc));

        NotificationChannel finance = new NotificationChannel(
                CHANNEL_FINANCE,
                context.getString(R.string.notif_channel_finance_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        finance.setDescription(context.getString(R.string.notif_channel_finance_desc));

        NotificationChannel auth = new NotificationChannel(
                CHANNEL_AUTH,
                context.getString(R.string.notif_channel_auth_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        auth.setDescription(context.getString(R.string.notif_channel_auth_desc));

        nm.createNotificationChannel(grades);
        nm.createNotificationChannel(plan);
        nm.createNotificationChannel(finance);
        nm.createNotificationChannel(auth);
    }
}

