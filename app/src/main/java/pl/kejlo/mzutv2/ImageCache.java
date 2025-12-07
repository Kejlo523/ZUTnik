package pl.kejlo.mzutv2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Singleton managing both memory and disk caching for images.
 * Keeps images for 7 days (logic handled implicitly by file timestamp or could be explicit).
 * For simplicity, we just rely on existence in disk cache, manual clearing logic can be added if needed,
 * but users requested persistence.
 */
public class ImageCache {

    private static final String TAG = "ImageCache";
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 50; // 50 MB
    private static final String DISK_CACHE_SUBDIR = "images";

    private static ImageCache instance;
    private LruCache<String, Bitmap> memoryCache;
    private File diskCacheDir;

    private ImageCache(Context context) {
        // Initialize memory cache (1/8 of app memory)
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        // Initialize disk cache dir
        try {
            diskCacheDir = new File(context.getCacheDir(), DISK_CACHE_SUBDIR);
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create disk cache dir", e);
        }
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new ImageCache(context);
        }
    }

    public static synchronized ImageCache getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ImageCache not initialized. Call init(Context) first.");
        }
        return instance;
    }

    /**
     * Tries to get bitmap from memory cache only.
     * Safe to call on UI thread.
     */
    public Bitmap getFromMemory(String url) {
        if (url == null) return null;
        return memoryCache.get(hashKeyForDisk(url));
    }

    /**
     * Tries to get bitmap from disk cache.
     * WARNING: IO Operation - Do NOT call on UI thread.
     * If found, puts it into memory cache.
     */
    public Bitmap getFromDisk(String url) {
        if (url == null) return null;
        String key = hashKeyForDisk(url);

        // Check memory first just in case
        Bitmap mem = memoryCache.get(key);
        if (mem != null) return mem;

        if (diskCacheDir != null) {
            File file = new File(diskCacheDir, key);
            if (file.exists()) {
                Bitmap disk = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (disk != null) {
                    memoryCache.put(key, disk);
                    return disk;
                }
            }
        }
        return null;
    }

    /**
     * Gets an input stream for the cached image file.
     * Useful for WebView interception or serving raw bytes.
     * WARN: Caller must close the stream.
     */
    public java.io.InputStream getStream(String url) {
        if (url == null) return null;
        if (diskCacheDir == null) return null;

        String key = hashKeyForDisk(url);
        File file = new File(diskCacheDir, key);
        if (file.exists()) {
            try {
                return new java.io.FileInputStream(file);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Legacy method. Tries memory then disk.
     * WARNING: fast on memory hit, slow on disk hit.
     */
    public Bitmap get(String url) {
        return getFromDisk(url); // getFromDisk checks memory internally
    }

    public void clear() {
        memoryCache.evictAll();
        if (diskCacheDir != null && diskCacheDir.exists()) {
            File[] files = diskCacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    public void put(String url, Bitmap bitmap) {
        if (url == null || bitmap == null) return;

        String key = hashKeyForDisk(url);

        // 1. Memory
        memoryCache.put(key, bitmap);

        // 2. Disk
        if (diskCacheDir != null) {
            synchronized (this) {
                File file = new File(diskCacheDir, key);
                if (!file.exists()) {
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(file);
                        // Save as JPEG to save space, quality 50 (strong compression)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, fos);
                        fos.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to save image to disk", e);
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (IOException ignored) {}
                        }
                    }
                }
            }
        }
    }

    private String hashKeyForDisk(String key) {
        try {
            MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            return bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(key.hashCode());
        }
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
