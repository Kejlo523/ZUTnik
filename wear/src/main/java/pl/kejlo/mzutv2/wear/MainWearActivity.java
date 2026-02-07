package pl.kejlo.mzutv2.wear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import androidx.core.graphics.ColorUtils;
import android.graphics.drawable.GradientDrawable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWearActivity extends Activity {

    private static final String TAG = "MZUTWearSync/WEAR";
    private static final long FAST_POLL_INTERVAL_MS = 1500L;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Button btnSync;
    private ImageButton btnCancel;
    private LinearLayout statusCard;
    private TextView status;
    private ProgressBar progress;
    private TextView progressText;
    private LinearLayout weekContainer;
    private ScrollView rootScroll;
    private LinearLayout contentRoot;
    private TextView titleView;
    private TextView descView;
    private TextView weekLabel;
    private int themeBg = 0xFF0B1020;
    private int themeCard = 0xFF111827;
    private int themeCardAlt = 0xFF141C2A;
    private int themeText = 0xFFFFFFFF;
    private int themeMuted = 0xFFB0B7C3;
    private int themeSubtle = 0xFF8F96A3;
    private int themeAccent = 0xFF4F8DFF;
    private String lastStatus = "";
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private boolean forceFastPolling = false;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollExecutor.execute(() ->
                    pl.kejlo.mzutv2.wear.sync.WearSyncPoller.pollOnce(getApplicationContext()));
            pollHandler.postDelayed(this, getPollIntervalMs());
        }
    };
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            publishWatchStatus();
            statusHandler.postDelayed(this, 5 * 60 * 1000);
        }
    };
    private final BroadcastReceiver snapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatusFromSnapshot();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);

        titleView = findViewById(R.id.wearTitle);
        descView = findViewById(R.id.wearDesc);
        btnSync = findViewById(R.id.btnWearSync);
        btnCancel = findViewById(R.id.btnWearCancel);
        statusCard = findViewById(R.id.wearStatusCard);
        status = findViewById(R.id.wearStatus);
        progress = findViewById(R.id.wearProgress);
        progressText = findViewById(R.id.wearProgressText);
        weekLabel = findViewById(R.id.wearWeekLabel);
        weekContainer = findViewById(R.id.weekContainer);
        rootScroll = findViewById(R.id.wearRootScroll);
        contentRoot = findViewById(R.id.wearContentRoot);

        if (titleView != null) {
            titleView.setText(R.string.wear_main_title);
        }
        if (descView != null) {
            descView.setText(R.string.wear_main_desc);
        }

        if (btnSync != null) {
            btnSync.setOnClickListener(v -> requestSync());
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> cancelRequest());
        }
        applyRoundInsets();
        setupRotaryScrolling();
        updateStatusFromSnapshot();
        // Manual sync only.
    }

    private void applyRoundInsets() {
        if (contentRoot == null) {
            return;
        }
        boolean isRound = getResources().getConfiguration().isScreenRound();
        if (isRound) {
            int pad = (int) (getResources().getDisplayMetrics().density * 12);
            contentRoot.setPadding(pad, pad, pad, pad);
        }
    }

    private void setupRotaryScrolling() {
        if (rootScroll == null) {
            return;
        }
        rootScroll.setFocusableInTouchMode(true);
        rootScroll.requestFocus();
        final float scrollFactor = ViewConfiguration.get(this).getScaledVerticalScrollFactor();
        rootScroll.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL
                    && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor;
                rootScroll.scrollBy(0, (int) delta);
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        IntentFilter filter = new IntentFilter();
        filter.addAction(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
        filter.addAction(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SYNC_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(snapshotReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(snapshotReceiver, filter);
        }
        pollHandler.post(pollRunnable);
        statusHandler.post(statusRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        unregisterReceiver(snapshotReceiver);
        pollHandler.removeCallbacks(pollRunnable);
        statusHandler.removeCallbacks(statusRunnable);
    }

    private void publishWatchStatus() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = -1;
            if (bm != null) {
                level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            String deviceName = Build.MODEL != null ? Build.MODEL : "Wear";
            PutDataMapRequest req = PutDataMapRequest.create(
                    pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_WATCH_STATUS);
            DataMap map = req.getDataMap();
            map.putInt(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_BATTERY, level);
            map.putString(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_DEVICE_NAME, deviceName);
            map.putLong(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_TIMESTAMP,
                    System.currentTimeMillis());
            Wearable.getDataClient(this)
                    .putDataItem(req.asPutDataRequest());
        } catch (Exception e) {
            Log.e(TAG, "publishWatchStatus: failed", e);
        }
    }

    private void requestSync() {
        executor.execute(() -> {
            try {
                Log.d(TAG, "requestSync: start");
                setForceFastPolling(true);
                reschedulePollerNow();
                List<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes == null || nodes.isEmpty()) {
                    Log.w(TAG, "requestSync: no phone nodes");
                    runOnUiThread(this::setIdleState);
                    runOnUiThread(() ->
                            Toast.makeText(this, R.string.wear_main_no_phone, Toast.LENGTH_SHORT).show());
                    return;
                }
                Log.d(TAG, "requestSync: nodes=" + nodes);
                runOnUiThread(() -> {
                    setStatusText(getString(R.string.wear_main_status_waiting_approval));
                    setProgressValue(15);
                    setStatusCardVisible(true);
                    setCancelVisible(true);
                    setProgressVisible(true);
                    setSyncEnabled(false);
                });
                for (Node n : nodes) {
                    Wearable.getMessageClient(this)
                            .sendMessage(n.getId(), pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_SYNC, new byte[0])
                            .addOnSuccessListener(r -> Log.d(TAG, "requestSync: sent to " + n.getDisplayName()))
                            .addOnFailureListener(e -> Log.e(TAG, "requestSync: send failed " + n.getDisplayName(), e));
                }
                // Fallback: send a DataItem request (more reliable on some devices)
                try {
                    PutDataMapRequest req = PutDataMapRequest.create(
                            pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_SYNC);
                    DataMap map = req.getDataMap();
                    map.putLong(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_TIMESTAMP,
                            System.currentTimeMillis());
                    Tasks.await(Wearable.getDataClient(this)
                            .putDataItem(req.asPutDataRequest().setUrgent()));
                    Log.d(TAG, "requestSync: DataItem sent");
                } catch (Exception e) {
                    Log.e(TAG, "requestSync: DataItem failed", e);
                }
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.wear_main_sync_sent, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "requestSync: failed", e);
                runOnUiThread(this::setIdleState);
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.wear_main_sync_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void cancelRequest() {
        executor.execute(() -> {
            try {
                Uri uri = Uri.parse("wear://*" +
                        pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_SYNC);
                Wearable.getDataClient(this).deleteDataItems(uri);
            } catch (Exception e) {
                Log.e(TAG, "cancelRequest: delete request failed", e);
            }
            try {
                List<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this)
                                .sendMessage(n.getId(),
                                        pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_CANCEL,
                                        new byte[0]);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "cancelRequest: notify failed", e);
            }
            try {
                PutDataMapRequest req = PutDataMapRequest.create(
                        pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_CANCEL);
                req.getDataMap().putLong(
                        pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_TIMESTAMP,
                        System.currentTimeMillis());
                Wearable.getDataClient(this)
                        .putDataItem(req.asPutDataRequest().setUrgent());
            } catch (Exception e) {
                Log.e(TAG, "cancelRequest: data item failed", e);
            }
            pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.setProgress(
                    this, 0, "");
            runOnUiThread(this::setIdleState);
        });
    }

    private void updateStatusFromSnapshot() {
        pl.kejlo.mzutv2.wear.model.WearPlanSnapshot snap =
                pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.load(this);
        Log.d(TAG, "updateStatusFromSnapshot: snap=" + (snap != null));
        applyThemeFromSnapshot(snap);
        if (snap != null && snap.loginRequired) {
            setStatusText(getString(R.string.wear_main_status_login_required));
            setProgressValue(0);
            setStatusCardVisible(true);
            setCancelVisible(false);
            setProgressVisible(false);
            setSyncEnabled(true);
            renderWeekList(snap);
            return;
        }
        String statusText = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.getStatus(this);
        int progressValue = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.getProgress(this);
        long lastSync = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.getLastSyncMs(this);
        boolean hasStatus = statusText != null && !statusText.isEmpty();
        boolean waitingApproval = hasStatus
                && statusText.equals(getString(R.string.wear_main_status_waiting_approval));
        boolean approved = hasStatus
                && statusText.equals(getString(R.string.wear_main_status_approved));
        boolean busy = waitingApproval || approved || (progressValue > 0 && progressValue < 100);
        setForceFastPolling(busy);
        if (busy) {
            reschedulePollerNow();
        }

        if (hasStatus) {
            setStatusText(statusText);
            if (!statusText.equals(lastStatus)) {
                lastStatus = statusText;
                if (statusText.equals(getString(R.string.wear_main_status_approved))) {
                    Toast.makeText(this, R.string.wear_main_status_approved, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (lastSync > 0) {
            setStatusText(getString(R.string.wear_main_status_received));
        } else {
            setStatusText(getString(R.string.wear_main_status_idle));
        }

        if (lastSync > 0 || hasStatus || progressValue > 0) {
            setStatusCardVisible(true);
        } else {
            setStatusCardVisible(false);
        }

        setProgressValue(progressValue);
        setCancelVisible(waitingApproval);
        setProgressVisible(busy);
        setSyncEnabled(!busy);
        renderWeekList(snap);
    }

    private void setIdleState() {
        setStatusText(getString(R.string.wear_main_status_idle));
        setProgressValue(0);
        setProgressVisible(false);
        setCancelVisible(false);
        setSyncEnabled(true);
        setStatusCardVisible(false);
        setForceFastPolling(false);
        reschedulePollerNow();
    }

    private long getPollIntervalMs() {
        if (forceFastPolling) {
            return FAST_POLL_INTERVAL_MS;
        }
        return 10_000L;
    }

    private void setForceFastPolling(boolean enabled) {
        forceFastPolling = enabled;
    }

    private void reschedulePollerNow() {
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    private void setStatusCardVisible(boolean visible) {
        if (statusCard != null) {
            statusCard.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void setCancelVisible(boolean visible) {
        if (btnCancel != null) {
            btnCancel.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void setProgressVisible(boolean visible) {
        if (progress != null) {
            progress.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (progressText != null) {
            progressText.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void setSyncEnabled(boolean enabled) {
        if (btnSync != null) {
            btnSync.setEnabled(enabled);
            btnSync.setAlpha(enabled ? 1f : 0.6f);
        }
    }

    private void setStatusText(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private void applyThemeFromSnapshot(pl.kejlo.mzutv2.wear.model.WearPlanSnapshot snap) {
        if (snap == null) {
            return;
        }
        if (snap.colorBg != 0) {
            themeBg = snap.colorBg;
        }
        if (snap.colorCard != 0) {
            themeCard = snap.colorCard;
        }
        if (snap.colorCardAlt != 0) {
            themeCardAlt = snap.colorCardAlt;
        }
        if (snap.colorText != 0) {
            themeText = snap.colorText;
        }
        if (snap.colorMuted != 0) {
            themeMuted = snap.colorMuted;
        }
        if (snap.colorSubtle != 0) {
            themeSubtle = snap.colorSubtle;
        }
        if (snap.colorAccent != 0) {
            themeAccent = snap.colorAccent;
        }

        if (contentRoot != null) {
            contentRoot.setBackgroundColor(themeBg);
        }
        if (weekLabel != null) {
            weekLabel.setTextColor(themeSubtle);
        }
        if (titleView != null) {
            titleView.setTextColor(themeText);
        }
        if (descView != null) {
            descView.setTextColor(themeMuted);
        }
        if (status != null) {
            status.setTextColor(themeText);
        }
        if (progressText != null) {
            progressText.setTextColor(themeMuted);
        }
        if (statusCard != null) {
            statusCard.setBackground(makeRounded(themeCard, 12, ColorUtils.setAlphaComponent(themeText, 30), 1));
        }
        if (progress != null) {
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(themeAccent));
            progress.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(themeMuted, 40)));
        }
        if (btnSync != null) {
            btnSync.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(themeAccent));
        }
        if (btnCancel != null) {
            btnCancel.setColorFilter(themeMuted);
        }
    }

    private void setProgressValue(int value) {
        int v = value;
        if (v < 0) {
            v = 0;
        } else if (v > 100) {
            v = 100;
        }
        if (progress != null) {
            progress.setProgress(v);
        }
        if (progressText != null) {
            progressText.setText(v + "%");
        }
    }

    private void renderWeekList(pl.kejlo.mzutv2.wear.model.WearPlanSnapshot snap) {
        if (weekContainer == null) {
            return;
        }
        weekContainer.removeAllViews();
        if (snap == null || snap.loginRequired) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.wear_main_status_login_required));
            tv.setTextSize(12f);
                tv.setTextColor(themeMuted);
                weekContainer.addView(tv);
                return;
            }

        if (snap.weekDays == null || snap.weekDays.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.tile_no_data));
            tv.setTextSize(12f);
            tv.setTextColor(themeMuted);
            weekContainer.addView(tv);
            return;
        }

        for (pl.kejlo.mzutv2.wear.model.WearPlanSnapshot.WeekDay day : snap.weekDays) {
            LinearLayout dayCard = new LinearLayout(this);
            dayCard.setOrientation(LinearLayout.VERTICAL);
            dayCard.setBackground(makeRounded(themeCard, 12, ColorUtils.setAlphaComponent(themeText, 30), 1));
            dayCard.setPadding(10, 8, 10, 8);
            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dayLp.setMargins(0, 6, 0, 6);

            TextView header = new TextView(this);
            header.setText(day.dateLabel != null ? day.dateLabel : "");
            header.setTextSize(12f);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextColor(themeText);
            header.setPadding(2, 2, 0, 6);
            dayCard.addView(header);

            if (day.events == null || day.events.isEmpty()) {
                TextView none = new TextView(this);
                none.setText(getString(R.string.tile_no_events));
                none.setTextSize(11f);
                none.setTextColor(themeSubtle);
                none.setPadding(8, 0, 0, 4);
                dayCard.addView(none);
                weekContainer.addView(dayCard, dayLp);
                continue;
            }

            for (pl.kejlo.mzutv2.wear.model.WearPlanSnapshot.Event ev : day.events) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(8, 6, 8, 6);
                row.setBackground(makeRounded(themeCardAlt, 8, 0, 0));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 2, 0, 2);

                TextView stripe = new TextView(this);
                int color = ev.color != 0 ? ev.color : 0xFF4F8DFF;
                stripe.setBackgroundColor(color);
                LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(4, 22);
                stripeLp.setMarginEnd(6);
                row.addView(stripe, stripeLp);

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView title = new TextView(this);
                title.setText(ev.title != null ? ev.title : "");
                title.setTextSize(12f);
                title.setTextColor(themeText);
                col.addView(title);

                TextView time = new TextView(this);
                time.setText(ev.time != null ? ev.time : "");
                time.setTextSize(10f);
                time.setTextColor(themeMuted);
                col.addView(time);

                if (ev.room != null && !ev.room.isEmpty()) {
                    TextView room = new TextView(this);
                    room.setText(ev.room);
                    room.setTextSize(10f);
                    room.setTextColor(themeSubtle);
                    col.addView(room);
                }

                row.addView(col);
                dayCard.addView(row, rowLp);
            }
            weekContainer.addView(dayCard, dayLp);
        }
    }

    private GradientDrawable makeRounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0 && strokeDp > 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
