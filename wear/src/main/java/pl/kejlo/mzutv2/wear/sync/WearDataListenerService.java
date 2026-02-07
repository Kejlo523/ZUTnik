package pl.kejlo.mzutv2.wear.sync;

import android.content.Intent;
import android.util.Log;
import android.util.SparseArray;

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
import pl.kejlo.mzutv2.wear.tile.PlanTileService;

import java.util.Arrays;
import java.util.zip.CRC32;

public class WearDataListenerService extends WearableListenerService {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final Object TRANSFER_LOCK = new Object();
    private static final TransferState TRANSFER_STATE = new TransferState();

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
                    Log.d(TAG, "onDataChanged: payload bytes=" +
                            (payload != null ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0));
                    WearSnapshotStore.save(this, payload);
                    WearSnapshotStore.setProgress(this, 100,
                            getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_received));
                    requestComplicationUpdate();
                    requestTileUpdate();
                    Intent updated = new Intent(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
                    updated.setPackage(getPackageName());
                    sendBroadcast(updated);
                    Intent progress = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
                    progress.setPackage(getPackageName());
                    sendBroadcast(progress);
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
        Log.d(TAG, "onMessageReceived: path=" + messageEvent.getPath() +
                " bytes=" + (messageEvent.getData() != null ? messageEvent.getData().length : 0));
        if (WearSyncConstants.PATH_SYNC_APPROVE.equals(messageEvent.getPath())) {
            Log.d(TAG, "onMessageReceived: sync_approve");
            WearSnapshotStore.setProgress(this, 20,
                    getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_approved));
            broadcastProgress();
            sendReadyToPhone(messageEvent.getSourceNodeId());
            return;
        }
        if (WearSyncConstants.PATH_SYNC_DECLINE.equals(messageEvent.getPath())) {
            Log.d(TAG, "onMessageReceived: sync_decline");
            WearSnapshotStore.setProgress(this, 0,
                    getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_declined));
            broadcastProgress();
            return;
        }
        if (WearSyncConstants.PATH_SYNC_START.equals(messageEvent.getPath())) {
            DataMap map = DataMap.fromByteArray(messageEvent.getData());
            int totalChunks = map.getInt(WearSyncConstants.KEY_TOTAL_CHUNKS, 0);
            int totalBytes = map.getInt(WearSyncConstants.KEY_TOTAL_BYTES, 0);
            long checksum = map.getLong(WearSyncConstants.KEY_CHECKSUM, 0L);
            synchronized (TRANSFER_LOCK) {
                TRANSFER_STATE.reset(totalChunks, totalBytes, checksum);
            }
            Log.d(TAG, "onMessageReceived: sync_start chunks=" + totalChunks + " bytes=" + totalBytes);
            WearSnapshotStore.setProgress(this, 25,
                    getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_receiving));
            broadcastProgress();
            return;
        }
        if (WearSyncConstants.PATH_SYNC_CHUNK.equals(messageEvent.getPath())) {
            DataMap map = DataMap.fromByteArray(messageEvent.getData());
            int index = map.getInt(WearSyncConstants.KEY_CHUNK_INDEX, -1);
            int total = map.getInt(WearSyncConstants.KEY_TOTAL_CHUNKS, 0);
            byte[] part = map.getByteArray(WearSyncConstants.KEY_CHUNK_BYTES);
            if (index < 0 || part == null) {
                return;
            }
            boolean completed = false;
            int received = 0;
            int totalChunks = 0;
            synchronized (TRANSFER_LOCK) {
                if (TRANSFER_STATE.totalChunks <= 0 && total > 0) {
                    TRANSFER_STATE.reset(total, 0, 0L);
                }
                TRANSFER_STATE.addChunk(index, part);
                received = TRANSFER_STATE.receivedChunks;
                totalChunks = TRANSFER_STATE.totalChunks;
                completed = TRANSFER_STATE.isComplete();
            }
            int pct = totalChunks > 0 ? 25 + (int) Math.round(65.0 * received / totalChunks) : 25;
            WearSnapshotStore.setProgress(this, pct,
                    getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_receiving));
            broadcastProgress();
            if (completed) {
                handleTransferComplete();
            }
            return;
        }
        if (WearSyncConstants.PATH_SYNC_END.equals(messageEvent.getPath())) {
            Log.d(TAG, "onMessageReceived: sync_end");
            handleTransferComplete();
            return;
        }
        if (WearSyncConstants.PATH_SYNC_PROGRESS.equals(messageEvent.getPath())) {
            DataMap map = DataMap.fromByteArray(messageEvent.getData());
            int progress = map.getInt(WearSyncConstants.KEY_PROGRESS, 0);
            String status = map.getString(WearSyncConstants.KEY_STATUS, "");
            Log.d(TAG, "onMessageReceived: progress=" + progress + " status=" + status);
            WearSnapshotStore.setProgress(this, progress, status);
            Intent progressIntent = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
            progressIntent.setPackage(getPackageName());
            sendBroadcast(progressIntent);
            return;
        }
        if (WearSyncConstants.PATH_PING.equals(messageEvent.getPath())) {
            try {
                Wearable.getMessageClient(this)
                        .sendMessage(messageEvent.getSourceNodeId(),
                                WearSyncConstants.PATH_PONG,
                                messageEvent.getData() != null ? messageEvent.getData() : new byte[0])
                        .addOnSuccessListener(r -> Log.d(TAG, "onMessageReceived: pong sent"))
                        .addOnFailureListener(e -> Log.e(TAG, "onMessageReceived: pong failed", e));
            } catch (Exception e) {
                Log.e(TAG, "onMessageReceived: pong failed", e);
            }
            return;
        }
        if (WearSyncConstants.PATH_PLAN_SNAPSHOT_MSG.equals(messageEvent.getPath())) {
            try {
                String payload = new String(messageEvent.getData(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "onMessageReceived: snapshot bytes=" +
                        (payload != null ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0));
                WearSnapshotStore.save(this, payload);
                WearSnapshotStore.setProgress(this, 100,
                        getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_received));
                requestComplicationUpdate();
                requestTileUpdate();
                Intent updated = new Intent(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
                updated.setPackage(getPackageName());
                sendBroadcast(updated);
                Intent progressIntent = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
                progressIntent.setPackage(getPackageName());
                sendBroadcast(progressIntent);
                sendAckToPhone(messageEvent.getSourceNodeId());
            } catch (Exception ignored) {
                Log.e(TAG, "onMessageReceived: snapshot parse failed", ignored);
            }
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
        } catch (Exception ignored) {
            Log.e(TAG, "requestComplicationUpdate: failed", ignored);
        }
    }

    private void requestTileUpdate() {
        try {
            TileService.getUpdater(this).requestUpdate(PlanTileService.class);
            Log.d(TAG, "requestTileUpdate: ok");
        } catch (Exception ignored) {
            Log.e(TAG, "requestTileUpdate: failed", ignored);
        }
    }

    private void broadcastProgress() {
        Intent progressIntent = new Intent(WearSyncConstants.ACTION_SYNC_PROGRESS);
        progressIntent.setPackage(getPackageName());
        sendBroadcast(progressIntent);
    }

    private void sendReadyToPhone(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        try {
            Wearable.getMessageClient(this)
                    .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_READY, new byte[0])
                    .addOnSuccessListener(r -> Log.d(TAG, "sendReadyToPhone: ok"))
                    .addOnFailureListener(e -> Log.e(TAG, "sendReadyToPhone: fail", e));
        } catch (Exception e) {
            Log.e(TAG, "sendReadyToPhone: failed", e);
        }
    }

    private void handleTransferComplete() {
        byte[] assembled;
        long checksum;
        synchronized (TRANSFER_LOCK) {
            assembled = TRANSFER_STATE.assemble();
            checksum = TRANSFER_STATE.checksum;
            TRANSFER_STATE.clear();
        }
        if (assembled == null || assembled.length == 0) {
            return;
        }
        if (checksum != 0L) {
            long actual = computeCrc32(assembled);
            if (actual != checksum) {
                Log.e(TAG, "handleTransferComplete: checksum mismatch");
                WearSnapshotStore.setProgress(this, 0,
                        getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_checksum_error));
                broadcastProgress();
                return;
            }
        }
        String payload = new String(assembled, java.nio.charset.StandardCharsets.UTF_8);
        WearSnapshotStore.save(this, payload);
        WearSnapshotStore.setProgress(this, 100,
                getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_received));
        requestComplicationUpdate();
        requestTileUpdate();
        Intent updated = new Intent(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
        updated.setPackage(getPackageName());
        sendBroadcast(updated);
        broadcastProgress();
        sendAckToPhone(null);
    }

    private void sendAckToPhone(String nodeId) {
        try {
            DataMap map = new DataMap();
            map.putInt(WearSyncConstants.KEY_PROGRESS, 100);
            map.putString(WearSyncConstants.KEY_STATUS,
                    getString(pl.kejlo.mzutv2.wear.R.string.wear_main_status_received));
            byte[] data = map.toByteArray();
            if (nodeId != null && !nodeId.isEmpty()) {
                Wearable.getMessageClient(this)
                        .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_ACK, data)
                        .addOnSuccessListener(r -> Log.d(TAG, "sendAckToPhone: ok " + nodeId))
                        .addOnFailureListener(e -> Log.e(TAG, "sendAckToPhone: fail " + nodeId, e));
                return;
            }
            for (Node n : Tasks.await(Wearable.getNodeClient(this).getConnectedNodes())) {
                Wearable.getMessageClient(this)
                        .sendMessage(n.getId(), WearSyncConstants.PATH_SYNC_ACK, data)
                        .addOnSuccessListener(r -> Log.d(TAG, "sendAckToPhone: ok " + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG, "sendAckToPhone: fail " + n.getDisplayName(), e));
            }
        } catch (Exception ignored) {
            Log.e(TAG, "sendAckToPhone: failed", ignored);
        }
    }

    private static long computeCrc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static final class TransferState {
        int totalChunks = 0;
        int totalBytes = 0;
        long checksum = 0L;
        int receivedChunks = 0;
        int receivedBytes = 0;
        SparseArray<byte[]> parts = new SparseArray<>();

        void reset(int totalChunks, int totalBytes, long checksum) {
            this.totalChunks = Math.max(0, totalChunks);
            this.totalBytes = Math.max(0, totalBytes);
            this.checksum = checksum;
            this.receivedChunks = 0;
            this.receivedBytes = 0;
            this.parts.clear();
        }

        void clear() {
            reset(0, 0, 0L);
        }

        void addChunk(int index, byte[] data) {
            if (index < 0 || data == null) {
                return;
            }
            if (parts.get(index) != null) {
                return;
            }
            parts.put(index, data);
            receivedChunks += 1;
            receivedBytes += data.length;
        }

        boolean isComplete() {
            return totalChunks > 0 && receivedChunks >= totalChunks;
        }

        byte[] assemble() {
            if (!isComplete()) {
                return null;
            }
            int expectedSize = totalBytes > 0 ? totalBytes : receivedBytes;
            byte[] all = new byte[expectedSize];
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] part = parts.get(i);
                if (part == null) {
                    return null;
                }
                if (offset + part.length > all.length) {
                    all = Arrays.copyOf(all, offset + part.length);
                }
                System.arraycopy(part, 0, all, offset, part.length);
                offset += part.length;
            }
            if (offset != all.length) {
                all = Arrays.copyOf(all, offset);
            }
            return all;
        }
    }
}
