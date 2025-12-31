package pl.kejlo.mzutv2;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;

import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private static final String TAG = "mZUTv2";

    private static final String PREFS_NAME = "mzut_prefs";
    private static final String KEY_LAST_LOGIN = "last_login";

    private static final String LIMBO_LOGIN = "Student";
    private static final String LIMBO_PASSWORD = "Test";

    private ImageView appIcon;
    private View loginCard;
    private View headerContainer;
    private TextInputEditText editLogin;
    private TextInputEditText editPass;
    private TextInputLayout loginInputLayout;
    private TextInputLayout passwordInputLayout;
    private Button btnLogin;

    private View rootView;
    private boolean isKeyboardVisible = false;
    private int headerExpandedBottomMargin = 0;
    private int headerCollapsedBottomMargin = 0;

    private static final long ANIM_DURATION_ENTER = 600;
    private static final long ANIM_STAGGER_DELAY = 100;
    private ObjectAnimator loadingAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        EdgeToEdge.enable(this);

        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        if (session.getAuthKey() != null && session.getUserId() != null) {
            Intent i = new Intent(LoginActivity.this, HomeActivity.class);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        appIcon = findViewById(R.id.appIcon);
        loginCard = findViewById(R.id.loginCard);
        headerContainer = findViewById(R.id.headerContainer);
        loginInputLayout = findViewById(R.id.loginInputLayout);
        passwordInputLayout = findViewById(R.id.passwordInputLayout);
        editLogin = findViewById(R.id.editLogin);
        editPass = findViewById(R.id.editPass);
        btnLogin = findViewById(R.id.btnLogin);

        if (headerContainer != null) {
            ViewGroup.LayoutParams params = headerContainer.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
                headerExpandedBottomMargin = lp.bottomMargin;
                headerCollapsedBottomMargin = Math.max(dpToPx(8), headerExpandedBottomMargin / 2);
            }
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastLogin = prefs.getString(KEY_LAST_LOGIN, "");
        if (editLogin.getText() != null && editLogin.getText().length() == 0 && !lastLogin.isEmpty()) {
            editLogin.setText(lastLogin);
            editLogin.setSelection(lastLogin.length());
        }

        btnLogin.setOnClickListener(v -> doLogin());

        if (loginInputLayout != null) {
            loginInputLayout.post(() -> {
                loginInputLayout.setHintEnabled(false);
                loginInputLayout.setHintEnabled(true);
            });
        }
        if (passwordInputLayout != null) {
            passwordInputLayout.post(() -> {
                passwordInputLayout.setHintEnabled(false);
                passwordInputLayout.setHintEnabled(true);
            });
        }

        rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    Rect r = new Rect();
                    rootView.getWindowVisibleDisplayFrame(r);
                    int screenHeight = rootView.getRootView().getHeight();
                    int heightDiff = screenHeight - r.height();
                    boolean visible = heightDiff > dpToPx(150);
                    if (visible != isKeyboardVisible) {
                        isKeyboardVisible = visible;
                        handleKeyboardVisibilityChange(visible);
                    }
                }
            });
        }

        prepareViewsForAnimation();
        startKillerIntroAnimation();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void handleKeyboardVisibilityChange(boolean visible) {
        if (visible) {
            if (headerContainer != null) {
                ViewGroup.LayoutParams params = headerContainer.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
                    lp.bottomMargin = headerCollapsedBottomMargin;
                    headerContainer.setLayoutParams(lp);
                }
            }
            appIcon.animate()
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            loginCard.animate()
                    .translationY(-dpToPx(12))
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            if (headerContainer != null) {
                ViewGroup.LayoutParams params = headerContainer.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
                    lp.bottomMargin = headerExpandedBottomMargin;
                    headerContainer.setLayoutParams(lp);
                }
            }
            appIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            loginCard.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

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

        if (loginInputLayout != null) {
            loginInputLayout.setAlpha(0f);
            loginInputLayout.setTranslationY(50f);
        }
        if (passwordInputLayout != null) {
            passwordInputLayout.setAlpha(0f);
            passwordInputLayout.setTranslationY(50f);
        }

        btnLogin.setAlpha(0f);
        btnLogin.setTranslationY(150f);
        btnLogin.setScaleX(0.5f);
    }

    private void startKillerIntroAnimation() {
        appIcon.animate()
                .alpha(1f)
                .translationY(0f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(800)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .start();

        loginCard.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(200)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();

        if (loginInputLayout != null) {
            loginInputLayout.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(400)
                    .setDuration(500)
                    .setInterpolator(new OvershootInterpolator(1.0f))
                    .start();
        }

        if (passwordInputLayout != null) {
            passwordInputLayout.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(500)
                    .setDuration(500)
                    .setInterpolator(new OvershootInterpolator(1.0f))
                    .start();
        }

        btnLogin.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .setStartDelay(650)
                .setDuration(600)
                .setInterpolator(new OvershootInterpolator(2.0f))
                .start();
    }

    private void doLogin() {
        String login = editLogin.getText() != null ? editLogin.getText().toString().trim() : "";
        String pass = editPass.getText() != null ? editPass.getText().toString().trim() : "";

        if (login.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_enter_credentials, Toast.LENGTH_SHORT).show();
            animateFailureShake(editLogin);
            animateFailureShake(editPass);
            return;
        }

        if (LIMBO_LOGIN.equals(login) && LIMBO_PASSWORD.equals(pass)) {
            startLimboLogin(login);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_LOGIN, login).apply();

        String token = MzutTokenGenerator.generateToken(login, pass);
        String tokenJpg = MzutTokenGenerator.generateToken(login, null);

        Log.d(TAG, "Generated token: " + token);

        if (isAuthTaskRunning) {
            return;
        }

        startLoadingState();

        executeAuthTask(login, pass, token, tokenJpg);
    }

    private void startLimboLogin(String login) {
        if (isAuthTaskRunning) {
            return;
        }
        startLoadingState();
        MzutSession session = MzutSession.getInstance(this);
        String userId = "st123456";
        String username = "Student (tryb demo)";
        String authKey = "Student_TOKEN";
        String imageUrl = null;
        session.updateUser(userId, username, authKey, imageUrl);
        session.saveToPreferences(this);
        stopLoadingState();
        Toast.makeText(
                this,
                getString(R.string.login_success, username),
                Toast.LENGTH_LONG).show();
        runSuccessTransitionAndOpenHome();
    }

    private void startLoadingState() {
        btnLogin.setEnabled(false);
        editLogin.setEnabled(false);
        editPass.setEnabled(false);

        editLogin.animate().alpha(0.5f).setDuration(300).start();
        editPass.animate().alpha(0.5f).setDuration(300).start();

        loadingAnimator = ObjectAnimator.ofPropertyValuesHolder(
                btnLogin,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 0.95f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 0.95f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.8f));
        loadingAnimator.setDuration(600);
        loadingAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        loadingAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        loadingAnimator.start();
    }

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

    private void animateFailureShake(View view) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(50);
        }

        ObjectAnimator shake = ObjectAnimator.ofFloat(view, "translationX", 0, 25, -25, 25, -25, 15, -15, 6, -6, 0);
        shake.setDuration(700);
        shake.start();
    }

    private void runSuccessTransitionAndOpenHome() {
        if (loadingAnimator != null)
            loadingAnimator.cancel();

        appIcon.animate()
                .translationY(-1000f)
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(new AccelerateInterpolator())
                .start();

        loginCard.animate()
                .translationY(1000f)
                .alpha(0f)
                .rotation(10f)
                .setDuration(400)
                .setInterpolator(new AccelerateInterpolator())
                .start();

        btnLogin.animate()
                .scaleX(3f)
                .scaleY(3f)
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> {
                    Intent i = new Intent(LoginActivity.this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(i);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                })
                .start();
    }

    // Auth logic

    // Simple executor for background tasks
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
            .newSingleThreadExecutor();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean isAuthTaskRunning = false;

    private void executeAuthTask(String login, String password, String token, String tokenJpg) {
        if (isAuthTaskRunning) {
            return;
        }
        isAuthTaskRunning = true;

        executor.execute(() -> {
            JSONObject result = null;
            Exception error = null;

            try {
                Map<String, String> params = new HashMap<>();
                params.put("login", login);
                params.put("password", password);
                params.put("token", token);
                params.put("tokenJpg", tokenJpg);

                result = MzutApi.callApi("getAuthorization", params);
            } catch (Exception e) {
                error = e;
            }

            final JSONObject finalResult = result;
            final Exception finalError = error;

            handler.post(() -> handleAuthResult(finalResult, finalError, login, token, tokenJpg));
        });
    }

    private void handleAuthResult(JSONObject auth, Exception error, String originalLogin, String originalToken,
            String originalTokenJpg) {
        isAuthTaskRunning = false; // Task finished

        if (error != null) {
            stopLoadingState();
            String errorMessage = error.getMessage() != null ? error.getMessage() : "";
            Toast.makeText(
                    LoginActivity.this,
                    getString(R.string.login_error_generic, errorMessage),
                    Toast.LENGTH_LONG).show();
            Log.e(TAG, "API error", error);
            return;
        }

        if (auth == null) {
            stopLoadingState();
            Toast.makeText(
                    LoginActivity.this,
                    R.string.login_no_server_response,
                    Toast.LENGTH_LONG).show();
            return;
        }

        Log.d(TAG, "AUTH RESULT: " + auth.toString());

        String status = auth.optString(
                "logInStatus",
                auth.optString("loginInStatus", ""));

        if (!"OK".equalsIgnoreCase(status)) {
            stopLoadingState();
            if ("SYSTEM ERROR".equalsIgnoreCase(status)) {
                Toast.makeText(
                        LoginActivity.this,
                        R.string.login_system_error,
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(
                        LoginActivity.this,
                        R.string.login_invalid_credentials,
                        Toast.LENGTH_LONG).show();
                animateFailureShake(loginCard);
            }
            return;
        }

        String userId = auth.optString("login", originalLogin);
        String first = auth.optString("pierwszeImie", "");
        String last = auth.optString("nazwisko", "");
        String username = (first + " " + last).trim();
        String authKey = auth.optString("token", originalToken);
        String imageUrl = "https://www.zut.edu.pl/app-json-proxy/image/?userId="
                + userId + "&tokenJpg="
                + auth.optString("tokenJpg", originalTokenJpg);

        MzutSession session = MzutSession.getInstance(LoginActivity.this);
        session.updateUser(userId, username, authKey, imageUrl);
        session.saveToPreferences(LoginActivity.this);

        String displayName = username.isEmpty()
                ? getString(R.string.nav_header_default_username)
                : username;

        Toast.makeText(
                LoginActivity.this,
                getString(R.string.login_success, displayName),
                Toast.LENGTH_LONG).show();

        runSuccessTransitionAndOpenHome();
    }
}
