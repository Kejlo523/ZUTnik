package pl.kejlo.mzutv2;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.MotionEvent;
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

    // kafelki z podsumowaniem
    private TextView tvAverageValue;
    private TextView tvEctsValue;

    private GradesAdapter gradesAdapter;
    private final List<Grade> currentGrades = new ArrayList<>();

    private final List<Study> studies = new ArrayList<>();
    private final List<Semester> semesters = new ArrayList<>();

    private ArrayAdapter<String> studiesAdapter;
    private ArrayAdapter<String> semestersAdapter;

    private LoadSemestersTask currentSemestersTask;
    private LoadGradesTask currentGradesTask;
    private LoadInitTask currentInitTask;

    // flaga jak w InfoActivity
    private boolean studiesSpinnerInitialized = false;

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
        gradesProgress   = findViewById(R.id.gradesProgress);
        tvEmpty          = findViewById(R.id.tvEmpty);

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

        // spinner semestrów ma stałą konfigurację
        setupSemestersSpinner();

        // 🔥 zamiast loadStudiesFromSessionAndInit – robimy jak w InfoActivity:
        // najpierw async load (studia + semestry), potem dopiero konfiguracja spinnerów
        runInitialLoad();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // tylko podglądamy gest, NIE blokujemy eventu
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    // -----------------------
    //   PIERWSZE ŁADOWANIE (jak LoadInfoTask w InfoActivity)
    // -----------------------
    private void runInitialLoad() {
        if (currentInitTask != null) {
            currentInitTask.cancel(true);
        }
        currentInitTask = new LoadInitTask();
        currentInitTask.execute();
    }

    private class LoadInitTask extends AsyncTask<Void, Void, List<Semester>> {
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
                // repo.loadSemesters():
                // - jeśli nie ma kierunków, woła loadStudies()
                // - pobiera semestry dla aktywnego kierunku
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
                Toast.makeText(
                        GradesActivity.this,
                        "Błąd ładowania danych: " + error.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
                showEmptyState(true);
                return;
            }

            // 1) zaktualizuj listę semestrów
            semesters.clear();
            if (result != null) {
                semesters.addAll(result);
            }

            List<String> semNames = new ArrayList<>();
            for (Semester s : semesters) {
                semNames.add("Semestr " + s.nrSemestru + " (" + s.rokAkademicki + ")");
            }

            semestersAdapter.clear();
            semestersAdapter.addAll(semNames);
            semestersAdapter.notifyDataSetChanged();

            // 2) ustaw spinner kierunków na podstawie MzutSession (jak w InfoActivity)
            setupStudiesSpinner();

            // 3) jeśli mamy semestry – wybierz ostatni i od razu załaduj oceny
            if (!semesters.isEmpty()) {
                int indexCurrent = semesters.size() - 1;
                if (indexCurrent < 0) indexCurrent = 0;
                spinnerSemesters.setSelection(indexCurrent);

                Semester selected = semesters.get(indexCurrent);
                reloadGrades(selected);
            } else {
                showEmptyState(true);
            }
        }
    }

    // -----------------------
    //   SPINNER: KIERUNEK – jak w InfoActivity
    // -----------------------
    private void setupStudiesSpinner() {
        MzutSession session = MzutSession.getInstance();
        List<Study> sessionStudies = session.getStudies();

        // brak kierunków -> ukryj spinner
        if (sessionStudies == null || sessionStudies.isEmpty()) {
            spinnerStudies.setVisibility(View.GONE);
            return;
        }

        spinnerStudies.setVisibility(View.VISIBLE);

        studies.clear();
        studies.addAll(sessionStudies);

        List<String> labels = new ArrayList<>();
        for (Study st : studies) {
            labels.add(st.toString()); // label tak jak w InfoActivity
        }

        if (!studiesSpinnerInitialized) {
            // pierwszy raz – tworzymy adapter i listener
            studiesAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.spinner_item_dark,
                    labels
            );
            studiesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            spinnerStudies.setAdapter(studiesAdapter);
            studiesSpinnerInitialized = true;

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) activeIndex = 0;
            spinnerStudies.setSelection(activeIndex);

            spinnerStudies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    MzutSession s = MzutSession.getInstance();
                    if (position == s.getActiveStudyIndex()) {
                        return; // nic się nie zmieniło
                    }
                    s.setActiveStudyIndex(position);
                    // jak w InfoActivity: zmiana kierunku => przeładuj dane,
                    // u nas: przeładuj semestry + później oceny
                    reloadSemesters();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
        } else {
            // kolejne wywołania – tylko odświeżamy zawartość / selection
            studiesAdapter.clear();
            studiesAdapter.addAll(labels);
            studiesAdapter.notifyDataSetChanged();

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) activeIndex = 0;
            spinnerStudies.setSelection(activeIndex);
        }
    }

    // -----------------------
    //   SPINNER: SEMESTR (logika z Twojego kodu)
    // -----------------------
    private void setupSemestersSpinner() {
        semestersAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item_dark,
                new ArrayList<String>()
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
    //   ŁADOWANIE SEMESTRÓW (dla zmiany kierunku)
    // -----------------------
    private void reloadSemesters() {
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
                semestersAdapter.clear();
                semestersAdapter.notifyDataSetChanged();

                currentGrades.clear();
                gradesAdapter.notifyDataSetChanged();
                updateSummaryCards();
                showEmptyState(true);
                return;
            }

            semesters.clear();
            semesters.addAll(result);

            // budujemy labelki do spinnera
            List<String> semNames = new ArrayList<>();
            for (Semester s : semesters) {
                semNames.add("Semestr " + s.nrSemestru + " (" + s.rokAkademicki + ")");
            }

            semestersAdapter.clear();
            semestersAdapter.addAll(semNames);
            semestersAdapter.notifyDataSetChanged();

            // odśwież spinner kierunków z sesji (gdyby repo zmieniło studies/activeStudyIndex)
            setupStudiesSpinner();

            // domyślnie wybierz ostatni (najświeższy) semestr
            int indexCurrent = semesters.size() - 1;
            if (indexCurrent < 0) indexCurrent = 0;
            spinnerSemesters.setSelection(indexCurrent);

            // i od razu załaduj oceny
            Semester selected = semesters.get(indexCurrent);
            reloadGrades(selected);
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
                // w oryginalnym repo z ZIP-a jest:
                //    List<Grade> loadGradesForSemester(Semester semester)
                // więc przekazujemy cały obiekt, nie String
                return repo.loadGradesForSemester(semester);
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
            // ECTS trzymasz w polu weight (double)
            double ects = g.weight;
            if (ects < 0) ects = 0;
            sumEcts += ects;

            // wartość oceny w stringu, np. "4.5", "5", "zal", "2.0"
            String raw = g.grade;
            if (raw == null) continue;

            raw = raw.trim();
            if (raw.isEmpty()) continue;

            String normalized = raw.replace(",", ".");

            double value;
            try {
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException nfe) {
                // np. "ZAL", "NZAL" – pomijamy w średniej
                continue;
            }

            if (ects <= 0.0) {
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

        if (tvAverageValue != null) {
            if (sumWeights > 0.0) {
                tvAverageValue.setText(String.format("%.2f", avg));
            } else {
                tvAverageValue.setText("–");
            }
        }

        if (tvEctsValue != null) {
            tvEctsValue.setText(String.valueOf((int) Math.round(sumEcts)));
        }
    }

    // -----------------------
    //   HELPERY UI
    // -----------------------
    private void showLoading(boolean loading) {
        if (gradesProgress != null) {
            gradesProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private void showEmptyState(boolean empty) {
        if (tvEmpty != null) {
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (listGrades != null) {
            listGrades.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }
}
