package pl.kejlo.mzutv2;

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

// Home screen provider for the "day plan" widget.
public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH =
            "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("pl", "PL"));

    // Time label in footer ("Odświeżono:")
    private static final DateTimeFormatter TIME_LABEL =
            DateTimeFormatter.ofPattern("HH:mm");

    // Plan preferences (filters as in PlanActivity)
    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // System update (e.g. every 30 minutes) – refresh list and header immediately
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
            updateOneWidget(context, appWidgetManager, appWidgetId);
        }
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
                // Refresh list data
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                // Refresh header (date + subtitle + footer)
                updateOneWidget(context, mgr, appWidgetId);
            }
        }
    }

    // Initializes session from SharedPreferences (new MzutSession).
    // Returns true if we have userId + authKey (user is logged in).
    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private void updateOneWidget(Context context,
                                 AppWidgetManager appWidgetManager,
                                 int appWidgetId) {

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);

        // Attach RemoteViewsService that provides the list data
        Intent svcIntent = new Intent(context, PlanDayWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.setData(Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widgetList, svcIntent);

        LocalDate today = LocalDate.now();
        LocalDate dateToShow = today;
        boolean showingTomorrow = false;

        // Default subtitle
        String subtitleText = "Dzisiejsze zajęcia";

        // Initialize session – if there is no login, widget stays empty instead of crashing
        boolean hasSession = ensureSessionFromPrefs(context);

        if (hasSession) {
            try {
                // Repository with context -> shared cache with main app
                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                LocalDate targetDate = today;

                // Load filters
                Set<String> hiddenSubjectKeys = context
                        .getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE)
                        .getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                LocalTime now = LocalTime.now();
                int nowMin = now.getHour() * 60 + now.getMinute();

                // First attempt: today
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
                        // There are still classes today – keep "today" view
                        PlanRepository.PlanEventUi next = upcomingToday.get(0);
                        if (next.startMin <= nowMin) {
                            subtitleText = "Zajęcia w trakcie";
                        } else {
                            int diffMin = next.startMin - nowMin;
                            int h = diffMin / 60;
                            int m = diffMin % 60;

                            StringBuilder sb = new StringBuilder("Najbliższe za ");
                            if (h > 0) {
                                sb.append(h).append(" h");
                                if (m > 0) {
                                    sb.append(" ").append(m).append(" min");
                                }
                            } else {
                                sb.append(m).append(" min");
                            }
                            subtitleText = sb.toString();
                        }
                    } else {
                        // There were classes today, but all finished -> try tomorrow
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
                            subtitleText = "Brak zajęć jutro";
                            targetDate = today.plusDays(1);
                            showingTomorrow = true;
                        }
                    }
                } else {
                    // No classes today at all (also after filters) -> try tomorrow
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
                        subtitleText = "Brak zajęć jutro";
                        targetDate = today.plusDays(1);
                        showingTomorrow = true;
                    }
                }

                dateToShow = targetDate;

            } catch (Exception ignored) {
                // On error keep default "Dzisiejsze zajęcia" and today's date
            }
        } else {
            // No session – suggest logging in
            subtitleText = "Zaloguj się w aplikacji mZUT";
        }

        // Header date (optionally with "(jutro)")
        String dateLabel = dateToShow.format(DATE_LABEL);
        if (showingTomorrow) {
            dateLabel += " (jutro)";
        }
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);

        // Footer: last refresh time
        LocalTime nowTime = LocalTime.now();
        views.setTextViewText(
                R.id.widgetLastRefresh,
                "Odświeżono: " + nowTime.format(TIME_LABEL)
        );

        // Click on whole widget -> open PlanActivity
        Intent openIntent = new Intent(context, PlanActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        // Click on refresh icon -> broadcast ACTION_REFRESH
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

        // Click on a single list row -> open PlanActivity (template)
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
    // Returns true if there are any classes tomorrow (after filters).
    // outSubtitle[0] is set to "Jutrzejsze zajęcia" or "Brak zajęć jutro".
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
            outSubtitle[0] = "Brak zajęć jutro";
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
            outSubtitle[0] = "Brak zajęć jutro";
            return false;
        }

        outSubtitle[0] = "Jutrzejsze zajęcia";
        return true;
    }
}
