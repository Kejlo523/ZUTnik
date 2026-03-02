package pl.kejlo.mzutv2.wear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.sync.WearSnapshotStore;
import pl.kejlo.mzutv2.wear.sync.WearSyncConstants;
import pl.kejlo.mzutv2.wear.util.WearLocaleManager;

public class MainWearActivity extends Activity {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final long SYNC_REQUEST_TIMEOUT_MS = 20_000L;
    private static final long SYNC_TAP_DEBOUNCE_MS = 1_200L;
    private static final float ROTARY_THRESHOLD = 48f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout rootContainer;
    private LinearLayout dayPageContent;
    private LinearLayout dayHeaderCard;
    private TextView dayTitle;
    private TextView dayCounter;
    private RecyclerView eventsList;

    private LinearLayout syncPageContent;
    private LinearLayout syncSheet;
    private TextView syncTitle;
    private TextView syncCounter;
    private TextView syncStatus;
    private ProgressBar syncProgress;
    private Button btnSyncNow;

    private EventAdapter eventAdapter;
    private WearPlanSnapshot currentSnap;

    private int totalDays = 0;
    private int currentPage = 0; // 0..(totalDays-1) = day pages, totalDays = sync page
    private int activeEventIndex = 0;

    private float rotaryAccumulator = 0f;
    private float swipeStartX = 0f;
    private float swipeStartY = 0f;

    private volatile boolean syncRequestInFlight = false;
    private long lastSyncTapTs = 0L;

    private int themeBg = 0xFF000000;
    private int themeCard = 0xFF171717;
    private int themeText = 0xFFFFFFFF;
    private int themeMuted = 0xFFAEB6C4;
    private int themeAccent = 0xFF4F8DFF;

    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            publishWatchStatus();
            handler.postDelayed(this, 5 * 60 * 1000L);
        }
    };

    private final Runnable syncRequestTimeoutRunnable = () -> {
        if (!syncRequestInFlight) {
            return;
        }
        syncRequestInFlight = false;
        WearSnapshotStore.setProgress(
                MainWearActivity.this,
                0,
                getString(R.string.wear_main_sync_failed));
        refreshSyncStatusUi();
    };

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (WearSyncConstants.ACTION_SNAPSHOT_UPDATED.equals(action)) {
                syncRequestInFlight = false;
                handler.removeCallbacks(syncRequestTimeoutRunnable);
                if (WearLocaleManager.needsRecreateForCurrentContext(MainWearActivity.this)) {
                    recreate();
                    return;
                }
                updateUI();
                refreshSyncStatusUi();
                return;
            }
            if (WearSyncConstants.ACTION_SYNC_PROGRESS.equals(action)) {
                refreshSyncStatusUi();
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(WearLocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);
        bindViews();
        setupList();
        setupActions();
        applyRoundInsets();
        updateUI();
        refreshSyncStatusUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
        filter.addAction(WearSyncConstants.ACTION_SYNC_PROGRESS);
        ContextCompat.registerReceiver(
                this,
                syncReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        handler.post(statusRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(syncReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacks(statusRunnable);
        handler.removeCallbacks(syncRequestTimeoutRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event != null
                && event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {

            float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                    * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            rotaryAccumulator += delta;
            if (Math.abs(rotaryAccumulator) >= ROTARY_THRESHOLD) {
                int direction = rotaryAccumulator > 0f ? 1 : -1;
                rotaryAccumulator = 0f;
                navigatePage(direction, true);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void bindViews() {
        rootContainer = findViewById(R.id.rootContainer);
        dayPageContent = findViewById(R.id.dayPageContent);
        dayHeaderCard = findViewById(R.id.dayHeaderCard);
        dayTitle = findViewById(R.id.dayTitle);
        dayCounter = findViewById(R.id.dayCounter);
        eventsList = findViewById(R.id.eventsList);

        syncPageContent = findViewById(R.id.syncPageContent);
        syncSheet = findViewById(R.id.syncSheet);
        syncTitle = findViewById(R.id.syncTitle);
        syncCounter = findViewById(R.id.syncCounter);
        syncStatus = findViewById(R.id.syncStatus);
        syncProgress = findViewById(R.id.syncProgress);
        btnSyncNow = findViewById(R.id.btnSyncNow);
    }

    private void setupList() {
        eventsList.setLayoutManager(new LinearLayoutManager(this));
        eventsList.setItemAnimator(null);
        eventsList.setClipToPadding(false);
        eventAdapter = new EventAdapter(new ArrayList<>());
        eventsList.setAdapter(eventAdapter);
        eventsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateCarouselVisuals();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCarouselVisuals();
                }
            }
        });
    }

    private void setupActions() {
        btnSyncNow.setOnClickListener(v -> requestSync());
        rootContainer.setOnTouchListener((v, event) -> {
            if (event == null) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                return false;
            }
            if (action == MotionEvent.ACTION_UP) {
                float dx = event.getX() - swipeStartX;
                float dy = event.getY() - swipeStartY;
                if (Math.abs(dx) > dp(40) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    int direction = dx < 0 ? 1 : -1;
                    navigatePage(direction, true);
                    return true;
                }
            }
            return false;
        });
    }

    private void updateUI() {
        currentSnap = WearSnapshotStore.load(this);
        boolean localeChanged = WearLocaleManager.updateOverrideFromSnapshot(this, currentSnap);
        if (localeChanged && WearLocaleManager.needsRecreateForCurrentContext(this)) {
            recreate();
            return;
        }
        applyThemeFromSnapshot(currentSnap);

        if (currentSnap != null && currentSnap.weekDays != null && !currentSnap.weekDays.isEmpty()) {
            totalDays = currentSnap.weekDays.size();
            if (currentPage > totalDays) {
                currentPage = totalDays;
            }
            if (currentPage < 0) {
                currentPage = findInitialDayIndex(currentSnap);
            }
        } else {
            totalDays = 0;
            currentPage = 0;
        }

        renderCurrentPage(false, 0);
    }

    private int findInitialDayIndex(WearPlanSnapshot snap) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < snap.weekDays.size(); i++) {
            WearPlanSnapshot.WeekDay day = snap.weekDays.get(i);
            if (day == null) {
                continue;
            }
            if (!TextUtils.isEmpty(day.dateIso)) {
                try {
                    if (today.equals(LocalDate.parse(day.dateIso))) {
                        return i;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!TextUtils.isEmpty(day.dateLabel)) {
                String marker = today.format(DateTimeFormatter.ofPattern("dd.MM"));
                if (day.dateLabel.contains(marker)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private void navigatePage(int direction, boolean animate) {
        int maxPage = totalDays; // last page is sync
        int next = currentPage + direction;
        if (next < 0 || next > maxPage) {
            return;
        }
        currentPage = next;
        renderCurrentPage(animate, direction);
    }

    private void renderCurrentPage(boolean animate, int direction) {
        if (currentPage >= totalDays) {
            showSyncPage(animate, direction);
        } else {
            showDayPage(animate, direction);
        }
    }

    private void showDayPage(boolean animate, int direction) {
        dayPageContent.setVisibility(View.VISIBLE);
        syncPageContent.setVisibility(View.GONE);

        List<EventItem> items = new ArrayList<>();
        if (currentSnap == null || currentSnap.weekDays == null || currentSnap.weekDays.isEmpty()) {
            dayTitle.setText(R.string.tile_no_data);
            dayCounter.setText(getString(R.string.wear_day_counter_format, 1, 1));
            items.add(new EventItem(getString(R.string.tile_no_events), "", "", 0));
            eventAdapter.setItems(items);
            activeEventIndex = 0;
            eventAdapter.setActiveIndex(activeEventIndex);
            eventsList.post(this::applyCarouselTransforms);
            return;
        }

        WearPlanSnapshot.WeekDay day = currentSnap.weekDays.get(currentPage);
        dayTitle.setText(!TextUtils.isEmpty(day.dateLabel) ? day.dateLabel : day.dateIso);
        dayCounter.setText(getString(R.string.wear_day_counter_format, currentPage + 1, totalDays + 1));

        if (day.events == null || day.events.isEmpty()) {
            items.add(new EventItem(getString(R.string.tile_no_events), "", "", 0));
        } else {
            for (WearPlanSnapshot.Event ev : day.events) {
                items.add(new EventItem(ev.title, ev.time, ev.room, ev.color));
            }
        }

        eventAdapter.setItems(items);
        activeEventIndex = 0;
        eventAdapter.setActiveIndex(activeEventIndex);
        eventsList.scrollToPosition(0);
        eventsList.post(this::updateCarouselVisuals);

        if (animate) {
            float fromX = direction > 0 ? dp(20) : -dp(20);
            dayPageContent.setTranslationX(fromX);
            dayPageContent.setAlpha(0f);
            dayPageContent.animate().translationX(0f).alpha(1f).setDuration(150).start();
        }
    }

    private void showSyncPage(boolean animate, int direction) {
        dayPageContent.setVisibility(View.GONE);
        syncPageContent.setVisibility(View.VISIBLE);
        syncCounter.setText(getString(R.string.wear_sync_counter_format, totalDays + 1, totalDays + 1));
        refreshSyncStatusUi();

        if (animate) {
            float fromX = direction > 0 ? dp(20) : -dp(20);
            syncPageContent.setTranslationX(fromX);
            syncPageContent.setAlpha(0f);
            syncPageContent.animate().translationX(0f).alpha(1f).setDuration(150).start();
        }
    }

    private void refreshSyncStatusUi() {
        String statusText = WearSnapshotStore.getStatus(this);
        int progressValue = WearSnapshotStore.getProgress(this);

        if (TextUtils.isEmpty(statusText)) {
            if (currentSnap != null && currentSnap.loginRequired) {
                statusText = getString(R.string.wear_main_status_login_required);
            } else {
                statusText = getString(R.string.wear_main_status_idle);
            }
        }

        boolean progressBusy = progressValue > 0 && progressValue < 100;
        boolean busy = syncRequestInFlight || progressBusy;

        syncStatus.setText(shortenStatus(statusText));
        syncProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            syncProgress.setProgress(progressValue > 0 ? progressValue : 5);
        }
        btnSyncNow.setEnabled(!busy);
        btnSyncNow.setAlpha(busy ? 0.6f : 1f);
    }

    private void updateCarouselVisuals() {
        updateActiveEventFromScroll();
        applyCarouselTransforms();
    }

    private void updateActiveEventFromScroll() {
        if (eventsList == null || eventAdapter == null || eventAdapter.getItemCount() == 0) {
            return;
        }

        int anchorY = eventsList.getPaddingTop() + dp(34);
        int closestPos = activeEventIndex;
        float minDist = Float.MAX_VALUE;

        int childCount = eventsList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = eventsList.getChildAt(i);
            if (child == null) {
                continue;
            }
            int pos = eventsList.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) {
                continue;
            }
            float centerY = (child.getTop() + child.getBottom()) / 2f;
            float dist = Math.abs(centerY - anchorY);
            if (dist < minDist) {
                minDist = dist;
                closestPos = pos;
            }
        }

        if (closestPos != activeEventIndex) {
            activeEventIndex = closestPos;
            eventAdapter.setActiveIndex(activeEventIndex);
        }
    }

    private void applyCarouselTransforms() {
        if (eventsList == null) {
            return;
        }
        int listHeight = eventsList.getHeight();
        if (listHeight <= 0) {
            return;
        }

        int anchorY = eventsList.getPaddingTop() + dp(34);
        float maxDist = Math.max(dp(120), listHeight * 0.7f);

        int childCount = eventsList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = eventsList.getChildAt(i);
            if (child == null) {
                continue;
            }
            float centerY = (child.getTop() + child.getBottom()) / 2f;
            float dist = Math.abs(centerY - anchorY);
            float norm = Math.min(1f, dist / maxDist);
            float scale = 1f - (0.2f * norm);
            float alpha = 1f - (0.55f * norm);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);
        }
    }

    private String shortenStatus(String text) {
        if (text == null) {
            return "";
        }
        String out = text.trim();
        if (out.length() > 44) {
            out = out.substring(0, 41) + "...";
        }
        return out;
    }

    private void requestSync() {
        long now = System.currentTimeMillis();
        if (syncRequestInFlight) {
            return;
        }
        if (now - lastSyncTapTs < SYNC_TAP_DEBOUNCE_MS) {
            return;
        }
        lastSyncTapTs = now;

        currentPage = totalDays;
        renderCurrentPage(false, 0);

        syncRequestInFlight = true;
        handler.removeCallbacks(syncRequestTimeoutRunnable);
        WearSnapshotStore.setProgress(this, 5, getString(R.string.wear_main_status_syncing));
        refreshSyncStatusUi();

        executor.execute(() -> {
            try {
                List<Node> nodes = resolveSyncNodes();
                if (nodes.isEmpty()) {
                    onSyncRequestFailed(R.string.wear_main_no_phone);
                    return;
                }

                int sentCount = 0;
                for (Node node : nodes) {
                    try {
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(
                                node.getId(),
                                WearSyncConstants.PATH_REQUEST_SYNC,
                                new byte[0]));
                        sentCount++;
                    } catch (Exception e) {
                        Log.e(TAG, "requestSync: message failed for node " + node.getDisplayName(), e);
                    }
                }

                boolean dataRequestSent = sendDataSyncRequest();
                if (sentCount == 0 && !dataRequestSent) {
                    onSyncRequestFailed(R.string.wear_main_sync_failed);
                    return;
                }

                WearSnapshotStore.setProgress(this, 20, getString(R.string.wear_main_status_waiting));
                runOnUiThread(() -> {
                    refreshSyncStatusUi();
                });
                handler.postDelayed(syncRequestTimeoutRunnable, SYNC_REQUEST_TIMEOUT_MS);
            } catch (Exception e) {
                Log.e(TAG, "requestSync: fatal", e);
                onSyncRequestFailed(R.string.wear_main_sync_failed);
            }
        });
    }

    private void onSyncRequestFailed(int statusRes) {
        syncRequestInFlight = false;
        handler.removeCallbacks(syncRequestTimeoutRunnable);
        WearSnapshotStore.setProgress(this, 0, getString(statusRes));
        runOnUiThread(() -> {
            refreshSyncStatusUi();
        });
    }

    private boolean sendDataSyncRequest() {
        try {
            PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_REQUEST_SYNC);
            DataMap map = req.getDataMap();
            map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
            map.putString("source", "watch_button");
            Tasks.await(Wearable.getDataClient(this).putDataItem(req.asPutDataRequest().setUrgent()));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "sendDataSyncRequest: failed", e);
            return false;
        }
    }

    private List<Node> resolveSyncNodes() {
        try {
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
            if (nodes != null) {
                return nodes;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            Log.e(TAG, "resolveSyncNodes: failed", e);
            return Collections.emptyList();
        }
    }

    private void publishWatchStatus() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            String deviceName = Build.MODEL != null ? Build.MODEL : getString(R.string.wear_device_default_name);

            PutDataMapRequest req = PutDataMapRequest.create(WearSyncConstants.PATH_WATCH_STATUS);
            DataMap map = req.getDataMap();
            map.putInt(WearSyncConstants.KEY_BATTERY, level);
            map.putString(WearSyncConstants.KEY_DEVICE_NAME, deviceName);
            map.putLong(WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
            Wearable.getDataClient(this).putDataItem(req.asPutDataRequest().setUrgent());
        } catch (Exception e) {
            Log.e(TAG, "publishWatchStatus: failed", e);
        }
    }

    private void applyRoundInsets() {
        boolean isRound = getResources().getConfiguration().isScreenRound();
        float fontScale = getResources().getConfiguration().fontScale;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        int inset = isRound ? Math.max((int) (screenWidth * 0.11f), dp(14)) : dp(10);
        int fontExtra = Math.max(0, Math.round((fontScale - 1f) * dp(8)));

        dayPageContent.setPadding(inset, dp(8), inset, dp(8) + fontExtra);
        syncPageContent.setPadding(inset, dp(8), inset, dp(6) + fontExtra);
        eventsList.setPadding(0, dp(2), 0, dp(38) + fontExtra);
    }

    private void applyThemeFromSnapshot(WearPlanSnapshot snap) {
        if (snap != null) {
            if (snap.colorCard != 0) {
                themeCard = snap.colorCard;
            }
            if (snap.colorText != 0) {
                themeText = snap.colorText;
            }
            if (snap.colorMuted != 0) {
                themeMuted = snap.colorMuted;
            }
            if (snap.colorAccent != 0) {
                themeAccent = snap.colorAccent;
            }
        }
        themeBg = 0xFF000000;

        rootContainer.setBackgroundColor(themeBg);
        dayHeaderCard.setBackground(null);
        syncSheet.setBackground(null);

        dayTitle.setTextColor(themeText);
        dayCounter.setTextColor(themeAccent);
        syncTitle.setTextColor(themeText);
        syncCounter.setTextColor(themeAccent);
        syncStatus.setTextColor(themeText);
        btnSyncNow.setBackground(makeRounded(themeAccent, dp(18)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            syncProgress.setProgressTintList(ColorStateList.valueOf(themeAccent));
            syncProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33222222));
        }

        if (eventAdapter != null) {
            eventAdapter.notifyDataSetChanged();
        }
    }

    private GradientDrawable makeRounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable makeRoundedWithStroke(int color, int radius, int strokeColor) {
        GradientDrawable d = makeRounded(color, radius);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class EventItem {
        final String title;
        final String time;
        final String room;
        final int color;

        EventItem(String title, String time, String room, int color) {
            this.title = title != null ? title : "";
            this.time = time != null ? time : "";
            this.room = room != null ? room : "";
            this.color = color;
        }
    }

    private final class EventAdapter extends RecyclerView.Adapter<EventAdapter.ViewHolder> {
        private final List<EventItem> items;
        private int activeIndex = 0;

        EventAdapter(List<EventItem> items) {
            this.items = items;
        }

        void setItems(List<EventItem> newItems) {
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            activeIndex = 0;
            notifyDataSetChanged();
        }

        void setActiveIndex(int index) {
            int bounded = Math.max(0, Math.min(index, Math.max(0, items.size() - 1)));
            if (activeIndex == bounded) {
                return;
            }
            int old = activeIndex;
            activeIndex = bounded;
            if (old >= 0 && old < items.size()) {
                notifyItemChanged(old);
            }
            if (activeIndex >= 0 && activeIndex < items.size()) {
                notifyItemChanged(activeIndex);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MainWearActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(4));
            row.setLayoutParams(lp);

            View stripe = new View(MainWearActivity.this);
            LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(dp(4), dp(34));
            stripeLp.setMarginEnd(dp(10));
            row.addView(stripe, stripeLp);

            LinearLayout content = new LinearLayout(MainWearActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f));

            TextView title = new TextView(MainWearActivity.this);
            title.setTextSize(12f);
            title.setTextColor(themeText);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(title);

            TextView time = new TextView(MainWearActivity.this);
            time.setTextSize(10f);
            time.setTextColor(themeMuted);
            time.setSingleLine(true);
            time.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(time);

            TextView room = new TextView(MainWearActivity.this);
            room.setTextSize(10f);
            room.setTextColor(themeMuted);
            room.setSingleLine(true);
            room.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(room);

            row.addView(content);
            return new ViewHolder(row, stripe, title, time, room);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            EventItem item = items.get(position);
            boolean isPrimary = position == activeIndex;
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) holder.row.getLayoutParams();
            if (isPrimary) {
                lp.setMargins(0, 0, 0, dp(4));
                holder.row.setPadding(dp(10), dp(8), dp(10), dp(8));
                holder.row.setBackground(makeRoundedWithStroke(themeCard, dp(10), 0x28FFFFFF));
                holder.title.setTextSize(12f);
                holder.title.setMaxLines(2);
                holder.time.setTextSize(10f);
                holder.room.setTextSize(10f);
            } else {
                lp.setMargins(dp(18), 0, dp(18), dp(3));
                holder.row.setPadding(dp(8), dp(5), dp(8), dp(5));
                holder.row.setBackground(makeRoundedWithStroke(0x1F222222, dp(8), 0x1FFFFFFF));
                holder.title.setTextSize(10f);
                holder.title.setMaxLines(1);
                holder.time.setTextSize(9f);
                holder.room.setTextSize(9f);
            }
            holder.row.setLayoutParams(lp);

            String safeTitle = item.title.replaceAll("\\s*\\(.*?\\)$", "");
            holder.title.setText(safeTitle);
            holder.title.setTextColor(themeText);
            holder.time.setTextColor(themeMuted);
            holder.room.setTextColor(themeMuted);

            boolean hasMeta = !TextUtils.isEmpty(item.time) || !TextUtils.isEmpty(item.room);
            int stripeColor = item.color != 0 ? item.color : themeAccent;
            holder.stripe.setVisibility(hasMeta ? View.VISIBLE : View.GONE);
            holder.stripe.setBackground(makeRounded(stripeColor, dp(2)));
            ViewGroup.LayoutParams stripeRawLp = holder.stripe.getLayoutParams();
            if (stripeRawLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams stripeLp = (LinearLayout.LayoutParams) stripeRawLp;
                stripeLp.width = isPrimary ? dp(4) : dp(3);
                stripeLp.height = isPrimary ? dp(34) : dp(18);
                stripeLp.setMarginEnd(isPrimary ? dp(10) : dp(8));
                holder.stripe.setLayoutParams(stripeLp);
            }

            if (TextUtils.isEmpty(item.time)) {
                holder.time.setVisibility(View.GONE);
            } else {
                holder.time.setVisibility(View.VISIBLE);
                if (isPrimary) {
                    holder.time.setText(item.time);
                } else if (TextUtils.isEmpty(item.room)) {
                    holder.time.setText(item.time);
                } else {
                    holder.time.setText(item.time + " · " + item.room);
                }
            }

            if (!isPrimary || TextUtils.isEmpty(item.room)) {
                holder.room.setVisibility(View.GONE);
            } else {
                holder.room.setVisibility(View.VISIBLE);
                holder.room.setText(item.room);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final LinearLayout row;
            final View stripe;
            final TextView title;
            final TextView time;
            final TextView room;

            ViewHolder(@NonNull View itemView, View stripe, TextView title, TextView time, TextView room) {
                super(itemView);
                this.row = (LinearLayout) itemView;
                this.stripe = stripe;
                this.title = title;
                this.time = time;
                this.room = room;
            }
        }
    }
}
