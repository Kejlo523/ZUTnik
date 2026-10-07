package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class PlanDayWidgetService extends RemoteViewsService {

    private static final String TAG = "ZUTnik-PlanWidgetSvc";
    public static final String EXTRA_DATE_ISO = PlanDayWidgetProvider.EXTRA_DATE_ISO;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlanDayFactory(getApplicationContext(), intent);
    }

    static class PlanDayFactory implements RemoteViewsFactory {

        private final Context context;
        private volatile List<PlanRepository.PlanEventUi> events = Collections.emptyList();
        private final String owner;
        private LocalDate targetDate;

        PlanDayFactory(Context context, Intent intent) {
            this.context = context;
            String requestedOwner = intent != null ? intent.getStringExtra(PlanDayWidgetHelper.EXTRA_OWNER) : null;
            owner = requestedOwner != null ? requestedOwner : PlanDayWidgetHelper.owner(context);
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
                if (!isCurrentOwner()) {
                    events = Collections.emptyList();
                    return;
                }
                Set<String> hiddenSubjectKeys = PlanDayWidgetHelper.hiddenKeys(context);

                PlanRepository repo = new PlanRepository(context.getApplicationContext());

                PlanRepository.PlanResult result = repo.loadPlanFromCache("day", targetDate);

                if (result == null) return;
                LocalDate today = LocalDate.now();
                LocalTime now = LocalTime.now();
                List<PlanRepository.PlanEventUi> next = PlanDayWidgetHelper.upcomingEvents(
                        result, targetDate, today, now.getHour() * 60 + now.getMinute(), hiddenSubjectKeys);
                if (next.isEmpty() && !result.cachedRangeAvailable
                        && ZutnikSession.getInstance(context).isUsosLogin() && !events.isEmpty()) {
                    // A missing/corrupt cache is not a successful empty USOS response.
                    next = new ArrayList<>(events);
                    next.removeIf(event -> hiddenSubjectKeys.contains(event.subjectKey)
                            || (targetDate.equals(today) && event.endMin <= now.getHour() * 60 + now.getMinute()));
                }
                if (isCurrentOwner()) events = Collections.unmodifiableList(new ArrayList<>(next));
                else events = Collections.emptyList();

            } catch (Exception e) {
                if (!isCurrentOwner()) events = Collections.emptyList();
                Log.w(TAG, "Widget list refresh failed", e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onDestroy() {
            events = Collections.emptyList();
        }

        private boolean isCurrentOwner() {
            return !owner.isEmpty() && owner.equals(PlanDayWidgetHelper.owner(context));
        }

        @Override
        public int getCount() {
            return isCurrentOwner() ? events.size() : 0;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            List<PlanRepository.PlanEventUi> snapshot = events;
            if (!isCurrentOwner() || position < 0 || position >= snapshot.size())
                return null;

            PlanRepository.PlanEventUi ev = snapshot.get(position);
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_plan_day_item_glass);

            String type = shortType(ev.typeLabel, ev.typeClass);
            rv.setTextViewText(R.id.itemTitle, compactTitle(ev.title, type));

            // Apply Theme Text Color
            int textColorPrimary = context.getColor(R.color.glass_text_primary); // Default White
            int textColorSecondary = context.getColor(R.color.glass_text_secondary);

            rv.setTextColor(R.id.itemTitle, textColorPrimary);
            rv.setTextColor(R.id.itemTime, textColorSecondary);
            rv.setTextColor(R.id.itemRoom, textColorSecondary);
            rv.setTextColor(R.id.itemType, textColorSecondary);

            String start = ev.startStr != null ? ev.startStr : "";
            String end = ev.endStr != null ? ev.endStr : "";
            rv.setTextViewText(R.id.itemTime, start + " - " + end);
            rv.setTextViewText(R.id.itemType, type);

            String roomStr = "";
            if (ev.room != null && !ev.room.trim().isEmpty()) {
                roomStr = context.getString(R.string.plan_widget_room_format, ev.room.trim());
            }
            if (ev.group != null && !ev.group.trim().isEmpty()) {
                String group = context.getString(R.string.plan_widget_group_format, ev.group.trim());
                roomStr += (roomStr.isEmpty() ? "" : "  ·  ") + group;
            }

            rv.setTextViewText(R.id.itemRoom, roomStr);

            int color = ThemeManager.resolveEventColor(context, ev.typeClass);
            rv.setInt(R.id.itemColorStrip, "setBackgroundColor", color);

            Intent fillIntent = new Intent();
            fillIntent.putExtra("currentDate", targetDate.toString());
            fillIntent.putExtra("viewMode", "day");
            rv.setOnClickFillInIntent(R.id.itemRoot, fillIntent);

            return rv;
        }

        private String compactTitle(String title, String type) {
            String value = title != null ? title.trim() : "";
            if (type == null || type.isEmpty()) {
                return value;
            }
            String suffix = " (" + type + ")";
            if (value.length() >= suffix.length()
                    && value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length())) {
                return value.substring(0, value.length() - suffix.length()).trim();
            }
            return value;
        }

        private String shortType(String typeLabel, String typeClass) {
            String value = typeLabel != null ? typeLabel.trim().toLowerCase(java.util.Locale.ROOT) : "";
            if (value.contains("labor")) return "L";
            if (value.contains("wyk") || value.contains("lecture")) return "W";
            if (value.contains("ćw") || value.contains("cw") || value.contains("aud")) return "Ć";
            if (value.contains("projekt")) return "P";
            if (value.contains("semin")) return "S";
            String fallback = typeClass != null ? typeClass.trim().toUpperCase(java.util.Locale.ROOT) : "";
            return fallback.length() > 2 ? fallback.substring(0, 2) : fallback;
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
            return false;
        }
    }
}
