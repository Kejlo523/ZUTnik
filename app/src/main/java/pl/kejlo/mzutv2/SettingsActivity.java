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

import androidx.activity.EdgeToEdge;
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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        contentRoot = findViewById(R.id.contentRoot);
        // Toolbar setup, same pattern as other screens
        Toolbar toolbar = findViewById(R.id.toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

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

        int initialPos;
        if ("en".equals(currentLang)) {
            initialPos = 1;
        } else if ("uk".equals(currentLang)) {
            initialPos = 2;
        } else {
            initialPos = 0; // default to PL
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

                // 0 -> pl, 1 -> en, 2 -> uk
                String langCode;
                switch (position) {
                    case 1:
                        langCode = "en";
                        break;
                    case 2:
                        langCode = "uk";
                        break;
                    case 0:
                    default:
                        langCode = "pl";
                        break;
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

                Toast.makeText(SettingsActivity.this, "Zapisano ustawienia widgetu", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
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

        // Closing Settings is usually enough for changes to apply
        finish();
    }
}
