package pl.kejlo.mzutv2;

/**
 * Centralized cache TTL policy used across the app.
 * Values balance freshness (critical student data) and network usage.
 */
public final class CachePolicy {

    private static final long SECOND_MS = 1_000L;
    private static final long MINUTE_MS = 60L * SECOND_MS;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    /** Extra hour added to every API refresh interval. */
    private static final long API_PING_OFFSET_MS = HOUR_MS;

    private CachePolicy() {
        // Utility class
    }

    // Core student data (must refresh relatively quickly)
    public static final long STUDIES_TTL_MS = 10L * MINUTE_MS + API_PING_OFFSET_MS;
    public static final long SEMESTERS_TTL_MS = 10L * MINUTE_MS + API_PING_OFFSET_MS;
    public static final long GRADES_TTL_MS = 20L * MINUTE_MS + API_PING_OFFSET_MS;
    public static final long INFO_TTL_MS = 30L * MINUTE_MS + API_PING_OFFSET_MS;

    // Secondary content
    public static final long NEWS_TTL_MS = 4L * HOUR_MS + API_PING_OFFSET_MS;
    public static final long ABOUT_STATS_TTL_MS = 12L * HOUR_MS + API_PING_OFFSET_MS;
    public static final long FINANCE_TTL_MS = 4L * HOUR_MS + API_PING_OFFSET_MS;

    // Plan-related caches
    public static final long PLAN_FILTER_TTL_MS = 12L * HOUR_MS + API_PING_OFFSET_MS;
    public static final long PLAN_ALBUM_TTL_MS = 6L * HOUR_MS + API_PING_OFFSET_MS;
    public static final long PLAN_USER_SCOPE_TTL_MS = 20L * MINUTE_MS + API_PING_OFFSET_MS;
    public static final long PLAN_SESSION_TTL_MS = 6L * HOUR_MS + API_PING_OFFSET_MS;
}
