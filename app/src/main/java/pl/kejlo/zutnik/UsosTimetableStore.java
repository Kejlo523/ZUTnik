package pl.kejlo.zutnik;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import pl.kejlo.zutnik.PlanRepository.PlanDebug;
import pl.kejlo.zutnik.PlanRepository.PlanEventRaw;

/** Account-scoped raw timetable cache. Construction and all cache queries are network-free. */
public final class UsosTimetableStore {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long WEEK_TTL = 6L * HOUR;
    private static final long CATALOG_TTL = 7L * 24L * HOUR;
    private static final long GROUP_TTL = 7L * 24L * HOUR;
    private static final long FAILURE_BACKOFF = 5L * 60L * 1000L;
    private static final long MAX_FAILURE_BACKOFF = 6L * HOUR;
    private static final long REQUEST_SPACING_MS = 500L;
    private static final long LEGACY_RETRY = 15L * 60L * 1000L;
    private static final int MAX_VISIBLE_WEEK_REQUESTS = 6;
    private static final String FILE_NAME = "usos_timetable_v1.json";
    private static final String STUDENT = "services/tt/student";
    private static final String PARTICIPANT = "services/groups/participant";
    private static final String CLASSGROUP = "services/tt/classgroup_dates2";
    private static final String ACTIVITY_FIELDS = "type|start_time|end_time|name|course_id|course_name"
            + "|classtype_id|classtype_name|group_number|unit_id|building_name|room_number|frequency";
    private static final String GROUP_FIELDS = "course_unit_id|group_number|class_type|class_type_id"
            + "|course_name|lecturers|term_id";
    private static final Pattern HTTP_ERROR = Pattern.compile("^USOS API HTTP (\\d{3})(?::|$)");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final Object INSTANCE_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    // Shared across retired and current owners, so an account switch cannot overlap requests.
    private static final ReentrantLock IO_LOCK = new ReentrantLock(true);
    // Accessed only while holding IO_LOCK, including across session changes.
    private static long lastRequestStartedAt;
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "usos-timetable-sync");
        thread.setDaemon(true);
        return thread;
    });
    @SuppressLint("StaticFieldLeak") // The store retains only the application context.
    private static volatile UsosTimetableStore instance;

    private final Context context;
    private final ZutnikSession capturedSession;
    private final String baseUrl;
    private final String userId;
    private final String studyId;
    private final String accessToken;
    private final String accessSecret;
    private final String studentNumber;
    private final String owner;
    private final AtomicFile cacheFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object stateLock = new Object();
    private final ThreadLocal<UsosApi.RequestControl> widgetRequest = new ThreadLocal<>();
    private volatile boolean retired;
    private volatile boolean authFailed;
    private State state = State.empty();
    private boolean running;
    private int completed;
    private int total;
    private String error = "";
    private boolean fallbackUsed;
    private long widgetRevision;
    private long syncGeneration;
    private final List<RangeRequest> pendingRanges = new ArrayList<>();

    public interface Listener {
        void onSyncStateChanged(SyncState state);
    }

    public static final class SyncState {
        public final boolean running;
        public final int completed;
        public final int total;
        public final long revision;
        public final String error;
        public final boolean fallbackUsed;

        private SyncState(boolean running, int completed, int total, long revision,
                          String error, boolean fallbackUsed) {
            this.running = running;
            this.completed = completed;
            this.total = total;
            this.revision = revision;
            this.error = error;
            this.fallbackUsed = fallbackUsed;
        }
    }

    public static UsosTimetableStore get(Context context) {
        Objects.requireNonNull(context, "context");
        Context app = Objects.requireNonNull(context.getApplicationContext(), "application context");
        synchronized (INSTANCE_LOCK) {
            ZutnikSession session = ZutnikSession.getInstance(app);
            if (instance == null || !instance.matchesSession(session)) {
                if (instance != null) instance.retire();
                instance = new UsosTimetableStore(app, session);
            }
            return instance;
        }
    }

    private UsosTimetableStore(Context context, ZutnikSession session) {
        this.context = context;
        capturedSession = session;
        baseUrl = text(BuildConfig.USOS_BASE_URL);
        userId = text(session.getUserId());
        studyId = text(session.getActiveStudyId());
        accessToken = text(session.getUsosAccessToken());
        accessSecret = text(session.getUsosAccessTokenSecret());
        studentNumber = text(session.getStudentNumber());
        // These USOS endpoints return all of the student's studies in a single response.
        owner = identity(baseUrl, userId);
        File directory = new File(new File(context.getFilesDir(), "usos_timetable"), ownerHash(owner));
        cacheFile = new AtomicFile(new File(directory, FILE_NAME));
        readCache();
        widgetRevision = state.revision;
    }

    /** Inclusive range. Nonpartial short ranges are student-only, suitable for notification workers. */
    public Map<LocalDate, List<PlanEventRaw>> loadRange(LocalDate start, LocalDate end,
            boolean allowNetwork, boolean forceRefresh, boolean partialAllowed, PlanDebug debug)
            throws IOException, JSONException {
        validateRange(start, end);
        if (!isCurrent()) return Collections.emptyMap();
        State initial = snapshot();
        if (!allowNetwork || !NetworkStatusHelper.isNetworkAvailable(context)) {
            return cachedRange(initial, start, end);
        }
        checkCurrent();
        if (forceRefresh && !reserveManualRefresh()) return cachedRange(snapshot(), start, end);
        initial = snapshot();
        long baseline = initial.version;
        boolean shortRange = ChronoUnit.DAYS.between(start, end) < 14L;
        if (partialAllowed) {
            LocalDate today = LocalDate.now();
            LocalDate currentWeek = monday(today);
            try {
                if (forceRefresh || (!initial.weeks.containsKey(currentWeek)
                        && getRangeTimestamp(currentWeek, currentWeek.plusDays(6)) == 0L)) {
                    ensureWeek(currentWeek, forceRefresh, baseline, debug);
                }
                LocalDate requestedWeek = monday(start);
                if (shortRange && !requestedWeek.equals(currentWeek)
                        && (forceRefresh || (!snapshot().weeks.containsKey(requestedWeek)
                        && getRangeTimestamp(requestedWeek, requestedWeek.plusDays(6)) == 0L))) {
                    ensureWeek(requestedWeek, forceRefresh, baseline, debug);
                }
            } catch (RequestFailure failure) {
                if (failure.stop) throw failure;
                // The first screen can still render an old or partial cache after a transport failure.
            } catch (JSONException ignored) {
                // ensureWeek has already recorded a sanitized error and retry deadline.
            }
            checkCurrent();
            Map<LocalDate, List<PlanEventRaw>> result = cachedRange(snapshot(), start, end);
            startSync(today, forceRefresh, baseline, start, end);
            return result;
        }

        IOException firstIoError = null;
        JSONException firstJsonError = null;
        if (shortRange) {
            // Fourteen inclusive dates may intersect three Monday-based student windows.
            for (LocalDate week = monday(start); !week.isAfter(end); week = week.plusWeeks(1)) {
                try {
                    ensureWeek(week, forceRefresh, baseline, debug);
                } catch (RequestFailure failure) {
                    if (failure.stop) throw failure;
                    if (firstIoError == null) firstIoError = failure;
                } catch (JSONException failure) {
                    if (firstJsonError == null) firstJsonError = failure;
                }
            }
        } else {
            // Explicit wide requests (filters/export) load complete selected terms, not weekly exams.
            ensureCatalog(forceRefresh, baseline, debug);
            Catalog catalog = snapshot().catalog;
            if (catalog != null) {
                for (Term term : matchingTerms(catalog, start, end)) {
                    for (Group group : term.groups) {
                        try {
                            ensureGroup(term, group, forceRefresh, baseline, debug);
                        } catch (RequestFailure failure) {
                            if (failure.stop) throw failure;
                            if (firstIoError == null) firstIoError = failure;
                        } catch (JSONException failure) {
                            if (firstJsonError == null) firstJsonError = failure;
                        }
                    }
                }
            }
        }
        checkCurrent();
        if (firstIoError != null) throw firstIoError;
        if (firstJsonError != null) throw firstJsonError;
        refreshWidgetsIfChanged();
        return cachedRange(snapshot(), start, end);
    }

    /** Manual widget work has a hard deadline and never tries the retired plan host. */
    void refreshWidgetRange(LocalDate start, LocalDate end, UsosApi.RequestControl control)
            throws IOException, JSONException {
        if (ChronoUnit.DAYS.between(start, end) >= 14L) throw new IllegalArgumentException("Widget range too wide");
        UsosApi.RequestControl previous = widgetRequest.get();
        widgetRequest.set(Objects.requireNonNull(control));
        try {
            control.remainingMs();
            loadRange(start, end, true, true, false, null);
        } finally {
            if (previous == null) widgetRequest.remove();
            else widgetRequest.set(previous);
        }
    }

    /** Foreground opt-in only. Cache-only loads never call this method. */
    public void startSync(LocalDate anchor, boolean forceRefresh) {
        if (forceRefresh && !reserveManualRefresh()) return;
        startSync(anchor == null ? LocalDate.now() : anchor, forceRefresh, snapshot().version, null, null);
    }

    private boolean reserveManualRefresh() {
        synchronized (stateLock) {
            long now = System.currentTimeMillis();
            if (!isCurrent() || authFailed || running || !NetworkStatusHelper.isNetworkAvailable(context)
                    || backedOff(state, "network", now)
                    || fresh(state.manualRefreshAt, CachePolicy.MANUAL_REFRESH_COOLDOWN_MS, now)) return false;
            state = new State(state.weeks, state.catalog, state.groups, state.retries,
                    state.version + 1L, state.revision, now);
        }
        persistSnapshot();
        return true;
    }

    private void startSync(LocalDate anchor, boolean force, long baseline, LocalDate start, LocalDate end) {
        if (!isCurrent() || authFailed) return;
        long generation;
        synchronized (stateLock) {
            if (!isCurrent() || authFailed) return;
            if (start != null) enqueueRangeLocked(new RangeRequest(start, end, force, baseline));
            if (running) return;
            if (backedOff(state, "network", System.currentTimeMillis())
                    || !NetworkStatusHelper.isNetworkAvailable(context)) return;
            boolean rangeWork = false;
            for (RangeRequest request : pendingRanges) {
                if (hasRangeWork(state, request)) rangeWork = true;
            }
            if (!force && !hasSyncWork(state, anchor) && !rangeWork) {
                pendingRanges.clear();
                return;
            }
            running = true;
            generation = ++syncGeneration;
            completed = 0;
            total = 3;
            error = "";
            fallbackUsed = false;
            dispatchLocked();
        }
        SYNC_EXECUTOR.execute(() -> runSync(anchor, force, baseline, generation));
    }

    private void runSync(LocalDate anchor, boolean force, long baseline, long generation) {
        try {
            syncWeek(monday(anchor), force, baseline);
            syncWeek(monday(anchor).plusWeeks(1), force, baseline);
            refreshWidgetsIfChanged();
            try {
                ensureCatalog(force, baseline, null);
            } catch (RequestFailure failure) {
                if (failure.stop) throw failure;
            } catch (JSONException ignored) {
            }
            completeStep();
            checkContinuation();
            Catalog catalog = snapshot().catalog;
            List<Term> terms = catalog == null ? Collections.emptyList() : upcomingTerms(catalog, anchor);
            int groupCount = 0;
            for (Term term : terms) groupCount += term.groups.size();
            synchronized (stateLock) {
                if (!isCurrent()) return;
                total += groupCount;
                dispatchLocked();
            }
            for (Term term : terms) {
                for (Group group : term.groups) {
                    checkContinuation();
                    try {
                        // The manual screen refresh verifies visible student weeks, not every
                        // already-cached semester schedule. Explicit wide refreshes remain forced.
                        ensureGroup(term, group, false, baseline, null);
                    } catch (RequestFailure failure) {
                        if (failure.stop) throw failure;
                    } catch (JSONException ignored) {
                    }
                    completeStep();
                }
            }
            drainRequestedRanges(generation);
        } catch (RequestFailure failure) {
            setError(failure.reason);
        } catch (RuntimeException ignored) {
            setError("sync_failed");
        } finally {
            synchronized (stateLock) {
                if (syncGeneration == generation) {
                    running = false;
                    if (isCurrent()) dispatchLocked();
                }
            }
            refreshWidgetsIfChanged();
        }
    }

    private void syncWeek(LocalDate week, boolean force, long baseline) throws RequestFailure {
        checkContinuation();
        try {
            ensureWeek(week, force, baseline, null);
        } catch (RequestFailure failure) {
            if (failure.stop) throw failure;
        } catch (JSONException ignored) {
        }
        synchronized (stateLock) {
            Week cached = state.weeks.get(week);
            if (isCurrent() && cached != null && "legacy".equals(cached.source)) fallbackUsed = true;
        }
        completeStep();
    }

    private void drainRequestedRanges(long generation) throws RequestFailure {
        while (true) {
            checkContinuation();
            List<RangeRequest> batch;
            synchronized (stateLock) {
                if (!isCurrent()) return;
                if (pendingRanges.isEmpty()) {
                    // Close atomically with enqueueing, so a late screen request starts a new run.
                    if (syncGeneration == generation) {
                        running = false;
                        dispatchLocked();
                    }
                    return;
                }
                batch = new ArrayList<>(pendingRanges);
                pendingRanges.clear();
            }
            for (int index = 0; index < batch.size(); index++) {
                RangeRequest request = batch.get(index);
                try {
                    checkContinuation();
                    if (!hasRangeWork(snapshot(), request)) continue;
                    if (catalogNeedsWork(snapshot(), request, System.currentTimeMillis())) {
                        try {
                            ensureCatalog(request.force, request.baseline, null);
                        } catch (RequestFailure failure) {
                            if (failure.stop) throw failure;
                        } catch (JSONException ignored) {
                        }
                    }
                    checkContinuation();
                    State current = snapshot();
                    List<Term> terms = current.catalog == null ? Collections.emptyList()
                            : matchingTerms(current.catalog, request.start, request.end);
                    Map<Term, List<Group>> groupWork = new LinkedHashMap<>();
                    long now = System.currentTimeMillis();
                    int count = 0;
                    for (Term term : terms) {
                        List<Group> groups = new ArrayList<>();
                        for (Group group : term.groups) {
                            if (groupNeedsWork(current, term, group, request, now)) groups.add(group);
                        }
                        if (!groups.isEmpty()) groupWork.put(term, groups);
                        count += groups.size();
                    }
                    List<LocalDate> weeks = requestedWeekWork(current, request, now);
                    synchronized (stateLock) {
                        if (!isCurrent()) return;
                        total += count + weeks.size();
                        dispatchLocked();
                    }
                    for (Map.Entry<Term, List<Group>> entry : groupWork.entrySet()) {
                        for (Group group : entry.getValue()) {
                            checkContinuation();
                            try {
                                ensureGroup(entry.getKey(), group,
                                        request.force && !supportsStudentWeeks(request), request.baseline, null);
                            } catch (RequestFailure failure) {
                                if (failure.stop) throw failure;
                            } catch (JSONException ignored) {
                            }
                            completeStep();
                        }
                    }
                    // Visible day/week/month windows also need the student feed's exams. Explicit
                    // semester ranges stay bulk-only; six-Monday months can remain partly unverified.
                    for (LocalDate week : weeks) syncWeek(week, request.force, request.baseline);
                } catch (RequestFailure failure) {
                    synchronized (stateLock) {
                        if (isCurrent()) {
                            // Keep interrupted/outage batches resumable, including requests not begun yet.
                            for (int remaining = index; remaining < batch.size(); remaining++) {
                                enqueueRangeLocked(batch.get(remaining));
                            }
                        }
                    }
                    throw failure;
                }
            }
        }
    }

    private void enqueueRangeLocked(RangeRequest request) {
        LocalDate start = request.start;
        LocalDate end = request.end;
        boolean force = request.force;
        long baseline = request.baseline;
        boolean studentWeeks = supportsStudentWeeks(request);
        for (int i = pendingRanges.size() - 1; i >= 0; i--) {
            RangeRequest previous = pendingRanges.get(i);
            if (studentWeeks != supportsStudentWeeks(previous)) continue;
            if (ChronoUnit.DAYS.between(end, previous.start) > 1L
                    || ChronoUnit.DAYS.between(previous.end, start) > 1L) continue;
            LocalDate mergedStart = previous.start.isBefore(start) ? previous.start : start;
            LocalDate mergedEnd = previous.end.isAfter(end) ? previous.end : end;
            // Keep separate visible months separate, rather than coalescing away their weekly work.
            if (studentWeeks && ChronoUnit.DAYS.between(mergedStart, mergedEnd) >= 31L) continue;
            start = mergedStart;
            end = mergedEnd;
            force |= previous.force;
            baseline = Math.min(baseline, previous.baseline);
            pendingRanges.remove(i);
        }
        pendingRanges.add(new RangeRequest(start, end, force, baseline));
    }

    public SyncState getSyncState() {
        synchronized (stateLock) {
            if (!isCurrent()) {
                return new SyncState(false, completed, total, state.revision, "session_changed", false);
            }
            return syncStateLocked();
        }
    }

    public void addListener(Listener listener) {
        if (listener == null || !isCurrent()) return;
        listeners.add(listener);
        synchronized (stateLock) {
            SyncState current = syncStateLocked();
            mainHandler.post(() -> deliver(listener, current));
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** Bulk/legacy coverage never proves that exams have not been removed. */
    public boolean hasVerifiedRange(LocalDate start, LocalDate end) {
        validateRange(start, end);
        if (!isCurrent() || authFailed || accessToken.isEmpty() || accessSecret.isEmpty()) return false;
        State snapshot = snapshot();
        long now = System.currentTimeMillis();
        for (LocalDate week = monday(start); !week.isAfter(end); week = week.plusWeeks(1)) {
            Week record = snapshot.weeks.get(week);
            Retry failed = snapshot.retries.get(weekKey(week));
            if (record == null || !"usos".equals(record.source)
                    || !fresh(record.timestamp, weekTtl(week, record), now)
                    || (failed != null && failed.failedAt >= record.timestamp)) return false;
        }
        return isCurrent();
    }

    public boolean isRangeCached(LocalDate start, LocalDate end) {
        return getRangeTimestamp(start, end) > 0L;
    }

    /** Explicit export also needs weekly exams/other activities, not only full class-group schedules. */
    public void prepareExportRange(LocalDate start, LocalDate end) throws IOException, JSONException {
        loadRange(start, end, true, false, false, new PlanDebug());
        if (NetworkStatusHelper.isNetworkAvailable(context)) {
            long baseline = snapshot().version;
            for (LocalDate week = monday(start); !week.isAfter(end); week = week.plusWeeks(1)) {
                ensureWeek(week, false, baseline, null);
            }
        }
        State cached = snapshot();
        for (LocalDate week = monday(start); !week.isAfter(end); week = week.plusWeeks(1)) {
            if (!isCurrent() || !cached.weeks.containsKey(week)) {
                throw new IOException(context.getString(R.string.plan_sync_export_incomplete));
            }
        }
        refreshWidgetsIfChanged();
    }

    /** Oldest successful timestamp covering every requested date; zero means incomplete coverage. */
    public long getRangeTimestamp(LocalDate start, LocalDate end) {
        validateRange(start, end);
        if (!isCurrent()) return 0L;
        State snapshot = snapshot();
        Map<Term, Long> termTimestamps = new LinkedHashMap<>();
        if (snapshot.catalog != null) {
            for (Term term : matchingTerms(snapshot.catalog, start, end)) {
                termTimestamps.put(term, completeTermTimestamp(snapshot, term));
            }
        }
        long oldest = Long.MAX_VALUE;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Week week = snapshot.weeks.get(monday(date));
            long covered = week == null ? 0L : week.timestamp;
            if (covered == 0L && snapshot.catalog != null) {
                // A successful complete participant catalog also confirms dates with no relevant groups.
                covered = snapshot.catalog.timestamp;
                for (Map.Entry<Term, Long> entry : termTimestamps.entrySet()) {
                    if (entry.getKey().contains(date)) covered = Math.min(covered, entry.getValue());
                }
            }
            if (covered == 0L) return 0L;
            oldest = Math.min(oldest, covered);
        }
        return isCurrent() ? oldest : 0L;
    }

    /** Detached JSON copies; no schedule fetches or per-course metadata requests. */
    public List<JSONObject> getCachedGroups(LocalDate start, LocalDate end) {
        validateRange(start, end);
        if (!isCurrent()) return Collections.emptyList();
        Catalog catalog = snapshot().catalog;
        List<JSONObject> result = new ArrayList<>();
        if (catalog != null) {
            for (Term term : matchingTerms(catalog, start, end)) {
                for (Group group : term.groups) {
                    try {
                        result.add(new JSONObject(group.json));
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        return isCurrent() ? Collections.unmodifiableList(result) : Collections.emptyList();
    }

    /** Actual inclusive academic term bounds, or null when the cached catalog has no matching term. */
    public LocalDate[] getCachedTermRange(LocalDate anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (!isCurrent()) return null;
        Catalog catalog = snapshot().catalog;
        Term selected = null;
        if (catalog != null) {
            for (Term term : catalog.terms) {
                if (term.contains(anchor) && (selected == null || term.start.isAfter(selected.start)
                        || (term.start.equals(selected.start) && term.finish.isBefore(selected.finish)))) {
                    selected = term;
                }
            }
        }
        return selected == null || !isCurrent() ? null : new LocalDate[]{selected.start, selected.finish};
    }

    private void ensureWeek(LocalDate week, boolean force, long baseline, PlanDebug debug)
            throws RequestFailure, JSONException {
        acquireIo();
        try {
            checkCurrent();
            State snapshot = snapshot();
            Week existing = snapshot.weeks.get(week);
            long now = System.currentTimeMillis();
            if (existing != null && existing.version > baseline) return;
            if (backedOff(snapshot, "student", now) || backedOff(snapshot, "network", now)) {
                setError("retry_later");
                return;
            }
            long ttl = weekTtl(week, existing);
            boolean invalidated = existing != null && failedAfter(snapshot, weekKey(week), existing.timestamp);
            if (!mayFetch(existing == null ? 0L : existing.timestamp, ttl, force,
                    "week:" + week, invalidated)) return;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("start", week.toString());
            params.put("days", "7");
            params.put("fields", ACTIVITY_FIELDS);
            try {
                JSONArray activities = requestArray(STUDENT, params, debug);
                publishWeek(week, canonical(activities), "usos", 0L);
            } catch (RequestFailure failure) {
                recordFailure("student", weekKey(week), failure);
                if (failure.stop) throw failure;
                if (widgetRequest.get() == null
                        && failure.httpCode != 401 && failure.httpCode != 403 && failure.httpCode != 429
                        && !studentNumber.isEmpty()
                        && (existing == null || !"usos".equals(existing.source))
                        && getRangeTimestamp(week, week.plusDays(6)) == 0L
                        && (existing == null || existing.legacyRetryAt <= now)
                        && !backedOff(snapshot(), "legacy", now)) {
                    try {
                        JSONArray legacy = requestLegacy(week, debug);
                        publishWeek(week, canonical(legacy), "legacy", System.currentTimeMillis() + LEGACY_RETRY);
                        return;
                    } catch (RequestFailure legacyFailure) {
                        recordFailure("legacy", null, legacyFailure);
                        if (legacyFailure.stop) throw legacyFailure;
                    } catch (JSONException ignored) {
                        recordFailure("legacy", null, new RequestFailure("invalid_response", 0, false));
                    }
                }
                throw failure;
            } catch (JSONException ignored) {
                recordFailure("student", weekKey(week), new RequestFailure("invalid_response", 0, false));
                throw new JSONException("invalid_response");
            }
        } finally {
            IO_LOCK.unlock();
        }
    }

    private void ensureCatalog(boolean force, long baseline, PlanDebug debug)
            throws RequestFailure, JSONException {
        acquireIo();
        try {
            checkCurrent();
            State snapshot = snapshot();
            Catalog existing = snapshot.catalog;
            if (existing != null && existing.version > baseline) return;
            long now = System.currentTimeMillis();
            if (backedOff(snapshot, "catalog", now) || backedOff(snapshot, "network", now)) {
                setError("retry_later");
                return;
            }
            if (!mayFetch(existing == null ? 0L : existing.timestamp,
                    CATALOG_TTL, force, "catalog", false)) return;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("fields", GROUP_FIELDS);
            params.put("active_terms", "false");
            try {
                paceRequest();
                JSONObject response;
                try {
                    response = UsosApi.get(PARTICIPANT, accessToken, accessSecret, params);
                } catch (IOException failure) {
                    throw transportFailure(failure);
                }
                checkCurrent();
                String json = canonical(response);
                Catalog parsed = parseCatalog(json, System.currentTimeMillis(), 0L);
                recordDebug(debug, PARTICIPANT, parsed.groupCount());
                synchronized (stateLock) {
                    checkCurrent();
                    long version = state.version + 1L;
                    Catalog catalog = new Catalog(json, parsed.timestamp, version, parsed.terms);
                    boolean changed = state.catalog == null || !state.catalog.json.equals(json);
                    Map<String, Retry> retries = new HashMap<>(state.retries);
                    retries.remove("catalog");
                    retries.remove("network");
                    state = new State(state.weeks, catalog, state.groups, retries, version,
                            state.revision + (changed ? 1L : 0L), state.manualRefreshAt);
                    dispatchLocked();
                }
                persistSnapshot();
            } catch (RequestFailure failure) {
                recordFailure("catalog", null, failure);
                throw failure;
            } catch (JSONException ignored) {
                recordFailure("catalog", null, new RequestFailure("invalid_response", 0, false));
                throw new JSONException("invalid_response");
            }
        } finally {
            IO_LOCK.unlock();
        }
    }

    private void ensureGroup(Term term, Group group, boolean force, long baseline, PlanDebug debug)
            throws RequestFailure, JSONException {
        acquireIo();
        try {
            checkCurrent();
            State snapshot = snapshot();
            Activities existing = snapshot.groups.get(group.key);
            String retryKey = "group:" + group.key;
            long now = System.currentTimeMillis();
            if (existing != null && existing.version > baseline) return;
            if (backedOff(snapshot, retryKey, now) || backedOff(snapshot, "network", now)) {
                setError("retry_later");
                return;
            }
            long ttl = term.finish.isBefore(LocalDate.now()) ? Long.MAX_VALUE : GROUP_TTL;
            if (!mayFetch(existing == null ? 0L : existing.timestamp, ttl, force, retryKey, false)) return;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("unit_id", group.unitId);
            params.put("group_number", group.number);
            params.put("fields", ACTIVITY_FIELDS);
            try {
                String json = canonical(requestArray(CLASSGROUP, params, debug));
                synchronized (stateLock) {
                    checkCurrent();
                    long version = state.version + 1L;
                    Map<String, Activities> groups = new HashMap<>(state.groups);
                    Activities previous = groups.put(group.key,
                            new Activities(json, System.currentTimeMillis(), version));
                    boolean changed = previous == null || !previous.json.equals(json);
                    Map<String, Retry> retries = new HashMap<>(state.retries);
                    retries.remove(retryKey);
                    retries.remove("network");
                    state = new State(state.weeks, state.catalog, groups, retries, version,
                            state.revision + (changed ? 1L : 0L), state.manualRefreshAt);
                    dispatchLocked();
                }
                // Persist every complete raw response, including [], before moving to another group.
                persistSnapshot();
            } catch (RequestFailure failure) {
                recordFailure(retryKey, null, failure);
                throw failure;
            } catch (JSONException ignored) {
                recordFailure(retryKey, null, new RequestFailure("invalid_response", 0, false));
                throw new JSONException("invalid_response");
            }
        } finally {
            IO_LOCK.unlock();
        }
    }

    private boolean mayFetch(long timestamp, long ttl, boolean force, String scope, boolean invalidated)
            throws RequestFailure {
        checkCurrent();
        if (!NetworkStatusHelper.isNetworkAvailable(context)) return false;
        if (force || timestamp == 0L) return true;
        long now = System.currentTimeMillis();
        if (!invalidated && fresh(timestamp, ttl, now)) return false;
        // SCREEN_AUTO still owns calendar/window authorization, but its six-hour TTL must not
        // hide a failed verification or a legacy record's deliberately shorter retry interval.
        long policyTimestamp = invalidated || ttl < WEEK_TTL ? Math.min(timestamp, now - WEEK_TTL - 1L) : timestamp;
        NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(context, NetworkRefreshPolicy.Module.PLAN,
                NetworkRefreshPolicy.Mode.SCREEN_AUTO, owner + ":" + scope, policyTimestamp);
        if (decision.allowNetwork) return true;
        // The legacy academic-calendar service may be unavailable. Cached USOS term bounds
        // still authorize screen updates during a known term, without fetching a calendar.
        Catalog catalog = snapshot().catalog;
        boolean cachedTermAllowed = "outside_plan_window".equals(decision.reason)
                && !NetworkRefreshPolicy.hasCachedAcademicCalendar(context) && catalog != null
                && !upcomingTerms(catalog, LocalDate.now()).isEmpty();
        if (cachedTermAllowed && BuildConfig.DEBUG) {
            Log.d("ZUTnikTimetablePolicy", "ALLOW module=PLAN mode=SCREEN_AUTO reason=cached_usos_term cacheAgeMs="
                    + Math.max(0L, now - timestamp));
        }
        return cachedTermAllowed;
    }

    private JSONArray requestArray(String endpoint, Map<String, String> params, PlanDebug debug)
            throws RequestFailure, JSONException {
        paceRequest();
        JSONArray response;
        try {
            response = UsosApi.getArray(endpoint, accessToken, accessSecret, params, widgetRequest.get());
        } catch (IOException failure) {
            throw transportFailure(failure);
        }
        checkCurrent();
        recordDebug(debug, endpoint, response.length());
        return response;
    }

    private JSONArray requestLegacy(LocalDate week, PlanDebug debug) throws RequestFailure, JSONException {
        paceRequest();
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse("https://plan.zut.edu.pl/schedule_student.php"))
                .newBuilder().addQueryParameter("number", studentNumber)
                .addQueryParameter("start", week.toString())
                .addQueryParameter("end", week.plusDays(7).toString()).build();
        Request request = new Request.Builder().url(url)
                .header("User-Agent", ZutnikNetwork.getBrowserUserAgent()).get().build();
        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RequestFailure("legacy_unavailable", response.code(), response.code() == 401);
            }
            if (response.body() == null) throw new RequestFailure("legacy_unavailable", 0, false);
            JSONArray events = new JSONArray(response.body().string());
            checkCurrent();
            recordDebug(debug, "https://plan.zut.edu.pl/schedule_student.php", events.length());
            return events;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException ignored) {
            checkCurrent();
            throw new RequestFailure("legacy_unavailable", 0, false);
        }
    }

    private void publishWeek(LocalDate week, String json, String source, long legacyRetryAt)
            throws RequestFailure {
        synchronized (stateLock) {
            checkCurrent();
            long version = state.version + 1L;
            Map<LocalDate, Week> weeks = new HashMap<>(state.weeks);
            Week previous = weeks.put(week,
                    new Week(json, System.currentTimeMillis(), version, source, legacyRetryAt));
            boolean changed = previous == null || !previous.json.equals(json) || !previous.source.equals(source);
            Map<String, Retry> retries = new HashMap<>(state.retries);
            if ("usos".equals(source)) {
                retries.remove("student");
                retries.remove(weekKey(week));
                retries.remove("network");
            } else {
                fallbackUsed = true;
                retries.remove("legacy");
            }
            state = new State(weeks, state.catalog, state.groups, retries, version,
                    state.revision + (changed ? 1L : 0L), state.manualRefreshAt);
            dispatchLocked();
        }
        persistSnapshot();
    }

    private void recordFailure(String key, String additionalKey, RequestFailure failure) {
        if (!isCurrent() || "cancelled".equals(failure.reason) || "offline".equals(failure.reason)) return;
        synchronized (stateLock) {
            if (!isCurrent()) return;
            long now = System.currentTimeMillis();
            Map<String, Retry> retries = new HashMap<>(state.retries);
            Retry retry = nextRetry(retries.get(key), failure, now);
            retries.put(key, retry);
            if (additionalKey != null) retries.put(additionalKey, retry);
            if (failure.httpCode == 429 || failure.httpCode >= 500
                    || (failure.httpCode == 0 && ("service_unavailable".equals(failure.reason)
                    || "legacy_unavailable".equals(failure.reason)))) {
                retries.put("network", nextRetry(retries.get("network"), failure, now));
            }
            if (failure.httpCode == 401) authFailed = true;
            state = new State(state.weeks, state.catalog, state.groups, retries,
                    state.version + 1L, state.revision, state.manualRefreshAt);
            error = failure.reason;
            dispatchLocked();
        }
        persistSnapshot();
    }

    private static Retry nextRetry(Retry previous, RequestFailure failure, long now) {
        int failures = previous == null ? 1 : Math.min(32, previous.failures + 1);
        long delay = Math.max(retryDelayMillis(failures), failure.retryAfterMs);
        long deadline = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
        if (previous != null) deadline = Math.max(deadline, previous.nextAllowed);
        return new Retry(now, deadline, failures);
    }

    static long retryDelayMillis(int failures) {
        return Math.min(MAX_FAILURE_BACKOFF, FAILURE_BACKOFF << Math.min(10, Math.max(0, failures - 1)));
    }

    private Map<LocalDate, List<PlanEventRaw>> cachedRange(State snapshot, LocalDate start, LocalDate end)
            throws JSONException {
        Map<String, Group> metadata = new HashMap<>();
        if (snapshot.catalog != null) {
            for (Term term : snapshot.catalog.terms) {
                for (Group group : term.groups) metadata.put(group.key, group);
            }
        }
        Map<String, PlanEventRaw> unique = new LinkedHashMap<>();
        if (snapshot.catalog != null) {
            for (Term term : matchingTerms(snapshot.catalog, start, end)) {
                for (Group group : term.groups) {
                    Activities activities = snapshot.groups.get(group.key);
                    if (activities == null) continue;
                    for (PlanEventRaw event : UsosTimetableMapper.parseActivities(
                            new JSONArray(activities.json), new JSONObject(group.json))) {
                        LocalDate date = eventDate(event);
                        if (date == null || snapshot.weeks.containsKey(monday(date))) continue;
                        addEvent(unique, event, start, end);
                    }
                }
            }
        }
        boolean legacyUsed = false;
        for (LocalDate week = monday(start); !week.isAfter(end); week = week.plusWeeks(1)) {
            Week cached = snapshot.weeks.get(week);
            if (cached == null) continue;
            List<PlanEventRaw> events;
            if ("legacy".equals(cached.source)) {
                events = parseLegacy(new JSONArray(cached.json));
                legacyUsed = true;
            } else {
                events = parseStudent(new JSONArray(cached.json), metadata);
            }
            for (PlanEventRaw event : events) {
                LocalDate date = eventDate(event);
                if (date != null && monday(date).equals(week)) addEvent(unique, event, start, end);
            }
        }
        TreeMap<LocalDate, List<PlanEventRaw>> byDate = new TreeMap<>();
        for (PlanEventRaw event : unique.values()) {
            byDate.computeIfAbsent(eventDate(event), ignored -> new ArrayList<>()).add(event);
        }
        Map<LocalDate, List<PlanEventRaw>> result = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<PlanEventRaw>> entry : byDate.entrySet()) {
            entry.getValue().sort(Comparator.comparing((PlanEventRaw event) -> text(event.start))
                    .thenComparing(event -> text(event.end)).thenComparing(event -> text(event.sourceId)));
            result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        if (legacyUsed) {
            synchronized (stateLock) {
                if (isCurrent() && !fallbackUsed) {
                    fallbackUsed = true;
                    dispatchLocked();
                }
            }
        }
        return isCurrent() ? Collections.unmodifiableMap(result) : Collections.emptyMap();
    }

    private static List<PlanEventRaw> parseStudent(JSONArray activities, Map<String, Group> metadata)
            throws JSONException {
        // Bucketing permits catalog enrichment without bypassing the mapper's confirmation dedupe.
        Map<String, JSONArray> buckets = new LinkedHashMap<>();
        for (int i = 0; i < activities.length(); i++) {
            JSONObject activity = activities.optJSONObject(i);
            if (activity == null) continue;
            String key = identity(scalar(activity, "unit_id"), scalar(activity, "group_number"));
            buckets.computeIfAbsent(key, ignored -> new JSONArray()).put(activity);
        }
        List<PlanEventRaw> events = new ArrayList<>();
        for (Map.Entry<String, JSONArray> entry : buckets.entrySet()) {
            Group group = metadata.get(entry.getKey());
            events.addAll(UsosTimetableMapper.parseActivities(entry.getValue(),
                    group == null ? null : new JSONObject(group.json)));
        }
        return events;
    }

    private static void addEvent(Map<String, PlanEventRaw> events, PlanEventRaw event,
                                 LocalDate start, LocalDate end) {
        LocalDate date = eventDate(event);
        if (date == null || date.isBefore(start) || date.isAfter(end)) return;
        String key = text(event.sourceId);
        if (key.isEmpty()) key = identity(event.sourceType, event.start, event.end, event.title);
        PlanEventRaw previous = events.get(key);
        if (previous == null || ("classgroup2".equals(event.sourceType)
                && !"classgroup2".equals(previous.sourceType))) events.put(key, event);
    }

    private static List<PlanEventRaw> parseLegacy(JSONArray activities) {
        List<PlanEventRaw> result = new ArrayList<>();
        for (int i = 0; i < activities.length(); i++) {
            JSONObject activity = activities.optJSONObject(i);
            if (activity == null) continue;
            LocalDateTime start = legacyTime(scalar(activity, "start"));
            LocalDateTime end = legacyTime(scalar(activity, "end"));
            if (start == null || end == null || !end.isAfter(start)) continue;
            PlanEventRaw event = new PlanEventRaw();
            event.title = scalar(activity, "title");
            event.description = scalar(activity, "description");
            event.start = start.format(LOCAL_TIME);
            event.end = end.format(LOCAL_TIME);
            event.workerTitle = scalar(activity, "worker_title");
            event.worker = scalar(activity, "worker");
            event.lessonForm = scalar(activity, "lesson_form");
            event.lessonFormShort = scalar(activity, "lesson_form_short");
            event.groupName = scalar(activity, "group_name");
            event.tokName = scalar(activity, "tok_name");
            event.room = scalar(activity, "room");
            event.lessonStatus = scalar(activity, "lesson_status");
            event.lessonStatusShort = scalar(activity, "lesson_status_short");
            event.subject = scalar(activity, "subject");
            event.hours = scalar(activity, "hours");
            event.color = scalar(activity, "color");
            event.borderColor = scalar(activity, "borderColor");
            event.sourceType = "legacy";
            event.sourceUnitId = "";
            event.sourceGroupNumber = "";
            event.sourceId = "legacy:" + identity(event.start, event.end, event.title, event.subject,
                    event.groupName, event.room, event.lessonForm, event.lessonStatus);
            result.add(event);
        }
        return result;
    }

    private static LocalDateTime legacyTime(String value) {
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value.replace(' ', 'T'));
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static LocalDate eventDate(PlanEventRaw event) {
        String start = text(event.start);
        if (start.length() < 10) return null;
        try {
            return LocalDate.parse(start.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Catalog parseCatalog(String json, long timestamp, long version) throws JSONException {
        JSONObject response = new JSONObject(json);
        JSONObject byTerm = response.getJSONObject("groups");
        JSONArray rawTerms = response.getJSONArray("terms");
        List<Term> terms = new ArrayList<>();
        Map<String, Boolean> knownTerms = new HashMap<>();
        for (int i = 0; i < rawTerms.length(); i++) {
            JSONObject rawTerm = rawTerms.getJSONObject(i);
            String id = scalar(rawTerm, "id");
            LocalDate start;
            LocalDate finish;
            try {
                start = LocalDate.parse(scalar(rawTerm, "start_date"));
                finish = LocalDate.parse(scalar(rawTerm, "finish_date"));
            } catch (DateTimeParseException ignored) {
                throw new JSONException("invalid_catalog");
            }
            if (id.isEmpty() || finish.isBefore(start) || knownTerms.put(id, true) != null) {
                throw new JSONException("invalid_catalog");
            }
            JSONArray rawGroups = byTerm.optJSONArray(id);
            if (byTerm.has(id) && rawGroups == null) throw new JSONException("invalid_catalog");
            Map<String, Group> groups = new LinkedHashMap<>();
            if (rawGroups != null) {
                for (int j = 0; j < rawGroups.length(); j++) {
                    JSONObject rawGroup = rawGroups.getJSONObject(j);
                    String unit = scalar(rawGroup, "course_unit_id");
                    String number = scalar(rawGroup, "group_number");
                    if (!safeIdentifier(unit) || !safeIdentifier(number)
                            || (!scalar(rawGroup, "term_id").isEmpty()
                            && !id.equals(scalar(rawGroup, "term_id")))) throw new JSONException("invalid_catalog");
                    JSONObject copy = new JSONObject(rawGroup.toString());
                    copy.put("term_id", id);
                    Group group = new Group(unit, number, canonical(copy));
                    groups.put(group.key, group);
                }
            }
            terms.add(new Term(id, start, finish, new ArrayList<>(groups.values())));
        }
        Iterator<String> keys = byTerm.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!knownTerms.containsKey(key)) throw new JSONException("invalid_catalog");
        }
        terms.sort(Comparator.comparing((Term term) -> term.start).thenComparing(term -> term.id));
        return new Catalog(json, timestamp, version, terms);
    }

    private static List<Term> matchingTerms(Catalog catalog, LocalDate start, LocalDate end) {
        List<Term> result = new ArrayList<>();
        for (Term term : catalog.terms) {
            if (!term.start.isAfter(end) && !term.finish.isBefore(start)) result.add(term);
        }
        return result;
    }

    private static List<Term> upcomingTerms(Catalog catalog, LocalDate anchor) {
        return matchingTerms(catalog, anchor, anchor.plusDays(14));
    }

    private static long completeTermTimestamp(State snapshot, Term term) {
        long oldest = snapshot.catalog.timestamp;
        for (Group group : term.groups) {
            Activities activities = snapshot.groups.get(group.key);
            if (activities == null) return 0L;
            oldest = Math.min(oldest, activities.timestamp);
        }
        return oldest;
    }

    private boolean hasSyncWork(State snapshot, LocalDate anchor) {
        long now = System.currentTimeMillis();
        if (backedOff(snapshot, "network", now)) return false;
        if (!backedOff(snapshot, "student", now)) {
            for (int i = 0; i < 2; i++) {
                Week week = snapshot.weeks.get(monday(anchor).plusWeeks(i));
                long ttl = weekTtl(monday(anchor).plusWeeks(i), week);
                if (week == null || !fresh(week.timestamp, ttl, now)
                        || failedAfter(snapshot, weekKey(monday(anchor).plusWeeks(i)), week.timestamp)) return true;
            }
        }
        if (!backedOff(snapshot, "catalog", now)
                && (snapshot.catalog == null || !fresh(snapshot.catalog.timestamp, CATALOG_TTL, now))) return true;
        if (snapshot.catalog != null) {
            for (Term term : upcomingTerms(snapshot.catalog, anchor)) {
                for (Group group : term.groups) {
                    if (backedOff(snapshot, "group:" + group.key, now)) continue;
                    Activities activities = snapshot.groups.get(group.key);
                    if (activities == null || (!term.finish.isBefore(LocalDate.now())
                            && !fresh(activities.timestamp, GROUP_TTL, now))) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRangeWork(State snapshot, RangeRequest request) {
        long now = System.currentTimeMillis();
        if (backedOff(snapshot, "network", now)) return false;
        if (supportsStudentWeeks(request)) {
            for (LocalDate week = monday(request.start); !week.isAfter(request.end); week = week.plusWeeks(1)) {
                if (weekNeedsWork(snapshot, week, request, now)) return true;
            }
        }
        if (catalogNeedsWork(snapshot, request, now)) return true;
        if (snapshot.catalog != null) {
            for (Term term : matchingTerms(snapshot.catalog, request.start, request.end)) {
                for (Group group : term.groups) {
                    if (groupNeedsWork(snapshot, term, group, request, now)) return true;
                }
            }
        }
        return false;
    }

    private static boolean catalogNeedsWork(State snapshot, RangeRequest request, long now) {
        if (backedOff(snapshot, "catalog", now)) return false;
        Catalog catalog = snapshot.catalog;
        if (catalog != null && catalog.version > request.baseline) return false;
        return catalog == null || request.force || !fresh(catalog.timestamp, CATALOG_TTL, now);
    }

    private static boolean groupNeedsWork(State snapshot, Term term, Group group, RangeRequest request, long now) {
        if (backedOff(snapshot, "group:" + group.key, now)) return false;
        Activities activities = snapshot.groups.get(group.key);
        if (activities != null && activities.version > request.baseline) return false;
        return activities == null || (request.force && !supportsStudentWeeks(request)) || (!term.finish.isBefore(LocalDate.now())
                && !fresh(activities.timestamp, GROUP_TTL, now));
    }

    private static boolean supportsStudentWeeks(RangeRequest request) {
        return ChronoUnit.DAYS.between(request.start, request.end) < 31L;
    }

    private static boolean weekNeedsWork(State snapshot, LocalDate week, RangeRequest request, long now) {
        if (backedOff(snapshot, "student", now)) return false;
        Week activities = snapshot.weeks.get(week);
        if (activities != null && activities.version > request.baseline) return false;
        if (activities == null || request.force || failedAfter(snapshot, weekKey(week), activities.timestamp)) return true;
        long ttl = weekTtl(week, activities);
        return !fresh(activities.timestamp, ttl, now);
    }

    private static long weekTtl(LocalDate week, Week record) {
        if (record != null && "legacy".equals(record.source)) return LEGACY_RETRY;
        return week.plusDays(6).isBefore(LocalDate.now().minusDays(14)) ? Long.MAX_VALUE : WEEK_TTL;
    }

    private static List<LocalDate> requestedWeekWork(State snapshot, RangeRequest request, long now) {
        List<LocalDate> weeks = new ArrayList<>();
        if (supportsStudentWeeks(request)) {
            for (LocalDate week = monday(request.start); !week.isAfter(request.end); week = week.plusWeeks(1)) {
                if (weekNeedsWork(snapshot, week, request, now)) {
                    weeks.add(week);
                    if (weeks.size() == MAX_VISIBLE_WEEK_REQUESTS) break;
                }
            }
        }
        return weeks;
    }

    private void readCache() {
        try {
            byte[] bytes;
            synchronized (FILE_LOCK) {
                if (!cacheFile.getBaseFile().exists()
                        && !new File(cacheFile.getBaseFile().getPath() + ".bak").exists()) return;
                bytes = cacheFile.readFully();
            }
            String encrypted = new String(bytes, StandardCharsets.UTF_8);
            // Never accept an accidentally plaintext cache via decrypt's migration behavior.
            if (!encrypted.startsWith("enc:v1:")) return;
            String plain = SecureLocalData.decrypt(context, encrypted);
            if (plain == null) return;
            JSONObject root = new JSONObject(plain);
            if (root.getInt("version") != 1 || !owner.equals(root.getString("owner"))) return;
            JSONObject weeksJson = root.getJSONObject("weeks");
            Map<LocalDate, Week> weeks = new HashMap<>();
            Iterator<String> weekKeys = weeksJson.keys();
            while (weekKeys.hasNext()) {
                String key = weekKeys.next();
                LocalDate date = LocalDate.parse(key);
                if (!date.equals(monday(date))) throw new JSONException("invalid_cache");
                JSONObject record = weeksJson.getJSONObject(key);
                long timestamp = positiveTimestamp(record);
                String source = record.getString("source");
                if (!"usos".equals(source) && !"legacy".equals(source)) throw new JSONException("invalid_cache");
                weeks.put(date, new Week(canonical(record.getJSONArray("events")), timestamp, 0L,
                        source, record.optLong("legacy_retry_at", 0L)));
            }
            Catalog catalog = null;
            JSONObject catalogJson = root.optJSONObject("catalog");
            if (catalogJson != null) {
                catalog = parseCatalog(canonical(catalogJson.getJSONObject("response")),
                        positiveTimestamp(catalogJson), 0L);
            }
            Map<String, Activities> groups = new HashMap<>();
            JSONObject groupsJson = root.getJSONObject("groups");
            Iterator<String> groupKeys = groupsJson.keys();
            while (groupKeys.hasNext()) {
                String key = groupKeys.next();
                JSONObject record = groupsJson.getJSONObject(key);
                groups.put(key, new Activities(canonical(record.getJSONArray("activities")),
                        positiveTimestamp(record), 0L));
            }
            Map<String, Retry> retries = new HashMap<>();
            JSONObject retriesJson = root.optJSONObject("retries");
            if (retriesJson != null) {
                Iterator<String> retryKeys = retriesJson.keys();
                while (retryKeys.hasNext()) {
                    String key = retryKeys.next();
                    JSONObject retry = retriesJson.getJSONObject(key);
                    retries.put(key, new Retry(retry.getLong("failed_at"), retry.getLong("next_allowed"),
                            Math.max(1, Math.min(32, retry.optInt("failures", 1)))));
                }
            }
            synchronized (stateLock) {
                if (!matchesSession(ZutnikSession.getInstance())) return;
                state = new State(weeks, catalog, groups, retries, 0L, Math.max(0L, root.optLong("revision", 0L)),
                        Math.max(0L, root.optLong("manual_refresh_at", 0L)));
            }
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            // Corrupt, unavailable-key and wrong-owner caches are misses, never successful empty ranges.
        }
    }

    private void persistSnapshot() {
        State snapshot = snapshot();
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("owner", owner);
            root.put("revision", snapshot.revision);
            root.put("manual_refresh_at", snapshot.manualRefreshAt);
            JSONObject weeks = new JSONObject();
            for (Map.Entry<LocalDate, Week> entry : snapshot.weeks.entrySet()) {
                Week week = entry.getValue();
                JSONObject record = new JSONObject();
                record.put("timestamp", week.timestamp);
                record.put("events", new JSONArray(week.json));
                record.put("source", week.source);
                record.put("legacy_retry_at", week.legacyRetryAt);
                weeks.put(entry.getKey().toString(), record);
            }
            root.put("weeks", weeks);
            if (snapshot.catalog != null) {
                JSONObject catalog = new JSONObject();
                catalog.put("timestamp", snapshot.catalog.timestamp);
                catalog.put("response", new JSONObject(snapshot.catalog.json));
                root.put("catalog", catalog);
            }
            JSONObject groups = new JSONObject();
            for (Map.Entry<String, Activities> entry : snapshot.groups.entrySet()) {
                JSONObject record = new JSONObject();
                record.put("timestamp", entry.getValue().timestamp);
                record.put("activities", new JSONArray(entry.getValue().json));
                groups.put(entry.getKey(), record);
            }
            root.put("groups", groups);
            JSONObject retries = new JSONObject();
            for (Map.Entry<String, Retry> entry : snapshot.retries.entrySet()) {
                JSONObject retry = new JSONObject();
                retry.put("failed_at", entry.getValue().failedAt);
                retry.put("next_allowed", entry.getValue().nextAllowed);
                retry.put("failures", entry.getValue().failures);
                retries.put(entry.getKey(), retry);
            }
            root.put("retries", retries);
            String encrypted = SecureLocalData.encrypt(context, root.toString());
            if (encrypted == null) throw new IOException("cache_write_failed");
            byte[] bytes = encrypted.getBytes(StandardCharsets.UTF_8);
            synchronized (FILE_LOCK) {
                if (!isCurrent() || snapshot.version != snapshot().version) return;
                File directory = cacheFile.getBaseFile().getParentFile();
                if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                    throw new IOException("cache_write_failed");
                }
                FileOutputStream stream = null;
                try {
                    stream = cacheFile.startWrite();
                    stream.write(bytes);
                    if (!isCurrent()) {
                        cacheFile.failWrite(stream);
                        stream = null;
                        return;
                    }
                    cacheFile.finishWrite(stream);
                    stream = null;
                } finally {
                    if (stream != null) cacheFile.failWrite(stream);
                }
            }
        } catch (IOException | JSONException ignored) {
            setError("cache_write_failed");
        }
    }

    private static long positiveTimestamp(JSONObject record) throws JSONException {
        long timestamp = record.getLong("timestamp");
        if (timestamp <= 0L || timestamp > System.currentTimeMillis() + FAILURE_BACKOFF) {
            throw new JSONException("invalid_cache");
        }
        return timestamp;
    }

    private State snapshot() {
        synchronized (stateLock) {
            return state;
        }
    }

    private boolean matchesSession(ZutnikSession session) {
        return !retired && capturedSession == session && session.isUsosLogin()
                && baseUrl.equals(text(BuildConfig.USOS_BASE_URL))
                && userId.equals(text(session.getUserId()))
                && studyId.equals(text(session.getActiveStudyId()))
                && accessToken.equals(text(session.getUsosAccessToken()))
                && accessSecret.equals(text(session.getUsosAccessTokenSecret()));
    }

    private boolean isCurrent() {
        return instance == this && matchesSession(ZutnikSession.getInstance());
    }

    private void checkCurrent() throws RequestFailure {
        UsosApi.RequestControl control = widgetRequest.get();
        if (control != null) {
            try {
                control.remainingMs();
            } catch (java.io.InterruptedIOException stopped) {
                throw new RequestFailure("cancelled", 0, true);
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new RequestFailure("cancelled", 0, true);
        if (!isCurrent()) throw new RequestFailure("session_changed", 0, true);
        if (authFailed || userId.isEmpty() || accessToken.isEmpty() || accessSecret.isEmpty()) {
            throw new RequestFailure("authentication_required", 401, true);
        }
    }

    private void checkNetwork() throws RequestFailure {
        checkCurrent();
        if (!NetworkStatusHelper.isNetworkAvailable(context)) throw new RequestFailure("offline", 0, true);
    }

    private void checkContinuation() throws RequestFailure {
        checkNetwork();
        if (backedOff(snapshot(), "network", System.currentTimeMillis())) {
            throw new RequestFailure("retry_later", 0, true);
        }
    }

    private void acquireIo() throws RequestFailure {
        try {
            UsosApi.RequestControl control = widgetRequest.get();
            if (control == null) IO_LOCK.lockInterruptibly();
            else if (!IO_LOCK.tryLock(control.remainingMs(), TimeUnit.MILLISECONDS)) {
                throw new RequestFailure("cancelled", 0, true);
            }
        } catch (java.io.InterruptedIOException stopped) {
            throw new RequestFailure("cancelled", 0, true);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new RequestFailure("cancelled", 0, true);
        }
    }

    private void paceRequest() throws RequestFailure {
        checkNetwork();
        long now = SystemClock.elapsedRealtime();
        long wait = lastRequestStartedAt == 0L ? 0L : REQUEST_SPACING_MS - (now - lastRequestStartedAt);
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw new RequestFailure("cancelled", 0, true);
            }
        }
        checkNetwork();
        lastRequestStartedAt = SystemClock.elapsedRealtime();
    }

    private RequestFailure transportFailure(IOException failure) throws RequestFailure {
        checkCurrent();
        String message = failure.getMessage();
        Matcher match = HTTP_ERROR.matcher(message == null ? "" : message);
        UsosApi.HttpException http = failure instanceof UsosApi.HttpException ? (UsosApi.HttpException) failure : null;
        int code = http != null ? http.statusCode : match.find() ? Integer.parseInt(match.group(1)) : 0;
        String reason = code == 401 ? "authentication_required" : code == 403 ? "permission_denied"
                : code == 429 ? "rate_limited" : "service_unavailable";
        return new RequestFailure(reason, code, code == 401 || code == 429, http == null ? 0L : http.retryAfterMs);
    }

    private void retire() {
        retired = true;
        listeners.clear();
        synchronized (stateLock) {
            running = false;
        }
    }

    private void completeStep() {
        synchronized (stateLock) {
            if (!isCurrent()) return;
            completed++;
            dispatchLocked();
        }
    }

    private void setError(String reason) {
        synchronized (stateLock) {
            if (!isCurrent()) return;
            error = reason;
            dispatchLocked();
        }
    }

    private SyncState syncStateLocked() {
        return new SyncState(running, completed, total, state.revision, error, fallbackUsed);
    }

    private void dispatchLocked() {
        if (listeners.isEmpty()) return;
        SyncState notification = syncStateLocked();
        Listener[] targets = listeners.toArray(new Listener[0]);
        mainHandler.post(() -> {
            for (Listener listener : targets) deliver(listener, notification);
        });
    }

    private void deliver(Listener listener, SyncState notification) {
        if (isCurrent() && listeners.contains(listener)) listener.onSyncStateChanged(notification);
    }

    private void refreshWidgetsIfChanged() {
        synchronized (stateLock) {
            if (!isCurrent() || widgetRevision == state.revision) return;
            widgetRevision = state.revision;
        }
        mainHandler.post(() -> {
            if (!isCurrent()) return;
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, PlanDayWidgetProvider.class));
            if (ids.length == 0) return;
            // ACTION_REFRESH is the provider's public cache-only update entry point.
            context.sendBroadcast(new Intent(context, PlanDayWidgetProvider.class)
                    .setAction(PlanDayWidgetProvider.ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        });
    }

    private void recordDebug(PlanDebug debug, String endpoint, int count) {
        if (debug == null || !isCurrent()) return;
        PlanDebug.RequestDebug request = new PlanDebug.RequestDebug();
        request.url = endpoint.startsWith("https://") ? endpoint : baseUrl + endpoint;
        request.httpCode = 200;
        request.jsonOk = true;
        request.jsonCount = count;
        synchronized (debug) {
            debug.requests.add(request);
        }
    }

    private static boolean backedOff(State state, String key, long now) {
        Retry retry = state.retries.get(key);
        return retry != null && retry.nextAllowed > now;
    }

    private static boolean failedAfter(State state, String key, long timestamp) {
        Retry retry = state.retries.get(key);
        return retry != null && retry.failedAt >= timestamp;
    }

    private static boolean fresh(long timestamp, long ttl, long now) {
        return timestamp > 0L && timestamp <= now && now - timestamp < ttl;
    }

    private static LocalDate monday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) throw new IllegalArgumentException("end before start");
    }

    private static String weekKey(LocalDate week) {
        return "week:" + week;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String scalar(JSONObject object, String field) {
        Object value = object.opt(field);
        return value instanceof String || value instanceof Number ? text(value.toString()) : "";
    }

    private static boolean safeIdentifier(String value) {
        return !value.isEmpty() && value.matches("[A-Za-z0-9._-]+");
    }

    private static String identity(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = text(part);
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }

    private static String ownerHash(String owner) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Stable object ordering avoids revisions for response whitespace/key-order changes. */
    private static String canonical(Object json) throws JSONException {
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (String key : keys) {
                if (output.length() > 1) output.append(',');
                output.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return output.append('}').toString();
        }
        if (json instanceof JSONArray) {
            JSONArray array = (JSONArray) json;
            StringBuilder output = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) output.append(',');
                output.append(canonical(array.get(i)));
            }
            return output.append(']').toString();
        }
        if (json == null || json == JSONObject.NULL) return "null";
        if (json instanceof String) return JSONObject.quote((String) json);
        if (json instanceof Number) return JSONObject.numberToString((Number) json);
        if (json instanceof Boolean) return json.toString();
        throw new JSONException("invalid_response");
    }

    private static final class RequestFailure extends IOException {
        final String reason;
        final int httpCode;
        final boolean stop;
        final long retryAfterMs;

        RequestFailure(String reason, int httpCode, boolean stop) {
            this(reason, httpCode, stop, 0L);
        }

        RequestFailure(String reason, int httpCode, boolean stop, long retryAfterMs) {
            super(reason);
            this.reason = reason;
            this.httpCode = httpCode;
            this.stop = stop;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static class Activities {
        final String json;
        final long timestamp;
        final long version;

        Activities(String json, long timestamp, long version) {
            this.json = json;
            this.timestamp = timestamp;
            this.version = version;
        }
    }

    private static final class Week extends Activities {
        final String source;
        final long legacyRetryAt;

        Week(String json, long timestamp, long version, String source, long legacyRetryAt) {
            super(json, timestamp, version);
            this.source = source;
            this.legacyRetryAt = legacyRetryAt;
        }
    }

    private static final class Group {
        final String unitId;
        final String number;
        final String key;
        final String json;

        Group(String unitId, String number, String json) {
            this.unitId = unitId;
            this.number = number;
            this.key = identity(unitId, number);
            this.json = json;
        }
    }

    private static final class Term {
        final String id;
        final LocalDate start;
        final LocalDate finish;
        final List<Group> groups;

        Term(String id, LocalDate start, LocalDate finish, List<Group> groups) {
            this.id = id;
            this.start = start;
            this.finish = finish;
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        }

        boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(finish);
        }
    }

    private static final class Catalog extends Activities {
        final List<Term> terms;

        Catalog(String json, long timestamp, long version, List<Term> terms) {
            super(json, timestamp, version);
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
        }

        int groupCount() {
            int count = 0;
            for (Term term : terms) count += term.groups.size();
            return count;
        }
    }

    private static final class Retry {
        final long failedAt;
        final long nextAllowed;
        final int failures;

        Retry(long failedAt, long nextAllowed, int failures) {
            this.failedAt = failedAt;
            this.nextAllowed = nextAllowed;
            this.failures = failures;
        }
    }

    private static final class RangeRequest {
        final LocalDate start;
        final LocalDate end;
        final boolean force;
        final long baseline;

        RangeRequest(LocalDate start, LocalDate end, boolean force, long baseline) {
            this.start = start;
            this.end = end;
            this.force = force;
            this.baseline = baseline;
        }
    }

    private static final class State {
        final Map<LocalDate, Week> weeks;
        final Catalog catalog;
        final Map<String, Activities> groups;
        final Map<String, Retry> retries;
        final long version;
        final long revision;
        final long manualRefreshAt;

        State(Map<LocalDate, Week> weeks, Catalog catalog, Map<String, Activities> groups,
              Map<String, Retry> retries, long version, long revision, long manualRefreshAt) {
            this.weeks = Collections.unmodifiableMap(new HashMap<>(weeks));
            this.catalog = catalog;
            this.groups = Collections.unmodifiableMap(new HashMap<>(groups));
            this.retries = Collections.unmodifiableMap(new HashMap<>(retries));
            this.version = version;
            this.revision = revision;
            this.manualRefreshAt = manualRefreshAt;
        }

        static State empty() {
            return new State(Collections.emptyMap(), null, Collections.emptyMap(),
                    Collections.emptyMap(), 0L, 0L, 0L);
        }
    }
}
