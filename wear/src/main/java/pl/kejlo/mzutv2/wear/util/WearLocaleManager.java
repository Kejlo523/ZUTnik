package pl.kejlo.mzutv2.wear.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;

/**
 * Wear language strategy:
 * - No override: use watch system language.
 * - After sync: use language sent from phone snapshot.
 */
public final class WearLocaleManager {

    private static final String PREFS = "wear_locale";
    private static final String KEY_LANGUAGE_OVERRIDE = "language_override";

    private WearLocaleManager() {
    }

    public static Context wrap(Context context) {
        if (context == null) {
            return null;
        }
        Locale target = resolveTargetLocale(context);
        Locale current = getCurrentLocale(context);
        if (target == null || current == null || sameLocale(current, target)) {
            return context;
        }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(target);
        config.setLocales(new LocaleList(target));
        return context.createConfigurationContext(config);
    }

    public static boolean updateOverrideFromSnapshot(Context context, WearPlanSnapshot snapshot) {
        String next = snapshot != null ? normalizeLanguageTag(snapshot.languageTag) : "";
        String prev = getOverrideLanguageTag(context);
        if (safeEquals(prev, next)) {
            return false;
        }

        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (next.isEmpty()) {
            editor.remove(KEY_LANGUAGE_OVERRIDE);
        } else {
            editor.putString(KEY_LANGUAGE_OVERRIDE, next);
        }
        editor.apply();
        return true;
    }

    public static String getOverrideLanguageTag(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return "";
        }
        return normalizeLanguageTag(prefs.getString(KEY_LANGUAGE_OVERRIDE, ""));
    }

    public static boolean needsRecreateForCurrentContext(Context context) {
        if (context == null) {
            return false;
        }
        Locale current = getCurrentLocale(context);
        Locale target = resolveTargetLocale(context);
        return current != null && target != null && !sameLocale(current, target);
    }

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Locale resolveTargetLocale(Context context) {
        String overrideTag = getOverrideLanguageTag(context);
        Locale override = parseLocale(overrideTag);
        if (override != null) {
            return override;
        }
        Resources system = Resources.getSystem();
        if (system != null && system.getConfiguration() != null
                && system.getConfiguration().getLocales() != null
                && !system.getConfiguration().getLocales().isEmpty()) {
            return system.getConfiguration().getLocales().get(0);
        }
        return Locale.getDefault();
    }

    private static Locale getCurrentLocale(Context context) {
        if (context == null) {
            return null;
        }
        Configuration config = context.getResources().getConfiguration();
        if (config.getLocales() != null && !config.getLocales().isEmpty()) {
            return config.getLocales().get(0);
        }
        return Locale.getDefault();
    }

    private static Locale parseLocale(String languageTag) {
        String normalized = normalizeLanguageTag(languageTag);
        if (normalized.isEmpty()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isEmpty()) {
            return null;
        }
        return locale;
    }

    private static String normalizeLanguageTag(String languageTag) {
        if (languageTag == null) {
            return "";
        }
        String normalized = languageTag.trim().replace('_', '-');
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized;
    }

    private static boolean sameLocale(Locale a, Locale b) {
        return toComparableTag(a).equals(toComparableTag(b));
    }

    private static String toComparableTag(Locale locale) {
        if (locale == null) {
            return "";
        }
        String tag = locale.toLanguageTag();
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        return tag;
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }
}
