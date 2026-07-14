package pl.kejlo.zutnik;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class NewsActivity extends ZutnikBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private static final String PREFS_NEWS_CACHE = "zutnik_news_cache";
    private static final String KEY_NEWS_LIST_JSON = "news_list_json";
    private static final String KEY_NEWS_TIMESTAMP = "news_timestamp";
    private static final long NEWS_CACHE_TTL_MS = CachePolicy.NEWS_TTL_MS;

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
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applySystemBars(this);
        repo = new NewsRepository(this);

        openLatestOnLoad = getIntent().getBooleanExtra("EXTRA_OPEN_LATEST", false);

        ShellHostHelper.MountedContent shell = ShellHostHelper.mountContentLayout(
                this,
                R.layout.activity_news,
                MainNavHelper.Screen.NEWS);
        View content = shell.contentRoot;

        drawerContentRoot = content.findViewById(R.id.drawerContentRoot);

        listNews = content.findViewById(R.id.listNews);
        progress = content.findViewById(R.id.newsProgress);
        tvEmpty = content.findViewById(R.id.tvNewsEmpty);
        tvInfo = content.findViewById(R.id.tvNewsInfo);
        btnNewsRefresh = content.findViewById(R.id.btnNewsRefresh);

        listNews.setLayoutManager(new LinearLayoutManager(this));
        listNews.setItemAnimator(null);

        adapter = new NewsAdapter(this);
        listNews.setAdapter(adapter);
        setRefreshing(false);

        loadNewsFromCacheIfAvailable();
        updateNewsInfo(getCacheTimestamp());
        checkAutoOpen();

        if (shouldFetchFromNetwork()) {
            startLoadNews(false);
        }

        if (btnNewsRefresh != null) {
            btnNewsRefresh.setOnClickListener(v -> {
                NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                        NewsActivity.this,
                        NetworkRefreshPolicy.Module.NEWS,
                        NetworkRefreshPolicy.Mode.MANUAL,
                        "news",
                        getCacheTimestamp());
                if (!decision.allowNetwork) {
                    Toast.makeText(
                            NewsActivity.this,
                            NetworkRefreshPolicy.describeForUser(NewsActivity.this, decision),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
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
            openLatestOnLoad = false;
            NewsItem item = items.get(0);
            Intent i = new Intent(this, NewsDetailActivity.class);
            i.putExtra("title", item.title);
            i.putExtra("date", item.date);
            i.putExtra("link", item.link);
            i.putExtra("contentHtml", item.contentHtml);
            i.putExtra("descriptionText", item.descriptionText);
            startActivity(i);
            overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit);
        }
    }

    private void startLoadNews(boolean forceReload) {
        if (currentNewsFuture != null) {
            currentNewsFuture.cancel(true);
        }

        if (forceReload) {
            ImageCache.getInstance().clear();
        }

        executeLoadNewsTask(forceReload ? NetworkRefreshPolicy.Mode.MANUAL : NetworkRefreshPolicy.Mode.SCREEN_AUTO);
    }

    private void executeLoadNewsTask(NetworkRefreshPolicy.Mode mode) {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        setRefreshing(true);

        currentNewsFuture = executor.submit(() -> {
            List<NewsItem> loaded = null;
            Exception error = null;
            boolean success = false;

            try {
                NetworkRefreshPolicy.recordAttempt(
                        NewsActivity.this,
                        NetworkRefreshPolicy.Module.NEWS,
                        mode,
                        "news");
                loaded = repo.loadNews();
                NetworkRefreshPolicy.recordSuccess(NewsActivity.this, NetworkRefreshPolicy.Module.NEWS, "news");
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
                    syncEmptyState();
                    if (finalError != null) {
                        String msg = finalError.getMessage() != null ? finalError.getMessage() : "";
                        Toast.makeText(
                                NewsActivity.this,
                                getString(R.string.news_error_rss, msg),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                renderNewsItems(finalLoaded);
                saveNewsToCache(finalLoaded);
                updateNewsInfo(getCacheTimestamp());
                checkAutoOpen();
            });
        });
    }

    private boolean shouldFetchFromNetwork() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE);
        long ts = prefs.getLong(KEY_NEWS_TIMESTAMP, 0L);
        NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                this,
                NetworkRefreshPolicy.Module.NEWS,
                NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                "news",
                ts);
        return decision.allowNetwork;
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
        tvInfo.setText(getString(R.string.news_header_source_info_with_time, baseInfo, relative));
    }

    private void setRefreshing(boolean refreshing) {
        if (btnNewsRefresh == null) {
            return;
        }

        btnNewsRefresh.setEnabled(!refreshing);
        btnNewsRefresh.setAlpha(refreshing ? 0.6f : 1f);
    }

    private void loadNewsFromCacheIfAvailable() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE);
        String json = prefs.getString(KEY_NEWS_LIST_JSON, null);
        if (json == null || json.trim().isEmpty()) {
            return;
        }

        try {
            JSONArray arr = new JSONArray(json);
            List<NewsItem> cachedItems = new ArrayList<>();
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

                cachedItems.add(ni);
            }
            renderNewsItems(cachedItems);
        } catch (JSONException e) {
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
        }
    }

    private void clearNewsCache() {
        getSharedPreferences(PREFS_NEWS_CACHE, MODE_PRIVATE)
                .edit()
                .remove(KEY_NEWS_LIST_JSON)
                .remove(KEY_NEWS_TIMESTAMP)
                .apply();
    }

    private void renderNewsItems(List<NewsItem> newsItems) {
        items.clear();
        if (newsItems != null) {
            items.addAll(newsItems);
        }
        adapter.replaceItems(items);
        syncEmptyState();
    }

    private void syncEmptyState() {
        if (tvEmpty == null) {
            return;
        }
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
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

