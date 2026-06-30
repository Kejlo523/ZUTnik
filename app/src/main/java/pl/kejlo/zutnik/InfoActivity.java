package pl.kejlo.zutnik;

import android.content.Intent;
import android.os.Bundle;

public class InfoActivity extends ZutnikBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        startActivity(MainShellActivity.createIntent(this, MainNavHelper.Screen.INFO));
        finish();
        overridePendingTransition(0, 0);
    }
}
