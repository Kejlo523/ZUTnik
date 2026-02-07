package pl.kejlo.mzutv2.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class WearSyncReceiver extends BroadcastReceiver {
    private static final String TAG = "MZUTWearSync/PHONE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Log.d(TAG, "WearSyncReceiver: onReceive action=" + intent.getAction());
        if (!WearSyncManager.isAutoSyncEnabled(context)) {
            Log.d(TAG, "WearSyncReceiver: auto sync disabled");
            return;
        }
        WearSyncManager.syncNowAsync(context.getApplicationContext());
    }
}
