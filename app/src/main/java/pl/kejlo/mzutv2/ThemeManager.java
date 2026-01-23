package pl.kejlo.mzutv2;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

public class ThemeManager {

    private static final String PREFS_NAME = "mzut_settings";
    private static final String KEY_THEME = "app_theme";

    public static final String THEME_DEFAULT = "default";
    public static final String THEME_DEEP_BLUE = "deep_blue";
    public static final String THEME_HIGH_CONTRAST = "high_contrast";

    public static void applyTheme(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String theme = prefs.getString(KEY_THEME, THEME_DEFAULT);

        switch (theme) {
            case THEME_DEEP_BLUE:
                activity.setTheme(R.style.Theme_MZUTv2_DeepBlue);
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

    public static String getTheme(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME, THEME_DEFAULT);
    }

    public static void setTheme(Context context, String themeValue) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, themeValue)
                .apply();
    }

    public static int resolveEventColor(Context context, String typeClass) {
        String theme = getTheme(context);
        int color = 0;

        switch (theme) {
            default:
                // Standard Color Coding
                int resId = getDefaultEventColorResId(typeClass);
                color = androidx.core.content.ContextCompat.getColor(context, resId);
                break;
        }
        return color;
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
