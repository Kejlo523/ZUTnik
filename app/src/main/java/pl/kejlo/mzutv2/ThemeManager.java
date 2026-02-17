package pl.kejlo.mzutv2;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.WindowManager;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class ThemeManager {

    public static final String THEME_DEFAULT = "default";
    public static final String THEME_DEEP_BLUE = "deep_blue";
    public static final String THEME_LIME = "lime";
    public static final String THEME_HIGH_CONTRAST = "high_contrast";

    public static void applyTheme(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        String theme = prefs.getString(SettingsPrefs.KEY_APP_THEME, THEME_DEFAULT);

        switch (theme) {
            case THEME_DEEP_BLUE:
                activity.setTheme(R.style.Theme_MZUTv2_DeepBlue);
                break;
            case THEME_LIME:
                activity.setTheme(R.style.Theme_MZUTv2_Lime);
                break;
            case THEME_HIGH_CONTRAST:
                activity.setTheme(R.style.Theme_MZUTv2_HighContrast);
                break;
            case THEME_DEFAULT:
            default:
                activity.setTheme(R.style.Theme_MZUTv2);
                break;
        }
    }

    public static void applySystemBars(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        int bg = resolveColor(activity, R.attr.mzBg);
        android.view.Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            if (attrs.layoutInDisplayCutoutMode
                    != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                window.setAttributes(attrs);
            }
        }

        boolean light = ColorUtils.calculateLuminance(bg) > 0.5;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        }
    }

    public static String getTheme(Context context) {
        return context.getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString(SettingsPrefs.KEY_APP_THEME, THEME_DEFAULT);
    }

    public static void setTheme(Context context, String themeValue) {
        context.getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsPrefs.KEY_APP_THEME, themeValue)
                .apply();
    }

    public static int resolveEventColor(Context context, String typeClass) {
        int resId = getDefaultEventColorResId(typeClass);
        int baseColor = androidx.core.content.ContextCompat.getColor(context, resId);

        String theme = getTheme(context);
        if (THEME_DEEP_BLUE.equals(theme)) {
            int tint = resolveColor(context, R.attr.mzPrimary);
            return ColorUtils.blendARGB(baseColor, tint, 0.28f);
        }
        if (THEME_LIME.equals(theme)) {
            int tint = resolveColor(context, R.attr.mzLime);
            return ColorUtils.blendARGB(baseColor, tint, 0.28f);
        }
        return baseColor;
    }

    private static int getDefaultEventColorResId(String typeClass) {
        if (typeClass == null)
            typeClass = "";
        switch (typeClass) {
            case "week-event-type-lecture":
                return R.color.plan_event_lecture_bg;
            case "week-event-type-lab":
                return R.color.plan_event_lab_bg;
            case "week-event-type-auditory":
                return R.color.plan_event_auditory_bg;
            case "week-event-type-project":
                return R.color.plan_event_project_bg;
            case "week-event-type-seminar":
                return R.color.plan_event_seminar_bg;
            case "week-event-type-diploma-seminar":
                return R.color.plan_event_diploma_seminar_bg;
            case "week-event-type-diploma":
                return R.color.plan_event_diploma_bg;
            case "week-event-type-lectorate":
                return R.color.plan_event_lectorate_bg;
            case "week-event-type-conservatory":
                return R.color.plan_event_conservatory_bg;
            case "week-event-type-consultation":
                return R.color.plan_event_consultation_bg;
            case "week-event-type-field":
                return R.color.plan_event_field_bg;
            case "week-event-type-class":
                return R.color.plan_event_class_bg;
            case "week-event-type-exam":
                return R.color.plan_event_exam_bg;
            case "week-event-type-exam-remote":
                return R.color.plan_event_exam_remote_bg;
            case "week-event-type-cancelled":
                return R.color.plan_event_cancelled_bg;
            case "week-event-type-rector":
                return R.color.plan_event_rector_bg;
            case "week-event-type-dean":
                return R.color.plan_event_dean_bg;
            case "week-event-type-remote":
                return R.color.plan_event_remote_bg;
            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake":
                return R.color.plan_event_pass_bg;
            default:
                return R.color.plan_event_default_bg;
        }
    }

    public static int resolveColor(Context context, int attrResId) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        android.content.res.Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(attrResId, typedValue, true);
        return typedValue.data;
    }
}
