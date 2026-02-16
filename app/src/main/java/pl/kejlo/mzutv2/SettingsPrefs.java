package pl.kejlo.mzutv2;

public final class SettingsPrefs {

    private SettingsPrefs() {
    }

    public static final String PREFS_SETTINGS = "mzut_settings";

    public static final String KEY_APP_LANGUAGE = "app_language";
    public static final String DEFAULT_APP_LANGUAGE = "pl";

    public static final String KEY_APP_THEME = "app_theme";

    public static final String KEY_WIDGET_REFRESH_INTERVAL = "widget_refresh_interval";
    public static final String DEFAULT_WIDGET_REFRESH_INTERVAL = "30";

    public static final String KEY_NOTIFICATIONS_PERMISSION_ASKED = "notifications_permission_asked";
    public static final String KEY_NOTIFICATIONS_MASTER_ENABLED = "notifications_master_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_MASTER_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_GRADES_ENABLED = "notifications_grades_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_GRADES_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_ENABLED = "notifications_plan_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED = "notifications_plan_moved_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_MOVED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED = "notifications_plan_cancelled_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_CANCELLED_ENABLED = true;

    public static final String KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED = "notifications_plan_added_enabled";
    public static final boolean DEFAULT_NOTIFICATIONS_PLAN_ADDED_ENABLED = true;

    public static final String KEY_DEBUG_TOOLS_ENABLED = "debug_tools_enabled";
    public static final boolean DEFAULT_DEBUG_TOOLS_ENABLED = false;

    public static final String KEY_DEBUG_RUN_GRADES = "debug_run_grades";
    public static final boolean DEFAULT_DEBUG_RUN_GRADES = true;

    public static final String KEY_DEBUG_RUN_PLAN = "debug_run_plan";
    public static final boolean DEFAULT_DEBUG_RUN_PLAN = true;

    public static final String KEY_DEBUG_IGNORE_CALENDAR = "debug_ignore_calendar";
    public static final boolean DEFAULT_DEBUG_IGNORE_CALENDAR = true;
}
