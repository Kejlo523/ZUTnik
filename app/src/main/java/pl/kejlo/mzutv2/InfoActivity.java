package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Intent;
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

import java.util.ArrayList;
import java.util.List;

public class InfoActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;

    private ImageView imageAvatar;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        toolbar.setTitle("Dane Studenta");

        // 🔹 uniwersalny nav_header – ekran "info"
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "info");

        tvName   = findViewById(R.id.tvInfoName);
        tvUserId = findViewById(R.id.tvInfoUserId);
        imageAvatar = findViewById(R.id.imageInfoAvatar);


        tvAlbum        = findViewById(R.id.tvInfoAlbum);
        tvWydzial      = findViewById(R.id.tvInfoWydzial);
        tvKierunek     = findViewById(R.id.tvInfoKierunek);
        tvForma        = findViewById(R.id.tvInfoForma);
        tvPoziom       = findViewById(R.id.tvInfoPoziom);
        tvSpecjalnosc  = findViewById(R.id.tvInfoSpecjalnosc);
        tvSpecjalizacja= findViewById(R.id.tvInfoSpecjalizacja);
        tvStatus       = findViewById(R.id.tvInfoStatus);
        tvRok          = findViewById(R.id.tvInfoRok);
        tvSemestr      = findViewById(R.id.tvInfoSemestr);

        historyContainer = findViewById(R.id.infoHistoryContainer);
        progress         = findViewById(R.id.infoProgress);
        infoContentRoot  = findViewById(R.id.infoContent);
        spinnerStudies   = findViewById(R.id.spinnerStudies);

        MzutSession s = MzutSession.getInstance();
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        tvName.setText(username != null ? username : "Student");
        tvUserId.setText("ID użytkownika: " + (s.getUserId() != null ? s.getUserId() : "-"));

        // 🔹 avatar
        String imageUrl = s.getImageUrl();
        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            new LoadImageTask(imageAvatar).execute(imageUrl);
        } else {
            imageAvatar.setVisibility(View.GONE);
        }

        new LoadInfoTask().execute();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // tylko podglądamy gest, NIE blokujemy eventu
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
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
                Toast.makeText(InfoActivity.this,
                        "Błąd ładowania danych: " + (error != null ? error.getMessage() : ""),
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (details != null) {
                bindDetails(details);
            }

            bindHistory(history);
            setupStudiesSpinner();
        }
    }


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
            labels.add(st.toString()); // tu już jest ładny label z GradesRepository
        }

        if (!spinnerInitialized) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    R.layout.spinner_item_dark,              // biały tekst
                    labels
            );
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            spinnerStudies.setAdapter(adapter);
            spinnerInitialized = true;

            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) activeIndex = 0;
            spinnerStudies.setSelection(activeIndex);

            spinnerStudies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    MzutSession s = MzutSession.getInstance();
                    if (position == s.getActiveStudyIndex()) {
                        return;
                    }
                    s.setActiveStudyIndex(position);
                    new LoadInfoTask().execute();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
        } else {
            int activeIndex = session.getActiveStudyIndex();
            if (activeIndex < 0 || activeIndex >= labels.size()) activeIndex = 0;
            spinnerStudies.setSelection(activeIndex);
        }
    }

    private void bindDetails(StudiesInfoRepository.StudyDetails d) {
        setOrHide(tvAlbum,        d.album);
        setOrHide(tvWydzial,      d.wydzial);
        setOrHide(tvKierunek,     d.kierunek);      // 🔹 tu jest nazwa kierunku na ekranie
        setOrHide(tvForma,        d.forma);
        setOrHide(tvPoziom,       d.poziom);
        setOrHide(tvSpecjalnosc,  d.specjalnosc);
        setOrHide(tvSpecjalizacja,d.specjalizacja);
        setOrHide(tvStatus,       d.status);
        setOrHide(tvRok,          d.rokAkademicki);
        setOrHide(tvSemestr,      d.semestrLabel);
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
