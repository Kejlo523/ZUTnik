package pl.kejlo.mzutv2;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.widget.RemoteViews;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;

public class PlanDayWidgetProvider extends AppWidgetProvider {

    private static final String PREFS_NAME = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";
    private static final String ACTION_REFRESH = "pl.kejlo.mzutv2.PLAN_WIDGET_REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_REFRESH.equals(intent.getAction())) {
            forceUpdateAll(context);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context,
                                          AppWidgetManager manager,
                                          int appWidgetId,
                                          Bundle newOptions) {
        updateWidgetAsync(context, manager, appWidgetId);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidgetAsync(ctx, mgr, id);
    }

    public static void forceUpdateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, PlanDayWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(widget);
        for (int id : ids) updateWidgetAsync(context, mgr, id);
    }

    private static boolean shouldHideInWidget(Context ctx, PlanRepository.PlanEventUi ev) {
        if (ev == null || ev.subjectKey == null) return false;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> hidden = prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());
        return hidden.contains(ev.subjectKey);
    }

    private static String colorHexForType(String typeClass) {
        if (typeClass == null) typeClass = "";

        switch (typeClass) {
            case "week-event-type-lecture": return "#1E3A8A";
            case "week-event-type-lab": return "#7C3AED";
            case "week-event-type-auditory": return "#0F766E";
            case "week-event-type-exam": return "#B91C1C";
            case "week-event-type-remote": return "#2563EB";
            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake":
                return "#16A34A";
            default:
                return "#4B5563";
        }
    }

    private static void updateWidgetAsync(Context context,
                                          AppWidgetManager appWidgetManager,
                                          int appWidgetId) {

        RemoteViews loading = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);
        loading.setTextViewText(R.id.widgetContent, "Ładowanie…");
        appWidgetManager.updateAppWidget(appWidgetId, loading);

        new AsyncTask<Void, Void, String>() {

            LocalDate today;
            String subtitleText = "Dzisiejsze zajęcia";
            int totalUpcoming = 0;
            int shownLimit = 4; // dynamicznie zmieniany

            @Override
            protected String doInBackground(Void... voids) {

                try {
                    today = LocalDate.now();
                    LocalTime now = LocalTime.now();
                    int nowMin = now.getHour() * 60 + now.getMinute();

                    // pobieramy wysokość widgetu
                    Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
                    int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);

                    // progi wysokości → liczba elementów
                    if (height < 80) shownLimit = 1;
                    else if (height < 260) shownLimit = 2;
                    else if (height < 280) shownLimit = 3;
                    else if (height < 320) shownLimit = 4;
                    else if (height < 380) shownLimit = 6;
                    else shownLimit = 10;

                    PlanRepository repo = new PlanRepository();
                    PlanRepository.PlanResult result =
                            repo.loadPlan("day", today);

                    if (result.dayColumns == null || result.dayColumns.isEmpty()) {
                        subtitleText = "Brak planu na dziś";
                        return "Brak danych";
                    }

                    PlanRepository.DayColumn col = result.dayColumns.get(0);
                    if (col.events == null || col.events.isEmpty()) {
                        subtitleText = "Brak zajęć na dziś";
                        return "Brak zajęć 🙂";
                    }

                    StringBuilder sb = new StringBuilder();

                    int shown = 0;
                    PlanRepository.PlanEventUi nextEvent = null;
                    int nextDelta = 0;
                    boolean ongoing = false;

                    for (PlanRepository.PlanEventUi ev : col.events) {
                        if (ev == null) continue;
                        if (shouldHideInWidget(context, ev)) continue;

                        if (ev.endMin <= nowMin) continue;

                        totalUpcoming++;

                        if (nextEvent == null) {
                            nextEvent = ev;
                            if (ev.startMin <= nowMin) {
                                ongoing = true;
                                nextDelta = 0;
                            } else {
                                nextDelta = ev.startMin - nowMin;
                            }
                        }

                        if (shown < shownLimit) {
                            String color = colorHexForType(ev.typeClass);
                            String title = ev.title == null ? "Zajęcia" : ev.title;

                            sb.append("<font color='")
                                    .append(color)
                                    .append("'>┃</font>&nbsp;<b>")
                                    .append(title)
                                    .append("</b><br/>");

                            StringBuilder line2 = new StringBuilder();
                            if (ev.startStr != null && ev.endStr != null)
                                line2.append(ev.startStr).append("–").append(ev.endStr);

                            if (ev.room != null && !ev.room.isEmpty()) {
                                if (line2.length() > 0) line2.append(" · ");
                                line2.append(ev.room);
                            }

                            sb.append("<font color='#020617'>┃</font>&nbsp;")
                                    .append(line2)
                                    .append("<br/><br/>");

                            shown++;
                        }
                    }

                    if (nextEvent != null) {
                        if (ongoing) {
                            subtitleText = "Najbliższe zajęcia: trwają";
                        } else {
                            if (nextDelta < 60)
                                subtitleText = "Najbliższe za " + nextDelta + " min";
                            else
                                subtitleText = "Najbliższe za " +
                                        (nextDelta / 60) + " h " + (nextDelta % 60) + " min";
                        }
                    }

                    if (totalUpcoming > shown) {
                        sb.append("<b>+ ").append(totalUpcoming - shown).append(" więcej</b>");
                    }

                    String resultText = sb.toString().trim();
                    if (resultText.isEmpty()) resultText = "Brak zajęć 🙂";

                    return resultText;

                } catch (Exception e) {
                    subtitleText = "Błąd ładowania";
                    return "Błąd ładowania planu";
                }
            }

            @Override
            protected void onPostExecute(String html) {
                Locale pl = new Locale("pl", "PL");
                String dateStr = today.getDayOfMonth() + " " +
                        today.getMonth().getDisplayName(TextStyle.FULL, pl) +
                        " " + today.getYear();

                RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day);
                v.setTextViewText(R.id.widgetDate, dateStr);
                v.setTextViewText(R.id.widgetSubtitle, subtitleText);

                CharSequence styled =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY) :
                                Html.fromHtml(html);

                v.setTextViewText(R.id.widgetContent, styled);

                // klik otwiera plan
                Intent open = new Intent(context, PlanActivity.class);
                PendingIntent piOpen = PendingIntent.getActivity(
                        context, appWidgetId, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                v.setOnClickPendingIntent(R.id.widgetContent, piOpen);
                v.setOnClickPendingIntent(R.id.widgetDate, piOpen);
                v.setOnClickPendingIntent(R.id.widgetSubtitle, piOpen);

                // ikona odświeżania
                Intent refresh = new Intent(context, PlanDayWidgetProvider.class);
                refresh.setAction(ACTION_REFRESH);
                PendingIntent piRefresh = PendingIntent.getBroadcast(
                        context, appWidgetId, refresh,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                v.setOnClickPendingIntent(R.id.widgetRefresh, piRefresh);

                appWidgetManager.updateAppWidget(appWidgetId, v);
            }

        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
