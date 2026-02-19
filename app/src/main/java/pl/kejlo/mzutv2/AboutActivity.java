package pl.kejlo.mzutv2;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private android.widget.LinearLayout drawerContentRoot;

    private TextView textPlayStoreStats;
    private TextView aboutVersion;
    private ImageView aboutLogo;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler easterHandler = new Handler(Looper.getMainLooper());

    private static final String PREFS_ABOUT_CACHE = "mzut_about_cache";
    private static final String KEY_RATING = "play_store_rating";
    private static final String KEY_DOWNLOADS = "play_store_downloads";
    private static final String KEY_TIMESTAMP = "play_store_timestamp";
    // Cache for 24 hours
    private static final long CACHE_TTL_MS = 24L * 60L * 60L * 1000L;
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
        setContentView(R.layout.activity_about);
        ThemeManager.applySystemBars(this);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);
        textPlayStoreStats = findViewById(R.id.textPlayStoreStats);
        aboutVersion = findViewById(R.id.aboutVersion);
        aboutLogo = findViewById(R.id.aboutLogo);
        easterTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setTitle(R.string.about_title);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.ABOUT);

        setupUI();
        loadPlayStoreStats();
    }

    private void setupUI() {
        // Version
        try {
            String versionName = BuildConfig.VERSION_NAME;
            aboutVersion.setText(getString(R.string.about_version_prefix) + versionName);
        } catch (Exception e) {
            aboutVersion.setText(getString(R.string.about_version_prefix) + "1.0");
        }

        // GitHub Button
        findViewById(R.id.cardGithub).setOnClickListener(v -> {
            openUrl("https://github.com/Kejlo523/mzut-v2", "GitHub");
        });

        // Rate Us Button - Direct Play Store link
        findViewById(R.id.cardRateUs).setOnClickListener(v -> {
            openPlayStore();
        });

        // New Links
        findViewById(R.id.btnAppWebsite).setOnClickListener(v -> openUrl("https://mzut.endozero.pl", getString(R.string.about_link_title_project)));
        findViewById(R.id.btnAuthorWebsite).setOnClickListener(v -> openUrl("https://endozero.pl", getString(R.string.about_link_title_author)));
        findViewById(R.id.btnPrivacyPolicy).setOnClickListener(v -> openUrl("https://mzut.endozero.pl/privacy_policy.html", getString(R.string.about_link_title_privacy)));
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

    /*
    private void launchInAppReview() {
        // Disabled due to Google Play Quotas making it unreliable for a static button.
        // Reverted to openPlayStore().
    }
    */

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

    private void openPlayStore() {
        final String appPackageName = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)));
        } catch (ActivityNotFoundException anfe) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)));
        }
    }

    private void loadPlayStoreStats() {
        // 1. Try Cache
        SharedPreferences prefs = getSharedPreferences(PREFS_ABOUT_CACHE, MODE_PRIVATE);
        String cachedRating = prefs.getString(KEY_RATING, null);
        String cachedDownloads = prefs.getString(KEY_DOWNLOADS, null);
        long lastFetch = prefs.getLong(KEY_TIMESTAMP, 0L);

        if (cachedRating != null) {
            updateStatsText(cachedRating, cachedDownloads);
        }

        // 2. Fetch network if expired or empty
        if (System.currentTimeMillis() - lastFetch > CACHE_TTL_MS || cachedRating == null) {
            fetchStatsFromNetwork();
        }
    }

    private void updateStatsText(String rating, String downloads) {
        if (rating == null) return;
        
        String text;
        if (downloads != null) {
             text = getString(R.string.about_stats_format_full, rating, downloads);
        } else {
             text = getString(R.string.about_stats_format_rating_only, rating);
        }
        textPlayStoreStats.setText(text);
    }

    private void fetchStatsFromNetwork() {
        executor.execute(() -> {
            String rating = null;
            String downloads = null;
            
            try {
                String packageName = getPackageName();
                String url = "https://play.google.com/store/apps/details?id=" + packageName + "&hl=en";
                
                OkHttpClient client = new OkHttpClient.Builder()
                        .followRedirects(true)
                        .retryOnConnectionFailure(true)
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String html = response.body().string();
                        
                        // 1. JSON-LD Strategy
                        try {
                             Pattern pJson = Pattern.compile("<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);
                             Matcher mJson = pJson.matcher(html);
                             while (mJson.find()) {
                                 String json = mJson.group(1);
                                 if (json != null && json.contains("ratingValue")) {
                                     Pattern pVal = Pattern.compile("\"ratingValue\":\\s*\"([0-9.]+)\"");
                                     Matcher mVal = pVal.matcher(json);
                                     if (mVal.find()) {
                                         rating = mVal.group(1);
                                         if (rating.length() > 3) rating = rating.substring(0, 3);
                                     }
                                     break;
                                 }
                             }
                        } catch (Exception ignored) {}

                        // 2. Regex Strategy (Fallback for Rating)
                        if (rating == null) {
                            Pattern pRating = Pattern.compile("aria-label=\"Rated ([0-9]+[.,]?[0-9]?) stars");
                            Matcher mRating = pRating.matcher(html);
                            if (mRating.find()) {
                                rating = mRating.group(1);
                            }
                        }

                        // 3. Downloads Strategy
                        Pattern pDownloads = Pattern.compile(">\\s*([0-9,.]+[KkMm]?\\+)\\s*<"); 
                        Matcher mDownloads = pDownloads.matcher(html);
                        while (mDownloads.find()) {
                            String cand = mDownloads.group(1);
                            if (cand.contains("K") || cand.contains("M") || cand.length() >= 3) {
                                 downloads = cand;
                                 break;
                            }
                            if (cand.contains("00+")) {
                                downloads = cand;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Fallbacks requested by user
            if (rating == null) rating = "5.0";
            if (downloads == null) downloads = "100+";

            String finalRating = rating;
            String finalDownloads = downloads;
            
            handler.post(() -> {
                updateStatsText(finalRating, finalDownloads);
                
                // Cache
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_ABOUT_CACHE, MODE_PRIVATE).edit();
                editor.putString(KEY_RATING, finalRating);
                editor.putString(KEY_DOWNLOADS, finalDownloads);
                editor.putLong(KEY_TIMESTAMP, System.currentTimeMillis());
                editor.apply();
            });
        });
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
        View centerColumn = dialog.findViewById(R.id.easterCenterColumn);
        ImageButton closeButton = dialog.findViewById(R.id.easterCloseButton);
        ImageView centerLogo = dialog.findViewById(R.id.easterCenterLogo);
        View nebulaOne = dialog.findViewById(R.id.easterNebulaOne);
        View nebulaTwo = dialog.findViewById(R.id.easterNebulaTwo);
        View orbitFast = dialog.findViewById(R.id.easterOrbitFast);
        View orbitSlow = dialog.findViewById(R.id.easterOrbitSlow);
        View ring = dialog.findViewById(R.id.easterOrbitRing);
        View ringInner = dialog.findViewById(R.id.easterOrbitRingInner);
        View glow = dialog.findViewById(R.id.easterGlow);
        View starOne = dialog.findViewById(R.id.easterStarOne);
        View starTwo = dialog.findViewById(R.id.easterStarTwo);
        View starThree = dialog.findViewById(R.id.easterStarThree);
        View starFour = dialog.findViewById(R.id.easterStarFour);

        ArrayList<ValueAnimator> loopingAnimators = new ArrayList<>();

        ObjectAnimator ringRotate = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f);
        startInfiniteAnimator(ringRotate, 8_500L, 0L, false, loopingAnimators);

        ObjectAnimator ringInnerRotate = ObjectAnimator.ofFloat(ringInner, View.ROTATION, 360f, 0f);
        startInfiniteAnimator(ringInnerRotate, 6_000L, 0L, false, loopingAnimators);

        ObjectAnimator orbitFastRotate = ObjectAnimator.ofFloat(orbitFast, View.ROTATION, 0f, 360f);
        startInfiniteAnimator(orbitFastRotate, 2_100L, 0L, false, loopingAnimators);

        ObjectAnimator orbitSlowRotate = ObjectAnimator.ofFloat(orbitSlow, View.ROTATION, 360f, 0f);
        startInfiniteAnimator(orbitSlowRotate, 3_800L, 120L, false, loopingAnimators);

        ObjectAnimator logoPulse = ObjectAnimator.ofPropertyValuesHolder(
                centerLogo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.09f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.09f, 1f));
        startInfiniteAnimator(logoPulse, 1_120L, 0L, false, loopingAnimators);

        ObjectAnimator glowPulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.45f, 0.95f, 0.45f);
        startInfiniteAnimator(glowPulse, 1_250L, 0L, false, loopingAnimators);

        float nebulaOneBaseX = nebulaOne.getTranslationX();
        float nebulaOneBaseY = nebulaOne.getTranslationY();
        float nebulaTwoBaseX = nebulaTwo.getTranslationX();
        float nebulaTwoBaseY = nebulaTwo.getTranslationY();

        ObjectAnimator nebulaOneX = ObjectAnimator.ofFloat(
                nebulaOne,
                View.TRANSLATION_X,
                nebulaOneBaseX,
                nebulaOneBaseX + dpToPx(34f),
                nebulaOneBaseX - dpToPx(2f));
        startInfiniteAnimator(nebulaOneX, 12_000L, 0L, false, loopingAnimators);
        ObjectAnimator nebulaOneY = ObjectAnimator.ofFloat(
                nebulaOne,
                View.TRANSLATION_Y,
                nebulaOneBaseY,
                nebulaOneBaseY + dpToPx(32f),
                nebulaOneBaseY - dpToPx(4f));
        startInfiniteAnimator(nebulaOneY, 11_000L, 0L, false, loopingAnimators);

        ObjectAnimator nebulaTwoX = ObjectAnimator.ofFloat(
                nebulaTwo,
                View.TRANSLATION_X,
                nebulaTwoBaseX,
                nebulaTwoBaseX - dpToPx(29f),
                nebulaTwoBaseX + dpToPx(6f));
        startInfiniteAnimator(nebulaTwoX, 13_000L, 200L, false, loopingAnimators);
        ObjectAnimator nebulaTwoY = ObjectAnimator.ofFloat(
                nebulaTwo,
                View.TRANSLATION_Y,
                nebulaTwoBaseY,
                nebulaTwoBaseY - dpToPx(36f),
                nebulaTwoBaseY + dpToPx(6f));
        startInfiniteAnimator(nebulaTwoY, 10_500L, 150L, false, loopingAnimators);

        ObjectAnimator starOneTwinkle = ObjectAnimator.ofFloat(starOne, View.ALPHA, 0.35f, 1f, 0.35f);
        startInfiniteAnimator(starOneTwinkle, 920L, 40L, false, loopingAnimators);
        ObjectAnimator starTwoTwinkle = ObjectAnimator.ofFloat(starTwo, View.ALPHA, 0.25f, 0.95f, 0.25f);
        startInfiniteAnimator(starTwoTwinkle, 1_360L, 180L, false, loopingAnimators);
        ObjectAnimator starThreeTwinkle = ObjectAnimator.ofFloat(starThree, View.ALPHA, 0.3f, 1f, 0.3f);
        startInfiniteAnimator(starThreeTwinkle, 1_090L, 90L, false, loopingAnimators);
        ObjectAnimator starFourTwinkle = ObjectAnimator.ofFloat(starFour, View.ALPHA, 0.28f, 0.9f, 0.28f);
        startInfiniteAnimator(starFourTwinkle, 1_520L, 240L, false, loopingAnimators);

        centerLogo.setOnClickListener(v -> {
            centerLogo.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            centerLogo.animate()
                    .rotationBy(540f)
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(280L)
                    .withEndAction(() -> centerLogo.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(320L)
                            .start())
                    .start();
            centerColumn.animate()
                    .scaleX(1.03f)
                    .scaleY(1.03f)
                    .setDuration(220L)
                    .withEndAction(() -> centerColumn.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(260L)
                            .start())
                    .start();
        });
        root.setOnClickListener(v -> dialog.dismiss());
        centerColumn.setOnClickListener(v -> {
            // Consume clicks so tap-outside closes only when touching the backdrop.
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(dialogInterface -> {
            for (ValueAnimator animator : loopingAnimators) {
                animator.cancel();
            }
        });

        dialog.show();
    }

    private void startInfiniteAnimator(
            ValueAnimator animator,
            long durationMs,
            long startDelayMs,
            boolean reverseMode,
            ArrayList<ValueAnimator> bucket) {
        animator.setDuration(durationMs);
        animator.setStartDelay(startDelayMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(reverseMode ? ValueAnimator.REVERSE : ValueAnimator.RESTART);
        animator.start();
        bucket.add(animator);
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
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
