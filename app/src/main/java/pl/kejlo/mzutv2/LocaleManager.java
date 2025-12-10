package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

public class LocaleManager {

    private static final String PREFS_SETTINGS = "mzut_settings";
    private static final String KEY_LANGUAGE = "app_language";
    private static final String DEFAULT_LANG = "pl";

    public static Context wrap(Context context) {
        String lang = getLanguage(context);
        return updateResources(context, lang);
    }

    public static void setLanguage(Context context, String langCode) {
        if (langCode == null || langCode.isEmpty()) {
            langCode = DEFAULT_LANG;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply();
    }

    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANG);
    }

    private static Context updateResources(Context context, String langCode) {
        Locale locale;
        if (langCode.contains("-")) {
            String[] split = langCode.split("-");
            if (split.length > 1) {
                locale = new Locale(split[0], split[1]);
            } else {
                locale = new Locale(langCode);
            }
        } else if (langCode.contains("_")) {
            String[] split = langCode.split("_");
            if (split.length > 1) {
                locale = new Locale(split[0], split[1]);
            } else {
                locale = new Locale(langCode);
            }
        } else {
            locale = new Locale(langCode);
        }
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }
}
