package pl.kejlo.zutnik;

import android.content.Intent;
import android.os.Bundle;

public class PlanActivity extends ZutnikBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        Intent redirect = MainShellActivity.createIntent(this, MainNavHelper.Screen.PLAN);
        if (getIntent() != null) {
            if (getIntent().hasExtra("EXTRA_SEARCH_QUERY")) {
                redirect.putExtra("EXTRA_SEARCH_QUERY", getIntent().getStringExtra("EXTRA_SEARCH_QUERY"));
            }
            if (getIntent().hasExtra("EXTRA_SEARCH_CATEGORY")) {
                redirect.putExtra("EXTRA_SEARCH_CATEGORY", getIntent().getStringExtra("EXTRA_SEARCH_CATEGORY"));
            }
        }
        startActivity(redirect);
        finish();
        overridePendingTransition(0, 0);
    }
}
