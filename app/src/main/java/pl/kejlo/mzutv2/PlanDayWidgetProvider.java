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

/**
 * Provider widgetu "Plan dnia".
 *
 * Kluczowe założenia:
 *  - korzysta z MzutSession.initializeFromPreferences(...) → poprawna sesja po ubiciu procesu,
 *  - korzysta z PlanRepository(context) → wspólny cache planu z apką (plik + TTL),
 *  - w onUpdate() odświeża zarówno nagłówek, jak i listę (notifyAppWidgetViewDataChanged),
 *  - logika "dziś / jutro" jest spójna z serwisem.
 */
public class PlanDayWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH =
            "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("pl", "PL"));

    // formatter do stopki „Odświeżono:”
    private static final DateTimeFormatter TIME_LABEL =
            DateTimeFormatter.ofPattern("HH:mm");

    // prefs planu (filtry jak w PlanActivity)
    private static final String PREFS_PLAN        = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // Systemowe odświeżenie (np. co 30 min) – od razu odświeżamy i listę, i nagłówek
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
                // odśwież dane listy
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList);
                // zaktualizuj header (data + subtitle + stopka)
                updateOneWidget(context, mgr, appWidgetId);
            }
        }
    }

    /**
     * Inicjalizuje sesję z SharedPreferences (nowy MzutSession).
     *
     * @return true jeśli mamy userId + authKey (użytkownik zalogowany).
     */
    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
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

        LocalDate today = LocalDate.now();
        LocalDate dateToShow = today;
        boolean showingTomorrow = false;

        // domyślny podtytuł
        String subtitleText = "Dzisiejsze zajęcia";

        // init sesji – jeśli nie ma logowania, nie wylecimy z błędem, tylko widget pokaże pusto
        boolean hasSession = ensureSessionFromPrefs(context);

        if (hasSession) {
            try {
                // repo z contextem -> wspólny cache z apką
                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                LocalDate targetDate = today;

                // wczytanie filtrów
                Set<String> hiddenSubjectKeys = context
                        .getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE)
                        .getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                LocalTime now = LocalTime.now();
                int nowMin = now.getHour() * 60 + now.getMinute();

                // ---------- PROBA: DZISIAJ ----------
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
                        // są jeszcze zajęcia dzisiaj – zostajemy przy "dzisiaj"
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
                                if (m > 0) sb.append(" ").append(m).append(" min");
                            } else {
                                sb.append(m).append(" min");
                            }
                            subtitleText = sb.toString();
                        }
                    } else {
                        // były dziś zajęcia, ale wszystkie już się skończyły -> spróbuj jutro
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
                    // w ogóle brak zajęć dziś (także po filtrach) -> spróbuj jutro
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
                // w razie błędu zostanie domyślne "Dzisiejsze zajęcia" i dzisiejsza data
            }
        } else {
            // brak sesji – sugerujemy zalogowanie
            subtitleText = "Zaloguj się w aplikacji mZUT";
        }

        // nagłówek z datą (ew. (jutro))
        String dateLabel = dateToShow.format(DATE_LABEL);
        if (showingTomorrow) {
            dateLabel += " (jutro)";
        }
        views.setTextViewText(R.id.widgetDate, dateLabel);
        views.setTextViewText(R.id.widgetSubtitle, subtitleText);

        // stopka: kiedy ostatnio odświeżono widget
        LocalTime nowTime = LocalTime.now();
        views.setTextViewText(
                R.id.widgetLastRefresh,
                "Odświeżono: " + nowTime.format(TIME_LABEL)
        );

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

    /**
     * Próbuje przygotować subtitle dla jutra.
     * Zwraca true, jeśli jutro są jakiekolwiek zajęcia (po filtrach).
     * outSubtitle[0] – ustawia tekst podtytułu ("Jutrzejsze zajęcia" albo "Brak zajęć jutro").
     */
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
