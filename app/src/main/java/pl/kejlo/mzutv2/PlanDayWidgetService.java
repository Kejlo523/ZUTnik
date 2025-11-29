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

// RemoteViewsService for the day-plan widget ListView.
public class PlanDayWidgetService extends RemoteViewsService {

    // Plan prefs (same as in PlanActivity)
    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanDayFactory(getApplicationContext());
    }

    // Initializes session from SharedPreferences (new MzutSession).
    // Returns true if we have userId + authKey (user is logged in).
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
            // No-op – data is loaded in onDataSetChanged
        }

        @Override
        public void onDataSetChanged() {
            final long token = Binder.clearCallingIdentity();
            try {
                events.clear();

                // Restore session after process kill
                boolean hasSession = ensureSessionFromPrefs(context);
                if (!hasSession) {
                    // No logged in user -> no data in widget
                    return;
                }

                SharedPreferences planPrefs =
                        context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                Set<String> hiddenSubjectKeys = planPrefs.getStringSet(
                        KEY_FILTER_HIDDEN,
                        new HashSet<>()
                );

                // Repository with context -> shared cache with the main app
                PlanRepository repo = new PlanRepository(context.getApplicationContext());
                LocalDate today = LocalDate.now();

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

                List<PlanRepository.PlanEventUi> todayAll = new ArrayList<>();
                if (todayCol != null && todayCol.events != null) {
                    for (PlanRepository.PlanEventUi ev : todayCol.events) {
                        if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                                && hiddenSubjectKeys.contains(ev.subjectKey)) {
                            // Hidden by filter
                            continue;
                        }
                        todayAll.add(ev);
                    }
                }

                if (!todayAll.isEmpty()) {
                    Collections.sort(todayAll, (a, b) -> Integer.compare(a.startMin, b.startMin));

                    // Drop events that already finished – only for today
                    List<PlanRepository.PlanEventUi> upcomingToday = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : todayAll) {
                        if (ev.endMin > nowMin) {
                            upcomingToday.add(ev);
                        }
                    }

                    if (!upcomingToday.isEmpty()) {
                        events.addAll(upcomingToday);
                        // There are still classes today – stop here
                        return;
                    }
                }

                // If there is nothing for today -> try tomorrow
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
                    // No classes tomorrow – list stays empty
                    return;
                }

                List<PlanRepository.PlanEventUi> tomorrowAll = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : tomorrowCol.events) {
                    if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                            && hiddenSubjectKeys.contains(ev.subjectKey)) {
                        // Hidden by filter
                        continue;
                    }
                    tomorrowAll.add(ev);
                }

                if (tomorrowAll.isEmpty()) {
                    // Everything filtered out for tomorrow
                    return;
                }

                Collections.sort(tomorrowAll, (a, b) -> Integer.compare(a.startMin, b.startMin));

                // For tomorrow we do not filter by time – everything is in the future anyway
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

            // Line 1: title
            String title = ev.title != null ? ev.title : "";
            rv.setTextViewText(R.id.itemTitle, title);

            // Line 2: time range + room
            StringBuilder line2 = new StringBuilder();
            line2.append(ev.startStr)
                    .append("–")
                    .append(ev.endStr);
            if (ev.room != null && !ev.room.isEmpty()) {
                line2.append("  · ").append(ev.room);
            }
            rv.setTextViewText(R.id.itemRoom, line2.toString());

            // Color strip on the left by type (same mapping as in PlanActivity)
            int color = colorForType(ev.typeClass);
            rv.setInt(R.id.itemColorStrip, "setBackgroundColor", color);

            // Row click -> just open PlanActivity
            Intent fillIntent = new Intent();
            rv.setOnClickFillInIntent(R.id.itemRoot, fillIntent);

            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            // Use default loading view
            return null;
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
            if (typeClass == null) {
                typeClass = "";
            }

            switch (typeClass) {
                case "week-event-type-lecture":
                    return 0xFF1E3A8A;
                case "week-event-type-lab":
                    return 0xFF064E3B;
                case "week-event-type-auditory":
                    return 0xFF3730A3;
                case "week-event-type-exam":
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
