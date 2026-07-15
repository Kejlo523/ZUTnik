package pl.kejlo.zutnik;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import android.view.WindowManager;
import android.content.res.ColorStateList;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResult;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import androidx.biometric.BiometricPrompt;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends PhoneAwareActivity {

    private Spinner spinnerLanguage;
    private SwitchMaterial switchNotifMaster;
    private SwitchMaterial switchNotifGrades;
    private SwitchMaterial switchNotifFinance;
    private SwitchMaterial switchNotifFinanceDue;
    private SwitchMaterial switchNotifFinanceBooked;
    private SwitchMaterial switchNotifPlan;
    private SwitchMaterial switchNotifPlanMoved;
    private SwitchMaterial switchNotifPlanCancelled;
    private SwitchMaterial switchNotifPlanAdded;
    private SwitchMaterial switchNotifPlanRemoved;
    private LinearLayout layoutFinanceNotifCategories;
    private LinearLayout layoutPlanNotifCategories;
    private SwitchMaterial switchPrivacyMode;
    private View privacyStatusDot;
    private android.widget.TextView privacyStatus;
    private View btnPrivacyChangePin;

    private boolean internalNotifUiChange = false;
    private boolean pendingEnableByPermission = false;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<Intent> privacyAuthLauncher;
    private ActivityResultLauncher<String> settingsExportLauncher;
    private ActivityResultLauncher<String[]> settingsImportLauncher;
    private final ExecutorService settingsIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupNotificationPermissionLauncher();
        setupPrivacyAuthLauncher();
        setupSettingsBackupLaunchers();
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_settings);
        ThemeManager.applySystemBars(this);

        View contentRoot = findViewById(R.id.contentRoot);
        if (contentRoot != null) {
            MainNavHelper.applyRootContentInsets(contentRoot);
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(this, toolbar);
        toolbar.setTitle(R.string.settings_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
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

        String currentRefreshVal = SettingsPrefs.getWidgetRefreshInterval(this);

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
                    restartMainShell();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        setupNotificationSettings();
        setupPrivacySettings();
        setupSettingsBackupButtons();
    }

    private void setupSettingsBackupLaunchers() {
        settingsExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) exportSettings(uri);
                });
        settingsImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) readSettingsForImport(uri);
                });
    }

    private void setupSettingsBackupButtons() {
        View export = findViewById(R.id.btnSettingsExport);
        View importButton = findViewById(R.id.btnSettingsImport);
        if (export != null) {
            export.setOnClickListener(v -> settingsExportLauncher.launch(
                    getString(R.string.settings_backup_file_name)));
        }
        if (importButton != null) {
            importButton.setOnClickListener(v -> settingsImportLauncher.launch(new String[] {
                    "application/json",
                    "text/plain"
            }));
        }
    }

    private void exportSettings(Uri uri) {
        settingsIoExecutor.execute(() -> {
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt");
                 OutputStreamWriter writer = stream != null
                         ? new OutputStreamWriter(stream, StandardCharsets.UTF_8)
                         : null) {
                if (writer == null) throw new IllegalStateException("No output stream");
                writer.write(SettingsBackupManager.exportToJson(this));
                writer.flush();
                mainHandler.post(() -> Toast.makeText(
                        this,
                        R.string.settings_backup_exported,
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                mainHandler.post(this::showSettingsBackupError);
            }
        });
    }

    private void readSettingsForImport(Uri uri) {
        settingsIoExecutor.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri);
                 BufferedReader reader = stream != null
                         ? new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                         : null) {
                if (reader == null) throw new IllegalStateException("No input stream");
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (json.length() > 2_000_000) {
                        throw new IllegalArgumentException("Settings file too large");
                    }
                    json.append(line).append('\n');
                }
                new org.json.JSONObject(json.toString());
                mainHandler.post(() -> confirmSettingsImport(json.toString()));
            } catch (Exception e) {
                mainHandler.post(this::showSettingsBackupError);
            }
        });
    }

    private void confirmSettingsImport(String json) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_backup_import_title)
                .setMessage(R.string.settings_backup_import_message)
                .setPositiveButton(R.string.settings_backup_import, (dialog, which) ->
                        settingsIoExecutor.execute(() -> {
                            try {
                                SettingsBackupManager.importFromJson(this, json);
                                mainHandler.post(() -> {
                                    Toast.makeText(
                                            this,
                                            R.string.settings_backup_imported,
                                            Toast.LENGTH_SHORT).show();
                                    restartMainShell();
                                });
                            } catch (Exception e) {
                                mainHandler.post(this::showSettingsBackupError);
                            }
                        }))
                .setNegativeButton(R.string.dialog_add_edit_tile_btn_cancel, null)
                .show();
    }

    private void showSettingsBackupError() {
        Toast.makeText(this, R.string.settings_backup_error, Toast.LENGTH_LONG).show();
    }

    private void setupPrivacyAuthLauncher() {
        privacyAuthLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        PrivacyManager.disable(this);
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    }
                    bindPrivacyUi();
                });
    }

    private void setupPrivacySettings() {
        switchPrivacyMode = findViewById(R.id.switchPrivacyMode);
        privacyStatusDot = findViewById(R.id.privacyStatusDot);
        privacyStatus = findViewById(R.id.privacyStatus);
        btnPrivacyChangePin = findViewById(R.id.btnPrivacyChangePin);
        if (switchPrivacyMode == null || privacyStatus == null || privacyStatusDot == null) {
            return;
        }
        switchPrivacyMode.setUseMaterialThemeColors(true);
        bindPrivacyUi();
        switchPrivacyMode.setOnCheckedChangeListener((button, checked) -> {
            if (internalNotifUiChange) {
                return;
            }
            if (checked) {
                internalNotifUiChange = true;
                switchPrivacyMode.setChecked(false);
                internalNotifUiChange = false;
                showPinSetupDialog();
            } else if (PrivacyManager.isEnabled(this)) {
                internalNotifUiChange = true;
                switchPrivacyMode.setChecked(true);
                internalNotifUiChange = false;
                Intent intent = new Intent(this, PrivacyLockActivity.class);
                intent.putExtra(PrivacyLockActivity.EXTRA_AUTH_ONLY, true);
                privacyAuthLauncher.launch(intent);
                overridePendingTransition(R.anim.fade_in, 0);
            }
        });
        btnPrivacyChangePin.setOnClickListener(v -> showPinSetupDialog());
    }

    private void bindPrivacyUi() {
        if (switchPrivacyMode == null || privacyStatus == null || privacyStatusDot == null) {
            return;
        }
        boolean enabled = PrivacyManager.isEnabled(this);
        internalNotifUiChange = true;
        switchPrivacyMode.setChecked(enabled);
        internalNotifUiChange = false;
        if (!enabled) {
            privacyStatus.setText(R.string.settings_privacy_mode_status_off);
            privacyStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ThemeManager.resolveColor(this, R.attr.mzMuted)));
        } else if (PrivacyManager.isBiometricEnabled(this)) {
            privacyStatus.setText(R.string.settings_privacy_mode_status_biometric);
            privacyStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ThemeManager.resolveColor(this, R.attr.mzSuccess)));
        } else {
            privacyStatus.setText(R.string.settings_privacy_mode_status_pin);
            privacyStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ThemeManager.resolveColor(this, R.attr.mzSuccess)));
        }
        if (btnPrivacyChangePin != null) {
            btnPrivacyChangePin.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void showPinSetupDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_privacy_pin_setup, null);
        TextInputEditText pinInput = content.findViewById(R.id.privacyPinInput);
        TextInputEditText confirmInput = content.findViewById(R.id.privacyPinConfirmInput);
        TextInputLayout pinLayout = content.findViewById(R.id.privacyPinLayout);
        TextInputLayout confirmLayout = content.findViewById(R.id.privacyPinConfirmLayout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_setup_title)
                .setMessage(R.string.privacy_setup_message)
                .setView(content)
                .setPositiveButton(R.string.dialog_add_edit_tile_btn_save, null)
                .setNegativeButton(R.string.dialog_add_edit_tile_btn_cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String pin = pinInput.getText() != null ? pinInput.getText().toString() : "";
                    String confirmation = confirmInput.getText() != null
                            ? confirmInput.getText().toString()
                            : "";
                    pinLayout.setError(null);
                    confirmLayout.setError(null);
                    if (!pin.matches("\\d{4}")) {
                        pinLayout.setError(getString(R.string.privacy_pin_invalid));
                        return;
                    }
                    if (!pin.equals(confirmation)) {
                        confirmLayout.setError(getString(R.string.privacy_pin_mismatch));
                        return;
                    }
                    if (!PrivacyManager.setPinAndEnable(this, pin)) {
                        pinLayout.setError(getString(R.string.privacy_pin_invalid));
                        return;
                    }
                    pinInput.setText(null);
                    confirmInput.setText(null);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    dialog.dismiss();
                    bindPrivacyUi();
                    offerBiometricSetup();
                }));
        dialog.show();
    }

    private void offerBiometricSetup() {
        if (!PrivacyManager.isBiometricAvailable(this)) {
            showPrivacyReadyConfirmation();
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    private boolean completed;

                    @Override
                    public void onAuthenticationSucceeded(
                            @androidx.annotation.NonNull BiometricPrompt.AuthenticationResult result) {
                        if (completed) return;
                        completed = true;
                        PrivacyManager.setBiometricEnabled(SettingsActivity.this, true);
                        bindPrivacyUi();
                        showPrivacyReadyConfirmation();
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode,
                            @androidx.annotation.NonNull CharSequence errString) {
                        if (completed) return;
                        completed = true;
                        PrivacyManager.setBiometricEnabled(SettingsActivity.this, false);
                        bindPrivacyUi();
                        showPrivacyReadyConfirmation();
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.privacy_biometric_setup_title))
                .setSubtitle(getString(R.string.privacy_biometric_setup_subtitle))
                .setNegativeButtonText(getString(R.string.privacy_biometric_skip))
                .build();
        prompt.authenticate(info);
    }

    private void showPrivacyReadyConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_enabled_title)
                .setMessage(R.string.privacy_enabled_message)
                .setPositiveButton(R.string.privacy_test_lock, (dialog, which) -> {
                    PrivacyManager.lock();
                    if (PrivacyManager.beginLockActivityLaunch()) {
                        startActivity(new Intent(this, PrivacyLockActivity.class));
                        overridePendingTransition(R.anim.fade_in, 0);
                    }
                })
                .show();
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
        switchNotifFinance = findViewById(R.id.switchNotifFinance);
        switchNotifFinanceDue = findViewById(R.id.switchNotifFinanceDue);
        switchNotifFinanceBooked = findViewById(R.id.switchNotifFinanceBooked);
        switchNotifPlan = findViewById(R.id.switchNotifPlan);
        switchNotifPlanMoved = findViewById(R.id.switchNotifPlanMoved);
        switchNotifPlanCancelled = findViewById(R.id.switchNotifPlanCancelled);
        switchNotifPlanAdded = findViewById(R.id.switchNotifPlanAdded);
        switchNotifPlanRemoved = findViewById(R.id.switchNotifPlanRemoved);
        layoutFinanceNotifCategories = findViewById(R.id.layoutFinanceNotifCategories);
        layoutPlanNotifCategories = findViewById(R.id.layoutPlanNotifCategories);

        if (switchNotifMaster == null || switchNotifGrades == null
                || switchNotifFinance == null || switchNotifFinanceDue == null || switchNotifFinanceBooked == null
                || switchNotifPlan == null
                || switchNotifPlanMoved == null || switchNotifPlanCancelled == null
                || switchNotifPlanAdded == null || switchNotifPlanRemoved == null) {
            return;
        }

        applyThemeAwareSwitchColors(
                switchNotifMaster,
                switchNotifGrades,
                switchNotifFinance,
                switchNotifFinanceDue,
                switchNotifFinanceBooked,
                switchNotifPlan,
                switchNotifPlanMoved,
                switchNotifPlanCancelled,
                switchNotifPlanAdded,
                switchNotifPlanRemoved);

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

        switchNotifFinance.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_ENABLED, isChecked)
                    .apply();
            updateNotificationSwitchEnabledState();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifFinanceDue.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_DUE_ENABLED, isChecked)
                    .apply();
            NotificationSyncManager.syncWorkerSchedule(this);
            Toast.makeText(this, R.string.settings_notifications_saved, Toast.LENGTH_SHORT).show();
        });

        switchNotifFinanceBooked.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_BOOKED_ENABLED, isChecked)
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

        switchNotifPlanRemoved.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalNotifUiChange) {
                return;
            }
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PLAN_REMOVED_ENABLED, isChecked)
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
        switchNotifFinance.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_ENABLED));
        switchNotifFinanceDue.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_DUE_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_DUE_ENABLED));
        switchNotifFinanceBooked.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_BOOKED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_FINANCE_BOOKED_ENABLED));
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
        switchNotifPlanRemoved.setChecked(prefs.getBoolean(
                SettingsPrefs.KEY_NOTIFICATIONS_PLAN_REMOVED_ENABLED,
                SettingsPrefs.DEFAULT_NOTIFICATIONS_PLAN_REMOVED_ENABLED));
        internalNotifUiChange = false;

        updateNotificationSwitchEnabledState();
    }

    private void updateNotificationSwitchEnabledState() {
        if (switchNotifMaster == null) {
            return;
        }

        boolean hasPermission = NotificationSyncManager.hasNotificationPermission(this);
        boolean masterEnabled = switchNotifMaster.isChecked() && hasPermission;
        boolean financeEnabled = masterEnabled && switchNotifFinance.isChecked();
        boolean planEnabled = masterEnabled && switchNotifPlan.isChecked();

        switchNotifGrades.setEnabled(masterEnabled);
        switchNotifFinance.setEnabled(masterEnabled);
        switchNotifFinanceDue.setEnabled(financeEnabled);
        switchNotifFinanceBooked.setEnabled(financeEnabled);
        switchNotifPlan.setEnabled(masterEnabled);
        switchNotifPlanMoved.setEnabled(planEnabled);
        switchNotifPlanCancelled.setEnabled(planEnabled);
        switchNotifPlanAdded.setEnabled(planEnabled);
        switchNotifPlanRemoved.setEnabled(planEnabled);

        if (!hasPermission && switchNotifMaster.isChecked()) {
            internalNotifUiChange = true;
            switchNotifMaster.setChecked(false);
            internalNotifUiChange = false;
        }

        if (layoutFinanceNotifCategories != null) {
            layoutFinanceNotifCategories.setAlpha(financeEnabled ? 1f : 0.45f);
        }
        if (layoutPlanNotifCategories != null) {
            layoutPlanNotifCategories.setAlpha(planEnabled ? 1f : 0.45f);
        }
    }

    private void applyThemeAwareSwitchColors(SwitchMaterial... switches) {
        if (switches == null) {
            return;
        }
        for (SwitchMaterial sw : switches) {
            if (sw != null) {
                sw.setUseMaterialThemeColors(true);
            }
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

        restartMainShell();
    }

    private void restartMainShell() {
        Intent i = MainShellActivity.createIntent(this, MainNavHelper.Screen.HOME);
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

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        settingsIoExecutor.shutdownNow();
        super.onDestroy();
    }
}
