package pl.kejlo.mzutv2.wear.tile;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.ResolvableFuture;
import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TimelineBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.ZonedDateTime;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.sync.WearSnapshotStore;
import pl.kejlo.mzutv2.wear.R;
import pl.kejlo.mzutv2.wear.util.WearScheduleUtils;

/**
 * Modern tile service for MZUT schedule.
 * Features:
 * - Rounded corners on cards
 * - Gradient accent bar
 * - Clean typography
 * - Compact event rows
 */
public class PlanTileService extends TileService {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final String RESOURCES_VERSION = "2";
    private static final String PREFS_TILE = "wear_tile";
    private static final String KEY_TILE_SEEN_TS = "tile_seen_ts";
    private static final long TILE_SEEN_THROTTLE_MS = 30 * 60 * 1000L;

    // Modern dark theme
    private static final int COLOR_BG = 0xFF0A0E1A;
    private static final int COLOR_CARD = 0xFF161B2E;
    private static final int COLOR_CARD_HIGHLIGHT = 0xFF1E2642;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFB8C0D0;
    private static final int COLOR_SUBTLE = 0xFF6B7280;
    private static final int COLOR_ACCENT = 0xFF6366F1;
    private static final int COLOR_ACCENT_LIGHT = 0xFF818CF8;
    private static final int COLOR_SUCCESS = 0xFF10B981;

    private int themeBg = COLOR_BG;
    private int themeCard = COLOR_CARD;
    private int themeCardHighlight = COLOR_CARD_HIGHLIGHT;
    private int themeText = COLOR_TEXT;
    private int themeMuted = COLOR_MUTED;
    private int themeSubtle = COLOR_SUBTLE;
    private int themeAccent = COLOR_ACCENT;
    private int themeAccentLight = COLOR_ACCENT_LIGHT;

    @NonNull
    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            @NonNull RequestBuilders.TileRequest requestParams) {
        ResolvableFuture<TileBuilders.Tile> future = ResolvableFuture.create();
        try {
            markTileSeen();
            WearPlanSnapshot snap = WearSnapshotStore.load(this);
            if (snap == null) {
                snap = new WearPlanSnapshot();
            }
            applyTheme(snap);

            LayoutElementBuilders.Layout layout = new LayoutElementBuilders.Layout.Builder()
                    .setRoot(buildLayout(snap))
                    .build();

            TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setFreshnessIntervalMillis(30 * 60 * 1000L)
                    .setTimeline(new TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(layout)
                                    .build())
                            .build())
                    .build();

            future.set(tile);
        } catch (Exception e) {
            Log.e(TAG, "onTileRequest: failed", e);
            future.set(buildErrorTile());
        }
        return future;
    }

    @NonNull
    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest requestParams) {
        ResolvableFuture<ResourceBuilders.Resources> future = ResolvableFuture.create();
        future.set(new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build());
        return future;
    }

    private void applyTheme(WearPlanSnapshot snap) {
        if (snap == null) return;
        if (snap.colorBg != 0) themeBg = snap.colorBg;
        if (snap.colorCard != 0) themeCard = snap.colorCard;
        if (snap.colorCardAlt != 0) themeCardHighlight = snap.colorCardAlt;
        if (snap.colorText != 0) themeText = snap.colorText;
        if (snap.colorMuted != 0) themeMuted = snap.colorMuted;
        if (snap.colorSubtle != 0) themeSubtle = snap.colorSubtle;
        if (snap.colorAccent != 0) themeAccent = snap.colorAccent;
        if (snap.colorAccentText != 0) themeAccentLight = snap.colorAccentText;
    }

    private TileBuilders.Tile buildErrorTile() {
        LayoutElementBuilders.Layout layout = new LayoutElementBuilders.Layout.Builder()
                .setRoot(text(getString(R.string.tile_no_data), 12, COLOR_TEXT))
                .build();
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(60 * 60 * 1000L)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(layout)
                                .build())
                        .build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildLayout(WearPlanSnapshot snap) {
        ModifiersBuilders.Modifiers rootMods = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                        .setClassName("pl.kejlo.mzutv2.wear.MainWearActivity")
                                        .setPackageName(getPackageName())
                                        .build())
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(12))
                        .setEnd(DimensionBuilders.dp(12))
                        .setTop(DimensionBuilders.dp(10))
                        .setBottom(DimensionBuilders.dp(8))
                        .build())
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeBg))
                        .build())
                .build();

        LayoutElementBuilders.Column.Builder root = new LayoutElementBuilders.Column.Builder()
                .setModifiers(rootMods)
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START);

        // Header with app name
        root.addContent(buildHeader());

        // Login required state
        if (snap == null || snap.loginRequired) {
            root.addContent(spacer(8));
            root.addContent(buildInfoCard(
                    getString(R.string.tile_login_required),
                    getString(R.string.tile_wait_sync),
                    themeSubtle));
            return root.build();
        }

        // Find next event
        WearScheduleUtils.NextEventInfo next =
                WearScheduleUtils.findNextEvent(snap, ZonedDateTime.now());

        if (next == null || next.event == null) {
            root.addContent(spacer(8));
            root.addContent(buildInfoCard(
                    getString(R.string.tile_no_events),
                    snap.dateLabel != null ? snap.dateLabel : "",
                    COLOR_SUCCESS));
            return root.build();
        }

        // Next event card - prominent
        root.addContent(spacer(6));
        root.addContent(buildNextEventCard(next));

        // Secondary events (compact list)
        if (snap.events != null && snap.events.size() > 1) {
            int added = 0;
            root.addContent(spacer(6));
            for (WearPlanSnapshot.Event ev : snap.events) {
                if (added >= 2) break;
                if (isSameEvent(ev, next.event)) continue;
                root.addContent(buildCompactEventRow(ev));
                root.addContent(spacer(3));
                added++;
            }
        }

        return root.build();
    }

    private LayoutElementBuilders.LayoutElement buildHeader() {
        return new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(new LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .addContent(text("📚 MZUT", 13, themeAccentLight))
                        .build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildNextEventCard(
            WearScheduleUtils.NextEventInfo next) {
        WearPlanSnapshot.Event ev = next.event;
        int accent = ev.color != 0 ? ev.color : themeAccent;

        String title = WearScheduleUtils.ellipsize(ev.title, 20);
        String timeRange = next.timeRange != null ? next.timeRange : "";
        String eta = WearScheduleUtils.formatEta(this, ZonedDateTime.now(), next.start);

        // Card with accent left border
        ModifiersBuilders.Modifiers cardMods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeCard))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(12))
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(10))
                        .setEnd(DimensionBuilders.dp(10))
                        .setTop(DimensionBuilders.dp(10))
                        .setBottom(DimensionBuilders.dp(10))
                        .build())
                .build();

        // Accent bar
        LayoutElementBuilders.Box accentBar = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(4))
                .setHeight(DimensionBuilders.dp(40))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(accent))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(2))
                                        .build())
                                .build())
                        .build())
                .build();

        // Content column
        LayoutElementBuilders.Column.Builder content = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());

        content.addContent(text("NASTĘPNE", 9, accent));
        content.addContent(spacer(2));
        content.addContent(text(title, 14, themeText));
        if (!timeRange.isEmpty()) {
            content.addContent(text(timeRange, 11, themeMuted));
        }
        if (ev.room != null && !ev.room.isEmpty()) {
            content.addContent(text(ev.room, 10, themeSubtle));
        }

        // ETA badge
        LayoutElementBuilders.LayoutElement etaBadge = null;
        if (eta != null && !eta.isEmpty()) {
            etaBadge = new LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.wrap())
                    .setHeight(DimensionBuilders.wrap())
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                            .setBackground(new ModifiersBuilders.Background.Builder()
                                    .setColor(ColorBuilders.argb(themeCardHighlight))
                                    .setCorner(new ModifiersBuilders.Corner.Builder()
                                            .setRadius(DimensionBuilders.dp(6))
                                            .build())
                                    .build())
                            .setPadding(new ModifiersBuilders.Padding.Builder()
                                    .setStart(DimensionBuilders.dp(6))
                                    .setEnd(DimensionBuilders.dp(6))
                                    .setTop(DimensionBuilders.dp(4))
                                    .setBottom(DimensionBuilders.dp(4))
                                    .build())
                            .build())
                    .addContent(text(eta, 12, themeText))
                    .build();
        }

        // Main row: accent bar + content + eta
        LayoutElementBuilders.Row.Builder row = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER);

        row.addContent(accentBar);
        row.addContent(spacerH(8));
        row.addContent(content.build());
        if (etaBadge != null) {
            row.addContent(spacerH(6));
            row.addContent(etaBadge);
        }

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setModifiers(cardMods)
                .addContent(row.build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildCompactEventRow(WearPlanSnapshot.Event ev) {
        int dotColor = ev.color != 0 ? ev.color : themeAccent;

        ModifiersBuilders.Modifiers rowMods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeCardHighlight))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(8))
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(8))
                        .setEnd(DimensionBuilders.dp(8))
                        .setTop(DimensionBuilders.dp(6))
                        .setBottom(DimensionBuilders.dp(6))
                        .build())
                .build();

        // Dot indicator
        LayoutElementBuilders.Box dot = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(6))
                .setHeight(DimensionBuilders.dp(6))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(dotColor))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(3))
                                        .build())
                                .build())
                        .build())
                .build();

        // Text content
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());
        col.addContent(text(WearScheduleUtils.ellipsize(ev.title, 18), 11, themeText));
        if (ev.time != null && !ev.time.isEmpty()) {
            col.addContent(text(ev.time, 9, themeMuted));
        }

        LayoutElementBuilders.Row.Builder row = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER);

        row.addContent(dot);
        row.addContent(spacerH(8));
        row.addContent(col.build());

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setModifiers(rowMods)
                .addContent(row.build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildInfoCard(String title, String subtitle, int accentColor) {
        ModifiersBuilders.Modifiers cardMods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeCard))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(12))
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setAll(DimensionBuilders.dp(12))
                        .build())
                .build();

        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);

        col.addContent(text(title, 13, themeText));
        if (subtitle != null && !subtitle.isEmpty()) {
            col.addContent(spacer(4));
            col.addContent(text(subtitle, 10, themeSubtle));
        }

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setModifiers(cardMods)
                .addContent(col.build())
                .build();
    }

    private LayoutElementBuilders.Text text(String content, int sizeSp, int color) {
        if (content == null) content = "";
        return new LayoutElementBuilders.Text.Builder()
                .setText(content)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sizeSp))
                        .setColor(ColorBuilders.argb(color))
                        .build())
                .setMaxLines(2)
                .build();
    }

    private LayoutElementBuilders.Spacer spacer(int heightDp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(heightDp))
                .build();
    }

    private LayoutElementBuilders.Spacer spacerH(int widthDp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(widthDp))
                .build();
    }

    private boolean isSameEvent(WearPlanSnapshot.Event a, WearPlanSnapshot.Event b) {
        if (a == null || b == null) return false;
        return safeEq(a.title, b.title) && safeEq(a.time, b.time) && safeEq(a.room, b.room);
    }

    private boolean safeEq(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        return a.equals(b != null ? b : "");
    }

    private void markTileSeen() {
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS_TILE, MODE_PRIVATE);
            long last = prefs.getLong(KEY_TILE_SEEN_TS, 0L);
            if (now - last < TILE_SEEN_THROTTLE_MS) return;
            prefs.edit().putLong(KEY_TILE_SEEN_TS, now).apply();

            com.google.android.gms.wearable.PutDataMapRequest req =
                    com.google.android.gms.wearable.PutDataMapRequest.create(
                            pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_TILE_SEEN);
            req.getDataMap().putLong(
                    pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_TIMESTAMP, now);
            com.google.android.gms.wearable.Wearable.getDataClient(this)
                    .putDataItem(req.asPutDataRequest().setUrgent());
        } catch (Exception e) {
            Log.e(TAG, "markTileSeen: failed", e);
        }
    }
}
