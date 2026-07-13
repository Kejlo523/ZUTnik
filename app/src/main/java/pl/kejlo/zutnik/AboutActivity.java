package pl.kejlo.zutnik;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AboutActivity extends PhoneAwareActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private android.widget.LinearLayout drawerContentRoot;

    private TextView aboutVersion;
    private ImageView aboutLogo;

    private final Handler easterHandler = new Handler(Looper.getMainLooper());

    private static final long EASTER_HOLD_DURATION_MS = 5_000L;
    private static final float EASTER_HOLD_MAX_SCALE = 1.14f;
    private static final String PREFS_EASTER_EGG = "about_easter_egg";
    private static final String KEY_EASTER_DISCOVERED_AT = "discovered_at";
    private static final String KEY_EASTER_DISCOVERY_CODE = "discovery_code";

    private Runnable easterUnlockRunnable;
    private ValueAnimator easterHoldAnimator;
    private boolean easterUnlockedFromHold;
    private int easterTouchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);

        ShellHostHelper.mountContentLayout(
                this,
                R.layout.activity_about,
                MainNavHelper.Screen.ABOUT);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        aboutVersion = findViewById(R.id.aboutVersion);
        aboutLogo = findViewById(R.id.aboutLogo);
        easterTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        setupUI();
    }

    private void setupUI() {
        // Version
        String versionName = BuildConfig.VERSION_NAME;
        if (versionName == null || versionName.trim().isEmpty()) {
            aboutVersion.setText(R.string.common_dash);
        } else {
            aboutVersion.setText(getString(R.string.about_version_value, versionName));
        }

        // GitHub Button
        findViewById(R.id.cardGithub).setOnClickListener(v -> {
            openUrl("https://github.com/Kejlo523/ZUTnik", getString(R.string.about_github_title));
        });

        findViewById(R.id.cardRateUs).setOnClickListener(v -> openPlayStore(true));
        findViewById(R.id.btnViewPlayStore).setOnClickListener(v -> openPlayStore(false));
        findViewById(R.id.btnShareApp).setOnClickListener(v -> shareApp());

        // New Links
        findViewById(R.id.btnAppWebsite).setOnClickListener(v -> openUrl("https://zutnik.endozero.pl", getString(R.string.about_link_title_project)));
        findViewById(R.id.btnAuthorWebsite).setOnClickListener(v -> openUrl("https://endozero.pl", getString(R.string.about_link_title_author)));
        findViewById(R.id.btnPrivacyPolicy).setOnClickListener(v -> openUrl("https://zutnik.endozero.pl/privacy_policy.html", getString(R.string.about_link_title_privacy)));
        findViewById(R.id.btnContactEmail).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:kejlo@endozero.pl"));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                 Toast.makeText(this, getString(R.string.error_no_email_app), Toast.LENGTH_SHORT).show();
            }
        });

        setupEasterEggHold();
    }
    private void openUrl(String url, String title) {
        try {
            Intent intent = new Intent(this, WebLinkActivity.class);
            intent.putExtra(WebLinkActivity.EXTRA_URL, url);
            intent.putExtra(WebLinkActivity.EXTRA_TITLE, title);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.home_open_url_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void openPlayStore(boolean ratingIntent) {
        final String appPackageName = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)));
            if (ratingIntent) {
                InAppReviewPrompter.suppressAfterManualStoreOpen(this);
            }
        } catch (ActivityNotFoundException anfe) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)));
                if (ratingIntent) {
                    InAppReviewPrompter.suppressAfterManualStoreOpen(this);
                }
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, R.string.home_open_url_error, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void shareApp() {
        String playStoreUrl = "https://play.google.com/store/apps/details?id=" + getPackageName();
        Intent shareIntent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_share_subject))
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.about_share_text, playStoreUrl));
        startActivity(Intent.createChooser(shareIntent, getString(R.string.about_share_chooser)));
    }

    private void setupEasterEggHold() {
        if (aboutLogo == null) return;

        easterUnlockRunnable = () -> {
            easterUnlockedFromHold = true;
            cancelHoldAnimation(false);
            aboutLogo.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showEasterEggDialog();
            aboutLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start();
        };

        aboutLogo.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    easterUnlockedFromHold = false;
                    startHoldAnimation();
                    easterHandler.removeCallbacks(easterUnlockRunnable);
                    easterHandler.postDelayed(easterUnlockRunnable, EASTER_HOLD_DURATION_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!isPointInsideView(v, event, easterTouchSlop)) {
                        cancelHoldDetection(true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    cancelHoldDetection(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelHoldDetection(true);
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean isPointInsideView(View view, MotionEvent event, int touchSlopPx) {
        float x = event.getX();
        float y = event.getY();
        return x >= -touchSlopPx
                && y >= -touchSlopPx
                && x <= view.getWidth() + touchSlopPx
                && y <= view.getHeight() + touchSlopPx;
    }

    private void startHoldAnimation() {
        cancelHoldAnimation(false);
        easterHoldAnimator = ValueAnimator.ofFloat(1f, EASTER_HOLD_MAX_SCALE);
        easterHoldAnimator.setDuration(EASTER_HOLD_DURATION_MS);
        easterHoldAnimator.setInterpolator(new LinearInterpolator());
        easterHoldAnimator.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            aboutLogo.setScaleX(scale);
            aboutLogo.setScaleY(scale);
        });
        easterHoldAnimator.start();
    }

    private void cancelHoldAnimation(boolean animateBack) {
        if (easterHoldAnimator != null) {
            easterHoldAnimator.cancel();
            easterHoldAnimator = null;
        }
        if (aboutLogo != null && animateBack) {
            aboutLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start();
        }
    }

    private void cancelHoldDetection(boolean animateBack) {
        easterHandler.removeCallbacks(easterUnlockRunnable);
        if (!easterUnlockedFromHold) {
            cancelHoldAnimation(animateBack);
        }
    }

    private void showEasterEggDialog() {
        SharedPreferences rewardPreferences = getSharedPreferences(PREFS_EASTER_EGG, MODE_PRIVATE);
        long discoveredAt = rewardPreferences.getLong(KEY_EASTER_DISCOVERED_AT, 0L);
        String discoveryCode = rewardPreferences.getString(KEY_EASTER_DISCOVERY_CODE, null);
        if (discoveredAt <= 0L || discoveryCode == null || discoveryCode.isEmpty()) {
            discoveredAt = System.currentTimeMillis();
            discoveryCode = String.format(
                    Locale.ROOT,
                    "ZN-%04X",
                    new SecureRandom().nextInt(0x10000));
            rewardPreferences.edit()
                    .putLong(KEY_EASTER_DISCOVERED_AT, discoveredAt)
                    .putString(KEY_EASTER_DISCOVERY_CODE, discoveryCode)
                    .apply();
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_about_easter_egg);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        View root = dialog.findViewById(R.id.easterRoot);
        View rewardCard = dialog.findViewById(R.id.easterRewardCard);
        View accentLine = dialog.findViewById(R.id.easterAccentLine);
        ImageButton closeButton = dialog.findViewById(R.id.easterCloseButton);
        ImageView centerLogo = dialog.findViewById(R.id.easterCenterLogo);
        TextView discoveryDate = dialog.findViewById(R.id.easterDiscoveryDate);
        TextView discoveryCodeView = dialog.findViewById(R.id.easterDiscoveryCode);
        View doneButton = dialog.findViewById(R.id.easterDoneButton);

        Locale locale = getResources().getConfiguration().getLocales().get(0);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale);
        discoveryDate.setText(Instant.ofEpochMilli(discoveredAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(formatter));
        discoveryCodeView.setText(discoveryCode);

        root.setOnClickListener(v -> dialog.dismiss());
        rewardCard.setOnClickListener(v -> { });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        doneButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            dialog.dismiss();
        });

        rewardCard.setAlpha(0f);
        rewardCard.setTranslationY(dpToPx(28f));
        rewardCard.setScaleX(0.98f);
        rewardCard.setScaleY(0.98f);
        centerLogo.setScaleX(0.82f);
        centerLogo.setScaleY(0.82f);
        centerLogo.setRotation(-8f);
        accentLine.setPivotX(0f);
        accentLine.setScaleX(0f);
        doneButton.setAlpha(0f);
        doneButton.setTranslationY(dpToPx(10f));

        dialog.setOnShowListener(ignored -> {
            DecelerateInterpolator interpolator = new DecelerateInterpolator();
            rewardCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420L)
                    .setInterpolator(interpolator)
                    .start();
            centerLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setStartDelay(90L)
                    .setDuration(480L)
                    .setInterpolator(interpolator)
                    .start();
            accentLine.animate()
                    .scaleX(1f)
                    .setStartDelay(120L)
                    .setDuration(520L)
                    .setInterpolator(interpolator)
                    .start();
            doneButton.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(220L)
                    .setDuration(320L)
                    .setInterpolator(interpolator)
                    .start();
        });

        dialog.show();
        rewardCard.post(() -> {
            int availableWidth = root.getWidth() - Math.round(dpToPx(32f));
            int targetWidth = Math.min(availableWidth, Math.round(dpToPx(440f)));
            ViewGroup.LayoutParams layoutParams = rewardCard.getLayoutParams();
            layoutParams.width = Math.max(0, targetWidth);
            rewardCard.setLayoutParams(layoutParams);
        });
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        easterHandler.removeCallbacksAndMessages(null);
    }
}
