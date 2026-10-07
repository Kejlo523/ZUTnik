package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Shared cache-only presentation and explicit, account-scoped widget refresh. */
public final class PlanDayWidgetHelper {
    static final String EXTRA_OWNER = "pl.kejlo.zutnik.PLAN_WIDGET_OWNER";
    static final String REQUESTED_AT = "requested_at";
    private static final long REQUEST_LIFETIME_MS = 2L * 60L * 1000L;
    static final long MAX_REFRESH_DURATION_MS = 60L * 1000L;
    private static final AtomicReference<RefreshRun> activeRefresh = new AtomicReference<>();

    static final class RefreshRun {
        final String owner;
        final long expiresAt;
        final UsosApi.RequestControl request;

        RefreshRun(String owner, long now) {
            this.owner = owner;
            expiresAt = now + MAX_REFRESH_DURATION_MS;
            request = new UsosApi.RequestControl(expiresAt);
        }

        boolean isActive(String currentOwner, long now) {
            return !owner.isEmpty() && owner.equals(currentOwner)
                    && now >= expiresAt - MAX_REFRESH_DURATION_MS && now < expiresAt;
        }
    }

    private PlanDayWidgetHelper() {}

    static String owner(Context context) {
        ZutnikSession session = ZutnikSession.getInstance(context);
        if (!session.isLoggedIn()) return "";
        return part(BuildConfig.USOS_BASE_URL) + part(session.getLoginType())
                + part(session.getUserId()) + part(session.getActiveStudyId());
    }

    private static String part(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    static Set<String> hiddenKeys(Context context) {
        Set<String> saved = context.getSharedPreferences("zutnik_plan", Context.MODE_PRIVATE)
                .getStringSet("plan_hidden_filters_v2", Collections.emptySet());
        return saved == null ? Collections.emptySet() : new HashSet<>(saved);
    }

    static PlanRepository.PlanResult loadVisibleCache(Context context, LocalDate today) throws Exception {
        PlanRepository repository = new PlanRepository(context.getApplicationContext());
        PlanRepository.PlanResult result = new PlanRepository.PlanResult();
        PlanRepository.PlanResult current = repository.loadPlanFromCache("week", today);
        PlanRepository.PlanResult next = repository.loadPlanFromCache("week", today.plusWeeks(1));
        if (current != null && current.dayColumns != null) result.dayColumns.addAll(current.dayColumns);
        if (next != null && next.dayColumns != null) result.dayColumns.addAll(next.dayColumns);
        return result;
    }

    static List<PlanRepository.PlanEventUi> eventsForDate(PlanRepository.PlanResult result,
            LocalDate date, Set<String> hiddenKeys) {
        if (result == null || result.dayColumns == null) return Collections.emptyList();
        List<PlanRepository.PlanEventUi> events = new ArrayList<>();
        for (PlanRepository.DayColumn column : result.dayColumns) {
            if (column == null || !date.equals(column.date) || column.events == null) continue;
            for (PlanRepository.PlanEventUi event : column.events) {
                if (event != null && !hiddenKeys.contains(event.subjectKey)) events.add(event);
            }
        }
        events.sort(Comparator.comparingInt(event -> event.startMin));
        return events;
    }

    static List<PlanRepository.PlanEventUi> upcomingEvents(PlanRepository.PlanResult result,
            LocalDate date, LocalDate today, int nowMin, Set<String> hiddenKeys) {
        List<PlanRepository.PlanEventUi> events = new ArrayList<>(eventsForDate(result, date, hiddenKeys));
        if (date.equals(today)) events.removeIf(event -> event.endMin <= nowMin);
        return events;
    }

    static LocalDate bestDate(PlanRepository.PlanResult result, LocalDate today, int nowMin,
            Set<String> hiddenKeys) {
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = today.plusDays(offset);
            if (!upcomingEvents(result, date, today, nowMin, hiddenKeys).isEmpty()) return date;
        }
        return today.plusDays(1);
    }

    static long cacheTimestamp(Context context, LocalDate date) {
        if (!ZutnikSession.getInstance(context).isUsosLogin()) return 0L;
        return UsosTimetableStore.get(context).getRangeTimestamp(date, date);
    }

    static boolean verifiedRange(Context context, LocalDate start, LocalDate end) {
        return !ZutnikSession.getInstance(context).isUsosLogin()
                || UsosTimetableStore.get(context).hasVerifiedRange(start, end);
    }

    static boolean isRefreshing(Context context) {
        return isRefreshing(owner(context), SystemClock.elapsedRealtime());
    }

    static boolean isRefreshing(String currentOwner, long now) {
        RefreshRun run = activeRefresh.get();
        return run != null && run.isActive(currentOwner, now);
    }

    static RefreshRun beginRefresh(String owner, long now) {
        RefreshRun run = new RefreshRun(owner, now);
        activeRefresh.set(run);
        return run;
    }

    static void finishRefresh(RefreshRun run) {
        if (run != null) activeRefresh.compareAndSet(run, null);
    }

    static boolean isRequestFresh(long requestedAt, long now) {
        long age = now - requestedAt;
        return requestedAt > 0L && age >= 0L && age <= REQUEST_LIFETIME_MS;
    }

    static void enqueueManualRefresh(Context context) {
        if (!ZutnikSession.getInstance(context).isUsosLogin()) return;
        String owner = owner(context);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(RefreshWorker.class)
                .setInputData(new Data.Builder().putString(EXTRA_OWNER, owner)
                        .putLong(REQUESTED_AT, System.currentTimeMillis()).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("plan-widget-manual:" + owner,
                ExistingWorkPolicy.KEEP, request);
    }

    static void fetchManualRange(Context context, String expectedOwner, LocalDate today) throws Exception {
        fetchManualRange(context, expectedOwner, today,
                new UsosApi.RequestControl(SystemClock.elapsedRealtime() + MAX_REFRESH_DURATION_MS));
    }

    private static void fetchManualRange(Context context, String expectedOwner, LocalDate today,
            UsosApi.RequestControl request) throws Exception {
        if (expectedOwner == null || expectedOwner.isEmpty() || !expectedOwner.equals(owner(context))
                || !ZutnikSession.getInstance(context).isUsosLogin()) return;
        UsosTimetableStore store = UsosTimetableStore.get(context);
        if (!expectedOwner.equals(owner(context))) return;
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        // Nonpartial short ranges fetch student weeks only, without semester/group fan-out.
        store.refreshWidgetRange(monday, monday.plusDays(13), request);
    }

    private static void scheduleRefreshExpiry(Context context, long expiresAt) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(context, PlanDayWidgetProvider.class)
                .setAction(PlanDayWidgetProvider.ACTION_REFRESH)
                .setData(Uri.parse("zutnik://plan-widget/refresh-expiry"));
        PendingIntent pending = PendingIntent.getBroadcast(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // The launcher retains RemoteViews after process death; this redraw survives it too.
        // It reads cache only, including when the normal periodic widget timer is disabled.
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, expiresAt + 1000L, pending);
    }

    private static void redraw(Context context) {
        context.sendBroadcast(new Intent(context, PlanDayWidgetProvider.class)
                .setAction(PlanDayWidgetProvider.ACTION_REFRESH));
    }

    public static final class RefreshWorker extends Worker {
        private final Object lifecycleLock = new Object();
        private Thread runner;
        private RefreshRun run;
        private boolean stopped;
        public RefreshWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull @Override public Result doWork() {
            Context context = getApplicationContext();
            String expectedOwner = getInputData().getString(EXTRA_OWNER);
            long requestedAt = getInputData().getLong(REQUESTED_AT, 0L);
            try {
                if (expectedOwner == null || !expectedOwner.equals(owner(context))
                        || !isRequestFresh(requestedAt, System.currentTimeMillis()) || isStopped()) {
                    return Result.success();
                }
                synchronized (lifecycleLock) {
                    if (stopped || isStopped()) return Result.success();
                    runner = Thread.currentThread();
                    run = beginRefresh(expectedOwner, SystemClock.elapsedRealtime());
                }
                scheduleRefreshExpiry(context, run.expiresAt);
                redraw(context);
                fetchManualRange(context, expectedOwner, LocalDate.now(), run.request);
            } catch (Exception failure) {
                Log.w("ZUTnik-PlanWidget", "Manual widget refresh failed; retaining cache", failure);
            } finally {
                synchronized (lifecycleLock) {
                    finishRefresh(run);
                    runner = null;
                }
                // Even an expired job after process restart must clear the launcher's stale loader.
                redraw(context);
            }
            // Offline/failures never create an automatic network retry; the store owns backoff.
            return Result.success();
        }

        @Override public void onStopped() {
            super.onStopped();
            synchronized (lifecycleLock) {
                stopped = true;
                if (run != null) {
                    run.request.cancel();
                    finishRefresh(run);
                }
                if (runner != null) runner.interrupt();
            }
            redraw(getApplicationContext());
        }
    }
}
