package pl.kejlo.mzutv2.wear;

public final class WearSyncConstants {
    private WearSyncConstants() {}

    public static final String PATH_PLAN_SNAPSHOT = "/mzut/plan_snapshot";
    public static final String PATH_PLAN_SNAPSHOT_MSG = "/mzut/plan_snapshot_msg";
    public static final String KEY_PAYLOAD = "payload";
    public static final String KEY_TIMESTAMP = "ts";
    public static final String ACTION_WEAR_SYNC = "pl.kejlo.mzutv2.WEAR_SYNC";
    public static final String ACTION_WEAR_SYNC_REQUEST = "pl.kejlo.mzutv2.WEAR_SYNC_REQUEST";
    public static final String ACTION_WEAR_SYNC_CANCEL = "pl.kejlo.mzutv2.WEAR_SYNC_CANCEL";
    public static final String PATH_REQUEST_SYNC = "/mzut/request_sync";
    public static final String PATH_REQUEST_CANCEL = "/mzut/request_cancel";
    public static final String PATH_SYNC_PROGRESS = "/mzut/sync_progress";
    public static final String PATH_SYNC_ACK = "/mzut/sync_ack";
    public static final String PATH_SYNC_APPROVE = "/mzut/sync_approve";
    public static final String PATH_SYNC_DECLINE = "/mzut/sync_decline";
    public static final String PATH_SYNC_READY = "/mzut/sync_ready";
    public static final String PATH_SYNC_START = "/mzut/sync_start";
    public static final String PATH_SYNC_CHUNK = "/mzut/sync_chunk";
    public static final String PATH_SYNC_END = "/mzut/sync_end";
    public static final String PATH_WATCH_STATUS = "/mzut/watch_status";
    public static final String PATH_TILE_SEEN = "/mzut/tile_seen";
    public static final String PATH_PING = "/mzut/ping";
    public static final String PATH_PONG = "/mzut/pong";
    public static final String ACTION_WEAR_SYNC_PROGRESS = "pl.kejlo.mzutv2.WEAR_SYNC_PROGRESS";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_STATUS = "status";
    public static final String KEY_TOTAL_CHUNKS = "total_chunks";
    public static final String KEY_TOTAL_BYTES = "total_bytes";
    public static final String KEY_CHUNK_INDEX = "chunk_index";
    public static final String KEY_CHUNK_BYTES = "chunk_bytes";
    public static final String KEY_CHECKSUM = "checksum";
    public static final String KEY_NODE_ID = "node_id";
    public static final String KEY_NODE_NAME = "node_name";
    public static final String KEY_BATTERY = "battery";
    public static final String KEY_DEVICE_NAME = "device_name";
    public static final String CAPABILITY_WEAR_SYNC = "mzut_wear_sync";
}
