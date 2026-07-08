package pl.kejlo.zutnik;

/**
 * Centralized cache TTL policy used across the app.
 * Values balance freshness (critical student data) and network usage.
 */
public final class CachePolicy {

    private static final long SECOND_MS = 1_000L;
    private static final long MINUTE_MS = 60L * SECOND_MS;
    private static final long HOUR_MS = 60L * MINUTE_MS;

    private CachePolicy() {
        // Utility class
    }

    public static final long MANUAL_REFRESH_COOLDOWN_MS = 5L * MINUTE_MS;
    public static final long BACKGROUND_SYNC_INTERVAL_MS = 6L * HOUR_MS;

    // Core student data. These are intentionally conservative; screens use cache
    // first and manual refresh remains available after a short cooldown.
    public static final long STUDIES_TTL_MS = 7L * 24L * HOUR_MS;
    public static final long SEMESTERS_TTL_MS = 7L * 24L * HOUR_MS;
    public static final long GRADES_TTL_MS = 6L * HOUR_MS;
    public static final long INFO_TTL_MS = 7L * 24L * HOUR_MS;

    // Secondary content
    public static final long NEWS_TTL_MS = 24L * HOUR_MS;
    public static final long ABOUT_STATS_TTL_MS = 7L * 24L * HOUR_MS;
    public static final long FINANCE_TTL_MS = 24L * HOUR_MS;

    // Plan-related caches
    public static final long PLAN_FILTER_TTL_MS = 7L * 24L * HOUR_MS;
    public static final long PLAN_ALBUM_TTL_MS = 7L * 24L * HOUR_MS;
    public static final long PLAN_USER_SCOPE_TTL_MS = 6L * HOUR_MS;
    public static final long PLAN_SESSION_TTL_MS = 7L * 24L * HOUR_MS;
}
