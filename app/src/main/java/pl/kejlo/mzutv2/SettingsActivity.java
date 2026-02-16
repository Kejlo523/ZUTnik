package pl.kejlo.mzutv2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {

    private Spinner spinnerLanguage;
    private SwitchMaterial switchNotifMaster;
    private SwitchMaterial switchNotifGrades;
    private SwitchMaterial switchNotifPlan;
    private SwitchMaterial switchNotifPlanMoved;
    private SwitchMaterial switchNotifPlanCancelled;
    private SwitchMaterial switchNotifPlanAdded;
    private LinearLayout layoutPlanNotifCategories;

    private boolean internalNotifUiChange = false;
    private boolean pendingEnableByPermission = false;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupNotificationPermissionLauncher();
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_settings);
        ThemeManager.applySystemBars(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        spinnerLanguage = findViewById(R.id.spinnerLanguage);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.settings_language_entries,
                R.layout.spinner_item_dark);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerLanguage.setAdapter(adapter);

        String currentLang = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                .getString(SettingsPrefs.KEY_APP_LANGUAGE, SettingsPrefs.DEFAULT_APP_LANGUAGE);

        String[] values = getResources().getStringArray(R.array.settings_language_values);
        int initialPos = findValuePosition(values, currentLang, 0);
        spinnerLanguage.setSelection(initialPos);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }

                String[] values = getResources().getStringArray(R.array.settings_language_values);
                String langCode = SettingsPrefs.DEFAULT_APP_LANGUAGE;
                if (position >= 0 && position < values.length) {
                    langCode = values[position];
                }
                applyLanguage(langCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Spinner spinnerRefresh = findViewById(R.id.spinnerWidgetRefresh);
        ArrayAdapter<CharSequence> adapterRefresh = ArrayAdapter.createFromResource(
                this,
                R.array.settings_widget_refresh_entries,
                R.layout.spinner_item_dark);
        adapterRefresh.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerRefresh.setAdapter(adapterRefresh);

        SharedPreferences prefs = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
        String currentRefreshVal = prefs.getString(
                SettingsPrefs.KEY_WIDGET_REFRESH_INTERVAL,
                SettingsPrefs.DEFAULT_WIDGET_REFRESH_INTERVAL);

        String[] refreshValues = getResources().getStringArray(R.array.settings_widget_refresh_values);
        int refreshPos = findValuePosition(
                refreshValues,
                currentRefreshVal,
                findValuePosition(refreshValues, SettingsPrefs.DEFAULT_WIDGET_REFRESH_INTERVAL, 0));
        spinnerRefresh.setSelection(refreshPos);

        spinnerRefresh.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }
                if (position < 0 || position >= refreshValues.length) {
                    return;
                }
                String val = refreshValues[position];
                getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                        .edit()
                        .putString(SettingsPrefs.KEY_WIDGET_REFRESH_INTERVAL, val)
                        .apply();

                PlanDayWidgetProvider.rescheduleRefresh(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, R.string.settings_widget_refresh_saved, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Spinner spinnerTheme = findViewById(R.id.spinnerTheme);
        ArrayAdapter<CharSequence> adapterTheme = ArrayAdapter.createFromResource(
                this,
                R.array.settings_theme_entries,
                R.layout.spinner_item_dark);
        adapterTheme.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerTheme.setAdapter(adapterTheme);

        String currentTheme = ThemeManager.getTheme(this);
        String[] themeValues = getResources().getStringArray(R.array.settings_theme_values);
        int themePos = findValuePosition(themeValues, currentTheme, 0);
        spinnerTheme.setSelection(themePos);

        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) {
                    firstCall = false;
                    return;
                }
                if (position < 0 || position >= themeValues.length) {
                    return;
                }
                String val = themeValues[position];
                String current = ThemeManager.getTheme(SettingsActivity.this);
                if (!val.equals(current)) {
                    ThemeManager.setTheme(SettingsActivity.this, val);
                    Toast.makeText(SettingsActivity.this, R.string.settings_theme_changed, Toast.LENGTH_SHORT).show();

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

        setupNotificationSettings();
    }

    private void setupNotificationPermissionLauncher() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    SharedPreferences prefs = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
                    prefs.edit()
                            .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                            .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, granted && pendingEnableByPermission)
                            .apply();

                    if (!granted) {
                        Toast.makeText(
                                this,
                                R.string.settings_notifications_permission_denied,
                                Toast.LENGTH_LONG).show();
                    }
                    pendingEnableByPermission = false;
                    bindNotificationSwitchesFromPrefs();
                    NotificationSyncManager.syncWorkerSchedule(this);
                });
    }

    private void setupNotificationSettings() {
        switchNotifMaster = findViewById(R.id.switchNotifMaster);
        switchNotifGrades = findViewById(R.id.switchNotifGrades);
        switchNotifPlan = findViewById(R.id.switchNotifPlan);
        switchNotifPlanMoved = findViewById(R.id.switchNotifPlanMoved);
        switchNotifPlanCancelled = findViewById(R.id.switchNotifPlanCancelled);
        switchNotifPlanAdded = findViewById(R.id.switchNotifPlanAdded);
        layoutPlanNotifCategories = findViewById(R.id.layoutPlanNotifCategories);

        if (switchNotifMaster == null || switchNotifGrades == null || switchNotifPlan == null
                || switchNotifPlanMoved == null || switchNotifPlanCancelled == null || switchNotifPlanAdded == null) {
            return;
        }

        bindNotificationSwitchesFromPrefs();

        switchNotifMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }

            if (isChecked && !NotificationSyncManager.hasNotificationPermission(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pendingEnableByPermission = true;
                    internalNotifUiChange = true;
                    switchNotifMaster.setChecked(false);
                    internalNotifUiChange = false;
                    Toast.makeText(
                            this,
                            R.string.settings_notifications_permission_required,
                            Toast.LENGTH_SHORT).show();
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            }

            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, isChecked)
                    .apply();
            updateNotificationSwitchEnabledState();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifGrades.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_GRADES_ENABLED, isChecked)
                    .apply();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifPlan.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ENABLED, isChecked)
                    .apply();
            updateNotificationSwitchEnabledState();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifPlanMoved.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED, isChecked)
                    .apply();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifPlanCancelled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED, isChecked)
                    .apply();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifPlanAdded.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED, isChecked)
                    .apply();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void bindNotificationSwitchesFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);

        internalNotifUiChange = true;
        switchNotifMaster.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_MASTER_ENABLED));
        switchNotifGrades.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_GRADES_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_GRADES_ENABLED));
        switchNotifPlan.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_ENABLED));
        switchNotifPlanMoved.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_MOVED_ENABLED));
        switchNotifPlanCancelled.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_CANCELLED_ENABLED));
        switchNotifPlanAdded.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_ADDED_ENABLED));
        internalNotifUiChange = false;

        updateNotificationSwitchEnabledState();
    }

    private void updateNotificationSwitchEnabledState() {
        if (switchNotifMaster == null) {
            return;
        }

        boolean hasPermission = NotificationSyncManager.hasNotificationPermission(this);
        boolean masterEnabled = switchNotifMaster.isChecked() && hasPermission;
        boolean planEnabled = masterEnabled && switchNotifPlan.isChecked();

        switchNotifGrades.setEnabled(masterEnabled);
        switchNotifPlan.setEnabled(masterEnabled);
        switchNotifPlanMoved.setEnabled(planEnabled);
        switchNotifPlanCancelled.setEnabled(planEnabled);
        switchNotifPlanAdded.setEnabled(planEnabled);

        if (!hasPermission && switchNotifMaster.isChecked()) {
            internalNotifUiChange = true;
            switchNotifMaster.setChecked(false);
            internalNotifUiChange = false;
        }

        if (layoutPlanNotifCategories != null) {
            layoutPlanNotifCategories.setAlpha(planEnabled ? 1f : 0.45f);
        }
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
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applyLanguage(String langCode) {
        SharedPreferences prefs = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
        prefs.edit().putString(SettingsPrefs.KEY_APP_LANGUAGE, langCode).apply();

        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(langCode);
        AppCompatDelegate.setApplicationLocales(appLocale);

        Toast.makeText(
                this,
                getString(R.string.settings_language_changed_toast),
                Toast.LENGTH_SHORT).show();

        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private int findValuePosition(String[] values, String value, int fallback) {
        if (values == null || values.length == 0) {
            return fallback;
        }
        if (value == null) {
            return fallback;
        }
        for (int i = 0; i < values.length; i++) {
            if (value.equals(values[i])) {
                return i;
            }
        }
        return fallback;
    }
}
