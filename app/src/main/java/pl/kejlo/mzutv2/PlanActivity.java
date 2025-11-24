package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.app.AlertDialog;
import android.content.Context;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class PlanActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    // cache listy przedmiotów do filtra
    private static final String KEY_FILTER_CACHE_JSON = "plan_filters_cache_json";
    private static final String KEY_FILTER_CACHE_TS   = "plan_filters_cache_ts";
    private static final long  FILTER_CACHE_TTL_MS   = 30L * 24L * 60L * 60L * 1000L; // ok. 30 dni

    private static final int START_HOUR = 6;
    private static final int END_HOUR   = 22;
    private static final float HOUR_HEIGHT_DP = 48f;
    private static final float DAY_HEADER_HEIGHT_DP = 32f;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private Spinner spinnerStudies;
    private boolean spinnerInitialized = false;

    private Button btnViewDay;
    private Button btnViewWeek;
    private Button btnViewMonth;
    private Button btnPrev;
    private Button btnNext;
    private Button btnToday;
    private Button btnFilters;
    private Button btnRefresh;                 // NOWE

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

    private String viewMode = "week";
    private LocalDate currentDate = LocalDate.now();

    private final PlanRepository planRepository = new PlanRepository();
    private final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SharedPreferences prefs;
    private Set<String> hiddenSubjectKeys = new HashSet<>();

    // linia "teraz" – przechowujemy globalnie
    private FrameLayoutWithChildren nowLineParent;
    private View nowLineView;
    private final Handler nowLineHandler = new Handler(Looper.getMainLooper());
    private final Runnable nowLineRunnable = new Runnable() {
        @Override
        public void run() {
            updateNowLinePosition();
            scheduleNextNowLineUpdate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plan);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenSubjectKeys = new HashSet<>(prefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>()));

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        toolbar.setTitle("Plan zajęć");
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "plan");

        spinnerStudies = findViewById(R.id.spinnerStudies);

        btnViewDay   = findViewById(R.id.btnViewDay);
        btnViewWeek  = findViewById(R.id.btnViewWeek);
        btnViewMonth = findViewById(R.id.btnViewMonth);
        btnPrev      = findViewById(R.id.btnPrev);
        btnNext      = findViewById(R.id.btnNext);
        btnToday     = findViewById(R.id.btnToday);
        btnFilters   = findViewById(R.id.btnFilters);
        btnRefresh   = findViewById(R.id.btnRefresh); // pamiętaj o dodaniu w XML

        btnFiltersOriginalText = btnFilters.getText();

        tvHeaderLabel = findViewById(R.id.tvHeaderLabel);
        tvEmpty       = findViewById(R.id.tvEmpty);

        layoutWeekRoot   = findViewById(R.id.layoutWeekRoot);
        layoutTimeColumn = findViewById(R.id.layoutTimeColumn);
        layoutWeekDays   = findViewById(R.id.layoutWeekDays);

        layoutMonthRoot     = findViewById(R.id.layoutMonthRoot);
        layoutMonthWeekdays = findViewById(R.id.layoutMonthWeekdays);
        gridMonth           = findViewById(R.id.gridMonth);

        progress = findViewById(R.id.planProgress);

        setupWeekdayHeadersForMonth();
        setupTimeColumn();
        setupViewModeButtons();
        setupNavButtons();
        setupFiltersButton();
        setupRefreshButton();
        setupStudiesSpinner();
        // swipe wywalony – nie wywołujemy setupSwipeDetector()

        if (savedInstanceState != null) {
            viewMode = savedInstanceState.getString("viewMode", "week");
            String dateStr = savedInstanceState.getString("currentDate", null);
            if (dateStr != null) {
                try {
                    currentDate = LocalDate.parse(dateStr, YMD);
                } catch (Exception ignored) {}
            }
        }

        new LoadPlanTask().execute();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // tylko podglądamy gest, NIE blokujemy eventu
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
        outState.putString("viewMode", viewMode);
        outState.putString("currentDate", currentDate.format(YMD));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }

    /* =============== STUDIA =============== */

    private void setupStudiesSpinner() {
        MzutSession session = MzutSession.getInstance();
        if (session.getStudies() == null || session.getStudies().isEmpty()) {
            new LoadStudiesTask().execute();
        } else {
            initStudiesSpinner(session.getStudies());
        }
    }

    private void initStudiesSpinner(List<Study> studies) {
        if (studies == null) studies = Collections.emptyList();

        List<String> labels = new ArrayList<>();
        for (Study s : studies) {
            labels.add(s.toString());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item_dark,
                labels
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerStudies.setAdapter(adapter);
        spinnerInitialized = true;

        final MzutSession session = MzutSession.getInstance();
        int activeIndex = session.getActiveStudyIndex();
        if (activeIndex < 0 || activeIndex >= labels.size()) activeIndex = 0;
        spinnerStudies.setSelection(activeIndex);

        spinnerStudies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerInitialized) return;
                if (position == session.getActiveStudyIndex()) return;
                session.setActiveStudyIndex(position);
                new LoadPlanTask().execute();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private class LoadStudiesTask extends AsyncTask<Void, Void, List<Study>> {
        Exception error;

        @Override
        protected List<Study> doInBackground(Void... voids) {
            try {
                GradesRepository repo = new GradesRepository();
                return repo.loadStudies();
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Study> studies) {
            if (studies == null || studies.isEmpty()) {
                if (error != null) {
                    Toast.makeText(PlanActivity.this,
                            "Błąd ładowania listy kierunków: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            initStudiesSpinner(studies);
        }
    }

    /* =============== TRYB DZIEN/ TYDZ / MIESIAC =============== */

    private void setupViewModeButtons() {
        View.OnClickListener modeClick = v -> {
            if (v == btnViewDay) {
                viewMode = "day";
            } else if (v == btnViewWeek) {
                viewMode = "week";
            } else if (v == btnViewMonth) {
                viewMode = "month";
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
        btnViewDay.setAlpha("day".equals(viewMode) ? 1f : 0.6f);
        btnViewWeek.setAlpha("week".equals(viewMode) ? 1f : 0.6f);
        btnViewMonth.setAlpha("month".equals(viewMode) ? 1f : 0.6f);
    }

    private void setupNavButtons() {
        btnPrev.setOnClickListener(v -> {
            shiftCurrentDate(-1);
            new LoadPlanTask().execute();
        });

        btnNext.setOnClickListener(v -> {
            shiftCurrentDate(+1);
            new LoadPlanTask().execute();
        });

        btnToday.setOnClickListener(v -> {
            currentDate = LocalDate.now();
            new LoadPlanTask().execute();
        });
    }

    private void setupRefreshButton() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> new LoadPlanTask().execute());
        }
    }

    private void shiftCurrentDate(int dir) {
        if ("day".equals(viewMode)) {
            currentDate = currentDate.plusDays(dir);
        } else if ("week".equals(viewMode)) {
            currentDate = currentDate.plusWeeks(dir);
        } else {
            currentDate = currentDate.plusMonths(dir);
        }
    }

    /* =============== FILTRY =============== */

    private void setupFiltersButton() {
        btnFilters.setOnClickListener(v -> new LoadSubjectsForFilterTask(false).execute());

        // długie przytrzymanie: czyści cache listy przedmiotów
        btnFilters.setOnLongClickListener(v -> {
            clearFilterCache();
            Toast.makeText(
                    PlanActivity.this,
                    "Cache listy przedmiotów wyczyszczony.\nPrzy następnym otwarciu zostanie pobrana nowa lista.",
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });
    }

    private class LoadSubjectsForFilterTask extends AsyncTask<Void, Void, List<PlanRepository.SubjectFilterItem>> {
        Exception error;
        private final boolean forceRefresh;

        LoadSubjectsForFilterTask(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (btnFilters != null) {
                btnFilters.setEnabled(false);
                btnFilters.setText("Ładowanie…");
            }
            if (progress != null) {
                progress.setVisibility(View.VISIBLE);
            }
            Toast.makeText(
                    PlanActivity.this,
                    "Pobieranie listy, proszę czekać…",
                    Toast.LENGTH_SHORT
            ).show();
        }

        @Override
        protected List<PlanRepository.SubjectFilterItem> doInBackground(Void... voids) {
            try {
                // najpierw cache, jeśli nie wymuszamy odświeżenia
                if (!forceRefresh) {
                    List<PlanRepository.SubjectFilterItem> cached = loadFilterCache();
                    if (cached != null && !cached.isEmpty()) {
                        return cached;
                    }
                }

                // brak cache lub wymuszone odświeżenie -> pobierz z repo
                List<PlanRepository.SubjectFilterItem> fresh = planRepository.loadSubjectsForFilter();
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
                    Toast.makeText(PlanActivity.this,
                            "Błąd ładowania listy przedmiotów: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(PlanActivity.this,
                            "Brak przedmiotów do filtrowania.",
                            Toast.LENGTH_SHORT).show();
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
            labels[i] = it.label + " (" + it.typeLabel + ")";
            checked[i] = hiddenSubjectKeys.contains(it.filterKey);
        }

        new AlertDialog.Builder(this)
                .setTitle("Filtr przedmiotów – wyklucz z widoku")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setPositiveButton("Zastosuj", (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    for (int i = 0; i < items.size(); i++) {
                        if (checked[i]) {
                            hiddenSubjectKeys.add(items.get(i).filterKey);
                        }
                    }
                    prefs.edit().putStringSet(KEY_FILTER_HIDDEN, hiddenSubjectKeys).apply();
                    new LoadPlanTask().execute();
                })
                .setNeutralButton("Reset", (dialog, which) -> {
                    hiddenSubjectKeys.clear();
                    prefs.edit().remove(KEY_FILTER_HIDDEN).apply();
                    new LoadPlanTask().execute();
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    // --- CACHE LISTY PRZEDMIOTÓW ---

    private List<PlanRepository.SubjectFilterItem> loadFilterCache() {
        long ts = prefs.getLong(KEY_FILTER_CACHE_TS, 0L);
        if (ts == 0L) return null;

        long now = System.currentTimeMillis();
        if (now - ts > FILTER_CACHE_TTL_MS) {
            return null; // cache przeterminowany
        }

        String json = prefs.getString(KEY_FILTER_CACHE_JSON, null);
        if (json == null || json.isEmpty()) return null;

        try {
            JSONArray arr = new JSONArray(json);
            List<PlanRepository.SubjectFilterItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                PlanRepository.SubjectFilterItem it = new PlanRepository.SubjectFilterItem();
                it.label     = obj.optString("label", "");
                it.typeLabel = obj.optString("typeLabel", "");
                it.filterKey = obj.optString("filterKey", "");
                items.add(it);
            }
            return items;
        } catch (JSONException e) {
            return null;
        }
    }

    private void saveFilterCache(List<PlanRepository.SubjectFilterItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (PlanRepository.SubjectFilterItem it : items) {
                JSONObject obj = new JSONObject();
                obj.put("label", it.label != null ? it.label : "");
                obj.put("typeLabel", it.typeLabel != null ? it.typeLabel : "");
                obj.put("filterKey", it.filterKey != null ? it.filterKey : "");
                arr.put(obj);
            }
            prefs.edit()
                    .putString(KEY_FILTER_CACHE_JSON, arr.toString())
                    .putLong(KEY_FILTER_CACHE_TS, System.currentTimeMillis())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private void clearFilterCache() {
        prefs.edit()
                .remove(KEY_FILTER_CACHE_JSON)
                .remove(KEY_FILTER_CACHE_TS)
                .apply();
    }

    /* =============== GODZINY I NAGŁÓWKI =============== */

    private void setupTimeColumn() {
        layoutTimeColumn.removeAllViews();
        for (int h = START_HOUR; h < END_HOUR; h++) {
            TextView tv = new TextView(this);
            tv.setText(String.format("%02d:00", h));
            tv.setTextColor(0xFF9CA3AF);
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
        String[] dni = {"Pon", "Wt", "Śr", "Czw", "Pt", "Sob", "Nd"};
        for (String d : dni) {
            TextView tv = new TextView(this);
            tv.setText(d);
            tv.setTextColor(0xFF9CA3AF);
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

    /* =============== ŁADOWANIE PLANU =============== */

    private class LoadPlanTask extends AsyncTask<Void, Void, PlanRepository.PlanResult> {
        Exception error;

        @Override
        protected void onPreExecute() {
            progress.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            // zatrzymaj aktualizację linii – i tak zaraz przerysujemy
            nowLineHandler.removeCallbacks(nowLineRunnable);
            nowLineParent = null;
            nowLineView   = null;
        }

        @Override
        protected PlanRepository.PlanResult doInBackground(Void... voids) {
            try {
                return planRepository.loadPlan(viewMode, currentDate);
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
                tvEmpty.setText(error != null
                        ? "Błąd ładowania planu: " + error.getMessage()
                        : "Błąd ładowania planu.");
                return;
            }

            currentDate = result.current;
            viewMode    = result.viewMode != null ? result.viewMode : viewMode;

            updateViewModeButtonsUi();
            tvHeaderLabel.setText(result.headerLabel != null ? result.headerLabel : "");

            if ("day".equals(viewMode) || "week".equals(viewMode)) {
                renderWeekOrDay(result);
            } else {
                renderMonth(result);
            }

            if (("day".equals(viewMode) || "week".equals(viewMode)) && !result.hasAnyEventsInRange) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("Brak zajęć w wybranym zakresie.");
            } else if ("month".equals(viewMode)
                    && (result.monthGrid == null || result.monthGrid.isEmpty())) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("Brak danych planu dla tego miesiąca.");
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        }
    }

    /* =============== RYSOWANIE DAY/WEEK  =============== */

    private void renderWeekOrDay(PlanRepository.PlanResult result) {
        layoutWeekRoot.setVisibility(View.VISIBLE);
        layoutMonthRoot.setVisibility(View.GONE);

        layoutWeekDays.removeAllViews();

        final float totalHours = END_HOUR - START_HOUR;
        final int columnHeight = (int) (dpToPx(HOUR_HEIGHT_DP) * totalHours);

        List<PlanRepository.DayColumn> cols =
                result.dayColumns != null ? result.dayColumns : Collections.emptyList();

        // ukrywanie soboty i niedzieli, jeśli obie nie mają żadnych zajęć (tylko w widoku tygodnia)
        if ("week".equals(viewMode) && cols.size() >= 7) {
            int satIndex = -1;
            int sunIndex = -1;
            boolean satHasEvents = false;
            boolean sunHasEvents = false;

            for (int i = 0; i < cols.size(); i++) {
                PlanRepository.DayColumn col = cols.get(i);
                if (col == null || col.date == null) continue;

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

        final LocalDate today = LocalDate.now();

        for (PlanRepository.DayColumn col : cols) {

            boolean isSelectedDay = (col.date != null && col.date.equals(currentDate));

            LinearLayout dayColumn = new LinearLayout(this);
            dayColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            dayLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
            dayColumn.setLayoutParams(dayLp);

            // Nagłówek dnia (stała wysokość)
            TextView tvHeader = new TextView(this);
            tvHeader.setText(formatDayHeader(col.date));
            tvHeader.setTextColor(0xFFE5E7EB);
            tvHeader.setTextSize(12f);
            tvHeader.setGravity(Gravity.CENTER);
            tvHeader.setPadding(0, dpToPx(4), 0, dpToPx(4));
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(DAY_HEADER_HEIGHT_DP)
            );
            tvHeader.setLayoutParams(headerLp);

            if (isSelectedDay) {
                tvHeader.setBackgroundColor(0xFF02091B); // delikatnie jaśniejsze tło dla wybranego dnia
            }

            dayColumn.addView(tvHeader);

            // ciało z eventami
            FrameLayoutWithChildren dayBody = new FrameLayoutWithChildren(this);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    columnHeight
            );
            dayBody.setLayoutParams(bodyLp);
            dayBody.setBackgroundColor(isSelectedDay ? 0xFF020918 : 0xFF020617);
            dayColumn.addView(dayBody);

            // poziome linie godzin – delikatne, za eventami
            addHourLines(dayBody);

            // eventy do narysowania
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

            // po zmierzeniu kolumny – ustawiamy top/height/left/width + linia "teraz"
            dayBody.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            dayBody.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            int w = dayBody.getWidth();
                            if (w <= 0) return;

                            layoutEventsInDayBody(dayBody, renderedEvents, w);

                            // linia "teraz" – tylko na DZISIEJSZYM dniu
                            if (colDate != null
                                    && ("day".equals(viewMode) || "week".equals(viewMode))
                                    && colDate.equals(today)) {
                                setupNowLine(dayBody, w);
                            }
                        }
                    });

            layoutWeekDays.addView(dayColumn);
        }
    }

    private void addHourLines(FrameLayoutWithChildren dayBody) {
        // linia na początku każdej godziny od START_HOUR do END_HOUR-1
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
            // delikatna linia, półprzezroczysta, ZA eventami (bo dodawana wcześniej)
            line.setBackgroundColor(0x331F2937);
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
        int calEnd   = END_HOUR   * 60;

        if (renderedEvents.isEmpty()) return;

        // sortujemy po starcie
        renderedEvents.sort(Comparator.comparingInt(o -> o.ev.startMin));

        // budowa klastrów (dowolny overlap)
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
                if (s < clusterEnd) { // nakłada się w czasie -> ten sam klaster
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
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        int marginPx = dpToPx(2);
        int overlapOffset = dpToPx(6); // lekkie przesunięcie przy małym overlapie

        for (List<RenderedEvent> cluster : clusters) {
            if (cluster.isEmpty()) continue;

            // sprawdzamy, czy w klastrze są "prawdziwe" kolizje (overlap >= 20 minut)
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

            // Jeśli brak prawdziwych kolizji -> pełna szerokość + lekkie przesunięcia
            if (!hasRealCollision) {
                int clusterSize = cluster.size();

                int maxOffset = overlapOffset * (clusterSize - 1);
                int availableWidth = widthPx - 2 * marginPx - maxOffset;
                if (availableWidth < dpToPx(40)) {
                    // awaryjnie – nie ściskamy za mocno
                    availableWidth = widthPx - 2 * marginPx;
                    maxOffset = 0;
                }

                for (int i = 0; i < cluster.size(); i++) {
                    RenderedEvent re = cluster.get(i);

                    int startMin = re.ev.startMin;
                    int endMin   = re.ev.endMin;

                    if (endMin <= calStart || startMin >= calEnd) {
                        re.view.setVisibility(View.GONE);
                        continue;
                    }

                    int startClamped = Math.max(startMin, calStart);
                    int endClamped   = Math.min(endMin, calEnd);
                    int duration     = Math.max(endClamped - startClamped, 15);

                    float offsetMinutes = startClamped - calStart;
                    float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);
                    float heightPx = (duration / 60f) * dpToPx(HOUR_HEIGHT_DP);
                    if (heightPx < dpToPx(22)) {
                        heightPx = dpToPx(22);
                    }

                    FrameLayoutWithChildren.LayoutParams lp =
                            (FrameLayoutWithChildren.LayoutParams) re.view.getLayoutParams();

                    lp.topMargin = (int) topPx;
                    lp.height    = (int) heightPx;
                    lp.leftMargin = marginPx + i * overlapOffset;
                    lp.width      = availableWidth;

                    re.view.setLayoutParams(lp);
                }

                continue; // nie robimy lane’ów dla tego klastra
            }

            // --- klasyczny lane-algorithm dla "dużych" kolizji ---

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
                int endMin   = re.ev.endMin;

                if (endMin <= calStart || startMin >= calEnd) {
                    re.view.setVisibility(View.GONE);
                    continue;
                }

                int startClamped = Math.max(startMin, calStart);
                int endClamped   = Math.min(endMin, calEnd);
                int duration     = Math.max(endClamped - startClamped, 15);

                float offsetMinutes = startClamped - calStart;
                float topPx = (offsetMinutes / 60f) * dpToPx(HOUR_HEIGHT_DP);
                float heightPx = (duration / 60f) * dpToPx(HOUR_HEIGHT_DP);
                if (heightPx < dpToPx(22)) {
                    heightPx = dpToPx(22);
                }

                FrameLayoutWithChildren.LayoutParams lp =
                        (FrameLayoutWithChildren.LayoutParams) re.view.getLayoutParams();

                lp.topMargin = (int) topPx;
                lp.height    = (int) heightPx;
                lp.leftMargin = (int) (marginPx + laneIdx * laneWidth);
                lp.width      = (int) (laneWidth - 2 * marginPx);

                re.view.setLayoutParams(lp);
            }
        }
    }

    /* =============== LINIA "TERAZ" =============== */

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
            nowLineView.setBackgroundColor(0xFFF97373); // czerwona linia
            parent.addView(nowLineView);
            nowLineView.bringToFront();
        }

        updateNowLinePosition();
        nowLineHandler.removeCallbacks(nowLineRunnable);
        scheduleNextNowLineUpdate();
    }

    private void updateNowLinePosition() {
        if (nowLineParent == null || nowLineView == null) return;

        LocalTime now = LocalTime.now();
        int minutesNow = now.getHour() * 60 + now.getMinute();
        int minBound   = START_HOUR * 60;
        int maxBound   = END_HOUR   * 60;
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
        tv.setTextColor(0xFFF9FAFB);
        tv.setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));
        tv.setClickable(true);

        // zaokrąglony, przyciemniony background
        int color = colorForType(ev.typeClass);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpToPx(6)); // ZAOKRĄGLENIE
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

    private int colorForType(String typeClass) {
        if (typeClass == null) typeClass = "";

        // mocno przyciemnione, nadal pełna alfa (bez przezroczystości)
        switch (typeClass) {

            case "week-event-type-lecture":      // wykład
                return 0xFF1E3A8A; // ciemny niebieski

            case "week-event-type-lab":          // laboratoria
                return 0xFF064E3B; // ciemny zielony

            case "week-event-type-auditory":     // ćwiczenia audytoryjne
                return 0xFF3730A3; // ciemny fiolet/indigo

            case "week-event-type-exam":         // egzamin
                return 0xFF7F1D1D; // ciemny czerwony

            case "week-event-type-cancelled":
                return 0xFF374151; // szary

            case "week-event-type-rector":
                return 0xFF78350F; // ciemny pomarańcz/brąz

            case "week-event-type-remote":
                return 0xFF1E40AF; // ciemny niebieski (zdalne)

            case "week-event-type-pass":
            case "week-event-type-pass-retake":
            case "week-event-type-pass-remote":
            case "week-event-type-pass-remote-retake":
                return 0xFF166534; // ciemny zielony

            default:
                return 0xFF1D4ED8; // ciemny niebieski domyślny
        }
    }

    private void showEventDetails(PlanRepository.PlanEventUi ev) {
        String time = ev.startStr + " - " + ev.endStr;
        StringBuilder msg = new StringBuilder();
        msg.append(time);

        if (ev.room != null && !ev.room.isEmpty()) {
            msg.append("\nSala: ").append(ev.room);
        }
        if (ev.group != null && !ev.group.isEmpty()) {
            msg.append("\nGrupa: ").append(ev.group);
        }
        if (ev.teacher != null && !ev.teacher.isEmpty()) {
            msg.append("\nProwadzący: ").append(ev.teacher);
        }

        new AlertDialog.Builder(this)
                .setTitle(ev.title != null ? ev.title : "Zajęcia")
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private String formatDayHeader(LocalDate date) {
        if (date == null) return "";
        String shortName;
        switch (date.getDayOfWeek()) {
            case MONDAY:    shortName = "Pn"; break;
            case TUESDAY:   shortName = "Wt"; break;
            case WEDNESDAY: shortName = "Śr"; break;
            case THURSDAY:  shortName = "Cz"; break;
            case FRIDAY:    shortName = "Pt"; break;
            case SATURDAY:  shortName = "Sb"; break;
            case SUNDAY:
            default:        shortName = "Nd"; break;
        }
        return shortName + "\n" + date.format(DateTimeFormatter.ofPattern("dd.MM"));
    }

    /* =============== RYSOWANIE MIESIĄCA =============== */

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
                    cellRoot.setBackgroundColor(0x112563EB);
                } else {
                    cellRoot.setBackgroundColor(0xFF020617);
                }

                TextView tvNum = new TextView(this);
                tvNum.setText(String.valueOf(cell.date.getDayOfMonth()));
                tvNum.setTextColor(0xFFE5E7EB);
                tvNum.setTextSize(13f);
                cellRoot.addView(tvNum);

                if (cell.hasPlan) {
                    TextView tvHint = new TextView(this);
                    tvHint.setText("Zajęcia w tym dniu");
                    tvHint.setTextSize(9f);
                    tvHint.setTextColor(0xFF9CA3AF);
                    cellRoot.addView(tvHint);

                    cellRoot.setOnClickListener(v -> {
                        viewMode = "day";
                        currentDate = cell.date;
                        new LoadPlanTask().execute();
                    });
                }

                gridMonth.addView(cellRoot);
            }
        }
    }

    /* =============== POMOCNICZE =============== */

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
}
