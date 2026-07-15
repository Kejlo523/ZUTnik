package pl.kejlo.zutnik;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrivacyLockActivity extends AppCompatActivity {

    public static final String EXTRA_AUTH_ONLY = "privacy_auth_only";

    private final StringBuilder pin = new StringBuilder();
    private final ExecutorService pinExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pinDots;
    private TextView errorText;
    private View pinPanel;
    private MaterialButton biometricButton;
    private boolean verifying;
    private boolean authOnly;
    private boolean biometricPromptVisible;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_privacy_lock);
        ThemeManager.applySystemBars(this);

        authOnly = getIntent().getBooleanExtra(EXTRA_AUTH_ONLY, false);
        pinDots = findViewById(R.id.privacyPinDots);
        errorText = findViewById(R.id.privacyError);
        pinPanel = findViewById(R.id.privacyPinPanel);
        biometricButton = findViewById(R.id.privacyBiometric);

        int[] keyIds = {
                R.id.privacyKey0, R.id.privacyKey1, R.id.privacyKey2, R.id.privacyKey3,
                R.id.privacyKey4, R.id.privacyKey5, R.id.privacyKey6, R.id.privacyKey7,
                R.id.privacyKey8, R.id.privacyKey9
        };
        for (int digit = 0; digit <= 9; digit++) {
            final int value = digit;
            findViewById(keyIds[digit]).setOnClickListener(v -> appendDigit(value));
        }
        findViewById(R.id.privacyBackspace).setOnClickListener(v -> removeDigit());

        boolean biometricAvailable = PrivacyManager.isBiometricEnabled(this);
        biometricButton.setVisibility(biometricAvailable ? View.VISIBLE : View.INVISIBLE);
        biometricButton.setOnClickListener(v -> showBiometricPrompt());
        updateDots();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (authOnly) {
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                } else {
                    moveTaskToBack(true);
                }
            }
        });

        if (biometricAvailable) {
            handler.postDelayed(this::showBiometricPrompt, 220L);
        }
    }

    private void appendDigit(int digit) {
        if (verifying || pin.length() >= PrivacyManager.requiredPinLength()) {
            return;
        }
        pin.append(digit);
        errorText.setVisibility(View.INVISIBLE);
        updateDots();
        if (pin.length() == PrivacyManager.requiredPinLength()) {
            verifyPin();
        }
    }

    private void removeDigit() {
        if (verifying || pin.length() == 0) {
            return;
        }
        pin.deleteCharAt(pin.length() - 1);
        errorText.setVisibility(View.INVISIBLE);
        updateDots();
    }

    private void updateDots() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < PrivacyManager.requiredPinLength(); i++) {
            if (i > 0) dots.append("  ");
            dots.append(i < pin.length() ? "●" : "○");
        }
        pinDots.setText(dots.toString());
    }

    private void verifyPin() {
        verifying = true;
        String candidate = pin.toString();
        pinExecutor.execute(() -> {
            boolean valid = PrivacyManager.verifyPin(this, candidate);
            handler.post(() -> {
                verifying = false;
                if (valid) {
                    completeAuthentication();
                } else {
                    showPinError();
                }
            });
        });
    }

    private void showPinError() {
        pin.setLength(0);
        updateDots();
        errorText.setText(R.string.privacy_pin_wrong);
        errorText.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(pinPanel, View.TRANSLATION_X, 0f, -12f, 12f, -8f, 8f, 0f)
                .setDuration(280L)
                .start();
    }

    private void showBiometricPrompt() {
        if (biometricPromptVisible || !PrivacyManager.isBiometricEnabled(this)) {
            return;
        }
        biometricPromptVisible = true;
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @androidx.annotation.NonNull BiometricPrompt.AuthenticationResult result) {
                        biometricPromptVisible = false;
                        completeAuthentication();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @androidx.annotation.NonNull CharSequence errString) {
                        biometricPromptVisible = false;
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        errorText.setText(R.string.privacy_biometric_failed);
                        errorText.setVisibility(View.VISIBLE);
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.privacy_unlock_title))
                .setSubtitle(getString(R.string.privacy_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.privacy_use_pin))
                .build();
        prompt.authenticate(info);
    }

    private void completeAuthentication() {
        PrivacyManager.markUnlocked();
        setResult(Activity.RESULT_OK);
        finish();
        overridePendingTransition(0, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pinExecutor.shutdownNow();
        PrivacyManager.cancelLockActivityLaunch();
        super.onDestroy();
    }
}
