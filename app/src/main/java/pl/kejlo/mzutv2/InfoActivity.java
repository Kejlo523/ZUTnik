package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.SharedPreferences;
import android.os.AsyncTask;
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

    // Cache configuration
    private static final String PREFS_INFO_CACHE      = "mzut_info_cache";
    private static final String KEY_INFO_DETAILS_JSON = "info_details_json";
    private static final String KEY_INFO_HISTORY_JSON = "info_history_json";
    private static final String KEY_INFO_TIMESTAMP    = "info_timestamp";
    private static final long INFO_CACHE_TTL_MS       = 7L * 24L * 60L * 60L * 1000L; // 7 days

    private DrawerLayout drawerLayout;

    private ImageView imageAvatar;
    private ImageView btnInfoRefresh;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private TextView tvName, tvUserId;
    private TextView tvAlbum, tvWydzial, tvKierunek, tvForma,
            tvPoziom, tvSpecjalnosc, tvSpecjalizacja, tvStatus,
            tvRok, tvSemestr;

    private LinearLayout historyContainer;
    private View progress;
    private View infoContentRoot;
    private Spinner spinnerStudies;
    private boolean spinnerInitialized = false;

    private LoadInfoTask currentTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        toolbar.setTitle("Dane Studenta");

        // Navigation drawer for the "info" screen
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "info");

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

        MzutSession s = MzutSession.getInstance();
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        tvName.setText(username != null ? username : "Student");
        tvUserId.setText("ID użytkownika: " + (s.getUserId() != null ? s.getUserId() : "-"));

        // Avatar image
        String imageUrl = s.getImageUrl();
        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            new LoadImageTask(imageAvatar).execute(imageUrl);
        } else {
            imageAvatar.setVisibility(View.GONE);
        }

        // 1) Try to load from cache and bind immediately if possible
        loadInfoFromCacheIfAvailable();

        // 2) Setup studies spinner (may already be available from other screens)
        setupStudiesSpinner();

        // 3) If cache is missing or outdated, fetch from network
        if (shouldFetchFromNetwork()) {
            startInfoLoad(false);
        }

        // 4) Refresh icon – forces reload
        if (btnInfoRefresh != null) {
            btnInfoRefresh.setOnClickListener(v -> {
                startInfoLoad(true);
            });
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Only observe the gesture, do not block the event
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    // Network loading

    private void startInfoLoad(boolean forceReload) {
        // forceReload simply ignores TTL at the call site and always fetches
        Toast.makeText(this, "Synchronizuję…", Toast.LENGTH_SHORT).show();

        if (currentTask != null) {
            currentTask.cancel(true);
        }
        currentTask = new LoadInfoTask();
        currentTask.execute();
    }

    private class LoadInfoTask extends AsyncTask<Void, Void, Boolean> {
        StudiesInfoRepository.StudyDetails details;
        List<StudiesInfoRepository.StudyHistoryItem> history;
        Exception error;

        @Override
        protected void onPreExecute() {
            progress.setVisibility(View.VISIBLE);
            infoContentRoot.setAlpha(0.3f);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                StudiesInfoRepository repo = new StudiesInfoRepository();
                details = repo.loadCurrentStudyDetails();
                history = repo.loadStudyHistory();
                return true;
            } catch (Exception e) {
                error = e;
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            progress.setVisibility(View.GONE);
            infoContentRoot.setAlpha(1f);

            if (!ok) {
                Toast.makeText(
                        InfoActivity.this,
                        "Błąd ładowania danych: " + (error != null ? error.getMessage() : ""),
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            if (details != null) {
                bindDetails(details);
            }

            bindHistory(history);
            // Ensure studies spinner is updated, e.g. after the first login
            setupStudiesSpinner();

            // Save to cache after successful refresh
            saveInfoToCache(details, history);
        }
    }

    // Cache logic

    // Returns true if data should be fetched from the network
    // (cache is empty or older than 7 days).
    private boolean shouldFetchFromNetwork() {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        long ts = prefs.getLong(KEY_INFO_TIMESTAMP, 0L);
        if (ts == 0L) {
            return true;
        }

        long now = System.currentTimeMillis();
        return (now - ts) > INFO_CACHE_TTL_MS;
    }

    // Loads data from cache (if available) and binds it to the views.
    private void loadInfoFromCacheIfAvailable() {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        String detailsJson = prefs.getString(KEY_INFO_DETAILS_JSON, null);
        String historyJson = prefs.getString(KEY_INFO_HISTORY_JSON, null);

        if (detailsJson == null && historyJson == null) {
            return; // First start, no cache yet
        }

        // Details
        if (detailsJson != null) {
            try {
                JSONObject obj = new JSONObject(detailsJson);
                setOrHide(tvAlbum,         obj.optString("album", null));
                setOrHide(tvWydzial,       obj.optString("wydzial", null));
                setOrHide(tvKierunek,      obj.optString("kierunek", null));
                setOrHide(tvForma,         obj.optString("forma", null));
                setOrHide(tvPoziom,        obj.optString("poziom", null));
                setOrHide(tvSpecjalnosc,   obj.optString("specjalnosc", null));
                setOrHide(tvSpecjalizacja, obj.optString("specjalizacja", null));
                setOrHide(tvStatus,        obj.optString("status", null));
                setOrHide(tvRok,           obj.optString("rokAkademicki", null));
                setOrHide(tvSemestr,       obj.optString("semestrLabel", null));
            } catch (JSONException e) {
                // On error just ignore cache
            }
        }

        // History
        if (historyJson != null) {
            try {
                JSONArray arr = new JSONArray(historyJson);
                bindHistoryFromCache(arr);
            } catch (JSONException e) {
                // Fallback: show empty history message
                bindHistory(null);
            }
        }
    }

    // Saves the data fetched from the API into cache.
    private void saveInfoToCache(StudiesInfoRepository.StudyDetails d,
                                 List<StudiesInfoRepository.StudyHistoryItem> history) {
        SharedPreferences prefs = getSharedPreferences(PREFS_INFO_CACHE, MODE_PRIVATE);
        JSONObject detailsObj = new JSONObject();
        JSONArray historyArr = new JSONArray();

        try {
            if (d != null) {
                detailsObj.put("album",         d.album != null ? d.album : "");
                detailsObj.put("wydzial",       d.wydzial != null ? d.wydzial : "");
                detailsObj.put("kierunek",      d.kierunek != null ? d.kierunek : "");
                detailsObj.put("forma",         d.forma != null ? d.forma : "");
                detailsObj.put("poziom",        d.poziom != null ? d.poziom : "");
                detailsObj.put("specjalnosc",   d.specjalnosc != null ? d.specjalnosc : "");
                detailsObj.put("specjalizacja", d.specjalizacja != null ? d.specjalizacja : "");
                detailsObj.put("status",        d.status != null ? d.status : "");
                detailsObj.put("rokAkademicki", d.rokAkademicki != null ? d.rokAkademicki : "");
                detailsObj.put("semestrLabel",  d.semestrLabel != null ? d.semestrLabel : "");
            }

            if (history != null) {
                for (StudiesInfoRepository.StudyHistoryItem item : history) {
                    if (item == null) {
                        continue;
                    }
                    JSONObject h = new JSONObject();
                    h.put("label",  item.label  != null ? item.label  : "");
                    h.put("status", item.status != null ? item.status : "");
                    historyArr.put(h);
                }
            }

            prefs.edit()
                    .putString(KEY_INFO_DETAILS_JSON, detailsObj.toString())
                    .putString(KEY_INFO_HISTORY_JSON, historyArr.toString())
                    .putLong(KEY_INFO_TIMESTAMP, System.currentTimeMillis())
                    .apply();

        } catch (JSONException e) {
            // Cache is optional, ignore errors
        }
    }

    // Creates history views based on cached JSON.
    private void bindHistoryFromCache(JSONArray arr) {
        historyContainer.removeAllViews();
        if (arr == null || arr.length() == 0) {
            TextView tv = new TextView(this);
            tv.setText("Brak przebiegu studiów.");
            tv.setTextColor(0xFF9CA3AF);
            tv.setTextSize(12);
            historyContainer.addView(tv);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String label = obj.optString("label", "");
            String status = obj.optString("status", "");

            TextView tv = new TextView(this);
            tv.setText(label + " – " + status);
            tv.setTextColor(0xFFE5E7EB);
            tv.setTextSize(13);
            tv.setPadding(0, 6, 0, 6);
            historyContainer.addView(tv);
        }
    }

    // Studies spinner

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
                    labels
            );
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
                    // Changing the active study triggers a refresh (and cache update)
                    startInfoLoad(true);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
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

    // Binding data from API

    private void bindDetails(StudiesInfoRepository.StudyDetails d) {
        setOrHide(tvAlbum,         d.album);
        setOrHide(tvWydzial,       d.wydzial);
        setOrHide(tvKierunek,      d.kierunek);
        setOrHide(tvForma,         d.forma);
        setOrHide(tvPoziom,        d.poziom);
        setOrHide(tvSpecjalnosc,   d.specjalnosc);
        setOrHide(tvSpecjalizacja, d.specjalizacja);
        setOrHide(tvStatus,        d.status);
        setOrHide(tvRok,           d.rokAkademicki);
        setOrHide(tvSemestr,       d.semestrLabel);
    }

    private void bindHistory(List<StudiesInfoRepository.StudyHistoryItem> history) {
        historyContainer.removeAllViews();
        if (history == null || history.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Brak przebiegu studiów.");
            tv.setTextColor(0xFF9CA3AF);
            tv.setTextSize(12);
            historyContainer.addView(tv);
            return;
        }

        for (StudiesInfoRepository.StudyHistoryItem item : history) {
            TextView tv = new TextView(this);
            tv.setText(item.label + " – " + item.status);
            tv.setTextColor(0xFFE5E7EB);
            tv.setTextSize(13);
            tv.setPadding(0, 6, 0, 6);
            historyContainer.addView(tv);
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

    // Avatar loading

    private static class LoadImageTask extends android.os.AsyncTask<String, Void, android.graphics.Bitmap> {
        private final android.widget.ImageView target;

        LoadImageTask(android.widget.ImageView target) {
            this.target = target;
        }

        @Override
        protected android.graphics.Bitmap doInBackground(String... urls) {
            String url = urls[0];
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoInput(true);
                conn.connect();
                java.io.InputStream is = conn.getInputStream();
                return android.graphics.BitmapFactory.decodeStream(is);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(android.graphics.Bitmap bmp) {
            if (bmp != null && target != null) {
                target.setImageBitmap(bmp);
            } else if (target != null) {
                target.setVisibility(View.GONE);
            }
        }
    }
}
