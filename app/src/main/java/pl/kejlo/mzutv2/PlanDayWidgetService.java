package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Service for day-plan widget list.
public class PlanDayWidgetService extends RemoteViewsService {

    // Plan prefs (matches PlanActivity)
    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    // Must match PlanDayWidgetProvider
    public static final String EXTRA_DATE_ISO = "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        // Pass intent for EXTRA_DATE_ISO
        return new PlanDayFactory(getApplicationContext(), intent);
    }

    // Init session from prefs.
    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private static class PlanDayFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<PlanRepository.PlanEventUi> events = new ArrayList<>();

        private final LocalDate targetDate; // Target date.

        PlanDayFactory(Context context, Intent intent) {
            this.context = context;

            LocalDate tmpDate;
            try {
                String dateStr = intent != null ? intent.getStringExtra(EXTRA_DATE_ISO) : null;
                if (dateStr != null) {
                    tmpDate = LocalDate.parse(dateStr);
                } else {
                    tmpDate = LocalDate.now();
                }
            } catch (Exception e) {
                tmpDate = LocalDate.now();
            }
            this.targetDate = tmpDate;
        }

        @Override
        public void onCreate() {
            // No-op
        }

        @Override
        public void onDataSetChanged() {
            final long token = Binder.clearCallingIdentity();
            try {
                events.clear();

                // Restore session
                boolean hasSession = ensureSessionFromPrefs(context);
                if (!hasSession) {
                    return;
                }

                SharedPreferences planPrefs =
                        context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                Set<String> hiddenSubjectKeys = planPrefs.getStringSet(
                        KEY_FILTER_HIDDEN,
                        new HashSet<>()
                );

                // Repo with shared cache
                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                LocalDate today = LocalDate.now();
                boolean isToday = targetDate.equals(today);

                LocalTime now = LocalTime.now();
                int nowMin = now.getHour() * 60 + now.getMinute();

                // Load plan for target date
                PlanRepository.PlanResult result = repo.loadPlan("day", targetDate);

                PlanRepository.DayColumn dayCol = null;
                if (result.dayColumns != null) {
                    for (PlanRepository.DayColumn col : result.dayColumns) {
                        if (targetDate.equals(col.date)) {
                            dayCol = col;
                            break;
                        }
                    }
                }

                if (dayCol == null || dayCol.events == null) {
                    // No classes
                    return;
                }

                List<PlanRepository.PlanEventUi> all = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : dayCol.events) {
                    if (ev.subjectKey != null && !ev.subjectKey.isEmpty()
                            && hiddenSubjectKeys.contains(ev.subjectKey)) {
                        // Hidden by filter
                        continue;
                    }
                    all.add(ev);
                }

                if (all.isEmpty()) {
                    return;
                }

                Collections.sort(all, (a, b) -> Integer.compare(a.startMin, b.startMin));

                if (isToday) {
                    // Today: show upcoming only
                    List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : all) {
                        if (ev.endMin > nowMin) {
                            upcoming.add(ev);
                        }
                    }
                    events.addAll(upcoming);
                } else {
                    // Future: show all
                    events.addAll(all);
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

            // Title
            String title = ev.title != null ? ev.title : "";
            rv.setTextViewText(R.id.itemTitle, title);

            // Line 2: time + room
            StringBuilder line2 = new StringBuilder();
            line2.append(ev.startStr)
                    .append("–")
                    .append(ev.endStr);
            if (ev.room != null && !ev.room.isEmpty()) {
                line2.append("  · ").append(ev.room);
            }
            rv.setTextViewText(R.id.itemRoom, line2.toString());

            // Type color strip
            int color = colorForType(ev.typeClass);
            rv.setInt(R.id.itemColorStrip, "setBackgroundColor", color);

            Intent fillIntent = new Intent();
            rv.setOnClickFillInIntent(R.id.itemRoot, fillIntent);

            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
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

            int resId;
            switch (typeClass) {
                case "week-event-type-lecture":
                    resId = R.color.plan_event_lecture_bg;
                    break;
                case "week-event-type-lab":
                    resId = R.color.plan_event_lab_bg;
                    break;
                case "week-event-type-auditory":
                    resId = R.color.plan_event_auditory_bg;
                    break;
                case "week-event-type-exam":
                    resId = R.color.plan_event_exam_bg;
                    break;
                case "week-event-type-cancelled":
                    resId = R.color.plan_event_cancelled_bg;
                    break;
                case "week-event-type-rector":
                    resId = R.color.plan_event_rector_bg;
                    break;
                case "week-event-type-remote":
                    resId = R.color.plan_event_remote_bg;
                    break;
                case "week-event-type-pass":
                case "week-event-type-pass-retake":
                case "week-event-type-pass-remote":
                case "week-event-type-pass-remote-retake":
                    resId = R.color.plan_event_pass_bg;
                    break;
                default:
                    resId = R.color.plan_event_default_bg;
                    break;
            }

            return ContextCompat.getColor(context, resId);
        }
    }
}