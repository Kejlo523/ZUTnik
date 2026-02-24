package pl.kejlo.mzutv2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class PlanDayWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "mZUTv2-PlanWidget";
    public static final String ACTION_REFRESH = "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";
    public static final String EXTRA_DATE_ISO = "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    private static final String DATE_LABEL_PATTERN = "d MMMM yyyy";
    private static final String DAY_OF_WEEK_LABEL_PATTERN = "EEEE";

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT_DATE_LABEL = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";
    private static final long NO_CLASSES_WIDGET_REFRESH_MINUTES = 3L * 60L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static DateTimeFormatter dateLabelFormatter() {
        return DateTimeFormatter.ofPattern(DATE_LABEL_PATTERN, Locale.getDefault());
    }

    private static DateTimeFormatter dayOfWeekFormatter() {
        return DateTimeFormatter.ofPattern(DAY_OF_WEEK_LABEL_PATTERN, Locale.getDefault());
    }

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

            final PendingResult result = goAsync();
            final int[] finalIds = ids;
            executor.execute(() -> {
                for (int appWidgetId : finalIds) {
                    updateOneWidget(context, mgr, appWidgetId);
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                }
                schedulePeriodicRefresh(context);
                result.finish();
            });
        }
    }

    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.isLoggedIn();
    }
    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day_glass);

        String theme = ThemeManager.getTheme(context);
        int textColorPrimary = context.getColor(R.color.glass_text_primary);
        int textColorSecondary = context.getColor(R.color.glass_text_secondary);

        switch (theme) {
            case ThemeManager.THEME_DEEP_BLUE:
            case ThemeManager.THEME_LIME:
            default:
                views.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.bg_widget_dark_glass);
                break;
        }

        views.setTextColor(R.id.widgetDate, textColorPrimary);
        views.setTextColor(R.id.widgetSubtitle, textColorSecondary);
        views.setTextColor(R.id.widgetLastRefresh, textColorSecondary);
        views.setInt(R.id.widgetRefresh, "setColorFilter", textColorSecondary);

        views.setViewVisibility(R.id.widgetRefresh, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widgetLoading, android.view.View.GONE);

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute();

        LocalDate targetDate = today;
        String subtitleText;
        boolean hideList = false;
        boolean listHasItems = false;
        String emptyStateText = context.getString(R.string.plan_widget_empty_state);

        boolean hasSession = ensureSessionFromPrefs(context);

        if (hasSession) {
            try {
                PlanRepository repo = new PlanRepository(context.getApplicationContext());
                List<PlanRepository.SessionPeriod> periods = repo.fetchSessionDates();
                PlanRepository.SessionPeriod activeNoClasses =
                        PlanRepository.findActivePeriod(periods, today, true);

                if (activeNoClasses != null) {
                    LocalDate nextClassesDate = activeNoClasses.endDate.plusDays(1);
                    String periodLabel = PlanRepository.getPeriodDisplayName(context, activeNoClasses.name);
                    subtitleText = context.getString(
                            R.string.plan_widget_subtitle_no_classes_until,
                            periodLabel,
                            nextClassesDate.format(SHORT_DATE_LABEL));
                    targetDate = nextClassesDate;
                    hideList = true;
                    emptyStateText = subtitleText;
                } else {
                    Set<String> hiddenSubjectKeys = context
                            .getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE)
                            .getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                    PlanRepository.PlanResult weekResult = repo.loadPlan("week", today);
                    PlanRepository.PlanResult nextWeekResult = repo.loadPlan("week", today.plusDays(7));

                    if (nextWeekResult != null && nextWeekResult.dayColumns != null && weekResult != null) {
                        if (weekResult.dayColumns == null) {
                            weekResult.dayColumns = new ArrayList<>();
                        }
                        for (PlanRepository.DayColumn col : nextWeekResult.dayColumns) {
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

                    targetDate = findBestDateToShow(weekResult, today, nowMin, hiddenSubjectKeys);
                    List<PlanRepository.PlanEventUi> targetEvents = getEventsForDate(weekResult, targetDate, hiddenSubjectKeys);
                    LocalDate tomorrow = today.plusDays(1);
                    boolean tomorrowHasClasses = !getEventsForDate(weekResult, tomorrow, hiddenSubjectKeys).isEmpty();

                    if (targetDate.equals(today)) {
                        List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                        for (PlanRepository.PlanEventUi ev : targetEvents) {
                            if (ev.endMin > nowMin) {
                                upcoming.add(ev);
                            }
                        }
                        listHasItems = !upcoming.isEmpty();

                        if (!upcoming.isEmpty()) {
                            PlanRepository.PlanEventUi next = upcoming.get(0);
                            if (next.startMin <= nowMin) {
                                subtitleText = context.getString(R.string.plan_widget_subtitle_in_progress);
                            } else {
                                subtitleText = formatNextClassSubtitle(context, next.startMin - nowMin);
                            }
                        } else {
                            subtitleText = context.getString(R.string.plan_widget_subtitle_today);
                        }
                    } else if (!tomorrowHasClasses) {
                        listHasItems = !targetEvents.isEmpty();
                        subtitleText = context.getString(R.string.plan_widget_subtitle_no_classes_tomorrow);
                    } else if (targetDate.equals(tomorrow)) {
                        listHasItems = !targetEvents.isEmpty();
                        subtitleText = context.getString(R.string.plan_widget_subtitle_tomorrow);
                    } else {
                        listHasItems = !targetEvents.isEmpty();
                        String dayName = targetDate.format(dayOfWeekFormatter());
                        dayName = dayName.substring(0, 1).toUpperCase(Locale.getDefault()) + dayName.substring(1);
                        subtitleText = dayName;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Widget refresh failed", e);
                hideList = true;
                subtitleText = context.getString(R.string.plan_widget_subtitle_today);
                emptyStateText = context.getString(R.string.plan_widget_empty_state);
            }
        } else {
            subtitleText = context.getString(R.string.plan_widget_subtitle_login_required);
            hideList = true;
            emptyStateText = subtitleText;
        }

        String dateLabel = hideList ? today.format(dateLabelFormatter()) : targetDate.format(dateLabelFormatter());
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);
        boolean showList = !hideList && listHasItems;
        boolean showSubtitle = !hideList;
        if (!showList && emptyStateText.equals(subtitleText)) {
            showSubtitle = false;
        }
        views.setViewVisibility(R.id.widgetSubtitle, showSubtitle ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.widgetList, showList ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.widgetEmptyState, showList ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setTextViewText(R.id.widgetEmptyState, emptyStateText);

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
    private String formatNextClassSubtitle(Context context, int diffMin) {
        if (diffMin <= 0) {
            return context.getString(R.string.plan_widget_subtitle_in_progress);
        }

        int hours = diffMin / 60;
        int minutes = diffMin % 60;
        String timePart;
        if (hours > 0 && minutes > 0) {
            timePart = context.getString(R.string.plan_widget_time_hours_minutes, hours, minutes);
        } else if (hours > 0) {
            timePart = context.getString(R.string.plan_widget_time_hours, hours);
        } else {
            timePart = context.getString(R.string.plan_widget_time_minutes, minutes);
        }
        return context.getString(R.string.plan_widget_subtitle_next_in_format, timePart);
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
                out.sort(Comparator.comparingInt(ev -> ev.startMin));
                return out;
            }
        }
        return Collections.emptyList();
    }

    private static void schedulePeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;

        cancelPeriodicRefresh(context);

        String intervalStr = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE)
                .getString(
                        SettingsPrefs.KEY_WIDGET_REFRESH_INTERVAL,
                        SettingsPrefs.DEFAULT_WIDGET_REFRESH_INTERVAL);
        long intervalMin;
        try {
            intervalMin = Long.parseLong(intervalStr);
        } catch (NumberFormatException e) {
            intervalMin = 30;
        }

        if (intervalMin <= 0) {
            return;
        }

        List<PlanRepository.SessionPeriod> cachedPeriods = PlanRepository.getCachedSessionDates(context);
        PlanRepository.SessionPeriod activeNoClasses = PlanRepository.findActivePeriod(
                cachedPeriods,
                LocalDate.now(),
                true);
        if (activeNoClasses != null) {
            intervalMin = Math.max(intervalMin, NO_CLASSES_WIDGET_REFRESH_MINUTES);
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
