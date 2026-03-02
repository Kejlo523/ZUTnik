package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlanChangeHistoryStore {

    private static final String PREFS_NAME = "mzut_plan_change_history";
    private static final String KEY_HISTORY = "plan_change_history_v1";
    private static final int MAX_ENTRIES = 80;

    public static final String TYPE_MOVED = "moved";
    public static final String TYPE_UPDATED = "updated";
    public static final String TYPE_CANCELLED = "cancelled";
    public static final String TYPE_ADDED = "added";
    public static final String TYPE_REMOVED = "removed";
    public static final String TYPE_REFRESHED = "refreshed";

    private PlanChangeHistoryStore() {
    }

    public static void append(Context context, List<ChangeRecord> newEntries) {
        if (context == null || newEntries == null || newEntries.isEmpty()) {
            return;
        }

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = scopedKey(context);

        List<ChangeRecord> merged = new ArrayList<>(newEntries.size() + MAX_ENTRIES);
        for (ChangeRecord entry : newEntries) {
            if (entry != null) {
                merged.add(entry);
            }
        }
        merged.addAll(read(context));

        if (merged.size() > MAX_ENTRIES) {
            merged = new ArrayList<>(merged.subList(0, MAX_ENTRIES));
        }

        JSONArray arr = new JSONArray();
        for (ChangeRecord entry : merged) {
            arr.put(entry.toJson());
        }
        prefs.edit().putString(key, arr.toString()).apply();
    }

    public static List<ChangeRecord> read(Context context) {
        List<ChangeRecord> out = new ArrayList<>();
        if (context == null) {
            return out;
        }

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(scopedKey(context), "[]");
        if (json == null || json.isEmpty()) {
            return out;
        }

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                ChangeRecord entry = ChangeRecord.fromJson(obj);
                if (entry != null) {
                    out.add(entry);
                }
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(scopedKey(context), "[]").apply();
    }

    private static String scopedKey(Context context) {
        String scope = NotificationSyncManager.buildCurrentSyncScope(context.getApplicationContext());
        return NotificationSyncManager.scopedPrefKey(KEY_HISTORY, scope);
    }

    public static final class ChangeRecord {
        public final String type;
        public final String title;
        public final String summary;
        public final long notifiedAt;
        public final String fromDate;
        public final int fromStartMin;
        public final int fromEndMin;
        public final String fromRoom;
        public final String fromGroup;
        public final String fromTeacher;
        public final String fromTypeLabel;
        public final String toDate;
        public final int toStartMin;
        public final int toEndMin;
        public final String toRoom;
        public final String toGroup;
        public final String toTeacher;
        public final String toTypeLabel;

        public ChangeRecord(
                String type,
                String title,
                String summary,
                long notifiedAt,
                String fromDate,
                int fromStartMin,
                int fromEndMin,
                String fromRoom,
                String fromGroup,
                String fromTeacher,
                String fromTypeLabel,
                String toDate,
                int toStartMin,
                int toEndMin,
                String toRoom,
                String toGroup,
                String toTeacher,
                String toTypeLabel) {
            this.type = safe(type);
            this.title = safe(title);
            this.summary = safe(summary);
            this.notifiedAt = notifiedAt;
            this.fromDate = safe(fromDate);
            this.fromStartMin = fromStartMin;
            this.fromEndMin = fromEndMin;
            this.fromRoom = safe(fromRoom);
            this.fromGroup = safe(fromGroup);
            this.fromTeacher = safe(fromTeacher);
            this.fromTypeLabel = safe(fromTypeLabel);
            this.toDate = safe(toDate);
            this.toStartMin = toStartMin;
            this.toEndMin = toEndMin;
            this.toRoom = safe(toRoom);
            this.toGroup = safe(toGroup);
            this.toTeacher = safe(toTeacher);
            this.toTypeLabel = safe(toTypeLabel);
        }

        public boolean hasFromSlot() {
            return !fromDate.isEmpty();
        }

        public boolean hasToSlot() {
            return !toDate.isEmpty();
        }

        public boolean isType(String expected) {
            return type.equalsIgnoreCase(expected == null ? "" : expected);
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("type", type);
                obj.put("title", title);
                obj.put("summary", summary);
                obj.put("notifiedAt", notifiedAt);
                obj.put("fromDate", fromDate);
                obj.put("fromStart", fromStartMin);
                obj.put("fromEnd", fromEndMin);
                obj.put("fromRoom", fromRoom);
                obj.put("fromGroup", fromGroup);
                obj.put("fromTeacher", fromTeacher);
                obj.put("fromTypeLabel", fromTypeLabel);
                obj.put("toDate", toDate);
                obj.put("toStart", toStartMin);
                obj.put("toEnd", toEndMin);
                obj.put("toRoom", toRoom);
                obj.put("toGroup", toGroup);
                obj.put("toTeacher", toTeacher);
                obj.put("toTypeLabel", toTypeLabel);
            } catch (Exception ignored) {
            }
            return obj;
        }

        static ChangeRecord fromJson(JSONObject obj) {
            if (obj == null) {
                return null;
            }
            try {
                return new ChangeRecord(
                        obj.optString("type", ""),
                        obj.optString("title", ""),
                        obj.optString("summary", ""),
                        obj.optLong("notifiedAt", 0L),
                        obj.optString("fromDate", ""),
                        obj.optInt("fromStart", 0),
                        obj.optInt("fromEnd", 0),
                        obj.optString("fromRoom", ""),
                        obj.optString("fromGroup", ""),
                        obj.optString("fromTeacher", ""),
                        obj.optString("fromTypeLabel", ""),
                        obj.optString("toDate", ""),
                        obj.optInt("toStart", 0),
                        obj.optInt("toEnd", 0),
                        obj.optString("toRoom", ""),
                        obj.optString("toGroup", ""),
                        obj.optString("toTeacher", ""),
                        obj.optString("toTypeLabel", ""));
            } catch (Exception e) {
                return null;
            }
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }

    static String safeForCompare(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
