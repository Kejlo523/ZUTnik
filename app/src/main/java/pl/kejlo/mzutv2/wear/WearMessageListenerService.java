package pl.kejlo.mzutv2.wear;

import android.util.Log;
import android.content.Intent;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;

public class WearMessageListenerService extends WearableListenerService {

    private static final String TAG = "MZUTWearSync/PHONE";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent == null || messageEvent.getPath() == null) {
            Log.w(TAG, "onMessageReceived: null event/path");
            return;
        }
        String path = messageEvent.getPath();
        int size = messageEvent.getData() != null ? messageEvent.getData().length : 0;
        Log.d(TAG, "onMessageReceived: path=" + path + " bytes=" + size);
        if (WearSyncConstants.PATH_REQUEST_SYNC.equals(messageEvent.getPath())) {
            Log.d(TAG, "onMessageReceived: request_sync");
            String nodeId = messageEvent.getSourceNodeId();
            Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        String name = "";
                        if (nodes != null) {
                            for (com.google.android.gms.wearable.Node n : nodes) {
                                if (n != null && n.getId().equals(nodeId)) {
                                    name = n.getDisplayName();
                                    break;
                                }
                            }
                        }
                        WearSyncManager.setPendingRequest(this, nodeId, name);
                        Intent i = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
                        i.setPackage(getPackageName());
                        i.putExtra(WearSyncConstants.KEY_NODE_ID, nodeId != null ? nodeId : "");
                        i.putExtra(WearSyncConstants.KEY_NODE_NAME, name);
                        sendBroadcast(i);
                    })
                    .addOnFailureListener(e -> {
                        WearSyncManager.setPendingRequest(this, nodeId, "");
                        Intent i = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
                        i.setPackage(getPackageName());
                        i.putExtra(WearSyncConstants.KEY_NODE_ID, nodeId != null ? nodeId : "");
                        sendBroadcast(i);
                    });
            return;
        }
        if (WearSyncConstants.PATH_REQUEST_CANCEL.equals(messageEvent.getPath())) {
            Log.d(TAG, "onMessageReceived: request_cancel");
            WearSyncManager.clearPendingRequest(this);
            Intent cancel = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_CANCEL);
            cancel.setPackage(getPackageName());
            sendBroadcast(cancel);
            return;
        }
        if (WearSyncConstants.PATH_SYNC_READY.equals(messageEvent.getPath())) {
            String nodeId = messageEvent.getSourceNodeId();
            Log.d(TAG, "onMessageReceived: sync_ready nodeId=" + nodeId);
            WearSyncManager.clearPendingRequest(this);
            WearSyncManager.syncNowAsyncForNode(this, nodeId);
            return;
        }
        if (WearSyncConstants.PATH_SYNC_ACK.equals(messageEvent.getPath())) {
            int progress = 100;
            String status = getString(pl.kejlo.mzutv2.R.string.wear_sync_status_watch_received);
            try {
                DataMap map = DataMap.fromByteArray(messageEvent.getData());
                progress = map.getInt(WearSyncConstants.KEY_PROGRESS, 100);
                String msg = map.getString(WearSyncConstants.KEY_STATUS, "");
                if (msg != null && !msg.isEmpty()) {
                    status = msg;
                }
                Log.d(TAG, "onMessageReceived: sync_ack progress=" + progress + " status=" + status);
            } catch (Exception ignored) {
                Log.e(TAG, "onMessageReceived: sync_ack parse failed", ignored);
            }
            WearSyncManager.sendProgress(this, progress, status);
            return;
        }
        if (WearSyncConstants.PATH_PONG.equals(messageEvent.getPath())) {
            long ts = 0L;
            try {
                DataMap map = DataMap.fromByteArray(messageEvent.getData());
                ts = map.getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
            } catch (Exception ignored) {
            }
            if (ts <= 0L) {
                ts = System.currentTimeMillis();
            }
            WearSyncManager.setLastPongTs(this, ts);
            Log.d(TAG, "onMessageReceived: pong ts=" + ts);
            return;
        }
        Log.w(TAG, "onMessageReceived: unknown path=" + path);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (dataEvents == null) {
            return;
        }
        for (DataEvent event : dataEvents) {
            if (event == null || event.getDataItem() == null
                    || event.getDataItem().getUri() == null) {
                continue;
            }
            String path = event.getDataItem().getUri().getPath();
            if (!WearSyncConstants.PATH_REQUEST_SYNC.equals(path)) {
                if (WearSyncConstants.PATH_REQUEST_CANCEL.equals(path)) {
                    Log.d(TAG, "onDataChanged: request_cancel (data)");
                    WearSyncManager.clearPendingRequest(this);
                    Intent cancel = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_CANCEL);
                    cancel.setPackage(getPackageName());
                    sendBroadcast(cancel);
                }
                continue;
            }
            Log.d(TAG, "onDataChanged: request_sync (data)");
            String nodeId = "";
            String nodeName = "";
            try {
                DataMapItem item = DataMapItem.fromDataItem(event.getDataItem());
                if (item != null) {
                    item.getDataMap().getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
                }
            } catch (Exception ignored) {
            }
            WearSyncManager.setPendingRequest(this, nodeId, nodeName);
            Intent i = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
            i.setPackage(getPackageName());
            i.putExtra(WearSyncConstants.KEY_NODE_ID, nodeId);
            i.putExtra(WearSyncConstants.KEY_NODE_NAME, nodeName);
            sendBroadcast(i);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}
