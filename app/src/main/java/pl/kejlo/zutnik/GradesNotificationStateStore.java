package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GradesNotificationStateStore {

    interface ChangesConsumer {
        void accept(Map<String, String> current, List<String> addedKeys);
    }

    static final int NOTIFICATION_ID = 9101;

    private static final Object STATE_LOCK = new Object();
    private static final String PREFS_NAME = "zutnik_background_sync_cache";
    private static final String KEY_BASELINE_READY = "grades_baseline_ready_v2";
    private static final String KEY_BASELINE_JSON = "grades_baseline_json_v2";

    private GradesNotificationStateStore() {
    }

    static boolean isBaselineReady(Context context, String scope) {
        if (context == null) {
            return false;
        }
        synchronized (STATE_LOCK) {
            return prefs(context).getBoolean(scopedKey(KEY_BASELINE_READY, scope), false);
        }
    }

    static void markSeen(Context context, List<Grade> visibleGrades) {
        if (context == null) {
            return;
        }
        Map<String, String> current = GradesNotificationTracker.buildSnapshot(visibleGrades);
        if (current.isEmpty()) {
            return;
        }

        Context appContext = context.getApplicationContext();
        String scope = NotificationSyncManager.buildCurrentSyncScope(appContext);
        synchronized (STATE_LOCK) {
            SharedPreferences preferences = prefs(appContext);
            Set<String> merged = readSet(preferences.getString(
                    scopedKey(KEY_BASELINE_JSON, scope),
                    "[]"));
            merged.addAll(current.keySet());
            saveBaseline(appContext, scope, merged);
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID);
        }
    }

    static void processBackgroundSnapshot(
            Context context,
            String scope,
            List<Grade> grades,
            ChangesConsumer consumer) {
        if (context == null || consumer == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        Map<String, String> current = GradesNotificationTracker.buildSnapshot(grades);
        synchronized (STATE_LOCK) {
            SharedPreferences preferences = prefs(appContext);
            String readyKey = scopedKey(KEY_BASELINE_READY, scope);
            String jsonKey = scopedKey(KEY_BASELINE_JSON, scope);
            boolean ready = preferences.getBoolean(readyKey, false);
            Set<String> previous = readSet(preferences.getString(jsonKey, "[]"));

            if (!ready) {
                saveBaseline(appContext, scope, current.keySet());
                consumer.accept(current, Collections.emptyList());
                return;
            }

            // A temporary empty API response must not erase a valid baseline.
            if (current.isEmpty() && !previous.isEmpty()) {
                consumer.accept(current, Collections.emptyList());
                return;
            }

            List<String> addedKeys = GradesNotificationTracker.findAddedKeys(previous, current);
            saveBaseline(appContext, scope, current.keySet());

            // Keep comparison, notification dispatch and UI acknowledgement ordered.
            consumer.accept(current, addedKeys);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String scopedKey(String baseKey, String scope) {
        return NotificationSyncManager.scopedPrefKey(baseKey, scope);
    }

    private static void saveBaseline(Context context, String scope, Set<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) {
            array.put(key);
        }
        prefs(context).edit()
                .putString(scopedKey(KEY_BASELINE_JSON, scope), array.toString())
                .putBoolean(scopedKey(KEY_BASELINE_READY, scope), true)
                .apply();
    }

    private static Set<String> readSet(String json) {
        Set<String> result = new HashSet<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, null);
                if (value != null && !value.isEmpty()) {
                    result.add(value);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
