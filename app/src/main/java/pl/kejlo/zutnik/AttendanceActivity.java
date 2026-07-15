package pl.kejlo.zutnik;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.content.res.ColorStateList;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AttendanceActivity extends PhoneAwareActivity {

    private LinearLayout drawerContentRoot;
    private RecyclerView listSubjects;
    private TextView tvAbsenceTotal;
    private TextView tvAttendanceSubtitle;
    private TextView tvAttendancePercent;
    private TextView tvEmpty;
    private ProgressBar attendanceProgress;
    private ImageView btnRefresh;

    private AttendanceRepository repository;
    private final List<Absence> absenceList = new ArrayList<>();
    private AttendanceAdapter adapter;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> loadFuture;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        super.onCreate(savedInstanceState);

        ShellHostHelper.MountedContent shell = ShellHostHelper.mountContentLayout(
                this,
                R.layout.activity_attendance,
                MainNavHelper.Screen.ATTENDANCE);
        View content = shell.contentRoot;

        drawerContentRoot = content.findViewById(R.id.drawerContentRoot);

        listSubjects = content.findViewById(R.id.listSubjects);
        tvAbsenceTotal = content.findViewById(R.id.tvAbsenceTotal);
        tvAttendanceSubtitle = content.findViewById(R.id.tvAttendanceSubtitle);
        tvAttendancePercent = content.findViewById(R.id.tvAttendancePercent);
        tvEmpty = content.findViewById(R.id.tvEmpty);
        attendanceProgress = content.findViewById(R.id.attendanceProgress);
        btnRefresh = content.findViewById(R.id.btnAttendanceRefresh);

        listSubjects.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AttendanceAdapter();
        listSubjects.setAdapter(adapter);

        repository = new AttendanceRepository(this);

        btnRefresh.setOnClickListener(v -> {
            NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                    this,
                    NetworkRefreshPolicy.Module.PLAN,
                    NetworkRefreshPolicy.Mode.MANUAL,
                    "attendance_subjects",
                    0L);
            if (!decision.allowNetwork) {
                Toast.makeText(this, NetworkRefreshPolicy.describeForUser(this, decision), Toast.LENGTH_SHORT).show();
                return;
            }
            NetworkRefreshPolicy.recordAttempt(
                    this,
                    NetworkRefreshPolicy.Module.PLAN,
                    NetworkRefreshPolicy.Mode.MANUAL,
                    "attendance_subjects");
            Toast.makeText(this, R.string.attendance_refreshing, Toast.LENGTH_SHORT).show();
            loadData(true);
        });

        loadData(false);
    }

    @Override
    protected void onDestroy() {
        if (loadFuture != null) {
            loadFuture.cancel(true);
        }
        loadExecutor.shutdownNow();
        if (repository != null) {
            repository.close();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    private void loadData(boolean forceRefresh) {
        if (loadFuture != null) {
            loadFuture.cancel(true);
        }

        showLoading(true);

        loadFuture = loadExecutor.submit(() -> {
            List<Absence> data = repository.loadSubjectsWithAbsences(forceRefresh);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                absenceList.clear();
                absenceList.addAll(data);
                adapter.notifyDataSetChanged();
                updateSummary();
                showLoading(false);
                showEmptyState(absenceList.isEmpty());
            });
        });
    }

    private void updateSummary() {
        int totalAbsences = 0;
        int atRisk = 0;
        for (Absence a : absenceList) {
            totalAbsences += a.absenceCount;
            if (a.isBelowRequiredAttendance()) {
                atRisk++;
            }
        }

        tvAbsenceTotal.setText(String.valueOf(totalAbsences));
        double overall = repository.calculateOverallAttendance(absenceList);
        tvAttendancePercent.setText(getString(R.string.attendance_percent_value, overall));

        if (atRisk > 0) {
            tvAttendanceSubtitle.setText(getResources().getQuantityString(
                    R.plurals.attendance_risk_summary,
                    atRisk,
                    atRisk));
            tvAttendanceSubtitle.setTextColor(ThemeManager.resolveColor(this, R.attr.mzDanger));
        } else {
            tvAttendanceSubtitle.setText(R.string.attendance_assistant_ok_summary);
            tvAttendanceSubtitle.setTextColor(ThemeManager.resolveColor(this, R.attr.mzMuted));
        }
    }

    private void showLoading(boolean loading) {
        attendanceProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            listSubjects.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void showEmptyState(boolean empty) {
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        listSubjects.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showEditHoursDialog(Absence absence, int position) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(absence.totalHours));
        input.setSelectAllOnFocus(true);
        input.setPadding(48, 32, 48, 32);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.attendance_edit_hours_title)
                .setMessage(getString(R.string.attendance_edit_hours_message, absence.subjectName))
                .setView(input)
                .setPositiveButton(R.string.dialog_add_edit_tile_btn_save, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    int newHours;
                    try {
                        newHours = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        newHours = 0;
                    }
                    if (newHours < 0) {
                        newHours = 0;
                    }
                    if (newHours > 999) {
                        newHours = 999;
                    }

                    absence.totalHours = newHours;
                    int maximumAbsences = absence.getMaximumAbsenceCount();
                    if (absence.absenceCount > maximumAbsences) {
                        absence.absenceCount = maximumAbsences;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                    }
                    repository.saveHours(absence.subjectKey, newHours);
                    adapter.notifyItemChanged(position);
                    updateSummary();
                })
                .setNegativeButton(R.string.dialog_add_edit_tile_btn_cancel, null)
                .show();
    }

    private class AttendanceAdapter extends RecyclerView.Adapter<AttendanceAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.attendance_subject_row, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(absenceList.get(position));
        }

        @Override
        public int getItemCount() {
            return absenceList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvSubjectName, tvSubjectType, tvHours, tvAbsenceCount, tvAttendanceStatus;
            ImageView btnMinus, btnPlus;
            View hoursContainer;
            ProgressBar attendanceBar;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSubjectName = itemView.findViewById(R.id.tvSubjectName);
                tvSubjectType = itemView.findViewById(R.id.tvSubjectType);
                tvHours = itemView.findViewById(R.id.tvHours);
                tvAbsenceCount = itemView.findViewById(R.id.tvAbsenceCount);
                btnMinus = itemView.findViewById(R.id.btnMinus);
                btnPlus = itemView.findViewById(R.id.btnPlus);
                hoursContainer = itemView.findViewById(R.id.hoursContainer);
                attendanceBar = itemView.findViewById(R.id.attendanceBar);
                tvAttendanceStatus = itemView.findViewById(R.id.tvAttendanceStatus);
            }

            void bind(Absence absence) {
                tvSubjectName.setText(absence.subjectName);
                tvSubjectType.setText(GradesTextUtils.formatTypeDisplay(
                        AttendanceActivity.this,
                        absence.subjectType));

                if (absence.totalHours > 0) {
                    tvHours.setText(getString(R.string.attendance_hours_format, absence.totalHours));
                } else {
                    tvHours.setText(R.string.attendance_hours_tap_to_set);
                }

                tvAbsenceCount.setText(String.valueOf(absence.absenceCount));
                bindAssistant(absence);

                hoursContainer.setOnClickListener(v ->
                        showEditHoursDialog(absence, getBindingAdapterPosition()));

                btnMinus.setOnClickListener(v -> {
                    if (absence.absenceCount > 0) {
                        absence.absenceCount--;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                        notifyItemChanged(getBindingAdapterPosition());
                        updateSummary();
                    }
                });

                btnPlus.setOnClickListener(v -> {
                    if (absence.totalHours == 0
                            || absence.absenceCount < absence.getMaximumAbsenceCount()) {
                        absence.absenceCount++;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                        notifyItemChanged(getBindingAdapterPosition());
                        updateSummary();
                    }
                });
            }

            private void bindAssistant(Absence absence) {
                if (absence.totalHours <= 0) {
                    attendanceBar.setProgress(0);
                    attendanceBar.setProgressTintList(ColorStateList.valueOf(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzBorderStrong)));
                    tvAttendanceStatus.setText(R.string.attendance_assistant_set_hours);
                    tvAttendanceStatus.setTextColor(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzMuted));
                    return;
                }

                int percent = (int) Math.round(absence.getAttendancePercent());
                attendanceBar.setProgress(Math.max(0, Math.min(100, percent)));
                if (absence.isBelowRequiredAttendance()) {
                    attendanceBar.setProgressTintList(ColorStateList.valueOf(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzDanger)));
                    tvAttendanceStatus.setText(getString(
                            R.string.attendance_assistant_below_limit,
                            percent,
                            Absence.DEFAULT_REQUIRED_ATTENDANCE_PERCENT));
                    tvAttendanceStatus.setTextColor(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzDanger));
                } else if (absence.getRemainingSafeAbsenceCount() == 0) {
                    attendanceBar.setProgressTintList(ColorStateList.valueOf(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzAccent)));
                    tvAttendanceStatus.setText(getString(
                            R.string.attendance_assistant_at_limit,
                            percent));
                    tvAttendanceStatus.setTextColor(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzAccent));
                } else {
                    attendanceBar.setProgressTintList(ColorStateList.valueOf(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzSuccess)));
                    int remaining = absence.getRemainingSafeAbsenceCount();
                    tvAttendanceStatus.setText(getResources().getQuantityString(
                            R.plurals.attendance_assistant_remaining,
                            remaining,
                            percent,
                            remaining));
                    tvAttendanceStatus.setTextColor(
                            ThemeManager.resolveColor(AttendanceActivity.this, R.attr.mzMuted));
                }
            }
        }
    }
}
