package pl.kejlo.zutnik;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class InfoTabFragment extends ZutnikTabFragment {
    // region Cache configuration

    private static final String PREFS_INFO_CACHE = "zutnik_info_cache";
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

    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private ImageView imageAvatar;
    private ImageView btnInfoRefresh;

    private TextView tvName;
    private TextView tvUserId;

    private TextView tvAlbum;
    private TextView tvKierunek;
    private TextView tvStatus;
    private TextView tvRok;
    private TextView tvSemestr;
    private TextView tvEctsProgramme;
    private TextView tvEctsOverall;
    private TextView tvElsStatus;
    private TextView tvElsExpiration;
    private TextView tvElsId;

    private LinearLayout historyContainer;
    private View progress;
    private View infoContentRoot;
    private Spinner spinnerStudies;
    private boolean spinnerInitialized = false;
    private ArrayAdapter<String> studiesAdapter;
    private AdapterView.OnItemSelectedListener studiesSpinnerListener;

    // endregion

    // endregion

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentInfoFuture;

    @Override
    @Nullable
    protected Toolbar getTabToolbar() {
        return toolbar;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return ShellLayoutInflater.inflateTabContent(inflater, R.layout.activity_info, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        drawerContentRoot = view.findViewById(R.id.drawerContentRoot);
        toolbar = view.findViewById(R.id.toolbar);
        shellActivity().setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(shellActivity(), toolbar);
        toolbar.setTitle(R.string.info_title);

        tvName = view.findViewById(R.id.tvInfoName);
        tvUserId = view.findViewById(R.id.tvInfoUserId);
        imageAvatar = view.findViewById(R.id.imageInfoAvatar);
        btnInfoRefresh = view.findViewById(R.id.btnInfoRefresh);

        tvAlbum = view.findViewById(R.id.tvInfoAlbum);
        tvKierunek = view.findViewById(R.id.tvInfoKierunek);
        tvStatus = view.findViewById(R.id.tvInfoStatus);
        tvRok = view.findViewById(R.id.tvInfoRok);
        tvSemestr = view.findViewById(R.id.tvInfoSemestr);
        tvEctsProgramme = view.findViewById(R.id.tvInfoEctsProgramme);
        tvEctsOverall = view.findViewById(R.id.tvInfoEctsOverall);
        tvElsStatus = view.findViewById(R.id.tvInfoElsStatus);
        tvElsExpiration = view.findViewById(R.id.tvInfoElsExpiration);
        tvElsId = view.findViewById(R.id.tvInfoElsId);

        historyContainer = view.findViewById(R.id.infoHistoryContainer);
        progress = view.findViewById(R.id.infoProgress);
        infoContentRoot = view.findViewById(R.id.infoContent);
        spinnerStudies = view.findViewById(R.id.spinnerStudies);

        if (infoContentRoot != null) {
            infoContentRoot.setAlpha(0f);
            infoContentRoot.setTranslationY(32f);
        }

        // Session & basic user info
        ZutnikSession session = ZutnikSession.getInstance();
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

        if (session.isUsosLogin() || session.isDemoLogin()) {
            String sn = session.getStudentNumber();
            setOrHide(tvAlbum, sn);
        }

        // ── Shared load logic for study details ─────────────────────────────
        String currentScopeKey = getActiveStudyCacheScopeKey();
        loadInfoFromCacheIfAvailable(currentScopeKey);
        setupStudiesSpinner();
        
        if (shouldFetchFromNetwork(currentScopeKey)) {
            startInfoLoad(false);
        }
        
        if (btnInfoRefresh != null) {
            btnInfoRefresh.setOnClickListener(v -> startInfoLoad(true));
        }
    }

    private void revealInfoContent() {
        if (infoContentRoot == null) {
            return;
        }
        infoContentRoot.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .start();
    }

    // region Network loading

    private void startInfoLoad(boolean forceReload) {
        // Manual refresh should bypass local info cache for current study.
        if (forceReload) {
            clearInfoCacheForActiveStudy();
            Toast.makeText(requireContext(), R.string.info_sync_in_progress, Toast.LENGTH_SHORT).show();
        }

        if (currentInfoFuture != null) {
            currentInfoFuture.cancel(true);
        }
        executeLoadInfoTask(forceReload);
    }

    private void executeLoadInfoTask(boolean forceRefreshStudies) {
        progress.setVisibility(View.VISIBLE);
        infoContentRoot.setAlpha(0.3f);
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
            final String finalScopeKey = getActiveStudyCacheScopeKey();

            handler.post(() -> {
                progress.setVisibility(View.GONE);
                infoContentRoot.setAlpha(1f);
                if (finalScopeKey != null && !finalScopeKey.equals(getActiveStudyCacheScopeKey())) {
                    return;
                }

                if (!finalSuccess) {
                    String message = finalError != null ? finalError.getMessage() : "";
                    android.util.Log.e("ZUTnik-INFO", "Failed to load student info or studies cache", finalError);
                    
                    // Support suppression of "Unable to resolve host" toast
                    boolean isDnsError = message != null && message.contains("Unable to resolve host");
                    boolean isOffline = !NetworkStatusHelper.isNetworkAvailable(requireContext());

                    if (isOffline || isDnsError) {
                        android.util.Log.d("InfoActivity", "Suppressed toast error: " + message);
                    } else {
                        Toast.makeText(
                                requireContext(),
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
                revealInfoContent();
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
        ZutnikSession session = ZutnikSession.getInstance();
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
        ZutnikSession session = ZutnikSession.getInstance();
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
        SharedPreferences cache = requireContext().getSharedPreferences(PREFS_INFO_CACHE, Context.MODE_PRIVATE);

        long ts = cache.getLong(getCacheKey(KEY_INFO_TIMESTAMP, scopeKey), 0L);
        if (ts == 0L) {
            return true;
        }

        // If offline, don't try to refresh expired cache
        if (!NetworkStatusHelper.isNetworkAvailable(requireContext())) {
            return false;
        }

        long now = System.currentTimeMillis();
        return (now - ts) > INFO_CACHE_TTL_MS;
    }

    // Loads data from cache (if available) and binds it to the views.
    // Returns true if cache was successfully loaded and displayed.
    private boolean loadInfoFromCacheIfAvailable(String scopeKey) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_INFO_CACHE, Context.MODE_PRIVATE);
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
        if (somethingLoaded) {
            revealInfoContent();
        }
        return somethingLoaded;
    }

    private void bindDetailsFromCache(JSONObject obj) {
        if (obj == null) {
            return;
        }
        
        setOrHide(tvAlbum, obj.optString("album", null));
        setOrHide(tvKierunek, obj.optString("kierunek", null));
        setOrHide(tvStatus, obj.optString("status", null));
        setOrHide(tvRok, obj.optString("rokAkademicki", null));
        setOrHide(tvSemestr, obj.optString("semestrLabel", null));
        setOrHide(tvEctsProgramme, obj.optString("ectsProgramme", null));
        setOrHide(tvEctsOverall, obj.optString("ectsOverall", null));
        setOrHide(tvElsStatus, obj.optString("elsStatus", null));
        setOrHide(tvElsExpiration, obj.optString("elsExpirationDate", null));
        setOrHide(tvElsId, obj.optString("elsId", null));
    }

    // Saves the data fetched from the API into cache.
    private void saveInfoToCache(String scopeKey,
            StudiesInfoRepository.StudyDetails d,
            List<StudiesInfoRepository.StudyHistoryItem> history) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_INFO_CACHE, Context.MODE_PRIVATE);
        JSONObject detailsObj = new JSONObject();
        JSONArray historyArr = new JSONArray();

        try {
            if (d != null) {
                detailsObj.put("album", d.album != null ? d.album : "");
                detailsObj.put("kierunek", d.kierunek != null ? d.kierunek : "");
                detailsObj.put("status", d.status != null ? d.status : "");
                detailsObj.put("rokAkademicki", d.rokAkademicki != null ? d.rokAkademicki : "");
                detailsObj.put("semestrLabel", d.semestrLabel != null ? d.semestrLabel : "");
                detailsObj.put("ectsProgramme", formatEcts(d.ectsProgramme));
                detailsObj.put("ectsOverall", formatEcts(d.ectsOverall));
                detailsObj.put("elsStatus", d.elsStatus != null ? d.elsStatus : "");
                detailsObj.put("elsExpirationDate", d.elsExpirationDate != null ? d.elsExpirationDate : "");
                detailsObj.put("elsId", d.elsId != null ? d.elsId : "");
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
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_INFO_CACHE, Context.MODE_PRIVATE);
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
        ZutnikSession session = ZutnikSession.getInstance();
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
                    requireContext(),
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
                    ZutnikSession s = ZutnikSession.getInstance();
                    if (position == s.getActiveStudyIndex()) {
                        return;
                    }
                    s.setActiveStudyIndex(position);
                    s.saveToPreferences(requireContext());

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
        
        // Direction/Programme (Kierunek)
        setOrHide(tvKierunek, d.kierunek);
        
        // Status (Aktywny/Nieaktywny)
        setOrHide(tvStatus, d.status);
        
        // Academic Year (Rok Akademicki) - e.g., 2023/2024
        setOrHide(tvRok, d.rokAkademicki);
        
        // Semester Label (Semestr) - e.g., "1 zimowy" or full USOS term name
        setOrHide(tvSemestr, d.semestrLabel);

        setOrHide(tvEctsProgramme, formatEcts(d.ectsProgramme));
        setOrHide(tvEctsOverall, formatEcts(d.ectsOverall));
        setOrHide(tvElsStatus, d.elsStatus);
        setOrHide(tvElsExpiration, d.elsExpirationDate);
        setOrHide(tvElsId, d.elsId);
    }

    private String formatEcts(Double value) {
        if (value == null || value < 0.0 || Double.isNaN(value)) {
            return "";
        }
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.format(java.util.Locale.getDefault(), "%.0f ECTS", value);
        }
        return String.format(java.util.Locale.getDefault(), "%.1f ECTS", value);
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
        if (tv == null) {
            return;
        }
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
        TextView tv = new TextView(requireContext());
        tv.setText(text);

        if (isEmpty) {
            tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.mzMuted));
            tv.setTextSize(HISTORY_EMPTY_TEXT_SIZE_SP);
        } else {
            tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.mzText));
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

            // 2. Fetch via ZutnikNetwork (handles SSL/certs)
            try {
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "ZUTnik-Android-Info/2.0")
                        .build();

                try (okhttp3.Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
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
