package pl.kejlo.mzutv2;

import android.annotation.SuppressLint;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

public class WebLinkActivity extends PhoneAwareActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";

    private WebView webView;
    private ProgressBar progressBar;
    private String currentUrl;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_web_link);
        ThemeManager.applySystemBars(this);

        View contentRoot = findViewById(R.id.contentRoot);
        Toolbar toolbar = findViewById(R.id.toolbar);
        progressBar = findViewById(R.id.progressBar);
        webView = findViewById(R.id.webView);

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        Intent intent = getIntent();
        String title = intent.getStringExtra(EXTRA_TITLE);
        currentUrl = normalizeUrl(intent.getStringExtra(EXTRA_URL));

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(!TextUtils.isEmpty(title)
                    ? title
                    : getString(R.string.web_link_title));
        }

        if (TextUtils.isEmpty(currentUrl)) {
            Toast.makeText(this, R.string.web_link_no_url, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!isHttpUrl(currentUrl)) {
            openExternalUrl(currentUrl);
            finish();
            return;
        }

        setupWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        webView.loadUrl(currentUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_web_link, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_open_external) {
            openExternal();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return false;
                }
                String url = request.getUrl().toString();
                String normalized = normalizeUrl(url);
                if (normalized != null && isHttpUrl(normalized)) {
                    currentUrl = normalized;
                    return false;
                }
                openExternalUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) {
                    return;
                }
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    if (progressBar.getVisibility() != View.VISIBLE) {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                    progressBar.setProgress(newProgress);
                }
            }
        });
    }

    private void openExternal() {
        if (TextUtils.isEmpty(currentUrl)) {
            return;
        }
        openExternalUrl(currentUrl);
    }

    private void openExternalUrl(String url) {
        String safeUrl = normalizeUrl(url);
        if (TextUtils.isEmpty(safeUrl)) {
            Toast.makeText(this, R.string.web_link_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl));
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.web_link_open_external_error, Toast.LENGTH_SHORT).show();
        }
    }

    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return null;
        }
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (TextUtils.isEmpty(scheme)) {
            url = "https://" + url;
            parsed = Uri.parse(url);
            scheme = parsed.getScheme();
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return url;
        }
        return url;
    }

    private static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
