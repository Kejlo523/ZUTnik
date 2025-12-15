package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GradesActivity extends MzutBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    // Grades cache – valid for 7 days
    private static final long GRADES_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L; // 7 days
    private static final String GRADES_CACHE_PREFS_NAME = "grades_cache";

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private Spinner spinnerStudies;
    private Spinner spinnerSemesters;
    private RecyclerView listGrades;
    private ProgressBar gradesProgress;
    private TextView tvEmpty;

    // Summary tiles
    private TextView tvAverageValue;
    private TextView tvEctsValue;

    // Refresh button (icon)
    private View btnGradesRefresh;

    private GradesAdapter gradesAdapter;
    private final List<Grade> currentGrades = new ArrayList<>();

    private final List<Study> studies = new ArrayList<>();
    private final List<Semester> semesters = new ArrayList<>();

    private ArrayAdapter<String> studiesAdapter;
    private ArrayAdapter<String> semestersAdapter;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentSemestersFuture;
    private java.util.concurrent.Future<?> currentGradesFuture;
    private java.util.concurrent.Future<?> currentInitFuture;

    // Flag mirroring InfoActivity behavior
    private boolean studiesSpinnerInitialized = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_grades);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        spinnerStudies = findViewById(R.id.spinnerStudies);
        spinnerSemesters = findViewById(R.id.spinnerSemesters);
        listGrades = findViewById(R.id.listGrades);
        gradesProgress = findViewById(R.id.gradesProgress);
        tvEmpty = findViewById(R.id.tvEmpty);

        tvAverageValue = findViewById(R.id.tvAverageValue);
        tvEctsValue = findViewById(R.id.tvEctsValue);

        btnGradesRefresh = findViewById(R.id.btnGradesRefresh);

        // Toolbar / title
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.grades_title);
        }

        // Nav drawer (active screen: grades)
        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.GRADES);

        // RecyclerView
        listGrades.setLayoutManager(new LinearLayoutManager(this));
        gradesAdapter = new GradesAdapter(currentGrades);
        listGrades.setAdapter(gradesAdapter);

        // Semesters spinner has a fixed configuration
        setupSemestersSpinner();

        // Grades refresh button – ALWAYS hits the network, ignores cache TTL
        if (btnGradesRefresh != null) {
            btnGradesRefresh.setOnClickListener(v -> {
                Toast.makeText(
                        GradesActivity.this,
                        R.string.grades_refresh_toast,
                        Toast.LENGTH_SHORT).show();

                int pos = spinnerSemesters.getSelectedItemPosition();
                if (pos >= 0 && pos < semesters.size()) {
                    Semester selected = semesters.get(pos);
                    // Hard refresh – bypass cache, always request from network
                    reloadGrades(selected, true);
                } else {
                    // If for some reason there are no semesters – reload everything
                    reloadSemesters();
                }
            });
        }

        // Initial load (studies + semesters + grades)
        runInitialLoad();
    }

    // -----------------------
    // INITIAL LOAD
    // -----------------------
    private void runInitialLoad() {
        if (currentInitFuture != null) {
            currentInitFuture.cancel(true);
        }
        executeInitTask();
    }

    private void executeInitTask() {
        showLoading(true);
        currentInitFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;

            try {
                GradesRepository repo = new GradesRepository();
                result = repo.loadSemesters();
            } catch (Exception e) {
                error = e;
            }

            // Fallback: If network failed (result is null or empty), try disk cache
            if (result == null || result.isEmpty()) {
                MzutSession s = MzutSession.getInstance();
                List<Study> all = s.getStudies();
                int idx = s.getActiveStudyIndex();
                if (all != null && idx >= 0 && idx < all.size()) {
                    List<Semester> cached = loadSemestersFromCache(all.get(idx));
                    if (cached != null && !cached.isEmpty()) {
                        result = cached;
                        fromCache = true;
                        error = null; // Clear error
                    }
                }
            }

            final List<Semester> finalResult = result;
            final Exception finalError = error;
            final boolean finalFromCache = fromCache;

            handler.post(() -> {
                showLoading(false);

                if (finalError != null) {
                    String message = finalError.getMessage() != null ? finalError.getMessage() : "";
                    // Support suppression of "Unable to resolve host" toast
                    boolean isDnsError = message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(GradesActivity.this);

                    if (isOffline || isDnsError) {
                        android.util.Log.d("GradesActivity", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                GradesActivity.this,
                                getString(R.string.grades_error_initial_load, message),
                                Toast.LENGTH_LONG).show();
                    }
                    showEmptyState(true);
                    return;
                }

                // If loaded successfully from network (not cache), persist it
                if (!finalFromCache && finalResult != null && !finalResult.isEmpty()) {
                    MzutSession s = MzutSession.getInstance();
                    List<Study> all = s.getStudies();
                    int idx = s.getActiveStudyIndex();
                    if (all != null && idx >= 0 && idx < all.size()) {
                        saveSemestersToCache(all.get(idx), finalResult);
                    }
                }

                // 1) Update the list of semesters
                semesters.clear();
                if (finalResult != null) {
                    semesters.addAll(finalResult);
                }

                List<String> semNames = new ArrayList<>();
                for (Semester s : semesters) {
                    semNames.add(
                            getString(
                                    R.string.grades_semester_label,
                                    s.nrSemestru,
                                    s.rokAkademicki));
                }

                semestersAdapter.clear();
                semestersAdapter.addAll(semNames);
                semestersAdapter.notifyDataSetChanged();

                // 2) Studies spinner based on MzutSession
                setupStudiesSpinner();

                // 3) If we have semesters – select the last one and load grades (from cache if
                // fresh)
                if (!semesters.isEmpty()) {
                    int indexCurrent = semesters.size() - 1;
                    if (indexCurrent < 0) {
                        indexCurrent = 0;
                    }
                    spinnerSemesters.setSelection(indexCurrent);

                    Semester selected = semesters.get(indexCurrent);
                    reloadGrades(selected, false);
                } else {
                    showEmptyState(true);
                }
            });
        });
    }

    // -----------------------
    // SPINNER: STUDY
    // -----------------------
    private void setupStudiesSpinner() {
        MzutSession session = MzutSession.getInstance();
        List<Study> sessionStudies = session.getStudies();

        // No studies -> hide the spinner
        if (sessionStudies == null || sessionStudies.isEmpty()) {
            spinnerStudies.setVisibility(View.GONE);
            return;
        }

        spinnerStudies.setVisibility(View.VISIBLE);

        studies.clear();
        studies.addAll(sessionStudies);

        List<String> labels = new ArrayList<>();
        for (Study st : studies) {
            labels.add(st.toString()); // Label same as in InfoActivity
        }

        if (!studiesSpinnerInitialized) {
            // First time – create adapter and listener
            studiesAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.spinner_item_dark,
                    labels);
            studiesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            spinnerStudies.setAdapter(studiesAdapter);
            studiesSpinnerInitialized = true;

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) {
                activeIndex = 0;
            }
            spinnerStudies.setSelection(activeIndex);

            spinnerStudies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    MzutSession s = MzutSession.getInstance();
                    if (position == s.getActiveStudyIndex()) {
                        return; // Nothing changed
                    }
                    s.setActiveStudyIndex(position);
                    // Study changed => reload semesters (repository itself will use getStudies
                    // cache)
                    reloadSemesters();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } else {
            // Subsequent calls – refresh contents / selection
            studiesAdapter.clear();
            studiesAdapter.addAll(labels);
            studiesAdapter.notifyDataSetChanged();

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) {
                activeIndex = 0;
            }
            spinnerStudies.setSelection(activeIndex);
        }
    }

    // -----------------------
    // SPINNER: SEMESTER
    // -----------------------
    private void setupSemestersSpinner() {
        semestersAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item_dark,
                new ArrayList<>());
        semestersAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerSemesters.setAdapter(semestersAdapter);

        spinnerSemesters.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id) {
                if (position >= 0 && position < semesters.size()) {
                    Semester selected = semesters.get(position);
                    // Normal semester switch – use cache if it is still fresh
                    reloadGrades(selected, false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });
    }

    // -----------------------
    // LOADING SEMESTERS (for study change)
    // -----------------------
    private void reloadSemesters() {
        if (currentSemestersFuture != null) {
            currentSemestersFuture.cancel(true);
        }
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }

        executeLoadSemestersTask();
    }

    private void executeLoadSemestersTask() {
        showLoading(true);
        currentSemestersFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;

            try {
                GradesRepository repo = new GradesRepository();
                result = repo.loadSemesters();
            } catch (Exception e) {
                error = e;
            }

            if (result == null) {
                // Try to load from disk cache
                MzutSession s = MzutSession.getInstance();
                List<Study> all = s.getStudies();
                int idx = s.getActiveStudyIndex();
                if (all != null && idx >= 0 && idx < all.size()) {
                    result = loadSemestersFromCache(all.get(idx));
                    if (result != null && !result.isEmpty()) {
                        // Found in cache -> clear error, we are good offline
                        error = null;
                        fromCache = true;
                    }
                }
            }

            final List<Semester> finalResult = result;
            final Exception finalError = error;
            final boolean finalFromCache = fromCache;

            handler.post(() -> {
                showLoading(false);

                if (finalError != null) {
                    String message = finalError.getMessage() != null ? finalError.getMessage() : "";
                    Toast.makeText(
                            GradesActivity.this,
                            getString(R.string.grades_error_loading_semesters, message),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (finalResult == null || finalResult.isEmpty()) {
                    Toast.makeText(
                            GradesActivity.this,
                            R.string.grades_no_semesters_for_study,
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

                // If loaded successfully from network (not cache), persist it
                if (!finalFromCache) {
                    MzutSession s = MzutSession.getInstance();
                    List<Study> all = s.getStudies();
                    int idx = s.getActiveStudyIndex();
                    if (all != null && idx >= 0 && idx < all.size()) {
                        saveSemestersToCache(all.get(idx), finalResult);
                    }
                }

                semesters.clear();
                semesters.addAll(finalResult);

                // Build labels for the semester spinner
                List<String> semNames = new ArrayList<>();
                for (Semester s : semesters) {
                    semNames.add(
                            getString(
                                    R.string.grades_semester_label,
                                    s.nrSemestru,
                                    s.rokAkademicki));
                }

                semestersAdapter.clear();
                semestersAdapter.addAll(semNames);
                semestersAdapter.notifyDataSetChanged();

                // Refresh studies spinner from session (in case repository changed
                // studies/activeStudyIndex)
                setupStudiesSpinner();

                // By default select the last (most recent) semester
                int indexCurrent = semesters.size() - 1;
                if (indexCurrent < 0) {
                    indexCurrent = 0;
                }
                spinnerSemesters.setSelection(indexCurrent);

                // Immediately load grades (here: using cache if available)
                Semester selected = semesters.get(indexCurrent);
                reloadGrades(selected, false);
            });
        });
    }

    // -----------------------
    // LOADING GRADES + CACHE
    // -----------------------
    private void reloadGrades(Semester semester) {
        reloadGrades(semester, false);
    }

    /**
     * @param forceNetwork true -> REFRESH BUTTON – always hits the network (ignores
     *                     TTL)
     *                     false -> regular semester switching / initial load – uses
     *                     7-day cache
     */
    // -----------------------
    // GROUPING HELPER
    // -----------------------
    private List<Grade> groupGrades(List<Grade> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        // Map: SubjectName -> Grade (accumulated)
        // Since "subjectName" includes form (e.g. "Math (Lecture)"), distinct forms
        // will be separate entries.
        java.util.Map<String, Grade> map = new java.util.LinkedHashMap<>();

        for (Grade g : source) {
            String key = g.subjectName; // e.g. "Programowanie (Laboratorium)"
            if (key == null)
                key = "";

            if (map.containsKey(key)) {
                // Merge
                Grade existing = map.get(key);

                // 1. Add to history
                if (g.grade != null && !g.grade.trim().isEmpty()) {
                    existing.gradeHistory.add(g.grade);
                    // Update display grade to be a combination or just the latest?
                    // UI will mostly use gradeHistory now, but let's keep grade updated as "latest"
                    // or concatenated just in case
                    existing.grade = existing.grade + " / " + g.grade;
                }

                // 2. Update date (take the latest/current one)
                if (g.date != null && !g.date.isEmpty()) {
                    existing.date = g.date;
                }

            } else {
                // New entry
                Grade copy = new Grade();
                copy.subjectName = g.subjectName;
                copy.grade = g.grade;
                copy.weight = g.weight;
                copy.type = g.type;
                copy.teacher = g.teacher;
                copy.date = g.date;

                // Initialize history
                if (g.grade != null && !g.grade.trim().isEmpty()) {
                    copy.gradeHistory.add(g.grade);
                }

                map.put(key, copy);
            }
        }

        // Sort history for each grade (Ascending: 2.0 -> 3.0)
        for (Grade g : map.values()) {
            if (g.gradeHistory != null && g.gradeHistory.size() > 1) {
                java.util.Collections.sort(g.gradeHistory, (o1, o2) -> {
                    double v1 = parseGradeValue(o1);
                    double v2 = parseGradeValue(o2);
                    return Double.compare(v1, v2);
                });

                // Also update the display string to match the order?
                // Currently 'grade' field is concatenated during loop. Let's rebuild it from
                // sorted history for consistency.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < g.gradeHistory.size(); i++) {
                    if (i > 0)
                        sb.append(" / ");
                    sb.append(g.gradeHistory.get(i));
                }
                g.grade = sb.toString();
            }
        }

        return new ArrayList<>(map.values());
    }

    private double parseGradeValue(String s) {
        if (s == null)
            return 0.0;
        String p = s.trim().toLowerCase().replace(",", ".");
        if ("nk".equals(p) || "2".equals(p) || "2.0".equals(p) || "nzal".equals(p))
            return 2.0;
        if ("zal".equals(p) || "z".equals(p))
            return 3.0; // Treat Pass as > Fail
        try {
            return Double.parseDouble(p);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void reloadGrades(Semester semester, boolean forceNetwork) {
        if (semester == null) {
            return;
        }

        // 1) Normal mode (no force) – try cache first. If it is fresh -> do NOT hit the
        // network.
        if (!forceNetwork) {
            List<Grade> cached = loadGradesFromCache(semester, false);
            if (cached != null && !cached.isEmpty()) {
                currentGrades.clear();
                // Grouping from fresh cache
                currentGrades.addAll(groupGrades(cached));
                gradesAdapter.notifyDataSetChanged();
                showEmptyState(false);
                updateSummaryCards();
                // Fresh cache -> skip network request
                return;
            }
        }

        // 2) Force mode (button) or missing cache -> fetch from network in background
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }

        executeLoadGradesTask(semester, forceNetwork);
    }

    private void executeLoadGradesTask(Semester semester, boolean forceNetwork) {
        showLoading(true);
        currentGradesFuture = executor.submit(() -> {
            List<Grade> grades = null;
            Exception error = null;
            try {
                GradesRepository repo = new GradesRepository();
                grades = repo.loadGradesForSemester(semester);
            } catch (Exception e) {
                error = e;
            }

            final List<Grade> finalGrades = grades;
            final Exception finalError = error;

            handler.post(() -> {
                showLoading(false);

                if (finalError != null) {
                    // On error, try using cache even if it is expired
                    List<Grade> cached = loadGradesFromCache(semester, true);
                    if (cached != null && !cached.isEmpty()) {
                        currentGrades.clear();
                        // Grouping from cache fallback
                        currentGrades.addAll(groupGrades(cached));
                        gradesAdapter.notifyDataSetChanged();
                        showEmptyState(false);
                        updateSummaryCards();

                        int msgId = forceNetwork
                                ? R.string.grades_refresh_network_failed_using_cache
                                : R.string.grades_load_network_failed_using_cache;

                        Toast.makeText(
                                GradesActivity.this,
                                msgId,
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    String message = finalError.getMessage() != null ? finalError.getMessage() : "";
                    boolean isDnsError = message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(GradesActivity.this);

                    if (isOffline || isDnsError) {
                        android.util.Log.d("GradesActivity", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                GradesActivity.this,
                                getString(R.string.grades_error_loading_grades, message),
                                Toast.LENGTH_LONG).show();
                    }
                    showEmptyState(true);
                    return;
                }

                currentGrades.clear();
                if (finalGrades != null) {
                    // Grouping from network load
                    currentGrades.addAll(groupGrades(finalGrades));
                    // Save to cache – only on successful load (RAW data)
                    saveGradesToCache(semester, finalGrades);
                }
                gradesAdapter.notifyDataSetChanged();

                boolean isEmpty = currentGrades.isEmpty();
                showEmptyState(isEmpty);

                updateSummaryCards();
            });
        });
    }

    // -----------------------
    // SUMMARY: ECTS + AVERAGE
    // -----------------------
    private void updateSummaryCards() {
        double sumWeighted = 0.0;
        double sumWeights = 0.0;
        double sumEcts = 0.0;

        for (Grade g : currentGrades) {
            // ECTS points are stored in the weight field (double)
            double ects = g.weight;
            if (ects < 0) {
                ects = 0;
            }
            sumEcts += ects;

            // Grade value as string, e.g. "4.5", "5", "zal", "2.0"
            String raw = g.grade;
            if (raw == null) {
                continue;
            }

            raw = raw.trim();
            if (raw.isEmpty()) {
                continue;
            }

            String normalized = raw.replace(",", ".");

            double value;
            try {
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException nfe) {
                // e.g. "ZAL", "NZAL" – excluded from the average
                continue;
            }

            if (ects <= 0.0) {
                sumWeighted += value;
                sumWeights += 1.0;
            } else {
                sumWeighted += value * ects;
                sumWeights += ects;
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
                tvAverageValue.setText(R.string.grades_average_placeholder);
            }
        }

        if (tvEctsValue != null) {
            tvEctsValue.setText(String.valueOf((int) Math.round(sumEcts)));
        }
    }

    // -----------------------
    // CACHE: SAVE / LOAD GRADES
    // -----------------------
    private SharedPreferences getGradesCachePrefs() {
        return getSharedPreferences(GRADES_CACHE_PREFS_NAME, MODE_PRIVATE);
    }

    private String buildCacheKey(Semester semester) {
        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        String semId = semester != null ? semester.listaSemestrowId : null;
        if (semId == null) {
            semId = "no_sem";
        }
        return userId + "_" + semId;
    }

    private void saveGradesToCache(Semester semester, List<Grade> grades) {
        if (semester == null || grades == null) {
            return;
        }

        try {
            JSONArray arr = new JSONArray();
            for (Grade g : grades) {
                JSONObject o = new JSONObject();
                o.put("subjectName", g.subjectName);
                o.put("grade", g.grade);
                o.put("weight", g.weight);
                o.put("type", g.type);
                o.put("teacher", g.teacher);
                o.put("date", g.date);
                arr.put(o);
            }

            JSONObject wrapper = new JSONObject();
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put("grades", arr);

            String key = buildCacheKey(semester);
            getGradesCachePrefs()
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();
        } catch (JSONException e) {
            // Ignore – cache is optional
        }
    }

    /**
     * @param ignoreTtl if true – ignore 7-day TTL (used as fallback on network
     *                  error).
     */
    private List<Grade> loadGradesFromCache(Semester semester, boolean ignoreTtl) {
        if (semester == null) {
            return null;
        }

        String key = buildCacheKey(semester);
        String json = getGradesCachePrefs().getString(key, null);
        if (json == null) {
            return null;
        }

        try {
            JSONObject wrapper = new JSONObject(json);
            long ts = wrapper.optLong("timestamp", 0L);
            long now = System.currentTimeMillis();

            if (!ignoreTtl && ts > 0 && (now - ts) > GRADES_CACHE_TTL_MS) {
                // If offline, we can ignore TTL to show old data instead of error
                if (NetworkStatusHelper.isNetworkAvailable(this)) {
                    // Cache expired and we are online -> return null to force refresh
                    return null;
                }
            }

            JSONArray arr = wrapper.optJSONArray("grades");
            if (arr == null) {
                return null;
            }

            List<Grade> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Grade g = new Grade();
                g.subjectName = o.optString("subjectName", "");
                g.grade = o.optString("grade", "");
                g.weight = o.optDouble("weight", 0.0);
                g.type = o.optString("type", "");
                g.teacher = o.optString("teacher", "");
                g.date = o.optString("date", "");
                result.add(g);
            }

            return result;
        } catch (JSONException e) {
            return null;
        }
    }

    // -----------------------
    // CACHE: PERSIST SEMESTERS LIST
    // -----------------------
    private static final String PREFS_GRADES_SEMESTERS_CACHE = "grades_semesters_cache";

    private void saveSemestersToCache(Study study, List<Semester> list) {
        if (study == null || list == null || study.przynaleznoscId == null)
            return;

        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        if (userId == null)
            userId = "unknown";

        String key = userId + "_" + study.przynaleznoscId;

        try {
            JSONArray arr = new JSONArray();
            for (Semester sem : list) {
                JSONObject o = new JSONObject();
                o.put("id", sem.listaSemestrowId);
                o.put("nr", sem.nrSemestru);
                o.put("pora", sem.pora);
                o.put("rok", sem.rokAkademicki);
                o.put("stat", sem.status);
                arr.put(o);
            }

            JSONObject wrapper = new JSONObject();
            wrapper.put("ts", System.currentTimeMillis());
            wrapper.put("data", arr);

            getSharedPreferences(PREFS_GRADES_SEMESTERS_CACHE, MODE_PRIVATE)
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();

        } catch (JSONException ignored) {
        }
    }

    private List<Semester> loadSemestersFromCache(Study study) {
        if (study == null || study.przynaleznoscId == null)
            return null;

        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        if (userId == null)
            userId = "unknown";

        String key = userId + "_" + study.przynaleznoscId;
        SharedPreferences prefs = getSharedPreferences(PREFS_GRADES_SEMESTERS_CACHE, MODE_PRIVATE);

        String json = prefs.getString(key, null);
        if (json == null)
            return null;

        try {
            JSONObject wrapper = new JSONObject(json);
            // We can treat semester list cache as "long lived" or check TTL.
            // Since repo uses 7 days, we can match that or just use it as persistent
            // fallback.
            // Let's use same TTL logic.
            long ts = wrapper.optLong("ts", 0L);
            if ((System.currentTimeMillis() - ts) > GRADES_CACHE_TTL_MS) {
                if (NetworkStatusHelper.isNetworkAvailable(this)) {
                    return null;
                }
            }

            JSONArray arr = wrapper.optJSONArray("data");
            if (arr == null)
                return null;

            List<Semester> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Semester sem = new Semester();
                sem.listaSemestrowId = o.optString("id");
                sem.nrSemestru = o.optString("nr");
                sem.pora = o.optString("pora");
                sem.rokAkademicki = o.optString("rok");
                sem.status = o.optString("stat");
                list.add(sem);
            }
            return list;
        } catch (JSONException e) {
            return null;
        }
    }

    // -----------------------
    // UI HELPERS
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
