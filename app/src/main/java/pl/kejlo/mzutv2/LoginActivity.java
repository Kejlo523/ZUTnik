package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.CycleInterpolator;
import android.view.animation.DecelerateInterpolator;
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

    // Animation specific constants
    private static final long ANIM_DURATION_ENTER = 600;
    private static final long ANIM_STAGGER_DELAY = 100;
    private ObjectAnimator loadingAnimator;

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

        // Focus listener for aesthetic focus scaling
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            float scale = hasFocus ? 1.02f : 1.0f;
            float elevation = hasFocus ? 12f : 0f; // works on newer Androids visual only
            v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        };
        editLogin.setOnFocusChangeListener(focusListener);
        editPass.setOnFocusChangeListener(focusListener);

        // Prepare views for entry animation (hide them initially)
        prepareViewsForAnimation();

        // Start the "Wow" intro
        startKillerIntroAnimation();
    }

    /**
     * Sets initial state of views before animation starts (hidden, scaled down, etc.)
     */
    private void prepareViewsForAnimation() {
        appIcon.setAlpha(0f);
        appIcon.setTranslationY(-300f);
        appIcon.setRotation(-180f);
        appIcon.setScaleX(0.5f);
        appIcon.setScaleY(0.5f);

        loginCard.setAlpha(0f);
        loginCard.setScaleX(0.9f);
        loginCard.setScaleY(0.9f);
        loginCard.setTranslationY(200f);

        editLogin.setAlpha(0f);
        editLogin.setTranslationX(-100f);

        editPass.setAlpha(0f);
        editPass.setTranslationX(100f);

        btnLogin.setAlpha(0f);
        btnLogin.setTranslationY(150f);
        btnLogin.setScaleX(0.5f);
    }

    /**
     * Executes the complex entry animation sequence.
     */
    private void startKillerIntroAnimation() {
        // 1. App Icon Drop & Spin
        appIcon.animate()
                .alpha(1f)
                .translationY(0f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(800)
                .setInterpolator(new OvershootInterpolator(1.5f)) // Bouncy effect
                .start();

        // 2. Card Rise (Slightly delayed)
        loginCard.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(200)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();

        // 3. Inputs Slide In (Staggered from sides)
        editLogin.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(400)
                .setDuration(500)
                .setInterpolator(new OvershootInterpolator(1.0f))
                .start();

        editPass.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(500)
                .setDuration(500)
                .setInterpolator(new OvershootInterpolator(1.0f))
                .start();

        // 4. Button Pop Up
        btnLogin.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .setStartDelay(650)
                .setDuration(600)
                .setInterpolator(new OvershootInterpolator(2.0f)) // Extra bouncy
                .start();
    }

    private void doLogin() {
        String login = editLogin.getText().toString().trim();
        String pass = editPass.getText().toString().trim();

        if (login.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_enter_credentials, Toast.LENGTH_SHORT).show();
            animateFailureShake(editLogin);
            animateFailureShake(editPass);
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

        // Start Loading Animation state
        startLoadingState();

        currentTask = new AuthTask(login, pass, token, tokenJpg);
        currentTask.execute();
    }

    /**
     * Transits UI into a "Thinking" state.
     * Button pulsates, inputs fade out slightly.
     */
    private void startLoadingState() {
        btnLogin.setEnabled(false);
        editLogin.setEnabled(false);
        editPass.setEnabled(false);

        // Fade out inputs slightly
        editLogin.animate().alpha(0.5f).setDuration(300).start();
        editPass.animate().alpha(0.5f).setDuration(300).start();

        // Pulse the login button
        loadingAnimator = ObjectAnimator.ofPropertyValuesHolder(
                btnLogin,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 0.95f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 0.95f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.8f)
        );
        loadingAnimator.setDuration(600);
        loadingAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        loadingAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        loadingAnimator.start();
    }

    /**
     * Resets UI from Loading state back to Normal (e.g., on error).
     */
    private void stopLoadingState() {
        if (loadingAnimator != null) {
            loadingAnimator.cancel();
            btnLogin.setScaleX(1f);
            btnLogin.setScaleY(1f);
            btnLogin.setAlpha(1f);
        }

        btnLogin.setEnabled(true);
        editLogin.setEnabled(true);
        editPass.setEnabled(true);

        editLogin.animate().alpha(1f).setDuration(200).start();
        editPass.animate().alpha(1f).setDuration(200).start();
    }

    /**
     * Violently shakes the view to indicate error + Haptic feedback.
     */
    private void animateFailureShake(View view) {
        // Haptic feedback
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(50); // 50ms vibration
        }

        // CycleInterpolator creates the shake effect
        ObjectAnimator shake = ObjectAnimator.ofFloat(view, "translationX", 0, 25, -25, 25, -25, 15, -15, 6, -6, 0);
        shake.setDuration(700);
        shake.start();
    }

    /**
     * Explosive exit animation on success.
     * Icon flies up, Card drops down, elements fade out fast.
     */
    private void runSuccessTransitionAndOpenHome() {
        if (loadingAnimator != null) loadingAnimator.cancel();

        // 1. Icon shoots up into the sky
        appIcon.animate()
                .translationY(-1000f)
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(new AccelerateInterpolator())
                .start();

        // 2. Card drops to the floor
        loginCard.animate()
                .translationY(1000f)
                .alpha(0f)
                .rotation(10f) // slight tilt
                .setDuration(400)
                .setInterpolator(new AccelerateInterpolator())
                .start();

        // 3. Button explodes (scales up and fades)
        btnLogin.animate()
                .scaleX(3f)
                .scaleY(3f)
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> {
                    Intent i = new Intent(LoginActivity.this, HomeActivity.class);
                    // Disable standard activity transition for seamless feel
                    i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(i);
                    // Use a custom fade transition
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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
            currentTask = null;

            if (error != null) {
                stopLoadingState(); // Stop animations
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
                stopLoadingState(); // Stop animations
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
                stopLoadingState(); // Stop animations
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
                    // Shake and Vibrate
                    animateFailureShake(loginCard);
                }
                return;
            }

            // SUCCESS!
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

            // Run the exit animation
            runSuccessTransitionAndOpenHome();
        }
    }
}