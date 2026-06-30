package pl.kejlo.zutnik;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView-based USOS OAuth authorization screen.
 *
 * Loads the USOS authorization URL inside the app's WebView.
 * Intercepts the zutnik://oauth/callback redirect, performs the
 * access-token exchange, fetches user info and proceeds to HomeActivity.
 *
 * Start with:
 *   Intent i = new Intent(ctx, UsosLoginWebActivity.class);
 *   i.putExtra(EXTRA_AUTH_URL, authorizationUrl);
 *   startActivity(i);
 */
public class UsosLoginWebActivity extends PhoneAwareActivity {

    public static final String EXTRA_AUTH_URL = "usos_auth_url";

    private static final String TAG       = "UsosLoginWeb";
    private static final String PREFS_NAME = "zutnik_prefs";

    private WebView     webView;
    private ProgressBar progressBar;
    private View        loadingOverlay;
    private TextView    loadingText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         handler  = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_usos_login_web);
        ThemeManager.applySystemBars(this);
        ThemeManager.applyRootWindowInsets(findViewById(R.id.contentRoot));

        Toolbar toolbar = findViewById(R.id.toolbar);
        progressBar    = findViewById(R.id.progressBar);
        webView        = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText    = findViewById(R.id.loadingText);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.login_usos_web_title);
        }

        String authUrl = getIntent().getStringExtra(EXTRA_AUTH_URL);
        if (authUrl == null || authUrl.isEmpty()) {
            Toast.makeText(this, R.string.login_usos_missing_auth_url, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();
        webView.loadUrl(authUrl);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                Uri uri = request.getUrl();

                // Intercept the OAuth callback
                if ("zutnik".equals(uri.getScheme())) {
                    handleOAuthCallback(uri);
                    return true;
                }
                return false; // let WebView handle http/https normally
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) return;
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }
        });
    }

    private void handleOAuthCallback(Uri callbackUri) {
        String oauthToken    = callbackUri.getQueryParameter("oauth_token");
        String oauthVerifier = callbackUri.getQueryParameter("oauth_verifier");

        if (oauthToken == null || oauthVerifier == null) {
            Toast.makeText(this, getString(
                            R.string.login_usos_error_auth,
                            getString(R.string.login_usos_reason_incomplete_params)),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String requestTokenSecret = prefs.getString(UsosOAuthCallbackActivity.KEY_TEMP_RT_SECRET, null);

        if (requestTokenSecret == null) {
            Toast.makeText(this, getString(
                            R.string.login_usos_error_auth,
                            getString(R.string.login_usos_reason_missing_request_secret)),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Hide WebView, show loading overlay
        webView.setVisibility(View.GONE);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
        if (loadingText != null) loadingText.setText(R.string.login_usos_exchanging_token);

        executor.execute(() -> {
            try {
                // Exchange verifier for access token
                UsosOAuth.AccessToken at = UsosOAuth.fetchAccessToken(
                        BuildConfig.USOS_CONSUMER_KEY,
                        BuildConfig.USOS_CONSUMER_SECRET,
                        oauthToken, requestTokenSecret, oauthVerifier);

                prefs.edit().remove(UsosOAuthCallbackActivity.KEY_TEMP_RT_SECRET).apply();

                handler.post(() -> {
                    if (loadingText != null) loadingText.setText(R.string.login_usos_fetching_user);
                });

                // Fetch user info with all needed fields in one request
                java.util.Map<String, String> userParams = new java.util.TreeMap<>();
                userParams.put("fields", "id|first_name|last_name|student_number|photo_urls|has_photo");
                JSONObject user = UsosApi.get("services/users/user", at.token, at.tokenSecret, userParams);
                Log.d(TAG, "USOS user response: " + user.toString());

                String userId = user.optString("id", "");
                if (userId.isEmpty()) {
                    throw new Exception(getString(R.string.login_usos_error_missing_user_id));
                }

                JSONObject firstObj = user.optJSONObject("first_name");
                JSONObject lastObj  = user.optJSONObject("last_name");
                String first = firstObj != null
                        ? firstObj.optString("pl", firstObj.optString("en", ""))
                        : user.optString("first_name", "");
                String last  = lastObj  != null
                        ? lastObj.optString("pl",  lastObj.optString("en",  ""))
                        : user.optString("last_name", "");
                String username = (first + " " + last).trim();

                String studentNumber = user.optString("student_number", null);
                if (studentNumber != null && studentNumber.isEmpty()) studentNumber = null;

                // Extract profile photo URL (100x100 is a good size for avatar)
                String photoUrl = null;
                JSONObject photoUrls = user.optJSONObject("photo_urls");
                if (photoUrls != null) {
                    photoUrl = photoUrls.optString("100x100", photoUrls.optString("50x50", null));
                }

                // Save session
                ZutnikSession session = ZutnikSession.getInstance(UsosLoginWebActivity.this);
                session.updateUsosUser(userId, username, at.token, at.tokenSecret, photoUrl, studentNumber);
                session.saveToPreferences(UsosLoginWebActivity.this);

                Log.d(TAG, "USOS login OK: " + username + " (" + userId + ")");

                String displayName = username.isEmpty()
                        ? getString(R.string.nav_header_default_username) : username;

                handler.post(() -> {
                    Toast.makeText(UsosLoginWebActivity.this,
                            getString(R.string.login_success, displayName),
                            Toast.LENGTH_LONG).show();

                    Intent i = new Intent(UsosLoginWebActivity.this, MainShellActivity.class);
                    i.putExtra(MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION, true);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });

            } catch (Exception e) {
                Log.e(TAG, "Token exchange error", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> {
                    Toast.makeText(UsosLoginWebActivity.this,
                            getString(R.string.login_usos_error_auth, msg),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
    }
}
