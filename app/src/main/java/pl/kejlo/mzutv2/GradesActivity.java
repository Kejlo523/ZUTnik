package pl.kejlo.mzutv2;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;

public class GradesActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private Spinner spinnerStudies;
    private Spinner spinnerSemesters;
    private RecyclerView listGrades;
    private ProgressBar gradesProgress;
    private TextView tvEmpty;

    // nowe kafelki z podsumowaniem
    private TextView tvAverageValue;
    private TextView tvEctsValue;

    private GradesAdapter gradesAdapter;
    private final List<Grade> currentGrades = new ArrayList<>();

    private final List<Study> studies = new ArrayList<>();
    private final List<Semester> semesters = new ArrayList<>();

    private ArrayAdapter<Study> studiesAdapter;
    private ArrayAdapter<Semester> semestersAdapter;

    private LoadSemestersTask currentSemestersTask;
    private LoadGradesTask currentGradesTask;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grades);

        // --- init views ---
        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        spinnerStudies   = findViewById(R.id.spinnerStudies);
        spinnerSemesters = findViewById(R.id.spinnerSemesters);
        listGrades       = findViewById(R.id.listGrades);
        gradesProgress   = findViewById(R.id.gradesProgress);    // dodaj w XML jeśli nie masz
        tvEmpty          = findViewById(R.id.tvEmpty);           // dodaj w XML jeśli nie masz

        tvAverageValue = findViewById(R.id.tvAverageValue);
        tvEctsValue    = findViewById(R.id.tvEctsValue);

        // --- toolbar / tytuł ---
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Oceny");
        }

        // --- nav drawer (aktywny ekran: grades) ---
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "grades");

        // --- RecyclerView ---
        listGrades.setLayoutManager(new LinearLayoutManager(this));
        gradesAdapter = new GradesAdapter(currentGrades);
        listGrades.setAdapter(gradesAdapter);

        setupStudiesSpinner();
        setupSemestersSpinner();

        // załaduj kierunki z sesji i zainicjuj wybór
        loadStudiesFromSessionAndInit();
    }

    // -----------------------
    //   SPINNER: KIERUNEK
    // -----------------------
    private void setupStudiesSpinner() {
        studiesAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item_dark,
                studies
        );
        studiesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerStudies.setAdapter(studiesAdapter);

        spinnerStudies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // ustaw aktywny kierunek w sesji i przeładuj semestry
                MzutSession session = MzutSession.getInstance();
                if (position >= 0 && position < studies.size()) {
                    session.setActiveStudyIndex(position);
                    reloadSemesters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // nic
            }
        });
    }

    // -----------------------
    //   SPINNER: SEMESTR
    // -----------------------
    private void setupSemestersSpinner() {
        semestersAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item_dark,
                semesters
        );
        semestersAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerSemesters.setAdapter(semestersAdapter);

        spinnerSemesters.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < semesters.size()) {
                    Semester selected = semesters.get(position);
                    reloadGrades(selected);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // nic
            }
        });
    }

    // -----------------------
    //   ŁADOWANIE STUDIÓW
    // -----------------------
    private void loadStudiesFromSessionAndInit() {
        MzutSession session = MzutSession.getInstance();
        List<Study> sessionStudies = session.getStudies();

        if (sessionStudies == null || sessionStudies.isEmpty()) {
            Toast.makeText(this, "Brak danych o kierunkach. Zaloguj się ponownie.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        studies.clear();
        studies.addAll(sessionStudies);
        studiesAdapter.notifyDataSetChanged();

        int activeIndex = session.getActiveStudyIndex();
        if (activeIndex < 0 || activeIndex >= studies.size()) {
            activeIndex = 0;
            session.setActiveStudyIndex(activeIndex);
        }

        spinnerStudies.setSelection(activeIndex);
        // samo ustawienie selection odpali listener -> reloadSemesters()
    }

    // -----------------------
    //   ŁADOWANIE SEMESTRÓW
    // -----------------------
    private void reloadSemesters() {
        // anuluj stare taski, jeśli jeszcze lecą
        if (currentSemestersTask != null) {
            currentSemestersTask.cancel(true);
        }
        if (currentGradesTask != null) {
            currentGradesTask.cancel(true);
        }

        currentSemestersTask = new LoadSemestersTask();
        currentSemestersTask.execute();
    }

    private class LoadSemestersTask extends AsyncTask<Void, Void, List<Semester>> {

        private Exception error;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showLoading(true);
        }

        @Override
        protected List<Semester> doInBackground(Void... voids) {
            try {
                GradesRepository repo = new GradesRepository();
                return repo.loadSemesters();
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Semester> result) {
            super.onPostExecute(result);
            showLoading(false);

            if (error != null) {
                Toast.makeText(GradesActivity.this,
                        "Błąd pobierania semestrów: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (result == null || result.isEmpty()) {
                Toast.makeText(GradesActivity.this,
                        "Brak semestrów dla wybranego kierunku.",
                        Toast.LENGTH_SHORT).show();
                semesters.clear();
                semestersAdapter.notifyDataSetChanged();
                currentGrades.clear();
                gradesAdapter.notifyDataSetChanged();
                updateSummaryCards();
                showEmptyState(true);
                return;
            }

            semesters.clear();
            semesters.addAll(result);
            semestersAdapter.notifyDataSetChanged();

            // wybierz bieżący semestr (if isCurrent)
            int indexCurrent = 0;
            for (int i = 0; i < semesters.size(); i++) {
                if (semesters.get(i).isCurrent()) {
                    indexCurrent = i;
                    break;
                }
            }
            spinnerSemesters.setSelection(indexCurrent);
            // listener spinnera odpali reloadGrades(...)
        }
    }

    // -----------------------
    //   ŁADOWANIE OCEN
    // -----------------------
    private void reloadGrades(Semester semester) {
        if (semester == null) return;

        if (currentGradesTask != null) {
            currentGradesTask.cancel(true);
        }

        currentGradesTask = new LoadGradesTask(semester);
        currentGradesTask.execute();
    }

    private class LoadGradesTask extends AsyncTask<Void, Void, List<Grade>> {

        private final Semester semester;
        private Exception error;

        LoadGradesTask(Semester semester) {
            this.semester = semester;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showLoading(true);
        }

        @Override
        protected List<Grade> doInBackground(Void... voids) {
            try {
                GradesRepository repo = new GradesRepository();
                return repo.loadGradesForSemester(semester.getListySemestrowId());
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Grade> grades) {
            super.onPostExecute(grades);
            showLoading(false);

            if (error != null) {
                Toast.makeText(GradesActivity.this,
                        "Błąd pobierania ocen: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                showEmptyState(true);
                return;
            }

            currentGrades.clear();
            if (grades != null) {
                currentGrades.addAll(grades);
            }
            gradesAdapter.notifyDataSetChanged();

            boolean isEmpty = currentGrades.isEmpty();
            showEmptyState(isEmpty);

            updateSummaryCards();
        }
    }

    // -----------------------
    //   PODSUMOWANIE: ECTS + ŚREDNIA
    // -----------------------
    private void updateSummaryCards() {
        double sumWeighted = 0.0;
        double sumWeights  = 0.0;
        double sumEcts     = 0.0;

        for (Grade g : currentGrades) {
            int ects = g.getWeight();   // w repo: weight = ECTS
            sumEcts += ects;

            // próbujemy sparsować wartość oceny
            String raw = g.getGrade();
            if (raw == null) continue;

            raw = raw.trim().replace(",", "."); // np. 4,5 -> 4.5

            double value;
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException nfe) {
                // "ZAL", "NZAL" itp. – pomijamy w średniej
                continue;
            }

            if (ects <= 0) {
                // jeśli brak ECTS, licz jako zwykłą jednostkę
                sumWeighted += value;
                sumWeights  += 1.0;
            } else {
                sumWeighted += value * ects;
                sumWeights  += ects;
            }
        }

        double avg = 0.0;
        if (sumWeights > 0.0) {
            avg = sumWeighted / sumWeights;
        }

        // aktualizacja UI
        if (tvAverageValue != null) {
            if (sumWeights > 0.0) {
                tvAverageValue.setText(String.format("%.2f", avg));
            } else {
                tvAverageValue.setText("–");
            }
        }

        if (tvEctsValue != null) {
            tvEctsValue.setText(String.valueOf((int) sumEcts));
        }
    }

    // -----------------------
    //   HELPERY UI
    // -----------------------
    private void showLoading(boolean loading) {
        if (gradesProgress != null) {
            gradesProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        // opcjonalnie możesz przyciemnić listę/wyłączyć kliknięcia
    }

    private void showEmptyState(boolean empty) {
        if (tvEmpty != null) {
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        listGrades.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
