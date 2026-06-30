package pl.kejlo.zutnik;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ImageCache {

    private static final String TAG = "ImageCache";
    private static final String DISK_CACHE_SUBDIR = "images";

    private static ImageCache instance;

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskCacheDir;

    private ImageCache(Context context) {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = maxMemoryKb / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        File cacheDir = null;
        try {
            cacheDir = new File(context.getCacheDir(), DISK_CACHE_SUBDIR);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create disk cache dir", e);
        }
        diskCacheDir = cacheDir;
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

    public Bitmap getFromMemory(String url) {
        if (url == null) {
            return null;
        }
        return memoryCache.get(hashKeyForDisk(url));
    }

    public Bitmap getFromDisk(String url) {
        if (url == null) {
            return null;
        }

        String key = hashKeyForDisk(url);
        Bitmap memoryBitmap = memoryCache.get(key);
        if (memoryBitmap != null) {
            return memoryBitmap;
        }

        if (diskCacheDir == null) {
            return null;
        }

        File file = new File(diskCacheDir, key);
        if (!file.exists()) {
            return null;
        }

        Bitmap diskBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (diskBitmap != null) {
            memoryCache.put(key, diskBitmap);
        }
        return diskBitmap;
    }

    public InputStream getStream(String url) {
        if (url == null || diskCacheDir == null) {
            return null;
        }

        File file = new File(diskCacheDir, hashKeyForDisk(url));
        if (!file.exists()) {
            return null;
        }

        try {
            return new FileInputStream(file);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void clear() {
        memoryCache.evictAll();
        if (diskCacheDir == null || !diskCacheDir.exists()) {
            return;
        }

        File[] files = diskCacheDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            file.delete();
        }
    }

    public void put(String url, Bitmap bitmap) {
        if (url == null || bitmap == null) {
            return;
        }

        String key = hashKeyForDisk(url);
        memoryCache.put(key, bitmap);

        if (diskCacheDir == null) {
            return;
        }

        synchronized (this) {
            File file = new File(diskCacheDir, key);
            if (file.exists()) {
                return;
            }

            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, output);
                output.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to save image to disk", e);
            }
        }
    }

    private String hashKeyForDisk(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            return bytesToHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(key.hashCode());
        }
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            String hex = Integer.toHexString(0xFF & value);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
