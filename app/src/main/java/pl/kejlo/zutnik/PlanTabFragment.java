package pl.kejlo.zutnik;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.fragment.app.Fragment;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import androidx.core.graphics.Insets;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.core.content.ContextCompat;

public class PlanTabFragment extends ZutnikTabFragment {

    private View rootView;

    @SuppressWarnings("unchecked")
    private <T extends View> T tabFind(int id) {
        return (T) rootView.findViewById(id);
    }

    private enum ViewMode {
        DAY("day"),
        WEEK("week"),
        MONTH("month");

        private final String id;

        ViewMode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static ViewMode fromId(String id) {
            if (id == null) {
                return WEEK;
            }
            for (ViewMode m : values()) {
                if (m.id.equals(id))
                    return m;
            }
            return WEEK;
        }
    }

    private static final String PREFS_NAME = "zutnik_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    private static final String KEY_FILTER_CACHE_JSON = "plan_filters_cache_json";
    private static final String KEY_FILTER_CACHE_TS = "plan_filters_cache_ts";
    private static final String KEY_FILTER_CACHE_FORCE_REFRESH = "plan_filters_force_refresh_v1";
    private static final String KEY_PLAN_LAST_NETWORK_SYNC_TS = "plan_last_network_sync_ts";
    private static final String FILTER_CACHE_JSON_PREFIX = KEY_FILTER_CACHE_JSON + "_";
    private static final String FILTER_CACHE_TS_PREFIX = KEY_FILTER_CACHE_TS + "_";
    private static final long FILTER_CACHE_TTL_MS = CachePolicy.PLAN_FILTER_TTL_MS;

    private static final int START_HOUR = 6;
    private static final int END_HOUR = 22;
    private static final float HOUR_HEIGHT_DP = 48f;
    private static final float DAY_HEADER_HEIGHT_DP = 48f;
    private static final float MONTH_CELL_HEIGHT_DP = 70f;

    private static final int VP_START_POSITION = 5000;
    private static final int PAGE_REFRESH_RADIUS = 1;

    private Button btnViewDay;
    private Button btnViewWeek;
    private Button btnViewMonth;

    private ImageButton btnSearch;
    private ImageButton btnRefresh;
    private ImageButton btnMenu;

    private TextView tvHeaderLabel;
    private TextView tvPlanToolbarSubtitle;
    private Toolbar toolbar;

    private LinearLayout layoutTimeColumn;

    private LinearLayout layoutWeekHeadersFixed;
    private LinearLayout layoutWeekHeadersRow;
    private LinearLayout layoutMonthHeadersFixed;

    private ViewPager2 viewPager;
    private RecyclerView pagerRecyclerView;
    private PlanPagerAdapter pagerAdapter;

    private ProgressBar progress;

    private String viewModeId = ViewMode.WEEK.getId();

    private LocalDate baseDate = LocalDate.now();
    private LocalDate currentDate = LocalDate.now();

    private PlanRepository planRepository;

    private final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SharedPreferences prefs;
    private Set<String> hiddenSubjectKeys = new HashSet<>();
    private final Set<String> achievementViewedModes = new HashSet<>();

    private PlanRepository.SearchParams currentSearchQuery = null;

    private android.animation.ObjectAnimator mRefreshAnimator;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private volatile List<PlanRepository.SessionPeriod> sessionDates = new ArrayList<>();
    private final Object forceRefreshLock = new Object();
    private boolean pendingForceRefresh = false;
    private LocalDate pendingForceRefreshDate = null;
    private String pendingForceRefreshMode = null;

    private static class PlanKey {
        final String viewModeId;
        final LocalDate date;

        PlanKey(String v, LocalDate d) {
            this.viewModeId = v;
            this.date = d;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof PlanKey))
                return false;
            PlanKey pk = (PlanKey) o;
            return viewModeId.equals(pk.viewModeId) && date.equals(pk.date);
        }

        @Override
        public int hashCode() {
            return viewModeId.hashCode() * 31 + date.hashCode();
        }
    }

    private final LinkedHashMap<PlanKey, PlanRepository.PlanResult> planCache = new LinkedHashMap<>(
            20, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PlanKey, PlanRepository.PlanResult> eldest) {
            return size() > 20;
        }
    };

    private static class PageLoadRequest {
        final int renderContextVersion;
        final String viewModeId;
        final LocalDate date;

        PageLoadRequest(int renderContextVersion, String viewModeId, LocalDate date) {
            this.renderContextVersion = renderContextVersion;
            this.viewModeId = viewModeId;
            this.date = date;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof PageLoadRequest))
                return false;
            PageLoadRequest other = (PageLoadRequest) o;
            if (renderContextVersion != other.renderContextVersion)
                return false;
            if (viewModeId != null ? !viewModeId.equals(other.viewModeId) : other.viewModeId != null)
                return false;
            return date != null ? date.equals(other.date) : other.date == null;
        }

        @Override
        public int hashCode() {
            int result = renderContextVersion;
            result = 31 * result + (viewModeId != null ? viewModeId.hashCode() : 0);
            result = 31 * result + (date != null ? date.hashCode() : 0);
            return result;
        }
    }

    private final Set<PageLoadRequest> activePageLoads = Collections.synchronizedSet(new HashSet<>());
    private int planRenderContextVersion = 0;

    private void putPlanInCache(String modeId, LocalDate date, PlanRepository.PlanResult result) {
        if (modeId == null || date == null || result == null)
            return;
        planCache.put(new PlanKey(modeId, date), result);
    }

    private PlanRepository.PlanResult getPlanFromCache(String modeId, LocalDate date) {
        if (modeId == null || date == null)
            return null;
        return planCache.get(new PlanKey(modeId, date));
    }

    private void beginPlanRenderContext() {
        planRenderContextVersion++;
        activePageLoads.clear();

        if (viewPager != null) {
            viewPager.animate().cancel();
            viewPager.clearAnimation();
        }
        if (pagerRecyclerView != null) {
            pagerRecyclerView.stopScroll();
            pagerRecyclerView.clearAnimation();
            pagerRecyclerView.getRecycledViewPool().clear();
        }
    }

    private PlanRepository.SearchParams snapshotCurrentSearchQuery() {
        if (currentSearchQuery == null) {
            return null;
        }

        PlanRepository.SearchParams snapshot = new PlanRepository.SearchParams();
        snapshot.category = currentSearchQuery.category;
        snapshot.query = currentSearchQuery.query;
        return snapshot;
    }

    private final Handler nowLineHandler = new Handler(Looper.getMainLooper());
    private final Runnable nowLineRunnable = () -> {
        if (!isAdded() || getView() == null || isMonthMode()) {
            return;
        }
        updateNowLineInVisiblePage();
        scheduleNextNowLineUpdate();
    };
    private final Runnable resumePlanRecoveryRunnable = this::restorePlanContentIfNeeded;

    private List<PlanRepository.DayColumn> getVisibleColumns(List<PlanRepository.DayColumn> allColumns) {
        if (allColumns == null || allColumns.isEmpty()) {
            return Collections.emptyList();
        }

        if (!isWeekMode()) {
            return allColumns;
        }

        int satIndex = -1;
        int sunIndex = -1;
        boolean satHasEvents = false;
        boolean sunHasEvents = false;

        for (int i = 0; i < allColumns.size(); i++) {
            PlanRepository.DayColumn col = allColumns.get(i);
            if (col == null || col.date == null)
                continue;

            DayOfWeek dow = col.date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                satIndex = i;
                satHasEvents = col.events != null && !col.events.isEmpty();
            } else if (dow == DayOfWeek.SUNDAY) {
                sunIndex = i;
                sunHasEvents = col.events != null && !col.events.isEmpty();
            }
        }

        if (satIndex != -1 && sunIndex != -1 && !satHasEvents && !sunHasEvents) {
            List<PlanRepository.DayColumn> filtered = new ArrayList<>();
            for (int i = 0; i < allColumns.size(); i++) {
                if (i == satIndex || i == sunIndex)
                    continue;
                filtered.add(allColumns.get(i));
            }
            return filtered;
        }

        return allColumns;
    }

    private boolean areWeekendsHidden(List<PlanRepository.DayColumn> rawColumns, List<PlanRepository.DayColumn> visibleColumns) {
        if (!isWeekMode() || rawColumns == null || rawColumns.isEmpty() || visibleColumns == null || visibleColumns.isEmpty()) {
            return false;
        }

        boolean rawHasSaturday = false;
        boolean rawHasSunday = false;
        for (PlanRepository.DayColumn col : rawColumns) {
            if (col == null || col.date == null) {
                continue;
            }
            DayOfWeek dow = col.date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                rawHasSaturday = true;
            } else if (dow == DayOfWeek.SUNDAY) {
                rawHasSunday = true;
            }
        }

        boolean visibleHasSaturday = false;
        boolean visibleHasSunday = false;
        for (PlanRepository.DayColumn col : visibleColumns) {
            if (col == null || col.date == null) {
                continue;
            }
            DayOfWeek dow = col.date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                visibleHasSaturday = true;
            } else if (dow == DayOfWeek.SUNDAY) {
                visibleHasSunday = true;
            }
        }

        return rawHasSaturday && rawHasSunday && !visibleHasSaturday && !visibleHasSunday;
    }

    @Override
    @Nullable
    protected Toolbar getTabToolbar() {
        return toolbar;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return ShellLayoutInflater.inflateTabContent(inflater, R.layout.activity_plan, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootView = view;

        planRepository = new PlanRepository(requireContext().getApplicationContext());
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenSubjectKeys = new HashSet<>(prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>()));
        purgeExpiredFilterCaches();

        LinearLayout drawerContentRoot = tabFind(R.id.drawerContentRoot);
        CoordinatorLayout planCoordinatorRoot = tabFind(R.id.planCoordinatorRoot);
        toolbar = tabFind(R.id.toolbar);
        FloatingActionButton fabAddEvent = tabFind(R.id.fabAddEvent);

        shellActivity().setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(shellActivity(), toolbar);

        ScrollView scrollPlan = tabFind(R.id.scrollPlan);
        scrollPlan.setClipToPadding(false);
        final int fabMarginLeft;
        final int fabMarginTop;
        final int fabMarginRight;
        final int fabMarginBottom;
        if (fabAddEvent != null && fabAddEvent.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) fabAddEvent.getLayoutParams();
            fabMarginLeft = lp.leftMargin;
            fabMarginTop = lp.topMargin;
            fabMarginRight = lp.rightMargin;
            fabMarginBottom = lp.bottomMargin;
        } else {
            fabMarginLeft = 0;
            fabMarginTop = 0;
            fabMarginRight = 0;
            fabMarginBottom = 0;
        }

        View insetHost = planCoordinatorRoot != null ? planCoordinatorRoot : drawerContentRoot;
        if (insetHost != null && fabAddEvent != null) {
            ViewCompat.setOnApplyWindowInsetsListener(insetHost, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                if (fabAddEvent.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp =
                            (ViewGroup.MarginLayoutParams) fabAddEvent.getLayoutParams();
                    lp.leftMargin = fabMarginLeft + insets.left;
                    lp.topMargin = fabMarginTop;
                    lp.rightMargin = fabMarginRight + insets.right;
                    lp.bottomMargin = fabMarginBottom + insets.bottom;
                    fabAddEvent.setLayoutParams(lp);
                }
                return windowInsets;
            });
            NavigationBarView shellNav = shellActivity().getShellNavigation();
            if (shellNav != null) {
                shellNav.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        ViewCompat.requestApplyInsets(insetHost));
            }
            ViewCompat.requestApplyInsets(insetHost);
        }

        toolbar.setTitle("");
        toolbar.setSubtitle(null);

        btnViewDay = tabFind(R.id.btnViewDay);
        btnViewWeek = tabFind(R.id.btnViewWeek);
        btnViewMonth = tabFind(R.id.btnViewMonth);
        btnSearch = tabFind(R.id.btnSearch);
        btnRefresh = tabFind(R.id.btnRefresh);
        btnMenu = tabFind(R.id.btnMenu);

        tvHeaderLabel = tabFind(R.id.tvHeaderLabel);
        tvPlanToolbarSubtitle = tabFind(R.id.tvPlanToolbarSubtitle);
        updatePlanDataFreshness(false);

        layoutTimeColumn = tabFind(R.id.layoutTimeColumn);
        progress = tabFind(R.id.planProgress);

        layoutWeekHeadersFixed = tabFind(R.id.layoutWeekHeadersFixed);
        layoutWeekHeadersRow = tabFind(R.id.layoutWeekHeadersRow);
        layoutMonthHeadersFixed = tabFind(R.id.layoutMonthHeadersFixed);

        viewPager = tabFind(R.id.planViewPager);

        setupWeekdayHeadersForMonth();
        setupTimeColumn();
        setupViewModeButtons();
        setupMenuButton();
        setupSearchButton();
        setupRefreshButton();
        setupBackNavigation();

        if (viewPager != null) {
            setupHeightForViewPager();
            pagerAdapter = new PlanPagerAdapter(requireContext());
            viewPager.setOffscreenPageLimit(ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT);
            viewPager.setNestedScrollingEnabled(false);
            View pagerChild = viewPager.getChildAt(0);
            if (pagerChild instanceof RecyclerView) {
                pagerRecyclerView = (RecyclerView) pagerChild;
                pagerRecyclerView.setItemAnimator(null);
                pagerRecyclerView.setItemViewCacheSize(0);
                pagerRecyclerView.getRecycledViewPool().setMaxRecycledViews(0, 1);
            }

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    updateCurrentDateFromPosition(position);

                    if (!isMonthMode()) {
                        updateNowLineInVisiblePage();
                    }

                    PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, currentDate);
                    if (cached != null) {
                        if (isMonthMode()) {
                            if (cached.headerLabel != null)
                                tvHeaderLabel.setText(cached.headerLabel);
                        } else {
                            updateFixedWeekHeaders(cached.dayColumns);
                        }
                        updatePlanDataFreshness(false);
                    } else {
                        if (!isMonthMode()) {
                            updateFixedWeekHeaders(Collections.emptyList());
                        }
                    }
                }
            });

            ViewTreeObserver pagerObserver = viewPager.getViewTreeObserver();
            pagerObserver.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (viewPager == null || viewPager.getWidth() <= 0) {
                        return true;
                    }
                    ViewTreeObserver observer = viewPager.getViewTreeObserver();
                    if (observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }
                    attachMeasuredPlanPagerAdapter();
                    return false;
                }
            });
        }

        if (savedInstanceState != null) {
            String vm = savedInstanceState.getString("viewMode", viewModeId);
            viewModeId = vm != null ? vm : ViewMode.WEEK.getId();

            String dateStr = savedInstanceState.getString("currentDate", null);
            if (dateStr != null) {
                try {
                    currentDate = LocalDate.parse(dateStr, YMD);
                    baseDate = currentDate;
                    if (viewPager != null) {
                        viewPager.setCurrentItem(VP_START_POSITION, false);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        String shortcutAction = requireActivity().getIntent()
                .getStringExtra(AppShortcutRouter.EXTRA_SHORTCUT_ACTION);
        if (AppShortcutRouter.isPlanAction(shortcutAction)) {
            requireActivity().getIntent().removeExtra(AppShortcutRouter.EXTRA_SHORTCUT_ACTION);
            applyLauncherShortcut(shortcutAction, false);
        }
        updateViewModeButtonsUi();

        // Handle Search Intent (New Action)
        if (requireActivity().getIntent().hasExtra("EXTRA_SEARCH_QUERY")) {
            String q = requireActivity().getIntent().getStringExtra("EXTRA_SEARCH_QUERY");
            String c = requireActivity().getIntent().getStringExtra("EXTRA_SEARCH_CATEGORY");
            requireActivity().getIntent().removeExtra("EXTRA_SEARCH_QUERY");
            requireActivity().getIntent().removeExtra("EXTRA_SEARCH_CATEGORY");
            if (q != null && !q.isEmpty()) {
                currentSearchQuery = new PlanRepository.SearchParams();
                currentSearchQuery.query = q;
                currentSearchQuery.category = c;
                String toast = getString(R.string.plan_toast_search_prefix, q, c != null ? c : "");
                Toast.makeText(requireContext(), toast.trim(), Toast.LENGTH_SHORT).show();
            }
        }

        setupFab();

        // Load session dates in background
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                    appContext,
                    NetworkRefreshPolicy.Module.SESSION,
                    NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                    "academic_calendar",
                    0L);
            List<PlanRepository.SessionPeriod> sessions;
            if (decision.allowNetwork) {
                NetworkRefreshPolicy.recordAttempt(
                        appContext,
                        NetworkRefreshPolicy.Module.SESSION,
                        NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                        "academic_calendar");
                sessions = planRepository.fetchSessionDates();
                if (sessions != null && !sessions.isEmpty()) {
                    NetworkRefreshPolicy.recordSuccess(
                            appContext,
                            NetworkRefreshPolicy.Module.SESSION,
                            "academic_calendar");
                }
            } else {
                sessions = PlanRepository.getCachedSessionDates(appContext);
            }
            if (sessions != null && !sessions.isEmpty()) {
                sessionDates = sessions;
                // Refresh displayed pages so session markers appear
                handler.post(this::notifyAllPlanPagesChanged);
            }
        });

        loadPlanForCurrentMode();
        runIntroAnimations();
        achievementViewedModes.add(viewModeId);
        handler.postDelayed(this::maybeUnlockAmbientPlanAchievement, 900L);
    }

    public void handleLauncherShortcut(@NonNull String action) {
        applyLauncherShortcut(action, true);
    }

    private void applyLauncherShortcut(@Nullable String action, boolean reload) {
        if (AppShortcutRouter.ACTION_PLAN_TODAY.equals(action)) {
            setCurrentViewMode(ViewMode.DAY);
            currentSearchQuery = null;
            currentDate = LocalDate.now();
            baseDate = currentDate;
            updateViewModeButtonsUi();
            if (viewPager != null) {
                viewPager.setCurrentItem(VP_START_POSITION, false);
            }
            if (reload) {
                planCache.clear();
                loadPlanForCurrentMode();
            }
        } else if (AppShortcutRouter.ACTION_PLAN_SEARCH.equals(action)) {
            handler.postDelayed(this::showSearchDialog, 120L);
        }
    }

    private void setupFab() {
        com.google.android.material.floatingactionbutton.FloatingActionButton fab = tabFind(R.id.fabAddEvent);
        if (fab != null) {
            fab.setVisibility(View.GONE);
        }
    }

    private void showAddCustomEventDialog() {
        int startMin = getDefaultStartMin();
        int[] range = buildDefaultRange(startMin);
        LocalDate date = currentDate != null ? currentDate : LocalDate.now();
        AddCustomEventDialog dialog = AddCustomEventDialog.newForSlot(date, range[0], range[1]);
        dialog.setListener(event -> refreshAfterCustomEvent());
        dialog.show(getParentFragmentManager(), "add_custom_event");
    }

    private void runIntroAnimations() {
        View[] topControls = { btnViewDay, btnViewWeek, btnViewMonth };
        View[] actions = { tvHeaderLabel, btnSearch, btnRefresh, btnMenu };
        View mainContent = viewPager;
        View sideColumn = layoutTimeColumn;
        View headerRow = layoutWeekHeadersFixed;

        // Reset
        for (View v : topControls)
            if (v != null) {
                v.setAlpha(0f);
                v.setTranslationY(-20f);
            }
        for (View v : actions)
            if (v != null) {
                v.setAlpha(0f);
                v.setTranslationY(-20f);
            }
        if (mainContent != null) {
            mainContent.setAlpha(0f);
            mainContent.setTranslationY(50f);
        }
        if (sideColumn != null) {
            sideColumn.setAlpha(0f);
            sideColumn.setTranslationX(-30f);
        }
        if (headerRow != null) {
            headerRow.setAlpha(0f);
            headerRow.setTranslationY(-20f);
        }

        long delay = 100;

        for (View v : topControls) {
            if (v != null)
                animateIn(v, delay);
            delay += 50;
        }

        delay = 200;
        for (View v : actions) {
            if (v != null)
                animateIn(v, delay);
            delay += 50;
        }

        if (headerRow != null)
            animateIn(headerRow, 300);
        if (sideColumn != null)
            animateIn(sideColumn, 350);
        if (mainContent != null)
            animateIn(mainContent, 400);
    }

    private void animateIn(View v, long delayMs) {
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .translationX(0f)
                .setStartDelay(delayMs)
                .setDuration(400)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    private void setupHeightForViewPager() {
        if (viewPager == null)
            return;

        int gridHeight;

        if (isMonthMode()) {
            gridHeight = dpToPx(6 * MONTH_CELL_HEIGHT_DP + 16);
            if (layoutTimeColumn != null)
                layoutTimeColumn.setVisibility(View.GONE);
            if (layoutWeekHeadersFixed != null)
                layoutWeekHeadersFixed.setVisibility(View.GONE);
            if (layoutMonthHeadersFixed != null)
                layoutMonthHeadersFixed.setVisibility(View.VISIBLE);

        } else {
            float totalHours = END_HOUR - START_HOUR;
            gridHeight = dpToPx(totalHours * HOUR_HEIGHT_DP + DAY_HEADER_HEIGHT_DP + 8);

            if (layoutTimeColumn != null)
                layoutTimeColumn.setVisibility(View.VISIBLE);
            if (layoutTimeColumn != null) {
                ViewGroup.LayoutParams tp = layoutTimeColumn.getLayoutParams();
                if (tp != null) {
                    tp.height = gridHeight;
                    layoutTimeColumn.setLayoutParams(tp);
                }
            }
            if (layoutWeekHeadersFixed != null)
                layoutWeekHeadersFixed.setVisibility(View.VISIBLE);
            if (layoutMonthHeadersFixed != null)
                layoutMonthHeadersFixed.setVisibility(View.GONE);
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) viewPager.getLayoutParams();
        if (params == null) {
            params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    gridHeight);
        } else {
            params.height = gridHeight;
        }
        viewPager.setLayoutParams(params);
    }

    @Override
    public void onResume() {
        super.onResume();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        handler.removeCallbacks(resumePlanRecoveryRunnable);
        if (!isMonthMode()) {
            updateNowLineInVisiblePage();
            scheduleNextNowLineUpdate();
        }
        handler.postDelayed(resumePlanRecoveryRunnable, 520L);
    }

    @Override
    public void onPause() {
        super.onPause();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        handler.removeCallbacks(resumePlanRecoveryRunnable);
    }

    @Override
    public void onDestroyView() {
        nowLineHandler.removeCallbacks(nowLineRunnable);
        handler.removeCallbacks(resumePlanRecoveryRunnable);
        if (mRefreshAnimator != null) {
            mRefreshAnimator.cancel();
            mRefreshAnimator = null;
        }
        if (viewPager != null) {
            viewPager.setAdapter(null);
        }
        pagerAdapter = null;
        pagerRecyclerView = null;
        super.onDestroyView();
    }

    private void attachMeasuredPlanPagerAdapter() {
        if (!isAdded() || viewPager == null || pagerAdapter == null || viewPager.getAdapter() != null) {
            return;
        }
        viewPager.setAdapter(pagerAdapter);
        viewPager.setCurrentItem(VP_START_POSITION, false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("viewMode", viewModeId);
        outState.putString("currentDate", currentDate.format(YMD));
    }

    private ViewMode getCurrentViewMode() {
        return ViewMode.fromId(viewModeId);
    }

    private void setCurrentViewMode(ViewMode mode) {
        if (mode != null)
            viewModeId = mode.getId();
    }

    private boolean isDayMode() {
        return getCurrentViewMode() == ViewMode.DAY;
    }

    private boolean isWeekMode() {
        return getCurrentViewMode() == ViewMode.WEEK;
    }

    private boolean isMonthMode() {
        return getCurrentViewMode() == ViewMode.MONTH;
    }

    private LocalDate normalizeAnchorDateForMode(LocalDate date, ViewMode mode) {
        LocalDate safeDate = date != null ? date : LocalDate.now();
        if (mode == null) {
            return safeDate;
        }
        if (mode == ViewMode.MONTH) {
            return safeDate.with(TemporalAdjusters.firstDayOfMonth());
        }
        if (mode == ViewMode.WEEK && safeDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return safeDate.plusDays(1);
        }
        return safeDate;
    }

    private void normalizeCurrentModeAnchor() {
        ViewMode mode = getCurrentViewMode();
        LocalDate anchor = currentDate != null ? currentDate : baseDate;
        anchor = normalizeAnchorDateForMode(anchor, mode);
        currentDate = anchor;
        baseDate = anchor;
    }

    private void updateCurrentDateFromPosition(int position) {
        int diff = position - VP_START_POSITION;
        LocalDate newDate;

        if (isDayMode()) {
            newDate = baseDate.plusDays(diff);
        } else if (isWeekMode()) {
            newDate = baseDate.plusWeeks(diff);
        } else if (isMonthMode()) {
            newDate = baseDate.plusMonths(diff).with(TemporalAdjusters.firstDayOfMonth());
        } else {
            return;
        }

        currentDate = newDate;

        PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, currentDate);
        if (cached != null && cached.headerLabel != null) {
            tvHeaderLabel.setText(cached.headerLabel);
            updatePlanDataFreshness(false);
        } else {
            if (isMonthMode()) {
                tvHeaderLabel.setText(currentDate.format(DateTimeFormatter.ofPattern("MM.yyyy")));
            } else {
                tvHeaderLabel.setText(currentDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
            }
        }
    }

    private void loadPlanForCurrentMode() {
        updateViewModeButtonsUi();
        beginPlanRenderContext();
        normalizeCurrentModeAnchor();

        setupHeightForViewPager();

        if (viewPager != null) {
            int pos = viewPager.getCurrentItem();
            if (pos == VP_START_POSITION) {
                updateCurrentDateFromPosition(pos);
            } else {
                viewPager.setCurrentItem(VP_START_POSITION, false);
                updateCurrentDateFromPosition(VP_START_POSITION);
            }
        }

        notifyAllPlanPagesChanged();

        if (viewPager != null) {
            PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, currentDate);
            if (cached != null) {
                if (!isMonthMode())
                    updateFixedWeekHeaders(cached.dayColumns);
                if (cached.headerLabel != null)
                    tvHeaderLabel.setText(cached.headerLabel);
                updatePlanDataFreshness(false);
            } else {
                if (!isMonthMode())
                    updateFixedWeekHeaders(Collections.emptyList());
            }
        }
    }

    private void setupViewModeButtons() {
        View.OnClickListener modeClick = v -> {
            ViewMode newMode = ViewMode.WEEK;
            if (v == btnViewDay)
                newMode = ViewMode.DAY;
            else if (v == btnViewMonth)
                newMode = ViewMode.MONTH;

            if (!newMode.getId().equals(viewModeId)) {
                setCurrentViewMode(newMode);
                LocalDate anchor = normalizeAnchorDateForMode(currentDate, newMode);
                currentDate = anchor;
                baseDate = anchor;
                if (viewPager != null) {
                    viewPager.setCurrentItem(VP_START_POSITION, false);
                }
                loadPlanForCurrentMode();
            }
            recordViewedPlanMode(newMode);
        };

        btnViewDay.setOnClickListener(modeClick);
        btnViewWeek.setOnClickListener(modeClick);
        btnViewMonth.setOnClickListener(modeClick);
        updateViewModeButtonsUi();
    }

    private void updateViewModeButtonsUi() {
        ViewMode mode = getCurrentViewMode();
        btnViewDay.setSelected(mode == ViewMode.DAY);
        btnViewWeek.setSelected(mode == ViewMode.WEEK);
        btnViewMonth.setSelected(mode == ViewMode.MONTH);
    }

    private void setupMenuButton() {
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> {
                PopupMenu popup = ZutnikPopupMenu.create(requireContext(), v);

                popup.getMenu().add(0, 1, 0, R.string.plan_button_today);
                popup.getMenu().add(0, 2, 1, R.string.plan_button_add_event);
                popup.getMenu().add(0, 3, 2, R.string.plan_button_filters);
                popup.getMenu().add(0, 4, 3, R.string.plan_button_history);
                popup.getMenu().add(0, 5, 4, R.string.plan_button_calendar_export);

                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1:
                            goToToday();
                            return true;
                        case 2:
                            showAddCustomEventDialog();
                            return true;
                        case 3:
                            loadSubjectsForFilterAsync();
                            return true;
                        case 4:
                            startActivity(new Intent(requireContext(), PlanChangeHistoryActivity.class));
                            return true;
                        case 5:
                            startActivity(buildCalendarExportIntent());
                            return true;
                        default:
                            return false;
                    }
                });
                popup.show();
            });
        }
    }

    private void goToToday() {
        LocalDate anchor = normalizeAnchorDateForMode(LocalDate.now(), getCurrentViewMode());
        currentDate = anchor;
        baseDate = anchor;

        if (viewPager != null) {
            viewPager.setCurrentItem(VP_START_POSITION, true);
        }
        notifyAllPlanPagesChanged();
    }

    private Intent buildCalendarExportIntent() {
        Intent intent = new Intent(requireContext(), CalendarExportActivity.class);
        intent.putExtra(PlanCalendarExportHelper.EXTRA_VIEW_MODE, viewModeId);
        intent.putExtra(PlanCalendarExportHelper.EXTRA_CURRENT_DATE, currentDate != null ? currentDate.toString() : "");
        intent.putStringArrayListExtra(
                PlanCalendarExportHelper.EXTRA_HIDDEN_SUBJECT_KEYS,
                new ArrayList<>(hiddenSubjectKeys));
        if (currentSearchQuery != null) {
            intent.putExtra(
                    PlanCalendarExportHelper.EXTRA_SEARCH_CATEGORY,
                    currentSearchQuery.category != null ? currentSearchQuery.category : "");
            intent.putExtra(
                    PlanCalendarExportHelper.EXTRA_SEARCH_QUERY,
                    currentSearchQuery.query != null ? currentSearchQuery.query : "");
        }
        return intent;
    }

    private void setupRefreshButton() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                        requireContext(),
                        NetworkRefreshPolicy.Module.PLAN,
                        NetworkRefreshPolicy.Mode.MANUAL,
                        viewModeId,
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
                        NetworkRefreshPolicy.Module.PLAN,
                        NetworkRefreshPolicy.Mode.MANUAL,
                        viewModeId);
                startRefreshAnimation();
                updatePlanDataFreshnessText(getString(R.string.data_status_syncing));
                if (currentSearchQuery != null) {
                    currentSearchQuery = null;
                    Toast.makeText(requireContext(), R.string.plan_toast_resetting_search, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.plan_toast_refreshed, Toast.LENGTH_SHORT).show();
                }
                synchronized (forceRefreshLock) {
                    pendingForceRefresh = true;
                    pendingForceRefreshDate = currentDate;
                    pendingForceRefreshMode = viewModeId;
                }
                planCache.clear();
                loadPlanForCurrentMode();
            });
        }
    }

    private void setupBackNavigation() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (exitSearchMode(false)) {
                    return;
                }
                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    private boolean exitSearchMode(boolean showToast) {
        if (currentSearchQuery == null) {
            return false;
        }

        currentSearchQuery = null;
        if (showToast) {
            Toast.makeText(requireContext(), R.string.plan_toast_resetting_search, Toast.LENGTH_SHORT).show();
        }
        planCache.clear();
        loadPlanForCurrentMode();
        return true;
    }

    private void restorePlanContentIfNeeded() {
        if (!isAdded() || getActivity() == null || requireActivity().isFinishing() || viewPager == null || pagerAdapter == null) {
            return;
        }

        // Reset any half-finished page animations after returning to the screen.
        viewPager.animate().cancel();
        viewPager.setAlpha(1f);
        viewPager.setScaleX(1f);
        viewPager.setScaleY(1f);
        viewPager.setTranslationX(0f);
        viewPager.setTranslationY(0f);

        if (layoutTimeColumn != null) {
            layoutTimeColumn.animate().cancel();
            layoutTimeColumn.setAlpha(1f);
            layoutTimeColumn.setTranslationX(0f);
        }
        if (layoutWeekHeadersFixed != null) {
            layoutWeekHeadersFixed.animate().cancel();
            layoutWeekHeadersFixed.setAlpha(1f);
            layoutWeekHeadersFixed.setTranslationY(0f);
        }

        final int currentItem = viewPager.getCurrentItem();
        viewPager.post(() -> {
            if (!isAdded() || getActivity() == null || requireActivity().isFinishing() || pagerAdapter == null) {
                return;
            }

            boolean needsRefresh = false;
            if (pagerRecyclerView != null) {
                for (int i = 0; i < pagerRecyclerView.getChildCount(); i++) {
                    View child = pagerRecyclerView.getChildAt(i);
                    if (child == null) {
                        continue;
                    }
                    child.animate().cancel();
                    child.setAlpha(1f);
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                    child.setTranslationX(0f);
                    child.setTranslationY(0f);
                }

                RecyclerView.ViewHolder holder = pagerRecyclerView.findViewHolderForAdapterPosition(currentItem);
                if (!(holder instanceof PlanPageViewHolder)) {
                    needsRefresh = true;
                } else {
                    FrameLayout container = ((PlanPageViewHolder) holder).container;
                    needsRefresh = container == null
                            || container.getChildCount() == 0
                            || container.getAlpha() < 0.1f
                            || container.getWidth() == 0
                            || container.getHeight() == 0;
                    if (!needsRefresh) {
                        cancelPlanPageLoadingState(container);
                        container.setAlpha(1f);
                        container.setScaleX(1f);
                        container.setScaleY(1f);
                    }
                }
            } else {
                needsRefresh = true;
            }

            if (needsRefresh) {
                requestPageRefresh(currentItem);
            }
        });
    }

    private void updatePlanDataFreshness(boolean fetchedFromNetwork) {
        if (prefs == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (fetchedFromNetwork) {
            prefs.edit().putLong(KEY_PLAN_LAST_NETWORK_SYNC_TS, now).apply();
            updatePlanDataFreshnessText(getString(R.string.data_status_online_now));
            return;
        }

        long lastNetworkTs = prefs.getLong(KEY_PLAN_LAST_NETWORK_SYNC_TS, 0L);
        if (lastNetworkTs > 0L) {
            if ((now - lastNetworkTs) < DateUtils.MINUTE_IN_MILLIS) {
                updatePlanDataFreshnessText(getString(R.string.data_status_online_now));
                return;
            }
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    lastNetworkTs,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            updatePlanDataFreshnessText(getString(R.string.data_status_cache_since, rel));
        } else {
            updatePlanDataFreshnessText(getString(R.string.data_status_cache));
        }
    }

    private void updatePlanDataFreshnessText(String text) {
        String safe = text != null ? text : "";
        if (tvPlanToolbarSubtitle != null) {
            tvPlanToolbarSubtitle.setText(safe);
            return;
        }
        if (toolbar != null) {
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

    private void setupSearchButton() {
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
    }

    // Saved searches

    private void showSearchDialog() {
        View layout = getLayoutInflater().inflate(R.layout.dialog_plan_search, null);
        View searchDialogScroll = layout.findViewById(R.id.searchDialogScroll);

        TextInputLayout inputCategoryLayout = layout.findViewById(R.id.inputCategoryLayout);
        MaterialAutoCompleteTextView categoryView = layout.findViewById(R.id.editCategory);
        TextInputEditText input = layout.findViewById(R.id.editQuery);
        ProgressBar loadingIndicator = layout.findViewById(R.id.loadingIndicator);
        RecyclerView suggestionsRecycler = layout.findViewById(R.id.suggestionsRecycler);
        ImageButton btnClipboard = layout.findViewById(R.id.btnSavedSearches);
        Button btnSaveQuery = layout.findViewById(R.id.btnSaveQuery);

        if (searchDialogScroll != null) {
            final int scrollPaddingLeft = searchDialogScroll.getPaddingLeft();
            final int scrollPaddingTop = searchDialogScroll.getPaddingTop();
            final int scrollPaddingRight = searchDialogScroll.getPaddingRight();
            final int scrollPaddingBottom = searchDialogScroll.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(searchDialogScroll, (view, windowInsets) -> {
                Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                view.setPadding(
                        scrollPaddingLeft,
                        scrollPaddingTop,
                        scrollPaddingRight,
                        scrollPaddingBottom + ime.bottom);
                return windowInsets;
            });
        }

        String[] displayCategories = {
                getString(R.string.plan_search_cat_album),
                getString(R.string.plan_search_cat_teacher),
                getString(R.string.plan_search_cat_room),
                getString(R.string.plan_search_cat_subject),
                getString(R.string.plan_search_cat_group)
        };
        final String[] categoryKeys = {
                "album",
                "teacher",
                "room",
                "subject",
                "group"
        };

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), R.layout.item_dropdown_category,
                displayCategories) {
            @Override
            @NonNull
            public android.widget.Filter getFilter() {
                return new android.widget.Filter() {
                    @Override
                    protected android.widget.Filter.FilterResults performFiltering(CharSequence constraint) {
                        android.widget.Filter.FilterResults results = new android.widget.Filter.FilterResults();
                        results.values = Arrays.asList(displayCategories);
                        results.count = displayCategories.length;
                        return results;
                    }

                    @Override
                    protected void publishResults(CharSequence constraint, android.widget.Filter.FilterResults results) {
                        notifyDataSetChanged();
                    }
                };
            }
        };
        categoryView.setAdapter(categoryAdapter);
        categoryView.setThreshold(0);
        categoryView.setText(displayCategories[0], false);
        categoryView.setInputType(android.text.InputType.TYPE_NULL);
        categoryView.setCursorVisible(false);
        categoryView.setFocusable(false);
        categoryView.setFocusableInTouchMode(false);
        categoryView.setClickable(true);

        final int[] selectedCategory = { 0 };
        categoryView.setDropDownBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_list));
        categoryView.setOnClickListener(v -> categoryView.showDropDown());
        if (inputCategoryLayout != null) {
            inputCategoryLayout.setEndIconOnClickListener(v -> categoryView.showDropDown());
        }

        if (loadingIndicator != null) {
            loadingIndicator.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(ThemeManager.resolveColor(requireContext(), R.attr.mzPrimary)));
        }

        suggestionsRecycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(requireContext()));

        int itemHeight = dpToPx(48);
        int maxHeight = (int) (itemHeight * 2.5f);
        ViewGroup.LayoutParams recyclerLp = suggestionsRecycler.getLayoutParams();
        recyclerLp.height = maxHeight;
        suggestionsRecycler.setLayoutParams(recyclerLp);
        suggestionsRecycler.setNestedScrollingEnabled(true);

        final List<String> suggestionsList = new ArrayList<>();

        final RecyclerView.Adapter<RecyclerView.ViewHolder> suggestionsAdapter = new RecyclerView.Adapter<>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View itemView = getLayoutInflater().inflate(R.layout.item_search_suggestion, parent, false);
                return new RecyclerView.ViewHolder(itemView) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextView textView = holder.itemView.findViewById(R.id.suggestionText);
                String suggestion = suggestionsList.get(position);
                textView.setText(suggestion);

                boolean isPlaceholder = suggestion.equals(getString(R.string.plan_search_no_suggestions))
                        || suggestion.equals(getString(R.string.plan_search_album_no_autocomplete))
                        || suggestion.equals(getString(R.string.plan_search_type_to_search));

                holder.itemView.setOnClickListener(v -> {
                    if (!isPlaceholder) {
                        input.setText(suggestion);
                        input.setSelection(suggestion.length());
                    }
                });

                textView.setAlpha(isPlaceholder ? 0.55f : 1.0f);
            }

            @Override
            public int getItemCount() {
                return suggestionsList.size();
            }
        };

        suggestionsRecycler.setAdapter(suggestionsAdapter);

        Runnable refreshPlaceholder = () -> {
            int pos = selectedCategory[0];
            List<String> nextItems = new ArrayList<>();
            if (pos == 0) {
                nextItems.add(getString(R.string.plan_search_album_no_autocomplete));
            } else {
                String currentText = input.getText() != null ? input.getText().toString().trim() : "";
                if (currentText.isEmpty()) {
                    nextItems.add(getString(R.string.plan_search_type_to_search));
                }
            }
            replaceSuggestionItems(suggestionsList, nextItems, suggestionsAdapter);
        };

        categoryView.setOnItemClickListener((parent, view, position, id) -> {
            selectedCategory[0] = position;
            refreshPlaceholder.run();
        });
        categoryView.setOnClickListener(v -> categoryView.showDropDown());
        if (inputCategoryLayout != null) {
            inputCategoryLayout.setEndIconOnClickListener(v -> categoryView.showDropDown());
        }

        refreshPlaceholder.run();

        if (searchDialogScroll instanceof ViewGroup) {
            input.setOnFocusChangeListener((view, hasFocus) -> {
                if (!hasFocus) {
                    return;
                }
                searchDialogScroll.post(() -> {
                    android.graphics.Rect rect = new android.graphics.Rect();
                    view.getDrawingRect(rect);
                    int extraSpace = dpToPx(24);
                    rect.top = Math.max(0, rect.top - extraSpace);
                    rect.bottom += extraSpace;
                    ((ViewGroup) searchDialogScroll).requestChildRectangleOnScreen(view, rect, true);
                });
            });
        }

        final Handler debounceHandler = new Handler(Looper.getMainLooper());
        final Runnable[] fetchRunnable = { null };
        final boolean[] isDialogDismissed = { false };

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (fetchRunnable[0] != null) {
                    debounceHandler.removeCallbacks(fetchRunnable[0]);
                }

                String query = s.toString().trim();
                int pos = selectedCategory[0];

                if (pos == 0) {
                    if (loadingIndicator != null) {
                        loadingIndicator.setVisibility(View.GONE);
                    }
                    List<String> nextItems = new ArrayList<>();
                    nextItems.add(getString(R.string.plan_search_album_no_autocomplete));
                    replaceSuggestionItems(suggestionsList, nextItems, suggestionsAdapter);
                    return;
                }

                if (query.isEmpty()) {
                    if (loadingIndicator != null) {
                        loadingIndicator.setVisibility(View.GONE);
                    }
                    List<String> nextItems = new ArrayList<>();
                    nextItems.add(getString(R.string.plan_search_type_to_search));
                    replaceSuggestionItems(suggestionsList, nextItems, suggestionsAdapter);
                    return;
                }

                String kind = categoryKeys[pos];
                if (loadingIndicator != null) {
                    loadingIndicator.setVisibility(View.VISIBLE);
                }

                fetchRunnable[0] = () -> executor.execute(() -> {
                    try {
                        List<String> suggestions = planRepository.fetchSearchSuggestions(kind, query);
                        handler.post(() -> {
                            if (isDialogDismissed[0]) {
                                return;
                            }

                            if (loadingIndicator != null) {
                                loadingIndicator.setVisibility(View.GONE);
                            }
                            List<String> nextItems = new ArrayList<>();
                            if (suggestions.isEmpty()) {
                                nextItems.add(getString(R.string.plan_search_no_suggestions));
                            } else {
                                nextItems.addAll(suggestions);
                            }
                            replaceSuggestionItems(suggestionsList, nextItems, suggestionsAdapter);
                        });
                    } catch (Exception e) {
                        handler.post(() -> {
                            if (loadingIndicator != null) {
                                loadingIndicator.setVisibility(View.GONE);
                            }
                        });
                        android.util.Log.w("PlanActivity", "Failed to fetch search suggestions", e);
                    }
                });
                debounceHandler.postDelayed(fetchRunnable[0], 300);
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZUTnik_AlertDialog_Dark)
                .setTitle(R.string.plan_search_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.plan_search_button, (d, which) -> {
                    int pos = selectedCategory[0];
                    String categoryKey = (pos >= 0 && pos < categoryKeys.length) ? categoryKeys[pos]
                            : categoryKeys[0];
                    String categoryLabel = (pos >= 0 && pos < displayCategories.length) ? displayCategories[pos]
                            : displayCategories[0];
                    String query = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!query.isEmpty()) {
                        performSearch(categoryKey, categoryLabel, query);
                    }
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .create();

        // Set background BEFORE show() to avoid visual flash
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_rounded_dark));
        }

        dialog.setOnDismissListener(d -> {
            isDialogDismissed[0] = true;
            if (fetchRunnable[0] != null) {
                debounceHandler.removeCallbacks(fetchRunnable[0]);
            }
        });

        btnClipboard.setOnClickListener(v -> {
            dialog.dismiss();
            showSavedSearchesDialog();
        });

        btnSaveQuery.setOnClickListener(v -> {
            String q = input.getText() != null ? input.getText().toString().trim() : "";
            if (q.isEmpty()) {
                Toast.makeText(requireContext(), R.string.plan_search_empty_query, Toast.LENGTH_SHORT).show();
                return;
            }
            int pos = selectedCategory[0];
            String catKey = (pos >= 0 && pos < categoryKeys.length) ? categoryKeys[pos] : categoryKeys[0];
            String catLabel = (pos >= 0 && pos < displayCategories.length) ? displayCategories[pos]
                    : displayCategories[0];

            showSaveQueryDialog(catKey, catLabel, q);
            dialog.dismiss();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (searchDialogScroll != null) {
            ViewCompat.requestApplyInsets(searchDialogScroll);
        }
    }

    private void showSaveQueryDialog(String catKey, String catLabel, String query) {
        EditText inputLabel = new EditText(requireContext());
        inputLabel.setHint(R.string.plan_search_save_hint_label);

        int pad = dpToPx(20);
        LinearLayout container = new LinearLayout(requireContext());
        container.setPadding(pad, pad, pad, pad);
        container.addView(inputLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_search_save_title)
                .setView(container)
                .setPositiveButton(R.string.plan_search_save_confirm, (d, w) -> {
                    String label = inputLabel.getText().toString().trim();
                    if (label.isEmpty())
                        label = query; // fallback
                    saveSearchQuery(label, catKey, catLabel, query);
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .show();
    }

    private void saveSearchQuery(String label, String catKey, String catLabel, String query) {
        List<PlanRepository.SavedSearch> list = PlanRepository.loadSavedSearches(requireContext());
        list.add(new PlanRepository.SavedSearch(label, catKey, catLabel, query));
        PlanRepository.saveSavedSearches(requireContext(), list);
        Toast.makeText(requireContext(), R.string.plan_search_saved_toast, Toast.LENGTH_SHORT).show();
    }

    // -- Saved Search Persistence --

    private void showSavedSearchesDialog() {
        List<PlanRepository.SavedSearch> list = PlanRepository.loadSavedSearches(requireContext());
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), R.string.plan_search_no_saved, Toast.LENGTH_SHORT).show();
            showSearchDialog(); // Go back
            return;
        }

        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = list.get(i).label + " (" + list.get(i).catLabel + ": " + list.get(i).query + ")";
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_search_saved_title)
                .setItems(items, (d, which) -> {
                    PlanRepository.SavedSearch s = list.get(which);
                    performSearch(s.catKey, s.catLabel, s.query);
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .setNeutralButton(R.string.plan_search_saved_clear, (d, w) -> {
                    PlanRepository.saveSavedSearches(requireContext(), new ArrayList<>());
                    Toast.makeText(requireContext(), R.string.plan_search_saved_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void performSearch(String categoryKey, String categoryLabel, String query) {
        currentSearchQuery = new PlanRepository.SearchParams();
        currentSearchQuery.category = categoryKey;
        currentSearchQuery.query = query;

        boolean revealedAchievement = maybeUnlockOwnAlbumAchievement(categoryKey, query);

        planCache.clear();
        loadPlanForCurrentMode();

        if (!revealedAchievement) {
            String msg = getString(R.string.plan_toast_search_prefix, categoryLabel, query);
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    public void applyExternalSearch(String categoryKey, String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        String normalizedCategory = categoryKey == null || categoryKey.trim().isEmpty()
                ? "album"
                : categoryKey.trim();
        performSearch(normalizedCategory, categoryLabel(normalizedCategory), query.trim());
    }

    private String categoryLabel(String categoryKey) {
        switch (categoryKey) {
            case "teacher":
                return getString(R.string.plan_search_cat_teacher);
            case "room":
                return getString(R.string.plan_search_cat_room);
            case "subject":
                return getString(R.string.plan_search_cat_subject);
            case "group":
                return getString(R.string.plan_search_cat_group);
            case "album":
            default:
                return getString(R.string.plan_search_cat_album);
        }
    }

    private boolean maybeUnlockOwnAlbumAchievement(String categoryKey, String query) {
        String ownStudentNumber = ZutnikSession.getInstance(requireContext()).getStudentNumber();
        if (!AchievementManager.isOwnAlbumSearch(categoryKey, query, ownStudentNumber)) {
            return false;
        }

        return unlockAndRevealAchievement(AchievementManager.Achievement.OWN_ALBUM, 220L);
    }

    private void maybeUnlockAmbientPlanAchievement() {
        if (!isAdded() || getView() == null) {
            return;
        }
        if (AchievementManager.isNightPlanVisit(LocalTime.now())
                && unlockAndRevealAchievement(AchievementManager.Achievement.NIGHT_PLAN, 120L)) {
            return;
        }
        if (!NetworkStatusHelper.isNetworkAvailable(requireContext())) {
            unlockAndRevealAchievement(AchievementManager.Achievement.OFFLINE_PLAN, 120L);
        }
    }

    private void recordViewedPlanMode(ViewMode mode) {
        if (mode == null) {
            return;
        }
        achievementViewedModes.add(mode.getId());
        if (AchievementManager.hasViewedAllPlanModes(achievementViewedModes)) {
            unlockAndRevealAchievement(AchievementManager.Achievement.FULL_VIEW, 180L);
        }
    }

    private boolean unlockAndRevealAchievement(
            AchievementManager.Achievement achievement,
            long delayMs) {
        if (!isAdded()) {
            return false;
        }
        AchievementManager.UnlockResult result = AchievementManager.unlock(requireContext(), achievement);
        if (!result.newlyUnlocked) {
            return false;
        }

        Runnable reveal = () -> {
            if (isAdded()) {
                AchievementRewardDialog.show(requireActivity(), result.record);
            }
        };
        View root = getView();
        if (root != null) {
            root.postDelayed(reveal, delayMs);
        } else {
            handler.postDelayed(reveal, delayMs);
        }
        return true;
    }

    private String resolveSearchCategoryLabel(String categoryKey) {
        if ("teacher".equals(categoryKey)) {
            return getString(R.string.plan_search_cat_teacher);
        }
        if ("room".equals(categoryKey)) {
            return getString(R.string.plan_search_cat_room);
        }
        if ("subject".equals(categoryKey)) {
            return getString(R.string.plan_search_cat_subject);
        }
        if ("group".equals(categoryKey)) {
            return getString(R.string.plan_search_cat_group);
        }
        return getString(R.string.plan_search_cat_album);
    }

    private void startRefreshAnimation() {
        if (btnRefresh == null)
            return;
        if (mRefreshAnimator == null) {
            mRefreshAnimator = android.animation.ObjectAnimator.ofFloat(btnRefresh, "rotation", 0f, 360f);
            mRefreshAnimator.setDuration(1000);
            mRefreshAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            mRefreshAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        }
        if (!mRefreshAnimator.isStarted()) {
            mRefreshAnimator.start();
        }
    }

    private void stopRefreshAnimation() {
        if (mRefreshAnimator != null && mRefreshAnimator.isStarted()) {
            mRefreshAnimator.cancel();
            btnRefresh.setRotation(0f);
        }
    }

    private void setupTimeColumn() {
        layoutTimeColumn.removeAllViews();
        for (int h = START_HOUR; h < END_HOUR; h++) {
            TextView tv = new TextView(requireContext());
            tv.setText(String.format(java.util.Locale.US, "%02d:00", h));
            tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.mzPlanColumnText));
            tv.setTextSize(11f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(HOUR_HEIGHT_DP));
            tv.setGravity(Gravity.END | Gravity.TOP);
            tv.setPadding(0, dpToPx(2), dpToPx(4), 0);
            tv.setLayoutParams(lp);
            layoutTimeColumn.addView(tv);
        }
    }

    private void setupWeekdayHeadersForMonth() {
        if (layoutMonthHeadersFixed == null)
            return;
        layoutMonthHeadersFixed.removeAllViews();
        String[] days = {
                getString(R.string.plan_month_weekday_mon),
                getString(R.string.plan_month_weekday_tue),
                getString(R.string.plan_month_weekday_wed),
                getString(R.string.plan_month_weekday_thu),
                getString(R.string.plan_month_weekday_fri),
                getString(R.string.plan_month_weekday_sat),
                getString(R.string.plan_month_weekday_sun)
        };
        for (String d : days) {
            TextView tv = new TextView(requireContext());
            tv.setText(d);
            tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.mzMuted));
            tv.setTextSize(10.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dpToPx(2), 0, dpToPx(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f);
            tv.setLayoutParams(lp);
            layoutMonthHeadersFixed.addView(tv);
        }
    }

    private class PlanPagerAdapter extends RecyclerView.Adapter<PlanPageViewHolder> {

        private final Context context;

        PlanPagerAdapter(Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public PlanPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return new PlanPageViewHolder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull PlanPageViewHolder holder, int position) {
            int diff = position - VP_START_POSITION;
            LocalDate pageDate;
            int renderContextVersion = planRenderContextVersion;

            if (isDayMode()) {
                pageDate = baseDate.plusDays(diff);
            } else if (isWeekMode()) {
                pageDate = baseDate.plusWeeks(diff);
            } else {
                pageDate = baseDate.plusMonths(diff).with(TemporalAdjusters.firstDayOfMonth());
            }

            boolean animateEntrance = holder.boundContextVersion != renderContextVersion || holder.wasLoading;
            clearPlanPageHolder(holder);
            holder.boundContextVersion = renderContextVersion;
            holder.boundModeId = viewModeId;
            holder.boundDate = pageDate;

            PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, pageDate);

            if (cached != null) {
                holder.wasLoading = false;
                if (isMonthMode()) {
                    renderMonthPage(holder, cached);
                } else {
                    renderWeekPage(holder, cached);
                }
                if (animateEntrance) {
                    animatePlanPageEntrance(holder.container);
                }
            } else {
                showLoadingState(holder);
                holder.wasLoading = true;
                loadPageAsync(position, pageDate, viewModeId, renderContextVersion);
            }
        }

        @Override
        public void onViewRecycled(@NonNull PlanPageViewHolder holder) {
            clearPlanPageHolder(holder);
            holder.boundModeId = null;
            holder.boundDate = null;
            holder.boundContextVersion = -1;
            holder.wasLoading = false;
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return 10000;
        }

        private void showLoadingState(PlanPageViewHolder holder) {
            View skeleton = createPlanLoadingSkeletonView();
            holder.container.addView(skeleton, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(
                    skeleton,
                    View.ALPHA,
                    0.48f,
                    0.95f,
                    0.48f);
            pulse.setDuration(880L);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.RESTART);
            pulse.setInterpolator(new DecelerateInterpolator());
            pulse.start();
            holder.container.setTag(pulse);
        }

        private void renderWeekPage(PlanPageViewHolder holder,
                PlanRepository.PlanResult result) {

            LinearLayout columnsContainer = new LinearLayout(context);
            columnsContainer.setOrientation(LinearLayout.HORIZONTAL);
            columnsContainer.setClipChildren(false);
            columnsContainer.setClipToPadding(false);
            holder.container.addView(columnsContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            float totalHours = END_HOUR - START_HOUR;
            int columnHeight = (int) (dpToPx(HOUR_HEIGHT_DP) * totalHours);

            List<PlanRepository.DayColumn> rawCols = result.dayColumns != null ? result.dayColumns
                    : Collections.emptyList();
            List<PlanRepository.DayColumn> cols = getVisibleColumns(rawCols);
            boolean weekendsHidden = areWeekendsHidden(rawCols, cols);

            if (cols.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText(R.string.plan_no_classes_in_range);
                empty.setGravity(Gravity.CENTER);
                columnsContainer.addView(empty, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }

            LocalDate today = LocalDate.now();

            // Build day columns first, track them with their dates
            List<View> dayColumnViews = new ArrayList<>();
            List<LocalDate> dayColumnDates = new ArrayList<>();

            for (PlanRepository.DayColumn col : cols) {

                boolean highlight = col.date != null && col.date.equals(today);

                LinearLayout dayColumn = new LinearLayout(context);
                dayColumn.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f);
                dayColumn.setLayoutParams(dayLp);

                FrameLayoutWithChildren dayBody = new FrameLayoutWithChildren(context);
                LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        columnHeight);
                dayBody.setLayoutParams(bodyLp);
                dayBody.setBackground(buildPlanDayBackground(context, highlight));

                addHourLines(dayBody);
                attachEmptySlotAdd(dayBody, col.date);

                List<PlanRepository.PlanEventUi> events = col.events != null ? col.events : Collections.emptyList();
                List<PlanRepository.PlanEventUi> visibleEvents = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : events) {
                    if (!shouldHideEvent(ev)) {
                        visibleEvents.add(ev);
                    }
                }
                if (visibleEvents.size() != events.size() && planRepository != null) {
                    planRepository.relayoutDayEvents(visibleEvents);
                }

                List<RenderedEvent> renderedEvents = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : visibleEvents) {
                    View evView = createEventView(ev, col.date);
                    evView.setVisibility(View.INVISIBLE);
                    dayBody.addView(evView);
                    renderedEvents.add(new RenderedEvent(ev, evView));
                }

                LocalDate colDate = col.date;
                boolean isTodayColumn = colDate != null && colDate.equals(today);
                if (isTodayColumn) {
                    dayBody.setTag("TODAY_BODY");
                }
                dayBody.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        int width = dayBody.getWidth();
                        if (width <= 0) {
                            return true;
                        }
                        ViewTreeObserver observer = dayBody.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        layoutEventsInDayBody(renderedEvents, width);
                        if (isTodayColumn) {
                            updateNowLineInVisiblePage();
                        }
                        return true;
                    }
                });

                dayColumn.addView(dayBody);
                dayColumnViews.add(dayColumn);
                dayColumnDates.add(col.date);
            }

            // Add columns to container, inserting session separators between them
            for (int i = 0; i < dayColumnViews.size(); i++) {
                // Before first column: leading check (e.g. Sunday session)
                if (i == 0) {
                    LocalDate firstDate = dayColumnDates.get(0);
                    LocalDate dayBefore = firstDate.minusDays(1);
                    addSessionMarkerLines(columnsContainer, dayBefore, firstDate, columnHeight);
                }

                // Between adjacent columns: end markers then start markers
                if (i > 0) {
                    LocalDate prevDate = dayColumnDates.get(i - 1);
                    LocalDate curDate = dayColumnDates.get(i);
                    boolean hasSessionMarker = addSessionMarkerLines(
                            columnsContainer,
                            prevDate,
                            curDate,
                            columnHeight);
                    if (!hasSessionMarker) {
                        columnsContainer.addView(buildWeekColumnDivider(columnHeight));
                    }
                }
                columnsContainer.addView(dayColumnViews.get(i));
            }

            // After last column: trailing check (e.g. Saturday session, or session starting on last day)
            if (!dayColumnDates.isEmpty()) {
                LocalDate lastDate = dayColumnDates.get(dayColumnDates.size() - 1);
                long trailingGapDays = (weekendsHidden && lastDate.getDayOfWeek() == DayOfWeek.FRIDAY) ? 3L : 1L;
                LocalDate dayAfter = lastDate.plusDays(trailingGapDays);
                addSessionMarkerLines(columnsContainer, lastDate, dayAfter, columnHeight);
            }
        }

        private void renderMonthPage(PlanPageViewHolder holder, PlanRepository.PlanResult result) {
            GridLayout grid = new GridLayout(context);
            grid.setColumnCount(7);
            grid.setUseDefaultMargins(false);

            holder.container.addView(grid, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            List<List<PlanRepository.MonthCell>> gridData = result.monthGrid != null ? result.monthGrid
                    : Collections.emptyList();

            if (gridData.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText(R.string.plan_no_classes_in_range);
                empty.setGravity(Gravity.CENTER);
                holder.container.removeAllViews();
                holder.container.addView(empty);
                return;
            }

            int cellBg = ThemeManager.resolveColor(context, R.attr.mzCardSoft);
            int cellBorder = ThemeManager.resolveColor(context, R.attr.mzBorderSoft);
            int primary = ThemeManager.resolveColor(context, R.attr.mzPrimary);
            int eventBg = ColorUtils.blendARGB(cellBg, primary, 0.18f);
            int eventBorder = ColorUtils.blendARGB(cellBorder, primary, 0.5f);
            int dayText = ThemeManager.resolveColor(context, R.attr.mzText);
            int hintText = ThemeManager.resolveColor(context, R.attr.mzMuted);

            for (List<PlanRepository.MonthCell> week : gridData) {
                for (PlanRepository.MonthCell cell : week) {
                    if (cell == null) {
                        View spacer = new View(context);
                        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                        lp.width = 0;
                        lp.height = dpToPx(MONTH_CELL_HEIGHT_DP);
                        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                        spacer.setLayoutParams(lp);
                        grid.addView(spacer);
                        continue;
                    }

                    LinearLayout cellRoot = new LinearLayout(context);
                    cellRoot.setOrientation(LinearLayout.VERTICAL);
                    cellRoot.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                    lp.width = 0;
                    lp.height = dpToPx(MONTH_CELL_HEIGHT_DP);
                    lp.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    cellRoot.setLayoutParams(lp);

                    int fill = cell.hasPlan ? eventBg : cellBg;
                    int stroke = cell.hasPlan ? eventBorder : cellBorder;
                    cellRoot.setBackground(buildRoundedBg(fill, stroke));

                    TextView tvNum = new TextView(context);
                    tvNum.setText(String.valueOf(cell.date.getDayOfMonth()));
                    tvNum.setTextColor(dayText);
                    tvNum.setTextSize(14f);
                    tvNum.setTypeface(Typeface.DEFAULT_BOLD);
                    cellRoot.addView(tvNum);

                    if (cell.hasPlan) {
                        TextView tvHint = new TextView(context);
                        tvHint.setText(R.string.plan_month_cell_has_events_hint);
                        tvHint.setTextSize(9f);
                        tvHint.setTextColor(hintText);
                        tvHint.setMaxLines(1);
                        tvHint.setEllipsize(TextUtils.TruncateAt.END);
                        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        hintLp.topMargin = dpToPx(2);
                        tvHint.setLayoutParams(hintLp);
                        cellRoot.addView(tvHint);

                        cellRoot.setOnClickListener(v -> {
                            setCurrentViewMode(ViewMode.DAY);
                            currentDate = cell.date;
                            baseDate = currentDate;
                            if (viewPager != null)
                                viewPager.setCurrentItem(VP_START_POSITION, false);
                            loadPlanForCurrentMode();
                        });
                    }
                    grid.addView(cellRoot);
                }
            }
        }
    }

    private static class PlanPageViewHolder extends RecyclerView.ViewHolder {
        FrameLayout container;
        String boundModeId;
        LocalDate boundDate;
        int boundContextVersion = -1;
        boolean wasLoading;

        PlanPageViewHolder(FrameLayout container) {
            super(container);
            this.container = container;
        }
    }

    private void clearPlanPageHolder(@NonNull PlanPageViewHolder holder) {
        FrameLayout container = holder.container;
        if (container == null) {
            return;
        }
        cancelPlanPageLoadingState(container);
        container.animate().cancel();
        container.clearAnimation();
        container.removeAllViews();
    }

    private void loadPageAsync(int position, LocalDate date, String modeId, int renderContextVersion) {
        PageLoadRequest request = new PageLoadRequest(renderContextVersion, modeId, date);
        synchronized (activePageLoads) {
            if (activePageLoads.contains(request)) {
                return;
            }
            activePageLoads.add(request);
        }

        PlanRepository.SearchParams searchSnapshot = snapshotCurrentSearchQuery();
        final boolean forceRefreshThisLoad = consumePendingForceRefresh(date, modeId);
        executor.execute(() -> {
            PlanRepository.PlanResult res = null;
            try {
                if (searchSnapshot != null) {
                    res = planRepository.searchPlan(modeId, date, searchSnapshot);
                } else {
                    res = planRepository.loadPlan(modeId, date, forceRefreshThisLoad);
                }
            } catch (Exception ignored) {
            }

            final PlanRepository.PlanResult finalRes = res;
            handler.post(() -> {
                activePageLoads.remove(request);
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (!isAdded() || activity == null || activity.isFinishing())
                    return;
                if (request.renderContextVersion != planRenderContextVersion) {
                    return;
                }

                if (finalRes != null) {
                    putPlanInCache(modeId, date, finalRes);
                    requestPageRefresh(position);

                    if (date.equals(currentDate)) {
                        if (finalRes.headerLabel != null) {
                            tvHeaderLabel.setText(finalRes.headerLabel);
                        }
                        if (!isMonthMode()) {
                            updateFixedWeekHeaders(finalRes.dayColumns);
                        }
                        boolean fetchedFromNetwork = finalRes.debug != null
                                && finalRes.debug.requests != null
                                && !finalRes.debug.requests.isEmpty();
                        updatePlanDataFreshness(fetchedFromNetwork);
                    }
                }
                stopRefreshAnimation();
            });
        });
    }

    private boolean consumePendingForceRefresh(LocalDate date, String modeId) {
        synchronized (forceRefreshLock) {
            if (!pendingForceRefresh) {
                return false;
            }
            if (pendingForceRefreshDate != null && !pendingForceRefreshDate.equals(date)) {
                return false;
            }
            if (pendingForceRefreshMode != null
                    && modeId != null
                    && !pendingForceRefreshMode.equals(modeId)) {
                return false;
            }
            pendingForceRefresh = false;
            pendingForceRefreshDate = null;
            pendingForceRefreshMode = null;
            return true;
        }
    }

    private void loadSubjectsForFilterAsync() {
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        executor.execute(() -> {
            List<PlanRepository.SubjectFilterItem> result = null;
            Exception error = null;
            boolean pendingForcedRefresh = false;
            try {
                purgeExpiredFilterCaches();
                pendingForcedRefresh = isFilterCacheForcedRefreshPending();
                boolean effectiveForceRefresh = pendingForcedRefresh;

                if (effectiveForceRefresh) {
                    clearAllFilterCaches();
                } else {
                    result = loadFilterCache();
                }

                if (result == null) {
                    result = planRepository.loadSubjectsForFilter(effectiveForceRefresh);
                    if (result != null && !result.isEmpty()) {
                        saveFilterCache(result);
                    }
                }
            } catch (Exception e) {
                error = e;
            }

            final List<PlanRepository.SubjectFilterItem> finalRes = result;
            final Exception finalErr = error;
            final boolean finalPendingForcedRefresh = pendingForcedRefresh;
            handler.post(() -> {
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (!isAdded() || activity == null || activity.isFinishing())
                    return;

                if (progress != null) {
                    progress.setVisibility(View.GONE);
                }

                if (finalRes == null || finalRes.isEmpty()) {
                    if (finalErr != null) {
                        String msg = finalErr.getMessage() != null ? finalErr.getMessage() : "";
                        boolean isDnsError = msg.contains("Unable to resolve host");
                        boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(requireContext());

                        if (isOffline || isDnsError) {
                            android.util.Log.d("PlanActivity", "Suppressed toast error: " + msg);
                        } else {
                            Toast.makeText(
                                    requireContext(),
                                    getString(R.string.plan_filters_error, msg),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(
                                requireContext(),
                                R.string.plan_filters_empty,
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                if (finalPendingForcedRefresh) {
                    markFilterCacheForcedRefreshHandled();
                }

                if (syncHiddenFiltersWithAvailable(finalRes)) {
                    planCache.clear();
                    loadPlanForCurrentMode();
                }

                showFiltersDialog(finalRes);
            });
        });
    }

    private void showFiltersDialog(List<PlanRepository.SubjectFilterItem> items) {
        String[] labels = new String[items.size()];
        String[] typeLabels = new String[items.size()];
        boolean[] checked = new boolean[items.size()];

        for (int i = 0; i < items.size(); i++) {
            PlanRepository.SubjectFilterItem it = items.get(i);
            labels[i] = it.label != null ? it.label : "";
            typeLabels[i] = resolveLocalizedFilterTypeLabel(it);
            checked[i] = hiddenSubjectKeys.contains(it.filterKey);
        }

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_plan_filters, null, false);
        RecyclerView list = content.findViewById(R.id.planFilterList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new PlanFilterAdapter(labels, typeLabels, checked));

        int rowHeight = Math.round(56f * getResources().getDisplayMetrics().density);
        int maxHeight = Math.round(Math.min(
                280f * getResources().getDisplayMetrics().density,
                getResources().getDisplayMetrics().heightPixels * 0.38f));
        ViewGroup.LayoutParams params = list.getLayoutParams();
        params.height = Math.min(Math.max(rowHeight * items.size(), rowHeight * 2), maxHeight);
        list.setLayoutParams(params);

        new MaterialAlertDialogBuilder(
                requireContext(),
                R.style.ThemeOverlay_ZUTnik_FilterDialog)
                .setTitle(R.string.plan_filters_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.plan_filters_apply, (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    for (int i = 0; i < items.size(); i++) {
                        if (checked[i]) {
                            hiddenSubjectKeys.add(items.get(i).filterKey);
                        }
                    }
                    prefs.edit().putStringSet(KEY_FILTER_HIDDEN, new HashSet<>(hiddenSubjectKeys)).apply();
                    planCache.clear();
                    loadPlanForCurrentMode();
                })
                .setNeutralButton(R.string.plan_filters_reset, (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    prefs.edit().remove(KEY_FILTER_HIDDEN).apply();
                    planCache.clear();
                    loadPlanForCurrentMode();
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .show();
    }

    private static final class PlanFilterAdapter
            extends RecyclerView.Adapter<PlanFilterAdapter.Holder> {

        private final String[] labels;
        private final String[] types;
        private final boolean[] checked;

        PlanFilterAdapter(String[] labels, String[] types, boolean[] checked) {
            this.labels = labels;
            this.types = types;
            this.checked = checked;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_plan_filter, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.title.setText(labels[position]);
            holder.type.setText(types[position]);
            holder.check.setChecked(checked[position]);
            holder.itemView.setContentDescription(labels[position] + ", " + types[position]);
            holder.itemView.setOnClickListener(v -> {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION) {
                    return;
                }
                checked[adapterPosition] = !checked[adapterPosition];
                notifyItemChanged(adapterPosition);
            });
        }

        @Override
        public int getItemCount() {
            return labels.length;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final MaterialCheckBox check;
            final TextView title;
            final TextView type;

            Holder(@NonNull View itemView) {
                super(itemView);
                check = itemView.findViewById(R.id.planFilterCheck);
                title = itemView.findViewById(R.id.planFilterTitle);
                type = itemView.findViewById(R.id.planFilterType);
            }
        }
    }

    private boolean isFilterCacheForcedRefreshPending() {
        return prefs.getBoolean(KEY_FILTER_CACHE_FORCE_REFRESH, false);
    }

    private void markFilterCacheForcedRefreshHandled() {
        prefs.edit().putBoolean(KEY_FILTER_CACHE_FORCE_REFRESH, false).apply();
    }

    private void clearAllFilterCaches() {
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : all.keySet()) {
            if (key.startsWith(FILTER_CACHE_JSON_PREFIX) || key.startsWith(FILTER_CACHE_TS_PREFIX)) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private void purgeExpiredFilterCaches() {
        long now = System.currentTimeMillis();
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = null;

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(FILTER_CACHE_TS_PREFIX)) {
                continue;
            }

            long ts;
            Object value = entry.getValue();
            if (value instanceof Number) {
                ts = ((Number) value).longValue();
            } else {
                ts = 0L;
            }

            if (ts > 0L && (now - ts) <= FILTER_CACHE_TTL_MS) {
                continue;
            }

            if (editor == null) {
                editor = prefs.edit();
            }

            String scope = key.substring(FILTER_CACHE_TS_PREFIX.length());
            editor.remove(key);
            editor.remove(FILTER_CACHE_JSON_PREFIX + scope);
        }

        if (editor != null) {
            editor.apply();
        }
    }

    private boolean syncHiddenFiltersWithAvailable(List<PlanRepository.SubjectFilterItem> availableItems) {
        if (availableItems == null || availableItems.isEmpty()) {
            return false;
        }

        Set<String> availableKeys = new HashSet<>();
        for (PlanRepository.SubjectFilterItem item : availableItems) {
            if (item == null || item.filterKey == null) {
                continue;
            }
            String key = item.filterKey.trim();
            if (!key.isEmpty()) {
                availableKeys.add(key);
            }
        }

        if (availableKeys.isEmpty() || hiddenSubjectKeys.isEmpty()) {
            return false;
        }

        Set<String> pruned = new HashSet<>();
        for (String selected : hiddenSubjectKeys) {
            if (availableKeys.contains(selected)) {
                pruned.add(selected);
            }
        }

        if (pruned.size() == hiddenSubjectKeys.size()) {
            return false;
        }

        hiddenSubjectKeys.clear();
        hiddenSubjectKeys.addAll(pruned);
        prefs.edit().putStringSet(KEY_FILTER_HIDDEN, new HashSet<>(hiddenSubjectKeys)).apply();
        return true;
    }

    private List<PlanRepository.SubjectFilterItem> loadFilterCache() {
        long ts = prefs.getLong(getFilterCacheTsKey(), 0L);
        if (ts == 0L)
            return null;

        long now = System.currentTimeMillis();
        if (now - ts > FILTER_CACHE_TTL_MS)
            return null;

        String json = SecureLocalData.readString(
                requireContext(),
                prefs,
                getFilterCacheJsonKey(),
                null);
        if (json == null || json.isEmpty())
            return null;

        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<PlanRepository.SubjectFilterItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                PlanRepository.SubjectFilterItem it = new PlanRepository.SubjectFilterItem();
                it.label = obj.optString("label", "");
                it.filterKey = obj.optString("filterKey", "");
                it.typeKey = obj.optString("typeKey", "");
                if (it.typeKey.trim().isEmpty()) {
                    it.typeKey = extractTypeKeyFromFilterKey(it.filterKey);
                }
                String localizedType = resolveTypeLabelByKey(it.typeKey);
                if (!localizedType.isEmpty()) {
                    it.typeLabel = localizedType;
                } else {
                    it.typeLabel = obj.optString("typeLabel", "");
                }
                items.add(it);
            }
            return items;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private void saveFilterCache(List<PlanRepository.SubjectFilterItem> items) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PlanRepository.SubjectFilterItem it : items) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("label", it.label != null ? it.label : "");
                obj.put("typeLabel", it.typeLabel != null ? it.typeLabel : "");
                obj.put("filterKey", it.filterKey != null ? it.filterKey : "");
                obj.put("typeKey", it.typeKey != null ? it.typeKey : "");
                arr.put(obj);
            }
            SecureLocalData.putString(
                    requireContext(),
                    prefs,
                    getFilterCacheJsonKey(),
                    arr.toString());
            prefs.edit()
                    .putLong(getFilterCacheTsKey(), System.currentTimeMillis())
                    .apply();
        } catch (org.json.JSONException ignored) {
        }
    }

    private String getFilterCacheJsonKey() {
        return KEY_FILTER_CACHE_JSON + "_" + getFilterCacheScopeKey();
    }

    private String getFilterCacheTsKey() {
        return KEY_FILTER_CACHE_TS + "_" + getFilterCacheScopeKey();
    }

    private String getFilterCacheScopeKey() {
        ZutnikSession session = ZutnikSession.getInstance();
        String userId = session.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            userId = "unknown";
        }

        String studyId = "default";
        Study active = session.getActiveStudy();
        if (active != null && active.przynaleznoscId != null && !active.przynaleznoscId.trim().isEmpty()) {
            studyId = active.przynaleznoscId.trim();
        }

        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        int academicYearStart = (month >= 10) ? now.getYear() : (now.getYear() - 1);
        int academicYearEnd = academicYearStart + 1;
        String term = (month >= 10 || month <= 2) ? "winter" : "summer";
        String language = LocaleManager.getLanguage(requireContext());
        if (language == null || language.trim().isEmpty()) {
            language = "default";
        }

        return userId + "_" + studyId + "_" + academicYearStart + "_" + academicYearEnd + "_" + term + "_" + language;
    }

    private String resolveLocalizedFilterTypeLabel(PlanRepository.SubjectFilterItem item) {
        if (item == null) {
            return "";
        }

        String typeKey = item.typeKey;
        if (typeKey == null || typeKey.trim().isEmpty()) {
            typeKey = extractTypeKeyFromFilterKey(item.filterKey);
            item.typeKey = typeKey;
        }

        String localized = resolveTypeLabelByKey(typeKey);
        if (!localized.isEmpty()) {
            item.typeLabel = localized;
            return localized;
        }
        return item.typeLabel != null ? item.typeLabel : "";
    }

    private String resolveTypeLabelByKey(String typeKey) {
        if (typeKey == null) {
            return "";
        }
        switch (typeKey.trim()) {
            case "lec":
                return getString(R.string.plan_type_lecture);
            case "aud":
                return getString(R.string.plan_type_auditory);
            case "lab":
                return getString(R.string.plan_type_lab);
            case "lek":
                return getString(R.string.plan_type_lectorate);
            default:
                return "";
        }
    }

    private String extractTypeKeyFromFilterKey(String filterKey) {
        if (filterKey == null) {
            return "";
        }
        int sep = filterKey.lastIndexOf("||");
        if (sep < 0 || sep >= filterKey.length() - 2) {
            return "";
        }
        String suffix = filterKey.substring(sep + 2).trim();
        if ("lec".equals(suffix) || "aud".equals(suffix) || "lab".equals(suffix) || "lek".equals(suffix)) {
            return suffix;
        }
        return "";
    }

    private void addHourLines(FrameLayoutWithChildren dayBody) {
        for (int h = START_HOUR; h < END_HOUR; h++) {
            int minutesFromStart = (h - START_HOUR) * 60;
            float topPx = (minutesFromStart / 60f) * dpToPx(HOUR_HEIGHT_DP);

            View line = new View(requireContext());
            FrameLayoutWithChildren.LayoutParams lp = new FrameLayoutWithChildren.LayoutParams(
                    FrameLayoutWithChildren.LayoutParams.MATCH_PARENT,
                    dpToPx(1));
            lp.topMargin = (int) topPx;
            line.setLayoutParams(lp);
            line.setBackgroundColor(ThemeManager.resolveColor(requireContext(), R.attr.mzPlanHourLine));
            dayBody.addView(line);
        }
    }

    private GradientDrawable buildPlanDayBackground(Context context, boolean highlight) {
        GradientDrawable bg = new GradientDrawable();
        int fill = ThemeManager.resolveColor(
                context,
                highlight ? R.attr.mzPlanGridBgSelected : R.attr.mzPlanGridBg);
        bg.setColor(fill);
        bg.setCornerRadius(0f);
        return bg;
    }

    private void updateNowLineInVisiblePage() {
        if (viewPager == null)
            return;

        View root = viewPager.getChildAt(0);
        if (!(root instanceof RecyclerView))
            return;

        RecyclerView rv = (RecyclerView) root;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View page = rv.getChildAt(i);
            View todayBody = page.findViewWithTag("TODAY_BODY");
            if (todayBody instanceof FrameLayoutWithChildren) {
                drawNowLine((FrameLayoutWithChildren) todayBody);
            }
        }
    }

    private void drawNowLine(FrameLayoutWithChildren parent) {
        View line = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if ("NOW_LINE".equals(parent.getChildAt(i).getTag())) {
                line = parent.getChildAt(i);
                break;
            }
        }

        LocalTime now = LocalTime.now();
        int minNow = now.getHour() * 60 + now.getMinute();
        int minStart = START_HOUR * 60;
        int minEnd = END_HOUR * 60;

        if (minNow < minStart || minNow > minEnd) {
            if (line != null)
                line.setVisibility(View.GONE);
            return;
        }

        if (line == null) {
            line = new NowLineView(requireContext());
            line.setTag("NOW_LINE");
            line.setLongClickable(true);
            line.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                unlockAndRevealAchievement(AchievementManager.Achievement.TIMEKEEPER, 100L);
                return true;
            });
            parent.addView(line);
        } else if (!(line instanceof NowLineView)) {
            parent.removeView(line);
            line = new NowLineView(requireContext());
            line.setTag("NOW_LINE");
            line.setLongClickable(true);
            line.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                unlockAndRevealAchievement(AchievementManager.Achievement.TIMEKEEPER, 100L);
                return true;
            });
            parent.addView(line);
        }

        line.setVisibility(View.VISIBLE);
        float topPx = ((minNow - minStart) / 60f) * dpToPx(HOUR_HEIGHT_DP);
        int lineHeight = dpToPx(18);
        int maxTop = parent.getHeight() > 0 ? Math.max(0, parent.getHeight() - lineHeight) : (int) topPx;
        int topMargin = Math.max(0, Math.min((int) topPx - lineHeight / 2, maxTop));

        FrameLayoutWithChildren.LayoutParams lp = new FrameLayoutWithChildren.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                lineHeight);
        lp.topMargin = topMargin;
        line.setLayoutParams(lp);
        ((NowLineView) line).bind(
                ThemeManager.resolveColor(requireContext(), R.attr.mzPlanNowLine),
                now.format(DateTimeFormatter.ofPattern("HH:mm")));
        line.bringToFront();
    }

    private void scheduleNextNowLineUpdate() {
        if (!isAdded() || getView() == null || isMonthMode()) {
            return;
        }
        LocalTime now = LocalTime.now();
        int msToNextMinute = (60 - now.getSecond()) * 1000 - (now.getNano() / 1_000_000);
        if (msToNextMinute < 200)
            msToNextMinute = 1000;
        nowLineHandler.postDelayed(nowLineRunnable, msToNextMinute);
    }

    private static class RenderedEvent {
        final PlanRepository.PlanEventUi ev;
        final View view;

        RenderedEvent(PlanRepository.PlanEventUi e, View v) {
            this.ev = e;
            this.view = v;
        }
    }

    private void layoutEventsInDayBody(List<RenderedEvent> renderedEvents,
            int widthPx) {
        int calStart = START_HOUR * 60;
        int calEnd = END_HOUR * 60;

        if (renderedEvents.isEmpty() || widthPx <= 0)
            return;

        int marginPx = dpToPx(2);
        int contentWidth = Math.max(0, widthPx - 2 * marginPx);

        for (int i = 0; i < renderedEvents.size(); i++) {
            RenderedEvent re = renderedEvents.get(i);
            int startMin = re.ev.startMin;
            int endMin = re.ev.endMin;

            if (endMin <= calStart || startMin >= calEnd) {
                re.view.animate().cancel();
                re.view.setVisibility(View.GONE);
                continue;
            }

            int startClamped = Math.max(startMin, calStart);
            int endClamped = Math.min(endMin, calEnd);
            int duration = Math.max(endClamped - startClamped, 15);

            float offsetMinutes = startClamped - calStart;
            float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);
            float heightPx = (duration / 60f) * dpToPx(HOUR_HEIGHT_DP);
            if (heightPx < dpToPx(28))
                heightPx = dpToPx(28);

            float safeLeftPct = Math.max(0f, Math.min(100f, re.ev.leftPct));
            float safeWidthPct = re.ev.widthPct > 0f ? re.ev.widthPct : 100f;
            if (safeLeftPct + safeWidthPct > 100f) {
                safeWidthPct = 100f - safeLeftPct;
            }

            int leftInset = Math.round(contentWidth * (safeLeftPct / 100f));
            int itemWidth = Math.round(contentWidth * (safeWidthPct / 100f));

            FrameLayoutWithChildren.LayoutParams lp =
                    (FrameLayoutWithChildren.LayoutParams) re.view.getLayoutParams();
            lp.topMargin = (int) topPx;
            lp.height = (int) heightPx;
            lp.leftMargin = marginPx + leftInset;
            lp.width = Math.max(0, itemWidth - marginPx);
            re.view.setLayoutParams(lp);
            if (re.view.getVisibility() != View.VISIBLE) {
                animateScheduleEventEntrance(re.view, i);
            } else {
                re.view.animate().cancel();
                re.view.setAlpha(1f);
                re.view.setScaleX(1f);
                re.view.setScaleY(1f);
                re.view.setTranslationY(0f);
                re.view.setVisibility(View.VISIBLE);
            }
        }
    }

    private void animateScheduleEventEntrance(View view, int order) {
        if (view == null) {
            return;
        }
        long startDelay = Math.min(96L, Math.max(0, order) * 20L);
        view.animate().cancel();
        view.setAlpha(0f);
        view.setScaleX(0.92f);
        view.setScaleY(0.92f);
        view.setTranslationY(dpToPx(8));
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    private void refreshAfterCustomEvent() {
        planCache.clear();
        beginPlanRenderContext();
        notifyAllPlanPagesChanged();
    }

    private void notifyAllPlanPagesChanged() {
        if (pagerAdapter == null) {
            return;
        }
        Runnable refresh = () -> {
            if (pagerAdapter == null) {
                return;
            }
            int itemCount = pagerAdapter.getItemCount();
            if (itemCount <= 0) {
                return;
            }
            int center = resolvePagerRefreshCenter(itemCount);
            int start = Math.max(0, center - PAGE_REFRESH_RADIUS);
            int end = Math.min(itemCount - 1, center + PAGE_REFRESH_RADIUS);
            pagerAdapter.notifyItemRangeChanged(start, end - start + 1);
        };
        if (pagerRecyclerView != null) {
            pagerRecyclerView.post(refresh);
        } else if (viewPager != null) {
            viewPager.post(refresh);
        } else {
            refresh.run();
        }
    }

    private void requestPageRefresh(int position) {
        if (pagerAdapter == null || position < 0 || position >= pagerAdapter.getItemCount()) {
            return;
        }
        Runnable refresh = () -> {
            if (pagerAdapter != null && position < pagerAdapter.getItemCount()) {
                pagerAdapter.notifyItemChanged(position);
            }
        };
        if (pagerRecyclerView != null) {
            pagerRecyclerView.post(refresh);
        } else if (viewPager != null) {
            viewPager.post(refresh);
        } else {
            refresh.run();
        }
    }

    private int resolvePagerRefreshCenter(int itemCount) {
        int center = viewPager != null ? viewPager.getCurrentItem() : VP_START_POSITION;
        if (center < 0) {
            return 0;
        }
        if (center >= itemCount) {
            return itemCount - 1;
        }
        return center;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void replaceSuggestionItems(
            List<String> target,
            List<String> nextItems,
            RecyclerView.Adapter<?> adapter) {
        if (target == null || nextItems == null || adapter == null) {
            return;
        }
        target.clear();
        target.addAll(nextItems);
        adapter.notifyDataSetChanged();
    }

    private int getDefaultStartMin() {
        LocalTime now = LocalTime.now();
        int minutes = now.getHour() * 60 + now.getMinute();
        return clampAndRoundStart(minutes);
    }

    private int clampAndRoundStart(int minutes) {
        int minStart = START_HOUR * 60;
        int minEnd = END_HOUR * 60;
        int rounded = roundToQuarterHour(minutes);
        if (rounded < minStart)
            rounded = minStart;
        if (rounded > minEnd)
            rounded = minEnd;
        return rounded;
    }

    private int roundToQuarterHour(int minutes) {
        return Math.round(minutes / 15f) * 15;
    }

    private int[] buildDefaultRange(int startMin) {
        int minStart = START_HOUR * 60;
        int minEnd = END_HOUR * 60;
        int start = Math.max(minStart, Math.min(startMin, minEnd));
        int end = start + 90;
        if (end > minEnd) {
            end = minEnd;
            if (end - start < 30) {
                start = Math.max(minStart, end - 90);
            }
        }
        return new int[] { start, end };
    }

    private int getStartMinFromTouch(float yPx) {
        int minutesFromStart = Math.round((yPx / dpToPx(HOUR_HEIGHT_DP)) * 60f);
        int minutes = START_HOUR * 60 + minutesFromStart;
        return clampAndRoundStart(minutes);
    }

    private void attachEmptySlotAdd(FrameLayoutWithChildren dayBody, LocalDate date) {
        if (dayBody == null || date == null)
            return;

        dayBody.setClickable(true);
        final int touchSlop = android.view.ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        final float[] down = new float[2];

        dayBody.setOnTouchListener((v, event) -> {
            if (event == null)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getX();
                    down[1] = event.getY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getX() - down[0]);
                    float dy = Math.abs(event.getY() - down[1]);
                    if (dx <= touchSlop && dy <= touchSlop) {
                        v.performClick();
                        int startMin = getStartMinFromTouch(event.getY());
                        int[] range = buildDefaultRange(startMin);

                        // Show highlight at clicked slot
                        addSlotHighlight(dayBody, range[0], range[1]);

                        AddCustomEventDialog dialog = AddCustomEventDialog.newForSlot(date, range[0], range[1]);
                        dialog.setListener(ev -> refreshAfterCustomEvent());
                        dialog.show(getParentFragmentManager(), "add_custom_event_slot");

                        // Remove highlight on dialog dismiss
                        if (dialog.getDialog() != null) {
                            dialog.getDialog().setOnDismissListener(d -> removeSlotHighlight(dayBody));
                        } else {
                            // Dialog not yet created; use lifecycle
                            getParentFragmentManager().registerFragmentLifecycleCallbacks(
                                    new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                                        @Override
                                        public void onFragmentViewDestroyed(@NonNull androidx.fragment.app.FragmentManager fm,
                                                @NonNull androidx.fragment.app.Fragment f) {
                                            if (f == dialog) {
                                                removeSlotHighlight(dayBody);
                                                fm.unregisterFragmentLifecycleCallbacks(this);
                                            }
                                        }
                                    }, false);
                        }
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void addSlotHighlight(FrameLayoutWithChildren dayBody, int startMin, int endMin) {
        removeSlotHighlight(dayBody); // Remove any existing

        int calStart = START_HOUR * 60;
        float topPx = ((startMin - calStart) / 60f) * dpToPx(HOUR_HEIGHT_DP);
        float heightPx = ((endMin - startMin) / 60f) * dpToPx(HOUR_HEIGHT_DP);

        View highlight = new View(requireContext());
        highlight.setTag("SLOT_HIGHLIGHT");

        int primary = ThemeManager.resolveColor(requireContext(), R.attr.mzPrimary);
        int fillColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 50);
        int strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 140);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dpToPx(6));
        bg.setStroke(dpToPx(2), strokeColor, dpToPx(4), dpToPx(3));
        highlight.setBackground(bg);

        FrameLayoutWithChildren.LayoutParams lp = new FrameLayoutWithChildren.LayoutParams(
                FrameLayoutWithChildren.LayoutParams.MATCH_PARENT,
                (int) heightPx);
        lp.topMargin = (int) topPx;
        highlight.setLayoutParams(lp);

        // Fade in
        highlight.setAlpha(0f);
        highlight.animate().alpha(1f).setDuration(200).start();

        dayBody.addView(highlight);
        highlight.bringToFront();
    }

    private void removeSlotHighlight(FrameLayoutWithChildren dayBody) {
        if (dayBody == null) return;
        for (int i = dayBody.getChildCount() - 1; i >= 0; i--) {
            View child = dayBody.getChildAt(i);
            if ("SLOT_HIGHLIGHT".equals(child.getTag())) {
                child.animate().alpha(0f).setDuration(150).withEndAction(() -> dayBody.removeView(child)).start();
            }
        }
    }

    private void cancelPlanPageLoadingState(View container) {
        if (container == null) {
            return;
        }
        Object tag = container.getTag();
        if (tag instanceof android.animation.Animator) {
            ((android.animation.Animator) tag).cancel();
        }
        container.setTag(null);
    }

    private void animatePlanPageEntrance(View container) {
        if (container == null) {
            return;
        }
        container.animate().cancel();
        container.setAlpha(0f);
        container.setScaleX(0.985f);
        container.setScaleY(0.985f);
        container.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator(1.35f))
                .start();
    }

    private View createPlanLoadingSkeletonView() {
        int baseSurface = ThemeManager.resolveColor(requireContext(), R.attr.mzCardSoft);
        int border = ThemeManager.resolveColor(requireContext(), R.attr.mzBorderSoft);
        int text = ThemeManager.resolveColor(requireContext(), R.attr.mzText);
        int primary = ThemeManager.resolveColor(requireContext(), R.attr.mzPrimary);
        int fill = ColorUtils.blendARGB(baseSurface, text, 0.06f);
        int stroke = ColorUtils.blendARGB(border, fill, 0.45f);
        int accentFill = ColorUtils.blendARGB(fill, primary, 0.18f);
        int accentStroke = ColorUtils.blendARGB(stroke, primary, 0.32f);

        if (isMonthMode()) {
            return createMonthLoadingSkeleton(fill, stroke, accentFill, accentStroke);
        }
        return createScheduleLoadingSkeleton(fill, stroke, accentFill, accentStroke);
    }

    private View createScheduleLoadingSkeleton(
            int fillColor,
            int strokeColor,
            int accentFillColor,
            int accentStrokeColor) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        root.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(18));

        int columnCount = isDayMode() ? 1 : 5;
        boolean singleColumn = columnCount == 1;
        for (int i = 0; i < columnCount; i++) {
            LinearLayout column = new LinearLayout(requireContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipToPadding(false);
            column.setClipChildren(false);
            LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f);
            columnLp.setMargins(dpToPx(singleColumn ? 0 : 4), 0, dpToPx(singleColumn ? 0 : 4), 0);
            column.setLayoutParams(columnLp);

            boolean isAccentColumn = singleColumn || i == 2;
            LinearLayout headerStack = new LinearLayout(requireContext());
            headerStack.setOrientation(LinearLayout.VERTICAL);
            headerStack.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams headerStackLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            headerStackLp.bottomMargin = dpToPx(singleColumn ? 14 : 12);
            headerStack.setLayoutParams(headerStackLp);

            View topDot = createSkeletonBlock(
                    isAccentColumn ? accentFillColor : fillColor,
                    isAccentColumn ? accentStrokeColor : strokeColor,
                    999);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    dpToPx(singleColumn ? 12 : 8),
                    dpToPx(singleColumn ? 12 : 8));
            dotLp.bottomMargin = dpToPx(singleColumn ? 8 : 7);
            headerStack.addView(topDot, dotLp);

            View titleBlock = createSkeletonBlock(
                    isAccentColumn ? accentFillColor : fillColor,
                    isAccentColumn ? accentStrokeColor : strokeColor,
                    999);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    dpToPx(singleColumn ? 74 : 24 + (i % 2) * 4),
                    dpToPx(singleColumn ? 11 : 9));
            headerStack.addView(titleBlock, titleLp);

            View subtitleBlock = createSkeletonBlock(
                    ColorUtils.setAlphaComponent(fillColor, 232),
                    ColorUtils.setAlphaComponent(strokeColor, 104),
                    999);
            LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                    dpToPx(singleColumn ? 52 : 16 + ((i + 1) % 3) * 3),
                    dpToPx(singleColumn ? 7 : 6));
            subtitleLp.topMargin = dpToPx(6);
            headerStack.addView(subtitleBlock, subtitleLp);
            column.addView(headerStack);

            FrameLayout body = new FrameLayout(requireContext());
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f);
            body.setLayoutParams(bodyLp);
            body.setBackground(buildRoundedBg(
                    ColorUtils.setAlphaComponent(fillColor, singleColumn ? 112 : 90),
                    ColorUtils.setAlphaComponent(strokeColor, singleColumn ? 86 : 64),
                    singleColumn ? 22 : 18));
            body.setPadding(dpToPx(singleColumn ? 10 : 4), dpToPx(10), dpToPx(singleColumn ? 10 : 4), dpToPx(12));

            addSkeletonGuideLine(
                    body,
                    isAccentColumn ? accentStrokeColor : strokeColor,
                    18,
                    singleColumn ? 14 : 8,
                    singleColumn ? 14 : 8,
                    isAccentColumn ? 86 : 68,
                    4);
            addSkeletonGuideLine(body, strokeColor, 86, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 44, 1);
            addSkeletonGuideLine(body, strokeColor, 176, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);
            addSkeletonGuideLine(body, strokeColor, 266, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);
            addSkeletonGuideLine(body, strokeColor, 356, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);
            addSkeletonGuideLine(body, strokeColor, 446, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);
            addSkeletonGuideLine(body, strokeColor, 536, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);
            addSkeletonGuideLine(body, strokeColor, 626, singleColumn ? 16 : 8, singleColumn ? 16 : 8, 38, 1);

            if (singleColumn) {
                addSkeletonEventCard(
                        body,
                        accentFillColor,
                        accentStrokeColor,
                        82,
                        84,
                        8,
                        42,
                        18,
                        40);
                addSkeletonEventCard(
                        body,
                        fillColor,
                        strokeColor,
                        238,
                        62,
                        24,
                        14,
                        48,
                        76);
                addSkeletonEventCard(
                        body,
                        accentFillColor,
                        accentStrokeColor,
                        404,
                        92,
                        12,
                        54,
                        22,
                        46);
                addSkeletonEventCard(
                        body,
                        fillColor,
                        strokeColor,
                        578,
                        56,
                        36,
                        18,
                        60,
                        92);
            } else {
                addSkeletonEventCard(
                        body,
                        isAccentColumn ? accentFillColor : fillColor,
                        isAccentColumn ? accentStrokeColor : strokeColor,
                        84,
                        74,
                        4,
                        4,
                        6,
                        12);
                addSkeletonEventCard(
                        body,
                        fillColor,
                        strokeColor,
                        248,
                        56,
                        8,
                        4,
                        12,
                        18);
                addSkeletonEventCard(
                        body,
                        accentFillColor,
                        accentStrokeColor,
                        426,
                        68,
                        4,
                        6,
                        8,
                        14);
            }
            column.addView(body);

            root.addView(column);
        }

        return root;
    }

    private void addSkeletonGuideLine(
            FrameLayout body,
            int strokeColor,
            int topDp,
            int startDp,
            int endDp,
            int alpha,
            int heightDp) {
        if (body == null) {
            return;
        }
        View line = new View(requireContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(heightDp));
        lp.topMargin = dpToPx(topDp);
        lp.leftMargin = dpToPx(startDp);
        lp.rightMargin = dpToPx(endDp);
        line.setLayoutParams(lp);
        line.setBackgroundColor(ColorUtils.setAlphaComponent(strokeColor, alpha));
        body.addView(line);
    }

    private void addSkeletonEventCard(
            FrameLayout body,
            int fillColor,
            int strokeColor,
            int topDp,
            int heightDp,
            int startDp,
            int endDp,
            int titleEndInsetDp,
            int metaEndInsetDp) {
        if (body == null) {
            return;
        }
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipToPadding(false);
        card.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        card.setBackground(buildRoundedBg(
                ColorUtils.setAlphaComponent(fillColor, 246),
                ColorUtils.setAlphaComponent(strokeColor, 146),
                16));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(heightDp));
        cardLp.topMargin = dpToPx(topDp);
        cardLp.leftMargin = dpToPx(startDp);
        cardLp.rightMargin = dpToPx(endDp);
        card.setLayoutParams(cardLp);

        View accent = createSkeletonBlock(
                ColorUtils.blendARGB(
                        ColorUtils.setAlphaComponent(fillColor, 255),
                        ColorUtils.setAlphaComponent(strokeColor, 196),
                        0.28f),
                ColorUtils.setAlphaComponent(strokeColor, 0),
                999);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                dpToPx(3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        accentLp.rightMargin = dpToPx(7);
        card.addView(accent, accentLp);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipToPadding(false);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        View title = createSkeletonBlock(
                ColorUtils.setAlphaComponent(fillColor, 248),
                ColorUtils.setAlphaComponent(strokeColor, 112),
                999);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(8));
        titleLp.rightMargin = dpToPx(titleEndInsetDp);
        content.addView(title, titleLp);

        View meta = createSkeletonBlock(
                ColorUtils.setAlphaComponent(fillColor, 222),
                ColorUtils.setAlphaComponent(strokeColor, 92),
                999);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(6));
        metaLp.topMargin = dpToPx(7);
        metaLp.rightMargin = dpToPx(metaEndInsetDp);
        content.addView(meta, metaLp);

        View footer = createSkeletonBlock(
                ColorUtils.setAlphaComponent(strokeColor, 132),
                ColorUtils.setAlphaComponent(strokeColor, 72),
                999);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(5));
        footerLp.topMargin = dpToPx(6);
        footerLp.rightMargin = dpToPx(metaEndInsetDp + 12);
        content.addView(footer, footerLp);

        card.addView(content);
        body.addView(card);
    }

    private View createMonthLoadingSkeleton(
            int fillColor,
            int strokeColor,
            int accentFillColor,
            int accentStrokeColor) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));

        GridLayout weekdayRow = new GridLayout(requireContext());
        weekdayRow.setColumnCount(7);
        weekdayRow.setUseDefaultMargins(false);
        weekdayRow.setPadding(0, 0, 0, dpToPx(8));
        for (int i = 0; i < 7; i++) {
            View weekdayBlock = createSkeletonBlock(fillColor, strokeColor);
            GridLayout.LayoutParams labelLp = new GridLayout.LayoutParams();
            labelLp.width = 0;
            labelLp.height = dpToPx(8);
            labelLp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            labelLp.setMargins(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3));
            weekdayBlock.setLayoutParams(labelLp);
            weekdayRow.addView(weekdayBlock);
        }
        root.addView(weekdayRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout grid = new GridLayout(requireContext());
        grid.setColumnCount(7);
        grid.setUseDefaultMargins(false);

        for (int i = 0; i < 42; i++) {
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            boolean accentCell = i % 8 == 2 || i % 8 == 5;
            cell.setBackground(buildRoundedBg(
                    accentCell ? ColorUtils.setAlphaComponent(accentFillColor, 214) : ColorUtils.setAlphaComponent(fillColor, 202),
                    accentCell ? ColorUtils.setAlphaComponent(accentStrokeColor, 112) : ColorUtils.setAlphaComponent(strokeColor, 84)));

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dpToPx(MONTH_CELL_HEIGHT_DP);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
            cell.setLayoutParams(lp);

            View dayNumber = createSkeletonBlock(
                    accentCell ? accentFillColor : fillColor,
                    accentCell ? accentStrokeColor : strokeColor);
            LinearLayout.LayoutParams dayNumberLp = new LinearLayout.LayoutParams(
                    dpToPx((i % 3 == 0) ? 14 : 18),
                    dpToPx(8));
            dayNumberLp.bottomMargin = dpToPx(10);
            dayNumber.setLayoutParams(dayNumberLp);
            cell.addView(dayNumber);

            if (i % 5 != 0) {
                View hintBlock = createSkeletonBlock(fillColor, strokeColor);
                LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                        dpToPx(8),
                        dpToPx(8));
                hintLp.topMargin = dpToPx(2);
                cell.addView(hintBlock, hintLp);
            }

            if (i % 7 == 3 || i % 7 == 6 || accentCell) {
                View secondHint = createSkeletonBlock(accentFillColor, accentStrokeColor);
                LinearLayout.LayoutParams secondHintLp = new LinearLayout.LayoutParams(
                        dpToPx(8),
                        dpToPx(6));
                secondHintLp.topMargin = dpToPx(5);
                cell.addView(secondHint, secondHintLp);
            }

            grid.addView(cell);
        }

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View createSkeletonBlock(int fillColor, int strokeColor) {
        return createSkeletonBlock(fillColor, strokeColor, 10);
    }

    private View createSkeletonBlock(int fillColor, int strokeColor, int radiusDp) {
        View block = new View(requireContext());
        block.setBackground(buildRoundedBg(fillColor, strokeColor, radiusDp));
        block.setAlpha(0.96f);
        return block;
    }

    /**
     * Marker descriptor used by combined period separators.
     */
    private static class MarkerSpec {
        final String label;
        final int color;

        MarkerSpec(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * Adds one shared marker separator for the gap between prevDate and curDate.
     * End markers are placed first (top), start markers next (bottom).
     */
    private boolean addSessionMarkerLines(
            LinearLayout container,
            LocalDate prevDate,
            LocalDate curDate,
            int columnHeight) {
        if (sessionDates == null || sessionDates.isEmpty()) {
            return false;
        }
        if (prevDate == null || curDate == null) {
            return false;
        }

        List<MarkerSpec> markers = new ArrayList<>();
        boolean hasForwardRange = curDate.isAfter(prevDate);
        long gapDays = hasForwardRange ? java.time.temporal.ChronoUnit.DAYS.between(prevDate, curDate) : 0L;

        for (PlanRepository.SessionPeriod period : sessionDates) {
            if (period == null || period.endDate == null) {
                continue;
            }

            boolean matchesEnd;
            if (hasForwardRange) {
                // Boundary includes all hidden dates between prev and cur:
                // [prevDate, curDate)
                matchesEnd = !period.endDate.isBefore(prevDate) && period.endDate.isBefore(curDate);
            } else {
                matchesEnd = prevDate.equals(period.endDate);
            }

            if (matchesEnd) {
                markers.add(new MarkerSpec(
                        buildPeriodMarkerLabel(period, false),
                        resolvePeriodMarkerColor(period)));
            }
        }

        for (PlanRepository.SessionPeriod period : sessionDates) {
            if (period == null || period.startDate == null) {
                continue;
            }

            boolean matchesStart;
            if (hasForwardRange) {
                LocalDate effectiveEnd = gapDays > 1L ? curDate.minusDays(1) : curDate;
                // Standard boundary is (prevDate, curDate]. For collapsed multi-day gaps
                // (e.g. hidden weekend), we cap to hidden days to avoid leaking markers
                // from the next visible day.
                matchesStart = period.startDate.isAfter(prevDate) && !period.startDate.isAfter(effectiveEnd);
            } else {
                matchesStart = curDate.equals(period.startDate);
            }

            if (matchesStart) {
                markers.add(new MarkerSpec(
                        buildPeriodMarkerLabel(period, true),
                        resolvePeriodMarkerColor(period)));
            }
        }

        if (markers.isEmpty()) {
            return false;
        }

        container.addView(buildCombinedMarkerLine(markers, columnHeight));
        return true;
    }

    private View buildWeekColumnDivider(int columnHeight) {
        View divider = new View(requireContext());
        int dividerWidth = dpToPx(1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dividerWidth, columnHeight);
        params.setMargins(0, 0, -dividerWidth, 0);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(ThemeManager.resolveColor(requireContext(), R.attr.mzPlanHourLine));
        divider.setZ(dpToPx(1));
        return divider;
    }

    private String buildPeriodMarkerLabel(PlanRepository.SessionPeriod period, boolean start) {
        if (period == null) {
            return "";
        }
        String name = PlanRepository.getPeriodDisplayName(requireContext(), period.name);
        int prefixRes;
        if (PlanRepository.isSessionPeriodName(period.name)) {
            prefixRes = start ? R.string.session_start_label : R.string.session_end_label;
        } else {
            prefixRes = start ? R.string.calendar_period_start_label : R.string.calendar_period_end_label;
        }
        return getString(prefixRes) + " " + name;
    }

    private int resolvePeriodMarkerColor(PlanRepository.SessionPeriod period) {
        if (period == null || period.name == null) {
            int fallback = ThemeManager.resolveColor(requireContext(), R.attr.mzAccent);
            return fallback != 0 ? fallback : 0xFF4F8DFF;
        }

        String name = period.name.toLowerCase(java.util.Locale.ROOT);
        if (PlanRepository.isSessionPeriodName(name)) {
            int red = ThemeManager.resolveColor(requireContext(), R.attr.mzDanger);
            return red != 0 ? red : 0xFFF44336;
        }
        if (name.startsWith("przerwa_")) {
            int accent = ThemeManager.resolveColor(requireContext(), R.attr.mzAccent);
            return accent != 0 ? accent : 0xFF4F8DFF;
        }
        if ("wakacje_zimowe".equals(name)) {
            int primary = ThemeManager.resolveColor(requireContext(), R.attr.mzPrimary);
            return primary != 0 ? primary : 0xFF1976D2;
        }
        if ("wakacje_letnie".equals(name)) {
            int lime = ThemeManager.resolveColor(requireContext(), R.attr.mzLime);
            return lime != 0 ? lime : 0xFF8BC34A;
        }
        int fallback = ThemeManager.resolveColor(requireContext(), R.attr.mzAccent);
        return fallback != 0 ? fallback : 0xFF4F8DFF;
    }

    /**
     * Builds one separator line that can hold multiple markers ordered top-to-bottom.
     * The center line uses a vertical gradient based on marker colors.
     */
    private View buildCombinedMarkerLine(List<MarkerSpec> markers, int columnHeight) {
        float textSizePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 9f,
                getResources().getDisplayMetrics());
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSizePx);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();

        int padH = dpToPx(12);
        int padV = dpToPx(6);
        int badgeHeight = (int) Math.ceil(fm.descent - fm.ascent) + padV;
        int markerSide = Math.max(badgeHeight, dpToPx(20));
        int markerEdgePadding = dpToPx(8);

        FrameLayout separator = new FrameLayout(requireContext());
        separator.setClipChildren(false);
        separator.setClipToPadding(false);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(markerSide, columnHeight);
        int leadingOverlap = markerSide / 2;
        int trailingOverlap = markerSide - leadingOverlap;
        sepLp.setMargins(-leadingOverlap, 0, -trailingOverlap, 0);
        separator.setLayoutParams(sepLp);
        separator.setZ(dpToPx(2));

        addSessionMarkerGridNotches(separator, markerSide);

        View line = new View(requireContext());
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                dpToPx(2), FrameLayout.LayoutParams.MATCH_PARENT);
        lineLp.gravity = Gravity.CENTER_HORIZONTAL;
        line.setLayoutParams(lineLp);

        int[] gradientColors = buildMarkerGradientColors(markers);
        if (gradientColors.length == 1) {
            line.setBackgroundColor(gradientColors[0]);
        } else {
            GradientDrawable lineBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, gradientColors);
            line.setBackground(lineBg);
        }
        line.setAlpha(0.9f);
        separator.addView(line);

        int count = markers.size();
        for (int i = 0; i < count; i++) {
            MarkerSpec marker = markers.get(i);
            int badgeTextWidth = (int) Math.ceil(paint.measureText(marker.label)) + padH;
            TextView badge = buildMarkerBadge(marker, badgeTextWidth, markerSide, padH, padV);

            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    badgeTextWidth, markerSide);
            badgeLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

            int centerY;
            if (count == 1) {
                centerY = columnHeight / 2;
            } else if (count == 2) {
                centerY = (i == 0)
                        ? (badgeTextWidth / 2 + markerEdgePadding)
                        : (columnHeight - badgeTextWidth / 2 - markerEdgePadding);
            } else {
                int safeTop = badgeTextWidth / 2 + markerEdgePadding;
                int safeBottom = columnHeight - badgeTextWidth / 2 - markerEdgePadding;
                if (safeBottom <= safeTop) {
                    centerY = columnHeight / 2;
                } else {
                    float progress = (float) i / (float) (count - 1);
                    centerY = safeTop + Math.round((safeBottom - safeTop) * progress);
                }
            }

            int top = centerY - (markerSide / 2);
            int maxTop = Math.max(0, columnHeight - markerSide);
            badgeLp.topMargin = Math.max(0, Math.min(top, maxTop));
            badge.setLayoutParams(badgeLp);
            separator.addView(badge);
        }

        return separator;
    }

    private void addSessionMarkerGridNotches(FrameLayout separator, int markerSide) {
        int centerGap = dpToPx(3);
        int segmentWidth = Math.max(0, markerSide / 2 - centerGap);
        if (segmentWidth == 0) {
            return;
        }

        int lineColor = ThemeManager.resolveColor(requireContext(), R.attr.mzPlanHourLine);
        for (int hour = START_HOUR; hour < END_HOUR; hour++) {
            int minutesFromStart = (hour - START_HOUR) * 60;
            int top = Math.round((minutesFromStart / 60f) * dpToPx(HOUR_HEIGHT_DP));

            View left = new View(requireContext());
            FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(segmentWidth, dpToPx(1));
            leftParams.gravity = Gravity.TOP | Gravity.START;
            leftParams.topMargin = top;
            left.setLayoutParams(leftParams);
            left.setBackgroundColor(lineColor);
            separator.addView(left);

            View right = new View(requireContext());
            FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(segmentWidth, dpToPx(1));
            rightParams.gravity = Gravity.TOP | Gravity.END;
            rightParams.topMargin = top;
            right.setLayoutParams(rightParams);
            right.setBackgroundColor(lineColor);
            separator.addView(right);
        }
    }

    private TextView buildMarkerBadge(
            MarkerSpec marker,
            int badgeTextWidth,
            int badgeLayoutHeight,
            int padH,
            int padV) {
        TextView badge = new TextView(requireContext());
        badge.setText(marker.label);
        badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
        badge.setTextColor(0xFFFFFFFF);
        badge.setSingleLine(true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(padH / 2, padV / 2, padH / 2, padV / 2);
        badge.setRotation(90f);

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(marker.color);
        badgeBg.setCornerRadius(dpToPx(4));
        badgeBg.setStroke(dpToPx(1), ColorUtils.blendARGB(marker.color, 0xFF000000, 0.22f));
        badge.setBackground(badgeBg);
        badge.setLayoutParams(new FrameLayout.LayoutParams(badgeTextWidth, badgeLayoutHeight));
        return badge;
    }

    private int[] buildMarkerGradientColors(List<MarkerSpec> markers) {
        if (markers == null || markers.isEmpty()) {
            int fallback = ThemeManager.resolveColor(requireContext(), R.attr.mzAccent);
            return new int[] { fallback != 0 ? fallback : 0xFF4F8DFF };
        }
        int[] colors = new int[markers.size()];
        for (int i = 0; i < markers.size(); i++) {
            colors[i] = markers.get(i).color;
        }
        return colors;
    }
    private void updateFixedWeekHeaders(List<PlanRepository.DayColumn> rawCols) {
        if (layoutWeekHeadersRow == null || layoutWeekHeadersFixed == null)
            return;

        List<PlanRepository.DayColumn> cols = getVisibleColumns(rawCols);
        layoutWeekHeadersRow.removeAllViews();

        if (cols.isEmpty()) {
            layoutWeekHeadersFixed.setVisibility(View.GONE);
            return;
        }

        layoutWeekHeadersFixed.setVisibility(View.VISIBLE);
        LocalDate today = LocalDate.now();

        for (PlanRepository.DayColumn col : cols) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f);
            lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);

            TextView tv = new TextView(requireContext());
            tv.setLayoutParams(lp);
            tv.setText(formatDayHeader(col.date));
            tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.mzPlanHeaderText));
            tv.setTextSize(11.5f);
            tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

            if (col.date != null && col.date.equals(today)) {
                tv.setBackgroundResource(R.drawable.bg_week_header_selected);
            }

            layoutWeekHeadersRow.addView(tv);
        }
    }

    private boolean shouldHideEvent(PlanRepository.PlanEventUi ev) {
        if (ev == null)
            return false;
        if (ev.subjectKey == null || ev.subjectKey.isEmpty())
            return false;
        return hiddenSubjectKeys.contains(ev.subjectKey);
    }

    private View createEventView(PlanRepository.PlanEventUi ev, LocalDate eventDate) {
        PlanEventBlockView eventView = new PlanEventBlockView(requireContext());
        eventView.setTag(ev);
        int color = ThemeManager.resolveEventColor(requireContext(), ev.typeClass);
        int surface = ThemeManager.resolveColor(requireContext(), R.attr.mzCard);
        int eventFill = ColorUtils.blendARGB(color, surface, 0.14f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(eventFill);
        bg.setCornerRadius(dpToPx(7));
        bg.setStroke(dpToPx(1), ColorUtils.setAlphaComponent(color, 150));

        int danger = ThemeManager.resolveColor(requireContext(), R.attr.mzDanger);
        int dangerSoft = ThemeManager.resolveColor(requireContext(), R.attr.mzDangerSoft);
        int textColor = ThemeManager.resolveColor(requireContext(), R.attr.mzPlanEventTextColor);

        // Handle custom event overlay (exam/test on top of official event)
        if (ev.hasCustomOverlay && !TextUtils.isEmpty(ev.customOverlayLabel)) {
            textColor = danger;
        }

        // Handle standalone custom events (not matching any official event)
        if (ev.isCustomEvent) {
            bg.setColor(dangerSoft);
            textColor = danger;
        }

        List<String> displayLines = buildEventDisplayLines(ev);
        eventView.bind(ev, textColor, bg, buildEventContentDescription(displayLines));

        FrameLayoutWithChildren.LayoutParams lp = new FrameLayoutWithChildren.LayoutParams(
                FrameLayoutWithChildren.LayoutParams.MATCH_PARENT,
                FrameLayoutWithChildren.LayoutParams.WRAP_CONTENT);
        eventView.setLayoutParams(lp);

        eventView.setOnClickListener(v -> showEventDetails(ev, eventDate));
        return eventView;
    }

    private void showEventDetails(PlanRepository.PlanEventUi ev, LocalDate eventDate) {
        LocalDate targetDate = eventDate != null ? eventDate : currentDate;
        AddCustomEventDialog dialog = AddCustomEventDialog.newForEvent(targetDate, ev);
        dialog.setListener(event -> refreshAfterCustomEvent());
        dialog.setDetailsSearchClickListener((categoryKey, query) -> {
            String categoryLabel = resolveSearchCategoryLabel(categoryKey);
            performSearch(categoryKey, categoryLabel, query);
        });
        dialog.show(getParentFragmentManager(), "mark_custom_event");
    }

    private List<String> buildEventDisplayLines(PlanRepository.PlanEventUi ev) {
        List<String> lines = new ArrayList<>(4);
        if (ev == null) {
            return lines;
        }
        appendEventLine(lines, ev.hasCustomOverlay ? ev.customOverlayLabel : null);
        appendEventLine(lines, ev.title);

        String start = safe(ev.startStr);
        String end = safe(ev.endStr);
        if (!start.isEmpty() || !end.isEmpty()) {
            appendEventLine(lines, start.isEmpty() || end.isEmpty() ? start + end : start + " - " + end);
        }

        String room = safe(ev.room);
        String group = safe(ev.group);
        if (!room.isEmpty() || !group.isEmpty()) {
            appendEventLine(lines, room.isEmpty() ? group : (group.isEmpty() ? room : room + " • " + group));
        }
        return lines;
    }

    private CharSequence buildEventContentDescription(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private void appendEventLine(List<String> lines, String value) {
        String normalized = safe(value);
        if (!normalized.isEmpty()) {
            lines.add(normalized);
        }
    }

    private class NowLineView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int lineColor = 0xFFF43F5E;
        private String timeText = "";

        NowLineView(Context context) {
            super(context);
            setWillNotDraw(false);
            labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            labelPaint.setTextSize(spToPx(9f));
        }

        void bind(int color, String time) {
            lineColor = color != 0 ? color : 0xFFF43F5E;
            timeText = safe(time);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            float centerY = height / 2f;
            int faint = ColorUtils.setAlphaComponent(lineColor, 32);
            int strong = ColorUtils.setAlphaComponent(lineColor, 230);
            int tail = ColorUtils.setAlphaComponent(lineColor, 92);
            linePaint.setShader(new LinearGradient(
                    0f,
                    centerY,
                    width,
                    centerY,
                    new int[] { faint, strong, tail },
                    new float[] { 0f, 0.22f, 1f },
                    Shader.TileMode.CLAMP));
            rect.set(dpToPx(8), centerY - dpToPx(1), width - dpToPx(6), centerY + dpToPx(1));
            canvas.drawRoundRect(rect, dpToPx(999), dpToPx(999), linePaint);
            linePaint.setShader(null);

            linePaint.setColor(strong);
            canvas.drawCircle(dpToPx(7), centerY, dpToPx(4), linePaint);
            linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 220));
            canvas.drawCircle(dpToPx(7), centerY, dpToPx(1.7f), linePaint);

            if (width < dpToPx(76) || timeText.isEmpty()) {
                return;
            }

            float labelPadH = dpToPx(5);
            float labelHeight = dpToPx(15);
            float labelWidth = labelPaint.measureText(timeText) + labelPadH * 2f;
            float labelLeft = width - labelWidth - dpToPx(4);
            float labelTop = centerY - labelHeight / 2f;
            rect.set(labelLeft, labelTop, width - dpToPx(4), labelTop + labelHeight);
            linePaint.setColor(lineColor);
            canvas.drawRoundRect(rect, labelHeight / 2f, labelHeight / 2f, linePaint);

            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            labelPaint.setColor(Color.WHITE);
            float baseline = labelTop + (labelHeight - fm.ascent - fm.descent) / 2f;
            canvas.drawText(timeText, labelLeft + labelPadH, baseline, labelPaint);
        }
    }

    private class PlanEventBlockView extends View {
        private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint overlayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint decorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private PlanRepository.PlanEventUi event;
        private String timeText = "";
        private String titleText = "";
        private String footerText = "";
        private String badgeText = "";
        private String overlayText = "";
        private StaticLayout titleLayout;
        private int textColor = 0xFF1E293B;
        private int secondaryTextColor = 0xAA1E293B;
        private boolean textDirty = true;

        PlanEventBlockView(Context context) {
            super(context);
            setWillNotDraw(false);
            timePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            badgePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            overlayPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        void bind(
                PlanRepository.PlanEventUi event,
                int textColor,
                GradientDrawable background,
                CharSequence contentDescription) {
            this.event = event;
            this.textColor = textColor != 0 ? textColor : this.textColor;
            this.secondaryTextColor = ColorUtils.setAlphaComponent(this.textColor, Math.min(190, Color.alpha(this.textColor)));
            setBackground(background);
            setContentDescription(contentDescription);
            textDirty = true;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                textDirty = true;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (event == null) {
                return;
            }

            ensureTextLayout();
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            boolean compactWidth = width < dpToPx(82);
            boolean compactHeight = height < dpToPx(44);
            int padX = dpToPx(compactWidth ? 4 : 7);
            int padTop = dpToPx(compactHeight ? 4 : 6);
            int padBottom = dpToPx(5);

            Paint.FontMetrics timeFm = timePaint.getFontMetrics();
            float baseline = padTop - timeFm.ascent;
            timePaint.setColor(textColor);
            canvas.save();
            canvas.clipRect(padX, 0, Math.max(padX, width - padX), Math.min(height, baseline + timeFm.descent + dpToPx(2)));
            canvas.drawText(timeText, padX, baseline, timePaint);
            canvas.restore();

            if (!overlayText.isEmpty() && width > dpToPx(96) && height > dpToPx(52)) {
                drawOverlayPill(canvas, width, padTop);
            }

            float titleTop = baseline + dpToPx(compactHeight ? 4 : 6);
            if (shouldDrawHeaderDetail(height) && !footerText.isEmpty()) {
                Paint.FontMetrics metaFm = metaPaint.getFontMetrics();
                float detailBaseline = baseline + dpToPx(compactHeight ? 3 : 4) - metaFm.ascent;
                float detailBottom = detailBaseline + metaFm.descent;
                metaPaint.setColor(secondaryTextColor);
                canvas.save();
                canvas.clipRect(
                        padX,
                        baseline + timeFm.descent,
                        Math.max(padX, width - padX),
                        Math.min(height, detailBottom + dpToPx(2)));
                drawFittedSingleLine(canvas, footerText, metaPaint, padX, detailBaseline, Math.max(0, width - padX * 2));
                canvas.restore();
                titleTop = detailBottom + dpToPx(compactHeight ? 3 : 5);
            }

            if (titleLayout != null) {
                titlePaint.setColor(textColor);
                canvas.save();
                canvas.translate(padX, titleTop);
                titleLayout.draw(canvas);
                canvas.restore();
            }

            if (height >= dpToPx(38) && !badgeText.isEmpty()) {
                drawFooter(canvas, width, height, padX, padBottom);
            }
        }

        private void drawOverlayPill(Canvas canvas, int width, int padTop) {
            float padH = dpToPx(5);
            float pillHeight = dpToPx(15);
            float pillWidth = overlayPaint.measureText(overlayText) + padH * 2f;
            float left = width - pillWidth - dpToPx(6);
            if (left < dpToPx(46)) {
                return;
            }
            rect.set(left, padTop, width - dpToPx(6), padTop + pillHeight);
            decorPaint.setColor(ColorUtils.setAlphaComponent(ThemeManager.resolveColor(getContext(), R.attr.mzDanger), 34));
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, decorPaint);
            overlayPaint.setColor(ThemeManager.resolveColor(getContext(), R.attr.mzDanger));
            Paint.FontMetrics fm = overlayPaint.getFontMetrics();
            float baseline = padTop + (pillHeight - fm.ascent - fm.descent) / 2f;
            canvas.drawText(overlayText, left + padH, baseline, overlayPaint);
        }

        private void drawFooter(Canvas canvas, int width, int height, int padX, int padBottom) {
            Paint.FontMetrics badgeFm = badgePaint.getFontMetrics();
            float footerBaseline = height - padBottom - badgeFm.descent;
            float badgeWidth = badgePaint.measureText(badgeText);
            float badgeLeft = width - padX - badgeWidth;
            badgePaint.setColor(textColor);
            canvas.drawText(badgeText, badgeLeft, footerBaseline, badgePaint);
        }

        private void ensureTextLayout() {
            if (!textDirty) {
                return;
            }
            textDirty = false;
            titleLayout = null;

            int width = getWidth();
            int height = getHeight();
            if (event == null || width <= 0 || height <= 0) {
                return;
            }

            boolean compactWidth = width < dpToPx(82);
            boolean compactHeight = height < dpToPx(44);
            int padX = dpToPx(compactWidth ? 4 : 7);
            int padTop = dpToPx(compactHeight ? 4 : 6);
            int padBottom = dpToPx(5);
            int contentWidth = Math.max(0, width - padX * 2);

            overlayPaint.setTextSize(spToPx(8f));

            timeText = buildEventTimeText(event);
            String rawTitle = safe(event.title);
            titleText = stripTrailingTypeBadge(rawTitle);
            if (titleText.isEmpty()) {
                titleText = rawTitle;
            }
            footerText = buildFooterText(event, compactWidth);
            badgeText = buildTypeBadge(event, rawTitle);
            overlayText = safe(event.customOverlayLabel);

            timeText = fitEventTimeText(event, contentWidth, compactWidth ? 7.4f : 8.6f, compactWidth ? 9.3f : 10.5f);
            badgePaint.setTextSize(spToPx(compactWidth ? 8.2f : 9.0f));

            fitSingleLineText(metaPaint, footerText, contentWidth, compactWidth ? 4.8f : 5.2f, compactWidth ? 8.1f : 8.9f);

            Paint.FontMetrics timeFm = timePaint.getFontMetrics();
            float timeBaseline = padTop - timeFm.ascent;
            float titleTop = timeBaseline + dpToPx(compactHeight ? 4 : 6);
            if (shouldDrawHeaderDetail(height) && !footerText.isEmpty()) {
                Paint.FontMetrics metaFm = metaPaint.getFontMetrics();
                float detailBaseline = timeBaseline + dpToPx(compactHeight ? 3 : 4) - metaFm.ascent;
                float detailBottom = detailBaseline + metaFm.descent;
                titleTop = detailBottom + dpToPx(compactHeight ? 3 : 5);
            }
            int titleArea = Math.max(
                    dpToPx(8),
                    Math.round(height - titleTop - padBottom));
            float badgeAvoidWidth = shouldAvoidBadgeInTitle(height)
                    ? badgePaint.measureText(badgeText) + dpToPx(6)
                    : 0f;
            titleLayout = buildBestTitleLayout(
                    titleText,
                    contentWidth,
                    titleArea,
                    compactWidth,
                    compactHeight,
                    badgeAvoidWidth);
        }

        private boolean shouldDrawHeaderDetail(int height) {
            return height >= dpToPx(54);
        }

        private boolean shouldAvoidBadgeInTitle(int height) {
            return height >= dpToPx(50) && !badgeText.isEmpty();
        }

        private StaticLayout buildBestTitleLayout(
                String text,
                int availableWidth,
                int availableHeight,
                boolean compactWidth,
                boolean compactHeight,
                float bottomEndAvoidWidth) {
            if (TextUtils.isEmpty(text) || availableWidth <= 0 || availableHeight <= 0) {
                return null;
            }

            float maxSp = compactWidth ? 10.2f : 11.8f;
            float minSp = compactWidth ? 5.6f : 6.2f;
            StaticLayout fallback = null;

            for (float sp = maxSp; sp >= minSp; sp -= 0.35f) {
                titlePaint.setTextSize(spToPx(sp));
                int lineHeight = Math.max(1, titleLineHeight());
                int maxLines = Math.max(1, Math.min(compactHeight ? 2 : 7, availableHeight / lineHeight));
                StaticLayout candidate = makeTitleLayoutWithBadgeAvoidance(
                        text,
                        availableWidth,
                        maxLines,
                        bottomEndAvoidWidth);
                fallback = candidate;
                if (candidate != null && candidate.getHeight() <= availableHeight && !hasEllipsis(candidate)) {
                    return candidate;
                }
            }

            titlePaint.setTextSize(spToPx(minSp));
            int lineHeight = Math.max(1, titleLineHeight());
            int maxLines = Math.max(1, Math.min(compactHeight ? 2 : 7, availableHeight / lineHeight));
            StaticLayout clipped = makeTitleLayoutWithBadgeAvoidance(
                    text,
                    availableWidth,
                    maxLines,
                    bottomEndAvoidWidth);
            return clipped != null ? clipped : fallback;
        }

        private StaticLayout makeTitleLayoutWithBadgeAvoidance(
                String text,
                int availableWidth,
                int maxLines,
                float bottomEndAvoidWidth) {
            int safeMaxLines = Math.max(1, maxLines);
            if (bottomEndAvoidWidth <= 0f || safeMaxLines <= 1 || availableWidth < dpToPx(42)) {
                return makeTitleLayout(text, availableWidth, safeMaxLines);
            }

            int[] leftIndents = new int[safeMaxLines];
            int[] rightIndents = new int[safeMaxLines];
            rightIndents[safeMaxLines - 1] = Math.max(0, Math.round(bottomEndAvoidWidth));

            return StaticLayout.Builder
                    .obtain(text, 0, text.length(), titlePaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 0.92f)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setMaxLines(safeMaxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(availableWidth)
                    .setIndents(leftIndents, rightIndents)
                    .build();
        }

        private StaticLayout makeTitleLayout(String text, int availableWidth, int maxLines) {
            return StaticLayout.Builder
                    .obtain(text, 0, text.length(), titlePaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 0.92f)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setMaxLines(Math.max(1, maxLines))
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(availableWidth)
                    .build();
        }

        private boolean hasEllipsis(StaticLayout layout) {
            if (layout == null) {
                return false;
            }
            for (int i = 0; i < layout.getLineCount(); i++) {
                if (layout.getEllipsisCount(i) > 0) {
                    return true;
                }
            }
            return false;
        }

        private void fitSingleLineText(
                TextPaint paint,
                String text,
                float availableWidth,
                float minSp,
                float maxSp) {
            String value = safe(text);
            if (value.isEmpty() || availableWidth <= 0f) {
                paint.setTextSize(spToPx(minSp));
                return;
            }
            for (float sp = maxSp; sp >= minSp; sp -= 0.25f) {
                paint.setTextSize(spToPx(sp));
                if (paint.measureText(value) <= availableWidth) {
                    return;
                }
            }
            paint.setTextSize(spToPx(minSp));
        }

        private void drawFittedSingleLine(
                Canvas canvas,
                String text,
                TextPaint paint,
                float x,
                float baseline,
                float availableWidth) {
            String value = safe(text);
            if (value.isEmpty() || availableWidth <= 0f) {
                return;
            }
            float textWidth = paint.measureText(value);
            if (textWidth <= availableWidth || textWidth <= 0f) {
                canvas.drawText(value, x, baseline, paint);
                return;
            }

            float scale = Math.min(1f, availableWidth / textWidth);
            if (scale < 0.86f) {
                CharSequence clipped = TextUtils.ellipsize(
                        value,
                        paint,
                        availableWidth,
                        TextUtils.TruncateAt.MIDDLE);
                canvas.drawText(clipped.toString(), x, baseline, paint);
                return;
            }
            canvas.save();
            canvas.scale(scale, 1f, x, baseline);
            canvas.drawText(value, x, baseline, paint);
            canvas.restore();
        }

        private String fitEventTimeText(
                PlanRepository.PlanEventUi ev,
                float availableWidth,
                float minSp,
                float maxSp) {
            String full = buildEventTimeText(ev);
            String start = safe(ev != null ? ev.startStr : "");
            String end = safe(ev != null ? ev.endStr : "");
            String compact = !start.isEmpty() ? start : end;
            if (compact.isEmpty()) {
                compact = full;
            }

            if (full.isEmpty() || availableWidth <= 0f) {
                timePaint.setTextSize(spToPx(minSp));
                return "";
            }

            String fittedFull = tryFitEventTimeCandidate(full, availableWidth, minSp, maxSp);
            if (!fittedFull.isEmpty()) {
                return fittedFull;
            }

            String fittedCompact = tryFitEventTimeCandidate(compact, availableWidth, minSp, maxSp);
            if (!fittedCompact.isEmpty()) {
                return fittedCompact;
            }

            timePaint.setTextSize(spToPx(minSp));
            return TextUtils.ellipsize(
                    compact,
                    timePaint,
                    Math.max(0f, availableWidth),
                    TextUtils.TruncateAt.END).toString();
        }

        private String tryFitEventTimeCandidate(
                String text,
                float availableWidth,
                float minSp,
                float maxSp) {
            String value = safe(text);
            if (value.isEmpty()) {
                return "";
            }
            for (float sp = maxSp; sp >= minSp; sp -= 0.25f) {
                timePaint.setTextSize(spToPx(sp));
                if (timePaint.measureText(value) <= availableWidth) {
                    return value;
                }
            }
            return "";
        }

        private String buildEventTimeText(PlanRepository.PlanEventUi ev) {
            String start = safe(ev.startStr);
            String end = safe(ev.endStr);
            if (start.isEmpty()) {
                return end;
            }
            if (end.isEmpty()) {
                return start;
            }
            return start + "-" + end;
        }

        private String buildFooterText(PlanRepository.PlanEventUi ev, boolean compactWidth) {
            String room = safe(ev.room);
            String group = safe(ev.group);
            if (!room.isEmpty() && !group.isEmpty()) {
                return room + (compactWidth ? "/" : " / ") + group;
            }
            if (!room.isEmpty()) {
                return room;
            }
            return group;
        }

        private String buildTypeBadge(PlanRepository.PlanEventUi ev, String rawTitle) {
            String trailing = extractTrailingTypeBadge(rawTitle);
            if (!trailing.isEmpty()) {
                return "(" + trailing + ")";
            }

            String type = safe(ev.typeClass).toLowerCase(java.util.Locale.ROOT);
            if (type.contains("lecture")) {
                return "(W)";
            }
            if (type.contains("lab")) {
                return "(L)";
            }
            if (type.contains("auditory")) {
                return "(A)";
            }
            if (type.contains("lectorate")) {
                return "(Lek)";
            }
            if (type.contains("exam")) {
                return "(E)";
            }
            if (type.contains("pass")) {
                return "(Zal)";
            }
            return "";
        }

        private String stripTrailingTypeBadge(String text) {
            String badge = extractTrailingTypeBadge(text);
            if (badge.isEmpty()) {
                return safe(text);
            }
            String trimmed = safe(text);
            int open = trimmed.lastIndexOf('(');
            return open > 0 ? trimmed.substring(0, open).trim() : trimmed;
        }

        private String extractTrailingTypeBadge(String text) {
            String trimmed = safe(text);
            if (!trimmed.endsWith(")")) {
                return "";
            }
            int open = trimmed.lastIndexOf('(');
            if (open < 0 || open >= trimmed.length() - 2) {
                return "";
            }
            String inside = trimmed.substring(open + 1, trimmed.length() - 1).trim();
            if (inside.isEmpty() || inside.length() > 5 || inside.contains(" ")) {
                return "";
            }
            return inside;
        }

        private int titleLineHeight() {
            return textLineHeight(titlePaint) + dpToPx(1);
        }

        private int textLineHeight(TextPaint paint) {
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            return Math.max(dpToPx(10), metrics.descent - metrics.ascent);
        }
    }

    private String formatDayHeader(LocalDate date) {
        if (date == null)
            return "";
        String shortName;
        switch (date.getDayOfWeek()) {
            case MONDAY:
                shortName = getString(R.string.plan_header_mon_short);
                break;
            case TUESDAY:
                shortName = getString(R.string.plan_header_tue_short);
                break;
            case WEDNESDAY:
                shortName = getString(R.string.plan_header_wed_short);
                break;
            case THURSDAY:
                shortName = getString(R.string.plan_header_thu_short);
                break;
            case FRIDAY:
                shortName = getString(R.string.plan_header_fri_short);
                break;
            case SATURDAY:
                shortName = getString(R.string.plan_header_sat_short);
                break;
            case SUNDAY:
            default:
                shortName = getString(R.string.plan_header_sun_short);
                break;
        }
        return shortName + "\n" + date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"));
    }

    private GradientDrawable buildRoundedBg(int fillColor, int strokeColor) {
        return buildRoundedBg(fillColor, strokeColor, 10);
    }

    private GradientDrawable buildRoundedBg(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dpToPx(radiusDp));
        bg.setStroke(dpToPx(1), strokeColor);
        return bg;
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sp,
                getResources().getDisplayMetrics());
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public static class FrameLayoutWithChildren extends android.widget.FrameLayout {
        public FrameLayoutWithChildren(Context context) {
            super(context);
        }

        public FrameLayoutWithChildren(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
            return p instanceof LayoutParams;
        }

        @Override
        protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
            return new LayoutParams(p);
        }

        @Override
        public LayoutParams generateLayoutParams(AttributeSet attrs) {
            return new LayoutParams(getContext(), attrs);
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        public static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
            public LayoutParams(Context c, AttributeSet attrs) {
                super(c, attrs);
            }

            public LayoutParams(int width, int height) {
                super(width, height);
            }

            public LayoutParams(ViewGroup.LayoutParams source) {
                super(source);
            }
        }
    }
}
