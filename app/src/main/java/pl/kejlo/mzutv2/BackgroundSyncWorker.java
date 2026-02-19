package pl.kejlo.mzutv2;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BackgroundSyncWorker extends Worker {

    private static final String TAG = "mZUTv2-BgSync";
    public static final String INPUT_BOOTSTRAP_PREFETCH = "input_bootstrap_prefetch";

    private static final String PREFS_BG = "mzut_background_sync_cache";
    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_GRADES_BASELINE_READY = "grades_baseline_ready_v1";
    private static final String KEY_GRADES_BASELINE_JSON = "grades_baseline_json_v1";
    private static final String KEY_PLAN_BASELINE_READY = "plan_baseline_ready_v1";
    private static final String KEY_PLAN_BASELINE_JSON = "plan_baseline_json_v1";
    private static final String KEY_FILTER_CACHE_FORCE_REFRESH = "plan_filters_force_refresh_v1";
    private static final String FILTER_CACHE_JSON_PREFIX = "plan_filters_cache_json_";
    private static final String FILTER_CACHE_TS_PREFIX = "plan_filters_cache_ts_";
    private static final String KEY_LAST_GRADES_ALERT_HASH = "grades_last_alert_hash_v1";
    private static final String KEY_LAST_GRADES_ALERT_TS = "grades_last_alert_ts_v1";
    private static final String KEY_LAST_PLAN_ALERT_HASH = "plan_last_alert_hash_v1";
    private static final String KEY_LAST_PLAN_ALERT_TS = "plan_last_alert_ts_v1";

    private static final int NOTIF_ID_GRADES = 9101;
    private static final int NOTIF_ID_PLAN = 9102;
    private static final int PLAN_REFRESH_THRESHOLD = 10;
    private static final long ALERT_DEDUP_WINDOW_MS = 24L * 60L * 60L * 1000L;

    private static final String DATE_SHORT_PATTERN = "dd.MM";

    private static DateTimeFormatter dateShortFormatter() {
        return DateTimeFormatter.ofPattern(DATE_SHORT_PATTERN, Locale.getDefault());
    }

    public BackgroundSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        MzutSession.initializeFromPreferences(context);

        try {
            if (!canRunChecks(context)) {
                return Result.success();
            }

            if (!isWithinAcademicNotificationWindow(context)) {
                Log.d(TAG, "Skipping checks outside didactic/session period.");
                return Result.success();
            }

            if (getInputData().getBoolean(INPUT_BOOTSTRAP_PREFETCH, false)) {
                runBootstrapPrefetch(context);
            }

            if (NotificationSyncManager.isGradesEnabled(context)) {
                checkGrades(context);
            }

            if (NotificationSyncManager.isPlanEnabled(context) && NotificationSyncManager.isAnyPlanCategoryEnabled(context)) {
                checkPlanChanges(context);
            }
            return Result.success();
        } catch (SessionExpiredException expired) {
            Log.w(TAG, "Session expired during background check: " + expired.getMessage());
            return Result.success();
        } catch (IOException io) {
            Log.w(TAG, "Network/API error during background check", io);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Background sync failed", e);
            return Result.success();
        }
    }

    private boolean canRunChecks(Context context) {
        MzutSession session = MzutSession.getInstance();
        boolean hasSession = session.getUserId() != null && session.getAuthKey() != null;
        if (!hasSession) {
            NotificationSyncManager.cancelWorker(context);
            return false;
        }
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return false;
        }
        return NotificationSyncManager.isAnyFeatureEnabled(context);
    }

    private void runBootstrapPrefetch(Context context) {
        if (!(NotificationSyncManager.isPlanEnabled(context) && NotificationSyncManager.isAnyPlanCategoryEnabled(context))) {
            return;
        }
        try {
            PlanRepository repo = new PlanRepository(context);
            repo.loadPlan("week", LocalDate.now(), true);
            Log.d(TAG, "Bootstrap prefetch: plan full-refresh completed");
        } catch (Exception e) {
            Log.w(TAG, "Bootstrap prefetch: plan full-refresh failed", e);
        }
    }

    private boolean isWithinAcademicNotificationWindow(Context context) {
        LocalDate today = LocalDate.now();
        List<PlanRepository.SessionPeriod> periods = PlanRepository.getCachedSessionDates(context);
        if (periods.isEmpty()) {
            try {
                periods = new PlanRepository(context).fetchSessionDates();
            } catch (Exception ignored) {
            }
        }
        if (periods.isEmpty()) {
            // If calendar cannot be resolved, keep checks enabled instead of silently disabling.
            return true;
        }
        PlanRepository.SessionPeriod noClasses = PlanRepository.findActivePeriod(periods, today, true);
        return noClasses == null;
    }

    private void checkGrades(Context context) throws IOException, org.json.JSONException {
        GradesRepository repo = new GradesRepository();
        List<Semester> semesters = repo.loadSemesters();
        if (semesters == null) {
            semesters = Collections.emptyList();
        }

        Map<String, String> current = new LinkedHashMap<>();
        int fetchedSemesters = 0;
        for (Semester semester : semesters) {
            if (semester == null || semester.listaSemestrowId == null || semester.listaSemestrowId.trim().isEmpty()) {
                continue;
            }
            fetchedSemesters++;
            List<Grade> grades = repo.loadGradesForSemester(semester.listaSemestrowId);
            if (grades == null) {
                continue;
            }
            for (Grade grade : grades) {
                if (grade == null) {
                    continue;
                }
                String key = buildGradeKey(semester, grade);
                String label = buildGradeLabel(grade);
                current.put(key, label);
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        boolean baselineReady = prefs.getBoolean(KEY_GRADES_BASELINE_READY, false);
        Set<String> previous = readStringSetJson(prefs.getString(KEY_GRADES_BASELINE_JSON, "[]"));

        if (!baselineReady || previous.isEmpty()) {
            if (fetchedSemesters == 0) {
                Log.d(TAG, "Skipping grades baseline init: no semesters loaded yet.");
                return;
            }
            saveGradesBaselineSet(prefs, current.keySet());
            prefs.edit().putBoolean(KEY_GRADES_BASELINE_READY, true).apply();
            return;
        }

        List<String> addedKeys = new ArrayList<>();
        for (String key : current.keySet()) {
            if (!previous.contains(key)) {
                addedKeys.add(key);
            }
        }

        if (!addedKeys.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (String key : addedKeys) {
                String label = current.get(key);
                if (label != null && !label.isEmpty()) {
                    lines.add(label);
                }
            }
            Collections.sort(lines);
            saveGradesBaselineSet(prefs, current.keySet());

            String signature = buildAlertSignatureFromList(addedKeys);
            if (shouldNotifyForSignature(prefs, KEY_LAST_GRADES_ALERT_HASH, KEY_LAST_GRADES_ALERT_TS, signature)) {
                notifyGrades(context, addedKeys.size(), lines);
                rememberAlertSignature(prefs, KEY_LAST_GRADES_ALERT_HASH, KEY_LAST_GRADES_ALERT_TS, signature);
            }
            return;
        }

        saveGradesBaselineSet(prefs, current.keySet());
    }

    private void notifyGrades(Context context, int count, List<String> lines) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        String title = context.getString(R.string.notif_grades_title);
        String summary = context.getString(R.string.notif_grades_summary, count);
        String bigText = buildBigText(lines, 6, summary);

        Intent openIntent = new Intent(context, GradesActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                91010,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSyncManager.CHANNEL_GRADES)
                .setSmallIcon(R.drawable.ic_school)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_GRADES, builder.build());
    }

    private void checkPlanChanges(Context context) throws IOException, org.json.JSONException {
        boolean movedEnabled = NotificationSyncManager.isPlanMovedEnabled(context);
        boolean cancelledEnabled = NotificationSyncManager.isPlanCancelledEnabled(context);
        boolean addedEnabled = NotificationSyncManager.isPlanAddedEnabled(context);
        boolean removedEnabled = NotificationSyncManager.isPlanRemovedEnabled(context);
        if (!movedEnabled && !cancelledEnabled && !addedEnabled && !removedEnabled) {
            return;
        }

        List<PlanSnapshotEvent> current = collectPlanSnapshot(context);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        boolean baselineReady = prefs.getBoolean(KEY_PLAN_BASELINE_READY, false);
        List<PlanSnapshotEvent> previous = readPlanSnapshot(prefs.getString(KEY_PLAN_BASELINE_JSON, "[]"));

        if (!baselineReady || previous.isEmpty()) {
            savePlanBaselineSnapshot(prefs, current);
            prefs.edit().putBoolean(KEY_PLAN_BASELINE_READY, true).apply();
            return;
        }

        PlanDiff diff = diffPlan(previous, current, movedEnabled);
        int totalChanges = diff.moved.size()
                + diff.cancelled.size()
                + diff.removed.size()
                + diff.added.size();

        if (totalChanges > PLAN_REFRESH_THRESHOLD) {
            markFilterCacheRefreshNeeded(context);
            savePlanBaselineSnapshot(prefs, current);
            String signature = "refresh_" + totalChanges + "_" + current.size();
            if (shouldNotifyForSignature(prefs, KEY_LAST_PLAN_ALERT_HASH, KEY_LAST_PLAN_ALERT_TS, signature)) {
                notifyPlanRefreshed(context);
                rememberAlertSignature(prefs, KEY_LAST_PLAN_ALERT_HASH, KEY_LAST_PLAN_ALERT_TS, signature);
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        if (movedEnabled) {
            for (PlanMove move : diff.moved) {
                lines.add(context.getString(
                        R.string.notif_plan_line_moved,
                        move.from.title,
                        formatDate(move.from.date),
                        formatTime(move.from.startMin),
                        formatDate(move.to.date),
                        formatTime(move.to.startMin)));
            }
        }
        if (cancelledEnabled) {
            for (PlanSnapshotEvent ev : diff.cancelled) {
                lines.add(context.getString(
                        R.string.notif_plan_line_cancelled,
                        ev.title,
                        formatDate(ev.date),
                        formatTime(ev.startMin)));
            }
        }
        if (removedEnabled) {
            for (PlanSnapshotEvent ev : diff.removed) {
                lines.add(context.getString(
                        R.string.notif_plan_line_removed,
                        ev.title,
                        formatDate(ev.date),
                        formatTime(ev.startMin)));
            }
        }
        if (addedEnabled) {
            for (PlanSnapshotEvent ev : diff.added) {
                lines.add(context.getString(
                        R.string.notif_plan_line_added,
                        ev.title,
                        formatDate(ev.date),
                        formatTime(ev.startMin)));
            }
        }

        savePlanBaselineSnapshot(prefs, current);
        if (!lines.isEmpty()) {
            String signature = buildAlertSignatureFromList(lines);
            if (shouldNotifyForSignature(prefs, KEY_LAST_PLAN_ALERT_HASH, KEY_LAST_PLAN_ALERT_TS, signature)) {
                notifyPlanChanges(context, lines);
                rememberAlertSignature(prefs, KEY_LAST_PLAN_ALERT_HASH, KEY_LAST_PLAN_ALERT_TS, signature);
            }
        }
    }

    private void notifyPlanChanges(Context context, List<String> lines) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        String title = context.getString(R.string.notif_plan_title);
        String summary = context.getString(R.string.notif_plan_summary, lines.size());
        String bigText = buildBigText(lines, 7, summary);
        PendingIntent pi = buildPlanPendingIntent(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSyncManager.CHANNEL_PLAN)
                .setSmallIcon(R.drawable.ic_calendar)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_PLAN, builder.build());
    }

    private void notifyPlanRefreshed(Context context) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        PendingIntent pi = buildPlanPendingIntent(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSyncManager.CHANNEL_PLAN)
                .setSmallIcon(R.drawable.ic_calendar)
                .setContentTitle(context.getString(R.string.notif_plan_refreshed_title))
                .setContentText(context.getString(R.string.notif_plan_refreshed_text))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.notif_plan_refreshed_text)))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_PLAN, builder.build());
    }

    private PendingIntent buildPlanPendingIntent(Context context) {
        Intent openIntent = new Intent(context, PlanActivity.class);
        openIntent.putExtra("viewMode", "week");
        openIntent.putExtra("currentDate", LocalDate.now().toString());

        return PendingIntent.getActivity(
                context,
                91020,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private List<PlanSnapshotEvent> collectPlanSnapshot(Context context) throws IOException, org.json.JSONException {
        PlanRepository repo = new PlanRepository(context);
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(13);

        PlanRepository.PlanResult firstWeek = repo.loadPlan("week", start);
        PlanRepository.PlanResult secondWeek = repo.loadPlan("week", start.plusDays(7));

        Map<String, PlanSnapshotEvent> unique = new LinkedHashMap<>();
        collectSnapshotFromResult(unique, firstWeek, start, end);
        collectSnapshotFromResult(unique, secondWeek, start, end);

        List<PlanSnapshotEvent> out = new ArrayList<>(unique.values());
        out.sort(Comparator
                .comparing((PlanSnapshotEvent e) -> e.date)
                .thenComparingInt(e -> e.startMin)
                .thenComparing(e -> e.title));
        return out;
    }

    private void collectSnapshotFromResult(
            Map<String, PlanSnapshotEvent> out,
            PlanRepository.PlanResult result,
            LocalDate start,
            LocalDate end) {
        if (result == null || result.dayColumns == null) {
            return;
        }
        for (PlanRepository.DayColumn day : result.dayColumns) {
            if (day == null || day.date == null || day.events == null) {
                continue;
            }
            if (day.date.isBefore(start) || day.date.isAfter(end)) {
                continue;
            }
            for (PlanRepository.PlanEventUi ev : day.events) {
                if (ev == null || ev.isCustomEvent) {
                    continue;
                }
                PlanSnapshotEvent snap = PlanSnapshotEvent.from(day.date, ev);
                out.put(snap.id(), snap);
            }
        }
    }

    private PlanDiff diffPlan(List<PlanSnapshotEvent> previous, List<PlanSnapshotEvent> current, boolean detectMoves) {
        PlanDiff diff = new PlanDiff();

        Map<String, PlanSnapshotEvent> prevById = new HashMap<>();
        for (PlanSnapshotEvent ev : previous) {
            prevById.put(ev.id(), ev);
        }
        Map<String, PlanSnapshotEvent> curById = new HashMap<>();
        for (PlanSnapshotEvent ev : current) {
            curById.put(ev.id(), ev);
        }

        List<PlanSnapshotEvent> removed = new ArrayList<>();
        for (PlanSnapshotEvent ev : previous) {
            if (!curById.containsKey(ev.id())) {
                removed.add(ev);
            }
        }

        List<PlanSnapshotEvent> added = new ArrayList<>();
        for (PlanSnapshotEvent ev : current) {
            if (!prevById.containsKey(ev.id())) {
                added.add(ev);
            }
        }

        if (detectMoves && !removed.isEmpty() && !added.isEmpty()) {
            Set<Integer> usedAdded = new HashSet<>();
            List<PlanSnapshotEvent> remainingRemoved = new ArrayList<>();

            for (PlanSnapshotEvent oldEv : removed) {
                int bestIdx = -1;
                int bestScore = Integer.MAX_VALUE;
                for (int i = 0; i < added.size(); i++) {
                    if (usedAdded.contains(i)) {
                        continue;
                    }
                    PlanSnapshotEvent newEv = added.get(i);
                    if (!oldEv.core.equals(newEv.core)) {
                        continue;
                    }
                    int dayDiff = Math.abs((int) (newEv.date.toEpochDay() - oldEv.date.toEpochDay()));
                    int timeDiff = Math.abs(newEv.startMin - oldEv.startMin);
                    int score = dayDiff * 2000 + timeDiff;
                    if (score < bestScore) {
                        bestScore = score;
                        bestIdx = i;
                    }
                }
                if (bestIdx >= 0) {
                    PlanSnapshotEvent newEv = added.get(bestIdx);
                    usedAdded.add(bestIdx);
                    diff.moved.add(new PlanMove(oldEv, newEv));
                } else {
                    remainingRemoved.add(oldEv);
                }
            }

            List<PlanSnapshotEvent> remainingAdded = new ArrayList<>();
            for (int i = 0; i < added.size(); i++) {
                if (!usedAdded.contains(i)) {
                    remainingAdded.add(added.get(i));
                }
            }
            removed = remainingRemoved;
            added = remainingAdded;
        }

        diff.removed.addAll(removed);
        for (PlanSnapshotEvent ev : added) {
            if (ev.cancelledType) {
                diff.cancelled.add(ev);
            } else {
                diff.added.add(ev);
            }
        }

        Comparator<PlanSnapshotEvent> byDateTime = Comparator
                .comparing((PlanSnapshotEvent e) -> e.date)
                .thenComparingInt(e -> e.startMin)
                .thenComparing(e -> e.title);
        diff.cancelled.sort(byDateTime);
        diff.removed.sort(byDateTime);
        diff.added.sort(byDateTime);
        diff.moved.sort(Comparator
                .comparing((PlanMove m) -> m.to.date)
                .thenComparingInt(m -> m.to.startMin)
                .thenComparing(m -> m.to.title));

        return diff;
    }

    private String buildGradeKey(Semester semester, Grade grade) {
        return normalize(semester.listaSemestrowId)
                + "|" + normalize(grade.subjectName)
                + "|" + normalize(grade.grade)
                + "|" + normalize(grade.date)
                + "|" + normalize(grade.type)
                + "|" + normalize(grade.teacher);
    }

    private String buildGradeLabel(Grade grade) {
        String subject = safe(grade.subjectName);
        String score = safe(grade.grade);
        String date = safe(grade.date);
        StringBuilder sb = new StringBuilder();
        if (!subject.isEmpty()) {
            sb.append(subject);
        } else {
            sb.append("Przedmiot");
        }
        if (!score.isEmpty()) {
            sb.append(": ").append(score);
        }
        if (!date.isEmpty()) {
            sb.append(" (").append(date).append(")");
        }
        return sb.toString();
    }

    private String buildBigText(List<String> lines, int maxLines, String fallback) {
        if (lines == null || lines.isEmpty()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxLines, lines.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("- ").append(lines.get(i));
        }
        if (lines.size() > limit) {
            sb.append("\n...");
        }
        return sb.toString();
    }

    private String formatDate(LocalDate date) {
        return date != null ? date.format(dateShortFormatter()) : "--.--";
    }

    private String formatTime(int minutes) {
        int h = Math.max(0, minutes) / 60;
        int m = Math.max(0, minutes) % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private Set<String> readStringSetJson(String json) {
        Set<String> out = new HashSet<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i, null);
                if (item != null && !item.isEmpty()) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveGradesBaselineSet(SharedPreferences prefs, Set<String> values) {
        JSONArray arr = new JSONArray();
        for (String value : values) {
            arr.put(value);
        }
        prefs.edit().putString(KEY_GRADES_BASELINE_JSON, arr.toString()).apply();
    }

    private List<PlanSnapshotEvent> readPlanSnapshot(String json) {
        List<PlanSnapshotEvent> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                PlanSnapshotEvent ev = PlanSnapshotEvent.fromJson(obj);
                if (ev != null) {
                    out.add(ev);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void savePlanBaselineSnapshot(SharedPreferences prefs, List<PlanSnapshotEvent> events) {
        JSONArray arr = new JSONArray();
        for (PlanSnapshotEvent ev : events) {
            arr.put(ev.toJson());
        }
        prefs.edit().putString(KEY_PLAN_BASELINE_JSON, arr.toString()).apply();
    }

    private String buildAlertSignatureFromList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String value : sorted) {
            if (value == null) {
                continue;
            }
            sb.append(value.trim().toLowerCase(Locale.ROOT)).append('\n');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private boolean shouldNotifyForSignature(SharedPreferences prefs, String hashKey, String tsKey, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        String prev = prefs.getString(hashKey, "");
        if (!signature.equals(prev)) {
            return true;
        }
        long lastTs = prefs.getLong(tsKey, 0L);
        return (System.currentTimeMillis() - lastTs) >= ALERT_DEDUP_WINDOW_MS;
    }

    private void rememberAlertSignature(SharedPreferences prefs, String hashKey, String tsKey, String signature) {
        if (signature == null || signature.isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(hashKey, signature)
                .putLong(tsKey, System.currentTimeMillis())
                .apply();
    }

    private void markFilterCacheRefreshNeeded(Context context) {
        SharedPreferences planPrefs = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
        Map<String, ?> all = planPrefs.getAll();
        SharedPreferences.Editor editor = planPrefs.edit();

        for (String key : all.keySet()) {
            if (key.startsWith(FILTER_CACHE_JSON_PREFIX) || key.startsWith(FILTER_CACHE_TS_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.putBoolean(KEY_FILTER_CACHE_FORCE_REFRESH, true);
        editor.apply();
    }

    private static class PlanDiff {
        final List<PlanSnapshotEvent> cancelled = new ArrayList<>();
        final List<PlanSnapshotEvent> removed = new ArrayList<>();
        final List<PlanSnapshotEvent> added = new ArrayList<>();
        final List<PlanMove> moved = new ArrayList<>();
    }

    private static class PlanMove {
        final PlanSnapshotEvent from;
        final PlanSnapshotEvent to;

        PlanMove(PlanSnapshotEvent from, PlanSnapshotEvent to) {
            this.from = from;
            this.to = to;
        }
    }

    private static class PlanSnapshotEvent {
        final String core;
        final String title;
        final LocalDate date;
        final int startMin;
        final int endMin;
        final boolean cancelledType;

        PlanSnapshotEvent(String core, String title, LocalDate date, int startMin, int endMin, boolean cancelledType) {
            this.core = core;
            this.title = title;
            this.date = date;
            this.startMin = startMin;
            this.endMin = endMin;
            this.cancelledType = cancelledType;
        }

        static PlanSnapshotEvent from(LocalDate date, PlanRepository.PlanEventUi event) {
            String title = event.title != null ? event.title.trim() : "";
            String teacher = event.teacher != null ? event.teacher.trim() : "";
            String group = event.group != null ? event.group.trim() : "";
            String room = event.room != null ? event.room.trim() : "";
            String type = event.typeClass != null ? event.typeClass.trim() : "";
            int duration = Math.max(0, event.endMin - event.startMin);
            String core = (title + "|" + teacher + "|" + group + "|" + room + "|" + type + "|" + duration)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            boolean cancelledType = "week-event-type-cancelled".equalsIgnoreCase(type);
            return new PlanSnapshotEvent(core, title, date, event.startMin, event.endMin, cancelledType);
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("c", core);
                obj.put("t", title);
                obj.put("d", date != null ? date.toString() : "");
                obj.put("s", startMin);
                obj.put("e", endMin);
                obj.put("x", cancelledType);
            } catch (Exception ignored) {
            }
            return obj;
        }

        static PlanSnapshotEvent fromJson(JSONObject obj) {
            try {
                String core = obj.optString("c", "");
                String title = obj.optString("t", "");
                String dateStr = obj.optString("d", "");
                LocalDate date = LocalDate.parse(dateStr);
                int start = obj.optInt("s", 0);
                int end = obj.optInt("e", start);
                boolean cancelledType = obj.optBoolean("x", false);
                return new PlanSnapshotEvent(core, title, date, start, end, cancelledType);
            } catch (Exception e) {
                return null;
            }
        }

        String id() {
            return core + "|" + date + "|" + startMin + "|" + endMin;
        }
    }
}

