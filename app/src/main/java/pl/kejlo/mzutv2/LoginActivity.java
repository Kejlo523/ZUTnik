package pl.kejlo.mzutv2;

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
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

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
    public static final String EXTRA_SESSION_EXPIRED = "extra_session_expired";

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
    private RadioGroup loginMethodGroup;
    private TextView loginInfoText;

    private NestedScrollView contentRootScroll;
    private View rootView;
    private boolean isKeyboardVisible = false;
    private int headerExpandedBottomMargin = 0;
    private int headerCollapsedBottomMargin = 0;

    private static final long ANIM_DURATION_ENTER = 480L;
    private static final long ANIM_STAGGER_DELAY = 85L;
    private ObjectAnimator loadingAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);

        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        if (session.isLoggedIn()) {
            Intent i = new Intent(LoginActivity.this, HomeActivity.class);
            i.putExtra(HomeActivity.EXTRA_REQUEST_NOTIF_PERMISSION, false);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_login);
        ThemeManager.applySystemBars(this);
        contentRootScroll = findViewById(R.id.contentRoot);
        applyLoginWindowInsets();

        if (getIntent().getBooleanExtra(EXTRA_SESSION_EXPIRED, false)
                || SessionExpiryManager.consumeSessionExpiredNotice(this)) {
            Toast.makeText(this, R.string.session_expired_toast, Toast.LENGTH_LONG).show();
        }

        appIcon = findViewById(R.id.appIcon);
        loginCard = findViewById(R.id.loginCard);
        headerContainer = findViewById(R.id.headerContainer);
        loginInputLayout = findViewById(R.id.loginInputLayout);
        passwordInputLayout = findViewById(R.id.passwordInputLayout);
        editLogin = findViewById(R.id.editLogin);
        editPass = findViewById(R.id.editPass);
        btnLogin = findViewById(R.id.btnLogin);
        loginMethodGroup = findViewById(R.id.loginMethodGroup);
        loginInfoText = findViewById(R.id.loginInfoText);

        if (loginMethodGroup != null) {
            loginMethodGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radioUsos) {
                    switchToUsosMode();
                } else {
                    switchToMzutMode();
                }
            });
        }

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
        bindFocusAutoScroll(editLogin);
        bindFocusAutoScroll(editPass);

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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
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
                    .scaleX(0.86f)
                    .scaleY(0.86f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            loginCard.animate()
                    .translationY(-dpToPx(8))
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            scrollFocusedInputIntoView();
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
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            loginCard.animate()
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void applyLoginWindowInsets() {
        if (contentRootScroll == null) {
            return;
        }

        final int paddingLeft = contentRootScroll.getPaddingLeft();
        final int paddingTop = contentRootScroll.getPaddingTop();
        final int paddingRight = contentRootScroll.getPaddingRight();
        final int paddingBottom = contentRootScroll.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(contentRootScroll, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomInset = Math.max(systemBars.bottom, ime.bottom);
            view.setPadding(
                    paddingLeft + systemBars.left,
                    paddingTop + systemBars.top,
                    paddingRight + systemBars.right,
                    paddingBottom + bottomInset);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(contentRootScroll);
    }

    private void bindFocusAutoScroll(View target) {
        if (target == null) {
            return;
        }
        target.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                scrollFocusedInputIntoView();
            }
        });
    }

    private void scrollFocusedInputIntoView() {
        if (contentRootScroll == null) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        contentRootScroll.post(() -> {
            Rect rect = new Rect();
            focused.getDrawingRect(rect);
            int extraSpace = dpToPx(24);
            rect.top = Math.max(0, rect.top - extraSpace);
            rect.bottom += extraSpace;
            contentRootScroll.requestChildRectangleOnScreen(focused, rect, true);
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void prepareViewsForAnimation() {
        appIcon.setAlpha(0f);
        appIcon.setTranslationY(-dpToPx(18));
        appIcon.setScaleX(0.92f);
        appIcon.setScaleY(0.92f);

        loginCard.setAlpha(0f);
        loginCard.setScaleX(0.98f);
        loginCard.setScaleY(0.98f);
        loginCard.setTranslationY(dpToPx(20));

        if (loginInputLayout != null) {
            loginInputLayout.setAlpha(0f);
            loginInputLayout.setTranslationY(dpToPx(10));
        }
        if (passwordInputLayout != null) {
            passwordInputLayout.setAlpha(0f);
            passwordInputLayout.setTranslationY(dpToPx(10));
        }

        btnLogin.setAlpha(0f);
        btnLogin.setTranslationY(dpToPx(14));
        btnLogin.setScaleX(0.98f);
        btnLogin.setScaleY(0.98f);
    }

    private void startKillerIntroAnimation() {
        if (headerContainer != null) {
            headerContainer.setAlpha(0f);
            headerContainer.setTranslationY(-dpToPx(10));
        }

        if (headerContainer != null) {
            headerContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ANIM_DURATION_ENTER)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }

        appIcon.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIM_DURATION_ENTER)
                .setInterpolator(new DecelerateInterpolator(1.35f))
                .start();

        loginCard.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(ANIM_STAGGER_DELAY)
                .setDuration(ANIM_DURATION_ENTER)
                .setInterpolator(new DecelerateInterpolator(1.35f))
                .start();

        if (loginInputLayout != null) {
            loginInputLayout.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(ANIM_STAGGER_DELAY * 2L)
                    .setDuration(ANIM_DURATION_ENTER - 80L)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }

        if (passwordInputLayout != null) {
            passwordInputLayout.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(ANIM_STAGGER_DELAY * 3L)
                    .setDuration(ANIM_DURATION_ENTER - 80L)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }

        btnLogin.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(ANIM_STAGGER_DELAY * 4L)
                .setDuration(ANIM_DURATION_ENTER - 40L)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
    }

    private void switchToUsosMode() {
        if (loginInputLayout != null)  loginInputLayout.setVisibility(View.GONE);
        if (passwordInputLayout != null) passwordInputLayout.setVisibility(View.GONE);
        if (btnLogin != null)          btnLogin.setText(R.string.login_usos_button);
        if (loginInfoText != null)     loginInfoText.setText(R.string.login_usos_info_text);
    }

    private void switchToMzutMode() {
        if (loginInputLayout != null)  loginInputLayout.setVisibility(View.VISIBLE);
        if (passwordInputLayout != null) passwordInputLayout.setVisibility(View.VISIBLE);
        if (btnLogin != null)          btnLogin.setText(R.string.login_button);
        if (loginInfoText != null)     loginInfoText.setText(R.string.login_info_text);
    }

    private boolean isUsosSelected() {
        return loginMethodGroup != null
                && loginMethodGroup.getCheckedRadioButtonId() == R.id.radioUsos;
    }

    private void doLogin() {
        if (isUsosSelected()) {
            doUsosLogin();
            return;
        }

        String rawLogin = editLogin.getText() != null ? editLogin.getText().toString().trim() : "";
        String login = normalizeLoginIdentifier(rawLogin);
        String pass = editPass.getText() != null ? editPass.getText().toString().trim() : "";

        if (!login.equals(rawLogin) && editLogin != null) {
            editLogin.setText(login);
            editLogin.setSelection(login.length());
        }

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

    private String normalizeLoginIdentifier(String rawLogin) {
        if (rawLogin == null) {
            return "";
        }
        String normalized = rawLogin.trim();
        int at = normalized.indexOf('@');
        if (at > 0) {
            normalized = normalized.substring(0, at).trim();
        }
        return normalized;
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
        runSuccessTransitionAndOpenHome(true);
    }

    private void doUsosLogin() {
        if (isAuthTaskRunning) return;

        if (BuildConfig.USOS_CONSUMER_KEY.isEmpty()) {
            Toast.makeText(this, R.string.login_usos_keys_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        isAuthTaskRunning = true;
        startLoadingState();

        executor.execute(() -> {
            try {
                String scopes = "studies|grades|personal|photo|email|mobile_numbers|offline_access|payments";
                UsosOAuth.RequestToken rt = UsosOAuth.fetchRequestToken(
                        BuildConfig.USOS_CONSUMER_KEY,
                        BuildConfig.USOS_CONSUMER_SECRET,
                        scopes);

                // Persist request token secret so UsosOAuthCallbackActivity can retrieve it
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(UsosOAuthCallbackActivity.KEY_TEMP_RT_SECRET, rt.tokenSecret)
                        .apply();

                String authUrl = UsosOAuth.authorizationUrl(rt.token);

                handler.post(() -> {
                    isAuthTaskRunning = false;
                    stopLoadingState();
                    Intent webIntent = new Intent(LoginActivity.this, UsosLoginWebActivity.class);
                    webIntent.putExtra(UsosLoginWebActivity.EXTRA_AUTH_URL, authUrl);
                    startActivity(webIntent);
                });

            } catch (Exception e) {
                Log.e(TAG, "USOS request token error", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> {
                    isAuthTaskRunning = false;
                    stopLoadingState();
                    Toast.makeText(LoginActivity.this,
                            getString(R.string.login_usos_error_request_token, msg),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startLoadingState() {
        btnLogin.setEnabled(false);
        editLogin.setEnabled(false);
        editPass.setEnabled(false);

        editLogin.animate().alpha(0.65f).setDuration(220).start();
        editPass.animate().alpha(0.65f).setDuration(220).start();

        loadingAnimator = ObjectAnimator.ofPropertyValuesHolder(
                btnLogin,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 0.98f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 0.98f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.88f));
        loadingAnimator.setDuration(520);
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

        editLogin.animate().alpha(1f).setDuration(180).start();
        editPass.animate().alpha(1f).setDuration(180).start();
    }

    private void animateFailureShake(View view) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(50);
        }

        ObjectAnimator shake = ObjectAnimator.ofFloat(view, "translationX", 0, 10, -10, 6, -6, 3, -3, 0);
        shake.setDuration(320);
        shake.start();
    }

    private void runSuccessTransitionAndOpenHome(boolean requestNotificationPermission) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        SessionExpiryManager.clearSessionExpiredNotice(this);
        if (loadingAnimator != null) {
            loadingAnimator.cancel();
        }

        btnLogin.setEnabled(false);
        editLogin.setEnabled(false);
        editPass.setEnabled(false);

        if (headerContainer != null) {
            headerContainer.animate()
                    .alpha(0f)
                    .translationY(-dpToPx(10))
                    .setDuration(210)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }

        loginCard.animate()
                .alpha(0f)
                .translationY(-dpToPx(8))
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    Intent i = new Intent(LoginActivity.this, HomeActivity.class);
                    i.putExtra(HomeActivity.EXTRA_REQUEST_NOTIF_PERMISSION, requestNotificationPermission);
                    startActivity(i);
                    overridePendingTransition(R.anim.activity_login_to_home_enter, R.anim.activity_login_to_home_exit);
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

        runSuccessTransitionAndOpenHome(true);
    }
}
