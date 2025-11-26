package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RemoteViewsService dla ListView w widgetcie planu dnia.
 * - wczytuje sesję z SharedPreferences (MzutSession.initializeFromPreferences),
 * - respektuje filtry z PlanActivity,
 * - wyrzuca zajęcia, które już się skończyły (dla DZISIAJ),
 * - jeśli na dzisiaj nie ma już nic -> pokazuje zajęcia JUTRO.
 */
public class PlanDayWidgetService extends RemoteViewsService {

    // prefs planu (jak w PlanActivity)
    private static final String PREFS_PLAN        = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanDayFactory(getApplicationContext());
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

    private static class PlanDayFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<PlanRepository.PlanEventUi> events = new ArrayList<>();

        PlanDayFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            // nic – dane ładowane w onDataSetChanged
        }

        @Override
        public void onDataSetChanged() {
            final long token = Binder.clearCallingIdentity();
            try {
                events.clear();

                // 1. Przywróć sesję (po ubiciu procesu)
                boolean hasSession = ensureSessionFromPrefs(context);
                if (!hasSession) {
                    // brak zalogowanego użytkownika -> brak danych w widgetcie
                    return;
                }

                SharedPreferences planPrefs =
                        context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                Set<String> hiddenSubjectKeys = planPrefs.getStringSet(
                        KEY_FILTER_HIDDEN,
                        new HashSet<>()
                );

                // repo z contextem -> wspólny cache z apką
                PlanRepository repo = new PlanRepository(context.getApplicationContext());
                LocalDate today = LocalDate.now();

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

                List<PlanRepository.PlanEventUi> todayAll = new ArrayList<>();
                if (todayCol != null && todayCol.events != null) {
                    for (PlanRepository.PlanEventUi ev : todayCol.events) {
                        if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                                && hiddenSubjectKeys.contains(ev.subjectKey)) {
                            continue; // ukryty przez filtr
                        }
                        todayAll.add(ev);
                    }
                }

                if (!todayAll.isEmpty()) {
                    Collections.sort(todayAll, (a, b) -> Integer.compare(a.startMin, b.startMin));

                    // wyrzuć zajęcia, które się już skończyły – tylko dla DZISIEJSZEGO dnia
                    List<PlanRepository.PlanEventUi> upcomingToday = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : todayAll) {
                        if (ev.endMin > nowMin) {
                            upcomingToday.add(ev);
                        }
                    }

                    if (!upcomingToday.isEmpty()) {
                        events.addAll(upcomingToday);
                        return; // zostały zajęcia dziś – kończymy
                    }
                }

                // ---------- JESLI NA DZISIAJ NIC -> PROBA: JUTRO ----------
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
                    // jutro nie ma żadnych zajęć – lista pozostanie pusta
                    return;
                }

                List<PlanRepository.PlanEventUi> tomorrowAll = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : tomorrowCol.events) {
                    if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                            && hiddenSubjectKeys.contains(ev.subjectKey)) {
                        continue; // ukryty przez filtr
                    }
                    tomorrowAll.add(ev);
                }

                if (tomorrowAll.isEmpty()) {
                    // jutro wszystko przefiltrowane
                    return;
                }

                Collections.sort(tomorrowAll, (a, b) -> Integer.compare(a.startMin, b.startMin));

                // DLA JUTRA – NIE robimy filtra po czasie, bo wszystko i tak jest w przyszłości
                events.addAll(tomorrowAll);

            } catch (Exception e) {
                events.clear();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onDestroy() {
            events.clear();
        }

        @Override
        public int getCount() {
            return events.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= events.size()) {
                return null;
            }

            PlanRepository.PlanEventUi ev = events.get(position);

            RemoteViews rv = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_plan_day_item
            );

            // Linia 1: tytuł
            String title = ev.title != null ? ev.title : "";
            rv.setTextViewText(R.id.itemTitle, title);

            // Linia 2: godziny + sala
            StringBuilder line2 = new StringBuilder();
            line2.append(ev.startStr)
                    .append("–")
                    .append(ev.endStr);
            if (ev.room != null && !ev.room.isEmpty()) {
                line2.append("  · ").append(ev.room);
            }
            rv.setTextViewText(R.id.itemRoom, line2.toString());

            // kolor paska po lewej wg typu (jak w PlanActivity)
            int color = colorForType(ev.typeClass);
            rv.setInt(R.id.itemColorStrip, "setBackgroundColor", color);

            // kliknięcie w wiersz – po prostu otwiera PlanActivity
            Intent fillIntent = new Intent();
            rv.setOnClickFillInIntent(R.id.itemRoot, fillIntent);

            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null; // domyślna
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        private int colorForType(String typeClass) {
            if (typeClass == null) typeClass = "";

            switch (typeClass) {
                case "week-event-type-lecture":      // wykład
                    return 0xFF1E3A8A;
                case "week-event-type-lab":          // laboratoria
                    return 0xFF064E3B;
                case "week-event-type-auditory":     // ćwiczenia audytoryjne
                    return 0xFF3730A3;
                case "week-event-type-exam":         // egzamin
                    return 0xFF7F1D1D;
                case "week-event-type-cancelled":
                    return 0xFF374151;
                case "week-event-type-rector":
                    return 0xFF78350F;
                case "week-event-type-remote":
                    return 0xFF1E40AF;
                case "week-event-type-pass":
                case "week-event-type-pass-retake":
                case "week-event-type-pass-remote":
                case "week-event-type-pass-remote-retake":
                    return 0xFF166534;
                default:
                    return 0xFF1D4ED8;
            }
        }
    }
}
