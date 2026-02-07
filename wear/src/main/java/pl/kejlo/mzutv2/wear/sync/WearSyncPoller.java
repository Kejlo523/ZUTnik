package pl.kejlo.mzutv2.wear.sync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import pl.kejlo.mzutv2.wear.R;
import pl.kejlo.mzutv2.wear.complications.PlanComplicationService;
import pl.kejlo.mzutv2.wear.tile.PlanTileService;

public final class WearSyncPoller {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final String PREFS = "wear_sync_poll";
    private static final String KEY_LAST_APPROVE_TS = "last_approve_ts";
    private static final String KEY_LAST_DECLINE_TS = "last_decline_ts";
    private static final String KEY_LAST_SNAPSHOT_TS = "last_snapshot_ts";

    private WearSyncPoller() {}

    public static void pollOnce(Context context) {
        if (context == null) {
            return;
        }
        try {
            pollPath(context, WearSyncConstants.PATH_SYNC_APPROVE, KEY_LAST_APPROVE_TS);
            pollPath(context, WearSyncConstants.PATH_SYNC_DECLINE, KEY_LAST_DECLINE_TS);
            pollPath(context, WearSyncConstants.PATH_PLAN_SNAPSHOT, KEY_LAST_SNAPSHOT_TS);
        } catch (Exception e) {
            Log.e(TAG, "pollOnce: failed", e);
        }
    }

    private static void pollPath(Context context, String path, String keyLastTs) throws Exception {
        android.net.Uri uri = new android.net.Uri.Builder()
                .scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(path)
                .build();
        DataItemBuffer items = Tasks.await(Wearable.getDataClient(context).getDataItems(uri));
        if (items == null) {
            return;
        }
        try {
            for (DataItem item : items) {
                if (item == null || item.getUri() == null) {
                    continue;
                }
                long ts = 0L;
                try {
                    DataMapItem mapItem = DataMapItem.fromDataItem(item);
                    if (mapItem != null) {
                        ts = mapItem.getDataMap().getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
                    }
                } catch (Exception ignored) {
                }
                if (isAlreadyHandled(context, keyLastTs, ts)) {
                    continue;
                }
                markHandled(context, keyLastTs, ts);
                if (WearSyncConstants.PATH_SYNC_APPROVE.equals(path)) {
                    Log.d(TAG, "pollOnce: approve");
                    WearSnapshotStore.setProgress(context, 20,
                            context.getString(R.string.wear_main_status_approved));
                    broadcastProgress(context);
                    sendReadyDataItem(context);
                } else if (WearSyncConstants.PATH_SYNC_DECLINE.equals(path)) {
                    Log.d(TAG, "pollOnce: decline");
                    WearSnapshotStore.setProgress(context, 0,
                            context.getString(R.string.wear_main_status_declined));
                    broadcastProgress(context);
                } else if (WearSyncConstants.PATH_PLAN_SNAPSHOT.equals(path)) {
                    Log.d(TAG, "pollOnce: snapshot");
                    String payload = "";
                    try {
                        DataMapItem mapItem = DataMapItem.fromDataItem(item);
                        if (mapItem != null) {
                            payload = mapItem.getDataMap()
                                    .getString(WearSyncConstants.KEY_PAYLOAD, "");
                        }
                    } catch (Exception ignored) {
                    }
                    WearSnapshotStore.save(context, payload);
                    WearSnapshotStore.setProgress(context, 100,
                            context.getString(R.string.wear_main_status_received));
                    requestComplicationUpdate(context);
                    requestTileUpdate(context);
                    Intent updated = new Intent(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
                    updated.setPackage(context.getPackageName());
                    context.sendBroadcast(updated);
                    broadcastProgress(context);
                    sendAckDataItem(context);
                }
                Wearable.getDataClient(context).deleteDataItems(item.getUri());
            }
        } finally {
            items.release();
        }
    }

    private static void sendReadyDataItem(Context context) {
        try {
            PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_SYNC_READY);
            req.getDataMap().putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
            Tasks.await(Wearable.getDataClient(context)
                    .putDataItem(req.asPutDataRequest().setUrgent()));
            Log.d(TAG, "sendReadyDataItem: ok");
        } catch (Exception e) {
            Log.e(TAG, "sendReadyDataItem: failed", e);
        }
    }

    private static void sendAckDataItem(Context context) {
        try {
            PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_SYNC_ACK);
            DataMap map = req.getDataMap();
            map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
            Tasks.await(Wearable.getDataClient(context)
                    .putDataItem(req.asPutDataRequest().setUrgent()));
            Log.d(TAG, "sendAckDataItem: ok");
        } catch (Exception e) {
            Log.e(TAG, "sendAckDataItem: failed", e);
        }
    }

    private static void broadcastProgress(Context context) {
        Intent progressIntent = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
        progressIntent.setPackage(context.getPackageName());
        context.sendBroadcast(progressIntent);
    }

    private static void requestComplicationUpdate(Context context) {
        try {
            ComplicationDataSourceUpdateRequester requester =
                    ComplicationDataSourceUpdateRequester.create(
                            context,
                            new android.content.ComponentName(context, PlanComplicationService.class));
            requester.requestUpdateAll();
        } catch (Exception ignored) {
        }
    }

    private static void requestTileUpdate(Context context) {
        try {
            TileService.getUpdater(context).requestUpdate(PlanTileService.class);
        } catch (Exception ignored) {
        }
    }

    private static boolean isAlreadyHandled(Context context, String key, long ts) {
        if (context == null || ts <= 0) {
            return false;
        }
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(key, 0L);
        return ts <= last;
    }

    private static void markHandled(Context context, String key, long ts) {
        if (context == null) {
            return;
        }
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(key, ts).apply();
    }
}
