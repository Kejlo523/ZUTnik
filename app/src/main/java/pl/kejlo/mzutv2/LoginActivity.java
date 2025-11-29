package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private static final String TAG = "mZUTv2";

    // Only local login-screen preferences
    private static final String PREFS_NAME = "mzut_prefs";
    private static final String KEY_LAST_LOGIN = "last_login";

    private ImageView appIcon;
    private View loginCard;
    private EditText editLogin;
    private EditText editPass;
    private Button btnLogin;

    private AuthTask currentTask;
    private boolean isFormElevated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Try to restore session from preferences
        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        // If we already have userId + authKey, go straight to Home
        if (session.getAuthKey() != null && session.getUserId() != null) {
            Intent i = new Intent(LoginActivity.this, HomeActivity.class);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        appIcon = findViewById(R.id.appIcon);
        loginCard = findViewById(R.id.loginCard);
        editLogin = findViewById(R.id.editLogin);
        editPass = findViewById(R.id.editPass);
        btnLogin = findViewById(R.id.btnLogin);

        // Prefill last used login if available
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastLogin = prefs.getString(KEY_LAST_LOGIN, "");
        if (!lastLogin.isEmpty()) {
            editLogin.setText(lastLogin);
        }

        btnLogin.setOnClickListener(v -> doLogin());

        // Initial hidden state for staggered form animation (to avoid flicker)
        float startOffset = 24f;
        editLogin.setAlpha(0f);
        editLogin.setTranslationY(startOffset);
        editPass.setAlpha(0f);
        editPass.setTranslationY(startOffset);
        btnLogin.setAlpha(0f);
        btnLogin.setTranslationY(startOffset);

        // Focus listener: gently raise the form when keyboard appears
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            if (hasFocus) {
                elevateForm(true);
            } else if (!editLogin.hasFocus() && !editPass.hasFocus()) {
                elevateForm(false);
            }
        };
        editLogin.setOnFocusChangeListener(focusListener);
        editPass.setOnFocusChangeListener(focusListener);

        // Initial intro animation for icon and form
        runIntroAnimations();
    }

    private void doLogin() {
        String login = editLogin.getText().toString().trim();
        String pass = editPass.getText().toString().trim();

        if (login.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_enter_credentials, Toast.LENGTH_SHORT).show();
            playShakeAnimation(editLogin);
            playShakeAnimation(editPass);
            return;
        }

        // Remember last used login
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_LOGIN, login).apply();

        String token = MzutTokenGenerator.generateToken(login, pass);
        String tokenJpg = MzutTokenGenerator.generateToken(login, null);

        Log.d(TAG, "Generated token: " + token);

        // Avoid parallel login attempts
        if (currentTask != null) {
            return;
        }

        // Button click animation + temporary disable
        btnLogin.setEnabled(false);
        btnLogin.animate()
                .alpha(0.9f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(120)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    btnLogin.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(160)
                            .setInterpolator(new OvershootInterpolator(1.05f))
                            .start();

                    currentTask = new AuthTask(login, pass, token, tokenJpg);
                    currentTask.execute();
                })
                .start();
    }

    // Intro animations for initial appearance

    private void runIntroAnimations() {
        // Icon: start slightly above with zoom, then drop into place with overshoot
        appIcon.setAlpha(0f);
        appIcon.setScaleX(1.2f);
        appIcon.setScaleY(1.2f);
        appIcon.setTranslationY(-40f);

        appIcon.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(550)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();

        // Card: slide up from bottom with fade in
        loginCard.setAlpha(0f);
        loginCard.setTranslationY(80f);

        loginCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(200)
                .setDuration(450)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(this::runFormStaggerAnimation)
                .start();
    }

    // Slight stagger for login, password and button after the card appears
    private void runFormStaggerAnimation() {
        long baseDelay = 80L;
        long duration = 260L;

        editLogin.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(baseDelay)
                .setDuration(duration)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        editPass.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(baseDelay + 60L)
                .setDuration(duration)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        btnLogin.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(baseDelay + 120L)
                .setDuration(duration)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .start();
    }

    private void elevateForm(boolean elevate) {
        if (elevate == isFormElevated) {
            return;
        }
        isFormElevated = elevate;

        float targetTranslation = elevate ? -60f : 0f;

        loginCard.animate()
                .translationY(targetTranslation)
                .setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    private void playShakeAnimation(View view) {
        if (view == null) {
            return;
        }

        final float delta = 8f;

        view.animate()
                .translationX(-delta)
                .setDuration(40)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() ->
                        view.animate()
                                .translationX(delta)
                                .setDuration(60)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() ->
                                        view.animate()
                                                .translationX(0)
                                                .setDuration(40)
                                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                                .start()
                                )
                                .start()
                )
                .start();
    }

    // Exit animation after successful login, then navigate to HomeActivity
    private void runSuccessTransitionAndOpenHome() {
        btnLogin.setEnabled(false);
        loginCard.setClickable(false);
        editLogin.setEnabled(false);
        editPass.setEnabled(false);

        appIcon.animate()
                .translationY(-40f)
                .alpha(0f)
                .setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        loginCard.animate()
                .translationY(60f)
                .alpha(0f)
                .setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    Intent i = new Intent(LoginActivity.this, HomeActivity.class);
                    startActivity(i);
                    finish();
                })
                .start();
    }

    // AuthTask – login logic (behavior unchanged)

    private class AuthTask extends AsyncTask<Void, Void, JSONObject> {

        private final String login;
        private final String password;
        private final String token;
        private final String tokenJpg;
        private Exception error;

        AuthTask(String login, String password, String token, String tokenJpg) {
            this.login = login;
            this.password = password;
            this.token = token;
            this.tokenJpg = tokenJpg;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("login", login);
                params.put("password", password);
                params.put("token", token);
                params.put("tokenJpg", tokenJpg);

                return MzutApi.callApi("getAuthorization", params);
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(JSONObject auth) {
            btnLogin.setEnabled(true);
            currentTask = null;

            if (error != null) {
                String errorMessage = error.getMessage() != null ? error.getMessage() : "";
                Toast.makeText(
                        LoginActivity.this,
                        getString(R.string.login_error_generic, errorMessage),
                        Toast.LENGTH_LONG
                ).show();
                Log.e(TAG, "API error", error);
                return;
            }

            if (auth == null) {
                Toast.makeText(
                        LoginActivity.this,
                        R.string.login_no_server_response,
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            Log.d(TAG, "AUTH RESULT: " + auth.toString());

            String status = auth.optString(
                    "logInStatus",
                    auth.optString("loginInStatus", "")
            );

            if (!"OK".equalsIgnoreCase(status)) {
                if ("SYSTEM ERROR".equalsIgnoreCase(status)) {
                    Toast.makeText(
                            LoginActivity.this,
                            R.string.login_system_error,
                            Toast.LENGTH_LONG
                    ).show();
                } else {
                    Toast.makeText(
                            LoginActivity.this,
                            R.string.login_invalid_credentials,
                            Toast.LENGTH_LONG
                    ).show();
                    playShakeAnimation(editLogin);
                    playShakeAnimation(editPass);
                }
                return;
            }

            String userId = auth.optString("login", login);
            String first = auth.optString("pierwszeImie", "");
            String last = auth.optString("nazwisko", "");
            String username = (first + " " + last).trim();
            String authKey = auth.optString("token", token);
            String imageUrl = "https://www.zut.edu.pl/app-json-proxy/image/?userId="
                    + userId + "&tokenJpg="
                    + auth.optString("tokenJpg", tokenJpg);

            MzutSession session = MzutSession.getInstance(LoginActivity.this);
            session.updateUser(userId, username, authKey, imageUrl);
            session.saveToPreferences(LoginActivity.this);

            String displayName = username.isEmpty()
                    ? getString(R.string.nav_header_default_username)
                    : username;

            Toast.makeText(
                    LoginActivity.this,
                    getString(R.string.login_success, displayName),
                    Toast.LENGTH_LONG
            ).show();

            runSuccessTransitionAndOpenHome();
        }
    }
}
