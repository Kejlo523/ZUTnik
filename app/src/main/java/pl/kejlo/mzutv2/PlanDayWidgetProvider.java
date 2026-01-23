package pl.kejlo.mzutv2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";
    public static final String EXTRA_DATE_ISO = "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMMM yyyy",
            new Locale("pl", "PL"));

    private static final DateTimeFormatter DAY_OF_WEEK_LABEL = DateTimeFormatter.ofPattern("EEEE",
            new Locale("pl", "PL"));

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";
    private static final String PREFS_SETTINGS = "mzut_settings";
    // private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L; // Removed
    // constant

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        schedulePeriodicRefresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        cancelPeriodicRefresh(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // Use goAsync to allow background work without StrictMode hacks
        final PendingResult result = goAsync();
        executor.execute(() -> {
            for (int appWidgetId : appWidgetIds) {
                updateOneWidget(context, appWidgetManager, appWidgetId);
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
            }
            // Schedule next refresh
            schedulePeriodicRefresh(context);
            result.finish();
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = mgr.getAppWidgetIds(new ComponentName(context, PlanDayWidgetProvider.class));
            }

            // Show loading state immediately
            for (int appWidgetId : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day_glass);
                views.setViewVisibility(R.id.widgetRefresh, android.view.View.GONE);
                views.setViewVisibility(R.id.widgetLoading, android.view.View.VISIBLE);
                mgr.updateAppWidget(appWidgetId, views);
            }

            final PendingResult result = goAsync();
            final int[] finalIds = ids;
            executor.execute(() -> {
                for (int appWidgetId : finalIds) {
                    updateOneWidget(context, mgr, appWidgetId);
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                }
                result.finish();
            });
        }
    }

    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day_glass);

        // Apply Theme Background
        String theme = ThemeManager.getTheme(context);
        int bgRes = R.drawable.bg_widget_dark_glass; // Default
        int textColorPrimary = context.getColor(R.color.glass_text_primary);
        int textColorSecondary = context.getColor(R.color.glass_text_secondary);

        switch (theme) {
            case ThemeManager.THEME_DEEP_BLUE:
                // Deep Blue Glass (reuse existing drawable or similar tint)
                views.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.bg_widget_dark_glass);
                // Maybe tint it blue? For now keep glass.
                break;
            default:
                views.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.bg_widget_dark_glass);
                break;
        }

        views.setTextColor(R.id.widgetDate, textColorPrimary);
        views.setTextColor(R.id.widgetSubtitle, textColorSecondary);
        views.setTextColor(R.id.widgetLastRefresh, textColorSecondary);
        // Loading spinner tint
        views.setInt(R.id.widgetRefresh, "setColorFilter", textColorSecondary);

        views.setViewVisibility(R.id.widgetRefresh, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widgetLoading, android.view.View.GONE);

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute();

        LocalDate targetDate = today;
        String subtitleText = context.getString(R.string.plan_widget_subtitle_today);

        boolean hasSession = ensureSessionFromPrefs(context);

        if (hasSession) {
            try {
                PlanRepository repo = new PlanRepository(context.getApplicationContext());
                Set<String> hiddenSubjectKeys = context
                        .getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE)
                        .getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                // Load current week AND next week to ensure we find future events
                PlanRepository.PlanResult weekResult = repo.loadPlan("week", today);
                PlanRepository.PlanResult nextWeekResult = repo.loadPlan("week", today.plusDays(7));

                // Merge next week's data into result
                if (nextWeekResult != null && nextWeekResult.dayColumns != null && weekResult != null) {
                    if (weekResult.dayColumns == null) {
                        weekResult.dayColumns = new java.util.ArrayList<>();
                    }
                    for (PlanRepository.DayColumn col : nextWeekResult.dayColumns) {
                        // Only add if not already present
                        boolean found = false;
                        for (PlanRepository.DayColumn existing : weekResult.dayColumns) {
                            if (existing.date != null && existing.date.equals(col.date)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            weekResult.dayColumns.add(col);
                        }
                    }
                }

                // Now find the suitable day to show
                targetDate = findBestDateToShow(weekResult, today, nowMin, hiddenSubjectKeys);

                // Determine subtitle based on what we found
                if (targetDate.equals(today)) {
                    // Check if we have upcoming events today
                    List<PlanRepository.PlanEventUi> eventsToday = getEventsForDate(weekResult, today,
                            hiddenSubjectKeys);
                    List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : eventsToday) {
                        if (ev.endMin > nowMin)
                            upcoming.add(ev);
                    }

                    if (!upcoming.isEmpty()) {
                        PlanRepository.PlanEventUi next = upcoming.get(0);
                        if (next.startMin <= nowMin) {
                            subtitleText = context.getString(R.string.plan_widget_subtitle_in_progress);
                        } else {
                            int diffMin = next.startMin - nowMin;
                            int h = diffMin / 60;
                            int m = diffMin % 60;
                            if (h > 0) {
                                subtitleText = String.format(h > 0 && m == 0 ? "Za %d godz." : "Za %d godz. %d min", h,
                                        m);
                            } else {
                                subtitleText = String.format("Za %d min", m);
                            }
                        }
                    } else {
                        // All events today passed, but findBestDateToShow returned today?
                        // This implies no future events found at all, or logic decided to stay on
                        // today.
                        // Actually, if findBestDateToShow returns today but no upcoming,
                        // it means there were NO events in future days either.
                        subtitleText = context.getString(R.string.plan_widget_subtitle_today);
                    }
                } else if (targetDate.equals(today.plusDays(1))) {
                    subtitleText = context.getString(R.string.plan_widget_subtitle_tomorrow);
                } else {
                    String dayName = targetDate.format(DAY_OF_WEEK_LABEL);
                    dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1);
                    subtitleText = dayName;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            subtitleText = context.getString(R.string.plan_widget_subtitle_login_required);
        }

        String dateLabel = targetDate.format(DATE_LABEL);
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);

        String refreshedLabel = context.getString(R.string.plan_widget_refreshed_prefix)
                + " " + LocalTime.now().format(TIME_LABEL);
        views.setTextViewText(R.id.widgetLastRefresh, refreshedLabel);

        Intent svcIntent = new Intent(context, PlanDayWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.putExtra(EXTRA_DATE_ISO, targetDate.toString());
        svcIntent.setData(Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));

        views.setRemoteAdapter(R.id.widgetList, svcIntent);

        Intent openIntent = new Intent(context, PlanActivity.class);
        openIntent.putExtra("currentDate", targetDate.toString());
        openIntent.putExtra("viewMode", "day");

        PendingIntent piOpen = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        Intent refreshIntent = new Intent(context, PlanDayWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });
        PendingIntent piRefresh = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRefresh, piRefresh);

        Intent rowIntent = new Intent(context, PlanActivity.class);
        PendingIntent rowPI = PendingIntent.getActivity(
                context,
                0,
                rowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setPendingIntentTemplate(R.id.widgetList, rowPI);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private LocalDate findBestDateToShow(PlanRepository.PlanResult weekResult, LocalDate today, int nowMin,
            Set<String> hiddenKeys) {
        // 1. Check today for upcoming events
        List<PlanRepository.PlanEventUi> todayEvents = getEventsForDate(weekResult, today, hiddenKeys);
        for (PlanRepository.PlanEventUi ev : todayEvents) {
            if (ev.endMin > nowMin)
                return today;
        }

        // 2. Check next few days
        for (int i = 1; i <= 7; i++) {
            LocalDate d = today.plusDays(i);
            List<PlanRepository.PlanEventUi> dEvents = getEventsForDate(weekResult, d, hiddenKeys);
            if (!dEvents.isEmpty())
                return d;
        }

        // 3. Fallback to tomorrow if nothing found (or today if preferred?)
        // Logic: if today has events but all passed, and nothing in future -> maybe
        // fallback to tomorrow (empty)
        // If today empty and nothing future -> tomorrow
        return today.plusDays(1);
    }

    private List<PlanRepository.PlanEventUi> getEventsForDate(PlanRepository.PlanResult result, LocalDate date,
            Set<String> hiddenKeys) {
        if (result == null || result.dayColumns == null)
            return Collections.emptyList();

        for (PlanRepository.DayColumn col : result.dayColumns) {
            if (date.equals(col.date) && col.events != null) {
                List<PlanRepository.PlanEventUi> out = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : col.events) {
                    if (ev.subjectKey != null && hiddenKeys.contains(ev.subjectKey))
                        continue;
                    out.add(ev);
                }
                Collections.sort(out, (a, b) -> Integer.compare(a.startMin, b.startMin));
                return out;
            }
        }
        return Collections.emptyList();
    }

    private static void schedulePeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;

        // 1. Cancel existing
        cancelPeriodicRefresh(context);

        // 2. Read interval
        String intervalStr = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString("widget_refresh_interval", "30");
        long intervalMin = 30;
        try {
            intervalMin = Long.parseLong(intervalStr);
        } catch (NumberFormatException e) {
            intervalMin = 30;
        }

        if (intervalMin <= 0) {
            // "Never" / Manual only
            return;
        }

        long intervalMs = intervalMin * 60L * 1000L;

        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs,
                intervalMs, pi);
    }

    public static void rescheduleRefresh(Context context) {
        schedulePeriodicRefresh(context);
    }

    private static void cancelPeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;
        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}