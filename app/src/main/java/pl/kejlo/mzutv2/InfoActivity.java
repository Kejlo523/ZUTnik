package pl.kejlo.mzutv2;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.view.MotionEvent;
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

public class InfoActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
    // region Cache configuration

    private static final String PREFS_INFO_CACHE = "mzut_info_cache";
    private static final String KEY_INFO_DETAILS_JSON = "info_details_json";
    private static final String KEY_INFO_HISTORY_JSON = "info_history_json";
    private static final String KEY_INFO_TIMESTAMP = "info_timestamp";
    // 45 days
    private static final long INFO_CACHE_TTL_MS = 45L * 24L * 60L * 60L * 1000L;

    // History text appearance
    private static final float HISTORY_EMPTY_TEXT_SIZE_SP = 12f;
    private static final float HISTORY_TEXT_SIZE_SP = 13f;
    // w dp, potem przeliczymy na px
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

    // endregion

    // endregion

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private java.util.concurrent.Future<?> currentInfoFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_info);

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

        // Session & basic user info
        MzutSession session = MzutSession.getInstance();
        String username = session.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = session.getUserId();
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

        // 1) Try to load from cache and bind immediately if possible
        MzutSession sessionObj = MzutSession.getInstance();
        int currentIndex = sessionObj.getActiveStudyIndex();
        loadInfoFromCacheIfAvailable(currentIndex);

        // 2) Setup studies spinner (may already be available from other screens)
        setupStudiesSpinner();

        // 3) If cache is missing or outdated, fetch from network
        if (shouldFetchFromNetwork(currentIndex)) {
            startInfoLoad(false);
        }

        // 4) Refresh icon – forces reload
        if (btnInfoRefresh != null) {
            btnInfoRefresh.setOnClickListener(v -> startInfoLoad(true));
        }
    }

    // region Network loading

    private void startInfoLoad(boolean forceReload) {
        // forceReload simply ignores TTL at the call site and always fetches
        Toast.makeText(this, R.string.info_sync_in_progress, Toast.LENGTH_SHORT).show();

        if (currentInfoFuture != null) {
            currentInfoFuture.cancel(true);
        }
        executeLoadInfoTask();
    }

    private void executeLoadInfoTask() {
        progress.setVisibility(View.VISIBLE);
        infoContentRoot.setAlpha(0.3f);

        currentInfoFuture = executor.submit(() -> {
            StudiesInfoRepository.StudyDetails details = null;
            List<StudiesInfoRepository.StudyHistoryItem> history = null;
            Exception error = null;
            boolean success = false;

            try {
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

                if (!finalSuccess) {
                    String message = finalError != null ? finalError.getMessage() : "";
                    Toast.makeText(
                            InfoActivity.this,
                            getString(R.string.info_error_loading, message),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if (finalDetails != null) {
                    bindDetails(finalDetails);
                }

                bindHistory(finalHistory);
                // Ensure studies spinner is updated, e.g. after the first login
                setupStudiesSpinner();

                // Save to cache after successful refresh
                saveInfoToCache(MzutSession.getInstance().getActiveStudyIndex(), finalDetails, finalHistory);
            });
        });
    }

    // endregion

    // region Cache logic

    // region Cache logic

    private String getCacheKey(String baseKey, int index) {
        return baseKey + "_" + index;
    }

    // Returns true if data should be fetched from the network for the given index
    // (cache is empty or older than TTL).
    private boolean shouldFetchFromNetwork(int index) {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE); // Use default or specific? File is separate.
        // Actually code uses specific file: PREFS_INFO_CACHE
        SharedPreferences cache = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);

        long ts = cache.getLong(getCacheKey(KEY_INFO_TIMESTAMP, index), 0L);
        if (ts == 0L) {
            return true;
        }

        long now = System.currentTimeMillis();
        return (now - ts) > INFO_CACHE_TTL_MS;
    }

    // Loads data from cache (if available) and binds it to the views.
    // Returns true if cache was successfully loaded and displayed.
    private boolean loadInfoFromCacheIfAvailable(int index) {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        String keyDetails = getCacheKey(KEY_INFO_DETAILS_JSON, index);
        String keyHistory = getCacheKey(KEY_INFO_HISTORY_JSON, index);

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
    private void saveInfoToCache(int index,
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
                    .putString(getCacheKey(KEY_INFO_DETAILS_JSON, index), detailsObj.toString())
                    .putString(getCacheKey(KEY_INFO_HISTORY_JSON, index), historyArr.toString())
                    .putLong(getCacheKey(KEY_INFO_TIMESTAMP, index), System.currentTimeMillis())
                    .apply();

        } catch (JSONException e) {
            // Cache is optional, ignore errors
        }
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
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    R.layout.spinner_item_dark,
                    labels);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            spinnerStudies.setAdapter(adapter);
            spinnerInitialized = true;

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
                        return;
                    }
                    s.setActiveStudyIndex(position);

                    // Smart switching:
                    // 1. Try to load cached data for this index
                    boolean loaded = loadInfoFromCacheIfAvailable(position);

                    // 2. If no valid cache or expired, fetch from network
                    if (!loaded || shouldFetchFromNetwork(position)) {
                        startInfoLoad(false);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // no-op
                }
            });
        } else {
            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) {
                activeIndex = 0;
            }
            spinnerStudies.setSelection(activeIndex);
        }
    }

    // endregion

    // region Binding data from API

    private void bindDetails(StudiesInfoRepository.StudyDetails d) {
        setOrHide(tvAlbum, d.album);
        setOrHide(tvWydzial, d.wydzial);
        setOrHide(tvKierunek, d.kierunek);
        setOrHide(tvForma, d.forma);
        setOrHide(tvPoziom, d.poziom);
        setOrHide(tvSpecjalnosc, d.specjalnosc);
        setOrHide(tvSpecjalizacja, d.specjalizacja);
        setOrHide(tvStatus, d.status);
        setOrHide(tvRok, d.rokAkademicki);
        setOrHide(tvSemestr, d.semestrLabel);
    }

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

    private void setOrHide(TextView tv, String value) {
        if (value == null || value.trim().isEmpty()) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setVisibility(View.VISIBLE);
            tv.setText(value);
        }
    }

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
