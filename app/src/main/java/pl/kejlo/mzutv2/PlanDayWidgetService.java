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
 * - wczytuje sesję z SharedPreferences (działa po ubiciu procesu),
 * - respektuje filtry z PlanActivity,
 * - wyrzuca zajęcia, które już się skończyły.
 */
public class PlanDayWidgetService extends RemoteViewsService {

    // prefs logowania (jak w LoginActivity)
    private static final String PREFS_LOGIN   = "mzut_prefs";
    private static final String KEY_USER_ID   = "user_id";
    private static final String KEY_AUTH_KEY  = "auth_key";

    // prefs planu (jak w PlanActivity)
    private static final String PREFS_PLAN        = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanDayFactory(getApplicationContext());
    }

    private static void ensureSessionFromPrefs(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);
        String authKey = prefs.getString(KEY_AUTH_KEY, null);

        if (userId == null || authKey == null) {
            // użytkownik nie zalogowany – widget będzie pusty
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
                ensureSessionFromPrefs(context);

                PlanRepository repo = new PlanRepository();
                LocalDate today = LocalDate.now();
                PlanRepository.PlanResult result = repo.loadPlan("day", today);

                // 2. Znajdź kolumnę dla dzisiaj
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
                    return;
                }

                // 3. Filtr przedmiotów (PlanActivity)
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
                        // ukryty przez filtr
                        continue;
                    }
                    allEvents.add(ev);
                }

                if (allEvents.isEmpty()) {
                    return;
                }

                // sort po czasie
                Collections.sort(allEvents, (a, b) -> Integer.compare(a.startMin, b.startMin));

                // 4. Wyrzuć zajęcia, które się już skończyły
                LocalTime now = LocalTime.now();
                int nowMin = now.getHour() * 60 + now.getMinute();

                for (PlanRepository.PlanEventUi ev : allEvents) {
                    if (ev.endMin > nowMin) {
                        // jeszcze trwają albo będą
                        events.add(ev);
                    }
                }

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

            // kolor tła wg typu (jak w PlanActivity)
            int color = colorForType(ev.typeClass);
            rv.setInt(R.id.itemRoot, "setBackgroundColor", color);

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
