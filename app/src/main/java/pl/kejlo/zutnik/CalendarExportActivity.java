package pl.kejlo.zutnik;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CalendarExportActivity extends ZutnikBaseActivity {

    private static final String[] CALENDAR_PERMISSIONS = new String[] {
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<PlanCalendarExportHelper.DeviceCalendarInfo> availableCalendars = new ArrayList<>();

    private ActivityResultLauncher<String[]> calendarPermissionLauncher;
    private ActivityResultLauncher<String> saveIcsLauncher;
    private ActivityResultLauncher<String> savePdfLauncher;

    private PlanCalendarExportHelper exportHelper;
    private PlanCalendarExportHelper.ExportScope selectedScope = PlanCalendarExportHelper.ExportScope.CURRENT_VIEW;
    private String pendingIcsContent;
    private byte[] pendingPdfContent;
    private CalendarAction pendingCalendarAction = CalendarAction.NONE;

    private AppCompatButton btnScopeCurrent;
    private AppCompatButton btnScopeSemester;
    private TextView tvScopeSummary;
    private android.view.View progress;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerLaunchers();
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_calendar_export);
        ThemeManager.applySystemBars(this);

        exportHelper = PlanCalendarExportHelper.fromIntent(this, getIntent());

        android.view.View contentRoot = findViewById(R.id.contentRoot);
        MainNavHelper.applyRootContentInsets(contentRoot);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(this, toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.calendar_export_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        btnScopeCurrent = findViewById(R.id.btnScopeCurrent);
        btnScopeSemester = findViewById(R.id.btnScopeSemester);
        tvScopeSummary = findViewById(R.id.tvScopeSummary);
        progress = findViewById(R.id.exportProgress);

        if (btnScopeCurrent != null) {
            btnScopeCurrent.setOnClickListener(v -> setSelectedScope(PlanCalendarExportHelper.ExportScope.CURRENT_VIEW));
        }
        if (btnScopeSemester != null) {
            btnScopeSemester.setOnClickListener(v -> setSelectedScope(PlanCalendarExportHelper.ExportScope.SEMESTER));
        }

        TextView btnSaveIcs = findViewById(R.id.btnSaveIcs);
        TextView btnShareIcs = findViewById(R.id.btnShareIcs);
        TextView btnSavePdf = findViewById(R.id.btnSavePdf);
        TextView btnSharePdf = findViewById(R.id.btnSharePdf);
        TextView btnImportDeviceCalendar = findViewById(R.id.btnImportDeviceCalendar);
        TextView btnClearDeviceCalendar = findViewById(R.id.btnClearDeviceCalendar);

        if (btnSaveIcs != null) {
            btnSaveIcs.setOnClickListener(v -> saveIcs());
        }
        if (btnShareIcs != null) {
            btnShareIcs.setOnClickListener(v -> shareIcs());
        }
        if (btnSavePdf != null) {
            btnSavePdf.setOnClickListener(v -> runPdfExport(false));
        }
        if (btnSharePdf != null) {
            btnSharePdf.setOnClickListener(v -> runPdfExport(true));
        }
        if (btnImportDeviceCalendar != null) {
            btnImportDeviceCalendar.setOnClickListener(v -> importToDeviceCalendar());
        }
        if (btnClearDeviceCalendar != null) {
            btnClearDeviceCalendar.setOnClickListener(v -> clearDeviceCalendar());
        }

        updateScopeUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCalendarPermissions()) {
            refreshDeviceCalendars(CalendarAction.NONE);
        } else {
            availableCalendars.clear();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void registerLaunchers() {
        calendarPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = true;
                    for (String permission : CALENDAR_PERMISSIONS) {
                        Boolean state = result.get(permission);
                        if (state == null || !state) {
                            granted = false;
                            break;
                        }
                    }
                    if (!granted) {
                        pendingCalendarAction = CalendarAction.NONE;
                        Toast.makeText(this, R.string.calendar_export_permission_denied, Toast.LENGTH_LONG).show();
                        return;
                    }

                    CalendarAction action = pendingCalendarAction;
                    pendingCalendarAction = CalendarAction.NONE;
                    refreshDeviceCalendars(action);
                });

        saveIcsLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/calendar"),
                uri -> {
                    if (uri == null) {
                        pendingIcsContent = null;
                        return;
                    }
                    writeIcsToUri(uri);
                });

        savePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> {
                    if (uri == null) {
                        pendingPdfContent = null;
                        return;
                    }
                    writePdfToUri(uri);
                });
    }

    private void setSelectedScope(PlanCalendarExportHelper.ExportScope scope) {
        selectedScope = scope;
        updateScopeUi();
    }

    private void updateScopeUi() {
        if (btnScopeCurrent != null) {
            btnScopeCurrent.setSelected(selectedScope == PlanCalendarExportHelper.ExportScope.CURRENT_VIEW);
        }
        if (btnScopeSemester != null) {
            btnScopeSemester.setSelected(selectedScope == PlanCalendarExportHelper.ExportScope.SEMESTER);
        }
        if (tvScopeSummary != null) {
            tvScopeSummary.setText(selectedScope == PlanCalendarExportHelper.ExportScope.SEMESTER
                    ? R.string.calendar_export_scope_semester_desc
                    : R.string.calendar_export_scope_current_desc);
        }
    }

    private void saveIcs() {
        runIcsExport(false);
    }

    private void shareIcs() {
        runIcsExport(true);
    }

    private void runIcsExport(boolean share) {
        showProgress(true);
        executor.execute(() -> {
            try {
                PlanCalendarExportHelper.ExportResult export = exportHelper.buildExport(selectedScope);
                if (export.events == null || export.events.isEmpty()) {
                    postToast(R.string.plan_export_ics_no_events, Toast.LENGTH_SHORT);
                    showProgress(false);
                    return;
                }

                if (share) {
                    Uri uri = PlanCalendarExportHelper.writeShareCacheFile(this, export.icsContent, export.fileName);
                    runOnUiThread(() -> {
                        showProgress(false);
                        shareIcsFile(uri, export.fileName);
                    });
                    return;
                }

                runOnUiThread(() -> {
                    showProgress(false);
                    pendingIcsContent = export.icsContent;
                    saveIcsLauncher.launch(export.fileName);
                });
            } catch (Exception e) {
                android.util.Log.e("CalendarExportActivity", "ICS export failed", e);
                postToast(R.string.plan_export_ics_error, Toast.LENGTH_LONG);
                showProgress(false);
            }
        });
    }

    private void writeIcsToUri(Uri uri) {
        String content = pendingIcsContent;
        pendingIcsContent = null;
        if (uri == null || content == null || content.isEmpty()) {
            return;
        }

        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) {
                throw new IllegalStateException("Output stream is null");
            }
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
            Toast.makeText(this, R.string.plan_export_ics_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("CalendarExportActivity", "Failed to save ICS", e);
            Toast.makeText(this, R.string.plan_export_ics_write_error, Toast.LENGTH_LONG).show();
        }
    }

    private void shareIcsFile(Uri uri, String fileName) {
        if (uri == null) {
            Toast.makeText(this, R.string.plan_export_ics_share_error, Toast.LENGTH_LONG).show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/calendar");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, fileName);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(
                    shareIntent,
                    getString(R.string.plan_export_ics_share_title)));
        } catch (Exception e) {
            android.util.Log.e("CalendarExportActivity", "No app to share ICS", e);
            Toast.makeText(this, R.string.plan_export_ics_share_error, Toast.LENGTH_LONG).show();
        }
    }

    private void runPdfExport(boolean share) {
        showProgress(true);
        executor.execute(() -> {
            try {
                PlanCalendarExportHelper.ExportResult semester = exportHelper.buildExport(
                        PlanCalendarExportHelper.ExportScope.SEMESTER);
                if (semester.events == null || semester.events.isEmpty()) {
                    postToast(R.string.calendar_export_pdf_no_events, Toast.LENGTH_SHORT);
                    showProgress(false);
                    return;
                }

                SemesterPdfExportHelper.PdfResult pdf = SemesterPdfExportHelper.build(
                        this,
                        semester.events);
                if (share) {
                    Uri uri = SemesterPdfExportHelper.writeShareCacheFile(
                            this,
                            pdf.content,
                            pdf.fileName);
                    runOnUiThread(() -> {
                        showProgress(false);
                        sharePdfFile(uri, pdf.fileName);
                    });
                    return;
                }

                runOnUiThread(() -> {
                    showProgress(false);
                    pendingPdfContent = pdf.content;
                    savePdfLauncher.launch(pdf.fileName);
                });
            } catch (Exception e) {
                android.util.Log.e("CalendarExportActivity", "PDF export failed", e);
                postToast(R.string.calendar_export_pdf_error, Toast.LENGTH_LONG);
                showProgress(false);
            }
        });
    }

    private void writePdfToUri(Uri uri) {
        byte[] content = pendingPdfContent;
        pendingPdfContent = null;
        if (uri == null || content == null || content.length == 0) {
            return;
        }

        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IllegalStateException("Output stream is null");
            }
            output.write(content);
            output.flush();
            Toast.makeText(this, R.string.calendar_export_pdf_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("CalendarExportActivity", "Failed to save PDF", e);
            Toast.makeText(this, R.string.calendar_export_pdf_write_error, Toast.LENGTH_LONG).show();
        }
    }

    private void sharePdfFile(Uri uri, String fileName) {
        if (uri == null) {
            Toast.makeText(this, R.string.calendar_export_pdf_share_error, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, fileName);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(
                    intent,
                    getString(R.string.calendar_export_pdf_share_title)));
        } catch (Exception e) {
            android.util.Log.e("CalendarExportActivity", "No app to share PDF", e);
            Toast.makeText(this, R.string.calendar_export_pdf_share_error, Toast.LENGTH_LONG).show();
        }
    }

    private void importToDeviceCalendar() {
        if (hasCalendarPermissions()) {
            refreshDeviceCalendars(CalendarAction.IMPORT);
            return;
        }
        pendingCalendarAction = CalendarAction.IMPORT;
        calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS);
    }

    private void clearDeviceCalendar() {
        if (hasCalendarPermissions()) {
            refreshDeviceCalendars(CalendarAction.CLEAR);
            return;
        }
        pendingCalendarAction = CalendarAction.CLEAR;
        calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS);
    }

    private boolean hasCalendarPermissions() {
        for (String permission : CALENDAR_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void refreshDeviceCalendars(CalendarAction action) {
        boolean showLoader = action != CalendarAction.NONE;
        if (showLoader) {
            showProgress(true);
        }

        executor.execute(() -> {
            List<PlanCalendarExportHelper.DeviceCalendarInfo> calendars = exportHelper.loadWritableCalendars();
            runOnUiThread(() -> {
                if (showLoader) {
                    showProgress(false);
                }

                applyAvailableCalendars(calendars);
                if (availableCalendars.isEmpty()) {
                    if (action != CalendarAction.NONE) {
                        Toast.makeText(this, R.string.calendar_export_no_device_calendars, Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                continueCalendarAction(action);
            });
        });
    }

    private void applyAvailableCalendars(List<PlanCalendarExportHelper.DeviceCalendarInfo> calendars) {
        availableCalendars.clear();
        if (calendars != null) {
            availableCalendars.addAll(calendars);
        }
    }

    private void continueCalendarAction(CalendarAction action) {
        if (action == null || action == CalendarAction.NONE) {
            return;
        }
        showDeviceCalendarPickerDialog(action);
    }

    private void showDeviceCalendarPickerDialog(CalendarAction action) {
        if (availableCalendars.isEmpty()) {
            Toast.makeText(this, R.string.calendar_export_no_device_calendars, Toast.LENGTH_LONG).show();
            return;
        }

        List<String> labels = new ArrayList<>();
        for (PlanCalendarExportHelper.DeviceCalendarInfo info : availableCalendars) {
            labels.add(info.toDisplayLabel(this));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(resolvePickerTitle(action))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= availableCalendars.size()) {
                        return;
                    }

                    PlanCalendarExportHelper.DeviceCalendarInfo pickedCalendar = availableCalendars.get(which);

                    if (action == CalendarAction.CLEAR) {
                        confirmClearSelectedCalendar(pickedCalendar);
                    } else {
                        importIntoSelectedCalendar(pickedCalendar);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int resolvePickerTitle(CalendarAction action) {
        if (action == CalendarAction.CLEAR) {
            return R.string.calendar_export_pick_calendar_clear;
        }
        return R.string.calendar_export_pick_calendar;
    }

    private void importIntoSelectedCalendar(PlanCalendarExportHelper.DeviceCalendarInfo calendarInfo) {
        if (calendarInfo == null) {
            return;
        }
        showProgress(true);
        executor.execute(() -> {
            try {
                int inserted = exportHelper.importIntoDeviceCalendar(calendarInfo, selectedScope);
                runOnUiThread(() -> {
                    showProgress(false);
                    if (inserted <= 0) {
                        Toast.makeText(this, R.string.plan_export_ics_no_events, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(
                            this,
                            getString(R.string.calendar_export_import_success, inserted),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                android.util.Log.e("CalendarExportActivity", "Calendar import failed", e);
                postToast(R.string.calendar_export_import_error, Toast.LENGTH_LONG);
                showProgress(false);
            }
        });
    }

    private void confirmClearSelectedCalendar(PlanCalendarExportHelper.DeviceCalendarInfo calendarInfo) {
        if (calendarInfo == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.calendar_export_clear_title)
                .setMessage(getString(
                        R.string.calendar_export_clear_message,
                        calendarInfo.toDisplayLabel(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.calendar_export_clear_action, (dialog, which) ->
                        clearImportedEventsFromCalendar(calendarInfo))
                .show();
    }

    private void clearImportedEventsFromCalendar(PlanCalendarExportHelper.DeviceCalendarInfo calendarInfo) {
        showProgress(true);
        executor.execute(() -> {
            int removed = exportHelper.clearImportedEventsFromCalendar(calendarInfo);
            runOnUiThread(() -> {
                showProgress(false);
                Toast.makeText(
                        this,
                        getString(R.string.calendar_export_clear_success, removed),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showProgress(boolean visible) {
        runOnUiThread(() -> {
            if (progress != null) {
                progress.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
            }
        });
    }

    private void postToast(int resId, int duration) {
        runOnUiThread(() -> Toast.makeText(this, resId, duration).show());
    }

    private enum CalendarAction {
        NONE,
        IMPORT,
        CLEAR
    }
}
