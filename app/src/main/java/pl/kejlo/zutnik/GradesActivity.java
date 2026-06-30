package pl.kejlo.zutnik;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;

public class GradesActivity extends ZutnikBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(MainShellActivity.createIntent(this, MainNavHelper.Screen.GRADES));
        finish();
        overridePendingTransition(0, 0);
    }
}
