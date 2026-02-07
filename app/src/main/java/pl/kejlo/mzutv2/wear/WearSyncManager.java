package pl.kejlo.mzutv2.wear;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.kejlo.mzutv2.R;

/**
 * Simplified WearOS sync manager.
 * - No polling
 * - No handshake (approve/decline)
 * - Auto-sync when requested
 * - Uses DataClient for reliable sync
 */
public class WearSyncManager {

    private static final String TAG = "MZUTWearSync/PHONE";
    private static final String PREFS_WEAR = "wear_sync_prefs";
    private static final String KEY_LAST_PONG_TS = "last_pong_ts";
    private static final String KEY_LAST_PONG_BATTERY = "last_pong_battery";
    private static final String KEY_LAST_SYNC_TS = "wear_last_sync_ts";
    private static final String KEY_AUTO_SYNC = "wear_auto_sync_enabled";
    private static final String KEY_AUTO_SYNC_INTERVAL = "wear_auto_sync_interval";
    private static final long WATCH_STATUS_STALE_MS = 90_000L;
    private static final long PING_TIMEOUT_MS = 800L;
    private static final long PING_POLL_MS = 100L;
    private static final long DEFAULT_AUTO_SYNC_INTERVAL_MIN = 60L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 10_000L;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ============================================================
    // Compatibility stubs (no-ops for removed functionality)
    // ============================================================



    /** Schedule wear auto-sync (simplified - just syncs periodically) */
    public static void scheduleWearAutoSync(Context context) {
        if (context == null) return;
        if (!isAutoSyncEnabled(context)) return;
        // WorkManager would be better here
        syncNowAsync(context);
    }

    public static boolean isAutoSyncEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_SYNC, true);
    }

    public static long getAutoSyncIntervalMinutes() {
        return DEFAULT_AUTO_SYNC_INTERVAL_MIN;
    }

    // ============================================================
    // Public API
    // ============================================================

    public static void syncNowAsync(Context context) {
        if (context == null) {
            return;
        }
        executor.execute(() -> syncNowBlocking(context));
    }

    public static void syncNowBlocking(Context context) {
        if (context == null) {
            return;
        }
        Log.d(TAG, "syncNowBlocking: start");
        sendProgress(context, 15, context.getString(R.string.wear_sync_status_prepare));

        WearPlanSnapshot snapshot = WearPlanSnapshotBuilder.build(context);
        if (snapshot == null) {
            Log.w(TAG, "syncNowBlocking: snapshot null");
            sendProgress(context, 0, context.getString(R.string.wear_sync_status_no_data));
            return;
        }

        String payload = snapshot.toJson();
        int payloadBytes = payload != null
                ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                : 0;
        Log.d(TAG, "syncNowBlocking: payload bytes=" + payloadBytes);

        sendProgress(context, 40, context.getString(R.string.wear_sync_status_sending));

        // Send via DataClient with urgent flag for immediate sync
        try {
            PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_PLAN_SNAPSHOT);
            DataMap map = req.getDataMap();
            map.putString(WearSyncConstants.KEY_PAYLOAD, payload);
            map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());

            Tasks.await(Wearable.getDataClient(context)
                    .putDataItem(req.asPutDataRequest().setUrgent()));
            Log.d(TAG, "syncNowBlocking: DataItem sent");
            sendProgress(context, 70, context.getString(R.string.wear_sync_status_waiting_watch));
        } catch (Exception e) {
            Log.e(TAG, "syncNowBlocking: DataItem send failed", e);
            sendProgress(context, 0, context.getString(R.string.wear_sync_status_error));
            return;
        }

        // Also send via MessageClient for immediate delivery
        sendSnapshotMessage(context, payload);
    }

    public static long getLastSyncTimestamp(Context context) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_SYNC_TS, 0L);
    }

    public static void sendProgress(Context context, int progress, String status) {
        if (context == null) {
            return;
        }
        Log.d(TAG, "sendProgress: " + progress + "% status=" + status);

        if (progress >= 100) {
            setLastSyncTimestamp(context, System.currentTimeMillis());
        }

        sendLocalProgress(context, progress, status);
        sendProgressToWatch(context, progress, status);
    }

    public static void setLastPongTs(Context context, long ts) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_PONG_TS, ts).apply();
    }

    public static void setLastPongBattery(Context context, int battery) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_LAST_PONG_BATTERY, battery).apply();
    }
    
    public static int getLastPongBattery(Context context) {
         if (context == null) return -1;
         return context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE)
                 .getInt(KEY_LAST_PONG_BATTERY, -1);
    }

    // ============================================================
    // Watch Status
    // ============================================================

    public static void getWatchStatusAsync(Context context, WatchStatusCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        executor.execute(() -> {
            WatchStatus status = fetchWatchStatus(context);
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onStatus(status));
        });
    }

    public static void getWatchStatusWithPingAsync(Context context, WatchStatusCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        executor.execute(() -> {
            WatchStatus status = fetchWatchStatus(context);
            boolean pingOk = false;
            int distinctBattery = -1;
            
            if (status != null && status.connected) {
                // Reset battery in prefs to detect update? OR just read after ping
                // We trust pingWatchOnce waits for the pong which updates the prefs
                pingOk = pingWatchOnce(context, status.nodeId);
            }
            
            if (status != null && status.connected) {
                 if (pingOk) {
                     // Ping succeeded -> Get fresh battery from Pong
                     distinctBattery = getLastPongBattery(context);
                     // Update status with fresh battery and timestamp
                     status = new WatchStatus(true, true, status.name, distinctBattery,
                             System.currentTimeMillis(), status.nodeId, status.paired, status.tileSeenTimestamp);
                 } else {
                     // Ping failed
                     status = new WatchStatus(true, false, status.name, status.battery,
                             status.timestamp, status.nodeId, status.paired, status.tileSeenTimestamp);
                 }
            }
            
            final WatchStatus resultStatus = status;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onStatus(resultStatus));
        });
    }

    public interface WatchStatusCallback {
        void onStatus(WatchStatus status);
    }

    public static final class WatchStatus {
        public final boolean connected;
        public final boolean responsive;
        public final String name;
        public final int battery;
        public final long timestamp;
        public final String nodeId;
        public final boolean paired;
        public final long tileSeenTimestamp;

        public WatchStatus(boolean connected, String name, int battery, long timestamp,
                String nodeId, boolean paired, long tileSeenTimestamp) {
            this(connected, connected, name, battery, timestamp, nodeId, paired, tileSeenTimestamp);
        }

        public WatchStatus(boolean connected, boolean responsive, String name, int battery,
                long timestamp, String nodeId, boolean paired, long tileSeenTimestamp) {
            this.connected = connected;
            this.responsive = responsive;
            this.name = name != null ? name : "";
            this.battery = battery;
            this.timestamp = timestamp;
            this.nodeId = nodeId != null ? nodeId : "";
            this.paired = paired;
            this.tileSeenTimestamp = tileSeenTimestamp;
        }

        public static WatchStatus empty() {
            return new WatchStatus(false, "", -1, 0L, "", false, 0L);
        }
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private static void setLastSyncTimestamp(Context context, long ts) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_SYNC_TS, ts).apply();
    }

    private static long getLastPongTs(Context context) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_PONG_TS, 0L);
    }

    private static boolean pingWatchOnce(Context context, String nodeIdHint) {
        if (context == null) {
            return false;
        }
        String nodeId = nodeIdHint;
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = findConnectedNodeId(context);
        }
        if (nodeId == null || nodeId.isEmpty()) {
            return false;
        }

        long ts = System.currentTimeMillis();
        try {
            DataMap map = new DataMap();
            map.putLong(WearSyncConstants.KEY_TIMESTAMP, ts);
            byte[] data = map.toByteArray();
            Tasks.await(Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WearSyncConstants.PATH_PING, data));
        } catch (Exception e) {
            Log.e(TAG, "pingWatchOnce: send failed", e);
            return false;
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < PING_TIMEOUT_MS) {
            long pong = getLastPongTs(context);
            if (pong >= ts) {
                return true;
            }
            try {
                Thread.sleep(PING_POLL_MS);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return false;
    }

    private static String findConnectedNodeId(Context context) {
        try {
            List<Node> nodes = Tasks.await(
                    Wearable.getNodeClient(context).getConnectedNodes());
            if (nodes != null && !nodes.isEmpty()) {
                Node node = nodes.get(0);
                if (node != null) {
                    return node.getId();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static WatchStatus fetchWatchStatus(Context context) {
        long now = System.currentTimeMillis();
        Node reachableNode = null;
        Node connectedNode = null;

        try {
            List<Node> connectedNodes = Tasks.await(
                    Wearable.getNodeClient(context).getConnectedNodes());
            if (connectedNodes != null && !connectedNodes.isEmpty()) {
                for (Node n : connectedNodes) {
                    if (n != null) {
                        connectedNode = n;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            CapabilityInfo reachableInfo = Tasks.await(
                    Wearable.getCapabilityClient(context)
                            .getCapability(WearSyncConstants.CAPABILITY_WEAR_SYNC,
                                    CapabilityClient.FILTER_REACHABLE));
            if (reachableInfo != null && reachableInfo.getNodes() != null) {
                for (Node n : reachableInfo.getNodes()) {
                    if (n != null) {
                        reachableNode = n;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        boolean hasConnected = reachableNode != null || connectedNode != null;
        String fallbackName = "";
        String fallbackNodeId = "";
        if (reachableNode != null) {
            fallbackName = reachableNode.getDisplayName() != null
                    ? reachableNode.getDisplayName() : "";
            fallbackNodeId = reachableNode.getId() != null ? reachableNode.getId() : "";
        } else if (connectedNode != null) {
            fallbackName = connectedNode.getDisplayName() != null
                    ? connectedNode.getDisplayName() : "";
            fallbackNodeId = connectedNode.getId() != null ? connectedNode.getId() : "";
        }

        WatchStatus latest = null;
        long tileSeenTs = 0L;
        Node pairedNode = null;

        try {
            CapabilityInfo info = Tasks.await(
                    Wearable.getCapabilityClient(context)
                            .getCapability(WearSyncConstants.CAPABILITY_WEAR_SYNC,
                                    CapabilityClient.FILTER_ALL));
            if (info != null && info.getNodes() != null && !info.getNodes().isEmpty()) {
                for (Node n : info.getNodes()) {
                    if (n != null) {
                        pairedNode = n;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            android.net.Uri uri = new android.net.Uri.Builder()
                    .scheme(com.google.android.gms.wearable.PutDataRequest.WEAR_URI_SCHEME)
                    .path(WearSyncConstants.PATH_WATCH_STATUS)
                    .build();
            com.google.android.gms.wearable.DataItemBuffer items =
                    Tasks.await(Wearable.getDataClient(context).getDataItems(uri));
            if (items != null) {
                try {
                    for (com.google.android.gms.wearable.DataItem item : items) {
                        if (item == null || item.getUri() == null) continue;
                        com.google.android.gms.wearable.DataMapItem mapItem =
                                com.google.android.gms.wearable.DataMapItem.fromDataItem(item);
                        if (mapItem == null) continue;
                        com.google.android.gms.wearable.DataMap map = mapItem.getDataMap();
                        int battery = map.getInt(WearSyncConstants.KEY_BATTERY, -1);
                        String name = map.getString(WearSyncConstants.KEY_DEVICE_NAME, "");
                        long ts = map.getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
                        String nodeId = item.getUri().getHost();
                        if (latest == null || ts > latest.timestamp) {
                            latest = new WatchStatus(true, name, battery, ts, nodeId, true, 0L);
                        }
                    }
                } finally {
                    items.release();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchWatchStatus: failed", e);
        }

        try {
            android.net.Uri tileUri = new android.net.Uri.Builder()
                    .scheme(com.google.android.gms.wearable.PutDataRequest.WEAR_URI_SCHEME)
                    .path(WearSyncConstants.PATH_TILE_SEEN)
                    .build();
            com.google.android.gms.wearable.DataItemBuffer items =
                    Tasks.await(Wearable.getDataClient(context).getDataItems(tileUri));
            if (items != null) {
                try {
                    for (com.google.android.gms.wearable.DataItem item : items) {
                        if (item == null || item.getUri() == null) continue;
                        com.google.android.gms.wearable.DataMapItem mapItem =
                                com.google.android.gms.wearable.DataMapItem.fromDataItem(item);
                        if (mapItem == null) continue;
                        long ts = mapItem.getDataMap().getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
                        if (ts > tileSeenTs) {
                            tileSeenTs = ts;
                        }
                    }
                } finally {
                    items.release();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchWatchStatus: tileSeen failed", e);
        }

        String pairedName = "";
        String pairedNodeId = "";
        if (pairedNode != null) {
            pairedName = pairedNode.getDisplayName() != null ? pairedNode.getDisplayName() : "";
            pairedNodeId = pairedNode.getId() != null ? pairedNode.getId() : "";
        }

        if (!hasConnected) {
            if (pairedNode != null) {
                return new WatchStatus(false, pairedName, -1,
                        latest != null ? latest.timestamp : 0L, pairedNodeId, true, tileSeenTs);
            }
            if (latest != null) {
                return new WatchStatus(false, latest.name, latest.battery, latest.timestamp,
                        latest.nodeId, false, tileSeenTs);
            }
            return WatchStatus.empty();
        }

        String name = !fallbackName.isEmpty() ? fallbackName : pairedName;
        String nodeId = !fallbackNodeId.isEmpty() ? fallbackNodeId : pairedNodeId;
        boolean stale = latest != null && latest.timestamp > 0
                && (now - latest.timestamp) > WATCH_STATUS_STALE_MS;
        int battery = (latest != null && !stale) ? latest.battery : -1;
        long ts = latest != null ? latest.timestamp : 0L;
        return new WatchStatus(true, name, battery, ts, nodeId, pairedNode != null, tileSeenTs);
    }

    private interface NodeCallback {
        void onNodes(List<Node> nodes);
    }

    private static void withWatchNodes(Context context, NodeCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        Wearable.getCapabilityClient(context)
                .getCapability(WearSyncConstants.CAPABILITY_WEAR_SYNC, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> {
                    List<Node> nodes = new ArrayList<>();
                    if (info != null) {
                        Set<Node> capNodes = info.getNodes();
                        if (capNodes != null && !capNodes.isEmpty()) {
                            nodes.addAll(capNodes);
                        }
                    }
                    if (!nodes.isEmpty()) {
                        callback.onNodes(nodes);
                        return;
                    }
                    Wearable.getNodeClient(context).getConnectedNodes()
                            .addOnSuccessListener(callback::onNodes);
                })
                .addOnFailureListener(e -> {
                    Wearable.getNodeClient(context).getConnectedNodes()
                            .addOnSuccessListener(callback::onNodes);
                });
    }

    private static void sendLocalProgress(Context context, int progress, String status) {
        Intent i = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_PROGRESS);
        i.setPackage(context.getPackageName());
        i.putExtra(WearSyncConstants.KEY_PROGRESS, progress);
        i.putExtra(WearSyncConstants.KEY_STATUS, status);
        context.sendBroadcast(i);
    }

    private static void sendProgressToWatch(Context context, int progress, String status) {
        DataMap map = new DataMap();
        map.putInt(WearSyncConstants.KEY_PROGRESS, progress);
        map.putString(WearSyncConstants.KEY_STATUS, status != null ? status : "");
        byte[] data = map.toByteArray();
        withWatchNodes(context, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                return;
            }
            for (Node n : nodes) {
                Wearable.getMessageClient(context)
                        .sendMessage(n.getId(), WearSyncConstants.PATH_SYNC_PROGRESS, data)
                        .addOnSuccessListener(r -> Log.d(TAG,
                                "sendProgressToWatch: ok node=" + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG,
                                "sendProgressToWatch: fail node=" + n.getDisplayName(), e));
            }
        });
    }

    private static void sendSnapshotMessage(Context context, String payload) {
        if (context == null || payload == null) {
            return;
        }
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Log.d(TAG, "sendSnapshotMessage: bytes=" + data.length);
        withWatchNodes(context, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "sendSnapshotMessage: no nodes");
                return;
            }
            for (Node n : nodes) {
                Wearable.getMessageClient(context)
                        .sendMessage(n.getId(), WearSyncConstants.PATH_PLAN_SNAPSHOT_MSG, data)
                        .addOnSuccessListener(r -> Log.d(TAG,
                                "sendSnapshotMessage: ok node=" + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG,
                                "sendSnapshotMessage: fail node=" + n.getDisplayName(), e));
            }
            sendProgress(context, 85, context.getString(R.string.wear_sync_status_waiting_watch));
        });
    }
    // ============================================================
    // Watch App Installation Logic
    // ============================================================

    public interface MissingAppCallback {
        void onMissingApp(String nodeId);
    }

    public static void checkIfWatchMissingApp(Context context, MissingAppCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        executor.execute(() -> {
            try {
                // 1. Get all connected nodes
                List<Node> connectedNodes = Tasks.await(
                        Wearable.getNodeClient(context).getConnectedNodes());

                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

                if (connectedNodes == null || connectedNodes.isEmpty()) {
                    handler.post(() -> callback.onMissingApp(null));
                    return;
                }

                // 2. Iterate and PING each node
                for (Node node : connectedNodes) {
                    boolean pong = pingWatchOnce(context, node.getId());
                    if (!pong) {
                        // Ping failed -> App likely missing (or not running/responsive)
                        String nodeId = node.getId();
                        handler.post(() -> callback.onMissingApp(nodeId));
                        return; // Report first missing
                    }
                }
                
                // If here, all connected nodes responded to PING
                handler.post(() -> callback.onMissingApp(null));

            } catch (Exception e) {
                Log.e(TAG, "checkIfWatchMissingApp: failed", e);
                // On error, assume no missing app to avoid blocking UI
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> callback.onMissingApp(null));
            }
        });
    }

    public static void openPlayStoreOnWatch(Context context, String nodeId) {
        if (context == null || nodeId == null) return;
        
        executor.execute(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(android.net.Uri.parse("market://details?id=pl.kejlo.mzutv2"));

                androidx.wear.remote.interactions.RemoteActivityHelper helper =
                        new androidx.wear.remote.interactions.RemoteActivityHelper(context, executor);

                ListenableFuture<Void> result = helper.startRemoteActivity(intent, nodeId);
                
                // Add listener just to log result
                result.addListener(() -> {
                    try {
                        result.get();
                        Log.d(TAG, "openPlayStoreOnWatch: success for " + nodeId);
                    } catch (Exception e) {
                        Log.e(TAG, "openPlayStoreOnWatch: failed", e);
                    }
                }, executor);
                
            } catch (Exception e) {
                 Log.e(TAG, "openPlayStoreOnWatch: fatal error", e);
            }
        });
    }
}
