package pl.kejlo.mzutv2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
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

public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";
    public static final String EXTRA_DATE_ISO = "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("pl", "PL"));

    private static final DateTimeFormatter DAY_OF_WEEK_LABEL =
            DateTimeFormatter.ofPattern("EEEE", new Locale("pl", "PL"));

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;

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
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        for (int appWidgetId : appWidgetIds) {
            updateOneWidget(context, appWidgetManager, appWidgetId);
        }
        schedulePeriodicRefresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = mgr.getAppWidgetIds(new ComponentName(context, PlanDayWidgetProvider.class));
            }

            for (int appWidgetId : ids) {
                updateOneWidget(context, mgr, appWidgetId);
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
            }
        }
    }

    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);

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

                List<PlanRepository.PlanEventUi> eventsToday = loadEventsForDay(repo, today, hiddenSubjectKeys);
                List<PlanRepository.PlanEventUi> upcomingToday = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : eventsToday) {
                    if (ev.endMin > nowMin) {
                        upcomingToday.add(ev);
                    }
                }

                if (!upcomingToday.isEmpty()) {
                    targetDate = today;
                    PlanRepository.PlanEventUi next = upcomingToday.get(0);

                    if (next.startMin <= nowMin) {
                        subtitleText = context.getString(R.string.plan_widget_subtitle_in_progress);
                    } else {
                        int diffMin = next.startMin - nowMin;
                        int h = diffMin / 60;
                        int m = diffMin % 60;
                        if (h > 0) {
                            if (m > 0) {
                                subtitleText = String.format("Za %d godz. %d min", h, m);
                            } else {
                                subtitleText = String.format("Za %d godz.", h);
                            }
                        } else {
                            subtitleText = String.format("Za %d min", m);
                        }
                    }
                } else {
                    LocalDate foundDate = null;

                    for (int i = 1; i <= 7; i++) {
                        LocalDate d = today.plusDays(i);
                        List<PlanRepository.PlanEventUi> events = loadEventsForDay(repo, d, hiddenSubjectKeys);
                        if (!events.isEmpty()) {
                            foundDate = d;
                            break;
                        }
                    }

                    if (foundDate != null) {
                        targetDate = foundDate;
                        if (foundDate.equals(today.plusDays(1))) {
                            subtitleText = context.getString(R.string.plan_widget_subtitle_tomorrow);
                        } else {
                            String dayName = foundDate.format(DAY_OF_WEEK_LABEL);
                            dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1);
                            subtitleText = dayName;
                        }
                    } else {
                        targetDate = today.plusDays(1);
                        subtitleText = context.getString(R.string.plan_widget_subtitle_no_classes_tomorrow);
                    }
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
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        Intent refreshIntent = new Intent(context, PlanDayWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{appWidgetId});
        PendingIntent piRefresh = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRefresh, piRefresh);

        Intent rowIntent = new Intent(context, PlanActivity.class);
        PendingIntent rowPI = PendingIntent.getActivity(
                context,
                0,
                rowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setPendingIntentTemplate(R.id.widgetList, rowPI);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private List<PlanRepository.PlanEventUi> loadEventsForDay(PlanRepository repo, LocalDate date, Set<String> hiddenKeys) {
        List<PlanRepository.PlanEventUi> result = new ArrayList<>();
        try {
            PlanRepository.PlanResult pr = repo.loadPlan("day", date);
            if (pr.dayColumns != null) {
                for (PlanRepository.DayColumn col : pr.dayColumns) {
                    if (date.equals(col.date) && col.events != null) {
                        for (PlanRepository.PlanEventUi ev : col.events) {
                            if (ev.subjectKey != null && hiddenKeys.contains(ev.subjectKey)) continue;
                            result.add(ev);
                        }
                    }
                }
            }
            Collections.sort(result, (a, b) -> Integer.compare(a.startMin, b.startMin));
        } catch (Exception ignored) {}
        return result;
    }

    private static void schedulePeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS, pi);
    }

    private static void cancelPeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}