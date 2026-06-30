package pl.kejlo.zutnik;

import android.graphics.Bitmap;

/**
 * Legacy wrapper for ImageCache to maintain compatibility.
 * Redirects all calls to the centralized ImageCache.
 */
public class ImageMemoryCache {

    /**
     * @deprecated Use ImageCache.getInstance().getFromMemory() for UI thread
     * or ImageCache.getInstance().getFromDisk() for background thread.
     * This method tries memory, then disk, which can cause LAG on main thread.
     */
    @Deprecated
    public static Bitmap get(String url) {
        try {
            // Forward to memory-only check to avoid lag on main thread if legacy code calls this.
            // If caller needs disk, they should use ImageCache.getInstance().getFromDisk() explicitly.
            return ImageCache.getInstance().getFromMemory(url);
        } catch (Exception e) {
            return null;
        }
    }

    public static void put(String url, Bitmap bitmap) {
        try {
            ImageCache.getInstance().put(url, bitmap);
        } catch (Exception e) {
        }
    }
}
