package pl.kejlo.zutnik;

import android.os.Bundle;

import androidx.annotation.Nullable;

public class FinanceActivity extends ZutnikBaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_shell);
        ThemeManager.applySystemBars(this);

        ShellHostHelper.setupNavigation(this, MainNavHelper.Screen.FINANCE);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragmentContainer, new FinanceTabFragment(), MainNavHelper.Screen.FINANCE.getId())
                    .commit();
        }
    }
}
