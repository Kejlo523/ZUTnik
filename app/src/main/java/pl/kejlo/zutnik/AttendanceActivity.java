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
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
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
        setContentView(R.layout.activity_attendance);
        ThemeManager.applySystemBars(this);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);

        Toolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        MainNavHelper.setup(
                this,
                drawerContentRoot,
                bottomNavigation,
                toolbar,
                MainNavHelper.Screen.ATTENDANCE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.attendance_title);
        }

        listSubjects = findViewById(R.id.listSubjects);
        tvAbsenceTotal = findViewById(R.id.tvAbsenceTotal);
        tvAttendanceSubtitle = findViewById(R.id.tvAttendanceSubtitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        attendanceProgress = findViewById(R.id.attendanceProgress);
        btnRefresh = findViewById(R.id.btnAttendanceRefresh);

        listSubjects.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AttendanceAdapter();
        listSubjects.setAdapter(adapter);

        repository = new AttendanceRepository(this);

        btnRefresh.setOnClickListener(v -> {
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
        for (Absence a : absenceList) {
            totalAbsences += a.absenceCount;
        }

        tvAbsenceTotal.setText(String.valueOf(totalAbsences));

        if (totalAbsences == 0) {
            tvAttendanceSubtitle.setText(R.string.attendance_no_absences);
        } else if (totalAbsences == 1) {
            tvAttendanceSubtitle.setText(R.string.attendance_one_absence);
        } else {
            tvAttendanceSubtitle.setText(getString(R.string.attendance_absences_count, totalAbsences));
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
                    if (absence.absenceCount > newHours) {
                        absence.absenceCount = newHours;
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
            TextView tvSubjectName, tvSubjectType, tvHours, tvAbsenceCount;
            ImageView btnMinus, btnPlus;
            View hoursContainer;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSubjectName = itemView.findViewById(R.id.tvSubjectName);
                tvSubjectType = itemView.findViewById(R.id.tvSubjectType);
                tvHours = itemView.findViewById(R.id.tvHours);
                tvAbsenceCount = itemView.findViewById(R.id.tvAbsenceCount);
                btnMinus = itemView.findViewById(R.id.btnMinus);
                btnPlus = itemView.findViewById(R.id.btnPlus);
                hoursContainer = itemView.findViewById(R.id.hoursContainer);
            }

            void bind(Absence absence) {
                tvSubjectName.setText(absence.subjectName);
                tvSubjectType.setText(absence.subjectType);

                if (absence.totalHours > 0) {
                    tvHours.setText(getString(R.string.attendance_hours_format, absence.totalHours));
                } else {
                    tvHours.setText(R.string.attendance_hours_tap_to_set);
                }

                tvAbsenceCount.setText(String.valueOf(absence.absenceCount));

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
                    if (absence.totalHours == 0 || absence.absenceCount < absence.totalHours) {
                        absence.absenceCount++;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                        notifyItemChanged(getBindingAdapterPosition());
                        updateSummary();
                    }
                });
            }
        }
    }
}
