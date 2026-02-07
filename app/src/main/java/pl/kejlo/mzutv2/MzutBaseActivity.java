package pl.kejlo.mzutv2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Base Activity that handles the offline indicator bar.
 * It injects the indicator into the root view and monitors network status.
 */
public abstract class MzutBaseActivity extends AppCompatActivity {

    private TextView offlineIndicator;
    private NetworkStatusHelper networkStatusHelper;
    private boolean isOfflineVisible = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int GREEN_DURATION_MS = 2000;
    private AlertDialog wearSyncDialog;
    private final android.content.BroadcastReceiver wearSyncRequestReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(true);
            rescheduleWearSyncPoller();
            showWearSyncDialog(intent);
        }
    };
    private final android.content.BroadcastReceiver wearSyncCancelReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (wearSyncDialog != null && wearSyncDialog.isShowing()) {
                wearSyncDialog.dismiss();
            }
            pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(false);
        }
    };
    private final Runnable wearSyncPoller = new Runnable() {
        @Override
        public void run() {
            pl.kejlo.mzutv2.wear.WearSyncManager.pollRequestDataItemAsync(
                    MzutBaseActivity.this.getApplicationContext());
            long delay = pl.kejlo.mzutv2.wear.WearSyncManager.getPollIntervalMs();
            handler.postDelayed(this, delay);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ensure session data (user, studies, auth) is loaded from storage
        MzutSession.getInstance(this);
        networkStatusHelper = new NetworkStatusHelper(this);
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(layoutResID);
        setupOfflineIndicator();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        setupOfflineIndicator();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        setupOfflineIndicator();
    }

    private void setupOfflineIndicator() {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null)
            return;

        // Inflate the indicator view
        getLayoutInflater().inflate(R.layout.layout_offline_indicator, root, true);
        offlineIndicator = findViewById(R.id.offlineIndicator);

        if (offlineIndicator != null) {
            // Ensure it's on top
            offlineIndicator.bringToFront();

            // Initial state: hidden
            offlineIndicator.setVisibility(View.GONE);
            offlineIndicator.setAlpha(0f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
        if (networkStatusHelper != null) {
            networkStatusHelper.observe(this, this::onNetworkStatusChanged);
        }
        pl.kejlo.mzutv2.wear.WearSyncManager.scheduleWearAutoSync(
                this.getApplicationContext());
        IntentFilter filter = new IntentFilter(
                pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
        IntentFilter cancelFilter = new IntentFilter(
                pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_CANCEL);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wearSyncRequestReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(wearSyncCancelReceiver, cancelFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wearSyncRequestReceiver, filter);
            registerReceiver(wearSyncCancelReceiver, cancelFilter);
        }
        handler.post(wearSyncPoller);
        pl.kejlo.mzutv2.wear.WearSyncManager.PendingRequest pending =
                pl.kejlo.mzutv2.wear.WearSyncManager.getPendingRequest(this);
        if (pending != null) {
            Intent i = new Intent(pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_REQUEST);
            i.putExtra(pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_NODE_ID, pending.nodeId);
            i.putExtra(pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_NODE_NAME, pending.nodeName);
            showWearSyncDialog(i);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (networkStatusHelper != null) {
            networkStatusHelper.removeObservers(this);
        }
        handler.removeCallbacks(wearSyncPoller);
        try {
            unregisterReceiver(wearSyncRequestReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(wearSyncCancelReceiver);
        } catch (Exception ignored) {
        }
    }

    private void showWearSyncDialog(Intent intent) {
        if (intent == null) {
            return;
        }
        if (wearSyncDialog != null && wearSyncDialog.isShowing()) {
            return;
        }
        pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(true);
        rescheduleWearSyncPoller();
        String nodeId = intent.getStringExtra(
                pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_NODE_ID);
        String nodeName = intent.getStringExtra(
                pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_NODE_NAME);
        String name = (nodeName != null && !nodeName.isEmpty())
                ? nodeName
                : getString(R.string.nav_watch_label);
        wearSyncDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wear_sync_request_title)
                .setMessage(getString(R.string.wear_sync_request_msg, name))
                .setPositiveButton(R.string.wear_sync_request_positive, (dialog, which) -> {
                    pl.kejlo.mzutv2.wear.WearSyncManager.sendSyncApprove(this, nodeId);
                    pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(true);
                    rescheduleWearSyncPoller();
                    dialog.dismiss();
                    showWearSyncProgressDialog();
                })
                .setNegativeButton(R.string.wear_sync_request_negative, (dialog, which) -> {
                    pl.kejlo.mzutv2.wear.WearSyncManager.sendSyncDecline(this, nodeId);
                    pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(false);
                    rescheduleWearSyncPoller();
                    dialog.dismiss();
                })
                .setOnDismissListener(d -> wearSyncDialog = null)
                .show();
    }

    private void showWearSyncProgressDialog() {
        pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(true);
        rescheduleWearSyncPoller();
        View content = getLayoutInflater().inflate(R.layout.dialog_watch_progress, null, false);
        TextView progressStatus = content.findViewById(R.id.watchProgressStatus);
        android.widget.ProgressBar progressBar = content.findViewById(R.id.watchProgressBar);
        TextView progressText = content.findViewById(R.id.watchProgressText);
        com.google.android.material.button.MaterialButton okButton =
                content.findViewById(R.id.watchProgressOk);
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        progressDialog.show();
        if (okButton != null) {
            okButton.setOnClickListener(v -> progressDialog.dismiss());
        }

        final boolean[] completed = new boolean[] { false };
        android.content.BroadcastReceiver progressReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int value = intent.getIntExtra(
                        pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_PROGRESS, 0);
                String status = intent.getStringExtra(
                        pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_STATUS);
                int v = Math.max(0, Math.min(100, value));
                if (v > 0 && v < 100) {
                    pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(true);
                    rescheduleWearSyncPoller();
                } else if (v >= 100 || v == 0) {
                    pl.kejlo.mzutv2.wear.WearSyncManager.setForceFastPolling(false);
                    rescheduleWearSyncPoller();
                }
                if (progressBar != null) {
                    progressBar.setProgress(v);
                }
                if (progressText != null) {
                    progressText.setText(getString(R.string.common_percent_format, v));
                }
                if (progressStatus != null && status != null && !status.isEmpty()) {
                    progressStatus.setText(status);
                }
                if (v >= 100 && !completed[0]) {
                    completed[0] = true;
                    if (progressStatus != null) {
                        progressStatus.setText(getString(R.string.wear_sync_success_msg));
                    }
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    if (progressText != null) {
                        progressText.setVisibility(View.GONE);
                    }
                    if (okButton != null) {
                        okButton.setVisibility(View.VISIBLE);
                    }
                    progressDialog.setCancelable(true);
                    progressDialog.setCanceledOnTouchOutside(true);
                }
            }
        };

        IntentFilter filter = new IntentFilter(
                pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_PROGRESS);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(progressReceiver, filter);
        }

        progressDialog.setOnDismissListener(dialog -> {
            try {
                unregisterReceiver(progressReceiver);
            } catch (Exception ignored) {
            }
        });
    }

    private void rescheduleWearSyncPoller() {
        handler.removeCallbacks(wearSyncPoller);
        handler.post(wearSyncPoller);
    }

    private void onNetworkStatusChanged(Boolean isConnected) {
        if (offlineIndicator == null)
            return;

        // Ensure default is connected if null
        boolean connected = isConnected != null && isConnected;

        if (!connected) {
            // OFFLINE
            showOfflineRed();
        } else {
            // ONLINE
            if (isOfflineVisible) {
                // If we were showing offline (or are currently showing it), transition to green
                // then hide
                showOnlineGreenAndHide();
            } else {
                // Just ensure it's hidden
                offlineIndicator.setVisibility(View.GONE);
            }
        }
    }

    private void showOfflineRed() {
        isOfflineVisible = true;

        // Cancel any pending hide
        handler.removeCallbacksAndMessages(null);

        offlineIndicator.setVisibility(View.VISIBLE);
        offlineIndicator.setText(R.string.offline_mode_label);
        offlineIndicator.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.offline_bar_bg));
        offlineIndicator.setTextColor(android.graphics.Color.WHITE);

        offlineIndicator.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
                .start();
    }

    private void showOnlineGreenAndHide() {
        // Turn green
        offlineIndicator.setText(R.string.online_mode_label);
        offlineIndicator.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.online_bar_bg));
        offlineIndicator.setTextColor(android.graphics.Color.WHITE);

        // Wait then hide
        handler.postDelayed(() -> {
            if (offlineIndicator != null) {
                offlineIndicator.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                offlineIndicator.setVisibility(View.GONE);
                                isOfflineVisible = false;
                            }
                        })
                        .start();
            }
        }, GREEN_DURATION_MS);
    }
}
