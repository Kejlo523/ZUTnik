package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AppShortcutRouter {

    public static final String ACTION_PLAN_TODAY = "pl.kejlo.zutnik.shortcut.PLAN_TODAY";
    public static final String ACTION_ATTENDANCE = "pl.kejlo.zutnik.shortcut.ATTENDANCE";
    public static final String ACTION_GRADES = "pl.kejlo.zutnik.shortcut.GRADES";
    public static final String ACTION_PLAN_SEARCH = "pl.kejlo.zutnik.shortcut.PLAN_SEARCH";
    public static final String EXTRA_SHORTCUT_ACTION = "extra_shortcut_action";

    private static final String PREFS_NAME = "zutnik_shortcuts";
    private static final String KEY_PENDING_ACTION = "pending_action";
    private static final String KEY_PENDING_AT = "pending_at";
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    private AppShortcutRouter() {
    }

    @Nullable
    public static String extractAction(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        return isShortcutAction(action) ? action : null;
    }

    public static boolean isPlanAction(@Nullable String action) {
        return ACTION_PLAN_TODAY.equals(action) || ACTION_PLAN_SEARCH.equals(action);
    }

    public static void rememberPending(@NonNull Context context, @Nullable String action) {
        if (!isShortcutAction(action)) {
            return;
        }
        preferences(context).edit()
                .putString(KEY_PENDING_ACTION, action)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .apply();
    }

    @Nullable
    public static String consumePending(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        String action = prefs.getString(KEY_PENDING_ACTION, null);
        long savedAt = prefs.getLong(KEY_PENDING_AT, 0L);
        prefs.edit().remove(KEY_PENDING_ACTION).remove(KEY_PENDING_AT).apply();
        if (!isShortcutAction(action)
                || savedAt <= 0L
                || System.currentTimeMillis() - savedAt > PENDING_TTL_MS) {
            return null;
        }
        return action;
    }

    public static void clearPending(@NonNull Context context) {
        preferences(context).edit().clear().apply();
    }

    @NonNull
    public static Intent createMainIntent(@NonNull Context context, @Nullable String action) {
        MainNavHelper.Screen initialTab = MainNavHelper.Screen.HOME;
        if (ACTION_PLAN_TODAY.equals(action) || ACTION_PLAN_SEARCH.equals(action)) {
            initialTab = MainNavHelper.Screen.PLAN;
        } else if (ACTION_GRADES.equals(action)) {
            initialTab = MainNavHelper.Screen.GRADES;
        }

        Intent intent = MainShellActivity.createIntent(context, initialTab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (isShortcutAction(action)) {
            intent.putExtra(EXTRA_SHORTCUT_ACTION, action);
        }
        return intent;
    }

    private static boolean isShortcutAction(@Nullable String action) {
        return ACTION_PLAN_TODAY.equals(action)
                || ACTION_ATTENDANCE.equals(action)
                || ACTION_GRADES.equals(action)
                || ACTION_PLAN_SEARCH.equals(action);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
