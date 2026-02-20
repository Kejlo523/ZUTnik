package pl.kejlo.mzutv2;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class NewsActivity extends MzutBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    // Cache
    private static final String PREFS_NEWS_CACHE = "mzut_news_cache";
    private static final String KEY_NEWS_LIST_JSON = "news_list_json";
    private static final String KEY_NEWS_TIMESTAMP = "news_timestamp";
    private static final long NEWS_CACHE_TTL_MS = CachePolicy.NEWS_TTL_MS;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private RecyclerView listNews;
    private ProgressBar progress;
    private TextView tvEmpty;
    private TextView tvInfo;
    private ImageView btnNewsRefresh;

    private NewsRepository repo;
    private final List<NewsItem> items = new ArrayList<>();

    private NewsAdapter adapter;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentNewsFuture;

    private boolean openLatestOnLoad = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new NewsRepository(this);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_news);
        ThemeManager.applySystemBars(this);

        openLatestOnLoad = getIntent().getBooleanExtra("EXTRA_OPEN_LATEST", false);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setTitle(R.string.news_title);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.NEWS);

        listNews = findViewById(R.id.listNews);
        progress = findViewById(R.id.newsProgress);
        tvEmpty = findViewById(R.id.tvNewsEmpty);
        tvInfo = findViewById(R.id.tvNewsInfo);
        btnNewsRefresh = findViewById(R.id.btnNewsRefresh);

        listNews.setLayoutManager(new LinearLayoutManager(this));

        adapter = new NewsAdapter(this, items);
        listNews.setAdapter(adapter);
        setRefreshing(false);

        // 1) Try to show cached data
        loadNewsFromCacheIfAvailable();
        updateNewsInfo(getCacheTimestamp());
        checkAutoOpen();

        // 2) If cache is missing or outdated, fetch from network
        if (shouldFetchFromNetwork()) {
            startLoadNews(false);
        }

        // 3) Manual refresh – always forces a network fetch
        if (btnNewsRefresh != null) {
            btnNewsRefresh.setOnClickListener(v -> {
                Toast.makeText(
                        NewsActivity.this,
                        R.string.news_refresh_toast,
                        Toast.LENGTH_SHORT).show();
                startLoadNews(true);
            });
        }
    }

    private void checkAutoOpen() {
        if (openLatestOnLoad && !items.isEmpty()) {
            openLatestOnLoad = false; // Consume
            NewsItem item = items.get(0);
            Intent i = new Intent(this, NewsDetailActivity.class);
            i.putExtra("title", item.title);
            i.putExtra("date", item.date);
            i.putExtra("link", item.link);
            i.putExtra("contentHtml", item.contentHtml);
            i.putExtra("descriptionText", item.descriptionText);
            startActivity(i);
        }
    }

    // Start loading

    private void startLoadNews(boolean forceReload) {
        if (currentNewsFuture != null) {
            currentNewsFuture.cancel(true);
        }

        if (forceReload) {
            // Clear cache immediately
            getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_NEWS_LIST_JSON)
                    .remove(KEY_NEWS_TIMESTAMP)
                    .apply();

            // Clear items from adapter to visual feedback of reload
            items.clear();
            adapter.notifyDataSetChanged();
            updateNewsInfo(0L);

            // Also suggest clearing image cache if desired
            ImageCache.getInstance().clear();
        }

        executeLoadNewsTask();
    }

    private void executeLoadNewsTask() {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        setRefreshing(true);

        currentNewsFuture = executor.submit(() -> {
            List<NewsItem> loaded = null;
            Exception error = null;
            boolean success = false;

            try {
                loaded = repo.loadNews();
                success = true;
            } catch (Exception e) {
                error = e;
            }

            final List<NewsItem> finalLoaded = loaded;
            final Exception finalError = error;
            final boolean finalSuccess = success;

            handler.post(() -> {
                progress.setVisibility(View.GONE);
                setRefreshing(false);

                if (!finalSuccess || finalLoaded == null) {
                    if (items.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                    if (finalError != null) {
                        String msg = finalError.getMessage() != null ? finalError.getMessage() : "";
                        Toast.makeText(
                                NewsActivity.this,
                                getString(R.string.news_error_rss, msg),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                items.clear();
                items.addAll(finalLoaded);
                adapter.notifyDataSetChanged();

                if (items.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }

                // Save to cache after successful fetch
                saveNewsToCache(finalLoaded);
                updateNewsInfo(getCacheTimestamp());
                checkAutoOpen();
            });
        });
    }

    // Cache logic

    private boolean shouldFetchFromNetwork() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE);
        long ts = prefs.getLong(KEY_NEWS_TIMESTAMP, 0L);
        if (ts == 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        return (now - ts) > NEWS_CACHE_TTL_MS;
    }

    private long getCacheTimestamp() {
        return getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE)
                .getLong(KEY_NEWS_TIMESTAMP, 0L);
    }

    private void updateNewsInfo(long timestamp) {
        if (tvInfo == null) {
            return;
        }
        String baseInfo = getString(R.string.news_header_source_info);
        if (timestamp <= 0L) {
            tvInfo.setText(baseInfo);
            return;
        }
        long now = System.currentTimeMillis();
        CharSequence relative;
        if (Math.abs(now - timestamp) < DateUtils.MINUTE_IN_MILLIS) {
            relative = getString(R.string.news_relative_just_now);
        } else {
            relative = DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    now,
                    DateUtils.MINUTE_IN_MILLIS);
        }
        tvInfo.setText(baseInfo + " \u2022 " + relative);
    }

    private void setRefreshing(boolean refreshing) {
        if (btnNewsRefresh == null) {
            return;
        }

        btnNewsRefresh.setEnabled(!refreshing);
        btnNewsRefresh.setAlpha(0.6f);
    }

    private void loadNewsFromCacheIfAvailable() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE);
        String json = prefs.getString(KEY_NEWS_LIST_JSON, null);
        if (json == null || json.trim().isEmpty()) {
            return;
        }

        try {
            JSONArray arr = new JSONArray(json);
            items.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }

                NewsItem ni = new NewsItem();
                ni.id = obj.optInt("id", i);
                ni.title = obj.optString("title", "");
                ni.link = obj.optString("link", null);
                ni.pubDateRaw = obj.optString("pubDateRaw", null);
                ni.date = obj.optString("date", "");
                ni.snippet = obj.optString("snippet", "");
                ni.descriptionHtml = obj.optString("descriptionHtml", null);
                ni.descriptionText = obj.optString("descriptionText", "");
                ni.contentHtml = obj.optString("contentHtml", null);
                ni.thumbUrl = obj.optString("thumbUrl", null);

                items.add(ni);
            }
            adapter.notifyDataSetChanged();

            if (items.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }

        } catch (JSONException e) {
            // Ignore cache on error
        }
    }

    private void saveNewsToCache(List<NewsItem> list) {
        if (list == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE);
        JSONArray arr = new JSONArray();

        try {
            for (NewsItem n : list) {
                if (n == null) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("id", n.id);
                obj.put("title", n.title != null ? n.title : "");
                obj.put("link", n.link != null ? n.link : "");
                obj.put("pubDateRaw", n.pubDateRaw != null ? n.pubDateRaw : "");
                obj.put("date", n.date != null ? n.date : "");
                obj.put("snippet", n.snippet != null ? n.snippet : "");
                obj.put("descriptionHtml", n.descriptionHtml != null ? n.descriptionHtml : "");
                obj.put("descriptionText", n.descriptionText != null ? n.descriptionText : "");
                obj.put("contentHtml", n.contentHtml != null ? n.contentHtml : "");
                obj.put("thumbUrl", n.thumbUrl != null ? n.thumbUrl : "");
                arr.put(obj);
            }

            prefs.edit()
                    .putString(KEY_NEWS_LIST_JSON, arr.toString())
                    .putLong(KEY_NEWS_TIMESTAMP, System.currentTimeMillis())
                    .apply();

        } catch (JSONException e) {
            // Cache is optional, ignore errors
        }
    }

    @Override
    protected void onDestroy() {
        if (currentNewsFuture != null) {
            currentNewsFuture.cancel(true);
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}

