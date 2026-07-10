package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GradesTabFragment extends ZutnikTabFragment {

    // Core student data uses short TTL to keep study/semester/grades in sync.
    private static final long GRADES_CACHE_TTL_MS = CachePolicy.GRADES_TTL_MS;
    private static final long SEMESTERS_CACHE_TTL_MS = CachePolicy.SEMESTERS_TTL_MS;
    private static final long CREDIT_SUMMARY_CACHE_TTL_MS = CachePolicy.INFO_TTL_MS;
    private static final String GRADES_CACHE_PREFS_NAME = "grades_cache";
    private static final String KEY_GRADES_GROUPING = "grades_grouping_enabled";
    private static final String ACTIVE_GRADES_CACHE_SEMESTER_ID = "active_terms_v4";
    private static final String KEY_CREDIT_SUMMARY_CACHE_PREFIX = "credit_summary_";
    private static final String KEY_GRADES_LAST_NETWORK_TS_PREFIX = "grades_last_network_ts_";

    private Spinner spinnerStudies;
    private Spinner spinnerSemesters;
    private RecyclerView listGrades;
    private ProgressBar gradesProgress;
    private TextView tvEmpty;

    // Summary tiles
    private TextView tvAverageValue;
    private TextView tvEctsValue;
    private TextView tvEctsTotalValue;
    private TextView tvEctsBreakdown;
    private Toolbar toolbar;

    private GradesAdapter flatAdapter;
    private GroupedGradesAdapter groupedAdapter;
    private boolean groupingEnabled = true;
    private final List<Grade> currentGradesRaw = new ArrayList<>();
    private final List<PlanRepository.SubjectFilterItem> planFilterItems = new ArrayList<>();

    private final List<Study> studies = new ArrayList<>();
    private final List<Semester> semesters = new ArrayList<>();

    private ArrayAdapter<String> semestersAdapter;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentSemestersFuture;
    private java.util.concurrent.Future<?> currentGradesFuture;
    private java.util.concurrent.Future<?> currentPlanFilterFuture;
    private java.util.concurrent.Future<?> currentInitFuture;
    private java.util.concurrent.Future<?> currentCreditSummaryFuture;

    @Override
    @Nullable
    protected Toolbar getTabToolbar() {
        return toolbar;
    }

    @Nullable
    @Override
    protected MainNavHelper.Screen getTabScreen() {
        return MainNavHelper.Screen.GRADES;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return ShellLayoutInflater.inflateTabContent(inflater, R.layout.activity_grades, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        toolbar = view.findViewById(R.id.toolbar);

        spinnerStudies = view.findViewById(R.id.spinnerStudies);
        spinnerSemesters = view.findViewById(R.id.spinnerSemesters);
        listGrades = view.findViewById(R.id.listGrades);
        gradesProgress = view.findViewById(R.id.gradesProgress);
        tvEmpty = view.findViewById(R.id.tvEmpty);

        tvAverageValue = view.findViewById(R.id.tvAverageValue);
        tvEctsValue = view.findViewById(R.id.tvEctsValue);
        tvEctsTotalValue = view.findViewById(R.id.tvEctsTotalValue);
        tvEctsBreakdown = view.findViewById(R.id.tvEctsBreakdown);
        updateGradesDataFreshness(false);

        View btnGradesRefresh = view.findViewById(R.id.btnGradesRefresh);

        listGrades.setLayoutManager(new LinearLayoutManager(requireContext()));
        groupingEnabled = requireContext()
                .getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRADES_GROUPING, true);
        flatAdapter = new GradesAdapter(currentGradesRaw);
        groupedAdapter = new GroupedGradesAdapter();
        listGrades.setAdapter(groupingEnabled ? groupedAdapter : flatAdapter);

        onTabActivated();

        setupSemestersSpinner();

        if (btnGradesRefresh != null) {
            btnGradesRefresh.setOnClickListener(v -> {
                NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                        requireContext(),
                        NetworkRefreshPolicy.Module.GRADES,
                        NetworkRefreshPolicy.Mode.MANUAL,
                        null,
                        0L);
                if (!decision.allowNetwork) {
                    Toast.makeText(
                            requireContext(),
                            NetworkRefreshPolicy.describeForUser(requireContext(), decision),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                NetworkRefreshPolicy.recordAttempt(
                        requireContext(),
                        NetworkRefreshPolicy.Module.GRADES,
                        NetworkRefreshPolicy.Mode.MANUAL,
                        null);
                updateGradesDataFreshnessText(getString(R.string.data_status_syncing));
                Toast.makeText(
                        requireContext(),
                        R.string.grades_refresh_toast,
                        Toast.LENGTH_SHORT).show();
                reloadCurrentGrades(true);
            });
        }

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (!isTabCurrentlyVisible()) {
                    return;
                }
                inflater.inflate(R.menu.grades_menu, menu);
            }

            @Override
            public void onPrepareMenu(@NonNull Menu menu) {
                if (!isTabCurrentlyVisible()) {
                    return;
                }
                MenuItem item = menu.findItem(R.id.action_toggle_grouping);
                if (item != null) {
                    item.setChecked(groupingEnabled);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (!isTabCurrentlyVisible()) {
                    return false;
                }
                if (item.getItemId() == R.id.action_toggle_grouping) {
                    groupingEnabled = !groupingEnabled;
                    requireContext()
                            .getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_GRADES_GROUPING, groupingEnabled)
                            .apply();
                    applyGradesView();
                    invalidateActivityMenu();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());

        showCachedCurrentGradesIfAvailable();
        runInitialLoad();
        loadPlanFilterItemsAsync(false);
    }

    @Override
    protected void onTabActivated() {
        super.onTabActivated();
        applyGradesView();
        markCurrentGradesAsSeenIfVisible();
        if (planFilterItems.isEmpty()) {
            loadPlanFilterItemsAsync(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (currentInitFuture != null) {
            currentInitFuture.cancel(true);
        }
        if (currentSemestersFuture != null) {
            currentSemestersFuture.cancel(true);
        }
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }
        if (currentPlanFilterFuture != null) {
            currentPlanFilterFuture.cancel(true);
        }
        if (currentCreditSummaryFuture != null) {
            currentCreditSummaryFuture.cancel(true);
        }
        executor.shutdownNow();
    }

    // Initial load
    private void runInitialLoad() {
        if (currentInitFuture != null) {
            currentInitFuture.cancel(true);
        }
        if (currentCreditSummaryFuture != null) {
            currentCreditSummaryFuture.cancel(true);
        }
        executeInitTask();
    }

    private void executeInitTask() {
        showLoading(true);
        currentInitFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;
            int expectedStudyIndex = ZutnikSession.getInstance().getActiveStudyIndex();
            Study activeStudyForCache = getActiveStudySnapshot();
            String expectedStudyId = activeStudyForCache != null ? normalizeStudyId(activeStudyForCache.przynaleznoscId)
                    : null;

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
                    result = repo.loadSemesters(false);
                } catch (Exception e) {
                    error = e;
                }
            }

            // Fallback: If network failed, try disk cache
            if (result == null) {
                ZutnikSession s = ZutnikSession.getInstance();
                if (activeStudyForCache != null) {
                    List<Semester> cached = loadSemestersFromCache(activeStudyForCache);
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
                if (!isExpectedStudyContext(expectedStudyIndex, expectedStudyId)) {
                    return;
                }

                if (finalError != null) {
                    String message = friendlyUsosErrorMessage(finalError);
                    // Support suppression of "Unable to resolve host" toast
                    boolean isDnsError = message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(requireContext());

                    if (isOffline || isDnsError) {
                        android.util.Log.d("GradesTabFragment", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.grades_error_initial_load, message),
                                Toast.LENGTH_LONG).show();
                    }
                    showEmptyState(currentGradesRaw.isEmpty());
                    return;
                }

                // If loaded successfully from network (not cache), persist it
                if (!finalFromCache && finalResult != null && !finalResult.isEmpty()) {
                    ZutnikSession s = ZutnikSession.getInstance();
                    Study active = s.getActiveStudy();
                    if (active != null) {
                        saveSemestersToCache(active, finalResult);
                    }
                }

                // 1) Update the list of semesters
                semesters.clear();
                if (finalResult != null) {
                    semesters.addAll(finalResult);
                }

                List<String> semNames = new ArrayList<>();
                boolean usos = ZutnikSession.getInstance().isUsosLogin();
                for (Semester s : semesters) {
                    if (usos && s.rokAkademicki != null
                            && s.rokAkademicki.toLowerCase(Locale.ROOT).contains("semestr")) {
                        semNames.add(s.rokAkademicki);
                    } else {
                        semNames.add(
                                getString(
                                        R.string.grades_semester_label,
                                        s.nrSemestru,
                                        s.rokAkademicki));
                    }
                }

                semestersAdapter.clear();
                semestersAdapter.addAll(semNames);
                semestersAdapter.notifyDataSetChanged();

                // 2) Studies spinner based on ZutnikSession
                setupStudiesSpinner();
                refreshCreditSummaryForActiveStudyAsync();

                // 3) Grades are loaded from active USOS terms; semester choices stay hidden.
                reloadCurrentGrades(false);
            });
        });
    }

    // Study spinner
    private void setupStudiesSpinner() {
        ZutnikSession session = ZutnikSession.getInstance();
        List<Study> sessionStudies = session.getStudies();

        // Grades are account-level in the USOS flow, so the visible study selector only
        // suggests precision that the grades endpoint does not reliably provide.
        spinnerStudies.setVisibility(View.GONE);
        if (sessionStudies == null || sessionStudies.isEmpty()) {
            setEctsSummary(null, null);
            updateGradesDataFreshness(false);
            return;
        }

        studies.clear();
        studies.addAll(sessionStudies);
    }

    // Semester spinner
    private void setupSemestersSpinner() {
        spinnerSemesters.setVisibility(View.GONE);
        semestersAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item_dark,
                new ArrayList<>());
        semestersAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerSemesters.setAdapter(semestersAdapter);
    }

    // Loading semesters
    private void reloadSemesters() {
        reloadSemesters(false);
    }

    private void reloadSemesters(boolean forceNetwork) {
        if (currentSemestersFuture != null) {
            currentSemestersFuture.cancel(true);
        }
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }
        if (currentCreditSummaryFuture != null) {
            currentCreditSummaryFuture.cancel(true);
        }

        executeLoadSemestersTask(forceNetwork);
    }

    private void executeLoadSemestersTask(boolean forceNetwork) {
        showLoading(true);
        currentSemestersFuture = executor.submit(() -> {
            List<Semester> result = null;
            Exception error = null;
            boolean fromCache = false;
            int expectedStudyIndex = ZutnikSession.getInstance().getActiveStudyIndex();
            Study activeStudyForCache = getActiveStudySnapshot();
            String expectedStudyId = activeStudyForCache != null ? normalizeStudyId(activeStudyForCache.przynaleznoscId)
                    : null;

            if (!forceNetwork && activeStudyForCache != null) {
                List<Semester> cached = loadSemestersFromCache(activeStudyForCache);
                if (cached != null && !cached.isEmpty()) {
                    result = cached;
                    fromCache = true;
                }
            }

            if (result == null) {
                try {
                    GradesRepository repo = new GradesRepository();
                    result = repo.loadSemesters(forceNetwork);
                } catch (Exception e) {
                    error = e;
                }
            }

            if (result == null) {
                // Try to load from disk cache
                if (activeStudyForCache != null) {
                    result = loadSemestersFromCache(activeStudyForCache);
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
                if (!isExpectedStudyContext(expectedStudyIndex, expectedStudyId)) {
                    return;
                }

                if (finalError != null) {
                    android.util.Log.e("ZUTnik-GRADES", "Failed to load semesters", finalError);
                    String message = friendlyUsosErrorMessage(finalError);
                    Toast.makeText(
                            requireContext(),
                            getString(R.string.grades_error_loading_semesters, message),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (finalResult == null || finalResult.isEmpty()) {
                    Toast.makeText(
                            requireContext(),
                            R.string.grades_no_semesters_for_study,
                            Toast.LENGTH_SHORT).show();
                    semesters.clear();
                    semestersAdapter.clear();
                    semestersAdapter.notifyDataSetChanged();

                    currentGradesRaw.clear();
                    applyGradesView();
                    updateSummaryCards();
                    updateGradesDataFreshness(false);
                    refreshCreditSummaryForActiveStudyAsync();
                    return;
                }

                // If loaded successfully from network (not cache), persist it
                if (!finalFromCache) {
                    ZutnikSession s = ZutnikSession.getInstance();
                    Study active = s.getActiveStudy();
                    if (active != null) {
                        saveSemestersToCache(active, finalResult);
                    }
                }

                semesters.clear();
                semesters.addAll(finalResult);

                // Build labels for the semester spinner
                List<String> semNames = new ArrayList<>();
                boolean usos = ZutnikSession.getInstance().isUsosLogin();
                for (Semester s : semesters) {
                    if (usos && s.rokAkademicki != null
                            && s.rokAkademicki.toLowerCase(Locale.ROOT).contains("semestr")) {
                        semNames.add(s.rokAkademicki);
                    } else {
                        semNames.add(
                                getString(
                                        R.string.grades_semester_label,
                                        s.nrSemestru,
                                        s.rokAkademicki));
                    }
                }

                semestersAdapter.clear();
                semestersAdapter.addAll(semNames);
                semestersAdapter.notifyDataSetChanged();

                // Refresh studies spinner from session (in case repository changed
                // studies/activeStudyIndex)
                setupStudiesSpinner();
                refreshCreditSummaryForActiveStudyAsync();

                // Grades are shown for active USOS terms, not from manual semester selection.
                reloadCurrentGrades(forceNetwork);
            });
        });
    }

    // Loading grades
    // Grade grouping for expandable subject view
    private List<PlanRepository.SubjectFilterItem> getHiddenPlanFilterItems() {
        Set<String> hidden = PlanSubjectFilterHelper.loadHiddenSubjectKeys(requireContext());
        if (hidden.isEmpty() || planFilterItems.isEmpty()) {
            return Collections.emptyList();
        }

        List<PlanRepository.SubjectFilterItem> hiddenItems = new ArrayList<>();
        for (PlanRepository.SubjectFilterItem item : planFilterItems) {
            if (item == null || item.filterKey == null || item.filterKey.isEmpty()) {
                continue;
            }
            if (hidden.contains(item.filterKey)) {
                hiddenItems.add(item);
            }
        }
        return hiddenItems;
    }

    private List<GroupedGradesAdapter.GradeGroup> buildGradeGroups(List<Grade> source) {
        List<PlanRepository.SubjectFilterItem> hiddenItems = getHiddenPlanFilterItems();
        List<Grade> filtered = filterGradesForDisplay(source, hiddenItems);
        Map<String, GroupedGradesAdapter.GradeGroup> grouped = buildGradeGroupMap(filtered);
        addVisiblePlanFilterPlaceholders(grouped, hiddenItems);
        List<GroupedGradesAdapter.GradeGroup> result = new ArrayList<>();
        for (GroupedGradesAdapter.GradeGroup group : grouped.values()) {
            finalizeGradeGroup(group);
            if (!group.others.isEmpty() || group.finalGrade != null || group.emptyFromPlanFilter) {
                result.add(group);
            }
        }
        return result;
    }

    private void addVisiblePlanFilterPlaceholders(
            Map<String, GroupedGradesAdapter.GradeGroup> grouped,
            List<PlanRepository.SubjectFilterItem> hiddenItems) {
        if (grouped == null || planFilterItems.isEmpty()) {
            return;
        }

        Set<String> hiddenKeys = new HashSet<>();
        if (hiddenItems != null) {
            for (PlanRepository.SubjectFilterItem item : hiddenItems) {
                if (item != null && item.filterKey != null && !item.filterKey.isEmpty()) {
                    hiddenKeys.add(item.filterKey);
                }
            }
        }

        Set<String> existingSubjects = new HashSet<>();
        for (String subject : grouped.keySet()) {
            String normalized = PlanSubjectFilterHelper.normalizeFilterString(subject);
            if (!normalized.isEmpty()) {
                existingSubjects.add(normalized);
            }
        }

        for (PlanRepository.SubjectFilterItem item : planFilterItems) {
            if (item == null || item.filterKey == null || item.filterKey.isEmpty()) {
                continue;
            }
            if (hiddenKeys.contains(item.filterKey)) {
                continue;
            }

            String subject = GradesTextUtils.clean(item.label);
            if (subject.isEmpty()) {
                continue;
            }
            String normalized = PlanSubjectFilterHelper.normalizeFilterString(subject);
            if (normalized.isEmpty() || existingSubjects.contains(normalized)) {
                continue;
            }

            GroupedGradesAdapter.GradeGroup group = new GroupedGradesAdapter.GradeGroup(subject);
            group.emptyFromPlanFilter = true;
            grouped.put(subject, group);
            existingSubjects.add(normalized);
        }
    }

    private Map<String, GroupedGradesAdapter.GradeGroup> buildGradeGroupMap(List<Grade> source) {
        Map<String, GroupedGradesAdapter.GradeGroup> map = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return map;
        }

        for (Grade g : source) {
            String subject = GradesTextUtils.extractBaseSubject(g.subjectName);
            if (subject.trim().isEmpty()) {
                continue;
            }

            GroupedGradesAdapter.GradeGroup group = map.get(subject);
            if (group == null) {
                group = new GroupedGradesAdapter.GradeGroup(subject);
                map.put(subject, group);
            }
            if (g.isNew) {
                group.hasNew = true;
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
        return map;
    }

    private void finalizeGradeGroup(GroupedGradesAdapter.GradeGroup group) {
        boolean hasFinal = group.finalGrade != null;
        boolean finalHasValue = hasFinal
                && group.finalGrade.grade != null
                && !group.finalGrade.grade.trim().isEmpty();
        group.finalMissing = hasFinal && !finalHasValue;
    }

    private List<Grade> filterGradesForDisplay(List<Grade> source) {
        return filterGradesForDisplay(source, getHiddenPlanFilterItems());
    }

    private List<Grade> filterGradesForDisplay(
            List<Grade> source,
            List<PlanRepository.SubjectFilterItem> hiddenItems) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        if (hiddenItems == null || hiddenItems.isEmpty()) {
            return new ArrayList<>(source);
        }

        List<Grade> filtered = new ArrayList<>();
        for (Grade grade : source) {
            if (!isGradeHiddenByPlanFilter(grade, hiddenItems)) {
                filtered.add(grade);
            }
        }
        return filtered;
    }

    private boolean isGradeHiddenByPlanFilter(
            Grade grade,
            List<PlanRepository.SubjectFilterItem> hiddenItems) {
        if (grade == null || hiddenItems == null || hiddenItems.isEmpty()) {
            return false;
        }
        for (PlanRepository.SubjectFilterItem hiddenItem : hiddenItems) {
            if (PlanSubjectFilterHelper.gradeMatchesHiddenFilterItem(grade, hiddenItem)) {
                return true;
            }
        }
        return false;
    }

    private void applyGradesView() {
        if (listGrades == null || flatAdapter == null || groupedAdapter == null) {
            return;
        }
        List<Grade> displayGrades = GradesCorrectionHelper.collapseCorrectedGrades(currentGradesRaw);
        if (groupingEnabled) {
            List<GroupedGradesAdapter.GradeGroup> groups = buildGradeGroups(displayGrades);
            groupedAdapter.setGroups(groups);
            if (listGrades.getAdapter() != groupedAdapter) {
                listGrades.setAdapter(groupedAdapter);
            }
            showEmptyState(groups.isEmpty());
        } else {
            List<Grade> filteredGrades = filterGradesForDisplay(displayGrades, getHiddenPlanFilterItems());
            flatAdapter.setGrades(filteredGrades);
            if (listGrades.getAdapter() != flatAdapter) {
                listGrades.setAdapter(flatAdapter);
            } else {
                flatAdapter.notifyDataSetChanged();
            }
            showEmptyState(filteredGrades.isEmpty());
        }
    }

    private void loadPlanFilterItemsAsync(boolean forceRefresh) {
        if (currentPlanFilterFuture != null) {
            currentPlanFilterFuture.cancel(true);
        }
        Context appContext = requireContext().getApplicationContext();
        currentPlanFilterFuture = executor.submit(() -> {
            List<PlanRepository.SubjectFilterItem> loaded = Collections.emptyList();
            try {
                loaded = PlanSubjectFilterHelper.loadAllFilterItems(appContext, forceRefresh);
            } catch (Exception ignored) {
                loaded = Collections.emptyList();
            }
            final List<PlanRepository.SubjectFilterItem> finalLoaded = loaded;
            handler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                planFilterItems.clear();
                if (finalLoaded != null && !finalLoaded.isEmpty()) {
                    planFilterItems.addAll(finalLoaded);
                }
                applyGradesView();
            });
        });
    }

    private boolean isFinalGrade(Grade g) {
        if (g == null) {
            return false;
        }
        String type = GradesTextUtils.normalizeKey(g.type);
        if (GradesTextUtils.hasFinalGradeMarker(type)) {
            return true;
        }
        if (type.isEmpty()) {
            return GradesTextUtils.isFinalGradeLabel(g.subjectName);
        }
        return false;
    }

    private Semester activeGradesCacheScope() {
        Semester scope = new Semester();
        scope.listaSemestrowId = ACTIVE_GRADES_CACHE_SEMESTER_ID;
        scope.nrSemestru = "";
        scope.rokAkademicki = "";
        scope.status = "Aktywny";
        return scope;
    }

    private void showCachedCurrentGradesIfAvailable() {
        Study activeStudy = getActiveStudySnapshot();
        String expectedStudyId = activeStudy != null ? normalizeStudyId(activeStudy.przynaleznoscId) : null;
        if (expectedStudyId == null) {
            return;
        }
        showCachedGradesIfAvailable(expectedStudyId, activeGradesCacheScope(), true);
    }

    private boolean showCachedGradesIfAvailable(String studyId, Semester scope, boolean ignoreTtl) {
        List<Grade> cached = loadGradesFromCache(studyId, scope, ignoreTtl);
        if (cached == null) {
            return false;
        }

        currentGradesRaw.clear();
        currentGradesRaw.addAll(cached);
        applyGradesView();
        markCurrentGradesAsSeenIfVisible();
        updateSummaryCards();
        updateGradesDataFreshness(false);
        if (planFilterItems.isEmpty()) {
            loadPlanFilterItemsAsync(false);
        }
        return true;
    }

    private void reloadCurrentGrades(boolean forceNetwork) {
        int expectedStudyIndex = ZutnikSession.getInstance().getActiveStudyIndex();
        Study activeStudy = getActiveStudySnapshot();
        String expectedStudyId = activeStudy != null ? normalizeStudyId(activeStudy.przynaleznoscId) : null;
        if (expectedStudyId == null) {
            showEmptyState(true);
            return;
        }

        Semester cacheScope = activeGradesCacheScope();
        if (!forceNetwork) {
            if (showCachedGradesIfAvailable(expectedStudyId, cacheScope, false)) {
                return;
            }
        }

        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }
        boolean showingCached = showCachedGradesIfAvailable(expectedStudyId, cacheScope, true);
        if (!forceNetwork) {
            NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                    requireContext(),
                    NetworkRefreshPolicy.Module.GRADES,
                    NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                    null,
                    0L);
            if (!decision.allowNetwork) {
                showEmptyState(!showingCached);
                updateGradesDataFreshness(false);
                return;
            }
        }
        executeLoadCurrentGradesTask(expectedStudyIndex, expectedStudyId, cacheScope, forceNetwork, showingCached);
    }

    private void executeLoadCurrentGradesTask(
            int expectedStudyIndex,
            String expectedStudyId,
            Semester cacheScope,
            boolean forceNetwork,
            boolean keepExistingWhileLoading) {
        showLoading(true);
        if (!keepExistingWhileLoading) {
            currentGradesRaw.clear();
            applyGradesView();
        }

        currentGradesFuture = executor.submit(() -> {
            List<Grade> grades = null;
            Exception error = null;
            List<Grade> previousSnapshot = loadGradesFromCache(expectedStudyId, cacheScope, true);
            try {
                GradesRepository repo = new GradesRepository();
                grades = forceNetwork
                        ? repo.loadCurrentGradesFromNetwork()
                        : repo.loadCurrentGrades(NetworkRefreshPolicy.Mode.SCREEN_AUTO);
                markNewGrades(previousSnapshot, grades);
            } catch (Exception e) {
                error = e;
            }

            final List<Grade> finalGrades = grades;
            final Exception finalError = error;
            handler.post(() -> {
                showLoading(false);
                if (!isExpectedStudyContext(expectedStudyIndex, expectedStudyId)) {
                    return;
                }

                if (finalError != null) {
                    List<Grade> cached = loadGradesFromCache(expectedStudyId, cacheScope, true);
                    if (cached != null) {
                        currentGradesRaw.clear();
                        currentGradesRaw.addAll(cached);
                        applyGradesView();
                        markCurrentGradesAsSeenIfVisible();
                        updateSummaryCards();
                        updateGradesDataFreshness(false);

                        int msgId = forceNetwork
                                ? R.string.grades_refresh_network_failed_using_cache
                                : R.string.grades_load_network_failed_using_cache;
                        Toast.makeText(requireContext(), msgId, Toast.LENGTH_LONG).show();
                        return;
                    }

                    String message = friendlyUsosErrorMessage(finalError);
                    String normalizedMessage = message.toLowerCase(Locale.ROOT);
                    boolean isDnsError = message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(requireContext());
                    boolean isInterruption = finalError instanceof java.io.InterruptedIOException
                            || normalizedMessage.contains("interrupted");
                    if (!(isOffline || isDnsError || isInterruption)) {
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.grades_error_loading_grades, message),
                                Toast.LENGTH_LONG).show();
                    }
                    showEmptyState(true);
                    updateGradesDataFreshness(false);
                    return;
                }

                currentGradesRaw.clear();
                if (finalGrades != null) {
                    currentGradesRaw.addAll(finalGrades);
                    saveGradesToCache(expectedStudyId, cacheScope, finalGrades);
                }
                applyGradesView();
                markCurrentGradesAsSeenIfVisible();
                updateSummaryCards();
                updateGradesDataFreshness(true);
                if (planFilterItems.isEmpty()) {
                    loadPlanFilterItemsAsync(forceNetwork);
                }
            });
        });
    }

    private void reloadGrades(Semester semester, boolean forceNetwork) {
        if (semester == null) {
            return;
        }
        int expectedStudyIndex = ZutnikSession.getInstance().getActiveStudyIndex();
        Study activeStudy = getActiveStudySnapshot();
        String expectedStudyId = activeStudy != null ? normalizeStudyId(activeStudy.przynaleznoscId) : null;
        if (expectedStudyId == null) {
            return;
        }

        // 1) Normal mode (no force) - try cache first. If it is fresh -> do NOT hit the
        // network.
        if (!forceNetwork) {
            if (showCachedGradesIfAvailable(expectedStudyId, semester, false)) {
                // Fresh cache -> skip network request
                return;
            }
        }

        // 2) Force mode (button) or missing cache -> fetch from network in background
        if (currentGradesFuture != null) {
            currentGradesFuture.cancel(true);
        }

        boolean showingCached = showCachedGradesIfAvailable(expectedStudyId, semester, true);
        executeLoadGradesTask(expectedStudyIndex, expectedStudyId, semester, forceNetwork, showingCached);
    }

    private void executeLoadGradesTask(
            int expectedStudyIndex,
            String expectedStudyId,
            Semester semester,
        boolean forceNetwork,
        boolean keepExistingWhileLoading) {
        showLoading(true);
        // Keep cached grades on screen while the network refresh runs.
        if (!keepExistingWhileLoading) {
            currentGradesRaw.clear();
            applyGradesView();
        }
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
                if (!isExpectedStudyContext(expectedStudyIndex, expectedStudyId)) {
                    return;
                }

                if (finalError != null) {
                    // On error, try using cache even if it is expired
                    List<Grade> cached = loadGradesFromCache(expectedStudyId, semester, true);
                    if (cached != null) {
                        currentGradesRaw.clear();
                        currentGradesRaw.addAll(cached);
                        applyGradesView();
                        updateSummaryCards();
                        updateGradesDataFreshness(false);

                        int msgId = forceNetwork
                                ? R.string.grades_refresh_network_failed_using_cache
                                : R.string.grades_load_network_failed_using_cache;

                        Toast.makeText(
                                requireContext(),
                                msgId,
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    android.util.Log.e("ZUTnik-GRADES", "Failed to load grades for semester", finalError);
                    String message = friendlyUsosErrorMessage(finalError);
                    String normalizedMessage = message.toLowerCase(Locale.ROOT);
                    boolean isDnsError = message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(requireContext());
                    boolean isInterruption = finalError instanceof java.io.InterruptedIOException
                            || normalizedMessage.contains("interrupted");

                    if (isOffline || isDnsError || isInterruption) {
                        android.util.Log.d("GradesTabFragment", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.grades_error_loading_grades, message),
                                Toast.LENGTH_LONG).show();
                    }
                    showEmptyState(true);
                    updateGradesDataFreshness(false);
                    return;
                }
                currentGradesRaw.clear();
                if (finalGrades != null) {
                    currentGradesRaw.addAll(finalGrades);
                    saveGradesToCache(expectedStudyId, semester, finalGrades);
                }
                applyGradesView();
                updateSummaryCards();
                updateGradesDataFreshness(true);
                if (planFilterItems.isEmpty()) {
                    loadPlanFilterItemsAsync(forceNetwork);
                }
            });
        });
    }

    private void markNewGrades(List<Grade> previous, List<Grade> current) {
        if (current == null || current.isEmpty()) {
            return;
        }
        if (previous == null || previous.isEmpty()) {
            for (Grade grade : current) {
                if (grade != null) {
                    grade.isNew = false;
                }
            }
            return;
        }

        Set<String> previousKeys = new HashSet<>();
        for (Grade grade : previous) {
            String key = buildGradeStableKey(grade);
            if (!key.isEmpty()) {
                previousKeys.add(key);
            }
        }
        for (Grade grade : current) {
            String key = buildGradeStableKey(grade);
            grade.isNew = !key.isEmpty() && !previousKeys.contains(key);
        }
    }

    private void markCurrentGradesAsSeenIfVisible() {
        if (!isTabCurrentlyVisible() || currentGradesRaw.isEmpty()) {
            return;
        }
        NotificationSyncManager.markGradesAsSeen(requireContext(), currentGradesRaw);
    }

    private String buildGradeStableKey(Grade grade) {
        if (grade == null) {
            return "";
        }
        return (
                GradesTextUtils.clean(grade.courseId) + "|" +
                GradesTextUtils.clean(grade.subjectName) + "|" +
                GradesTextUtils.clean(grade.type) + "|" +
                GradesTextUtils.clean(grade.grade) + "|" +
                GradesTextUtils.clean(grade.date) + "|" +
                GradesTextUtils.clean(grade.gradeDescription)
        ).toLowerCase(Locale.ROOT);
    }

    // Summary calculations
    private void updateSummaryCards() {
        double sumWeighted = 0.0;
        double sumWeights = 0.0;
        boolean usedFinal = false;
        List<Grade> summaryGrades = GradesCorrectionHelper.collapseCorrectedGrades(currentGradesRaw);

        for (Grade g : summaryGrades) {
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
            for (Grade g : summaryGrades) {
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
    }

    private String buildGradesLastSyncKey(Study study) {
        if (study == null || study.przynaleznoscId == null) {
            return null;
        }
        ZutnikSession s = ZutnikSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        return KEY_GRADES_LAST_NETWORK_TS_PREFIX + userId + "_" + study.przynaleznoscId;
    }

    private void updateGradesDataFreshness(boolean fetchedFromNetwork) {
        Study activeStudy = getActiveStudySnapshot();
        String lastSyncKey = buildGradesLastSyncKey(activeStudy);
        SharedPreferences cachePrefs = getGradesCachePrefs();
        long now = System.currentTimeMillis();

        if (fetchedFromNetwork && lastSyncKey != null) {
            cachePrefs.edit().putLong(lastSyncKey, now).apply();
            updateGradesDataFreshnessText(getString(R.string.data_status_online_now));
            return;
        }

        long lastNetworkTs = lastSyncKey != null ? cachePrefs.getLong(lastSyncKey, 0L) : 0L;
        if (lastNetworkTs > 0L) {
            if ((now - lastNetworkTs) < DateUtils.MINUTE_IN_MILLIS) {
                updateGradesDataFreshnessText(getString(R.string.data_status_online_now));
                return;
            }
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    lastNetworkTs,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            updateGradesDataFreshnessText(getString(R.string.data_status_cache_since, rel));
        } else {
            updateGradesDataFreshnessText(getString(R.string.data_status_cache));
        }
    }

    private void updateGradesDataFreshnessText(String text) {
        if (toolbar != null) {
            String safe = text != null ? text : "";
            SpannableString subtitle = new SpannableString(safe);
            if (!safe.isEmpty()) {
                subtitle.setSpan(
                        new RelativeSizeSpan(0.78f),
                        0,
                        safe.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            toolbar.setSubtitle(subtitle);
        }
    }

    private String buildCreditSummaryCacheKey(Study study) {
        if (study == null || study.przynaleznoscId == null) {
            return null;
        }
        ZutnikSession s = ZutnikSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        return KEY_CREDIT_SUMMARY_CACHE_PREFIX + userId + "_" + study.przynaleznoscId;
    }

    private GradesRepository.CreditSummary loadCreditSummaryFromCache(Study study) {
        String key = buildCreditSummaryCacheKey(study);
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

            if ((System.currentTimeMillis() - ts) > CREDIT_SUMMARY_CACHE_TTL_MS
                    && NetworkStatusHelper.isNetworkAvailable(requireContext())) {
                return null;
            }

            Double programme = wrapper.has("programme")
                    ? wrapper.optDouble("programme", Double.NaN)
                    : null;
            if (programme != null && (Double.isNaN(programme) || programme < 0.0)) {
                programme = null;
            }
            Double overall = wrapper.has("overall")
                    ? wrapper.optDouble("overall", Double.NaN)
                    : null;
            if (overall != null && (Double.isNaN(overall) || overall < 0.0)) {
                overall = null;
            }
            List<GradesRepository.ProgrammeCredit> programmeCredits = new ArrayList<>();
            JSONArray programmes = wrapper.optJSONArray("programmes");
            if (programmes != null) {
                for (int i = 0; i < programmes.length(); i++) {
                    JSONObject obj = programmes.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    Double used = obj.has("used")
                            ? obj.optDouble("used", Double.NaN)
                            : null;
                    if (used != null && (Double.isNaN(used) || used < 0.0)) {
                        used = null;
                    }
                    programmeCredits.add(new GradesRepository.ProgrammeCredit(
                            obj.optString("id", ""),
                            obj.optString("label", ""),
                            used));
                }
            }

            return new GradesRepository.CreditSummary(
                    wrapper.optString("studentProgrammeId", ""),
                    programme,
                    overall,
                    programmeCredits);
        } catch (JSONException e) {
            return null;
        }
    }

    private void saveCreditSummaryToCache(Study study, GradesRepository.CreditSummary summary) {
        String key = buildCreditSummaryCacheKey(study);
        if (key == null) {
            return;
        }

        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put("studentProgrammeId", summary != null ? summary.studentProgrammeId : "");
            if (summary != null && summary.programmeUsed != null) {
                wrapper.put("programme", Math.max(0.0, summary.programmeUsed));
            }
            if (summary != null && summary.overallUsed != null) {
                wrapper.put("overall", Math.max(0.0, summary.overallUsed));
            }
            JSONArray programmes = new JSONArray();
            if (summary != null && summary.programmeCredits != null) {
                for (GradesRepository.ProgrammeCredit credit : summary.programmeCredits) {
                    if (credit == null) {
                        continue;
                    }
                    JSONObject obj = new JSONObject();
                    obj.put("id", credit.studentProgrammeId != null ? credit.studentProgrammeId : "");
                    obj.put("label", credit.label != null ? credit.label : "");
                    if (credit.used != null && credit.used >= 0.0 && !Double.isNaN(credit.used)) {
                        obj.put("used", credit.used);
                    }
                    programmes.put(obj);
                }
            }
            wrapper.put("programmes", programmes);
            getGradesCachePrefs()
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private void refreshCreditSummaryForActiveStudyAsync() {
        final Study activeStudy = getActiveStudySnapshot();
        final int expectedStudyIndex = ZutnikSession.getInstance().getActiveStudyIndex();

        if (activeStudy == null || activeStudy.przynaleznoscId == null) {
            setEctsSummary(null, null);
            return;
        }

        final String expectedStudyId = normalizeStudyId(activeStudy.przynaleznoscId);
        GradesRepository.CreditSummary cached = loadCreditSummaryFromCache(activeStudy);
        if (cached != null) {
            setEctsSummary(cached);
            return;
        } else {
            setEctsSummary((GradesRepository.CreditSummary) null);
        }

        if (currentCreditSummaryFuture != null) {
            currentCreditSummaryFuture.cancel(true);
        }

        currentCreditSummaryFuture = executor.submit(() -> {
            GradesRepository.CreditSummary summary = null;
            try {
                summary = new GradesRepository().loadCreditSummary();
            } catch (Exception ignored) {
            }

            final GradesRepository.CreditSummary finalSummary = summary;
            handler.post(() -> {
                if (!isExpectedStudyContext(expectedStudyIndex, expectedStudyId)) {
                    return;
                }
                if (finalSummary == null) {
                    return;
                }
                setEctsSummary(finalSummary);
                saveCreditSummaryToCache(activeStudy, finalSummary);
            });
        });
    }

    private void setEctsSummary(Double programmeUsed, Double overallUsed) {
        setEctsSummary(new GradesRepository.CreditSummary("", programmeUsed, overallUsed));
    }

    private void setEctsSummary(GradesRepository.CreditSummary summary) {
        Double programmeUsed = summary != null ? summary.programmeUsed : null;
        Double overallUsed = summary != null ? summary.overallUsed : null;
        if (tvEctsValue != null) {
            tvEctsValue.setText(formatSummaryNumber(programmeUsed));
        }
        if (tvEctsTotalValue != null) {
            tvEctsTotalValue.setText(formatSummaryNumber(overallUsed));
        }
        updateEctsBreakdown(summary);
    }

    private void updateEctsBreakdown(GradesRepository.CreditSummary summary) {
        if (tvEctsBreakdown == null) {
            return;
        }
        if (summary == null || summary.programmeCredits == null || summary.programmeCredits.size() <= 1) {
            tvEctsBreakdown.setVisibility(View.GONE);
            tvEctsBreakdown.setText("");
            return;
        }

        List<String> parts = new ArrayList<>();
        double programmeSum = 0.0;
        boolean hasProgrammeSum = false;
        for (GradesRepository.ProgrammeCredit credit : summary.programmeCredits) {
            if (credit == null) {
                continue;
            }
            String label = shortenStudyLabel(credit.label);
            if (label.isEmpty()) {
                label = getString(R.string.grades_ects_programme_fallback);
            }
            parts.add(getString(
                    R.string.grades_ects_breakdown_item,
                    label,
                    formatSummaryNumber(credit.used)));
            if (credit.used != null && credit.used >= 0.0 && !Double.isNaN(credit.used)) {
                programmeSum += credit.used;
                hasProgrammeSum = true;
            }
        }

        if (summary.overallUsed != null && hasProgrammeSum) {
            double remaining = summary.overallUsed - programmeSum;
            if (remaining > 0.05) {
                parts.add(getString(
                        R.string.grades_ects_breakdown_remaining,
                        formatSummaryNumber(remaining)));
            }
        }

        if (parts.isEmpty()) {
            tvEctsBreakdown.setVisibility(View.GONE);
            tvEctsBreakdown.setText("");
            return;
        }
        tvEctsBreakdown.setVisibility(View.VISIBLE);
        tvEctsBreakdown.setText(String.join("  •  ", parts));
    }

    private String shortenStudyLabel(String value) {
        String clean = GradesTextUtils.clean(value);
        int paren = clean.indexOf('(');
        if (paren > 0) {
            clean = clean.substring(0, paren).trim();
        }
        int comma = clean.indexOf(',');
        if (comma > 0) {
            clean = clean.substring(0, comma).trim();
        }
        if (clean.length() <= 18) {
            return clean;
        }
        return clean.substring(0, 17).trim() + "…";
    }

    private String formatSummaryNumber(Double value) {
        if (value == null || value < 0.0 || Double.isNaN(value)) {
            return getString(R.string.grades_average_placeholder);
        }
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.format(Locale.getDefault(), "%.0f", value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    // Grades cache
    private SharedPreferences getGradesCachePrefs() {
        return requireContext().getSharedPreferences(GRADES_CACHE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String normalizeStudyId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String normalized = rawId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isExpectedStudyContext(int expectedStudyIndex, String expectedStudyId) {
        ZutnikSession session = ZutnikSession.getInstance();
        int currentStudyIndex = session.getActiveStudyIndex();
        if (expectedStudyIndex != currentStudyIndex) {
            return false;
        }

        Study currentStudy = session.getActiveStudy();
        String currentStudyId = currentStudy != null ? normalizeStudyId(currentStudy.przynaleznoscId) : null;
        String expectedId = normalizeStudyId(expectedStudyId);

        if (expectedId != null && currentStudyId != null) {
            return expectedId.equals(currentStudyId);
        }
        return true;
    }

    private Study getActiveStudySnapshot() {
        return ZutnikSession.getInstance().getActiveStudy();
    }

    private String buildCacheKey(String studyId, Semester semester) {
        ZutnikSession s = ZutnikSession.getInstance();
        String userId = s.getUserId();
        if (userId == null) {
            userId = "unknown";
        }
        String safeStudyId = (studyId != null && !studyId.trim().isEmpty()) ? studyId.trim() : "no_study";
        String semId = semester != null ? semester.listaSemestrowId : null;
        if (semId == null) {
            semId = "no_sem";
        }
        return userId + "_" + safeStudyId + "_" + semId;
    }

    private void saveGradesToCache(String studyId, Semester semester, List<Grade> grades) {
        if (semester == null || grades == null) {
            return;
        }

        try {
            JSONArray arr = new JSONArray();
            for (Grade g : grades) {
                JSONObject o = new JSONObject();
                o.put("subjectName", g.subjectName);
                o.put("courseId", g.courseId);
                o.put("grade", g.grade);
                o.put("weight", g.weight);
                o.put("gradeType", g.gradeType);
                o.put("type", g.type);
                o.put("gradeDescription", g.gradeDescription);
                o.put("passes", g.passes);
                o.put("teacher", g.teacher);
                o.put("date", g.date);
                o.put("comment", g.comment);
                o.put("countsIntoAverage", g.countsIntoAverage);
                o.put("examId", g.examId);
                o.put("examSessionNumber", g.examSessionNumber);
                o.put("dateModified", g.dateModified);
                o.put("dateAcquisition", g.dateAcquisition);
                o.put("modificationAuthor", g.modificationAuthor);
                o.put("decimalValue", g.decimalValue);
                o.put("gradeTypeId", g.gradeTypeId);
                arr.put(o);
            }

            JSONObject wrapper = new JSONObject();
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put("grades", arr);

            String key = buildCacheKey(studyId, semester);
            getGradesCachePrefs()
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();
        } catch (JSONException e) {
            // Ignore - cache is optional
        }
    }

    /**
     * @param ignoreTtl if true - ignore normal TTL (used as fallback on network
     *                  error).
     */
    private List<Grade> loadGradesFromCache(String studyId, Semester semester, boolean ignoreTtl) {
        if (semester == null) {
            return null;
        }

        String key = buildCacheKey(studyId, semester);
        SharedPreferences prefs = getGradesCachePrefs();
        String json = prefs.getString(key, null);
        if (json == null) {
            return null;
        }

        try {
            JSONObject wrapper = new JSONObject(json);
            long ts = wrapper.optLong("timestamp", 0L);
            long now = System.currentTimeMillis();

            if (!ignoreTtl && ts > 0 && (now - ts) > GRADES_CACHE_TTL_MS) {
                // If offline, we can ignore TTL to show old data instead of error
                if (NetworkStatusHelper.isNetworkAvailable(requireContext())) {
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
                g.courseId = o.optString("courseId", "");
                g.grade = o.optString("grade", "");
                g.weight = o.optDouble("weight", 0.0);
                g.gradeType = o.optString("gradeType", "");
                g.type = o.optString("type", "");
                g.gradeDescription = o.optString("gradeDescription", "");
                g.passes = o.optBoolean("passes", false);
                g.teacher = o.optString("teacher", "");
                g.date = o.optString("date", "");
                g.comment = o.optString("comment", "");
                g.countsIntoAverage = o.optBoolean("countsIntoAverage", false);
                g.examId = o.optString("examId", "");
                g.examSessionNumber = o.optInt("examSessionNumber", 0);
                g.dateModified = o.optString("dateModified", "");
                g.dateAcquisition = o.optString("dateAcquisition", "");
                g.modificationAuthor = o.optString("modificationAuthor", "");
                g.decimalValue = o.optString("decimalValue", "");
                g.gradeTypeId = o.optInt("gradeTypeId", 0);
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

        ZutnikSession s = ZutnikSession.getInstance();
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

            requireContext().getSharedPreferences(PREFS_GRADES_SEMESTERS_CACHE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key, wrapper.toString())
                    .apply();

        } catch (JSONException ignored) {
        }
    }

    private List<Semester> loadSemestersFromCache(Study study) {
        if (study == null || study.przynaleznoscId == null)
            return null;

        ZutnikSession s = ZutnikSession.getInstance();
        String userId = s.getUserId();
        if (userId == null)
            userId = "unknown";

        String key = userId + "_" + study.przynaleznoscId;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_GRADES_SEMESTERS_CACHE, Context.MODE_PRIVATE);

        String json = prefs.getString(key, null);
        if (json == null)
            return null;

        try {
            JSONObject wrapper = new JSONObject(json);
            long ts = wrapper.optLong("ts", 0L);
            if ((System.currentTimeMillis() - ts) > SEMESTERS_CACHE_TTL_MS) {
                if (NetworkStatusHelper.isNetworkAvailable(requireContext())) {
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

    private String friendlyUsosErrorMessage(Exception error) {
        String raw = error != null && error.getMessage() != null ? error.getMessage() : "";
        return UsosApi.friendlyErrorMessage(raw);
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
