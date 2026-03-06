package pl.kejlo.mzutv2;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Context;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class InfoActivity extends MzutBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
    // region Cache configuration

    private static final String PREFS_INFO_CACHE = "mzut_info_cache";
    private static final String KEY_INFO_DETAILS_JSON = "info_details_json";
    private static final String KEY_INFO_HISTORY_JSON = "info_history_json";
    private static final String KEY_INFO_TIMESTAMP = "info_timestamp";
    private static final long INFO_CACHE_TTL_MS = CachePolicy.INFO_TTL_MS;

    // History text appearance
    private static final float HISTORY_EMPTY_TEXT_SIZE_SP = 12f;
    private static final float HISTORY_TEXT_SIZE_SP = 13f;
    // in dp, converted to px later
    private static final int HISTORY_ITEM_VERTICAL_PADDING_DP = 6;

    // endregion

    // region Views

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private ImageView imageAvatar;
    private ImageView btnInfoRefresh;

    private TextView tvName;
    private TextView tvUserId;

    private TextView tvAlbum;
    private TextView tvWydzial;
    private TextView tvKierunek;
    private TextView tvForma;
    private TextView tvPoziom;
    private TextView tvSpecjalnosc;
    private TextView tvSpecjalizacja;
    private TextView tvStatus;
    private TextView tvRok;
    private TextView tvSemestr;

    private LinearLayout historyContainer;
    private View progress;
    private View infoContentRoot;
    private Spinner spinnerStudies;
    private boolean spinnerInitialized = false;
    private ArrayAdapter<String> studiesAdapter;
    private AdapterView.OnItemSelectedListener studiesSpinnerListener;

    // USOS Payments
    private View cardUsosPayments;
    private LinearLayout usosPaymentsContainer;
    private ImageView btnPaymentsRefresh;

    // endregion

    // endregion

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentInfoFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_info);
        ThemeManager.applySystemBars(this);

        // Views
        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setTitle(R.string.info_title);

        // Navigation drawer for the "info" screen
        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.INFO);

        tvName = findViewById(R.id.tvInfoName);
        tvUserId = findViewById(R.id.tvInfoUserId);
        imageAvatar = findViewById(R.id.imageInfoAvatar);
        btnInfoRefresh = findViewById(R.id.btnInfoRefresh);

        tvAlbum = findViewById(R.id.tvInfoAlbum);
        tvWydzial = findViewById(R.id.tvInfoWydzial);
        tvKierunek = findViewById(R.id.tvInfoKierunek);
        tvForma = findViewById(R.id.tvInfoForma);
        tvPoziom = findViewById(R.id.tvInfoPoziom);
        tvSpecjalnosc = findViewById(R.id.tvInfoSpecjalnosc);
        tvSpecjalizacja = findViewById(R.id.tvInfoSpecjalizacja);
        tvStatus = findViewById(R.id.tvInfoStatus);
        tvRok = findViewById(R.id.tvInfoRok);
        tvSemestr = findViewById(R.id.tvInfoSemestr);

        historyContainer = findViewById(R.id.infoHistoryContainer);
        progress = findViewById(R.id.infoProgress);
        infoContentRoot = findViewById(R.id.infoContent);
        spinnerStudies = findViewById(R.id.spinnerStudies);
        cardUsosPayments = findViewById(R.id.cardUsosPayments);
        usosPaymentsContainer = findViewById(R.id.usosPaymentsContainer);
        btnPaymentsRefresh = findViewById(R.id.btnPaymentsRefresh);

        // Session & basic user info
        MzutSession session = MzutSession.getInstance();
        String username = session.getUsername();
        if (username == null || username.trim().isEmpty()) {
            if (session.isUsosLogin()) {
                String sn = session.getStudentNumber();
                username = (sn != null && !sn.isEmpty()) ? sn : session.getUserId();
            } else {
                username = session.getUserId();
            }
        }
        if (username == null || username.trim().isEmpty()) {
            username = getString(R.string.nav_header_default_username);
        }
        tvName.setText(username);

        String userId = session.getUserId() != null ? session.getUserId() : "-";
        tvUserId.setText(getString(R.string.info_user_id, userId));

        // Avatar image
        String imageUrl = session.getImageUrl();
        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            loadAvatar(imageUrl);
        } else {
            imageAvatar.setVisibility(View.GONE);
        }

        if (session.isUsosLogin()) {
            // ── USOS mode: show album from session, load payments ────────────────
            String sn = session.getStudentNumber();
            setOrHide(tvAlbum, sn);

            if (cardUsosPayments != null) {
                cardUsosPayments.setVisibility(View.VISIBLE);
            }
            loadUsosPayments();

            if (btnPaymentsRefresh != null) {
                btnPaymentsRefresh.setOnClickListener(v -> loadUsosPayments());
            }
            if (btnInfoRefresh != null) {
                btnInfoRefresh.setOnClickListener(v -> loadUsosPayments());
            }
        } else if (cardUsosPayments != null) {
            cardUsosPayments.setVisibility(View.GONE);
        }

        // ── Shared load logic for study details ─────────────────────────────
        String currentScopeKey = getActiveStudyCacheScopeKey();
        loadInfoFromCacheIfAvailable(currentScopeKey);
        setupStudiesSpinner();
        
        if (shouldFetchFromNetwork(currentScopeKey)) {
            startInfoLoad(false);
        }
        
        if (btnInfoRefresh != null) {
            btnInfoRefresh.setOnClickListener(v -> {
                startInfoLoad(true);
                if (session.isUsosLogin()) {
                    loadUsosPayments();
                }
            });
        }

    }

    // region USOS Payments

    private void loadUsosPayments() {
        if (usosPaymentsContainer == null) return;
        usosPaymentsContainer.removeAllViews();
        addPaymentTextView(getString(R.string.info_sync_in_progress), true);

        executor.execute(() -> {
            try {
                JSONArray resp = UsosApi.getArray("services/payments/user_payments", null);
                handler.post(() -> bindPayments(resp));
            } catch (Exception e) {
                android.util.Log.e("InfoActivity", "Payments error", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                handler.post(() -> {
                    if (usosPaymentsContainer == null) return;
                    usosPaymentsContainer.removeAllViews();
                    addPaymentTextView(getString(R.string.info_usos_payment_error, msg), true);
                });
            }
        });
    }

    private void bindPayments(JSONArray items) {
        if (usosPaymentsContainer == null) return;
        usosPaymentsContainer.removeAllViews();

        if (items == null || items.length() == 0) {
            addPaymentTextView(getString(R.string.info_usos_no_payments), true);
            return;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            // Title: may be localized {"pl":...,"en":...} or plain string
            String title = extractLocalizedOrString(item, "name");
            if (title == null || title.isEmpty()) {
                title = extractLocalizedOrString(item, "title");
            }
            if (title == null || title.isEmpty()) {
                title = item.optString("id", "Płatność " + (i + 1));
            }

            String amount = item.optString("amount", null);
            String dueDate = item.optString("due_date", null);

            // Status: may be {"symbol":"paid","name":{...}} or plain string
            String statusSymbol = null;
            JSONObject statusObj = item.optJSONObject("status");
            if (statusObj != null) {
                statusSymbol = statusObj.optString("symbol", null);
            } else {
                statusSymbol = item.optString("status", null);
            }
            boolean isPaid = "paid".equalsIgnoreCase(statusSymbol)
                    || "1".equals(statusSymbol) || Boolean.TRUE.equals(item.opt("is_paid"));

            StringBuilder sb = new StringBuilder(title);
            if (amount != null && !amount.isEmpty()) {
                sb.append("\n").append(getString(R.string.info_usos_payment_amount, amount));
            }
            if (dueDate != null && !dueDate.isEmpty()) {
                sb.append("\n").append(getString(R.string.info_usos_payment_due, dueDate));
            }
            sb.append("\n").append(isPaid
                    ? getString(R.string.info_usos_payment_status_paid)
                    : getString(R.string.info_usos_payment_status_unpaid));

            addPaymentTextView(sb.toString(), false);

            // Divider between items
            if (i < items.length() - 1) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(ThemeManager.resolveColor(this, R.attr.mzMuted) & 0x40FFFFFF);
                usosPaymentsContainer.addView(divider);
            }
        }
    }

    private String extractLocalizedOrString(JSONObject obj, String key) {
        JSONObject localized = obj.optJSONObject(key);
        if (localized != null) {
            String val = localized.optString("pl", localized.optString("en", ""));
            return val.isEmpty() ? null : val;
        }
        String plain = obj.optString(key, null);
        return (plain == null || plain.isEmpty()) ? null : plain;
    }

    private void addPaymentTextView(String text, boolean muted) {
        if (usosPaymentsContainer == null) return;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        int padV = dpToPx(8);
        tv.setPadding(0, padV, 0, padV);
        tv.setTextColor(ThemeManager.resolveColor(this, muted ? R.attr.mzMuted : R.attr.mzText));
        usosPaymentsContainer.addView(tv);
    }

    // endregion

    // region Network loading

    private void startInfoLoad(boolean forceReload) {
        // Manual refresh should bypass local info cache for current study.
        if (forceReload) {
            clearInfoCacheForActiveStudy();
            Toast.makeText(this, R.string.info_sync_in_progress, Toast.LENGTH_SHORT).show();
        }

        if (currentInfoFuture != null) {
            currentInfoFuture.cancel(true);
        }
        executeLoadInfoTask(forceReload);
    }

    private void executeLoadInfoTask(boolean forceRefreshStudies) {
        progress.setVisibility(View.VISIBLE);
        infoContentRoot.setAlpha(0.3f);
        final String expectedScopeKey = getActiveStudyCacheScopeKey();

        currentInfoFuture = executor.submit(() -> {
            StudiesInfoRepository.StudyDetails details = null;
            List<StudiesInfoRepository.StudyHistoryItem> history = null;
            Exception error = null;
            boolean success = false;

            try {
                GradesRepository gradesRepository = new GradesRepository();
                gradesRepository.loadStudies(forceRefreshStudies);
                StudiesInfoRepository repo = new StudiesInfoRepository();
                details = repo.loadCurrentStudyDetails();
                history = repo.loadStudyHistory();
                success = true;
            } catch (Exception e) {
                error = e;
            }

            final StudiesInfoRepository.StudyDetails finalDetails = details;
            final List<StudiesInfoRepository.StudyHistoryItem> finalHistory = history;
            final Exception finalError = error;
            final boolean finalSuccess = success;

            handler.post(() -> {
                progress.setVisibility(View.GONE);
                infoContentRoot.setAlpha(1f);
                if (!expectedScopeKey.equals(getActiveStudyCacheScopeKey())) {
                    return;
                }

                if (!finalSuccess) {
                    String message = finalError != null ? finalError.getMessage() : "";
                    android.util.Log.e("mZUTv2-INFO", "Failed to load student info or studies cache", finalError);
                    
                    // Support suppression of "Unable to resolve host" toast
                    boolean isDnsError = message != null && message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(InfoActivity.this);

                    if (isOffline || isDnsError) {
                        android.util.Log.d("InfoActivity", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                InfoActivity.this,
                                getString(R.string.info_error_loading, message),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                if (finalDetails != null) {
                    bindDetails(finalDetails);
                }

                bindHistory(finalHistory);
                // Ensure studies spinner is updated, e.g. after the first login
                setupStudiesSpinner();

                // Save to cache after successful refresh
                saveInfoToCache(getActiveStudyCacheScopeKey(), finalDetails, finalHistory);
            });
        });
    }

    // endregion

    // region Cache logic

    // region Cache logic

    private String getCacheKey(String baseKey, String scopeKey) {
        String safeScope = (scopeKey == null || scopeKey.trim().isEmpty()) ? "default" : scopeKey.trim();
        return baseKey + "_" + safeScope;
    }

    private String getStudyCacheScopeKey(int index) {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies != null && index >= 0 && index < studies.size()) {
            Study selected = studies.get(index);
            if (selected != null && selected.przynaleznoscId != null && !selected.przynaleznoscId.trim().isEmpty()) {
                return selected.przynaleznoscId.trim();
            }
        }
        String activeId = session.getActiveStudyId();
        if (activeId != null && !activeId.trim().isEmpty()) {
            return activeId.trim();
        }
        return "idx_" + Math.max(0, index);
    }

    private String getActiveStudyCacheScopeKey() {
        MzutSession session = MzutSession.getInstance();
        String activeId = session.getActiveStudyId();
        if (activeId != null && !activeId.trim().isEmpty()) {
            return activeId.trim();
        }
        int idx = session.getActiveStudyIndex();
        if (idx < 0) {
            idx = 0;
        }
        return getStudyCacheScopeKey(idx);
    }

    // Returns true if data should be fetched from the network for the given study key
    // (cache is empty or older than TTL).
    private boolean shouldFetchFromNetwork(String scopeKey) {
        SharedPreferences cache = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);

        long ts = cache.getLong(getCacheKey(KEY_INFO_TIMESTAMP, scopeKey), 0L);
        if (ts == 0L) {
            return true;
        }

        // If offline, don't try to refresh expired cache
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            return false;
        }

        long now = System.currentTimeMillis();
        return (now - ts) > INFO_CACHE_TTL_MS;
    }

    // Loads data from cache (if available) and binds it to the views.
    // Returns true if cache was successfully loaded and displayed.
    private boolean loadInfoFromCacheIfAvailable(String scopeKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        String keyDetails = getCacheKey(KEY_INFO_DETAILS_JSON, scopeKey);
        String keyHistory = getCacheKey(KEY_INFO_HISTORY_JSON, scopeKey);

        String detailsJson = prefs.getString(keyDetails, null);
        String historyJson = prefs.getString(keyHistory, null);

        if (detailsJson == null && historyJson == null) {
            return false;
        }

        boolean somethingLoaded = false;

        // Details
        if (detailsJson != null) {
            try {
                JSONObject obj = new JSONObject(detailsJson);
                bindDetailsFromCache(obj);
                somethingLoaded = true;
            } catch (JSONException e) {
                // On error just ignore cache
            }
        }

        // History
        if (historyJson != null) {
            try {
                JSONArray arr = new JSONArray(historyJson);
                bindHistoryFromCache(arr);
                somethingLoaded = true;
            } catch (JSONException e) {
                // Fallback: show empty history message
                bindHistory(null);
            }
        }
        return somethingLoaded;
    }

    private void bindDetailsFromCache(JSONObject obj) {
        if (obj == null) {
            return;
        }
        
        setOrHide(tvAlbum, obj.optString("album", null));
        setOrHide(tvWydzial, obj.optString("wydzial", null));
        setOrHide(tvKierunek, obj.optString("kierunek", null));
        setOrHide(tvForma, obj.optString("forma", null));
        setOrHide(tvPoziom, obj.optString("poziom", null));
        setOrHide(tvSpecjalnosc, obj.optString("specjalnosc", null));
        setOrHide(tvSpecjalizacja, obj.optString("specjalizacja", null));
        setOrHide(tvStatus, obj.optString("status", null));
        setOrHide(tvRok, obj.optString("rokAkademicki", null));
        setOrHide(tvSemestr, obj.optString("semestrLabel", null));
    }

    // Saves the data fetched from the API into cache.
    private void saveInfoToCache(String scopeKey,
            StudiesInfoRepository.StudyDetails d,
            List<StudiesInfoRepository.StudyHistoryItem> history) {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        JSONObject detailsObj = new JSONObject();
        JSONArray historyArr = new JSONArray();

        try {
            if (d != null) {
                detailsObj.put("album", d.album != null ? d.album : "");
                detailsObj.put("wydzial", d.wydzial != null ? d.wydzial : "");
                detailsObj.put("kierunek", d.kierunek != null ? d.kierunek : "");
                detailsObj.put("forma", d.forma != null ? d.forma : "");
                detailsObj.put("poziom", d.poziom != null ? d.poziom : "");
                detailsObj.put("specjalnosc", d.specjalnosc != null ? d.specjalnosc : "");
                detailsObj.put("specjalizacja", d.specjalizacja != null ? d.specjalizacja : "");
                detailsObj.put("status", d.status != null ? d.status : "");
                detailsObj.put("rokAkademicki", d.rokAkademicki != null ? d.rokAkademicki : "");
                detailsObj.put("semestrLabel", d.semestrLabel != null ? d.semestrLabel : "");
            }

            if (history != null) {
                for (StudiesInfoRepository.StudyHistoryItem item : history) {
                    if (item == null) {
                        continue;
                    }
                    JSONObject h = new JSONObject();
                    h.put("label", item.label != null ? item.label : "");
                    h.put("status", item.status != null ? item.status : "");
                    historyArr.put(h);
                }
            }

            prefs.edit()
                    .putString(getCacheKey(KEY_INFO_DETAILS_JSON, scopeKey), detailsObj.toString())
                    .putString(getCacheKey(KEY_INFO_HISTORY_JSON, scopeKey), historyArr.toString())
                    .putLong(getCacheKey(KEY_INFO_TIMESTAMP, scopeKey), System.currentTimeMillis())
                    .apply();

        } catch (JSONException e) {
            // Cache is optional, ignore errors
        }
    }

    private void clearInfoCacheForActiveStudy() {
        String scopeKey = getActiveStudyCacheScopeKey();
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        prefs.edit()
                .remove(getCacheKey(KEY_INFO_DETAILS_JSON, scopeKey))
                .remove(getCacheKey(KEY_INFO_HISTORY_JSON, scopeKey))
                .remove(getCacheKey(KEY_INFO_TIMESTAMP, scopeKey))
                .apply();
    }

    // Creates history views based on cached JSON.
    private void bindHistoryFromCache(JSONArray arr) {
        historyContainer.removeAllViews();
        if (arr == null || arr.length() == 0) {
            addHistoryTextView(
                    getString(R.string.info_history_empty),
                    true);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String label = obj.optString("label", "");
            String status = obj.optString("status", "");
            String text = getString(R.string.info_history_item, label, status);
            addHistoryTextView(text, false);
        }
    }

    // endregion

    // region Studies spinner

    private void setupStudiesSpinner() {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            spinnerStudies.setVisibility(View.GONE);
            return;
        }

        spinnerStudies.setVisibility(View.VISIBLE);

        List<String> labels = new ArrayList<>();
        for (Study st : studies) {
            labels.add(st.toString());
        }

        if (!spinnerInitialized) {
            studiesAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.spinner_item_dark,
                    labels);
            studiesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            spinnerStudies.setAdapter(studiesAdapter);
            spinnerInitialized = true;

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) {
                activeIndex = 0;
            }
            spinnerStudies.setSelection(activeIndex);

            studiesSpinnerListener = new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    MzutSession s = MzutSession.getInstance();
                    if (position == s.getActiveStudyIndex()) {
                        return;
                    }
                    s.setActiveStudyIndex(position);
                    s.saveToPreferences(InfoActivity.this);

                    String studyScopeKey = getStudyCacheScopeKey(position);

                    // Smart switching:
                    // 1. Try to load cached data for this index
                    boolean loaded = loadInfoFromCacheIfAvailable(studyScopeKey);

                    // 2. If no valid cache or expired, fetch from network
                    if (!loaded || shouldFetchFromNetwork(studyScopeKey)) {
                        startInfoLoad(false);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // no-op
                }
            };
            spinnerStudies.setOnItemSelectedListener(studiesSpinnerListener);
        } else {
            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) {
                activeIndex = 0;
            }

            spinnerStudies.setOnItemSelectedListener(null);
            try {
                if (studiesAdapter != null) {
                    studiesAdapter.clear();
                    studiesAdapter.addAll(labels);
                    studiesAdapter.notifyDataSetChanged();
                }
                spinnerStudies.setSelection(activeIndex);
            } finally {
                spinnerStudies.setOnItemSelectedListener(studiesSpinnerListener);
            }
        }
    }

    // endregion

    // region Binding data from API

    /**
     * Binds study details to views, handling USOS/ZUT API differences.
     * USOS API provides richer data structure, while fields may be empty.
     */
    private void bindDetails(StudiesInfoRepository.StudyDetails d) {
        if (d == null) {
            return;
        }
        
        // Album (Student Number) - from USOS user data
        setOrHide(tvAlbum, d.album);
        
        // Faculty (Wydział)
        setOrHide(tvWydzial, d.wydzial);
        
        // Direction/Programme (Kierunek)
        setOrHide(tvKierunek, d.kierunek);
        
        // Form of study (Forma) - stacjonarne/niestacjonarne
        setOrHide(tvForma, d.forma);
        
        // Level of study (Poziom) - first/second degree, etc.
        setOrHide(tvPoziom, d.poziom);
        
        // Specialty (Specjalność)
        setOrHide(tvSpecjalnosc, d.specjalnosc);
        
        // Specialization (Specjalizacja)
        setOrHide(tvSpecjalizacja, d.specjalizacja);
        
        // Status (Aktywny/Nieaktywny)
        setOrHide(tvStatus, d.status);
        
        // Academic Year (Rok Akademicki) - e.g., 2023/2024
        setOrHide(tvRok, d.rokAkademicki);
        
        // Semester Label (Semestr) - e.g., "1 zimowy" or full USOS term name
        setOrHide(tvSemestr, d.semestrLabel);
    }

    /**
     * Binds study history (list of completed/active semesters).
     * USOS API provides term history with status.
     */
    private void bindHistory(List<StudiesInfoRepository.StudyHistoryItem> history) {
        historyContainer.removeAllViews();
        if (history == null || history.isEmpty()) {
            addHistoryTextView(
                    getString(R.string.info_history_empty),
                    true);
            return;
        }

        for (StudiesInfoRepository.StudyHistoryItem item : history) {
            String text = getString(R.string.info_history_item, item.label, item.status);
            addHistoryTextView(text, false);
        }
    }

    /**
     * Shows or hides a TextView based on whether the value is empty.
     */
    private void setOrHide(TextView tv, String value) {
        if (value == null || value.trim().isEmpty()) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setVisibility(View.VISIBLE);
            tv.setText(value);
        }
    }

    /**
     * Adds a single history item TextView to the history container.
     */
    private void addHistoryTextView(String text, boolean isEmpty) {
        TextView tv = new TextView(this);
        tv.setText(text);

        if (isEmpty) {
            tv.setTextColor(ThemeManager.resolveColor(this, R.attr.mzMuted));
            tv.setTextSize(HISTORY_EMPTY_TEXT_SIZE_SP);
        } else {
            tv.setTextColor(ThemeManager.resolveColor(this, R.attr.mzText));
            tv.setTextSize(HISTORY_TEXT_SIZE_SP);
            int padV = dpToPx(HISTORY_ITEM_VERTICAL_PADDING_DP);
            tv.setPadding(0, padV, 0, padV);
        }

        historyContainer.addView(tv);
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // endregion

    // region Avatar loading

    // region Avatar loading

    private void loadAvatar(String url) {
        if (url == null || url.isEmpty())
            return;

        executor.execute(() -> {
            // 1. Try cache (Disk check is safe here as this is background thread)
            try {
                android.graphics.Bitmap cached = ImageCache.getInstance().getFromDisk(url);
                if (cached != null) {
                    handler.post(() -> {
                        if (imageAvatar != null)
                            imageAvatar.setImageBitmap(cached);
                    });
                    return;
                }
            } catch (Exception ignored) {
            }

            // 2. Fetch via MzutNetwork (handles SSL/certs)
            try {
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "mZUTv2-Android-Info/1.0")
                        .build();

                try (okhttp3.Response response = MzutNetwork.getClient().newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        java.io.InputStream is = response.body().byteStream();
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);

                        // 3. Save to cache
                        if (bmp != null) {
                            try {
                                ImageCache.getInstance().put(url, bmp);
                            } catch (Exception ignored) {
                            }
                        }

                        final android.graphics.Bitmap finalBmp = bmp;
                        handler.post(() -> {
                            if (imageAvatar != null) {
                                if (finalBmp != null) {
                                    imageAvatar.setImageBitmap(finalBmp);
                                } else {
                                    imageAvatar.setVisibility(View.GONE);
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // endregion

    // endregion
}
