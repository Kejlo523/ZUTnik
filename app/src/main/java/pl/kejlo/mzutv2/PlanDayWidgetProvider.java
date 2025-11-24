package pl.kejlo.mzutv2;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH =
            "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("pl", "PL"));

    // te same prefs co w LoginActivity / PlanActivity
    private static final String PREFS_LOGIN       = "mzut_prefs";
    private static final String KEY_USER_ID       = "user_id";
    private static final String KEY_AUTH_KEY      = "auth_key";

    private static final String PREFS_PLAN        = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
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
                // odśwież dane listy
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                // zaktualizuj header (data + subtitle)
                updateOneWidget(context, mgr, appWidgetId);
            }
        }
    }

    private static void ensureSessionFromPrefs(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);
        String authKey = prefs.getString(KEY_AUTH_KEY, null);

        if (userId == null || authKey == null) {
            return;
        }

        MzutSession session = MzutSession.getInstance();
        if (session.getUserId() == null) {
            session.setUserId(userId);
        }
        if (session.getAuthKey() == null) {
            session.setAuthKey(authKey);
        }
    }

    private void updateOneWidget(Context context,
                                 AppWidgetManager appWidgetManager,
                                 int appWidgetId) {

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);

        // podpięcie RemoteViewsService z danymi listy
        Intent svcIntent = new Intent(context, PlanDayWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.setData(Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widgetList, svcIntent);

        // nagłówek z datą
        LocalDate today = LocalDate.now();
        views.setTextViewText(R.id.widgetDate, today.format(DATE_LABEL));

        // subtitle: „Najbliższe za …” / „Dziś brak zajęć” / itd – liczymy tu, osobno od listy
        String subtitleText = "Dzisiejsze zajęcia";
        try {
            ensureSessionFromPrefs(context);

            PlanRepository repo = new PlanRepository();
            PlanRepository.PlanResult result = repo.loadPlan("day", today);

            PlanRepository.DayColumn todayCol = null;
            if (result.dayColumns != null) {
                for (PlanRepository.DayColumn col : result.dayColumns) {
                    if (today.equals(col.date)) {
                        todayCol = col;
                        break;
                    }
                }
            }

            if (todayCol == null || todayCol.events == null) {
                subtitleText = "Dziś brak zajęć";
            } else {
                SharedPreferences planPrefs =
                        context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                Set<String> hiddenSubjectKeys = planPrefs.getStringSet(
                        KEY_FILTER_HIDDEN,
                        new HashSet<>()
                );

                List<PlanRepository.PlanEventUi> allEvents = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : todayCol.events) {
                    if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                            && hiddenSubjectKeys.contains(ev.subjectKey)) {
                        continue;
                    }
                    allEvents.add(ev);
                }

                if (allEvents.isEmpty()) {
                    subtitleText = "Dziś brak zajęć (po filtrach)";
                } else {
                    Collections.sort(allEvents, (a, b) -> Integer.compare(a.startMin, b.startMin));

                    LocalTime now = LocalTime.now();
                    int nowMin = now.getHour() * 60 + now.getMinute();

                    // tylko przyszłe / trwające
                    List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : allEvents) {
                        if (ev.endMin > nowMin) {
                            upcoming.add(ev);
                        }
                    }

                    if (upcoming.isEmpty()) {
                        subtitleText = "Dziś brak dalszych zajęć";
                    } else {
                        PlanRepository.PlanEventUi next = upcoming.get(0);
                        if (next.startMin <= nowMin) {
                            subtitleText = "Zajęcia w trakcie";
                        } else {
                            int diffMin = next.startMin - nowMin;
                            int h = diffMin / 60;
                            int m = diffMin % 60;

                            StringBuilder sb = new StringBuilder("Najbliższe za ");
                            if (h > 0) {
                                sb.append(h).append(" h");
                                if (m > 0) sb.append(" ").append(m).append(" min");
                            } else {
                                sb.append(m).append(" min");
                            }
                            subtitleText = sb.toString();
                        }
                    }
                }
            }

        } catch (Exception ignored) {
            // w razie błędu zostanie domyślne "Dzisiejsze zajęcia"
        }

        views.setTextViewText(R.id.widgetSubtitle, subtitleText);

        // kliknięcie w cały widget -> PlanActivity
        Intent openIntent = new Intent(context, PlanActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        // kliknięcie w ikonkę refresh -> broadcast ACTION_REFRESH
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

        // kliknięcie w pojedynczy wiersz listy -> PlanActivity (szablon)
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
}
