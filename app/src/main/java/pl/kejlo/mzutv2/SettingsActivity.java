package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.os.LocaleListCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        // Keep behavior consistent with InfoActivity / NewsDetailActivity
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private static final String PREFS_SETTINGS = "mzut_settings";
    private static final String KEY_APP_LANGUAGE = "app_language"; // "pl" / "en" / "uk"

    private Spinner spinnerLanguage;
    private LinearLayout contentRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_settings);
        ThemeManager.applySystemBars(this);

        contentRoot = findViewById(R.id.contentRoot);
        // Toolbar setup, same pattern as other screens
        Toolbar toolbar = findViewById(R.id.toolbar);

        // ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, windowInsets) -> {
        //    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
        //    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
        //    return WindowInsetsCompat.CONSUMED;
        // });

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        spinnerLanguage = findViewById(R.id.spinnerLanguage);

        // Adapter with custom dark layout (white text), same style as in InfoActivity
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.settings_language_entries,
                R.layout.spinner_item_dark);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerLanguage.setAdapter(adapter);

        // Load current language setting
        String currentLang = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, "pl");

        String[] values = getResources().getStringArray(R.array.settings_language_values);
        int initialPos = 0; // Default to first (PL)
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentLang)) {
                initialPos = i;
                break;
            }
        }
        spinnerLanguage.setSelection(initialPos);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }

                // Get values dynamically
                String[] values = getResources().getStringArray(R.array.settings_language_values);
                String langCode = "pl";
                if (position >= 0 && position < values.length) {
                    langCode = values[position];
                }

                applyLanguage(langCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        // Widget Refresh Setup
        Spinner spinnerRefresh = findViewById(R.id.spinnerWidgetRefresh);
        ArrayAdapter<CharSequence> adapterRefresh = ArrayAdapter.createFromResource(
                this,
                R.array.settings_widget_refresh_entries,
                R.layout.spinner_item_dark);
        adapterRefresh.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerRefresh.setAdapter(adapterRefresh);

        // Load current
        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        String currentRefreshVal = prefs.getString("widget_refresh_interval", "30"); // default 30

        String[] refreshValues = getResources().getStringArray(R.array.settings_widget_refresh_values);
        int refreshPos = 1; // Default to 30 (index 1)
        for (int i = 0; i < refreshValues.length; i++) {
            if (refreshValues[i].equals(currentRefreshVal)) {
                refreshPos = i;
                break;
            }
        }
        spinnerRefresh.setSelection(refreshPos);

        spinnerRefresh.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }
                String val = refreshValues[position];
                getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                        .edit()
                        .putString("widget_refresh_interval", val)
                        .apply();

                // Reschedule widget
                PlanDayWidgetProvider.rescheduleRefresh(SettingsActivity.this);

                Toast.makeText(SettingsActivity.this, R.string.settings_widget_refresh_saved, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Theme Setup
        Spinner spinnerTheme = findViewById(R.id.spinnerTheme);
        ArrayAdapter<CharSequence> adapterTheme = ArrayAdapter.createFromResource(
                this,
                R.array.settings_theme_entries,
                R.layout.spinner_item_dark);
        adapterTheme.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerTheme.setAdapter(adapterTheme);

        String currentTheme = ThemeManager.getTheme(this);
        String[] themeValues = getResources().getStringArray(R.array.settings_theme_values);
        int themePos = 0;
        for (int i = 0; i < themeValues.length; i++) {
            if (themeValues[i].equals(currentTheme)) {
                themePos = i;
                break;
            }
        }
        spinnerTheme.setSelection(themePos);

        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }
                String val = themeValues[position];
                String current = ThemeManager.getTheme(SettingsActivity.this);
                if (!val.equals(current)) {
                    ThemeManager.setTheme(SettingsActivity.this, val);
                    Toast.makeText(SettingsActivity.this, R.string.settings_theme_changed, Toast.LENGTH_SHORT).show();

                    // Restart app with cleared back stack to apply theme everywhere
                    Intent i = new Intent(SettingsActivity.this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Back arrow in toolbar, same behavior as in NewsDetailActivity
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applyLanguage(String langCode) {
        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        prefs.edit().putString(KEY_APP_LANGUAGE, langCode).apply();

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(langCode);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Toast.makeText(
                this,
                getString(R.string.settings_language_changed_toast),
                Toast.LENGTH_SHORT).show();

        // Restart app with cleared back stack to apply language everywhere
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
