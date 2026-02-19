package pl.kejlo.mzutv2;

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

import java.util.concurrent.TimeUnit;

public final class NotificationSyncManager {

    private NotificationSyncManager() {
    }

    public static final String UNIQUE_WORK_NAME = "mzut_background_sync";
    public static final String UNIQUE_BOOTSTRAP_WORK_NAME = "mzut_background_sync_bootstrap";
    public static final long SYNC_INTERVAL_MINUTES = 30L;
    private static final long SYNC_FLEX_MINUTES = 10L;
    private static final String PREFS_RUNTIME = "mzut_sync_runtime";
    private static final String KEY_BOOTSTRAP_SYNC_USER = "bootstrap_sync_user_v1";
    private static final String PREFS_BG = "mzut_background_sync_cache";
    private static final String KEY_GRADES_BASELINE_READY = "grades_baseline_ready_v1";
    private static final String KEY_PLAN_BASELINE_READY = "plan_baseline_ready_v1";

    public static final String CHANNEL_GRADES = "mzut_grades_changes";
    public static final String CHANNEL_PLAN = "mzut_plan_changes";
    public static final String CHANNEL_AUTH = "mzut_session_auth";

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

    public static boolean isAnyFeatureEnabled(Context context) {
        if (!isMasterEnabled(context)) {
            return false;
        }
        boolean grades = isGradesEnabled(context);
        boolean plan = isPlanEnabled(context) && isAnyPlanCategoryEnabled(context);
        return grades || plan;
    }

    public static void syncWorkerSchedule(Context context) {
        Context appContext = context.getApplicationContext();
        ensureChannels(appContext);

        MzutSession.initializeFromPreferences(appContext);
        MzutSession session = MzutSession.getInstance();
        boolean hasSession = session.getAuthKey() != null && session.getUserId() != null;

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

        MzutSession.initializeFromPreferences(appContext);
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null) {
            return;
        }
        if (!hasNotificationPermission(appContext) || !isAnyFeatureEnabled(appContext)) {
            return;
        }

        SharedPreferences runtimePrefs = appContext.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE);
        String syncedUser = runtimePrefs.getString(KEY_BOOTSTRAP_SYNC_USER, "");
        SharedPreferences bgPrefs = appContext.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        boolean gradesBaselineReady = bgPrefs.getBoolean(KEY_GRADES_BASELINE_READY, false);
        boolean requiresPlanBaseline = isPlanEnabled(appContext) && isAnyPlanCategoryEnabled(appContext);
        boolean planBaselineReady = bgPrefs.getBoolean(KEY_PLAN_BASELINE_READY, false);

        if (userId.equals(syncedUser)
                && gradesBaselineReady
                && (!requiresPlanBaseline || planBaselineReady)) {
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

        runtimePrefs.edit().putString(KEY_BOOTSTRAP_SYNC_USER, userId).apply();
    }

    public static void cancelWorker(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK_NAME);
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

        NotificationChannel auth = new NotificationChannel(
                CHANNEL_AUTH,
                context.getString(R.string.notif_channel_auth_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        auth.setDescription(context.getString(R.string.notif_channel_auth_desc));

        nm.createNotificationChannel(grades);
        nm.createNotificationChannel(plan);
        nm.createNotificationChannel(auth);
    }
}

