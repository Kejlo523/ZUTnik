package pl.kejlo.mzutv2.wear.sync;

import android.content.Intent;
import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import androidx.wear.tiles.TileService;

import pl.kejlo.mzutv2.wear.complications.PlanComplicationService;
import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.tile.PlanTileService;
import pl.kejlo.mzutv2.wear.util.WearLocaleManager;
import pl.kejlo.mzutv2.wear.R;

/**
 * Simplified WearOS data listener.
 * - Receives plan snapshots via DataClient (automatic sync) and MessageClient (immediate)
 * - No chunk transfer - not needed for small payloads
 * - Handles progress updates and ping/pong for diagnostics
 */
public class WearDataListenerService extends WearableListenerService {

    private static final String TAG = "MZUTWearSync/WEAR";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(WearLocaleManager.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getDataItem() == null || event.getDataItem().getUri() == null) {
                continue;
            }
            String path = event.getDataItem().getUri().getPath();
            Log.d(TAG, "onDataChanged: path=" + path);

            if (WearSyncConstants.PATH_PLAN_SNAPSHOT.equals(path)) {
                DataMapItem item = DataMapItem.fromDataItem(event.getDataItem());
                if (item != null) {
                    String payload = item.getDataMap().getString(WearSyncConstants.KEY_PAYLOAD, "");
                    int bytes = payload != null
                            ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            : 0;
                    Log.d(TAG, "onDataChanged: snapshot received, bytes=" + bytes);

                    WearPlanSnapshot snapshot = WearPlanSnapshot.fromJson(payload);
                    WearLocaleManager.updateOverrideFromSnapshot(this, snapshot);
                    WearSnapshotStore.save(this, payload);
                    WearSnapshotStore.setProgress(this, 100,
                            getString(R.string.wear_main_status_received));

                    requestComplicationUpdate();
                    requestTileUpdate();
                    broadcastSnapshotUpdated();
                    broadcastProgress();
                    sendAckToPhone(null);
                }
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        if (messageEvent == null || messageEvent.getPath() == null) {
            return;
        }
        String path = messageEvent.getPath();
        int size = messageEvent.getData() != null ? messageEvent.getData().length : 0;
        Log.d(TAG, "onMessageReceived: path=" + path + " bytes=" + size);

        // Direct snapshot message - immediate delivery
        if (WearSyncConstants.PATH_PLAN_SNAPSHOT_MSG.equals(path)) {
            try {
                String payload = new String(messageEvent.getData(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "onMessageReceived: snapshot received, bytes=" +
                        (payload != null ? payload.length() : 0));

                WearPlanSnapshot snapshot = WearPlanSnapshot.fromJson(payload);
                WearLocaleManager.updateOverrideFromSnapshot(this, snapshot);
                WearSnapshotStore.save(this, payload);
                WearSnapshotStore.setProgress(this, 100,
                        getString(R.string.wear_main_status_received));

                requestComplicationUpdate();
                requestTileUpdate();
                broadcastSnapshotUpdated();
                broadcastProgress();
                sendAckToPhone(messageEvent.getSourceNodeId());
            } catch (Exception e) {
                Log.e(TAG, "onMessageReceived: snapshot parse failed", e);
            }
            return;
        }

        // Progress update from phone
        if (WearSyncConstants.PATH_SYNC_PROGRESS.equals(path)) {
            try {
                DataMap map = DataMap.fromByteArray(messageEvent.getData());
                int progress = map.getInt(WearSyncConstants.KEY_PROGRESS, 0);
                String status = map.getString(WearSyncConstants.KEY_STATUS, "");
                Log.d(TAG, "onMessageReceived: progress=" + progress + " status=" + status);
                WearSnapshotStore.setProgress(this, progress, status);
                broadcastProgress();
            } catch (Exception ignored) {
            }
            return;
        }

        // Ping - respond with pong + battery level
        if (WearSyncConstants.PATH_PING.equals(path)) {
            try {
                // Get battery level
                android.os.BatteryManager bm =
                        (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                int battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);

                DataMap pongMap = new DataMap();
                pongMap.putInt(WearSyncConstants.KEY_BATTERY, battery);
                byte[] pongData = pongMap.toByteArray();

                Wearable.getMessageClient(this)
                        .sendMessage(messageEvent.getSourceNodeId(),
                                WearSyncConstants.PATH_PONG,
                                pongData)
                        .addOnSuccessListener(r -> Log.d(TAG, "onMessageReceived: pong sent with battery=" + battery))
                        .addOnFailureListener(e -> Log.e(TAG, "onMessageReceived: pong failed", e));
            } catch (Exception e) {
                Log.e(TAG, "onMessageReceived: pong failed", e);
            }
            return;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

    private void requestComplicationUpdate() {
        try {
            ComplicationDataSourceUpdateRequester requester =
                    ComplicationDataSourceUpdateRequester.create(
                            this,
                            new android.content.ComponentName(this, PlanComplicationService.class));
            requester.requestUpdateAll();
            Log.d(TAG, "requestComplicationUpdate: ok");
        } catch (Exception e) {
            Log.e(TAG, "requestComplicationUpdate: failed", e);
        }
    }

    private void requestTileUpdate() {
        try {
            TileService.getUpdater(this).requestUpdate(PlanTileService.class);
            Log.d(TAG, "requestTileUpdate: ok");
        } catch (Exception e) {
            Log.e(TAG, "requestTileUpdate: failed", e);
        }
    }

    private void broadcastSnapshotUpdated() {
        Intent updated = new Intent(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
        updated.setPackage(getPackageName());
        sendBroadcast(updated);
    }

    private void broadcastProgress() {
        Intent progressIntent = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
        progressIntent.setPackage(getPackageName());
        sendBroadcast(progressIntent);
    }

    private void sendAckToPhone(String nodeId) {
        try {
            DataMap map = new DataMap();
            map.putInt(WearSyncConstants.KEY_PROGRESS, 100);
            map.putString(WearSyncConstants.KEY_STATUS,
                    getString(R.string.wear_main_status_received));
            byte[] data = map.toByteArray();

            if (nodeId != null && !nodeId.isEmpty()) {
                Wearable.getMessageClient(this)
                        .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_ACK, data)
                        .addOnSuccessListener(r -> Log.d(TAG, "sendAckToPhone: ok " + nodeId))
                        .addOnFailureListener(e -> Log.e(TAG, "sendAckToPhone: fail " + nodeId, e));
                return;
            }

            // Fallback: send to all connected nodes
            for (Node n : Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())) {
                Wearable.getMessageClient(this)
                        .sendMessage(n.getId(), WearSyncConstants.PATH_SYNC_ACK, data)
                        .addOnSuccessListener(r -> Log.d(TAG, "sendAckToPhone: ok " + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG, "sendAckToPhone: fail " + n.getDisplayName(), e));
            }
        } catch (Exception e) {
            Log.e(TAG, "sendAckToPhone: failed", e);
        }
    }
}
