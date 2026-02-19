package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlanDayWidgetService extends RemoteViewsService {

    private static final String TAG = "mZUTv2-PlanWidgetSvc";
    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    public static final String EXTRA_DATE_ISO = "pl.kejlo.mzutv2.PLAN_WIDGET_DATE_ISO";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanDayFactory(getApplicationContext(), intent);
    }

    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private static class PlanDayFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<PlanRepository.PlanEventUi> events = new ArrayList<>();
        private LocalDate targetDate;

        PlanDayFactory(Context context, Intent intent) {
            this.context = context;
            updateDateFromIntent(intent);
        }

        private void updateDateFromIntent(Intent intent) {
            try {
                String dateStr = intent != null ? intent.getStringExtra(EXTRA_DATE_ISO) : null;
                if (dateStr != null) {
                    targetDate = LocalDate.parse(dateStr);
                } else {
                    targetDate = LocalDate.now();
                }
            } catch (Exception e) {
                targetDate = LocalDate.now();
            }
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            final long token = Binder.clearCallingIdentity();
            try {
                events.clear();

                if (!ensureSessionFromPrefs(context))
                    return;

                SharedPreferences planPrefs = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
                Set<String> hiddenSubjectKeys = planPrefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                PlanRepository.PlanResult result = repo.loadPlan("day", targetDate);

                if (result.dayColumns != null) {
                    for (PlanRepository.DayColumn col : result.dayColumns) {
                        if (targetDate.equals(col.date) && col.events != null) {
                            for (PlanRepository.PlanEventUi ev : col.events) {
                                if (ev.subjectKey != null && hiddenSubjectKeys.contains(ev.subjectKey))
                                    continue;
                                events.add(ev);
                            }
                            break;
                        }
                    }
                }

                if (events.isEmpty())
                    return;

                events.sort(Comparator.comparingInt(ev -> ev.startMin));

                LocalDate today = LocalDate.now();
                if (targetDate.equals(today)) {
                    LocalTime now = LocalTime.now();
                    int nowMin = now.getHour() * 60 + now.getMinute();

                    List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                    for (PlanRepository.PlanEventUi ev : events) {
                        if (ev.endMin > nowMin) {
                            upcoming.add(ev);
                        }
                    }
                    events.clear();
                    events.addAll(upcoming);
                }

            } catch (Exception e) {
                events.clear();
                Log.w(TAG, "Widget list refresh failed", e);
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
            if (position < 0 || position >= events.size())
                return null;

            PlanRepository.PlanEventUi ev = events.get(position);
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day_item_glass);

            rv.setTextViewText(R.id.itemTitle, ev.title != null ? ev.title : "");

            // Apply Theme Text Color
            int textColorPrimary = context.getColor(R.color.glass_text_primary); // Default White
            int textColorSecondary = context.getColor(R.color.glass_text_secondary);

            rv.setTextColor(R.id.itemTitle, textColorPrimary);
            rv.setTextColor(R.id.itemTime, textColorSecondary);
            rv.setTextColor(R.id.itemRoom, textColorSecondary);

            String start = ev.startStr != null ? ev.startStr : "";
            String end = ev.endStr != null ? ev.endStr : "";
            rv.setTextViewText(R.id.itemTime, start + " - " + end);

            String roomStr = "";
            if (ev.room != null && !ev.room.isEmpty())
                roomStr = ev.room;
            if (ev.group != null && !ev.group.isEmpty())
                roomStr += (roomStr.isEmpty() ? "" : " | ") + ev.group;

            rv.setTextViewText(R.id.itemRoom, roomStr);

            int color = ThemeManager.resolveEventColor(context, ev.typeClass);
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
    }
}
