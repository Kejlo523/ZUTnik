package pl.kejlo.mzutv2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
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
