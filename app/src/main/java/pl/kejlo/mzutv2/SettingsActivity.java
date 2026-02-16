package pl.kejlo.mzutv2;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {
    private static final String DEBUG_USER_LOGIN = "nj57796";
    private static final DateTimeFormatter DEBUG_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault());

    private Spinner spinnerLanguage;
    private SwitchMaterial switchNotifMaster;
    private SwitchMaterial switchNotifGrades;
    private SwitchMaterial switchNotifPlan;
    private SwitchMaterial switchNotifPlanMoved;
    private SwitchMaterial switchNotifPlanCancelled;
    private SwitchMaterial switchNotifPlanAdded;
    private LinearLayout layoutPlanNotifCategories;
    private LinearLayout settingsDebugSectionContainer;
    private SwitchMaterial switchDebugTools;
    private SwitchMaterial switchDebugRunGrades;
    private SwitchMaterial switchDebugRunPlan;
    private SwitchMaterial switchDebugIgnoreCalendar;
    private LinearLayout layoutDebugToolsBody;
    private Button btnDebugRunNow;
    private Button btnDebugResetBaselines;
    private Button btnDebugTestGradesNotif;
    private Button btnDebugTestPlanNotif;
    private Button btnDebugExpireSession;

    private boolean internalNotifUiChange = false;
    private boolean internalDebugUiChange = false;
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
        setupDebugToolsSection();
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

    private void setupDebugToolsSection() {
        settingsDebugSectionContainer = findViewById(R.id.settingsDebugSectionContainer);
        switchDebugTools = findViewById(R.id.switchDebugTools);
        switchDebugRunGrades = findViewById(R.id.switchDebugRunGrades);
        switchDebugRunPlan = findViewById(R.id.switchDebugRunPlan);
        switchDebugIgnoreCalendar = findViewById(R.id.switchDebugIgnoreCalendar);
        layoutDebugToolsBody = findViewById(R.id.layoutDebugToolsBody);
        btnDebugRunNow = findViewById(R.id.btnDebugRunNow);
        btnDebugResetBaselines = findViewById(R.id.btnDebugResetBaselines);
        btnDebugTestGradesNotif = findViewById(R.id.btnDebugTestGradesNotif);
        btnDebugTestPlanNotif = findViewById(R.id.btnDebugTestPlanNotif);
        btnDebugExpireSession = findViewById(R.id.btnDebugExpireSession);

        if (settingsDebugSectionContainer == null
                || switchDebugTools == null
                || switchDebugRunGrades == null
                || switchDebugRunPlan == null
                || switchDebugIgnoreCalendar == null
                || layoutDebugToolsBody == null
                || btnDebugRunNow == null
                || btnDebugResetBaselines == null
                || btnDebugTestGradesNotif == null
                || btnDebugTestPlanNotif == null
                || btnDebugExpireSession == null) {
            return;
        }

        if (!isDebugUser()) {
            settingsDebugSectionContainer.setVisibility(View.GONE);
            return;
        }

        settingsDebugSectionContainer.setVisibility(View.VISIBLE);
        bindDebugControlsFromPrefs();

        switchDebugTools.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalDebugUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_DEBUG_TOOLS_ENABLED, isChecked)
                    .apply();
            updateDebugControlsState();
            Toast.makeText(this, R.string.settings_debug_saved, Toast.LENGTH_SHORT).show();
        });

        switchDebugRunGrades.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalDebugUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_DEBUG_RUN_GRADES, isChecked)
                    .apply();
            Toast.makeText(this, R.string.settings_debug_saved, Toast.LENGTH_SHORT).show();
        });

        switchDebugRunPlan.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalDebugUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_DEBUG_RUN_PLAN, isChecked)
                    .apply();
            Toast.makeText(this, R.string.settings_debug_saved, Toast.LENGTH_SHORT).show();
        });

        switchDebugIgnoreCalendar.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalDebugUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_DEBUG_IGNORE_CALENDAR, isChecked)
                    .apply();
            Toast.makeText(this, R.string.settings_debug_saved, Toast.LENGTH_SHORT).show();
        });

        btnDebugRunNow.setOnClickListener(v -> {
            if (!isDebugEnabled()) {
                Toast.makeText(this, R.string.settings_debug_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            boolean runGrades = switchDebugRunGrades.isChecked();
            boolean runPlan = switchDebugRunPlan.isChecked();
            if (!runGrades && !runPlan) {
                Toast.makeText(this, R.string.settings_debug_select_scope, Toast.LENGTH_SHORT).show();
                return;
            }

            NotificationSyncManager.runDebugSyncNow(
                    this,
                    runGrades,
                    runPlan,
                    switchDebugIgnoreCalendar.isChecked());

            if (!NotificationSyncManager.hasNotificationPermission(this)) {
                Toast.makeText(this, R.string.settings_debug_missing_notifications_permission, Toast.LENGTH_SHORT).show();
            }
            Toast.makeText(this, R.string.settings_debug_run_started, Toast.LENGTH_SHORT).show();
        });

        btnDebugResetBaselines.setOnClickListener(v -> {
            if (!isDebugEnabled()) {
                Toast.makeText(this, R.string.settings_debug_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            BackgroundSyncWorker.clearBaselines(this);
            Toast.makeText(this, R.string.settings_debug_baselines_reset, Toast.LENGTH_SHORT).show();
        });

        btnDebugTestGradesNotif.setOnClickListener(v -> {
            if (!isDebugEnabled()) {
                Toast.makeText(this, R.string.settings_debug_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!NotificationSyncManager.hasNotificationPermission(this)) {
                Toast.makeText(this, R.string.settings_debug_missing_notifications_permission, Toast.LENGTH_SHORT).show();
                return;
            }
            showDebugGradeSimulationDialog();
        });

        btnDebugTestPlanNotif.setOnClickListener(v -> {
            if (!isDebugEnabled()) {
                Toast.makeText(this, R.string.settings_debug_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!NotificationSyncManager.hasNotificationPermission(this)) {
                Toast.makeText(this, R.string.settings_debug_missing_notifications_permission, Toast.LENGTH_SHORT).show();
                return;
            }
            showDebugPlanSimulationDialog();
        });

        btnDebugExpireSession.setOnClickListener(v -> {
            if (!isDebugEnabled()) {
                Toast.makeText(this, R.string.settings_debug_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            SessionExpiryManager.handleSessionExpired(getApplicationContext(), "DEBUG_TOOL");
            finish();
        });
    }

    private boolean isDebugUser() {
        MzutSession.initializeFromPreferences(this);
        String userId = MzutSession.getInstance().getUserId();
        return userId != null && DEBUG_USER_LOGIN.equalsIgnoreCase(userId.trim());
    }

    private boolean isDebugEnabled() {
        return isDebugUser() && switchDebugTools != null && switchDebugTools.isChecked();
    }

    private void bindDebugControlsFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
        internalDebugUiChange = true;
        switchDebugTools.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_DEBUG_TOOLS_ENABLED,
                SettingsPrefs.DEFAULT_DEBUG_TOOLS_ENABLED));
        switchDebugRunGrades.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_DEBUG_RUN_GRADES,
                SettingsPrefs.DEFAULT_DEBUG_RUN_GRADES));
        switchDebugRunPlan.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_DEBUG_RUN_PLAN,
                SettingsPrefs.DEFAULT_DEBUG_RUN_PLAN));
        switchDebugIgnoreCalendar.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_DEBUG_IGNORE_CALENDAR,
                SettingsPrefs.DEFAULT_DEBUG_IGNORE_CALENDAR));
        internalDebugUiChange = false;
        updateDebugControlsState();
    }

    private void updateDebugControlsState() {
        if (switchDebugTools == null) {
            return;
        }
        boolean enabled = switchDebugTools.isChecked();
        if (switchDebugRunGrades != null) {
            switchDebugRunGrades.setEnabled(enabled);
        }
        if (switchDebugRunPlan != null) {
            switchDebugRunPlan.setEnabled(enabled);
        }
        if (switchDebugIgnoreCalendar != null) {
            switchDebugIgnoreCalendar.setEnabled(enabled);
        }
        if (btnDebugRunNow != null) {
            btnDebugRunNow.setEnabled(enabled);
        }
        if (btnDebugResetBaselines != null) {
            btnDebugResetBaselines.setEnabled(enabled);
        }
        if (btnDebugTestGradesNotif != null) {
            btnDebugTestGradesNotif.setEnabled(enabled);
        }
        if (btnDebugTestPlanNotif != null) {
            btnDebugTestPlanNotif.setEnabled(enabled);
        }
        if (btnDebugExpireSession != null) {
            btnDebugExpireSession.setEnabled(enabled);
        }
        if (layoutDebugToolsBody != null) {
            layoutDebugToolsBody.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private interface DebugDateCallback {
        void onDateSelected(LocalDate date);
    }

    private interface DebugTimeCallback {
        void onTimeSelected(int minutesFromMidnight);
    }

    private void showDebugGradeSimulationDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_debug_simulate_grade, null);
        EditText subjectInput = dialogView.findViewById(R.id.etDebugGradeSubject);
        EditText gradeInput = dialogView.findViewById(R.id.etDebugGradeValue);
        EditText dateInput = dialogView.findViewById(R.id.etDebugGradeDate);

        LocalDate[] selectedDate = new LocalDate[]{LocalDate.now()};
        subjectInput.setText(getString(R.string.settings_debug_default_subject));
        gradeInput.setText(getString(R.string.settings_debug_default_grade_value));
        dateInput.setText(formatDebugDate(selectedDate[0]));

        dateInput.setOnClickListener(v -> showDebugDatePicker(
                selectedDate[0],
                date -> {
                    selectedDate[0] = date;
                    dateInput.setText(formatDebugDate(date));
                }));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_debug_dialog_grade_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_debug_dialog_send, (dialog, which) -> {
                    String subject = readTextOrDefault(subjectInput, R.string.settings_debug_default_subject);
                    String gradeValue = readTextOrDefault(gradeInput, R.string.settings_debug_default_grade_value);
                    NotificationDebugTools.showSimulatedGradeNotification(
                            this,
                            subject,
                            gradeValue,
                            selectedDate[0]);
                    Toast.makeText(this, R.string.settings_debug_simulation_sent, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showDebugPlanSimulationDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_debug_simulate_plan_change, null);
        Spinner spinnerChangeType = dialogView.findViewById(R.id.spinnerDebugPlanChangeType);
        EditText subjectInput = dialogView.findViewById(R.id.etDebugPlanSubject);
        EditText fromDateInput = dialogView.findViewById(R.id.etDebugPlanFromDate);
        EditText fromTimeInput = dialogView.findViewById(R.id.etDebugPlanFromTime);
        LinearLayout movedTargetLayout = dialogView.findViewById(R.id.layoutDebugPlanMovedTarget);
        EditText toDateInput = dialogView.findViewById(R.id.etDebugPlanToDate);
        EditText toTimeInput = dialogView.findViewById(R.id.etDebugPlanToTime);

        ArrayAdapter<CharSequence> typeAdapter = ArrayAdapter.createFromResource(
                this,
                R.array.debug_plan_change_type_entries,
                R.layout.spinner_item_dark);
        typeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerChangeType.setAdapter(typeAdapter);

        String[] changeTypeValues = getResources().getStringArray(R.array.debug_plan_change_type_values);

        LocalDate[] fromDate = new LocalDate[]{LocalDate.now()};
        int[] fromMinutes = new int[]{8 * 60};
        LocalDate[] toDate = new LocalDate[]{LocalDate.now().plusDays(1)};
        int[] toMinutes = new int[]{10 * 60};

        subjectInput.setText(getString(R.string.settings_debug_default_subject));
        fromDateInput.setText(formatDebugDate(fromDate[0]));
        fromTimeInput.setText(formatDebugTime(fromMinutes[0]));
        toDateInput.setText(formatDebugDate(toDate[0]));
        toTimeInput.setText(formatDebugTime(toMinutes[0]));

        spinnerChangeType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                NotificationDebugTools.PlanChangeType type = mapPlanChangeType(changeTypeValues, position);
                movedTargetLayout.setVisibility(type == NotificationDebugTools.PlanChangeType.MOVED
                        ? View.VISIBLE
                        : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                movedTargetLayout.setVisibility(View.GONE);
            }
        });

        fromDateInput.setOnClickListener(v -> showDebugDatePicker(
                fromDate[0],
                date -> {
                    fromDate[0] = date;
                    fromDateInput.setText(formatDebugDate(date));
                }));

        fromTimeInput.setOnClickListener(v -> showDebugTimePicker(
                fromMinutes[0],
                minutes -> {
                    fromMinutes[0] = minutes;
                    fromTimeInput.setText(formatDebugTime(minutes));
                }));

        toDateInput.setOnClickListener(v -> showDebugDatePicker(
                toDate[0],
                date -> {
                    toDate[0] = date;
                    toDateInput.setText(formatDebugDate(date));
                }));

        toTimeInput.setOnClickListener(v -> showDebugTimePicker(
                toMinutes[0],
                minutes -> {
                    toMinutes[0] = minutes;
                    toTimeInput.setText(formatDebugTime(minutes));
                }));

        spinnerChangeType.setSelection(0);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_debug_dialog_plan_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_debug_dialog_send, (dialog, which) -> {
                    NotificationDebugTools.PlanChangeType type = mapPlanChangeType(
                            changeTypeValues,
                            spinnerChangeType.getSelectedItemPosition());
                    String subject = readTextOrDefault(subjectInput, R.string.settings_debug_default_subject);

                    NotificationDebugTools.showSimulatedPlanNotification(
                            this,
                            type,
                            subject,
                            fromDate[0],
                            fromMinutes[0],
                            toDate[0],
                            toMinutes[0]);
                    Toast.makeText(this, R.string.settings_debug_simulation_sent, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showDebugDatePicker(LocalDate currentDate, DebugDateCallback callback) {
        LocalDate safeDate = currentDate != null ? currentDate : LocalDate.now();
        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    if (callback != null) {
                        callback.onDateSelected(LocalDate.of(year, month + 1, dayOfMonth));
                    }
                },
                safeDate.getYear(),
                safeDate.getMonthValue() - 1,
                safeDate.getDayOfMonth());
        picker.show();
    }

    private void showDebugTimePicker(int currentMinutes, DebugTimeCallback callback) {
        int safeMinutes = normalizeMinutes(currentMinutes);
        int hour = safeMinutes / 60;
        int minute = safeMinutes % 60;

        TimePickerDialog picker = new TimePickerDialog(
                this,
                (view, hourOfDay, selectedMinute) -> {
                    if (callback != null) {
                        callback.onTimeSelected((hourOfDay * 60) + selectedMinute);
                    }
                },
                hour,
                minute,
                true);
        picker.show();
    }

    private NotificationDebugTools.PlanChangeType mapPlanChangeType(String[] values, int position) {
        if (values != null && position >= 0 && position < values.length) {
            String value = values[position];
            if ("cancelled".equalsIgnoreCase(value)) {
                return NotificationDebugTools.PlanChangeType.CANCELLED;
            }
            if ("moved".equalsIgnoreCase(value)) {
                return NotificationDebugTools.PlanChangeType.MOVED;
            }
        }
        return NotificationDebugTools.PlanChangeType.ADDED;
    }

    private String readTextOrDefault(EditText input, int fallbackResId) {
        String fallback = getString(fallbackResId);
        if (input == null || input.getText() == null) {
            return fallback;
        }
        String value = input.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private String formatDebugDate(LocalDate date) {
        LocalDate safeDate = date != null ? date : LocalDate.now();
        return safeDate.format(DEBUG_DATE_FORMAT);
    }

    private String formatDebugTime(int minutes) {
        int safeMinutes = normalizeMinutes(minutes);
        int hour = safeMinutes / 60;
        int minute = safeMinutes % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private int normalizeMinutes(int minutes) {
        if (minutes < 0) {
            return 0;
        }
        if (minutes > (23 * 60 + 59)) {
            return 23 * 60 + 59;
        }
        return minutes;
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
