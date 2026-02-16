package pl.kejlo.mzutv2;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.android.gms.tasks.Task;

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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final String PREFS_ABOUT_CACHE = "mzut_about_cache";
    private static final String KEY_RATING = "play_store_rating";
    private static final String KEY_DOWNLOADS = "play_store_downloads";
    private static final String KEY_TIMESTAMP = "play_store_timestamp";
    // Cache for 24 hours
    private static final long CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

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

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }
}
