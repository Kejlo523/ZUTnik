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
import java.util.List;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.sync.WearSnapshotStore;
import pl.kejlo.mzutv2.wear.R;
import pl.kejlo.mzutv2.wear.util.WearScheduleUtils;

public class PlanTileService extends TileService {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final String RESOURCES_VERSION = "1";
    private static final String PREFS_TILE = "wear_tile";
    private static final String KEY_TILE_SEEN_TS = "tile_seen_ts";
    private static final long TILE_SEEN_THROTTLE_MS = 30 * 60 * 1000L;
    private static final int COLOR_BG = 0xFF0B1020;
    private static final int COLOR_CARD = 0xFF111827;
    private static final int COLOR_CARD_ALT = 0xFF0F172A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFB0B7C3;
    private static final int COLOR_SUBTLE = 0xFF8F96A3;
    private static final int COLOR_ACCENT_DEFAULT = 0xFF4F8DFF;
    private static final int COLOR_ACCENT_TEXT = 0xFF8EB4FF;
    private int themeBg = COLOR_BG;
    private int themeCard = COLOR_CARD;
    private int themeCardAlt = COLOR_CARD_ALT;
    private int themeText = COLOR_TEXT;
    private int themeMuted = COLOR_MUTED;
    private int themeSubtle = COLOR_SUBTLE;
    private int themeAccentText = COLOR_ACCENT_TEXT;

    @NonNull
    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest requestParams) {
        ResolvableFuture<TileBuilders.Tile> future = ResolvableFuture.create();
        try {
            markTileSeen();
            WearPlanSnapshot snap = WearSnapshotStore.load(this);
            if (snap == null) {
                snap = new WearPlanSnapshot();
            }
            int count = snap.events != null ? snap.events.size() : 0;
            Log.d(TAG, "onTileRequest: events=" + count + " loginRequired=" + snap.loginRequired);
            LayoutElementBuilders.Layout layout = new LayoutElementBuilders.Layout.Builder()
                    .setRoot(buildLayout(snap))
                    .build();

            TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setFreshnessIntervalMillis(60 * 60 * 1000L)
                    .setTimeline(new TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(layout)
                                    .build())
                            .build())
                    .build();

            future.set(tile);
        } catch (Exception e) {
            Log.e(TAG, "onTileRequest: failed", e);
            LayoutElementBuilders.Layout layout = new LayoutElementBuilders.Layout.Builder()
                    .setRoot(text(getString(R.string.tile_no_data), 12, COLOR_TEXT))
                    .build();
            TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setFreshnessIntervalMillis(60 * 60 * 1000L)
                    .setTimeline(new TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(layout)
                                    .build())
                            .build())
                    .build();
            future.set(tile);
        }
        return future;
    }

    @NonNull
    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest requestParams) {
        ResolvableFuture<ResourceBuilders.Resources> future = ResolvableFuture.create();
        ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build();
        future.set(res);
        return future;
    }

    private LayoutElementBuilders.LayoutElement buildLayout(WearPlanSnapshot snap) {
        themeBg = snap != null && snap.colorBg != 0 ? snap.colorBg : COLOR_BG;
        themeCard = snap != null && snap.colorCard != 0 ? snap.colorCard : COLOR_CARD;
        themeCardAlt = snap != null && snap.colorCardAlt != 0 ? snap.colorCardAlt : COLOR_CARD_ALT;
        themeText = snap != null && snap.colorText != 0 ? snap.colorText : COLOR_TEXT;
        themeMuted = snap != null && snap.colorMuted != 0 ? snap.colorMuted : COLOR_MUTED;
        themeSubtle = snap != null && snap.colorSubtle != 0 ? snap.colorSubtle : COLOR_SUBTLE;
        themeAccentText = snap != null && snap.colorAccentText != 0
                ? snap.colorAccentText
                : COLOR_ACCENT_TEXT;
        int accentText = themeAccentText;

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
                        .setAll(DimensionBuilders.dp(14))
                        .build())
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeBg))
                        .build())
                .build();

        LayoutElementBuilders.Column.Builder root = new LayoutElementBuilders.Column.Builder()
                .setModifiers(rootMods)
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand());

        root.addContent(text(getString(R.string.tile_title), 14, accentText));

        String date = snap != null ? snap.dateLabel : "";
        if (date == null || date.isEmpty()) {
            date = snap != null ? snap.subtitle : "";
        }
        if (date != null && !date.isEmpty()) {
            root.addContent(text(date, 10, themeMuted));
        }
        String subtitle = snap != null ? snap.subtitle : "";
        if (subtitle != null && !subtitle.isEmpty() && !subtitle.equals(date)) {
            root.addContent(text(subtitle, 10, themeSubtle));
        }

        if (snap == null || snap.loginRequired) {
            root.addContent(new LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(6))
                    .build());
            root.addContent(text(getString(R.string.tile_login_required), 11, themeMuted));
            root.addContent(text(getString(R.string.tile_wait_sync), 10, themeSubtle));
            return root.build();
        }

        WearScheduleUtils.NextEventInfo next =
                WearScheduleUtils.findNextEvent(snap, ZonedDateTime.now());
        if (next == null || next.event == null) {
            root.addContent(new LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(6))
                    .build());
            root.addContent(text(getString(R.string.tile_no_events), 11, themeSubtle));
            return root.build();
        }

        int accent = next.event.color != 0 ? next.event.color : COLOR_ACCENT_DEFAULT;
        root.addContent(new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(6))
                .build());
        root.addContent(buildNextCard(next, accent));

        if (snap.events != null && !snap.events.isEmpty()) {
            int added = 0;
            root.addContent(new LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(6))
                    .build());
            for (WearPlanSnapshot.Event ev : snap.events) {
                if (added >= 1) {
                    break;
                }
                if (isSameEvent(ev, next.event)) {
                    continue;
                }
                root.addContent(eventRowCompact(ev));
                added++;
            }
        }

        if (snap.refreshedLabel != null && !snap.refreshedLabel.isEmpty()) {
            root.addContent(new LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(4))
                    .build());
            root.addContent(text(snap.refreshedLabel, 9, themeSubtle));
        }

        return root.build();
    }

    private LayoutElementBuilders.LayoutElement buildNextCard(
            WearScheduleUtils.NextEventInfo next, int accent) {
        WearPlanSnapshot.Event ev = next.event;
        String title = WearScheduleUtils.ellipsize(ev.title, 22);
        String timeRange = next.timeRange != null ? next.timeRange : "";
        String eta = WearScheduleUtils.formatEta(this, ZonedDateTime.now(), next.start);

        ModifiersBuilders.Modifiers cardMods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeCard))
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setAll(DimensionBuilders.dp(10))
                        .build())
                .build();

        LayoutElementBuilders.Column.Builder left = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());
        left.addContent(text(getString(R.string.tile_next_label), 10, accent));
        left.addContent(text(title, 13, themeText));
        if (timeRange != null && !timeRange.isEmpty()) {
            left.addContent(text(timeRange, 10, themeMuted));
        }
        if (ev.room != null && !ev.room.isEmpty()) {
            left.addContent(text(ev.room, 10, themeSubtle));
        }

        LayoutElementBuilders.Column.Builder right = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.wrap())
                .setHeight(DimensionBuilders.wrap());
        if (eta != null && !eta.isEmpty()) {
            right.addContent(text(eta, 14, themeText));
        }

        LayoutElementBuilders.Row.Builder row = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());
        row.addContent(left.build());
        row.addContent(new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(8))
                .build());
        row.addContent(right.build());

        LayoutElementBuilders.Box.Builder card = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setModifiers(cardMods)
                .addContent(row.build());

        return card.build();
    }

    private LayoutElementBuilders.LayoutElement eventRowCompact(WearPlanSnapshot.Event ev) {
        LayoutElementBuilders.Row.Builder row = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());

        int dotColor = ev.color != 0 ? ev.color : COLOR_ACCENT_DEFAULT;
        LayoutElementBuilders.Box dot = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(6))
                .setHeight(DimensionBuilders.dp(6))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(dotColor))
                                .build())
                        .build())
                .build();

        row.addContent(dot);
        row.addContent(new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(6))
                .build());

        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap());
        col.addContent(text(WearScheduleUtils.ellipsize(ev.title, 20), 11, themeText));
        if (ev.time != null && !ev.time.isEmpty()) {
            col.addContent(text(ev.time, 9, themeMuted));
        }
        row.addContent(col.build());

        ModifiersBuilders.Modifiers rowMods = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(themeCardAlt))
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setAll(DimensionBuilders.dp(6))
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setModifiers(rowMods)
                .addContent(row.build())
                .build();
    }

    private boolean isSameEvent(WearPlanSnapshot.Event a, WearPlanSnapshot.Event b) {
        if (a == null || b == null) {
            return false;
        }
        return safeEq(a.title, b.title)
                && safeEq(a.time, b.time)
                && safeEq(a.room, b.room);
    }

    private boolean safeEq(String a, String b) {
        if (a == null) {
            return b == null || b.isEmpty();
        }
        return a.equals(b != null ? b : "");
    }

    private LayoutElementBuilders.Text text(String content, int sizeSp, int color) {
        if (content == null) {
            content = "";
        }
        LayoutElementBuilders.FontStyle.Builder fs = new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sizeSp))
                .setColor(ColorBuilders.argb(color));
        return new LayoutElementBuilders.Text.Builder()
                .setText(content)
                .setFontStyle(fs.build())
                .build();
    }

    private void markTileSeen() {
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS_TILE, MODE_PRIVATE);
            long last = prefs.getLong(KEY_TILE_SEEN_TS, 0L);
            if (now - last < TILE_SEEN_THROTTLE_MS) {
                return;
            }
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
