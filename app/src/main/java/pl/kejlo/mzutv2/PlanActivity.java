package pl.kejlo.mzutv2;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.navigation.NavigationView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.core.content.ContextCompat;

public class PlanActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
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
                if (m.id.equals(id)) return m;
            }
            return WEEK;
        }
    }

    private static final String PREFS_NAME = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    private static final String KEY_FILTER_CACHE_JSON = "plan_filters_cache_json";
    private static final String KEY_FILTER_CACHE_TS = "plan_filters_cache_ts";
    private static final long FILTER_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    private static final int START_HOUR = 6;
    private static final int END_HOUR = 22;
    private static final float HOUR_HEIGHT_DP = 48f;
    private static final float DAY_HEADER_HEIGHT_DP = 48f;
    private static final float MONTH_CELL_HEIGHT_DP = 70f;

    private static final int VP_START_POSITION = 5000;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private LinearLayout controlsContainer;
    private Button btnViewDay;
    private Button btnViewWeek;
    private Button btnViewMonth;

    private LinearLayout actionsContainer;
    private ImageButton btnSearch;
    private ImageButton btnRefresh;
    private ImageButton btnMenu;

    private TextView tvHeaderLabel;
    private TextView tvEmpty;

    private LinearLayout layoutTimeColumn;

    private LinearLayout layoutWeekHeadersFixed;
    private LinearLayout layoutWeekHeadersRow;
    private LinearLayout layoutMonthHeadersFixed;

    private ViewPager2 viewPager;
    private PlanPagerAdapter pagerAdapter;

    private ProgressBar progress;

    private String viewModeId = ViewMode.WEEK.getId();

    private LocalDate baseDate = LocalDate.now();
    private LocalDate currentDate = LocalDate.now();

    private PlanRepository planRepository;
    private final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SharedPreferences prefs;
    private Set<String> hiddenSubjectKeys = new HashSet<>();

    private PlanRepository.SearchParams currentSearchQuery = null;

    private android.animation.ObjectAnimator mRefreshAnimator;

    private static class PlanKey {
        final String viewModeId;
        final LocalDate date;

        PlanKey(String v, LocalDate d) {
            this.viewModeId = v;
            this.date = d;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlanKey)) return false;
            PlanKey pk = (PlanKey) o;
            return viewModeId.equals(pk.viewModeId) && date.equals(pk.date);
        }

        @Override
        public int hashCode() {
            return viewModeId.hashCode() * 31 + date.hashCode();
        }
    }

    private final LinkedHashMap<PlanKey, PlanRepository.PlanResult> planCache =
            new LinkedHashMap<PlanKey, PlanRepository.PlanResult>(20, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PlanKey, PlanRepository.PlanResult> eldest) {
                    return size() > 20;
                }
            };

    private void putPlanInCache(String modeId, LocalDate date, PlanRepository.PlanResult result) {
        if (modeId == null || date == null || result == null) return;
        planCache.put(new PlanKey(modeId, date), result);
    }

    private PlanRepository.PlanResult getPlanFromCache(String modeId, LocalDate date) {
        if (modeId == null || date == null) return null;
        return planCache.get(new PlanKey(modeId, date));
    }

    private final Handler nowLineHandler = new Handler(Looper.getMainLooper());
    private final Runnable nowLineRunnable = new Runnable() {
        @Override
        public void run() {
            updateNowLineInVisiblePage();
            scheduleNextNowLineUpdate();
        }
    };

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
            if (col == null || col.date == null) continue;

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
                if (i == satIndex || i == sunIndex) continue;
                filtered.add(allColumns.get(i));
            }
            return filtered;
        }

        return allColumns;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();
        if (session.getAuthKey() == null || session.getUserId() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_plan);

        planRepository = new PlanRepository(getApplicationContext());
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenSubjectKeys = new HashSet<>(prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>()));

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        ScrollView scrollPlan = findViewById(R.id.scrollPlan);
        scrollPlan.setClipToPadding(false);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Top padding for status bar, sides safe guard. Bottom is 0 to let content go behind navbar
            v.setPadding(insets.left, insets.top, insets.right, 0);
            return windowInsets;
        });

        ViewCompat.setOnApplyWindowInsetsListener(scrollPlan, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Bottom padding for navigation bar so last item is reachable
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setTitle(R.string.plan_title);
        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.PLAN
        );

        btnViewDay = findViewById(R.id.btnViewDay);
        btnViewWeek = findViewById(R.id.btnViewWeek);
        btnViewMonth = findViewById(R.id.btnViewMonth);
        btnSearch = findViewById(R.id.btnSearch);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnMenu = findViewById(R.id.btnMenu);

        tvHeaderLabel = findViewById(R.id.tvHeaderLabel);
        tvEmpty = findViewById(R.id.tvEmpty);

        layoutTimeColumn = findViewById(R.id.layoutTimeColumn);
        progress = findViewById(R.id.planProgress);

        layoutWeekHeadersFixed = findViewById(R.id.layoutWeekHeadersFixed);
        layoutWeekHeadersRow = findViewById(R.id.layoutWeekHeadersRow);
        layoutMonthHeadersFixed = findViewById(R.id.layoutMonthHeadersFixed);

        viewPager = findViewById(R.id.planViewPager);

        setupWeekdayHeadersForMonth();
        setupTimeColumn();
        setupViewModeButtons();
        setupMenuButton();
        setupSearchButton();
        setupRefreshButton();

        if (viewPager != null) {
            pagerAdapter = new PlanPagerAdapter(this);
            viewPager.setAdapter(pagerAdapter);
            viewPager.setCurrentItem(VP_START_POSITION, false);
            viewPager.setNestedScrollingEnabled(false);

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
                            if (cached.headerLabel != null) tvHeaderLabel.setText(cached.headerLabel);
                        } else {
                            updateFixedWeekHeaders(cached.dayColumns, currentDate);
                        }
                    } else {
                        if (!isMonthMode()) {
                            updateFixedWeekHeaders(Collections.emptyList(), currentDate);
                        }
                    }
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

        loadPlanForCurrentMode();
        runIntroAnimations();
    }

    private void runIntroAnimations() {
        View[] topControls = {btnViewDay, btnViewWeek, btnViewMonth};
        View[] actions = {tvHeaderLabel, btnSearch, btnRefresh, btnMenu};
        View mainContent = viewPager;
        View sideColumn = layoutTimeColumn;
        View headerRow = layoutWeekHeadersFixed;

        // Reset
        for(View v : topControls) if(v != null) { v.setAlpha(0f); v.setTranslationY(-20f); }
        for(View v : actions) if(v != null) { v.setAlpha(0f); v.setTranslationY(-20f); }
        if(mainContent != null) { mainContent.setAlpha(0f); mainContent.setTranslationY(50f); }
        if(sideColumn != null) { sideColumn.setAlpha(0f); sideColumn.setTranslationX(-30f); }
        if(headerRow != null) { headerRow.setAlpha(0f); headerRow.setTranslationY(-20f); }

        long delay = 100;

        for (View v : topControls) {
            if(v != null) animateIn(v, delay);
            delay += 50;
        }

        delay = 200;
        for (View v : actions) {
            if(v != null) animateIn(v, delay);
            delay += 50;
        }

        if (headerRow != null) animateIn(headerRow, 300);
        if (sideColumn != null) animateIn(sideColumn, 350);
        if (mainContent != null) animateIn(mainContent, 400);
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
        if (viewPager == null) return;

        int gridHeight;

        if (isMonthMode()) {
            gridHeight = dpToPx(6 * MONTH_CELL_HEIGHT_DP + 16);
            if (layoutTimeColumn != null) layoutTimeColumn.setVisibility(View.GONE);
            if (layoutWeekHeadersFixed != null) layoutWeekHeadersFixed.setVisibility(View.GONE);
            if (layoutMonthHeadersFixed != null) layoutMonthHeadersFixed.setVisibility(View.VISIBLE);

        } else {
            float totalHours = END_HOUR - START_HOUR;
            gridHeight = dpToPx(totalHours * HOUR_HEIGHT_DP + DAY_HEADER_HEIGHT_DP + 8);

            if (layoutTimeColumn != null) layoutTimeColumn.setVisibility(View.VISIBLE);
            if (layoutTimeColumn != null) {
                ViewGroup.LayoutParams tp = layoutTimeColumn.getLayoutParams();
                if (tp != null) {
                    tp.height = gridHeight;
                    layoutTimeColumn.setLayoutParams(tp);
                }
            }
            if (layoutWeekHeadersFixed != null) layoutWeekHeadersFixed.setVisibility(View.VISIBLE);
            if (layoutMonthHeadersFixed != null) layoutMonthHeadersFixed.setVisibility(View.GONE);
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) viewPager.getLayoutParams();
        if (params == null) {
            params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    gridHeight
            );
        } else {
            params.height = gridHeight;
        }
        viewPager.setLayoutParams(params);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        if (!isMonthMode()) {
            updateNowLineInVisiblePage();
            scheduleNextNowLineUpdate();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nowLineHandler.removeCallbacks(nowLineRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        if (mRefreshAnimator != null) {
            mRefreshAnimator.cancel();
            mRefreshAnimator = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("viewMode", viewModeId);
        outState.putString("currentDate", currentDate.format(YMD));
    }

    private ViewMode getCurrentViewMode() {
        return ViewMode.fromId(viewModeId);
    }

    private void setCurrentViewMode(ViewMode mode) {
        if (mode != null) viewModeId = mode.getId();
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

        if (isMonthMode()) {
            baseDate = baseDate.with(TemporalAdjusters.firstDayOfMonth());
            currentDate = currentDate.with(TemporalAdjusters.firstDayOfMonth());
        }

        setupHeightForViewPager();

        if (pagerAdapter != null) pagerAdapter.notifyDataSetChanged();

        if (viewPager != null) {
            int pos = viewPager.getCurrentItem();
            if (pos == VP_START_POSITION) {
                updateCurrentDateFromPosition(pos);
            } else {
                viewPager.setCurrentItem(VP_START_POSITION, false);
                updateCurrentDateFromPosition(VP_START_POSITION);
            }

            PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, currentDate);
            if (cached != null) {
                if (!isMonthMode()) updateFixedWeekHeaders(cached.dayColumns, currentDate);
                if (cached.headerLabel != null) tvHeaderLabel.setText(cached.headerLabel);
            } else {
                if (!isMonthMode()) updateFixedWeekHeaders(Collections.emptyList(), currentDate);
            }
        }
    }

    private void setupViewModeButtons() {
        View.OnClickListener modeClick = v -> {
            ViewMode newMode = ViewMode.WEEK;
            if (v == btnViewDay) newMode = ViewMode.DAY;
            else if (v == btnViewWeek) newMode = ViewMode.WEEK;
            else if (v == btnViewMonth) newMode = ViewMode.MONTH;

            if (!newMode.getId().equals(viewModeId)) {
                setCurrentViewMode(newMode);
                baseDate = currentDate;
                if (viewPager != null) {
                    viewPager.setCurrentItem(VP_START_POSITION, false);
                }
                loadPlanForCurrentMode();
            }
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
                PopupMenu popup = new PopupMenu(this, v);

                popup.getMenu().add(0, 1, 0, R.string.plan_button_today);
                popup.getMenu().add(0, 3, 2, R.string.plan_button_filters);

                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1:
                            goToToday();
                            return true;
                        case 3:
                            new LoadSubjectsForFilterTask(false).execute();
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
        LocalDate today = LocalDate.now();
        currentDate = today;
        baseDate = today;

        if (isMonthMode()) {
            baseDate = today.with(TemporalAdjusters.firstDayOfMonth());
            currentDate = baseDate;
        }

        if (viewPager != null) {
            viewPager.setCurrentItem(VP_START_POSITION, true);
        }
        if (pagerAdapter != null) pagerAdapter.notifyDataSetChanged();
    }

    private void setupRefreshButton() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                startRefreshAnimation();
                if (currentSearchQuery != null) {
                    currentSearchQuery = null;
                    Toast.makeText(this, R.string.plan_toast_resetting_search, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.plan_toast_refreshed, Toast.LENGTH_SHORT).show();
                }
                planCache.clear();
                loadPlanForCurrentMode();
            });
        }
    }

    private void setupSearchButton() {
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
    }

    // --- Saved Searches ---
    private static final String KEY_SAVED_SEARCHES = "plan_saved_searches_json";

    private void showSearchDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8));

        // Header row: Spinner + Save Button + Clipboard Button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final Spinner spinner = new Spinner(this);
        String[] displayCategories = {
                getString(R.string.plan_search_cat_album),
                getString(R.string.plan_search_cat_teacher),
                getString(R.string.plan_search_cat_room),
                getString(R.string.plan_search_cat_subject),
                getString(R.string.plan_search_cat_group)
        };
        final String[] apiCategories = {
                "Numer albumu",
                "Wykładowca",
                "Sala",
                "Przedmiot",
                "Grupa"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayCategories);
        spinner.setAdapter(adapter);
        
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(spinner, spinnerLp);

        // Clipboard (List) Button
        ImageButton btnClipboard = new ImageButton(this);
        btnClipboard.setImageResource(android.R.drawable.ic_menu_save); // Using a generic save/list icon available in standard android or AppCompat
        // Better: use a specific icon if available, but ic_menu_save or similar works for "Saved items" context somewhat
        // Let's use a standard localized string "Saved" on a small button if icon is risky, but ImageButton is requested ("new icon").
        // Safest standard icon: android.R.drawable.ic_menu_agenda or similar. Let's try basic "ic_menu_save" as placeholder for "Saved"
        // Actually, user said "clipboard icon". android.R.drawable.ic_menu_paste is close? 
        // Let's use android.R.drawable.ic_menu_my_calendar as it looks like a list/clipboard often.
        btnClipboard.setImageResource(android.R.drawable.ic_menu_my_calendar); 
        btnClipboard.setBackgroundColor(0); // transparent
        btnClipboard.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        headerRow.addView(btnClipboard);

        final EditText input = new EditText(this);
        input.setHint(R.string.plan_search_hint_input);
        input.setSingleLine(true);
        layout.addView(input);
        
        // Save Search Button (small, below input or next to Search? Dialog buttons are standard)
        // User asked for "Save option in this window". 
        // Let's put a "Save this query" button/icon inside the layout.
        
        Button btnSaveQuery = new Button(this);
        btnSaveQuery.setText(R.string.plan_search_save_label); 
        btnSaveQuery.setVisibility(View.VISIBLE);
        btnSaveQuery.setTextSize(12f);
        
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 
                dpToPx(36));
        saveLp.gravity = Gravity.END;
        layout.addView(btnSaveQuery, saveLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.plan_search_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.plan_search_button, (d, which) -> {
                    int pos = spinner.getSelectedItemPosition();
                    String categoryKey = (pos >= 0 && pos < apiCategories.length) ? apiCategories[pos] : apiCategories[0];
                    String categoryLabel = (pos >= 0 && pos < displayCategories.length) ? displayCategories[pos] : displayCategories[0];
                    String query = input.getText().toString().trim();
                    if (!query.isEmpty()) {
                        performSearch(categoryKey, categoryLabel, query);
                    }
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .create();

        btnClipboard.setOnClickListener(v -> {
            dialog.dismiss();
            showSavedSearchesDialog();
        });

        btnSaveQuery.setOnClickListener(v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) {
                Toast.makeText(this, R.string.plan_search_empty_query, Toast.LENGTH_SHORT).show();
                return;
            }
            int pos = spinner.getSelectedItemPosition();
            String catKey = (pos >= 0 && pos < apiCategories.length) ? apiCategories[pos] : apiCategories[0];
            String catLabel = (pos >= 0 && pos < displayCategories.length) ? displayCategories[pos] : displayCategories[0];
            
            showSaveQueryDialog(catKey, catLabel, q);
            dialog.dismiss();
        });

        dialog.show();
    }
    
    private void showSaveQueryDialog(String catKey, String catLabel, String query) {
        EditText inputLabel = new EditText(this);
        inputLabel.setHint(R.string.plan_search_save_hint_label);
        
        int pad = dpToPx(20);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(pad, pad, pad, pad);
        container.addView(inputLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.plan_search_save_title)
            .setView(container)
            .setPositiveButton(R.string.plan_search_save_confirm, (d, w) -> {
                 String label = inputLabel.getText().toString().trim();
                 if (label.isEmpty()) label = query; // fallback
                 saveSearchQuery(label, catKey, catLabel, query);
            })
            .setNegativeButton(R.string.plan_filters_cancel, null)
            .show();
    }

    private void saveSearchQuery(String label, String catKey, String catLabel, String query) {
        List<SavedSearch> list = loadSavedSearches();
        list.add(new SavedSearch(label, catKey, catLabel, query));
        saveSavedSearches(list);
        Toast.makeText(this, R.string.plan_search_saved_toast, Toast.LENGTH_SHORT).show();
    }

    private void showSavedSearchesDialog() {
        List<SavedSearch> list = loadSavedSearches();
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.plan_search_no_saved, Toast.LENGTH_SHORT).show();
            showSearchDialog(); // Go back
            return;
        }

        String[] items = new String[list.size()];
        for(int i=0; i<list.size(); i++) {
            items[i] = list.get(i).label + " (" + list.get(i).catLabel + ": " + list.get(i).query + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.plan_search_saved_title)
                .setItems(items, (d, which) -> {
                    SavedSearch s = list.get(which);
                    performSearch(s.catKey, s.catLabel, s.query);
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .setNeutralButton(R.string.plan_search_saved_clear, (d, w) -> {
                     saveSavedSearches(new ArrayList<>());
                     Toast.makeText(this, R.string.plan_search_saved_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // -- Saved Search Persistence --
    
    private static class SavedSearch {
        String label;
        String catKey;
        String catLabel;
        String query;
        
        SavedSearch(String l, String k, String cl, String q) {
            this.label = l; this.catKey = k; this.catLabel = cl; this.query = q;
        }
    }

    private List<SavedSearch> loadSavedSearches() {
        String json = prefs.getString(KEY_SAVED_SEARCHES, null);
        List<SavedSearch> list = new ArrayList<>();
        if (json == null) return list;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for(int i=0; i<arr.length(); i++) {
                 org.json.JSONObject o = arr.getJSONObject(i);
                 list.add(new SavedSearch(
                     o.optString("lbl"),
                     o.optString("ck"),
                     o.optString("cl"),
                     o.optString("q")
                 ));
            }
        } catch(Exception ignored){}
        return list;
    }
    
    private void saveSavedSearches(List<SavedSearch> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for(SavedSearch s : list) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("lbl", s.label);
                o.put("ck", s.catKey);
                o.put("cl", s.catLabel);
                o.put("q", s.query);
                arr.put(o);
            } catch(Exception ignored){}
        }
        prefs.edit().putString(KEY_SAVED_SEARCHES, arr.toString()).apply();
    }

    private void performSearch(String categoryKey, String categoryLabel, String query) {
        currentSearchQuery = new PlanRepository.SearchParams();
        currentSearchQuery.category = categoryKey;
        currentSearchQuery.query = query;

        planCache.clear();
        loadPlanForCurrentMode();

        String msg = getString(R.string.plan_toast_search_prefix, categoryLabel, query);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void startRefreshAnimation() {
        if (btnRefresh == null) return;
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
            TextView tv = new TextView(this);
            tv.setText(String.format("%02d:00", h));
            tv.setTextColor(ContextCompat.getColor(this, R.color.plan_time_column_text));
            tv.setTextSize(11f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(HOUR_HEIGHT_DP)
            );
            tv.setGravity(Gravity.END | Gravity.TOP);
            tv.setPadding(0, dpToPx(2), dpToPx(4), 0);
            tv.setLayoutParams(lp);
            layoutTimeColumn.addView(tv);
        }
    }

    private void setupWeekdayHeadersForMonth() {
        if (layoutMonthHeadersFixed == null) return;
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
            TextView tv = new TextView(this);
            tv.setText(d);
            tv.setTextColor(ContextCompat.getColor(this, R.color.plan_month_weekday_text));
            tv.setTextSize(11f);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
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
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            return new PlanPageViewHolder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull PlanPageViewHolder holder, int position) {
            int diff = position - VP_START_POSITION;
            LocalDate pageDate;

            if (isDayMode()) {
                pageDate = baseDate.plusDays(diff);
            } else if (isWeekMode()) {
                pageDate = baseDate.plusWeeks(diff);
            } else {
                pageDate = baseDate.plusMonths(diff).with(TemporalAdjusters.firstDayOfMonth());
            }

            holder.container.removeAllViews();

            PlanRepository.PlanResult cached = getPlanFromCache(viewModeId, pageDate);

            if (cached != null) {
                if (isMonthMode()) {
                    renderMonthPage(holder, cached);
                } else {
                    renderWeekPage(holder, cached, pageDate);
                }
            } else {
                showLoadingState(holder);
                new LoadPageTask(position, pageDate, viewModeId)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }

        @Override
        public int getItemCount() {
            return 10000;
        }

        private void showLoadingState(PlanPageViewHolder holder) {
            TextView loadingView = new TextView(context);
            loadingView.setText(R.string.plan_filters_loading);
            loadingView.setGravity(Gravity.CENTER);
            holder.container.addView(loadingView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }

        private void renderWeekPage(PlanPageViewHolder holder,
                                    PlanRepository.PlanResult result,
                                    LocalDate pageDate) {

            LinearLayout columnsContainer = new LinearLayout(context);
            columnsContainer.setOrientation(LinearLayout.HORIZONTAL);
            holder.container.addView(columnsContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            float totalHours = END_HOUR - START_HOUR;
            int columnHeight = (int) (dpToPx(HOUR_HEIGHT_DP) * totalHours);

            List<PlanRepository.DayColumn> rawCols =
                    result.dayColumns != null ? result.dayColumns : Collections.emptyList();
            List<PlanRepository.DayColumn> cols = getVisibleColumns(rawCols);

            boolean hasAnyEvents = false;
            for (PlanRepository.DayColumn col : cols) {
                if (col.events != null && !col.events.isEmpty()) {
                    for (PlanRepository.PlanEventUi ev : col.events) {
                        if (!shouldHideEvent(ev)) {
                            hasAnyEvents = true;
                            break;
                        }
                    }
                }
                if (hasAnyEvents) break;
            }

            if (cols.isEmpty() || !hasAnyEvents) {
                TextView empty = new TextView(context);
                empty.setText(R.string.plan_no_classes_in_range);
                empty.setGravity(Gravity.CENTER);
                columnsContainer.addView(empty, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }

            LocalDate today = LocalDate.now();

            for (PlanRepository.DayColumn col : cols) {

                boolean isToday = col.date != null && col.date.equals(today);
                boolean highlight = isToday;

                LinearLayout dayColumn = new LinearLayout(context);
                dayColumn.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                );
                dayLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
                dayColumn.setLayoutParams(dayLp);

                FrameLayoutWithChildren dayBody = new FrameLayoutWithChildren(context);
                LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        columnHeight
                );
                dayBody.setLayoutParams(bodyLp);
                dayBody.setBackgroundColor(ContextCompat.getColor(
                        context,
                        highlight ? R.color.plan_week_day_selected_bg : R.color.plan_week_day_bg
                ));

                addHourLines(dayBody);

                List<PlanRepository.PlanEventUi> events =
                        col.events != null ? col.events : Collections.emptyList();

                List<RenderedEvent> renderedEvents = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : events) {
                    if (shouldHideEvent(ev)) continue;
                    View evView = createEventView(ev);
                    dayBody.addView(evView);
                    renderedEvents.add(new RenderedEvent(ev, evView));
                }

                LocalDate colDate = col.date;
                dayBody.post(() -> {
                    int w = dayBody.getWidth();
                    if (w <= 0) return;
                    layoutEventsInDayBody(dayBody, renderedEvents, w);
                    if (colDate != null && colDate.equals(today)) {
                        dayBody.setTag("TODAY_BODY");
                        updateNowLineInVisiblePage();
                    }
                });

                dayColumn.addView(dayBody);
                columnsContainer.addView(dayColumn);
            }
        }

        private void renderMonthPage(PlanPageViewHolder holder, PlanRepository.PlanResult result) {
            GridLayout grid = new GridLayout(context);
            grid.setColumnCount(7);
            grid.setUseDefaultMargins(false);

            holder.container.addView(grid, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            List<List<PlanRepository.MonthCell>> gridData =
                    result.monthGrid != null ? result.monthGrid : Collections.emptyList();

            if (gridData.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText(R.string.plan_no_classes_in_range);
                empty.setGravity(Gravity.CENTER);
                holder.container.removeAllViews();
                holder.container.addView(empty);
                return;
            }

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
                    cellRoot.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));

                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                    lp.width = 0;
                    lp.height = dpToPx(MONTH_CELL_HEIGHT_DP);
                    lp.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    cellRoot.setLayoutParams(lp);

                    if (cell.hasPlan) {
                        cellRoot.setBackgroundColor(ContextCompat.getColor(context, R.color.plan_month_cell_with_events_bg));
                    } else {
                        cellRoot.setBackgroundColor(ContextCompat.getColor(context, R.color.plan_month_cell_bg));
                    }

                    TextView tvNum = new TextView(context);
                    tvNum.setText(String.valueOf(cell.date.getDayOfMonth()));
                    tvNum.setTextColor(ContextCompat.getColor(context, R.color.plan_month_day_number));
                    tvNum.setTextSize(13f);
                    cellRoot.addView(tvNum);

                    if (cell.hasPlan) {
                        TextView tvHint = new TextView(context);
                        tvHint.setText(R.string.plan_month_cell_has_events_hint);
                        tvHint.setTextSize(9f);
                        tvHint.setTextColor(ContextCompat.getColor(context, R.color.plan_month_day_hint));
                        cellRoot.addView(tvHint);

                        cellRoot.setOnClickListener(v -> {
                            setCurrentViewMode(ViewMode.DAY);
                            currentDate = cell.date;
                            baseDate = currentDate;
                            if (viewPager != null) viewPager.setCurrentItem(VP_START_POSITION, false);
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

        PlanPageViewHolder(FrameLayout container) {
            super(container);
            this.container = container;
        }
    }

    private class LoadPageTask extends AsyncTask<Void, Void, PlanRepository.PlanResult> {
        private final int pos;
        private final LocalDate date;
        private final String modeId;

        LoadPageTask(int p, LocalDate d, String m) {
            pos = p;
            date = d;
            modeId = m;
        }

        @Override
        protected PlanRepository.PlanResult doInBackground(Void... voids) {
            try {
                if (currentSearchQuery != null) {
                    return planRepository.searchPlan(modeId, date, currentSearchQuery);
                } else {
                    return planRepository.loadPlan(modeId, date, false);
                }
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(PlanRepository.PlanResult res) {
            if (res != null) {
                putPlanInCache(modeId, date, res);
                if (modeId.equals(viewModeId) && pagerAdapter != null) {
                    pagerAdapter.notifyItemChanged(pos);
                }

                if (date.equals(currentDate)) {
                    if (res.headerLabel != null) {
                        tvHeaderLabel.setText(res.headerLabel);
                    }
                    if (!isMonthMode()) {
                        updateFixedWeekHeaders(res.dayColumns, date);
                    }
                }
            }
            stopRefreshAnimation();
        }
    }

    private class LoadSubjectsForFilterTask extends AsyncTask<Void, Void, List<PlanRepository.SubjectFilterItem>> {
        Exception error;
        private final boolean forceRefresh;

        LoadSubjectsForFilterTask(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
        }

        @Override
        protected void onPreExecute() {
            if (progress != null) {
                progress.setVisibility(View.VISIBLE);
            }
        }

        @Override
        protected List<PlanRepository.SubjectFilterItem> doInBackground(Void... voids) {
            try {
                if (!forceRefresh) {
                    List<PlanRepository.SubjectFilterItem> cached = loadFilterCache();
                    if (cached != null && !cached.isEmpty()) {
                        return cached;
                    }
                }

                List<PlanRepository.SubjectFilterItem> fresh =
                        planRepository.loadSubjectsForFilter();
                if (fresh != null && !fresh.isEmpty()) {
                    saveFilterCache(fresh);
                }
                return fresh;
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<PlanRepository.SubjectFilterItem> items) {
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }

            if (items == null || items.isEmpty()) {
                if (error != null) {
                    String msg = error.getMessage() != null ? error.getMessage() : "";
                    Toast.makeText(
                            PlanActivity.this,
                            getString(R.string.plan_filters_error, msg),
                            Toast.LENGTH_LONG
                    ).show();
                } else {
                    Toast.makeText(
                            PlanActivity.this,
                            R.string.plan_filters_empty,
                            Toast.LENGTH_SHORT
                    ).show();
                }
                return;
            }
            showFiltersDialog(items);
        }
    }

    private void showFiltersDialog(List<PlanRepository.SubjectFilterItem> items) {
        String[] labels = new String[items.size()];
        boolean[] checked = new boolean[items.size()];

        for (int i = 0; i < items.size(); i++) {
            PlanRepository.SubjectFilterItem it = items.get(i);
            String label = it.label != null ? it.label : "";
            String typeLabel = it.typeLabel != null ? it.typeLabel : "";
            labels[i] = label + " (" + typeLabel + ")";
            checked[i] = hiddenSubjectKeys.contains(it.filterKey);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.plan_filters_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setPositiveButton(R.string.plan_filters_apply, (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    for (int i = 0; i < items.size(); i++) {
                        if (checked[i]) {
                            hiddenSubjectKeys.add(items.get(i).filterKey);
                        }
                    }
                    prefs.edit().putStringSet(KEY_FILTER_HIDDEN, hiddenSubjectKeys).apply();
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

    private List<PlanRepository.SubjectFilterItem> loadFilterCache() {
        long ts = prefs.getLong(KEY_FILTER_CACHE_TS, 0L);
        if (ts == 0L) return null;

        long now = System.currentTimeMillis();
        if (now - ts > FILTER_CACHE_TTL_MS) return null;

        String json = prefs.getString(KEY_FILTER_CACHE_JSON, null);
        if (json == null || json.isEmpty()) return null;

        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<PlanRepository.SubjectFilterItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                PlanRepository.SubjectFilterItem it = new PlanRepository.SubjectFilterItem();
                it.label = obj.optString("label", "");
                it.typeLabel = obj.optString("typeLabel", "");
                it.filterKey = obj.optString("filterKey", "");
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
                arr.put(obj);
            }
            prefs.edit()
                    .putString(KEY_FILTER_CACHE_JSON, arr.toString())
                    .putLong(KEY_FILTER_CACHE_TS, System.currentTimeMillis())
                    .apply();
        } catch (org.json.JSONException ignored) {
        }
    }

    private void clearFilterCache() {
        prefs.edit()
                .remove(KEY_FILTER_CACHE_JSON)
                .remove(KEY_FILTER_CACHE_TS)
                .apply();
    }

    private void addHourLines(FrameLayoutWithChildren dayBody) {
        for (int h = START_HOUR; h < END_HOUR; h++) {
            int minutesFromStart = (h - START_HOUR) * 60;
            float topPx = (minutesFromStart / 60f) * dpToPx(HOUR_HEIGHT_DP);

            View line = new View(this);
            FrameLayoutWithChildren.LayoutParams lp =
                    new FrameLayoutWithChildren.LayoutParams(
                            FrameLayoutWithChildren.LayoutParams.MATCH_PARENT,
                            dpToPx(1)
                    );
            lp.topMargin = (int) topPx;
            line.setLayoutParams(lp);
            line.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_hour_line));
            dayBody.addView(line);
        }
    }

    private void updateNowLineInVisiblePage() {
        if (viewPager == null) return;

        View root = viewPager.getChildAt(0);
        if (!(root instanceof RecyclerView)) return;

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
            if (line != null) line.setVisibility(View.GONE);
            return;
        }

        if (line == null) {
            line = new View(this);
            line.setTag("NOW_LINE");
            line.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_now_line));
            parent.addView(line);
        }

        line.setVisibility(View.VISIBLE);
        float topPx = ((minNow - minStart) / 60f) * dpToPx(HOUR_HEIGHT_DP);

        FrameLayoutWithChildren.LayoutParams lp = new FrameLayoutWithChildren.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(2)
        );
        lp.topMargin = (int) topPx;
        line.setLayoutParams(lp);
        line.bringToFront();
    }

    private void scheduleNextNowLineUpdate() {
        LocalTime now = LocalTime.now();
        int msToNextMinute =
                (60 - now.getSecond()) * 1000 - (now.getNano() / 1_000_000);
        if (msToNextMinute < 200) msToNextMinute = 1000;
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

    private void layoutEventsInDayBody(FrameLayoutWithChildren dayBody,
                                       List<RenderedEvent> renderedEvents,
                                       int widthPx) {
        int calStart = START_HOUR * 60;
        int calEnd = END_HOUR * 60;

        if (renderedEvents.isEmpty()) return;

        renderedEvents.sort(Comparator.comparingInt(o -> o.ev.startMin));

        List<List<RenderedEvent>> clusters = new ArrayList<>();
        List<RenderedEvent> currentCluster = new ArrayList<>();
        int clusterEnd = -1;

        for (RenderedEvent re : renderedEvents) {
            int s = re.ev.startMin;
            int e = re.ev.endMin;

            if (currentCluster.isEmpty()) {
                currentCluster.add(re);
                clusterEnd = e;
            } else {
                if (s < clusterEnd) {
                    currentCluster.add(re);
                    if (e > clusterEnd) clusterEnd = e;
                } else {
                    clusters.add(new ArrayList<>(currentCluster));
                    currentCluster.clear();
                    currentCluster.add(re);
                    clusterEnd = e;
                }
            }
        }
        if (!currentCluster.isEmpty()) clusters.add(currentCluster);

        int marginPx = dpToPx(2);
        int overlapOffset = dpToPx(6);

        for (List<RenderedEvent> cluster : clusters) {
            if (cluster.isEmpty()) continue;

            boolean hasRealCollision = false;
            for (int i = 0; i < cluster.size(); i++) {
                for (int j = i + 1; j < cluster.size(); j++) {
                    int s1 = cluster.get(i).ev.startMin;
                    int e1 = cluster.get(i).ev.endMin;
                    int s2 = cluster.get(j).ev.startMin;
                    int e2 = cluster.get(j).ev.endMin;
                    int overlap = Math.min(e1, e2) - Math.max(s1, s2);
                    if (overlap >= 20) {
                        hasRealCollision = true;
                        break;
                    }
                }
                if (hasRealCollision) break;
            }

            if (!hasRealCollision) {
                int clusterSize = cluster.size();
                int maxOffset = overlapOffset * (clusterSize - 1);
                int availableWidth = widthPx - 2 * marginPx - maxOffset;
                if (availableWidth < dpToPx(40)) {
                    availableWidth = widthPx - 2 * marginPx;
                    maxOffset = 0;
                }

                for (int i = 0; i < clusterSize; i++) {
                    RenderedEvent re = cluster.get(i);
                    int startMin = re.ev.startMin;
                    int endMin = re.ev.endMin;

                    if (endMin <= calStart || startMin >= calEnd) {
                        re.view.setVisibility(View.GONE);
                        continue;
                    }

                    int startClamped = Math.max(startMin, calStart);
                    int endClamped = Math.min(endMin, calEnd);
                    int duration = Math.max(endClamped - startClamped, 15);

                    float offsetMinutes = startClamped - calStart;
                    float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);
                    float heightPx = (duration / 60f) * dpToPx(HOUR_HEIGHT_DP);
                    if (heightPx < dpToPx(22)) heightPx = dpToPx(22);

                    FrameLayoutWithChildren.LayoutParams lp =
                            (FrameLayoutWithChildren.LayoutParams) re.view.getLayoutParams();
                    lp.topMargin = (int) topPx;
                    lp.height = (int) heightPx;
                    lp.leftMargin = marginPx + i * overlapOffset;
                    lp.width = availableWidth;
                    re.view.setLayoutParams(lp);
                }
                continue;
            }

            List<Integer> laneEnd = new ArrayList<>();
            int[] laneOf = new int[cluster.size()];

            for (int i = 0; i < cluster.size(); i++) {
                RenderedEvent re = cluster.get(i);
                int s = re.ev.startMin;
                int e = re.ev.endMin;
                int assignedLane = -1;
                for (int laneIdx = 0; laneIdx < laneEnd.size(); laneIdx++) {
                    if (s >= laneEnd.get(laneIdx)) {
                        assignedLane = laneIdx;
                        laneEnd.set(laneIdx, e);
                        break;
                    }
                }
                if (assignedLane == -1) {
                    assignedLane = laneEnd.size();
                    laneEnd.add(e);
                }
                laneOf[i] = assignedLane;
            }

            int laneCount = Math.max(1, laneEnd.size());
            float laneWidth = (widthPx - 2f * marginPx) / laneCount;

            for (int i = 0; i < cluster.size(); i++) {
                RenderedEvent re = cluster.get(i);
                int laneIdx = laneOf[i];
                int startMin = re.ev.startMin;
                int endMin = re.ev.endMin;

                if (endMin <= calStart || startMin >= calEnd) {
                    re.view.setVisibility(View.GONE);
                    continue;
                }

                int startClamped = Math.max(startMin, calStart);
                int endClamped = Math.min(endMin, calEnd);
                int duration = Math.max(endClamped - startClamped, 15);

                float offsetMinutes = startClamped - calStart;
                float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);
                float heightPx = (duration / 60f) * dpToPx(HOUR_HEIGHT_DP);
                if (heightPx < dpToPx(22)) heightPx = dpToPx(22);

                FrameLayoutWithChildren.LayoutParams lp =
                        (FrameLayoutWithChildren.LayoutParams) re.view.getLayoutParams();
                lp.topMargin = (int) topPx;
                lp.height = (int) heightPx;
                lp.leftMargin = (int) (marginPx + laneIdx * laneWidth);
                lp.width = (int) (laneWidth - 2 * marginPx);
                re.view.setLayoutParams(lp);
            }
        }
    }

    private void updateFixedWeekHeaders(List<PlanRepository.DayColumn> rawCols, LocalDate pageDate) {
        if (layoutWeekHeadersRow == null || layoutWeekHeadersFixed == null) return;

        List<PlanRepository.DayColumn> cols = getVisibleColumns(rawCols);
        layoutWeekHeadersRow.removeAllViews();

        if (cols == null || cols.isEmpty()) {
            layoutWeekHeadersFixed.setVisibility(View.GONE);
            return;
        }

        layoutWeekHeadersFixed.setVisibility(View.VISIBLE);
        LocalDate today = LocalDate.now();

        for (PlanRepository.DayColumn col : cols) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);

            TextView tv = new TextView(this);
            tv.setLayoutParams(lp);
            tv.setText(formatDayHeader(col.date));
            tv.setTextColor(ContextCompat.getColor(this, R.color.plan_week_header_text));
            tv.setTextSize(12f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dpToPx(4), 0, dpToPx(4));

            boolean isToday = col.date != null && col.date.equals(today);
            boolean highlight = isToday;

            if (highlight) {
                tv.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_week_header_selected_bg));
            }

            layoutWeekHeadersRow.addView(tv);
        }
    }

    private boolean shouldHideEvent(PlanRepository.PlanEventUi ev) {
        if (ev == null) return false;
        if (ev.subjectKey == null || ev.subjectKey.isEmpty()) return false;
        return hiddenSubjectKeys.contains(ev.subjectKey);
    }

    private View createEventView(PlanRepository.PlanEventUi ev) {
        TextView tv = new TextView(this);
        tv.setTag(ev);

        String time = ev.startStr + " - " + ev.endStr;
        StringBuilder sb = new StringBuilder();
        sb.append(ev.title != null ? ev.title : "");
        sb.append("\n");
        sb.append(time);
        if (ev.room != null && !ev.room.isEmpty()) {
            sb.append(" · ").append(ev.room);
        }
        if (ev.group != null && !ev.group.isEmpty()) {
            sb.append(" · ").append(ev.group);
        }

        tv.setText(sb.toString());
        tv.setTextSize(11f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.plan_event_text));
        tv.setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));
        tv.setClickable(true);

        int color = ContextCompat.getColor(this, colorResForType(ev.typeClass));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpToPx(6));
        tv.setBackground(bg);

        FrameLayoutWithChildren.LayoutParams lp =
                new FrameLayoutWithChildren.LayoutParams(
                        FrameLayoutWithChildren.LayoutParams.MATCH_PARENT,
                        FrameLayoutWithChildren.LayoutParams.WRAP_CONTENT
                );
        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> showEventDetails(ev));
        return tv;
    }

    private void showEventDetails(PlanRepository.PlanEventUi ev) {
        String time = ev.startStr + " - " + ev.endStr;
        StringBuilder msg = new StringBuilder();
        msg.append(time);

        if (ev.room != null && !ev.room.isEmpty()) {
            msg.append("\n").append(getString(R.string.plan_event_room_prefix)).append(" ").append(ev.room);
        }
        if (ev.group != null && !ev.group.isEmpty()) {
            msg.append("\n").append(getString(R.string.plan_event_group_prefix)).append(" ").append(ev.group);
        }
        if (ev.teacher != null && !ev.teacher.isEmpty()) {
            msg.append("\n").append(getString(R.string.plan_event_teacher_prefix)).append(" ").append(ev.teacher);
        }
        if (ev.typeLabel != null && !ev.typeLabel.isEmpty()) {
            msg.append("\n\n").append(ev.typeLabel);
        }

        new AlertDialog.Builder(this)
                .setTitle(ev.title != null ? ev.title : getString(R.string.plan_event_default_title))
                .setMessage(msg.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @ColorRes
    private int colorResForType(String typeClass) {
        if (typeClass == null) typeClass = "";
        switch (typeClass) {
            case "week-event-type-lecture": return R.color.plan_event_lecture_bg;
            case "week-event-type-lab": return R.color.plan_event_lab_bg;
            case "week-event-type-auditory": return R.color.plan_event_auditory_bg;
            case "week-event-type-project": return R.color.plan_event_project_bg;
            case "week-event-type-seminar": return R.color.plan_event_seminar_bg;
            case "week-event-type-diploma-seminar": return R.color.plan_event_diploma_seminar_bg;
            case "week-event-type-diploma": return R.color.plan_event_diploma_bg;
            case "week-event-type-lectorate": return R.color.plan_event_lectorate_bg;
            case "week-event-type-conservatory": return R.color.plan_event_conservatory_bg;
            case "week-event-type-consultation": return R.color.plan_event_consultation_bg;
            case "week-event-type-field": return R.color.plan_event_field_bg;
            case "week-event-type-class": return R.color.plan_event_class_bg;
            case "week-event-type-exam": return R.color.plan_event_exam_bg;
            case "week-event-type-exam-remote": return R.color.plan_event_exam_remote_bg;
            case "week-event-type-cancelled": return R.color.plan_event_cancelled_bg;
            case "week-event-type-rector": return R.color.plan_event_rector_bg;
            case "week-event-type-dean": return R.color.plan_event_dean_bg;
            case "week-event-type-remote": return R.color.plan_event_remote_bg;
            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake": return R.color.plan_event_pass_bg;
            default: return R.color.plan_event_default_bg;
        }
    }

    private String formatDayHeader(LocalDate date) {
        if (date == null) return "";
        String shortName;
        switch (date.getDayOfWeek()) {
            case MONDAY: shortName = getString(R.string.plan_header_mon_short); break;
            case TUESDAY: shortName = getString(R.string.plan_header_tue_short); break;
            case WEDNESDAY: shortName = getString(R.string.plan_header_wed_short); break;
            case THURSDAY: shortName = getString(R.string.plan_header_thu_short); break;
            case FRIDAY: shortName = getString(R.string.plan_header_fri_short); break;
            case SATURDAY: shortName = getString(R.string.plan_header_sat_short); break;
            case SUNDAY: default: shortName = getString(R.string.plan_header_sun_short); break;
        }
        return shortName + "\n" + date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"));
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public static class FrameLayoutWithChildren extends android.widget.FrameLayout {
        public FrameLayoutWithChildren(Context context) { super(context); }
        public FrameLayoutWithChildren(Context context, AttributeSet attrs) { super(context, attrs); }
        @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }
        @Override protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) { return new LayoutParams(p); }
        @Override public LayoutParams generateLayoutParams(AttributeSet attrs) { return new LayoutParams(getContext(), attrs); }
        @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); }
        public static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
            public LayoutParams(Context c, AttributeSet attrs) { super(c, attrs); }
            public LayoutParams(int width, int height) { super(width, height); }
            public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
        }
    }
}