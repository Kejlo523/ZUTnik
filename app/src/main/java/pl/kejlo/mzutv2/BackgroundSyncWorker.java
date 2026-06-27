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
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final String KEY_GRADES_BASELINE_READY = "grades_baseline_ready_v2";
    private static final String KEY_GRADES_BASELINE_JSON = "grades_baseline_json_v2";
    private static final String KEY_PLAN_BASELINE_READY = "plan_baseline_ready_v3";
    private static final String KEY_PLAN_BASELINE_JSON = "plan_baseline_json_v3";
    private static final String KEY_FINANCE_BASELINE_READY = "finance_baseline_ready_v1";
    private static final String KEY_FINANCE_BASELINE_JSON = "finance_baseline_json_v1";
    private static final String KEY_FINANCE_DUE_SENT_JSON = "finance_due_sent_json_v1";
    private static final String KEY_FILTER_CACHE_FORCE_REFRESH = "plan_filters_force_refresh_v1";
    private static final String FILTER_CACHE_JSON_PREFIX = "plan_filters_cache_json_";
    private static final String FILTER_CACHE_TS_PREFIX = "plan_filters_cache_ts_";
    private static final String KEY_LAST_GRADES_ALERT_HASH = "grades_last_alert_hash_v1";
    private static final String KEY_LAST_GRADES_ALERT_TS = "grades_last_alert_ts_v1";
    private static final String KEY_LAST_PLAN_ALERT_HASH = "plan_last_alert_hash_v1";
    private static final String KEY_LAST_PLAN_ALERT_TS = "plan_last_alert_ts_v1";

    private static final int NOTIF_ID_GRADES = 9101;
    private static final int NOTIF_ID_PLAN = 9102;
    private static final int NOTIF_ID_FINANCE_DUE = 9103;
    private static final int NOTIF_ID_FINANCE_BOOKED = 9104;
    private static final int PLAN_REFRESH_THRESHOLD = 35; // 36+ changes trigger the bulk refresh alert.
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

            boolean withinAcademicWindow = isWithinAcademicNotificationWindow(context);
            if (!withinAcademicWindow) {
                Log.d(TAG, "Skipping grades and timetable checks outside didactic/session period.");
            }

            if (getInputData().getBoolean(INPUT_BOOTSTRAP_PREFETCH, false)) {
                runBootstrapPrefetch(context);
            }

            if (NotificationSyncManager.isGradesEnabled(context)) {
                if (withinAcademicWindow) {
                    checkGrades(context);
                }
            }

            if (NotificationSyncManager.isPlanEnabled(context) && NotificationSyncManager.isAnyPlanCategoryEnabled(context)) {
                if (withinAcademicWindow) {
                    checkPlanChanges(context);
                }
            }

            if (NotificationSyncManager.isFinanceEnabled(context)
                    && NotificationSyncManager.isAnyFinanceCategoryEnabled(context)) {
                checkFinanceChanges(context);
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
        boolean hasSession = session.isLoggedIn();
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
        List<Grade> grades = repo.loadCurrentGrades();
        if (grades == null) {
            grades = Collections.emptyList();
        }
        Semester gradeScope = new Semester();
        gradeScope.listaSemestrowId = "active_terms";
        Map<String, String> current = new LinkedHashMap<>();
        for (Grade grade : grades) {
            if (grade == null || safe(grade.grade).isEmpty()) {
                continue;
            }
            String key = buildGradeKey(gradeScope, grade);
            String label = buildGradeLabel(grade);
            current.put(key, label);
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        String scope = NotificationSyncManager.buildCurrentSyncScope(context);
        String gradesBaselineReadyKey = NotificationSyncManager.scopedPrefKey(KEY_GRADES_BASELINE_READY, scope);
        String gradesBaselineJsonKey = NotificationSyncManager.scopedPrefKey(KEY_GRADES_BASELINE_JSON, scope);
        String gradesAlertHashKey = NotificationSyncManager.scopedPrefKey(KEY_LAST_GRADES_ALERT_HASH, scope);
        String gradesAlertTsKey = NotificationSyncManager.scopedPrefKey(KEY_LAST_GRADES_ALERT_TS, scope);
        boolean baselineReady = prefs.getBoolean(gradesBaselineReadyKey, false);
        Set<String> previous = readStringSetJson(prefs.getString(gradesBaselineJsonKey, "[]"));

        if (!baselineReady) {
            saveStringSetJson(prefs, gradesBaselineJsonKey, current.keySet());
            prefs.edit().putBoolean(gradesBaselineReadyKey, true).apply();
            return;
        }

        if (current.isEmpty() && !previous.isEmpty()) {
            Log.d(TAG, "Skipping grades baseline overwrite: USOS returned empty grades after a non-empty baseline.");
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
            saveStringSetJson(prefs, gradesBaselineJsonKey, current.keySet());

            String signature = buildAlertSignatureFromList(addedKeys);
            if (shouldNotifyForSignature(prefs, gradesAlertHashKey, gradesAlertTsKey, signature)) {
                notifyGrades(context, addedKeys.size(), lines);
                rememberAlertSignature(prefs, gradesAlertHashKey, gradesAlertTsKey, signature);
            }
            return;
        }

        saveStringSetJson(prefs, gradesBaselineJsonKey, current.keySet());
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

    private void checkFinanceChanges(Context context) throws Exception {
        boolean dueEnabled = NotificationSyncManager.isFinanceDueEnabled(context);
        boolean bookedEnabled = NotificationSyncManager.isFinanceBookedEnabled(context);
        if (!dueEnabled && !bookedEnabled) {
            return;
        }

        FinanceRepository repository = new FinanceRepository(context);
        FinanceRepository.FinanceSnapshot snapshot = repository.loadPaymentsForActiveStudy(true);
        List<FinanceRecord> currentRecords = snapshot != null && snapshot.records != null
                ? snapshot.records
                : Collections.emptyList();

        SharedPreferences prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        String scope = NotificationSyncManager.buildCurrentSyncScope(context);
        String financeBaselineReadyKey = NotificationSyncManager.scopedPrefKey(KEY_FINANCE_BASELINE_READY, scope);
        String financeBaselineJsonKey = NotificationSyncManager.scopedPrefKey(KEY_FINANCE_BASELINE_JSON, scope);
        boolean baselineReady = prefs.getBoolean(financeBaselineReadyKey, false);
        List<FinanceRecord> previousRecords = readFinanceBaselineSnapshot(prefs, financeBaselineJsonKey);

        if (dueEnabled) {
            notifyFinanceDueReminders(context, prefs, scope, currentRecords);
        }

        if (!baselineReady) {
            saveFinanceBaselineSnapshot(prefs, financeBaselineJsonKey, currentRecords);
            prefs.edit().putBoolean(financeBaselineReadyKey, true).apply();
            return;
        }

        if (bookedEnabled) {
            List<FinanceRecord> newlyBooked = FinanceNotificationPlanner.findNewlyBooked(previousRecords, currentRecords);
            if (!newlyBooked.isEmpty()) {
                notifyFinanceBooked(context, newlyBooked);
            }
        }

        saveFinanceBaselineSnapshot(prefs, financeBaselineJsonKey, currentRecords);
    }

    private void notifyFinanceDueReminders(
            Context context,
            SharedPreferences prefs,
            String scope,
            List<FinanceRecord> currentRecords) {
        List<FinanceRecord> candidates = FinanceNotificationPlanner.findDueReminders(currentRecords, LocalDate.now());
        String dueSentKey = NotificationSyncManager.scopedPrefKey(KEY_FINANCE_DUE_SENT_JSON, scope);
        Set<String> sentKeys = pruneFinanceDueReminderKeys(
                readStringSetJson(prefs.getString(dueSentKey, "[]")),
                LocalDate.now());

        List<FinanceRecord> freshReminders = new ArrayList<>();
        for (FinanceRecord record : candidates) {
            String reminderKey = buildFinanceDueReminderKey(record);
            if (reminderKey.isEmpty() || !sentKeys.add(reminderKey)) {
                continue;
            }
            freshReminders.add(record);
        }

        saveStringSetJson(prefs, dueSentKey, sentKeys);
        if (!freshReminders.isEmpty()) {
            notifyFinanceDue(context, freshReminders);
        }
    }

    private void notifyFinanceDue(Context context, List<FinanceRecord> records) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        List<String> lines = new ArrayList<>();
        for (FinanceRecord record : records) {
            lines.add(buildFinanceDueLine(context, record));
        }

        String title = context.getString(R.string.notif_finance_due_title);
        String summary = context.getString(R.string.notif_finance_due_summary, records.size());
        String bigText = buildBigText(lines, 6, summary);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSyncManager.CHANNEL_FINANCE)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setAutoCancel(true)
                .setContentIntent(buildFinancePendingIntent(context))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_FINANCE_DUE, builder.build());
    }

    private void notifyFinanceBooked(Context context, List<FinanceRecord> records) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        List<String> lines = new ArrayList<>();
        for (FinanceRecord record : records) {
            lines.add(buildFinanceBookedLine(context, record));
        }

        String title = context.getString(R.string.notif_finance_booked_title);
        String summary = context.getString(R.string.notif_finance_booked_summary, records.size());
        String bigText = buildBigText(lines, 6, summary);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSyncManager.CHANNEL_FINANCE)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setAutoCancel(true)
                .setContentIntent(buildFinancePendingIntent(context))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_FINANCE_BOOKED, builder.build());
    }

    private void checkPlanChanges(Context context) throws IOException, org.json.JSONException {
        boolean movedEnabled = NotificationSyncManager.isPlanMovedEnabled(context);
        boolean cancelledEnabled = NotificationSyncManager.isPlanCancelledEnabled(context);
        boolean addedEnabled = NotificationSyncManager.isPlanAddedEnabled(context);
        boolean removedEnabled = NotificationSyncManager.isPlanRemovedEnabled(context);
        if (!movedEnabled && !cancelledEnabled && !addedEnabled && !removedEnabled) {
            return;
        }

        PlanNotificationDiffEngine.Snapshot current = collectPlanNotificationSnapshot(context);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_BG, Context.MODE_PRIVATE);
        String scope = NotificationSyncManager.buildCurrentSyncScope(context);
        String planBaselineReadyKey = NotificationSyncManager.scopedPrefKey(KEY_PLAN_BASELINE_READY, scope);
        String planBaselineJsonKey = NotificationSyncManager.scopedPrefKey(KEY_PLAN_BASELINE_JSON, scope);
        String planAlertHashKey = NotificationSyncManager.scopedPrefKey(KEY_LAST_PLAN_ALERT_HASH, scope);
        String planAlertTsKey = NotificationSyncManager.scopedPrefKey(KEY_LAST_PLAN_ALERT_TS, scope);
        boolean baselineReady = prefs.getBoolean(planBaselineReadyKey, false);
        PlanNotificationDiffEngine.Snapshot previous = readPlanBaselineSnapshot(prefs, planBaselineJsonKey);

        if (!baselineReady) {
            savePlanBaselineSnapshot(prefs, planBaselineJsonKey, current);
            prefs.edit().putBoolean(planBaselineReadyKey, true).apply();
            return;
        }

        PlanNotificationDiffEngine.Diff diff = PlanNotificationDiffEngine.diff(previous, current);
        int totalChanges = diff.moved.size()
                + diff.updated.size()
                + diff.cancelled.size()
                + diff.removed.size()
                + diff.added.size();

        if (totalChanges > PLAN_REFRESH_THRESHOLD) {
            markFilterCacheRefreshNeeded(context);
            savePlanBaselineSnapshot(prefs, planBaselineJsonKey, current);
            String signature = "refresh_" + totalChanges + "_" + buildPlanSnapshotSignature(current);
            if (shouldNotifyForSignature(prefs, planAlertHashKey, planAlertTsKey, signature)) {
                notifyPlanRefreshed(
                        context,
                        new PlanChangeHistoryStore.ChangeRecord(
                                PlanChangeHistoryStore.TYPE_REFRESHED,
                                context.getString(R.string.notif_plan_title),
                                context.getString(R.string.notif_plan_refreshed_text),
                                System.currentTimeMillis(),
                                "",
                                0,
                                0,
                                "",
                                "",
                                "",
                                "",
                                "",
                                0,
                                0,
                                "",
                                "",
                                "",
                                ""));
                rememberAlertSignature(prefs, planAlertHashKey, planAlertTsKey, signature);
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        List<PlanChangeHistoryStore.ChangeRecord> historyEntries = new ArrayList<>();
        long notifiedAt = System.currentTimeMillis();
        if (movedEnabled) {
            for (PlanNotificationDiffEngine.Move move : diff.moved) {
                lines.add(context.getString(
                        R.string.notif_plan_line_moved,
                        move.from.title,
                        formatDate(move.from.date),
                        formatTimeRange(move.from.startMin, move.from.endMin),
                        formatDate(move.to.date),
                        formatTimeRange(move.to.startMin, move.to.endMin)));
                historyEntries.add(buildHistoryRecord(PlanChangeHistoryStore.TYPE_MOVED, move.from, move.to, "", notifiedAt));
            }
        }
        if (addedEnabled || removedEnabled) {
            for (PlanNotificationDiffEngine.Update update : diff.updated) {
                String summary = buildUpdateSummary(context, update.from, update.to);
                lines.add(context.getString(
                        R.string.notif_plan_line_updated,
                        update.to.title,
                        formatDate(update.to.date),
                        formatTimeRange(update.to.startMin, update.to.endMin),
                        summary));
                historyEntries.add(buildHistoryRecord(
                        PlanChangeHistoryStore.TYPE_UPDATED,
                        update.from,
                        update.to,
                        summary,
                        notifiedAt));
            }
        }
        if (cancelledEnabled) {
            for (PlanNotificationDiffEngine.Event event : diff.cancelled) {
                lines.add(context.getString(
                        R.string.notif_plan_line_cancelled,
                        event.title,
                        formatDate(event.date),
                        formatTimeRange(event.startMin, event.endMin)));
                historyEntries.add(buildHistoryRecord(PlanChangeHistoryStore.TYPE_CANCELLED, null, event, "", notifiedAt));
            }
        }
        if (removedEnabled) {
            for (PlanNotificationDiffEngine.Event event : diff.removed) {
                lines.add(context.getString(
                        R.string.notif_plan_line_removed,
                        event.title,
                        formatDate(event.date),
                        formatTimeRange(event.startMin, event.endMin)));
                historyEntries.add(buildHistoryRecord(PlanChangeHistoryStore.TYPE_REMOVED, event, null, "", notifiedAt));
            }
        }
        if (addedEnabled) {
            for (PlanNotificationDiffEngine.Event event : diff.added) {
                lines.add(context.getString(
                        R.string.notif_plan_line_added,
                        event.title,
                        formatDate(event.date),
                        formatTimeRange(event.startMin, event.endMin)));
                historyEntries.add(buildHistoryRecord(PlanChangeHistoryStore.TYPE_ADDED, null, event, "", notifiedAt));
            }
        }

        savePlanBaselineSnapshot(prefs, planBaselineJsonKey, current);
        if (!lines.isEmpty()) {
            String signature = buildAlertSignatureFromList(lines);
            if (shouldNotifyForSignature(prefs, planAlertHashKey, planAlertTsKey, signature)) {
                notifyPlanChanges(context, lines, historyEntries);
                rememberAlertSignature(prefs, planAlertHashKey, planAlertTsKey, signature);
            }
        }
    }

    private void notifyPlanChanges(
            Context context,
            List<String> lines,
            List<PlanChangeHistoryStore.ChangeRecord> historyEntries) {
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
        PlanChangeHistoryStore.append(context, historyEntries);
    }

    private void notifyPlanRefreshed(Context context, PlanChangeHistoryStore.ChangeRecord historyEntry) {
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
        List<PlanChangeHistoryStore.ChangeRecord> entries = new ArrayList<>();
        entries.add(historyEntry);
        PlanChangeHistoryStore.append(context, entries);
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

    private PendingIntent buildFinancePendingIntent(Context context) {
        Intent openIntent = new Intent(context, FinanceActivity.class);
        return PendingIntent.getActivity(
                context,
                91030,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PlanNotificationDiffEngine.Snapshot collectPlanNotificationSnapshot(Context context)
            throws IOException, org.json.JSONException {
        PlanRepository repo = new PlanRepository(context);
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(13);
        Map<LocalDate, List<PlanRepository.PlanEventRaw>> rawRange = repo.loadRawPlanRange(start, end, "notifications");
        return PlanNotificationDiffEngine.buildSnapshot(start, end, rawRange);
    }

    private PlanNotificationDiffEngine.Snapshot readPlanBaselineSnapshot(SharedPreferences prefs, String storageKey) {
        return PlanNotificationDiffEngine.Snapshot.fromJsonString(prefs.getString(storageKey, ""));
    }

    private void savePlanBaselineSnapshot(
            SharedPreferences prefs,
            String storageKey,
            PlanNotificationDiffEngine.Snapshot snapshot) {
        prefs.edit().putString(storageKey, snapshot != null ? snapshot.toJsonString() : "").apply();
    }

    private List<FinanceRecord> readFinanceBaselineSnapshot(SharedPreferences prefs, String storageKey) {
        List<FinanceRecord> records = new ArrayList<>();
        String raw = prefs.getString(storageKey, "[]");
        if (raw == null || raw.isEmpty()) {
            return records;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                records.add(FinanceRecord.fromJson(item));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    private void saveFinanceBaselineSnapshot(
            SharedPreferences prefs,
            String storageKey,
            List<FinanceRecord> records) {
        JSONArray arr = new JSONArray();
        if (records != null) {
            for (FinanceRecord record : records) {
                if (record == null) {
                    continue;
                }
                try {
                    arr.put(record.toJson());
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().putString(storageKey, arr.toString()).apply();
    }

    private String buildGradeKey(Semester semester, Grade grade) {
        return normalize(semester.listaSemestrowId)
                + "|" + normalize(grade.courseId)
                + "|" + normalize(grade.subjectName)
                + "|" + normalize(grade.grade)
                + "|" + normalize(normalizeGradeDate(grade))
                + "|" + normalize(grade.type)
                + "|" + normalize(grade.gradeDescription)
                + "|" + normalize(grade.comment);
    }

    private String normalizeGradeDate(Grade grade) {
        if (grade == null) {
            return "";
        }
        String[] candidates = {
                safe(grade.dateAcquisition),
                safe(grade.dateModified),
                safe(grade.date)
        };
        for (String candidate : candidates) {
            if (candidate.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher isoMatcher = java.util.regex.Pattern
                    .compile("(\\d{4}-\\d{2}-\\d{2})")
                    .matcher(candidate);
            if (isoMatcher.find()) {
                return isoMatcher.group(1);
            }
            java.util.regex.Matcher localMatcher = java.util.regex.Pattern
                    .compile("(\\d{2}\\.\\d{2}\\.\\d{4})")
                    .matcher(candidate);
            if (localMatcher.find()) {
                return localMatcher.group(1);
            }
            return candidate;
        }
        return "";
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

    private String formatTimeRange(int startMinutes, int endMinutes) {
        String start = formatTime(startMinutes);
        if (endMinutes <= startMinutes) {
            return start;
        }
        return start + "-" + formatTime(endMinutes);
    }

    private PlanChangeHistoryStore.ChangeRecord buildHistoryRecord(
            String type,
            PlanNotificationDiffEngine.Event from,
            PlanNotificationDiffEngine.Event to,
            String summary,
            long notifiedAt) {
        String title = to != null && !safe(to.title).isEmpty() ? to.title : (from != null ? from.title : "");
        return new PlanChangeHistoryStore.ChangeRecord(
                type,
                title,
                summary,
                notifiedAt,
                from != null && from.date != null ? from.date.toString() : "",
                from != null ? from.startMin : 0,
                from != null ? from.endMin : 0,
                from != null ? from.room : "",
                from != null ? from.group : "",
                from != null ? from.teacher : "",
                from != null ? from.typeLabel : "",
                to != null && to.date != null ? to.date.toString() : "",
                to != null ? to.startMin : 0,
                to != null ? to.endMin : 0,
                to != null ? to.room : "",
                to != null ? to.group : "",
                to != null ? to.teacher : "",
                to != null ? to.typeLabel : "");
    }

    private String buildUpdateSummary(
            Context context,
            PlanNotificationDiffEngine.Event from,
            PlanNotificationDiffEngine.Event to) {
        List<String> changes = new ArrayList<>();
        addUpdateFieldChange(changes, context.getString(R.string.plan_change_field_room), from.room, to.room);
        addUpdateFieldChange(changes, context.getString(R.string.plan_change_field_group), from.group, to.group);
        addUpdateFieldChange(changes, context.getString(R.string.plan_change_field_teacher), from.teacher, to.teacher);
        addUpdateFieldChange(changes, context.getString(R.string.plan_change_field_type), from.typeLabel, to.typeLabel);

        if (changes.isEmpty()) {
            return context.getString(R.string.plan_change_update_generic);
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(2, changes.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(changes.get(i));
        }
        if (changes.size() > limit) {
            sb.append(", ...");
        }
        return sb.toString();
    }

    private String buildFinanceDueLine(Context context, FinanceRecord record) {
        String title = safe(record != null ? record.getSafeTitle() : null);
        if (title.isEmpty()) {
            title = context.getString(R.string.notif_finance_item_fallback);
        }

        String dueDate = formatDate(record != null ? record.getDueDate() : null);
        String amount = FinanceRecord.formatMoneyText(record != null ? record.amountText : null);
        if (amount == null || amount.isEmpty()) {
            return context.getString(R.string.notif_finance_due_line, title, dueDate);
        }
        return context.getString(R.string.notif_finance_due_line_with_amount, title, dueDate, amount);
    }

    private String buildFinanceBookedLine(Context context, FinanceRecord record) {
        String title = safe(record != null ? record.getSafeTitle() : null);
        if (title.isEmpty()) {
            title = context.getString(R.string.notif_finance_item_fallback);
        }

        String amount = FinanceRecord.formatMoneyText(record != null ? record.paidText : null);
        if (amount == null || amount.isEmpty()) {
            amount = FinanceRecord.formatMoneyText(record != null ? record.amountText : null);
        }
        if (amount == null || amount.isEmpty()) {
            return context.getString(R.string.notif_finance_booked_line, title);
        }
        return context.getString(R.string.notif_finance_booked_line_with_amount, title, amount);
    }

    private void addUpdateFieldChange(List<String> out, String label, String before, String after) {
        String oldValue = safe(before);
        String newValue = safe(after);
        if (oldValue.equals(newValue)) {
            return;
        }
        if (oldValue.isEmpty()) {
            oldValue = "-";
        }
        if (newValue.isEmpty()) {
            newValue = "-";
        }
        out.add(label + " " + oldValue + " -> " + newValue);
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

    private void saveStringSetJson(SharedPreferences prefs, String storageKey, Set<String> values) {
        JSONArray arr = new JSONArray();
        for (String value : values) {
            arr.put(value);
        }
        prefs.edit().putString(storageKey, arr.toString()).apply();
    }

    private Set<String> pruneFinanceDueReminderKeys(Set<String> values, LocalDate today) {
        Set<String> pruned = new HashSet<>();
        if (values == null || values.isEmpty()) {
            return pruned;
        }
        for (String value : values) {
            if (value == null || value.length() < 10) {
                continue;
            }
            try {
                LocalDate dueDate = LocalDate.parse(value.substring(0, 10));
                if (!dueDate.isBefore(today)) {
                    pruned.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        return pruned;
    }

    private String buildFinanceDueReminderKey(FinanceRecord record) {
        if (record == null || record.getDueDate() == null) {
            return "";
        }
        return record.getDueDate() + "|" + record.getStableKey();
    }

    private String buildPlanSnapshotSignature(PlanNotificationDiffEngine.Snapshot snapshot) {
        if (snapshot == null || snapshot.events == null || snapshot.events.isEmpty()) {
            return "empty";
        }
        List<String> ids = new ArrayList<>(snapshot.events.size());
        for (PlanNotificationDiffEngine.Event event : snapshot.events) {
            if (event != null) {
                ids.add(event.signature());
            }
        }
        return buildAlertSignatureFromList(ids);
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
}

