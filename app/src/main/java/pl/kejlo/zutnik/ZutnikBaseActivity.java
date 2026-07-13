package pl.kejlo.zutnik;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;

/** Adds a compact, non-blocking connection status to application screens. */
public abstract class ZutnikBaseActivity extends PhoneAwareActivity {

    private static final int ONLINE_CONFIRMATION_MS = 1_600;

    private MaterialCardView offlineIndicator;
    private ImageView offlineIndicatorIcon;
    private TextView offlineIndicatorText;
    private NetworkStatusHelper networkStatusHelper;
    private boolean isOfflineVisible;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ZutnikSession.getInstance(this);
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
        if (root == null) {
            return;
        }

        getLayoutInflater().inflate(R.layout.layout_offline_indicator, root, true);
        offlineIndicator = findViewById(R.id.offlineIndicator);
        offlineIndicatorIcon = findViewById(R.id.offlineIndicatorIcon);
        offlineIndicatorText = findViewById(R.id.offlineIndicatorText);
        if (offlineIndicator == null) {
            return;
        }

        offlineIndicator.bringToFront();
        offlineIndicator.setVisibility(View.GONE);
        offlineIndicator.setAlpha(0f);
        offlineIndicator.post(this::positionOfflineIndicator);
    }

    private void positionOfflineIndicator() {
        if (offlineIndicator == null
                || !(offlineIndicator.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }

        int systemBottomInset = 0;
        WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(offlineIndicator);
        if (rootInsets != null) {
            Insets systemBars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemBottomInset = systemBars.bottom;
        }

        View bottomNavigation = findViewById(R.id.bottomNavigation);
        int bottomMargin;
        if (bottomNavigation != null
                && bottomNavigation.getVisibility() == View.VISIBLE
                && bottomNavigation.getHeight() > 0) {
            bottomMargin = bottomNavigation.getHeight() + dpToPx(8);
        } else {
            bottomMargin = systemBottomInset + dpToPx(16);
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) offlineIndicator.getLayoutParams();
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = bottomMargin;
        offlineIndicator.setLayoutParams(params);
        offlineIndicator.bringToFront();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
        if (networkStatusHelper != null) {
            networkStatusHelper.observe(this, this::onNetworkStatusChanged);
        }
        if (offlineIndicator != null) {
            offlineIndicator.post(this::positionOfflineIndicator);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
            positionOfflineIndicator();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (networkStatusHelper != null) {
            networkStatusHelper.removeObservers(this);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void onNetworkStatusChanged(Boolean isConnected) {
        if (offlineIndicator == null || offlineIndicatorText == null || offlineIndicatorIcon == null) {
            return;
        }

        if (!Boolean.TRUE.equals(isConnected)) {
            showOfflineStatus();
        } else if (isOfflineVisible) {
            showOnlineConfirmation();
        } else {
            offlineIndicator.setVisibility(View.GONE);
        }
    }

    private void showOfflineStatus() {
        boolean animateIn = offlineIndicator.getVisibility() != View.VISIBLE;
        isOfflineVisible = true;
        handler.removeCallbacksAndMessages(null);

        int dangerColor = ThemeManager.resolveColor(this, R.attr.mzDanger);
        offlineIndicatorText.setText(R.string.offline_mode_label);
        offlineIndicatorIcon.setImageResource(R.drawable.ic_wifi_off);
        ImageViewCompat.setImageTintList(
                offlineIndicatorIcon,
                ColorStateList.valueOf(dangerColor));
        offlineIndicator.setStrokeColor(ThemeManager.resolveColor(this, R.attr.mzBorderStrong));
        offlineIndicator.setContentDescription(getString(R.string.offline_mode_label));
        offlineIndicator.setVisibility(View.VISIBLE);
        positionOfflineIndicator();

        if (animateIn) {
            offlineIndicator.setAlpha(0f);
            offlineIndicator.setTranslationY(dpToPx(8));
            offlineIndicator.setScaleX(0.97f);
            offlineIndicator.setScaleY(0.97f);
            offlineIndicator.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setListener(null)
                    .start();
        }
    }

    private void showOnlineConfirmation() {
        handler.removeCallbacksAndMessages(null);

        int successColor = ThemeManager.resolveColor(this, R.attr.mzSuccess);
        offlineIndicatorText.setText(R.string.online_mode_label);
        offlineIndicatorIcon.setImageResource(R.drawable.ic_check);
        ImageViewCompat.setImageTintList(
                offlineIndicatorIcon,
                ColorStateList.valueOf(successColor));
        offlineIndicator.setStrokeColor(ThemeManager.resolveColor(this, R.attr.mzBorderStrong));
        offlineIndicator.setContentDescription(getString(R.string.online_mode_label));

        handler.postDelayed(() -> offlineIndicator.animate()
                .alpha(0f)
                .translationY(dpToPx(6))
                .setDuration(180L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        offlineIndicator.setVisibility(View.GONE);
                        offlineIndicator.setTranslationY(0f);
                        isOfflineVisible = false;
                    }
                })
                .start(), ONLINE_CONFIRMATION_MS);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
