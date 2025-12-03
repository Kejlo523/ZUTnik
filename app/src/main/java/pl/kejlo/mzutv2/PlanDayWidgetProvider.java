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

// Day plan widget provider.
public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH =
            "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";

    // Date extra key (must match service)
    public static final String EXTRA_DATE_ISO =
            "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("pl", "PL"));

    // Footer time format
    private static final DateTimeFormatter TIME_LABEL =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    // Refresh interval (30m)
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
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        // System update
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
            updateOneWidget(context, appWidgetManager, appWidgetId);
        }

        // Schedule alarm
        schedulePeriodicRefresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);

            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null || ids.length == 0) {
                ids = mgr.getAppWidgetIds(
                        new ComponentName(context, PlanDayWidgetProvider.class)
                );
            }

            for (int appWidgetId : ids) {
                // Refresh list
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                // Refresh header
                updateOneWidget(context, mgr, appWidgetId);
            }
        }
    }

    // Init session from prefs.
    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private void updateOneWidget(Context context,
                                 AppWidgetManager appWidgetManager,
                                 int appWidgetId) {

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);

        LocalDate today = LocalDate.now();
        LocalDate dateToShow = today;
        boolean showingTomorrow = false;

        String subtitleText = context.getString(R.string.plan_widget_subtitle_today);

        // Initialize session
        boolean hasSession = ensureSessionFromPrefs(context);

        if (hasSession) {
            try {
                // Repo with shared cache
                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                LocalDate targetDate = today;

                Set<String> hiddenSubjectKeys = context
                        .getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE)
                        .getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                LocalTime now = LocalTime.now();
                int nowMin = now.getHour() * 60 + now.getMinute();

                // --- Today/Tomorrow Logic ---

                // Try today
                PlanRepository.PlanResult resultToday = repo.loadPlan("day", today);

                PlanRepository.DayColumn todayCol = null;
                if (resultToday.dayColumns != null) {
                    for (PlanRepository.DayColumn col : resultToday.dayColumns) {
                        if (today.equals(col.date)) {
                            todayCol = col;
                            break;
                        }
                    }
                }

                List<PlanRepository.PlanEventUi> allToday = new ArrayList<>();
                if (todayCol != null && todayCol.events != null) {
                    for (PlanRepository.PlanEventUi ev : todayCol.events) {
                        if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                                && hiddenSubjectKeys.contains(ev.subjectKey)) {
                            continue;
                        }
                        allToday.add(ev);
                    }
                }

                if (!allToday.isEmpty()) {
                    Collections.sort(allToday, (a, b) -> Integer.compare(a.startMin, b.startMin));

                    List<PlanRepository.PlanEventUi> upcomingToday = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : allToday) {
                        if (ev.endMin > nowMin) {
                            upcomingToday.add(ev);
                        }
                    }

                    if (!upcomingToday.isEmpty()) {
                        // Classes remain today.
                        PlanRepository.PlanEventUi next = upcomingToday.get(0);
                        if (next.startMin <= nowMin) {
                            subtitleText = context.getString(R.string.plan_widget_subtitle_in_progress);
                        } else {
                            int diffMin = next.startMin - nowMin;
                            int h = diffMin / 60;
                            int m = diffMin % 60;

                            StringBuilder sb = new StringBuilder(
                                    context.getString(R.string.plan_widget_subtitle_next_prefix)
                            );

                            if (h > 0) {
                                sb.append(" ")
                                        .append(h)
                                        .append(" ")
                                        .append(context.getString(R.string.plan_widget_hours_suffix));

                                if (m > 0) {
                                    sb.append(" ")
                                            .append(m)
                                            .append(" ")
                                            .append(context.getString(R.string.plan_widget_minutes_suffix));
                                }
                            } else {
                                sb.append(" ")
                                        .append(m)
                                        .append(" ")
                                        .append(context.getString(R.string.plan_widget_minutes_suffix));
                            }

                            subtitleText = sb.toString();
                        }
                        targetDate = today;
                    } else {
                        // Today finished -> try tomorrow.
                        String[] subtitleHolder = new String[]{subtitleText};
                        showingTomorrow = tryTomorrowHeader(
                                context,
                                repo,
                                hiddenSubjectKeys,
                                today,
                                subtitleHolder
                        );
                        if (showingTomorrow) {
                            targetDate = today.plusDays(1);
                            subtitleText = subtitleHolder[0];
                        } else {
                            subtitleText = context.getString(
                                    R.string.plan_widget_subtitle_no_classes_tomorrow
                            );
                            targetDate = today.plusDays(1);
                            showingTomorrow = true;
                        }
                    }
                } else {
                    // No classes today -> try tomorrow.
                    String[] subtitleHolder = new String[]{subtitleText};
                    showingTomorrow = tryTomorrowHeader(
                            context,
                            repo,
                            hiddenSubjectKeys,
                            today,
                            subtitleHolder
                    );
                    if (showingTomorrow) {
                        targetDate = today.plusDays(1);
                        subtitleText = subtitleHolder[0];
                    } else {
                        subtitleText = context.getString(
                                R.string.plan_widget_subtitle_no_classes_tomorrow
                        );
                        targetDate = today.plusDays(1);
                        showingTomorrow = true;
                    }
                }

                dateToShow = targetDate;

            } catch (Exception ignored) {
                // Keep default
            }
        } else {
            subtitleText = context.getString(R.string.plan_widget_subtitle_login_required);
        }

        // Header date
        String dateLabel = dateToShow.format(DATE_LABEL);
        if (showingTomorrow) {
            dateLabel += " " + context.getString(R.string.plan_widget_date_tomorrow_suffix);
        }
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);

        // Footer
        LocalTime nowTime = LocalTime.now();
        String refreshedLabel = context.getString(R.string.plan_widget_refreshed_prefix)
                + " " + nowTime.format(TIME_LABEL);
        views.setTextViewText(R.id.widgetLastRefresh, refreshedLabel);

        // Configure list adapter
        Intent svcIntent = new Intent(context, PlanDayWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.putExtra(EXTRA_DATE_ISO, dateToShow.toString());
        svcIntent.setData(Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widgetList, svcIntent);

        // Click actions
        Intent openIntent = new Intent(context, PlanActivity.class);
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

    // Prepares subtitle for tomorrow.
    private boolean tryTomorrowHeader(Context context,
                                      PlanRepository repo,
                                      Set<String> hiddenSubjectKeys,
                                      LocalDate today,
                                      String[] outSubtitle) throws Exception {

        LocalDate tomorrow = today.plusDays(1);
        PlanRepository.PlanResult resultTomorrow = repo.loadPlan("day", tomorrow);

        PlanRepository.DayColumn tomorrowCol = null;
        if (resultTomorrow.dayColumns != null) {
            for (PlanRepository.DayColumn col : resultTomorrow.dayColumns) {
                if (tomorrow.equals(col.date)) {
                    tomorrowCol = col;
                    break;
                }
            }
        }

        if (tomorrowCol == null || tomorrowCol.events == null) {
            outSubtitle[0] = context.getString(
                    R.string.plan_widget_subtitle_no_classes_tomorrow
            );
            return false;
        }

        List<PlanRepository.PlanEventUi> allTomorrow = new ArrayList<>();
        for (PlanRepository.PlanEventUi ev : tomorrowCol.events) {
            if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                    && hiddenSubjectKeys.contains(ev.subjectKey)) {
                continue;
            }
            allTomorrow.add(ev);
        }

        if (allTomorrow.isEmpty()) {
            outSubtitle[0] = context.getString(
                    R.string.plan_widget_subtitle_no_classes_tomorrow
            );
            return false;
        }

        outSubtitle[0] = context.getString(R.string.plan_widget_subtitle_tomorrow);
        return true;
    }

    // Periodic Refresh (AlarmManager)

    private static void schedulePeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long first = System.currentTimeMillis() + REFRESH_INTERVAL_MS;

        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                first,
                REFRESH_INTERVAL_MS,
                pi
        );
    }

    private static void cancelPeriodicRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, PlanDayWidgetProvider.class);
        i.setAction(ACTION_REFRESH);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        am.cancel(pi);
    }
}