package pl.kejlo.mzutv2;

import android.graphics.Bitmap;
import android.util.LruCache;

public class ImageMemoryCache {

    // TTL obrazków – 7 dni
    private static final long TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private static class Entry {
        Bitmap bitmap;
        long timestamp;
    }

    // cache w KB
    private static final LruCache<String, Entry> cache;

    static {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = maxMemoryKb / 8; // np. 1/8 dostępnej pamięci
        cache = new LruCache<String, Entry>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Entry value) {
                if (value == null || value.bitmap == null) return 0;
                return value.bitmap.getByteCount() / 1024;
            }
        };
    }

    public static Bitmap get(String url) {
        if (url == null) return null;
        Entry e = cache.get(url);
        if (e == null || e.bitmap == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - e.timestamp > TTL_MS) {
            // przeterminowane – wywal z cache
            cache.remove(url);
            return null;
        }
        return e.bitmap;
    }

    public static void put(String url, Bitmap bitmap) {
        if (url == null || bitmap == null) return;
        Entry e = new Entry();
        e.bitmap = bitmap;
        e.timestamp = System.currentTimeMillis();
        cache.put(url, e);
    }
}
