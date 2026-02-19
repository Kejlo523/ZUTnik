package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GradesActivity extends MzutBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    // Grades cache - valid for 7 days
    private static final long GRADES_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L; // 7 days
    private static final String GRADES_CACHE_PREFS_NAME = "grades_cache";
    private static final String KEY_GRADES_GROUPING = "grades_grouping_enabled";
    private static final String KEY_TOTAL_ECTS_CACHE_PREFIX = "total_ects_";

    private Spinner spinnerStudies;
    private Spinner spinnerSemesters;
    private RecyclerView listGrades;
    private ProgressBar gradesProgress;
    private TextView tvEmpty;

    // Summary tiles
    private TextView tvAverageValue;
    private TextView tvEctsValue;
    private TextView tvEctsTotalValue;

    private GradesAdapter flatAdapter;
    private GroupedGradesAdapter groupedAdapter;
    private boolean groupingEnabled = true;
    private final List<Grade> currentGradesRaw = new ArrayList<>();

    private final List<Study> studies = new ArrayList<>();
    private final List<Semester> semesters = new ArrayList<>();

    private ArrayAdapter<String> studiesAdapter;
    private ArrayAdapter<String> semestersAdapter;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentSemestersFuture;
    private java.util.concurrent.Future<?> currentGradesFuture;
    private java.util.concurrent.Future<?> currentInitFuture;
    private java.util.concurrent.Future<?> currentTotalEctsFuture;

    // Flag mirroring InfoActivity behavior
    private boolean studiesSpinnerInitialized = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_grades);
        ThemeManager.applySystemBars(this);

        View drawerContentRoot = findViewById(R.id.drawerContentRoot);
        DrawerLayout drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        Toolbar toolbar = findViewById(R.id.toolbar);

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
        tvEctsTotalValue = findViewById(R.id.tvEctsTotalValue);

        View btnGradesRefresh = findViewById(R.id.btnGradesRefresh);

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
        groupingEnabled = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(KEY_GRADES_GROUPING, true);
        flatAdapter = new GradesAdapter(currentGradesRaw);
        groupedAdapter = new GroupedGradesAdapter();
        listGrades.setAdapter(groupingEnabled ? groupedAdapter : flatAdapter);

        // Semesters spinner has a fixed configuration
        setupSemestersSpinner();

        // Grades refresh button - ALWAYS hits the network, ignores cache TTL
        if (btnGradesRefresh != null) {
            btnGradesRefresh.setOnClickListener(v -> {
                Toast.makeText(
                        GradesActivity.this,
                        R.string.grades_refresh_toast,
                        Toast.LENGTH_SHORT).show();

                int pos = spinnerSemesters.getSelectedItemPosition();
                if (pos >= 0 && pos < semesters.size()) {
                    Semester selected = semesters.get(pos);
                    // Hard refresh - bypass cache, always request from network
                    reloadGrades(selected, true);
                } else {
                    // If for some reason there are no semesters - reload everything
                    reloadSemesters();
                }
            });
        }

        // Initial load (studies + semesters + grades)
        runInitialLoad();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.grades_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_toggle_grouping);
        if (item != null) {
            item.setChecked(groupingEnabled);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_grouping) {
            groupingEnabled = !groupingEnabled;
            getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_GRADES_GROUPING, groupingEnabled)
                    .apply();
            applyGradesView();
            invalidateOptionsMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Initial load
    private void runInitialLoad() {
        if (currentInitFuture != null) {
            currentInitFuture.cancel(true);
        }
        if (currentTotalEctsFuture != null) {
            currentTotalEctsFuture.cancel(true);
        }
        executeInitTask();
    }

    private void executeInitTask() {
        showLoading(true);
        currentInitFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;

            Study activeStudyForCache = getActiveStudySnapshot();
            if (activeStudyForCache != null) {
                List<Semester> cached = loadSemestersFromCache(activeStudyForCache);
                if (cached != null && !cached.isEmpty()) {
                    result = cached;
                    fromCache = true;
                }
            }

            if (result == null) {
                try {
                    GradesRepository repo = new GradesRepository();
                    result = repo.loadSemesters();
                } catch (Exception e) {
                    error = e;
                }
            }

            // Fallback: If network failed, try disk cache
            if (result == null) {
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
                refreshTotalEctsForActiveStudyAsync();

                // 3) If we have semesters - select the last one and load grades (from cache if
                // fresh)
                if (!semesters.isEmpty()) {
                    int indexCurrent = semesters.size() - 1;
                    spinnerSemesters.setSelection(indexCurrent);

                    Semester selected = semesters.get(indexCurrent);
                    reloadGrades(selected, false);
                } else {
                    showEmptyState(true);
                }
            });
        });
    }

    // Study spinner
    private void setupStudiesSpinner() {
        MzutSession session = MzutSession.getInstance();
        List<Study> sessionStudies = session.getStudies();

        // No studies -> hide the spinner
        if (sessionStudies == null || sessionStudies.isEmpty()) {
            spinnerStudies.setVisibility(View.GONE);
            setTotalEctsValue(0.0);
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
            // First time - create adapter and listener
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
                    s.saveToPreferences(GradesActivity.this);
                    setTotalEctsValue(0.0);
                    // Study changed => reload semesters (repository itself will use getStudies
                    // cache)
                    reloadSemesters();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } else {
            // Subsequent calls - refresh contents / selection
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

    // Semester spinner
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
                    // Normal semester switch - use cache if it is still fresh
                    reloadGrades(selected, false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });
    }

    // Loading semesters
    private void reloadSemesters() {
        if (currentSemestersFuture != null) {
            currentSemestersFuture.cancel(true);
        }
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }
        if (currentTotalEctsFuture != null) {
            currentTotalEctsFuture.cancel(true);
        }

        executeLoadSemestersTask();
    }

    private void executeLoadSemestersTask() {
        showLoading(true);
        currentSemestersFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;

            Study activeStudyForCache = getActiveStudySnapshot();
            if (activeStudyForCache != null) {
                List<Semester> cached = loadSemestersFromCache(activeStudyForCache);
                if (cached != null && !cached.isEmpty()) {
                    result = cached;
                    fromCache = true;
                }
            }

            if (result == null) {
                try {
                    GradesRepository repo = new GradesRepository();
                    result = repo.loadSemesters();
                } catch (Exception e) {
                    error = e;
                }
            }

            if (result == null) {
                // Try to load from disk cache
                MzutSession s = MzutSession.getInstance();
                List<Study> all = s.getStudies();
                int idx = s.getActiveStudyIndex();
                if (all != null && idx >= 0 && idx < all.size()) {
                    result = loadSemestersFromCache(all.get(idx));
                    if (result != null) {
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

                    currentGradesRaw.clear();
                    applyGradesView();
                    updateSummaryCards();
                    refreshTotalEctsForActiveStudyAsync();
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
                refreshTotalEctsForActiveStudyAsync();

                // By default select the last (most recent) semester
                int indexCurrent = semesters.size() - 1;
                spinnerSemesters.setSelection(indexCurrent);

                // Immediately load grades (here: using cache if available)
                Semester selected = semesters.get(indexCurrent);
                reloadGrades(selected, false);
            });
        });
    }

    // Loading grades
    // Grade grouping for expandable subject view
    private List<GroupedGradesAdapter.GradeGroup> buildGradeGroups(List<Grade> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, GroupedGradesAdapter.GradeGroup> map = new LinkedHashMap<>();

        for (Grade g : source) {
            String subject = extractBaseSubject(g.subjectName);
            if (subject.trim().isEmpty()) {
                continue;
            }

            GroupedGradesAdapter.GradeGroup group = map.get(subject);
            if (group == null) {
                group = new GroupedGradesAdapter.GradeGroup(subject);
                map.put(subject, group);
            }

            if (isFinalGrade(g)) {
                if (group.finalGrade == null) {
                    group.finalGrade = g;
                } else {
                    group.others.add(g);
                }
            } else {
                group.others.add(g);
            }
        }

        List<GroupedGradesAdapter.GradeGroup> result = new ArrayList<>();
        for (GroupedGradesAdapter.GradeGroup g : map.values()) {
            boolean hasOthers = g.others != null && !g.others.isEmpty();
            boolean hasFinal = g.finalGrade != null;

            // If only final grade exists and nothing else -> hide this subject
            if (!hasOthers && hasFinal) {
                continue;
            }

            if (!hasFinal && hasOthers) {
                g.finalMissing = true;
            }

            if (hasOthers) {
                result.add(g);
            }
        }

        return result;
    }

    private void applyGradesView() {
        if (groupingEnabled) {
            List<GroupedGradesAdapter.GradeGroup> groups = buildGradeGroups(currentGradesRaw);
            groupedAdapter.setGroups(groups);
            if (listGrades.getAdapter() != groupedAdapter) {
                listGrades.setAdapter(groupedAdapter);
            }
            showEmptyState(groups.isEmpty());
        } else {
            if (listGrades.getAdapter() != flatAdapter) {
                listGrades.setAdapter(flatAdapter);
            }
            int itemCount = flatAdapter.getItemCount();
            if (itemCount > 0) {
                flatAdapter.notifyItemRangeChanged(0, itemCount);
            }
            showEmptyState(currentGradesRaw.isEmpty());
        }
    }

    private boolean isFinalGrade(Grade g) {
        if (g == null) {
            return false;
        }
        String type = normalizeKey(g.type);
        if (type.contains("ocena koncowa")
                || type.contains("koncowa")
                || type.contains("final")
                || type.contains("abschluss")) {
            return true;
        }
        if (type.isEmpty()) {
            String subject = normalizeKey(g.subjectName);
            return subject.contains("ocena koncowa")
                    || subject.contains("koncowa")
                    || subject.contains("final")
                    || subject.contains("abschluss");
        }
        return false;
    }

    private String extractBaseSubject(String label) {
        if (label == null) {
            return "";
        }
        String name = label.trim();
        int parenIdx = name.lastIndexOf(" (");
        if (parenIdx > 0 && name.endsWith(")")) {
            name = name.substring(0, parenIdx);
        }
        return name.trim();
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String lower = repairMojibake(value).trim().toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
    private static String repairMojibake(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\u00C4\u2026", "\u0105")
                .replace("\u00C4\u2021", "\u0107")
                .replace("\u00C4\u2122", "\u0119")
                .replace("\u00C5\u201A", "\u0142")
                .replace("\u00C5\u201E", "\u0144")
                .replace("\u00C3\u00B3", "\u00F3")
                .replace("\u00C5\u203A", "\u015B")
                .replace("\u00C5\u00BC", "\u017C")
                .replace("\u00C5\u00BA", "\u017A")
                .replace("\u00C4\u201E", "\u0104")
                .replace("\u00C4\u2020", "\u0106")
                .replace("\u00C4\u02DC", "\u0118")
                .replace("\u00C5\u0081", "\u0141")
                .replace("\u00C5\u0192", "\u0143")
                .replace("\u00C3\u201C", "\u00D3")
                .replace("\u00C5\u0160", "\u015A")
                .replace("\u00C5\u00BB", "\u017B")
                .replace("\u00C5\u00B9", "\u0179")
                .replace("\u0139\u201A", "\u0142")
                .replace("\u0139\u201E", "\u0144");
    }

    private void reloadGrades(Semester semester, boolean forceNetwork) {
        if (semester == null) {
            return;
        }

        // 1) Normal mode (no force) - try cache first. If it is fresh -> do NOT hit the
        // network.
        if (!forceNetwork) {
            List<Grade> cached = loadGradesFromCache(semester, false);
            if (cached != null) {
                currentGradesRaw.clear();
                currentGradesRaw.addAll(cached);
                applyGradesView();
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
                    if (cached != null) {
                        currentGradesRaw.clear();
                        currentGradesRaw.addAll(cached);
                        applyGradesView();
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
                currentGradesRaw.clear();
                if (finalGrades != null) {
                    currentGradesRaw.addAll(finalGrades);
                    // Save to cache ??? only on successful load (RAW data)
                    saveGradesToCache(semester, finalGrades);
                }
                applyGradesView();
                updateSummaryCards();
            });
        });
    }

    // Summary calculations
    private void updateSummaryCards() {
        double sumWeighted = 0.0;
        double sumWeights = 0.0;
        double sumEcts = calculateEctsForGrades(currentGradesRaw);
        boolean usedFinal = false;

        for (Grade g : currentGradesRaw) {
            if (!isFinalGrade(g)) {
                continue;
            }
            usedFinal = true;
            // ECTS points are stored in the weight field (double)
            double ects = g.weight;
            if (ects < 0) {
                ects = 0;
            }

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
                // e.g. "ZAL", "NZAL" - excluded from the average
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

        if (!usedFinal) {
            sumWeighted = 0.0;
            sumWeights = 0.0;
            for (Grade g : currentGradesRaw) {
                double ects = g.weight;
                if (ects < 0) {
                    ects = 0;
                }

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
        }

        double avg = 0.0;
        if (sumWeights > 0.0) {
            avg = sumWeighted / sumWeights;
        }

        if (tvAverageValue != null) {
            if (sumWeights > 0.0) {
                tvAverageValue.setText(String.format(Locale.getDefault(), "%.2f", avg));
            } else {
                tvAverageValue.setText(R.string.grades_average_placeholder);
            }
        }

        if (tvEctsValue != null) {
            tvEctsValue.setText(String.valueOf((int) Math.round(sumEcts)));
        }
    }

    private double calculateEctsForGrades(List<Grade> grades) {
        if (grades == null || grades.isEmpty()) {
            return 0.0;
        }

        double sumFinal = 0.0;
        boolean hasFinal = false;
        for (Grade g : grades) {
            if (!isFinalGrade(g)) {
                continue;
            }
            hasFinal = true;
            if (g.weight > 0) {
                sumFinal += g.weight;
            }
        }
        if (hasFinal) {
            return sumFinal;
        }

        double sumAll = 0.0;
        for (Grade g : grades) {
            if (g.weight > 0) {
                sumAll += g.weight;
            }
        }
        return sumAll;
    }

    private void setTotalEctsValue(double totalEcts) {
        if (tvEctsTotalValue != null) {
            int rounded = (int) Math.round(Math.max(0.0, totalEcts));
            tvEctsTotalValue.setText(String.valueOf(rounded));
        }
    }

    private String buildTotalEctsCacheKey(Study study) {
        if (study == null || study.przynaleznoscId == null) {
            return null;
        }
        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        return KEY_TOTAL_ECTS_CACHE_PREFIX + userId + "_" + study.przynaleznoscId;
    }

    private String buildSemestersFingerprint(List<Semester> semList) {
        if (semList == null || semList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Semester sem : semList) {
            if (sem == null || sem.listaSemestrowId == null || sem.listaSemestrowId.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(sem.listaSemestrowId);
        }
        return sb.toString();
    }

    private Double loadTotalEctsFromCache(Study study, List<Semester> semList) {
        String key = buildTotalEctsCacheKey(study);
        if (key == null) {
            return null;
        }
        String raw = getGradesCachePrefs().getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        try {
            JSONObject wrapper = new JSONObject(raw);
            long ts = wrapper.optLong("timestamp", 0L);
            if (ts <= 0L) {
                return null;
            }

            if ((System.currentTimeMillis() - ts) > GRADES_CACHE_TTL_MS
                    && NetworkStatusHelper.isNetworkAvailable(this)) {
                return null;
            }

            String cachedFingerprint = wrapper.optString("semesters", "");
            String currentFingerprint = buildSemestersFingerprint(semList);
            if (!currentFingerprint.equals(cachedFingerprint)) {
                return null;
            }

            if (!wrapper.has("total")) {
                return null;
            }
            double total = wrapper.optDouble("total", Double.NaN);
            if (Double.isNaN(total) || total < 0.0) {
                return null;
            }
            return total;
        } catch (JSONException e) {
            return null;
        }
    }

    private void saveTotalEctsToCache(Study study, List<Semester> semList, double totalEcts) {
        String key = buildTotalEctsCacheKey(study);
        if (key == null) {
            return;
        }

        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put("semesters", buildSemestersFingerprint(semList));
            wrapper.put("total", Math.max(0.0, totalEcts));
            getGradesCachePrefs()
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private void refreshTotalEctsForActiveStudyAsync() {
        final Study activeStudy = getActiveStudySnapshot();
        final List<Semester> semSnapshot = new ArrayList<>(semesters);

        if (activeStudy == null || activeStudy.przynaleznoscId == null || semSnapshot.isEmpty()) {
            setTotalEctsValue(0.0);
            return;
        }

        final String expectedStudyId = activeStudy.przynaleznoscId;
        final Double cachedTotal = loadTotalEctsFromCache(activeStudy, semSnapshot);
        if (cachedTotal != null) {
            setTotalEctsValue(cachedTotal);
        } else {
            setTotalEctsValue(0.0);
        }

        if (currentTotalEctsFuture != null) {
            currentTotalEctsFuture.cancel(true);
        }

        currentTotalEctsFuture = executor.submit(() -> {
            double total = 0.0;
            boolean complete = true;
            GradesRepository repo = new GradesRepository();

            for (Semester sem : semSnapshot) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (sem == null || sem.listaSemestrowId == null || sem.listaSemestrowId.isEmpty()) {
                    continue;
                }

                List<Grade> semGrades = loadGradesFromCache(sem, false);
                if (semGrades == null) {
                    try {
                        semGrades = repo.loadGradesForSemester(sem);
                        if (semGrades != null) {
                            saveGradesToCache(sem, semGrades);
                        }
                    } catch (Exception e) {
                        semGrades = loadGradesFromCache(sem, true);
                    }
                }

                if (semGrades == null) {
                    complete = false;
                    continue;
                }
                total += calculateEctsForGrades(semGrades);
            }

            final double finalTotal = total;
            final boolean finalComplete = complete;
            handler.post(() -> {
                Study currentStudy = getActiveStudySnapshot();
                String currentStudyId = currentStudy != null ? currentStudy.przynaleznoscId : null;
                if (currentStudyId == null || !currentStudyId.equals(expectedStudyId)) {
                    return;
                }

                if (!finalComplete && cachedTotal != null) {
                    return;
                }

                setTotalEctsValue(finalTotal);
                if (finalComplete) {
                    saveTotalEctsToCache(activeStudy, semSnapshot, finalTotal);
                }
            });
        });
    }

    // Grades cache
    private SharedPreferences getGradesCachePrefs() {
        return getSharedPreferences(GRADES_CACHE_PREFS_NAME, MODE_PRIVATE);
    }

    private Study getActiveStudySnapshot() {
        MzutSession session = MzutSession.getInstance();
        List<Study> all = session.getStudies();
        int idx = session.getActiveStudyIndex();
        if (all == null || idx < 0 || idx >= all.size()) {
            return null;
        }
        return all.get(idx);
    }

    private String buildCacheKey(Semester semester) {
        MzutSession s = MzutSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        Study activeStudy = getActiveStudySnapshot();
        String studyId = (activeStudy != null && activeStudy.przynaleznoscId != null)
                ? activeStudy.przynaleznoscId
                : "no_study";
        String semId = semester != null ? semester.listaSemestrowId : null;
        if (semId == null) {
            semId = "no_sem";
        }
        return userId + "_" + studyId + "_" + semId;
    }

    private String buildLegacyCacheKey(Semester semester) {
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
                    .remove(buildLegacyCacheKey(semester))
                    .apply();
        } catch (JSONException e) {
            // Ignore - cache is optional
        }
    }

    /**
     * @param ignoreTtl if true - ignore 7-day TTL (used as fallback on network
     *                  error).
     */
    private List<Grade> loadGradesFromCache(Semester semester, boolean ignoreTtl) {
        if (semester == null) {
            return null;
        }

        String key = buildCacheKey(semester);
        SharedPreferences prefs = getGradesCachePrefs();
        String json = prefs.getString(key, null);
        if (json == null) {
            String legacyKey = buildLegacyCacheKey(semester);
            json = prefs.getString(legacyKey, null);
            if (json != null) {
                prefs.edit()
                        .putString(key, json)
                        .remove(legacyKey)
                        .apply();
            }
        }
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

    // Semesters cache
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

    // UI helpers
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
