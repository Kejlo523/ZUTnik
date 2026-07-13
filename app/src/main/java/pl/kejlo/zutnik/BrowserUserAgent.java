package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

final class BrowserUserAgent {

    private static final String PREFS = "network_browser_profile";
    private static final String KEY_INSTALL_SEED = "install_seed";

    private static final String[] ANDROID_PROFILES = {
            "Android 12; SM-G991B",
            "Android 13; SM-A536B",
            "Android 14; SM-S918B",
            "Android 15; SM-S921B",
            "Android 14; Pixel 7",
            "Android 15; Pixel 8",
            "Android 13; 2201117TY",
            "Android 14; motorola edge 40",
            "Android 12; CPH2207",
            "Android 13; V2145",
            "Android 14; SM-A556B",
            "Android 15; Pixel 9"
    };

    private static final String[] CHROME_VERSIONS = {
            "145.0.0.0",
            "146.0.0.0",
            "147.0.0.0",
            "148.0.0.0",
            "149.0.0.0",
            "150.0.0.0"
    };

    private BrowserUserAgent() {
    }

    @NonNull
    static String current() {
        ZutnikSession session = ZutnikSession.getInstance();
        String studentNumber = normalizeStudentNumber(session.getStudentNumber());
        if (!studentNumber.isEmpty()) {
            return fromSeed("student:" + studentNumber);
        }

        Context context = ZutnikSession.getAppContextOrNull();
        if (context != null) {
            return fromSeed("install:" + getOrCreateInstallSeed(context));
        }
        return fromSeed("install:default");
    }

    @NonNull
    static String fromSeed(@NonNull String seed) {
        byte[] digest = sha256(seed);
        String androidProfile = ANDROID_PROFILES[unsigned(digest[0]) % ANDROID_PROFILES.length];
        String chromeVersion = CHROME_VERSIONS[unsigned(digest[1]) % CHROME_VERSIONS.length];
        return String.format(
                Locale.ROOT,
                "Mozilla/5.0 (Linux; %s) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/%s Mobile Safari/537.36",
                androidProfile,
                chromeVersion);
    }

    @NonNull
    private static synchronized String getOrCreateInstallSeed(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String seed = prefs.getString(KEY_INSTALL_SEED, null);
        if (seed != null && !seed.isEmpty()) {
            return seed;
        }
        seed = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_INSTALL_SEED, seed).apply();
        return seed;
    }

    @NonNull
    private static String normalizeStudentNumber(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9A-Za-z]", "").toLowerCase(Locale.ROOT);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    @NonNull
    private static byte[] sha256(@NonNull String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
