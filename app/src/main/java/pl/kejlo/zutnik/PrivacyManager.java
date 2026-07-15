package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.biometric.BiometricManager;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PrivacyManager {

    private static final int PIN_LENGTH = 4;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile boolean unlockedForProcess;
    private static volatile boolean lockActivityLaunching;

    private PrivacyManager() {
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(SettingsPrefs.KEY_PRIVACY_MODE_ENABLED, false)
                && hasPin(context);
    }

    public static boolean hasPin(Context context) {
        SharedPreferences prefs = preferences(context);
        return !prefs.getString(SettingsPrefs.KEY_PRIVACY_PIN_SALT, "").isEmpty()
                && !prefs.getString(SettingsPrefs.KEY_PRIVACY_PIN_HASH, "").isEmpty();
    }

    public static int requiredPinLength() {
        return PIN_LENGTH;
    }

    public static boolean setPinAndEnable(Context context, String pin) {
        if (pin == null || !pin.matches("\\d{" + PIN_LENGTH + "}")) {
            return false;
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = hashPin(pin, salt);
        if (hash == null) {
            return false;
        }
        preferences(context).edit()
                .putString(SettingsPrefs.KEY_PRIVACY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(SettingsPrefs.KEY_PRIVACY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putBoolean(SettingsPrefs.KEY_PRIVACY_MODE_ENABLED, true)
                .putBoolean(SettingsPrefs.KEY_PRIVACY_BIOMETRIC_ENABLED, false)
                .apply();
        unlockedForProcess = true;
        return true;
    }

    public static boolean verifyPin(Context context, String pin) {
        try {
            SharedPreferences prefs = preferences(context);
            byte[] salt = Base64.decode(
                    prefs.getString(SettingsPrefs.KEY_PRIVACY_PIN_SALT, ""),
                    Base64.NO_WRAP);
            byte[] expected = Base64.decode(
                    prefs.getString(SettingsPrefs.KEY_PRIVACY_PIN_HASH, ""),
                    Base64.NO_WRAP);
            byte[] actual = hashPin(pin, salt);
            return actual != null && MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void disable(Context context) {
        preferences(context).edit()
                .remove(SettingsPrefs.KEY_PRIVACY_PIN_SALT)
                .remove(SettingsPrefs.KEY_PRIVACY_PIN_HASH)
                .remove(SettingsPrefs.KEY_PRIVACY_BIOMETRIC_ENABLED)
                .putBoolean(SettingsPrefs.KEY_PRIVACY_MODE_ENABLED, false)
                .apply();
        markUnlocked();
    }

    public static boolean isBiometricAvailable(Context context) {
        int result = BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isBiometricEnabled(Context context) {
        return isEnabled(context)
                && preferences(context).getBoolean(
                        SettingsPrefs.KEY_PRIVACY_BIOMETRIC_ENABLED,
                        false)
                && isBiometricAvailable(context);
    }

    public static void setBiometricEnabled(Context context, boolean enabled) {
        preferences(context).edit()
                .putBoolean(
                        SettingsPrefs.KEY_PRIVACY_BIOMETRIC_ENABLED,
                        enabled && isBiometricAvailable(context))
                .apply();
    }

    public static boolean isUnlocked() {
        return unlockedForProcess;
    }

    public static void markUnlocked() {
        unlockedForProcess = true;
        lockActivityLaunching = false;
    }

    public static void lock() {
        unlockedForProcess = false;
        lockActivityLaunching = false;
    }

    public static synchronized boolean beginLockActivityLaunch() {
        if (lockActivityLaunching) {
            return false;
        }
        lockActivityLaunching = true;
        return true;
    }

    public static void cancelLockActivityLaunch() {
        lockActivityLaunching = false;
    }

    private static byte[] hashPin(String pin, byte[] salt) {
        if (pin == null || salt == null || salt.length == 0) {
            return null;
        }
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception ignored) {
            return null;
        } finally {
            spec.clearPassword();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
    }
}
