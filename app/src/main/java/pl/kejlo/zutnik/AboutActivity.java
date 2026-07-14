package pl.kejlo.zutnik;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import com.google.android.material.card.MaterialCardView;

public class AboutActivity extends PhoneAwareActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private android.widget.LinearLayout drawerContentRoot;

    private TextView aboutVersion;
    private ImageView aboutLogo;
    private View achievementsSection;
    private GridLayout achievementsGrid;
    private TextView achievementsProgress;

    private final Handler easterHandler = new Handler(Looper.getMainLooper());

    private static final long EASTER_HOLD_DURATION_MS = 5_000L;
    private static final float EASTER_HOLD_MAX_SCALE = 1.14f;

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
        achievementsSection = findViewById(R.id.achievementsSection);
        achievementsGrid = findViewById(R.id.achievementsGrid);
        achievementsProgress = findViewById(R.id.achievementsProgress);
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
        renderAchievements();
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
            AchievementManager.UnlockResult result = AchievementManager.unlock(
                    this,
                    AchievementManager.Achievement.EXPLORER);
            AchievementRewardDialog.show(this, result.record);
            renderAchievements();
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

    private void renderAchievements() {
        if (achievementsSection == null || achievementsGrid == null || achievementsProgress == null) {
            return;
        }

        List<AchievementManager.Record> records = AchievementManager.getAll(this);
        int unlockedCount = 0;
        for (AchievementManager.Record record : records) {
            if (record.isUnlocked()) {
                unlockedCount++;
            }
        }

        achievementsGrid.removeAllViews();
        if (unlockedCount == 0) {
            achievementsSection.setVisibility(View.GONE);
            return;
        }

        achievementsSection.setVisibility(View.VISIBLE);
        achievementsProgress.setText(getString(
                R.string.achievements_progress,
                unlockedCount,
                records.size()));

        int primary = ThemeManager.resolveColor(this, R.attr.mzPrimary);
        int muted = ThemeManager.resolveColor(this, R.attr.mzMuted);
        int border = ThemeManager.resolveColor(this, R.attr.mzBorderSoft);
        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        achievementsGrid.setColumnCount(columns);

        for (int index = 0; index < records.size(); index++) {
            AchievementManager.Record record = records.get(index);
            View item = getLayoutInflater().inflate(R.layout.item_achievement, achievementsGrid, false);
            MaterialCardView card = item.findViewById(R.id.achievementCard);
            ImageView icon = item.findViewById(R.id.achievementIcon);
            TextView title = item.findViewById(R.id.achievementTitle);
            TextView meta = item.findViewById(R.id.achievementMeta);

            AchievementManager.Achievement achievement = record.achievement;
            boolean unlocked = record.isUnlocked();
            title.setText(achievement.titleRes);
            icon.setImageResource(achievement.iconRes);
            icon.setContentDescription(getString(achievement.titleRes));
            icon.setImageTintList(null);

            if (unlocked) {
                meta.setText(R.string.achievement_tile_unlocked);
                card.setStrokeColor(primary);
                icon.setAlpha(1f);
            } else {
                meta.setText(R.string.achievement_tile_locked);
                card.setStrokeColor(border);
                icon.setAlpha(0.34f);
                title.setAlpha(0.68f);
                meta.setTextColor(muted);
            }

            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> AchievementDetailsDialog.show(this, record));

            GridLayout.LayoutParams layoutParams = new GridLayout.LayoutParams();
            layoutParams.width = 0;
            layoutParams.height = dpToPx(164);
            layoutParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            layoutParams.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
            item.setLayoutParams(layoutParams);

            item.setAlpha(0f);
            item.setTranslationY(dpToPx(8));
            achievementsGrid.addView(item);
            item.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * 55L)
                    .setDuration(220L)
                    .start();
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
        renderAchievements();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        easterHandler.removeCallbacksAndMessages(null);
    }
}
