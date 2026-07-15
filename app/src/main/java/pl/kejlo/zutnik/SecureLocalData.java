package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Lightweight AES-GCM storage for sensitive preference payloads. */
public final class SecureLocalData {

    private static final String TAG = "ZUTnik-SecureData";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "zutnik_local_data_v1";
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static volatile SecretKey cachedKey;

    private SecureLocalData() {
    }

    public static String encrypt(Context context, String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + encrypted.length);
            payload.put((byte) iv.length);
            payload.put(iv);
            payload.put(encrypted);
            return PREFIX + Base64.encodeToString(payload.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Unable to encrypt local data", e);
            return null;
        }
    }

    public static String decrypt(Context context, String storedValue) {
        if (storedValue == null || !storedValue.startsWith(PREFIX)) {
            return storedValue;
        }
        try {
            byte[] payload = Base64.decode(storedValue.substring(PREFIX.length()), Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            int ivLength = buffer.get() & 0xff;
            if (ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) {
                return null;
            }
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Unable to decrypt local data", e);
            return null;
        }
    }

    public static String readString(
            Context context,
            SharedPreferences preferences,
            String key,
            String defaultValue) {
        String stored = preferences.getString(key, null);
        if (stored == null) {
            return defaultValue;
        }
        String plain = decrypt(context, stored);
        if (plain == null) {
            return defaultValue;
        }
        if (!stored.startsWith(PREFIX)) {
            putString(context, preferences, key, plain);
        }
        return plain;
    }

    public static boolean putString(
            Context context,
            SharedPreferences preferences,
            String key,
            String value) {
        if (value == null) {
            preferences.edit().remove(key).apply();
            return true;
        }
        String encrypted = encrypt(context, value);
        if (encrypted == null) {
            return false;
        }
        preferences.edit().putString(key, encrypted).apply();
        return true;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey key = cachedKey;
        if (key != null) {
            return key;
        }
        synchronized (SecureLocalData.class) {
            if (cachedKey == null) {
                cachedKey = loadOrCreateKey();
            }
            return cachedKey;
        }
    }

    private static SecretKey loadOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
