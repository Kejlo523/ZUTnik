package pl.kejlo.mzutv2.wear.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;

public class WearSnapshotStore {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final String PREFS = "wear_snapshot";
    private static final String KEY = "plan_snapshot";
    private static final String KEY_LAST_SYNC = "plan_snapshot_last_sync";
    private static final String KEY_PROGRESS = "sync_progress";
    private static final String KEY_STATUS = "sync_status";

    public static void save(Context context, String json) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY, json != null ? json : "")
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply();
        Log.d(TAG, "save: bytes=" + (json != null ? json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0));
    }

    public static WearPlanSnapshot load(Context context) {
        if (context == null) {
            return new WearPlanSnapshot();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY, "");
        Log.d(TAG, "load: bytes=" + (json != null ? json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0));
        return WearPlanSnapshot.fromJson(json);
    }

    public static long getLastSyncMs(Context context) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_SYNC, 0L);
    }

    public static void setProgress(Context context, int progress, String status) {
        if (context == null) {
            return;
        }
        if (progress < 0) {
            progress = 0;
        } else if (progress > 100) {
            progress = 100;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_PROGRESS, progress)
                .putString(KEY_STATUS, status != null ? status : "")
                .apply();
        Log.d(TAG, "setProgress: " + progress + "% status=" + status);
    }

    public static int getProgress(Context context) {
        if (context == null) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_PROGRESS, 0);
    }

    public static String getStatus(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_STATUS, "");
    }
}
