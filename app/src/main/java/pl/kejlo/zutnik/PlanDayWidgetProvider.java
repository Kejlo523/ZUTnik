package pl.kejlo.zutnik;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class PlanDayWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "ZUTnik-PlanWidget";
    public static final String ACTION_REFRESH = "pl.kejlo.zutnik.PLAN_WIDGET_REFRESH";
    public static final String ACTION_MANUAL_REFRESH = "pl.kejlo.zutnik.PLAN_WIDGET_MANUAL_REFRESH";
    public static final String EXTRA_DATE_ISO = "pl.kejlo.zutnik.PLAN_WIDGET_DATE_ISO";

    private static final String DATE_LABEL_PATTERN = "d MMMM yyyy";
    private static final String DAY_OF_WEEK_LABEL_PATTERN = "EEEE";

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
        refreshAsync(context, appWidgetManager, appWidgetIds, false);
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context,
            AppWidgetManager appWidgetManager,
            int appWidgetId,
            Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        refreshAsync(context, appWidgetManager, new int[] { appWidgetId }, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction()) || ACTION_MANUAL_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = mgr.getAppWidgetIds(new ComponentName(context, PlanDayWidgetProvider.class));
            }

            refreshAsync(context, mgr, ids, ACTION_MANUAL_REFRESH.equals(intent.getAction()));
        }
    }

    private void refreshAsync(Context context, AppWidgetManager manager, int[] ids, boolean manual) {
        Context app = context.getApplicationContext();
        String requestedOwner = PlanDayWidgetHelper.owner(app);
        PendingResult pending = goAsync();
        try {
            executor.execute(() -> {
                try {
                    if (manual && ids != null && ids.length > 0
                            && requestedOwner.equals(PlanDayWidgetHelper.owner(app))) {
                        PlanDayWidgetHelper.enqueueManualRefresh(app);
                    }
                    if (ids != null) {
                        for (int id : ids) {
                            try {
                                updateOneWidget(app, manager, id);
                                manager.notifyAppWidgetViewDataChanged(id, R.id.widgetList);
                            } catch (RuntimeException failure) {
                                Log.w(TAG, "Widget update failed", failure);
                            }
                        }
                    }
                    schedulePeriodicRefresh(app);
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Widget refresh dispatch failed", failure);
                } finally {
                    if (pending != null) pending.finish();
                }
            });
        } catch (RuntimeException failure) {
            if (pending != null) pending.finish();
            Log.w(TAG, "Widget executor rejected refresh", failure);
        }
    }
    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = buildViews(context, appWidgetManager, appWidgetId);
        if (views != null) appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
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

        boolean refreshing = PlanDayWidgetHelper.isRefreshing(context);
        views.setViewVisibility(R.id.widgetRefresh, refreshing ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widgetLoading, refreshing ? android.view.View.VISIBLE : android.view.View.GONE);

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute();

        LocalDate targetDate = today;
        String subtitleText;
        boolean hideList = false;
        boolean listHasItems = false;
        String emptyStateText = context.getString(R.string.plan_widget_empty_state);

        String owner = PlanDayWidgetHelper.owner(context);
        boolean hasSession = !owner.isEmpty();
        long cacheTimestamp = 0L;

        if (hasSession) {
            try {
                Set<String> hiddenSubjectKeys = PlanDayWidgetHelper.hiddenKeys(context);
                PlanRepository.PlanResult weekResult = PlanDayWidgetHelper.loadVisibleCache(context, today);
                targetDate = PlanDayWidgetHelper.bestDate(weekResult, today, nowMin, hiddenSubjectKeys);
                List<PlanRepository.PlanEventUi> upcoming = PlanDayWidgetHelper.upcomingEvents(
                        weekResult, targetDate, today, nowMin, hiddenSubjectKeys);
                listHasItems = !upcoming.isEmpty();
                cacheTimestamp = PlanDayWidgetHelper.cacheTimestamp(context, targetDate);
                LocalDate tomorrow = today.plusDays(1);
                boolean tomorrowHasClasses = !PlanDayWidgetHelper.eventsForDate(
                        weekResult, tomorrow, hiddenSubjectKeys).isEmpty();
                if (targetDate.equals(today)) {
                    if (!upcoming.isEmpty()) {
                        PlanRepository.PlanEventUi next = upcoming.get(0);
                        subtitleText = next.startMin <= nowMin
                                ? context.getString(R.string.plan_widget_subtitle_in_progress)
                                : formatNextClassSubtitle(context, next.startMin - nowMin);
                    } else {
                        subtitleText = context.getString(R.string.plan_widget_subtitle_today);
                    }
                } else if (!tomorrowHasClasses && PlanDayWidgetHelper.verifiedRange(context, tomorrow, tomorrow)) {
                    subtitleText = context.getString(R.string.plan_widget_subtitle_no_classes_tomorrow);
                } else if (targetDate.equals(tomorrow)) {
                    subtitleText = context.getString(R.string.plan_widget_subtitle_tomorrow);
                } else {
                    String dayName = targetDate.format(dayOfWeekFormatter());
                    subtitleText = dayName.substring(0, 1).toUpperCase(Locale.getDefault()) + dayName.substring(1);
                }
                if (!listHasItems && !PlanDayWidgetHelper.verifiedRange(context, today, today.plusDays(7))) {
                    emptyStateText = context.getString(R.string.plan_widget_sync_required);
                    subtitleText = emptyStateText;
                }
                if (refreshing) {
                    subtitleText = context.getString(R.string.plan_sync_preparing);
                }
            } catch (Exception e) {
                Log.w(TAG, "Widget refresh failed", e);
                hideList = true;
                subtitleText = context.getString(refreshing
                        ? R.string.plan_sync_preparing : R.string.plan_widget_sync_required);
                emptyStateText = subtitleText;
            }
        } else {
            subtitleText = context.getString(R.string.plan_widget_subtitle_login_required);
            hideList = true;
            emptyStateText = subtitleText;
        }

        if (!owner.equals(PlanDayWidgetHelper.owner(context))) {
            // Never publish a snapshot captured before a logout/account switch.
            context.sendBroadcast(new Intent(context, PlanDayWidgetProvider.class).setAction(ACTION_REFRESH));
            return null;
        }

        String dateLabel = hideList ? today.format(dateLabelFormatter()) : targetDate.format(dateLabelFormatter());
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);
        boolean showList = !hideList && listHasItems;
        boolean showSubtitle = !hideList;
        if (!showList && emptyStateText.equals(subtitleText)) {
            showSubtitle = false;
        }
        views.setViewVisibility(R.id.widgetStatusRow, showSubtitle ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setTextViewText(R.id.widgetEmptyState, emptyStateText);

        String refreshedLabel = cacheTimestamp > 0L ? context.getString(R.string.plan_widget_cached_at,
                Instant.ofEpochMilli(cacheTimestamp).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))) : "";
        views.setTextViewText(R.id.widgetLastRefresh, refreshedLabel);
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int minHeight = options != null
                ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                : 0;
        views.setViewVisibility(
                R.id.widgetLastRefresh,
                minHeight >= 230 && cacheTimestamp > 0L
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE);

        Intent svcIntent = new Intent(context, PlanDayWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.putExtra(EXTRA_DATE_ISO, targetDate.toString());
        svcIntent.putExtra(PlanDayWidgetHelper.EXTRA_OWNER, owner);
        svcIntent.setData(Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));

        views.setRemoteAdapter(R.id.widgetList, svcIntent);
        views.setEmptyView(R.id.widgetList, R.id.widgetEmptyState);
        // setEmptyView initially sees an unbound adapter; restore the known cache state afterward.
        views.setViewVisibility(R.id.widgetList, showList ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.widgetEmptyState, showList ? android.view.View.GONE : android.view.View.VISIBLE);

        Intent openIntent = new Intent(context, PlanActivity.class);
        openIntent.putExtra("currentDate", targetDate.toString());
        openIntent.putExtra("viewMode", "day");
        openIntent.setData(widgetUri(appWidgetId, "open", targetDate));

        PendingIntent piOpen = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        Intent refreshIntent = new Intent(context, PlanDayWidgetProvider.class);
        refreshIntent.setAction(ACTION_MANUAL_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });
        PendingIntent piRefresh = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRefresh, piRefresh);
        views.setOnClickPendingIntent(R.id.widgetLoading, piRefresh);

        views.setPendingIntentTemplate(R.id.widgetList, rowTemplate(context, appWidgetId, targetDate));

        return views;
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

    static Uri widgetUri(int widgetId, String action, LocalDate date) {
        return new Uri.Builder().scheme("zutnik").authority("plan-widget")
                .appendPath(Integer.toString(widgetId)).appendPath(action)
                .appendPath(date.toString()).build();
    }

    static PendingIntent rowTemplate(Context context, int widgetId, LocalDate date) {
        Intent intent = new Intent(context, PlanActivity.class);
        intent.putExtra("currentDate", date.toString());
        intent.putExtra("viewMode", "day");
        intent.setData(widgetUri(widgetId, "row", date));
        return PendingIntent.getActivity(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private static void schedulePeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;

        cancelPeriodicRefresh(context);

        if (AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, PlanDayWidgetProvider.class)).length == 0) return;

        String intervalStr = SettingsPrefs.getWidgetRefreshInterval(context);
        long intervalMin;
        try {
            intervalMin = Long.parseLong(intervalStr);
        } catch (NumberFormatException e) {
            intervalMin = Long.parseLong(SettingsPrefs.DEFAULT_WIDGET_REFRESH_INTERVAL);
        }

        if (intervalMin <= 0) {
            return;
        }

        long intervalMs = intervalMin * 60L * 1000L;

        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + intervalMs,
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
