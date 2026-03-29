package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the OAuth 1.0a callback deep link: mzutv2://oauth/callback
 *
 * Flow:
 *   Browser  →  mzutv2://oauth/callback?oauth_token=X&oauth_verifier=Y
 *   ↓
 *   This activity exchanges the verifier for an access token,
 *   fetches user info from USOS and saves the session.
 *   ↓
 *   HomeActivity (on success) or LoginActivity (on failure)
 */
public class UsosOAuthCallbackActivity extends PhoneAwareActivity {

    private static final String TAG = "UsosOAuthCallback";

    static final String PREFS_NAME              = "mzut_prefs";
    static final String KEY_TEMP_RT_SECRET      = "temp_usos_request_token_secret";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler        handler  = new Handler(Looper.getMainLooper());

    private TextView statusText;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_usos_oauth_callback);
        ThemeManager.applySystemBars(this);
        ThemeManager.applyRootWindowInsets(findViewById(R.id.contentRoot));

        statusText = findViewById(R.id.usosStatusText);

        Uri data = getIntent().getData();
        if (data == null) {
            failWithError(getString(R.string.login_usos_error_auth, "brak danych URI"));
            return;
        }

        String oauthToken    = data.getQueryParameter("oauth_token");
        String oauthVerifier = data.getQueryParameter("oauth_verifier");

        if (oauthToken == null || oauthVerifier == null) {
            failWithError(getString(R.string.login_usos_error_auth, "niekompletne parametry OAuth"));
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String requestTokenSecret = prefs.getString(KEY_TEMP_RT_SECRET, null);

        if (requestTokenSecret == null) {
            failWithError(getString(R.string.login_usos_error_auth, "brak request token secret"));
            return;
        }

        doTokenExchange(oauthToken, requestTokenSecret, oauthVerifier, prefs);
    }

    private void doTokenExchange(
            String requestToken,
            String requestTokenSecret,
            String verifier,
            SharedPreferences prefs) {

        executor.execute(() -> {
            try {
                // ── Step 1: Exchange for access token ──────────────────────────────
                UsosOAuth.AccessToken at = UsosOAuth.fetchAccessToken(
                        BuildConfig.USOS_CONSUMER_KEY,
                        BuildConfig.USOS_CONSUMER_SECRET,
                        requestToken, requestTokenSecret, verifier);

                // Clean up ephemeral data
                prefs.edit().remove(KEY_TEMP_RT_SECRET).apply();

                handler.post(() -> setStatus(getString(R.string.login_usos_fetching_user)));

                // ── Step 2: Fetch user info with all needed fields ──────────────────
                java.util.Map<String, String> userParams = new java.util.TreeMap<>();
                userParams.put("fields", "id|first_name|last_name|student_number|photo_urls|has_photo");
                JSONObject user = UsosApi.get(
                        "services/users/user",
                        at.token, at.tokenSecret,
                        userParams);

                String userId = user.optString("id", "");
                if (userId.isEmpty()) {
                    throw new Exception("Brak ID użytkownika w odpowiedzi USOS");
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

                String photoUrl = null;
                JSONObject photoUrls = user.optJSONObject("photo_urls");
                if (photoUrls != null) {
                    photoUrl = photoUrls.optString("100x100", photoUrls.optString("50x50", null));
                }

                // ── Step 3: Save session ────────────────────────────────────────────
                MzutSession session = MzutSession.getInstance(UsosOAuthCallbackActivity.this);
                session.updateUsosUser(userId, username, at.token, at.tokenSecret, photoUrl, studentNumber);
                session.saveToPreferences(UsosOAuthCallbackActivity.this);

                Log.d(TAG, "USOS login OK: " + username + " (" + userId + ")");

                String displayName = username.isEmpty()
                        ? getString(R.string.nav_header_default_username) : username;

                handler.post(() -> {
                    Toast.makeText(
                            UsosOAuthCallbackActivity.this,
                            getString(R.string.login_success, displayName),
                            Toast.LENGTH_LONG).show();

                    Intent i = new Intent(UsosOAuthCallbackActivity.this, HomeActivity.class);
                    i.putExtra(HomeActivity.EXTRA_REQUEST_NOTIF_PERMISSION, true);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });

            } catch (Exception e) {
                Log.e(TAG, "USOS OAuth error", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> failWithError(getString(R.string.login_usos_error_auth, msg)));
            }
        });
    }

    private void setStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    private void failWithError(String errorMessage) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
