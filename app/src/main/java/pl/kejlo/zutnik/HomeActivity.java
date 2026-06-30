package pl.kejlo.zutnik;

import android.content.Intent;
import android.os.Bundle;

public class HomeActivity extends ZutnikBaseActivity {

    public static final String EXTRA_REQUEST_NOTIF_PERMISSION = MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        Intent redirect = MainShellActivity.createIntent(this, MainNavHelper.Screen.HOME);
        if (getIntent() != null && getIntent().hasExtra(EXTRA_REQUEST_NOTIF_PERMISSION)) {
            redirect.putExtra(
                    EXTRA_REQUEST_NOTIF_PERMISSION,
                    getIntent().getBooleanExtra(EXTRA_REQUEST_NOTIF_PERMISSION, false));
        }
        startActivity(redirect);
        finish();
        overridePendingTransition(0, 0);
    }
}
