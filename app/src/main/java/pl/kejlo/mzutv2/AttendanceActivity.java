package pl.kejlo.mzutv2;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
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

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class AttendanceActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private LinearLayout drawerContentRoot;
    private RecyclerView listSubjects;
    private TextView tvAbsenceTotal;
    private TextView tvAttendanceSubtitle;
    private TextView tvEmpty;
    private ProgressBar attendanceProgress;
    private ImageView btnRefresh;

    private AttendanceRepository repository;
    private List<Absence> absenceList = new ArrayList<>();
    private AttendanceAdapter adapter;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance);

        // EdgeToEdge insets
        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.attendance_title);
        }

        // Navigation
        drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, NavDrawerHelper.Screen.ATTENDANCE);

        // Views
        listSubjects = findViewById(R.id.listSubjects);
        tvAbsenceTotal = findViewById(R.id.tvAbsenceTotal);
        tvAttendanceSubtitle = findViewById(R.id.tvAttendanceSubtitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        attendanceProgress = findViewById(R.id.attendanceProgress);
        btnRefresh = findViewById(R.id.btnAttendanceRefresh);

        // Setup RecyclerView
        listSubjects.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AttendanceAdapter();
        listSubjects.setAdapter(adapter);

        // Repository
        repository = new AttendanceRepository(this);

        // Refresh button
        btnRefresh.setOnClickListener(v -> {
            Toast.makeText(this, R.string.attendance_refreshing, Toast.LENGTH_SHORT).show();
            loadData();
        });

        // Initial load
        loadData();
    }

    private void loadData() {
        showLoading(true);

        Executors.newSingleThreadExecutor().execute(() -> {
            List<Absence> data = repository.loadSubjectsWithAbsences();
            runOnUiThread(() -> {
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
        listSubjects.setVisibility(loading ? View.GONE : View.VISIBLE);
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

        new AlertDialog.Builder(this)
                .setTitle(R.string.attendance_edit_hours_title)
                .setMessage(getString(R.string.attendance_edit_hours_message, absence.subjectName))
                .setView(input)
                .setPositiveButton(R.string.dialog_add_edit_tile_btn_save, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    int newHours = 0;
                    try {
                        newHours = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        newHours = 0;
                    }
                    if (newHours < 0)
                        newHours = 0;
                    if (newHours > 999)
                        newHours = 999;

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

    // Adapter
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
            Absence absence = absenceList.get(position);
            holder.bind(absence);
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

                // Show hours with hint if not set
                if (absence.totalHours > 0) {
                    tvHours.setText(getString(R.string.attendance_hours_format, absence.totalHours));
                } else {
                    tvHours.setText(R.string.attendance_hours_tap_to_set);
                }

                tvAbsenceCount.setText(String.valueOf(absence.absenceCount));

                // Click hours to edit
                hoursContainer.setOnClickListener(v -> {
                    showEditHoursDialog(absence, getAdapterPosition());
                });

                btnMinus.setOnClickListener(v -> {
                    if (absence.absenceCount > 0) {
                        absence.absenceCount--;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                        notifyItemChanged(getAdapterPosition());
                        updateSummary();
                    }
                });

                btnPlus.setOnClickListener(v -> {
                    // Allow adding absences even if hours not set
                    if (absence.totalHours == 0 || absence.absenceCount < absence.totalHours) {
                        absence.absenceCount++;
                        repository.saveAbsence(absence.subjectKey, absence.absenceCount);
                        notifyItemChanged(getAdapterPosition());
                        updateSummary();
                    }
                });
            }
        }
    }
}
