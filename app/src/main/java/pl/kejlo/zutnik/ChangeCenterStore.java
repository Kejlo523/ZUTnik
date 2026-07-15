package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ChangeCenterStore {

    public static final String CATEGORY_GRADES = "grades";
    public static final String CATEGORY_PLAN = "plan";
    public static final String CATEGORY_FINANCE = "finance";

    private static final String PREFS_NAME = "zutnik_change_center";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_PLAN_HISTORY_MIGRATED = "plan_history_migrated_v1";
    private static final int MAX_EVENTS = 120;

    private ChangeCenterStore() {
    }

    public static void append(
            Context context,
            String category,
            String type,
            String title,
            String message,
            List<String> details) {
        if (context == null) {
            return;
        }
        Event event = new Event(
                UUID.randomUUID().toString(),
                safe(category),
                safe(type),
                safe(title),
                safe(message),
                details != null ? new ArrayList<>(details) : Collections.emptyList(),
                System.currentTimeMillis(),
                false);
        append(context, Collections.singletonList(event));
    }

    public static synchronized void append(Context context, List<Event> newEvents) {
        if (context == null || newEvents == null || newEvents.isEmpty()) {
            return;
        }
        List<Event> merged = new ArrayList<>(newEvents.size() + MAX_EVENTS);
        for (Event event : newEvents) {
            if (event != null) {
                merged.add(event);
            }
        }
        merged.addAll(read(context));
        if (merged.size() > MAX_EVENTS) {
            merged = new ArrayList<>(merged.subList(0, MAX_EVENTS));
        }
        write(context, merged);
    }

    @NonNull
    public static synchronized List<Event> read(Context context) {
        List<Event> result = new ArrayList<>();
        if (context == null) {
            return result;
        }
        SharedPreferences preferences = preferences(context);
        String json = SecureLocalData.readString(
                context,
                preferences,
                scopedKey(context),
                "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Event event = Event.fromJson(array.optJSONObject(i));
                if (event != null) {
                    result.add(event);
                }
            }
        } catch (Exception ignored) {
        }
        String migrationKey = scopedMigrationKey(context);
        if (!preferences.getBoolean(migrationKey, false)) {
            preferences.edit().putBoolean(migrationKey, true).apply();
            List<PlanChangeHistoryStore.ChangeRecord> legacy = PlanChangeHistoryStore.read(context);
            if (!legacy.isEmpty()) {
                List<Event> migrated = new ArrayList<>();
                for (PlanChangeHistoryStore.ChangeRecord record : legacy) {
                    migrated.add(new Event(
                            "legacy-plan-" + record.notifiedAt + "-" + migrated.size(),
                            CATEGORY_PLAN,
                            record.type,
                            record.title,
                            record.summary,
                            Collections.emptyList(),
                            record.notifiedAt,
                            true));
                }
                migrated.addAll(result);
                if (migrated.size() > MAX_EVENTS) {
                    migrated = new ArrayList<>(migrated.subList(0, MAX_EVENTS));
                }
                write(context, migrated);
                result.clear();
                result.addAll(migrated);
            }
        }
        return result;
    }

    public static synchronized int unreadCount(Context context) {
        int count = 0;
        for (Event event : read(context)) {
            if (!event.read) {
                count++;
            }
        }
        return count;
    }

    public static synchronized void markAllRead(Context context) {
        List<Event> events = read(context);
        boolean changed = false;
        List<Event> marked = new ArrayList<>(events.size());
        for (Event event : events) {
            if (!event.read) {
                changed = true;
                marked.add(event.withRead(true));
            } else {
                marked.add(event);
            }
        }
        if (changed) {
            write(context, marked);
        }
    }

    public static synchronized void clear(Context context) {
        write(context, Collections.emptyList());
    }

    private static void write(Context context, List<Event> events) {
        JSONArray array = new JSONArray();
        for (Event event : events) {
            array.put(event.toJson());
        }
        SecureLocalData.putString(
                context,
                preferences(context),
                scopedKey(context),
                array.toString());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String scopedKey(Context context) {
        String scope = NotificationSyncManager.buildCurrentSyncScope(context.getApplicationContext());
        return NotificationSyncManager.scopedPrefKey(KEY_EVENTS, scope);
    }

    private static String scopedMigrationKey(Context context) {
        String scope = NotificationSyncManager.buildCurrentSyncScope(context.getApplicationContext());
        return NotificationSyncManager.scopedPrefKey(KEY_PLAN_HISTORY_MIGRATED, scope);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Event {
        public final String id;
        public final String category;
        public final String type;
        public final String title;
        public final String message;
        public final List<String> details;
        public final long timestamp;
        public final boolean read;

        Event(
                String id,
                String category,
                String type,
                String title,
                String message,
                List<String> details,
                long timestamp,
                boolean read) {
            this.id = safe(id);
            this.category = safe(category);
            this.type = safe(type);
            this.title = safe(title);
            this.message = safe(message);
            this.details = details != null ? Collections.unmodifiableList(details) : Collections.emptyList();
            this.timestamp = timestamp;
            this.read = read;
        }

        Event withRead(boolean value) {
            return new Event(id, category, type, title, message, details, timestamp, value);
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("category", category);
                object.put("type", type);
                object.put("title", title);
                object.put("message", message);
                object.put("timestamp", timestamp);
                object.put("read", read);
                JSONArray detailArray = new JSONArray();
                for (String detail : details) {
                    if (detail != null && !detail.trim().isEmpty()) {
                        detailArray.put(detail.trim());
                    }
                }
                object.put("details", detailArray);
            } catch (Exception ignored) {
            }
            return object;
        }

        static Event fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            try {
                List<String> details = new ArrayList<>();
                JSONArray array = object.optJSONArray("details");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        String detail = array.optString(i, "").trim();
                        if (!detail.isEmpty()) {
                            details.add(detail);
                        }
                    }
                }
                return new Event(
                        object.optString("id", ""),
                        object.optString("category", ""),
                        object.optString("type", ""),
                        object.optString("title", ""),
                        object.optString("message", ""),
                        details,
                        object.optLong("timestamp", 0L),
                        object.optBoolean("read", false));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
