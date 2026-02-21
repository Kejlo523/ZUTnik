import Foundation

public enum SettingsPrefs {
    public static let prefsSettings = "mzut_settings"

    public static let appLanguage = "app_language"
    public static let defaultAppLanguage = "pl"

    public static let appTheme = "app_theme"

    public static let widgetRefreshInterval = "widget_refresh_interval"
    public static let defaultWidgetRefreshInterval = "30"

    public static let notificationsPermissionAsked = "notifications_permission_asked"
    public static let notificationsMasterEnabled = "notifications_master_enabled"
    public static let defaultNotificationsMasterEnabled = true

    public static let notificationsGradesEnabled = "notifications_grades_enabled"
    public static let defaultNotificationsGradesEnabled = true

    public static let notificationsPlanEnabled = "notifications_plan_enabled"
    public static let defaultNotificationsPlanEnabled = true

    public static let notificationsPlanMovedEnabled = "notifications_plan_moved_enabled"
    public static let defaultNotificationsPlanMovedEnabled = true

    public static let notificationsPlanCancelledEnabled = "notifications_plan_cancelled_enabled"
    public static let defaultNotificationsPlanCancelledEnabled = true

    public static let notificationsPlanAddedEnabled = "notifications_plan_added_enabled"
    public static let defaultNotificationsPlanAddedEnabled = true

    public static let notificationsPlanRemovedEnabled = "notifications_plan_removed_enabled"
    public static let defaultNotificationsPlanRemovedEnabled = true
}
