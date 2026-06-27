package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsPrefs {

    private SettingsPrefs() {
    }

    public static final String PREFS_SETTINGS = "mzut_settings";

    public static final String KEY_APP_LANGUAGE = "app_language";
    public static final String DEFAULT_APP_LANGUAGE = "pl";

    public static final String KEY_APP_THEME = "app_theme";

    public static final String KEY_WIDGET_REFRESH_INTERVAL = "widget_refresh_interval";
    public static final String DEFAULT_WIDGET_REFRESH_INTERVAL = "30";

    public static String normalizeWidgetRefreshInterval(String intervalStr) {
        if (intervalStr == null) {
            return DEFAULT_WIDGET_REFRESH_INTERVAL;
        }
        switch (intervalStr) {
            case "75":
                return "15";
            case "90":
                return "30";
            case "105":
                return "45";
            case "300":
                return "240";
            default:
                return intervalStr;
        }
    }

    public static String getWidgetRefreshInterval(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_WIDGET_REFRESH_INTERVAL, DEFAULT_WIDGET_REFRESH_INTERVAL);
        String normalized = normalizeWidgetRefreshInterval(stored);
        if (!normalized.equals(stored)) {
            prefs.edit().putString(KEY_WIDGET_REFRESH_INTERVAL, normalized).apply();
        }
        return normalized;
    }

    public static final String KEY_NOTIFICATIONS_PERMISSION_ASKED = "notifications_permission_asked";
    public static final String KEY_NOTIFICATIONS_MASTER_ENABLED = "notifications_master_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_MASTER_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_GRADES_ENABLED = "notifications_grades_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_GRADES_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_ENABLED = "notifications_plan_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_FINANCE_ENABLED = "notifications_finance_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_FINANCE_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_FINANCE_DUE_ENABLED = "notifications_finance_due_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_FINANCE_DUE_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_FINANCE_BOOKED_ENABLED = "notifications_finance_booked_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_FINANCE_BOOKED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED = "notifications_plan_moved_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_MOVED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED = "notifications_plan_cancelled_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_CANCELLED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED = "notifications_plan_added_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_ADDED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_REMOVED_ENABLED = "notifications_plan_removed_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_REMOVED_ENABLED = true;
}
