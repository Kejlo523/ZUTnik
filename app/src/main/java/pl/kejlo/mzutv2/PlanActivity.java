package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import androidx.core.content.ContextCompat;
import androidx.annotation.ColorRes;

public class PlanActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
    // region Enums

    /**
     * Logical view modes for the timetable.
     */
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
                return WEEK; // sensible default
            }
            for (ViewMode m : values()) {
                if (m.id.equals(id)) {
                    return m;
                }
            }
            return WEEK;
        }
    }

    // endregion

    // region Preferences / cache

    private static final String PREFS_NAME = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    private static final String KEY_FILTER_CACHE_JSON = "plan_filters_cache_json";
    private static final String KEY_FILTER_CACHE_TS = "plan_filters_cache_ts";
    private static final long FILTER_CACHE_TTL_MS =
            30L * 24L * 60L * 60L * 1000L; // 30 dni

    // endregion

    // region Layout config

    private static final int START_HOUR = 6;
    private static final int END_HOUR = 22;
    private static final float HOUR_HEIGHT_DP = 48f;
    private static final float DAY_HEADER_HEIGHT_DP = 32f;

    // endregion

    // region Views

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private Button btnViewDay;
    private Button btnViewWeek;
    private Button btnViewMonth;
    private Button btnPrev;
    private Button btnNext;
    private Button btnToday;
    private Button btnFilters;
    private Button btnRefresh;

    private CharSequence btnFiltersOriginalText;

    private TextView tvHeaderLabel;
    private TextView tvEmpty;

    private View layoutWeekRoot;
    private LinearLayout layoutTimeColumn;
    private LinearLayout layoutWeekDays;

    private View layoutMonthRoot;
    private LinearLayout layoutMonthWeekdays;
    private GridLayout gridMonth;

    private ProgressBar progress;

    private FrameLayoutWithChildren nowLineParent;
    private View nowLineView;

    // endregion

    // region State & helpers

    private String viewModeId = ViewMode.WEEK.getId();
    private LocalDate currentDate = LocalDate.now();

    private PlanRepository planRepository;
    private final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SharedPreferences prefs;
    private Set<String> hiddenSubjectKeys = new HashSet<>();

    private final Handler nowLineHandler = new Handler(Looper.getMainLooper());
    private final Runnable nowLineRunnable = new Runnable() {
        @Override
        public void run() {
            updateNowLinePosition();
            scheduleNextNowLineUpdate();
        }
    };

    // endregion

    // region Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure session is valid
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

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

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
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnToday = findViewById(R.id.btnToday);
        btnFilters = findViewById(R.id.btnFilters);
        btnRefresh = findViewById(R.id.btnRefresh);

        btnFiltersOriginalText = btnFilters.getText();

        tvHeaderLabel = findViewById(R.id.tvHeaderLabel);
        tvEmpty = findViewById(R.id.tvEmpty);

        layoutWeekRoot = findViewById(R.id.layoutWeekRoot);
        layoutTimeColumn = findViewById(R.id.layoutTimeColumn);
        layoutWeekDays = findViewById(R.id.layoutWeekDays);

        layoutMonthRoot = findViewById(R.id.layoutMonthRoot);
        layoutMonthWeekdays = findViewById(R.id.layoutMonthWeekdays);
        gridMonth = findViewById(R.id.gridMonth);

        progress = findViewById(R.id.planProgress);

        setupWeekdayHeadersForMonth();
        setupTimeColumn();
        setupViewModeButtons();
        setupNavButtons();
        setupFiltersButton();
        setupRefreshButton();

        if (savedInstanceState != null) {
            String vm = savedInstanceState.getString("viewMode", viewModeId);
            viewModeId = vm != null ? vm : ViewMode.WEEK.getId();

            String dateStr = savedInstanceState.getString("currentDate", null);
            if (dateStr != null) {
                try {
                    currentDate = LocalDate.parse(dateStr, YMD);
                } catch (Exception ignored) {
                }
            }
        }

        new LoadPlanTask().execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nowLineParent != null && nowLineView != null) {
            updateNowLinePosition();
            nowLineHandler.removeCallbacks(nowLineRunnable);
            scheduleNextNowLineUpdate();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
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
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("viewMode", viewModeId);
        outState.putString("currentDate", currentDate.format(YMD));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }

    // endregion

    // region View mode helpers

    private ViewMode getCurrentViewMode() {
        return ViewMode.fromId(viewModeId);
    }

    private void setCurrentViewMode(ViewMode mode) {
        if (mode != null) {
            viewModeId = mode.getId();
        }
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

    // endregion

    // region UI setup

    private void setupViewModeButtons() {
        View.OnClickListener modeClick = v -> {
            if (v == btnViewDay) {
                setCurrentViewMode(ViewMode.DAY);
            } else if (v == btnViewWeek) {
                setCurrentViewMode(ViewMode.WEEK);
            } else if (v == btnViewMonth) {
                setCurrentViewMode(ViewMode.MONTH);
            }
            updateViewModeButtonsUi();
            new LoadPlanTask().execute();
        };

        btnViewDay.setOnClickListener(modeClick);
        btnViewWeek.setOnClickListener(modeClick);
        btnViewMonth.setOnClickListener(modeClick);

        updateViewModeButtonsUi();
    }

    private void updateViewModeButtonsUi() {
        ViewMode mode = getCurrentViewMode();
        btnViewDay.setAlpha(mode == ViewMode.DAY ? 1f : 0.6f);
        btnViewWeek.setAlpha(mode == ViewMode.WEEK ? 1f : 0.6f);
        btnViewMonth.setAlpha(mode == ViewMode.MONTH ? 1f : 0.6f);
    }

    private void setupNavButtons() {
        btnPrev.setOnClickListener(v -> {
            shiftCurrentDate(-1);
            new LoadPlanTask().execute();
        });

        btnNext.setOnClickListener(v -> {
            shiftCurrentDate(1);
            new LoadPlanTask().execute();
        });

        btnToday.setOnClickListener(v -> {
            currentDate = LocalDate.now();
            new LoadPlanTask().execute();
        });
    }

    private void setupRefreshButton() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> new LoadPlanTask(true).execute());
        }
    }

    private void shiftCurrentDate(int dir) {
        ViewMode mode = getCurrentViewMode();
        if (mode == ViewMode.DAY) {
            currentDate = currentDate.plusDays(dir);
        } else if (mode == ViewMode.WEEK) {
            currentDate = currentDate.plusWeeks(dir);
        } else {
            currentDate = currentDate.plusMonths(dir);
        }
    }

    private void setupFiltersButton() {
        btnFilters.setOnClickListener(v -> new LoadSubjectsForFilterTask(false).execute());

        btnFilters.setOnLongClickListener(v -> {
            clearFilterCache();
            Toast.makeText(
                    PlanActivity.this,
                    R.string.plan_filters_cache_cleared,
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });
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
        layoutMonthWeekdays.removeAllViews();
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
            layoutMonthWeekdays.addView(tv);
        }
    }

    // endregion

    // region Filters (subjects)

    private class LoadSubjectsForFilterTask extends AsyncTask<Void, Void, List<PlanRepository.SubjectFilterItem>> {
        Exception error;
        private final boolean forceRefresh;

        LoadSubjectsForFilterTask(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
        }

        @Override
        protected void onPreExecute() {
            if (btnFilters != null) {
                btnFilters.setEnabled(false);
                btnFilters.setText(R.string.plan_filters_loading);
            }
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
            if (btnFilters != null) {
                btnFilters.setEnabled(true);
                btnFilters.setText(btnFiltersOriginalText);
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
                    new LoadPlanTask().execute();
                })
                .setNeutralButton(R.string.plan_filters_reset, (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    prefs.edit().remove(KEY_FILTER_HIDDEN).apply();
                    new LoadPlanTask().execute();
                })
                .setNegativeButton(R.string.plan_filters_cancel, null)
                .show();
    }

    private List<PlanRepository.SubjectFilterItem> loadFilterCache() {
        long ts = prefs.getLong(KEY_FILTER_CACHE_TS, 0L);
        if (ts == 0L) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (now - ts > FILTER_CACHE_TTL_MS) {
            return null;
        }

        String json = prefs.getString(KEY_FILTER_CACHE_JSON, null);
        if (json == null || json.isEmpty()) {
            return null;
        }

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
            // cache optional
        }
    }

    private void clearFilterCache() {
        prefs.edit()
                .remove(KEY_FILTER_CACHE_JSON)
                .remove(KEY_FILTER_CACHE_TS)
                .apply();
    }

    // endregion

    // region Plan loading

    private class LoadPlanTask extends AsyncTask<Void, Void, PlanRepository.PlanResult> {
        Exception error;
        private final boolean forceRefresh;

        LoadPlanTask() {
            this(false);
        }

        LoadPlanTask(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
        }

        @Override
        protected void onPreExecute() {
            progress.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            nowLineHandler.removeCallbacks(nowLineRunnable);
            nowLineParent = null;
            nowLineView = null;
        }

        @Override
        protected PlanRepository.PlanResult doInBackground(Void... voids) {
            try {
                return planRepository.loadPlan(viewModeId, currentDate, forceRefresh);
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(PlanRepository.PlanResult result) {
            progress.setVisibility(View.GONE);

            if (result == null) {
                tvEmpty.setVisibility(View.VISIBLE);
                String msg = "";
                if (error != null && error.getMessage() != null) {
                    msg = getString(R.string.plan_error_loading_plan_with_message, error.getMessage());
                } else {
                    msg = getString(R.string.plan_error_loading_plan_generic);
                }
                tvEmpty.setText(msg);
                return;
            }

            currentDate = result.current;
            if (result.viewMode != null) {
                viewModeId = result.viewMode;
            }

            updateViewModeButtonsUi();
            tvHeaderLabel.setText(result.headerLabel != null ? result.headerLabel : "");

            if (isDayMode() || isWeekMode()) {
                renderWeekOrDay(result);
            } else {
                renderMonth(result);
            }

            if ((isDayMode() || isWeekMode()) && !result.hasAnyEventsInRange) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.plan_no_classes_in_range);
            } else if (isMonthMode()
                    && (result.monthGrid == null || result.monthGrid.isEmpty())) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.plan_no_data_for_month);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        }
    }

    // endregion

    // region Week / day rendering

    private void renderWeekOrDay(PlanRepository.PlanResult result) {
        layoutWeekRoot.setVisibility(View.VISIBLE);
        layoutMonthRoot.setVisibility(View.GONE);

        layoutWeekDays.removeAllViews();

        float totalHours = END_HOUR - START_HOUR;
        int columnHeight = (int) (dpToPx(HOUR_HEIGHT_DP) * totalHours);

        List<PlanRepository.DayColumn> cols =
                result.dayColumns != null ? result.dayColumns : Collections.emptyList();

        // Optional: hide Sat/Sun if they have no events in week view
        if (isWeekMode() && cols.size() >= 7) {
            int satIndex = -1;
            int sunIndex = -1;
            boolean satHasEvents = false;
            boolean sunHasEvents = false;

            for (int i = 0; i < cols.size(); i++) {
                PlanRepository.DayColumn col = cols.get(i);
                if (col == null || col.date == null) {
                    continue;
                }

                switch (col.date.getDayOfWeek()) {
                    case SATURDAY:
                        satIndex = i;
                        satHasEvents = col.events != null && !col.events.isEmpty();
                        break;
                    case SUNDAY:
                        sunIndex = i;
                        sunHasEvents = col.events != null && !col.events.isEmpty();
                        break;
                    default:
                        break;
                }
            }

            if (satIndex != -1 && sunIndex != -1 && !satHasEvents && !sunHasEvents) {
                List<PlanRepository.DayColumn> filtered = new ArrayList<>();
                for (int i = 0; i < cols.size(); i++) {
                    if (i == satIndex || i == sunIndex) continue;
                    filtered.add(cols.get(i));
                }
                cols = filtered;
            }
        }

        LocalDate today = LocalDate.now();

        for (PlanRepository.DayColumn col : cols) {
            boolean isSelectedDay = col.date != null && col.date.equals(currentDate);

            LinearLayout dayColumn = new LinearLayout(this);
            dayColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            dayLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
            dayColumn.setLayoutParams(dayLp);

            TextView tvHeader = new TextView(this);
            tvHeader.setText(formatDayHeader(col.date));
            tvHeader.setTextColor(ContextCompat.getColor(this, R.color.plan_week_header_text));
            tvHeader.setTextSize(12f);
            tvHeader.setGravity(Gravity.CENTER);
            tvHeader.setPadding(0, dpToPx(4), 0, dpToPx(4));
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(DAY_HEADER_HEIGHT_DP)
            );
            tvHeader.setLayoutParams(headerLp);

            if (isSelectedDay) {
                tvHeader.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_week_header_selected_bg));
            }

            dayColumn.addView(tvHeader);

            FrameLayoutWithChildren dayBody = new FrameLayoutWithChildren(this);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    columnHeight
            );
            dayBody.setLayoutParams(bodyLp);
            dayBody.setBackgroundColor(ContextCompat.getColor(
                    this,
                    isSelectedDay ? R.color.plan_week_day_selected_bg : R.color.plan_week_day_bg
            ));
            dayColumn.addView(dayBody);

            addHourLines(dayBody);

            List<PlanRepository.PlanEventUi> events =
                    col.events != null ? col.events : Collections.emptyList();

            List<RenderedEvent> renderedEvents = new ArrayList<>();
            for (PlanRepository.PlanEventUi ev : events) {
                if (shouldHideEvent(ev)) {
                    continue;
                }
                View evView = createEventView(ev);
                dayBody.addView(evView);
                renderedEvents.add(new RenderedEvent(ev, evView));
            }

            LocalDate colDate = col.date;

            dayBody.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            dayBody.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            int w = dayBody.getWidth();
                            if (w <= 0) {
                                return;
                            }

                            layoutEventsInDayBody(dayBody, renderedEvents, w);

                            if (colDate != null
                                    && (isDayMode() || isWeekMode())
                                    && colDate.equals(today)) {
                                setupNowLine(dayBody, w);
                            }
                        }
                    });

            layoutWeekDays.addView(dayColumn);
        }
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

        if (renderedEvents.isEmpty()) {
            return;
        }

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
                    if (e > clusterEnd) {
                        clusterEnd = e;
                    }
                } else {
                    clusters.add(new ArrayList<>(currentCluster));
                    currentCluster.clear();
                    currentCluster.add(re);
                    clusterEnd = e;
                }
            }
        }
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        int marginPx = dpToPx(2);
        int overlapOffset = dpToPx(6);

        for (List<RenderedEvent> cluster : clusters) {
            if (cluster.isEmpty()) {
                continue;
            }

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
                if (hasRealCollision) {
                    break;
                }
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
                    if (heightPx < dpToPx(22)) {
                        heightPx = dpToPx(22);
                    }

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
                if (heightPx < dpToPx(22)) {
                    heightPx = dpToPx(22);
                }

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

    private void setupNowLine(FrameLayoutWithChildren parent, int widthPx) {
        nowLineParent = parent;

        if (nowLineView == null) {
            nowLineView = new View(this);
            FrameLayoutWithChildren.LayoutParams lp =
                    new FrameLayoutWithChildren.LayoutParams(
                            widthPx,
                            dpToPx(1f)
                    );
            lp.topMargin = 0;
            nowLineView.setLayoutParams(lp);
            nowLineView.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_now_line));
            parent.addView(nowLineView);
            nowLineView.bringToFront();
        }

        updateNowLinePosition();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        scheduleNextNowLineUpdate();
    }

    private void updateNowLinePosition() {
        if (nowLineParent == null || nowLineView == null) {
            return;
        }

        LocalTime now = LocalTime.now();
        int minutesNow = now.getHour() * 60 + now.getMinute();
        int minBound = START_HOUR * 60;
        int maxBound = END_HOUR * 60;
        if (minutesNow < minBound || minutesNow > maxBound) {
            nowLineView.setVisibility(View.GONE);
            return;
        } else {
            nowLineView.setVisibility(View.VISIBLE);
        }

        float offsetMinutes = minutesNow - minBound;
        float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);

        FrameLayoutWithChildren.LayoutParams lp =
                (FrameLayoutWithChildren.LayoutParams) nowLineView.getLayoutParams();
        lp.topMargin = (int) topPx;
        nowLineView.setLayoutParams(lp);
        nowLineView.bringToFront();
    }

    private void scheduleNextNowLineUpdate() {
        LocalTime now = LocalTime.now();
        int msToNextMinute =
                (60 - now.getSecond()) * 1000 - (now.getNano() / 1_000_000);
        if (msToNextMinute < 200) {
            msToNextMinute = 1000;
        }
        nowLineHandler.postDelayed(nowLineRunnable, msToNextMinute);
    }

    private boolean shouldHideEvent(PlanRepository.PlanEventUi ev) {
        if (ev == null) {
            return false;
        }
        if (ev.subjectKey == null || ev.subjectKey.isEmpty()) {
            return false;
        }
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

    @ColorRes
    private int colorResForType(String typeClass) {
        if (typeClass == null) {
            typeClass = "";
        }

        switch (typeClass) {
            case "week-event-type-lecture":
                return R.color.plan_event_lecture_bg;
            case "week-event-type-lab":
                return R.color.plan_event_lab_bg;
            case "week-event-type-auditory":
                return R.color.plan_event_auditory_bg;
            case "week-event-type-exam":
                return R.color.plan_event_exam_bg;
            case "week-event-type-cancelled":
                return R.color.plan_event_cancelled_bg;
            case "week-event-type-rector":
                return R.color.plan_event_rector_bg;
            case "week-event-type-remote":
                return R.color.plan_event_remote_bg;
            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake":
                return R.color.plan_event_pass_bg;
            default:
                return R.color.plan_event_default_bg;
        }
    }

    private void showEventDetails(PlanRepository.PlanEventUi ev) {
        String time = ev.startStr + " - " + ev.endStr;
        StringBuilder msg = new StringBuilder();
        msg.append(time);

        if (ev.room != null && !ev.room.isEmpty()) {
            msg.append("\n")
                    .append(getString(R.string.plan_event_room_prefix))
                    .append(" ")
                    .append(ev.room);
        }
        if (ev.group != null && !ev.group.isEmpty()) {
            msg.append("\n")
                    .append(getString(R.string.plan_event_group_prefix))
                    .append(" ")
                    .append(ev.group);
        }
        if (ev.teacher != null && !ev.teacher.isEmpty()) {
            msg.append("\n")
                    .append(getString(R.string.plan_event_teacher_prefix))
                    .append(" ")
                    .append(ev.teacher);
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

    private String formatDayHeader(LocalDate date) {
        if (date == null) {
            return "";
        }
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
        return shortName + "\n" +
                date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"));
    }

    // endregion

    // region Month rendering

    private void renderMonth(PlanRepository.PlanResult result) {
        layoutWeekRoot.setVisibility(View.GONE);
        layoutMonthRoot.setVisibility(View.VISIBLE);

        gridMonth.removeAllViews();
        gridMonth.setColumnCount(7);

        List<List<PlanRepository.MonthCell>> grid =
                result.monthGrid != null ? result.monthGrid : Collections.emptyList();

        for (List<PlanRepository.MonthCell> week : grid) {
            for (PlanRepository.MonthCell cell : week) {
                if (cell == null) {
                    View spacer = new View(this);
                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                    lp.width = 0;
                    lp.height = dpToPx(48);
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    spacer.setLayoutParams(lp);
                    gridMonth.addView(spacer);
                    continue;
                }

                LinearLayout cellRoot = new LinearLayout(this);
                cellRoot.setOrientation(LinearLayout.VERTICAL);
                cellRoot.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));

                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = dpToPx(64);
                lp.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
                lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                cellRoot.setLayoutParams(lp);

                if (cell.hasPlan) {
                    cellRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_month_cell_with_events_bg));
                } else {
                    cellRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.plan_month_cell_bg));
                }

                TextView tvNum = new TextView(this);
                tvNum.setText(String.valueOf(cell.date.getDayOfMonth()));
                tvNum.setTextColor(ContextCompat.getColor(this, R.color.plan_month_day_number));
                tvNum.setTextSize(13f);
                cellRoot.addView(tvNum);

                if (cell.hasPlan) {
                    TextView tvHint = new TextView(this);
                    tvHint.setText(R.string.plan_month_cell_has_events_hint);
                    tvHint.setTextSize(9f);
                    tvHint.setTextColor(ContextCompat.getColor(this, R.color.plan_month_day_hint));
                    cellRoot.addView(tvHint);

                    cellRoot.setOnClickListener(v -> {
                        setCurrentViewMode(ViewMode.DAY);
                        currentDate = cell.date;
                        new LoadPlanTask().execute();
                    });
                }

                gridMonth.addView(cellRoot);
            }
        }
    }

    // endregion

    // region Utils / inner layout class

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public static class FrameLayoutWithChildren extends android.widget.FrameLayout {
        public FrameLayoutWithChildren(Context context) {
            super(context);
        }

        @Override
        protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
            return p instanceof LayoutParams;
        }

        @Override
        protected LayoutParams generateLayoutParams(android.view.ViewGroup.LayoutParams p) {
            return new LayoutParams(p);
        }

        @Override
        public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
            return new LayoutParams(getContext(), attrs);
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
            );
        }

        public static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
            public LayoutParams(Context c, android.util.AttributeSet attrs) {
                super(c, attrs);
            }

            public LayoutParams(int width, int height) {
                super(width, height);
            }

            public LayoutParams(android.view.ViewGroup.LayoutParams source) {
                super(source);
            }
        }
    }

    // endregion
}
