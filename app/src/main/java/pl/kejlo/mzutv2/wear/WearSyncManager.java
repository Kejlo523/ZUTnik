package pl.kejlo.mzutv2.wear;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

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
import java.util.zip.CRC32;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.kejlo.mzutv2.R;

public class WearSyncManager {

    private static final String TAG = "MZUTWearSync/PHONE";
    private static final String PREFS_SETTINGS = "mzut_settings";
    private static final String PREFS_HANDSHAKE = "wear_sync_handshake";
    private static final String PREFS_WEAR = "wear_sync_prefs";
    private static final String KEY_PENDING_NODE = "pending_node_id";
    private static final String KEY_PENDING_NODE_NAME = "pending_node_name";
    private static final String KEY_LAST_REQUEST_TS = "last_request_ts";
    private static final String KEY_LAST_READY_TS = "last_ready_ts";
    private static final String KEY_LAST_ACK_TS = "last_ack_ts";
    private static final String KEY_LAST_PONG_TS = "last_pong_ts";
    private static final String KEY_AUTO_SYNC_ENABLED = "wear_auto_sync_enabled";
    private static final String KEY_LAST_SYNC_TS = "wear_last_sync_ts";
    private static final int CHUNK_SIZE = 2000;
    private static final long WATCH_STATUS_STALE_MS = 90_000L;
    private static final long PING_TIMEOUT_MS = 800L;
    private static final long PING_POLL_MS = 100L;
    private static final long TILE_SEEN_STALE_MS = 7L * 24 * 60 * 60 * 1000L;
    private static final long AUTO_SYNC_INTERVAL_MIN = 60L;
    private static final long FAST_POLL_INTERVAL_MS = 2000L;
    private static final long FAST_POLL_WINDOW_MS = 2L * 60L * 1000L;
    private static volatile long fastPollUntilMs = 0L;
    private static volatile boolean forceFastPolling = false;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void syncNowAsync(Context context) {
        if (context == null) {
            return;
        }
        executor.execute(() -> syncNowBlocking(context));
    }

    public static void syncNowAsyncForNode(Context context, String nodeId) {
        if (context == null) {
            return;
        }
        executor.execute(() -> syncNowBlockingForNode(context, nodeId));
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
        PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_PLAN_SNAPSHOT);
        DataMap map = req.getDataMap();
        map.putString(WearSyncConstants.KEY_PAYLOAD, payload);
        map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());

        try {
            Tasks.await(Wearable.getDataClient(context).putDataItem(req.asPutDataRequest()));
            Log.d(TAG, "syncNowBlocking: DataItem sent");
            sendProgress(context, 70, context.getString(R.string.wear_sync_status_waiting_watch));
        } catch (Exception e) {
            Log.e(TAG, "syncNowBlocking: DataItem send failed", e);
            sendProgress(context, 0, context.getString(R.string.wear_sync_status_error));
        }

        sendSnapshotToWatch(context, payload);
    }

    private static void syncNowBlockingForNode(Context context, String nodeId) {
        if (context == null) {
            return;
        }
        Log.d(TAG, "syncNowBlockingForNode: nodeId=" + nodeId);
        sendProgress(context, 15, context.getString(R.string.wear_sync_status_prepare));
        WearPlanSnapshot snapshot = WearPlanSnapshotBuilder.build(context);
        if (snapshot == null) {
            Log.w(TAG, "syncNowBlockingForNode: snapshot null");
            sendProgress(context, 0, context.getString(R.string.wear_sync_status_no_data));
            return;
        }
        String payload = snapshot.toJson();
        int payloadBytes = payload != null
                ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                : 0;
        Log.d(TAG, "syncNowBlockingForNode: payload bytes=" + payloadBytes);
        sendProgress(context, 40, context.getString(R.string.wear_sync_status_sending));
        sendSnapshotDataItem(context, payload);
        sendProgress(context, 70, context.getString(R.string.wear_sync_status_waiting_watch));
    }

    public static void schedulePeriodicSync(Context context) {
        if (context == null) {
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }

        cancelPeriodicSync(context);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        String intervalStr = prefs.getString("widget_refresh_interval", "30");
        long intervalMin = 30;
        try {
            intervalMin = Long.parseLong(intervalStr);
        } catch (NumberFormatException ignored) {
            intervalMin = 30;
        }

        if (intervalMin <= 0) {
            return;
        }
        Log.d(TAG, "schedulePeriodicSync: intervalMin=" + intervalMin);

        long intervalMs = intervalMin * 60L * 1000L;

        Intent i = new Intent(context, WearSyncReceiver.class);
        i.setAction(WearSyncConstants.ACTION_WEAR_SYNC);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi);
    }

    public static void cancelPeriodicSync(Context context) {
        if (context == null) {
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }

        Intent i = new Intent(context, WearSyncReceiver.class);
        i.setAction(WearSyncConstants.ACTION_WEAR_SYNC);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        Log.d(TAG, "cancelPeriodicSync");
    }

    public static void sendProgress(Context context, int progress, String status) {
        if (context == null) {
            return;
        }
        Log.d(TAG, "sendProgress: " + progress + "% status=" + status);
        if (progress > 0 && progress < 100) {
            setForceFastPolling(true);
        } else if (progress >= 100 || progress == 0) {
            setForceFastPolling(false);
        }
        if (progress >= 100) {
            markAutoSyncEnabled(context);
            setLastSyncTimestamp(context, System.currentTimeMillis());
            scheduleWearAutoSync(context);
        }
        sendLocalProgress(context, progress, status);
        sendProgressToWatch(context, progress, status);
    }

    public static boolean isAutoSyncEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false);
    }

    public static long getLastSyncTimestamp(Context context) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_SYNC_TS, 0L);
    }

    public static long getAutoSyncIntervalMinutes() {
        return AUTO_SYNC_INTERVAL_MIN;
    }

    private static void markAutoSyncEnabled(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, true).apply();
    }

    private static void setLastSyncTimestamp(Context context, long ts) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_SYNC_TS, ts).apply();
    }

    public static void scheduleWearAutoSync(Context context) {
        if (context == null || !isAutoSyncEnabled(context)) {
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        cancelPeriodicSync(context);
        long intervalMs = AUTO_SYNC_INTERVAL_MIN * 60L * 1000L;
        Intent i = new Intent(context, WearSyncReceiver.class);
        i.setAction(WearSyncConstants.ACTION_WEAR_SYNC);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi);
        Log.d(TAG, "scheduleWearAutoSync: intervalMin=" + AUTO_SYNC_INTERVAL_MIN);
    }

    public static void setPendingRequest(Context context, String nodeId, String nodeName) {
        if (context == null) {
            return;
        }
        setForceFastPolling(true);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_HANDSHAKE, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PENDING_NODE, nodeId != null ? nodeId : "")
                .putString(KEY_PENDING_NODE_NAME, nodeName != null ? nodeName : "")
                .apply();
        Log.d(TAG, "setPendingRequest: nodeId=" + nodeId + " name=" + nodeName);
    }

    public static PendingRequest getPendingRequest(Context context) {
        if (context == null) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_HANDSHAKE, Context.MODE_PRIVATE);
        String nodeId = prefs.getString(KEY_PENDING_NODE, "");
        String nodeName = prefs.getString(KEY_PENDING_NODE_NAME, "");
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        return new PendingRequest(nodeId, nodeName);
    }

    public static long getPollIntervalMs() {
        return isFastPolling() ? FAST_POLL_INTERVAL_MS : 60_000L;
    }

    public static void boostPolling() {
        fastPollUntilMs = SystemClock.elapsedRealtime() + FAST_POLL_WINDOW_MS;
    }

    public static void setForceFastPolling(boolean enabled) {
        forceFastPolling = enabled;
        if (!enabled) {
            clearFastPolling();
        }
    }

    public static void clearFastPolling() {
        fastPollUntilMs = 0L;
    }

    public static boolean isFastPolling() {
        return forceFastPolling || SystemClock.elapsedRealtime() < fastPollUntilMs;
    }

    public static void clearPendingRequest(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_HANDSHAKE, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_PENDING_NODE)
                .remove(KEY_PENDING_NODE_NAME)
                .apply();
        Log.d(TAG, "clearPendingRequest");
    }

    public static void pollRequestDataItemAsync(Context context) {
        if (context == null) {
            return;
        }
        executor.execute(() -> pollRequestDataItem(context));
    }

    public static void getWatchStatusAsync(Context context, WatchStatusCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        executor.execute(() -> {
            WatchStatus status = fetchWatchStatus(context);
            final WatchStatus resultStatus = status;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onStatus(resultStatus));
        });
    }

    public static void getWatchStatusWithPingAsync(Context context, WatchStatusCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        executor.execute(() -> {
            WatchStatus status = fetchWatchStatus(context);
            boolean pingOk = false;
            if (status != null && status.connected) {
                pingOk = pingWatchOnce(context, status.nodeId);
            }
            if (status != null) {
                if (status.connected && !pingOk) {
                    status = new WatchStatus(true, false, status.name, status.battery,
                            status.timestamp, status.nodeId, status.paired, status.tileSeenTimestamp);
                }
            }
            final WatchStatus resultStatus = status;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> callback.onStatus(resultStatus));
        });
    }

    public static void setLastPongTs(Context context, long ts) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WEAR, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_PONG_TS, ts).apply();
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
            try {
                CapabilityInfo reachableInfo = Tasks.await(
                        Wearable.getCapabilityClient(context)
                                .getCapability(WearSyncConstants.CAPABILITY_WEAR_SYNC,
                                        CapabilityClient.FILTER_REACHABLE));
                if (reachableInfo != null && reachableInfo.getNodes() != null) {
                    for (Node n : reachableInfo.getNodes()) {
                        if (n != null) {
                            nodeId = n.getId();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
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
            java.util.List<Node> nodes = Tasks.await(
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

    private static void pollRequestDataItem(Context context) {
        if (context == null) {
            return;
        }
        try {
            pollPath(context, WearSyncConstants.PATH_REQUEST_SYNC, KEY_LAST_REQUEST_TS);
            pollPath(context, WearSyncConstants.PATH_SYNC_READY, KEY_LAST_READY_TS);
            pollPath(context, WearSyncConstants.PATH_SYNC_ACK, KEY_LAST_ACK_TS);
        } catch (Exception e) {
            Log.e(TAG, "pollRequestDataItem: failed", e);
        }
    }

    private static void pollPath(Context context, String path, String keyLastTs) throws Exception {
        android.net.Uri uri = new android.net.Uri.Builder()
                .scheme(com.google.android.gms.wearable.PutDataRequest.WEAR_URI_SCHEME)
                .path(path)
                .build();
        com.google.android.gms.wearable.DataItemBuffer items =
                Tasks.await(Wearable.getDataClient(context).getDataItems(uri));
        if (items == null) {
            return;
        }
        try {
            for (com.google.android.gms.wearable.DataItem item : items) {
                if (item == null || item.getUri() == null) {
                    continue;
                }
                String nodeId = item.getUri().getHost();
                long ts = 0L;
                try {
                    com.google.android.gms.wearable.DataMapItem mapItem =
                            com.google.android.gms.wearable.DataMapItem.fromDataItem(item);
                    if (mapItem != null) {
                        ts = mapItem.getDataMap().getLong(WearSyncConstants.KEY_TIMESTAMP, 0L);
                    }
                } catch (Exception ignored) {
                }
                if (isAlreadyHandled(context, keyLastTs, ts)) {
                    continue;
                }
                markHandled(context, keyLastTs, ts);
                if (WearSyncConstants.PATH_REQUEST_SYNC.equals(path)) {
                    Log.d(TAG, "pollRequestDataItem: found request nodeId=" + nodeId + " ts=" + ts);
                    boostPolling();
                    setPendingRequest(context, nodeId, "");
                    Intent i = new Intent(WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
                    i.setPackage(context.getPackageName());
                    i.putExtra(WearSyncConstants.KEY_NODE_ID, nodeId != null ? nodeId : "");
                    i.putExtra(WearSyncConstants.KEY_NODE_NAME, "");
                    context.sendBroadcast(i);
                } else if (WearSyncConstants.PATH_SYNC_READY.equals(path)) {
                    Log.d(TAG, "pollRequestDataItem: found ready nodeId=" + nodeId + " ts=" + ts);
                    boostPolling();
                    clearPendingRequest(context);
                    syncNowAsyncForNode(context, nodeId);
                } else if (WearSyncConstants.PATH_SYNC_ACK.equals(path)) {
                    Log.d(TAG, "pollRequestDataItem: found ack nodeId=" + nodeId + " ts=" + ts);
                    sendProgress(context, 100,
                            context.getString(R.string.wear_sync_status_watch_received));
                }
                Wearable.getDataClient(context).deleteDataItems(item.getUri());
            }
        } finally {
            items.release();
        }
    }

    private static boolean isAlreadyHandled(Context context, String key, long ts) {
        if (context == null || ts <= 0) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_HANDSHAKE, Context.MODE_PRIVATE);
        long last = prefs.getLong(key, 0L);
        return ts <= last;
    }

    private static void markHandled(Context context, String key, long ts) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_HANDSHAKE, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(key, ts)
                .apply();
    }

    private static WatchStatus fetchWatchStatus(Context context) {
        long now = System.currentTimeMillis();
        // quick ping is executed by getWatchStatusWithPingAsync on demand
        Node reachableNode = null;
        Node connectedNode = null;
        try {
            java.util.List<Node> connectedNodes = Tasks.await(
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
                    ? reachableNode.getDisplayName()
                    : "";
            fallbackNodeId = reachableNode.getId() != null ? reachableNode.getId() : "";
        } else if (connectedNode != null) {
            fallbackName = connectedNode.getDisplayName() != null
                    ? connectedNode.getDisplayName()
                    : "";
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
                    if (n == null) {
                        continue;
                    }
                    pairedNode = n;
                    break;
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
            if (items == null) {
                items = null;
            }
            if (items != null) {
                try {
                    for (com.google.android.gms.wearable.DataItem item : items) {
                        if (item == null || item.getUri() == null) {
                            continue;
                        }
                        com.google.android.gms.wearable.DataMapItem mapItem =
                                com.google.android.gms.wearable.DataMapItem.fromDataItem(item);
                        if (mapItem == null) {
                            continue;
                        }
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
                        if (item == null || item.getUri() == null) {
                            continue;
                        }
                        com.google.android.gms.wearable.DataMapItem mapItem =
                                com.google.android.gms.wearable.DataMapItem.fromDataItem(item);
                        if (mapItem == null) {
                            continue;
                        }
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
                return new WatchStatus(false, latest.name, latest.battery, latest.timestamp, latest.nodeId, false, tileSeenTs);
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

    public static void sendSyncApprove(Context context, String nodeId) {
        setForceFastPolling(true);
        sendDataItem(context, WearSyncConstants.PATH_SYNC_APPROVE);
        sendSimpleMessage(context, WearSyncConstants.PATH_SYNC_APPROVE, nodeId);
    }

    public static void sendSyncDecline(Context context, String nodeId) {
        setForceFastPolling(false);
        sendDataItem(context, WearSyncConstants.PATH_SYNC_DECLINE);
        sendSimpleMessage(context, WearSyncConstants.PATH_SYNC_DECLINE, nodeId);
    }

    private static void sendSnapshotDataItem(Context context, String payload) {
        if (context == null || payload == null) {
            return;
        }
        PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_PLAN_SNAPSHOT);
        DataMap map = req.getDataMap();
        map.putString(WearSyncConstants.KEY_PAYLOAD, payload);
        map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
        Wearable.getDataClient(context)
                .putDataItem(req.asPutDataRequest().setUrgent())
                .addOnSuccessListener(r -> Log.d(TAG, "sendSnapshotDataItem: ok bytes=" + payload.length()))
                .addOnFailureListener(e -> Log.e(TAG, "sendSnapshotDataItem: failed", e));
    }

    private static void sendDataItem(Context context, String path) {
        if (context == null) {
            return;
        }
        PutDataMapRequest req = PutDataMapRequest.create(path);
        req.getDataMap().putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
        Wearable.getDataClient(context)
                .putDataItem(req.asPutDataRequest().setUrgent())
                .addOnSuccessListener(r -> Log.d(TAG, "sendDataItem: ok " + path))
                .addOnFailureListener(e -> Log.e(TAG, "sendDataItem: failed " + path, e));
    }

    private static void sendSimpleMessage(Context context, String path, String nodeId) {
        if (context == null) {
            return;
        }
        byte[] data = new byte[0];
        if (nodeId != null && !nodeId.isEmpty()) {
            Wearable.getMessageClient(context)
                    .sendMessage(nodeId, path, data)
                    .addOnSuccessListener(r -> Log.d(TAG, "sendSimpleMessage: ok " + path))
                    .addOnFailureListener(e -> Log.e(TAG, "sendSimpleMessage: fail " + path, e));
            return;
        }
        withWatchNodes(context, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "sendSimpleMessage: no nodes for " + path);
                return;
            }
            for (Node n : nodes) {
                Wearable.getMessageClient(context)
                        .sendMessage(n.getId(), path, data)
                        .addOnSuccessListener(r -> Log.d(TAG, "sendSimpleMessage: ok " + path + " node=" + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG, "sendSimpleMessage: fail " + path + " node=" + n.getDisplayName(), e));
            }
        });
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
                    Log.d(TAG, "withWatchNodes: capability nodes="
                            + (info == null ? "null" : info.getNodes()));
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
                            .addOnSuccessListener(nodesFallback -> {
                                Log.d(TAG, "withWatchNodes: connected nodes=" + nodesFallback);
                                callback.onNodes(nodesFallback);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "withWatchNodes: capability failed", e);
                    Wearable.getNodeClient(context).getConnectedNodes()
                            .addOnSuccessListener(nodesFallback -> {
                                Log.d(TAG, "withWatchNodes: connected nodes=" + nodesFallback);
                                callback.onNodes(nodesFallback);
                            });
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
                Log.w(TAG, "sendProgressToWatch: no nodes");
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

    private static void sendSnapshotToWatch(Context context, String payload) {
        if (context == null || payload == null) {
            return;
        }
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Log.d(TAG, "sendSnapshotToWatch: bytes=" + data.length);
        withWatchNodes(context, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "sendSnapshotToWatch: no nodes");
                return;
            }
            for (Node n : nodes) {
                Wearable.getMessageClient(context)
                        .sendMessage(n.getId(), WearSyncConstants.PATH_PLAN_SNAPSHOT_MSG, data)
                        .addOnSuccessListener(r -> Log.d(TAG,
                                "sendSnapshotToWatch: ok node=" + n.getDisplayName()))
                        .addOnFailureListener(e -> Log.e(TAG,
                                "sendSnapshotToWatch: fail node=" + n.getDisplayName(), e));
            }
            sendProgress(context, 85, context.getString(R.string.wear_sync_status_waiting_watch));
        });
    }

    private static void sendSnapshotChunked(Context context, String payload, String nodeId) {
        if (context == null || payload == null) {
            return;
        }
        if (nodeId == null || nodeId.isEmpty()) {
            Log.w(TAG, "sendSnapshotChunked: missing nodeId");
            return;
        }
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        long checksum = computeCrc32(data);
        Log.d(TAG, "sendSnapshotChunked: bytes=" + data.length + " chunks=" + totalChunks);

        DataMap startMap = new DataMap();
        startMap.putInt(WearSyncConstants.KEY_TOTAL_CHUNKS, totalChunks);
        startMap.putInt(WearSyncConstants.KEY_TOTAL_BYTES, data.length);
        startMap.putLong(WearSyncConstants.KEY_CHECKSUM, checksum);
        byte[] startBytes = startMap.toByteArray();

        try {
            Tasks.await(Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_START, startBytes));
        } catch (Exception e) {
            Log.e(TAG, "sendSnapshotChunked: start failed", e);
        }

        int sentChunks = 0;
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, data.length);
            int size = end - start;
            byte[] part = new byte[size];
            System.arraycopy(data, start, part, 0, size);
            DataMap chunkMap = new DataMap();
            chunkMap.putInt(WearSyncConstants.KEY_CHUNK_INDEX, i);
            chunkMap.putInt(WearSyncConstants.KEY_TOTAL_CHUNKS, totalChunks);
            chunkMap.putByteArray(WearSyncConstants.KEY_CHUNK_BYTES, part);
            byte[] chunkBytes = chunkMap.toByteArray();
            try {
                Tasks.await(Wearable.getMessageClient(context)
                        .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_CHUNK, chunkBytes));
                sentChunks++;
                int pct = 40 + (int) Math.round(50.0 * sentChunks / totalChunks);
                sendProgress(context, pct, context.getString(R.string.wear_sync_status_sending));
            } catch (Exception e) {
                Log.e(TAG, "sendSnapshotChunked: chunk failed index=" + i, e);
            }
        }

        try {
            Tasks.await(Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WearSyncConstants.PATH_SYNC_END, new byte[0]));
        } catch (Exception e) {
            Log.e(TAG, "sendSnapshotChunked: end failed", e);
        }
        sendProgress(context, 85, context.getString(R.string.wear_sync_status_waiting_watch));
    }

    private static long computeCrc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    public static final class PendingRequest {
        public final String nodeId;
        public final String nodeName;

        public PendingRequest(String nodeId, String nodeName) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
        }
    }
}
