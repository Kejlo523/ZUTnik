package pl.kejlo.zutnik;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Shared activity behavior for phones and larger screens.
 */
public abstract class PhoneAwareActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPrivacyWindowProtection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrivacyWindowProtection();
        maybeShowPrivacyLock();
    }

    private void applyPrivacyWindowProtection() {
        if (PrivacyManager.isEnabled(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void maybeShowPrivacyLock() {
        if (!PrivacyManager.isEnabled(this) || PrivacyManager.isUnlocked()) {
            return;
        }
        ZutnikSession.initializeFromPreferences(this);
        if (!ZutnikSession.getInstance().isLoggedIn() || !PrivacyManager.beginLockActivityLaunch()) {
            return;
        }
        Intent intent = new Intent(this, PrivacyLockActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.screen_pop_enter, R.anim.screen_pop_exit);
    }
}
