package pl.kejlo.mzutv2.wear;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.android.gms.wearable.DataMap;
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

        // Auto-sync on request - no handshake needed
        if (WearSyncConstants.PATH_REQUEST_SYNC.equals(path)) {
            Log.d(TAG, "onMessageReceived: request_sync -> auto-sync");
            WearSyncManager.syncNowAsync(this);
            return;
        }

        // Ack from watch - sync completed successfully
        if (WearSyncConstants.PATH_SYNC_ACK.equals(path)) {
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

        // Pong - watch is alive
        if (WearSyncConstants.PATH_PONG.equals(path)) {
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
            // Auto-sync on data request as well (fallback)
            if (WearSyncConstants.PATH_REQUEST_SYNC.equals(path)) {
                Log.d(TAG, "onDataChanged: request_sync -> auto-sync");
                WearSyncManager.syncNowAsync(this);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}
