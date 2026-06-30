package pl.kejlo.zutnik;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

public class LoginActivity extends PhoneAwareActivity {

    private static final String TAG = "ZUTnikLogin";
    public static final String EXTRA_SESSION_EXPIRED = "extra_session_expired";
    private static final String PREFS_NAME = "zutnik_prefs";

    private static final long ANIM_DURATION_ENTER = 480L;
    private static final long ANIM_STAGGER_DELAY = 85L;

    private ImageView appIcon;
    private View loginCard;
    private View headerContainer;
    private View loginRoot;
    private Button btnLogin;
    private TextView loginInfoText;
    private TextView demoModeHint;
    private NestedScrollView contentRootScroll;

    private ObjectAnimator loadingAnimator;
    private boolean isAuthTaskRunning = false;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
            .newSingleThreadExecutor();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);

        ZutnikSession.initializeFromPreferences(this);
        ZutnikSession session = ZutnikSession.getInstance(this);

        if (session.isLoggedIn()) {
            Intent i = new Intent(LoginActivity.this, MainShellActivity.class);
            i.putExtra(MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION, false);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_login);
        ThemeManager.applySystemBars(this);

        loginRoot = findViewById(R.id.loginRoot);
        contentRootScroll = findViewById(R.id.contentRoot);
        appIcon = findViewById(R.id.appIcon);
        loginCard = findViewById(R.id.loginCard);
        headerContainer = findViewById(R.id.headerContainer);
        btnLogin = findViewById(R.id.btnLogin);
        loginInfoText = findViewById(R.id.loginInfoText);
        demoModeHint = findViewById(R.id.loginDemoHint);

        applyLoginWindowInsets();

        if (getIntent().getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
            Toast.makeText(this, R.string.session_expired_toast, Toast.LENGTH_LONG).show();
        }

        if (btnLogin != null) {
            btnLogin.setText(R.string.login_usos_button);
            btnLogin.setOnClickListener(v -> doUsosLogin());
        }
        if (loginInfoText != null) {
            loginInfoText.setText(R.string.login_usos_scope_hint);
        }
        if (demoModeHint != null) {
            demoModeHint.setOnClickListener(v -> enterDemoMode());
        }

        prepareViewsForAnimation();
        startIntroAnimation();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    private void applyLoginWindowInsets() {
        if (loginRoot == null || contentRootScroll == null) {
            return;
        }

        final int paddingLeft = contentRootScroll.getPaddingLeft();
        final int paddingTop = contentRootScroll.getPaddingTop();
        final int paddingRight = contentRootScroll.getPaddingRight();
        final int paddingBottom = contentRootScroll.getPaddingBottom();

        final ViewGroup.MarginLayoutParams demoBaseMargins =
                demoModeHint != null && demoModeHint.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                        ? new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) demoModeHint.getLayoutParams())
                        : null;

        ViewCompat.setOnApplyWindowInsetsListener(loginRoot, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomInset = Math.max(systemBars.bottom, ime.bottom);
            contentRootScroll.setPadding(
                    paddingLeft + systemBars.left,
                    paddingTop + systemBars.top,
                    paddingRight + systemBars.right,
                    paddingBottom + bottomInset);
            if (demoModeHint != null && demoBaseMargins != null
                    && demoModeHint.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) demoModeHint.getLayoutParams();
                lp.leftMargin = demoBaseMargins.leftMargin + systemBars.left;
                lp.topMargin = demoBaseMargins.topMargin + systemBars.top;
                lp.rightMargin = demoBaseMargins.rightMargin + systemBars.right;
                lp.bottomMargin = demoBaseMargins.bottomMargin + bottomInset;
                demoModeHint.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(loginRoot);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void prepareViewsForAnimation() {
        if (headerContainer != null) {
            headerContainer.setAlpha(0f);
            headerContainer.setTranslationY(-dpToPx(10));
        }
        if (appIcon != null) {
            appIcon.setAlpha(0f);
            appIcon.setTranslationY(-dpToPx(18));
            appIcon.setScaleX(0.92f);
            appIcon.setScaleY(0.92f);
        }
        if (loginCard != null) {
            loginCard.setAlpha(0f);
            loginCard.setScaleX(0.98f);
            loginCard.setScaleY(0.98f);
            loginCard.setTranslationY(dpToPx(20));
        }
        if (btnLogin != null) {
            btnLogin.setAlpha(0f);
            btnLogin.setTranslationY(dpToPx(14));
            btnLogin.setScaleX(0.98f);
            btnLogin.setScaleY(0.98f);
        }
        if (loginInfoText != null) {
            loginInfoText.setAlpha(0f);
            loginInfoText.setTranslationY(dpToPx(10));
        }
    }

    private void startIntroAnimation() {
        if (headerContainer != null) {
            headerContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ANIM_DURATION_ENTER)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }

        if (appIcon != null) {
            appIcon.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(ANIM_DURATION_ENTER)
                    .setInterpolator(new DecelerateInterpolator(1.35f))
                    .start();
        }

        if (loginCard != null) {
            loginCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(ANIM_STAGGER_DELAY)
                    .setDuration(ANIM_DURATION_ENTER)
                    .setInterpolator(new DecelerateInterpolator(1.35f))
                    .start();
        }

        if (btnLogin != null) {
            btnLogin.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(ANIM_STAGGER_DELAY * 2L)
                    .setDuration(ANIM_DURATION_ENTER - 40L)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }

        if (loginInfoText != null) {
            loginInfoText.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(ANIM_STAGGER_DELAY * 3L)
                    .setDuration(ANIM_DURATION_ENTER - 80L)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }
    }

    private void doUsosLogin() {
        if (isAuthTaskRunning) {
            return;
        }

        if (BuildConfig.USOS_CONSUMER_KEY.isEmpty()) {
            Toast.makeText(this, R.string.login_usos_keys_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        isAuthTaskRunning = true;
        startLoadingState();

        executor.execute(() -> {
            try {
                String scopes = "studies|grades|payments|cards|photo|crstests|surveys_filling|offline_access";
                UsosOAuth.RequestToken rt = UsosOAuth.fetchRequestToken(
                        BuildConfig.USOS_CONSUMER_KEY,
                        BuildConfig.USOS_CONSUMER_SECRET,
                        scopes);

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
                android.util.Log.e(TAG, "USOS request token error", e);
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

    private void enterDemoMode() {
        if (isAuthTaskRunning) {
            return;
        }

        ZutnikSession.clearSessionData(this);
        ZutnikSession session = ZutnikSession.getInstance(this);
        session.updateDemoUser(
                DemoDataProvider.DEMO_USER_ID,
                DemoDataProvider.DEMO_USERNAME,
                null);
        DemoDataProvider.populateSession(session);
        session.saveToPreferences(this);

        Toast.makeText(this, R.string.login_demo_toast, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(LoginActivity.this, MainShellActivity.class);
        i.putExtra(MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION, false);
        startActivity(i);
        finish();
    }

    private void startLoadingState() {
        if (btnLogin == null) {
            return;
        }

        btnLogin.setEnabled(false);
        if (loginInfoText != null) {
            loginInfoText.animate().alpha(0.7f).setDuration(180).start();
        }

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
        if (btnLogin == null) {
            return;
        }

        if (loadingAnimator != null) {
            loadingAnimator.cancel();
            btnLogin.setScaleX(1f);
            btnLogin.setScaleY(1f);
            btnLogin.setAlpha(1f);
        }

        btnLogin.setEnabled(true);
        if (loginInfoText != null) {
            loginInfoText.animate().alpha(1f).setDuration(160).start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
