package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public final class NetworkRefreshPolicy {

    private static final String TAG = "ZUTnikNetPolicy";
    private static final String PREFS_NAME = "zutnik_network_refresh_ledger";
    private static final String KEY_LAST_ATTEMPT_PREFIX = "last_attempt_";
    private static final String KEY_LAST_SUCCESS_PREFIX = "last_success_";
    private static final String KEY_LAST_MANUAL_PREFIX = "last_manual_";

    public enum Mode {
        BACKGROUND,
        SCREEN_AUTO,
        MANUAL,
        WIDGET
    }

    public enum Module {
        GRADES,
        PLAN,
        FINANCE,
        NEWS,
        INFO,
        ABOUT,
        HOME_USER,
        SESSION,
        LINK_PREVIEW
    }

    public static final class Decision {
        public final boolean allowNetwork;
        public final String reason;
        public final long cacheAgeMs;

        Decision(boolean allowNetwork, String reason, long cacheAgeMs) {
            this.allowNetwork = allowNetwork;
            this.reason = reason != null ? reason : "";
            this.cacheAgeMs = cacheAgeMs;
        }
    }

    private NetworkRefreshPolicy() {
    }

    public static Decision evaluate(
            Context context,
            Module module,
            Mode mode,
            String scope,
            long cacheTimestampMs) {
        return evaluate(context, module, mode, scope, cacheTimestampMs, defaultMinimumIntervalMs(module, mode));
    }

    public static Decision evaluate(
            Context context,
            Module module,
            Mode mode,
            String scope,
            long cacheTimestampMs,
            long minimumIntervalMs) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        long now = System.currentTimeMillis();
        String effectiveScope = buildEffectiveScope(appContext, scope);
        long lastSuccess = getLastSuccess(appContext, module, effectiveScope);
        long referenceTs = cacheTimestampMs > 0L ? cacheTimestampMs : lastSuccess;
        long cacheAgeMs = referenceTs > 0L ? Math.max(0L, now - referenceTs) : Long.MAX_VALUE;

        Decision decision = decide(appContext, module, mode, effectiveScope, cacheAgeMs, minimumIntervalMs, now);
        logDecision(module, mode, decision, cacheAgeMs);
        return decision;
    }

    public static void recordAttempt(Context context, Module module, Mode mode, String scope) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null || module == null) {
            return;
        }
        String effectiveScope = buildEffectiveScope(appContext, scope);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(appContext).edit()
                .putLong(key(KEY_LAST_ATTEMPT_PREFIX, module, effectiveScope), now);
        if (mode == Mode.MANUAL) {
            editor.putLong(key(KEY_LAST_MANUAL_PREFIX, module, effectiveScope), now);
        }
        editor.apply();
    }

    public static void recordSuccess(Context context, Module module, String scope) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null || module == null) {
            return;
        }
        String effectiveScope = buildEffectiveScope(appContext, scope);
        prefs(appContext).edit()
                .putLong(key(KEY_LAST_SUCCESS_PREFIX, module, effectiveScope), System.currentTimeMillis())
                .apply();
    }

    public static String describeForUser(Context context, Decision decision) {
        String reason = decision != null ? decision.reason : "";
        if ("manual_cooldown".equals(reason)) {
            return "Odświeżano niedawno - pokazuję dane z cache.";
        }
        if ("offline".equals(reason)) {
            return "Brak sieci - pokazuję dane z cache.";
        }
        return "Pokazuję dane z cache.";
    }

    public static boolean hasCachedAcademicCalendar(Context context) {
        return context != null && !PlanRepository.getCachedSessionDates(context).isEmpty();
    }

    public static boolean isGradesWindow(Context context, LocalDate date) {
        List<PlanRepository.SessionPeriod> periods = cachedPeriods(context);
        if (periods.isEmpty() || date == null) {
            return false;
        }

        for (PlanRepository.SessionPeriod period : periods) {
            if (period == null || !PlanRepository.isSessionPeriodName(period.name)) {
                continue;
            }
            LocalDate windowStart = period.startDate.minusDays(14);
            LocalDate windowEnd = period.endDate.plusDays(7);
            if (!date.isBefore(windowStart) && !date.isAfter(windowEnd)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPlanWindow(Context context, LocalDate date) {
        List<PlanRepository.SessionPeriod> periods = cachedPeriods(context);
        if (periods.isEmpty() || date == null) {
            return false;
        }
        PlanRepository.SessionPeriod noClasses = PlanRepository.findActivePeriod(periods, date, true);
        if (noClasses == null) {
            return true;
        }
        LocalDate nextClassesDate = noClasses.endDate.plusDays(1);
        return !date.isBefore(nextClassesDate.minusDays(14))
                && !date.isAfter(nextClassesDate);
    }

    private static Decision decide(
            Context appContext,
            Module module,
            Mode mode,
            String scope,
            long cacheAgeMs,
            long minimumIntervalMs,
            long now) {
        if (appContext == null || module == null || mode == null) {
            return deny("missing_context", cacheAgeMs);
        }

        if (!NetworkStatusHelper.isNetworkAvailable(appContext)) {
            return deny("offline", cacheAgeMs);
        }

        if (mode == Mode.MANUAL) {
            long lastManual = prefs(appContext).getLong(key(KEY_LAST_MANUAL_PREFIX, module, scope), 0L);
            if (lastManual > 0L && (now - lastManual) < CachePolicy.MANUAL_REFRESH_COOLDOWN_MS) {
                return deny("manual_cooldown", cacheAgeMs);
            }
            return allow("manual", cacheAgeMs);
        }

        if (mode == Mode.WIDGET) {
            return deny("widget_cache_only", cacheAgeMs);
        }

        if (mode == Mode.BACKGROUND) {
            if ((module == Module.GRADES || module == Module.PLAN) && !hasCachedAcademicCalendar(appContext)) {
                return deny("missing_academic_calendar", cacheAgeMs);
            }
            if (module == Module.GRADES && !isGradesWindow(appContext, LocalDate.now())) {
                return deny("outside_grades_window", cacheAgeMs);
            }
            if (module == Module.PLAN && !isPlanWindow(appContext, LocalDate.now())) {
                return deny("outside_plan_window", cacheAgeMs);
            }
            long lastAttempt = prefs(appContext).getLong(key(KEY_LAST_ATTEMPT_PREFIX, module, scope), 0L);
            long lastSuccess = prefs(appContext).getLong(key(KEY_LAST_SUCCESS_PREFIX, module, scope), 0L);
            long lastNetworkTouch = Math.max(lastAttempt, lastSuccess);
            if (lastNetworkTouch > 0L && (now - lastNetworkTouch) < minimumIntervalMs) {
                return deny("background_interval", cacheAgeMs);
            }
            return allow("background_due", cacheAgeMs);
        }

        long ttl = defaultTtlMs(module);
        if (cacheAgeMs <= ttl) {
            return deny("cache_fresh", cacheAgeMs);
        }
        if (module == Module.GRADES && !isGradesWindow(appContext, LocalDate.now())) {
            return deny("outside_grades_window", cacheAgeMs);
        }
        if (module == Module.PLAN && !isPlanWindow(appContext, LocalDate.now())) {
            return deny("outside_plan_window", cacheAgeMs);
        }
        return allow("stale_cache", cacheAgeMs);
    }

    private static Decision allow(String reason, long cacheAgeMs) {
        return new Decision(true, reason, cacheAgeMs);
    }

    private static Decision deny(String reason, long cacheAgeMs) {
        return new Decision(false, reason, cacheAgeMs);
    }

    private static long defaultMinimumIntervalMs(Module module, Mode mode) {
        if (mode != Mode.BACKGROUND) {
            return 0L;
        }
        if (module == Module.FINANCE) {
            return CachePolicy.FINANCE_TTL_MS;
        }
        return CachePolicy.BACKGROUND_SYNC_INTERVAL_MS;
    }

    private static long defaultTtlMs(Module module) {
        switch (module) {
            case GRADES:
                return CachePolicy.GRADES_TTL_MS;
            case PLAN:
                return CachePolicy.PLAN_USER_SCOPE_TTL_MS;
            case FINANCE:
                return CachePolicy.FINANCE_TTL_MS;
            case NEWS:
                return CachePolicy.NEWS_TTL_MS;
            case INFO:
            case HOME_USER:
                return CachePolicy.INFO_TTL_MS;
            case ABOUT:
                return CachePolicy.ABOUT_STATS_TTL_MS;
            case SESSION:
                return CachePolicy.PLAN_SESSION_TTL_MS;
            case LINK_PREVIEW:
                return 30L * 24L * 60L * 60L * 1000L;
            default:
                return CachePolicy.BACKGROUND_SYNC_INTERVAL_MS;
        }
    }

    private static List<PlanRepository.SessionPeriod> cachedPeriods(Context context) {
        return context != null ? PlanRepository.getCachedSessionDates(context) : java.util.Collections.emptyList();
    }

    private static long getLastSuccess(Context context, Module module, String scope) {
        if (context == null || module == null) {
            return 0L;
        }
        return prefs(context).getLong(key(KEY_LAST_SUCCESS_PREFIX, module, scope), 0L);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String buildEffectiveScope(Context context, String scope) {
        String normalized = scope != null ? scope.trim() : "";
        if (!normalized.isEmpty()) {
            return normalized;
        }
        if (context != null) {
            ZutnikSession.initializeFromPreferences(context);
        }
        ZutnikSession session = ZutnikSession.getInstance();
        String userId = safeScopePart(session.getUserId());
        String studyId = safeScopePart(session.getActiveStudyId());
        if (studyId.isEmpty()) {
            Study active = session.getActiveStudy();
            if (active != null) {
                studyId = safeScopePart(active.przynaleznoscId);
            }
        }
        return "u:" + userId + "|s:" + studyId;
    }

    private static String safeScopePart(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static String key(String prefix, Module module, String scope) {
        String raw = module.name().toLowerCase(Locale.ROOT) + "_" + (scope != null ? scope : "");
        return prefix + Integer.toHexString(raw.hashCode());
    }

    private static void logDecision(Module module, Mode mode, Decision decision, long cacheAgeMs) {
        if (!BuildConfig.DEBUG || decision == null) {
            return;
        }
        String age = cacheAgeMs == Long.MAX_VALUE ? "none" : String.valueOf(cacheAgeMs);
        Log.d(TAG, (decision.allowNetwork ? "ALLOW" : "SKIP")
                + " module=" + module
                + " mode=" + mode
                + " reason=" + decision.reason
                + " cacheAgeMs=" + age);
    }
}
