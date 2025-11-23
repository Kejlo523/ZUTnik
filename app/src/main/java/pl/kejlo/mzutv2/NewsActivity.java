package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;

public class NewsActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private RecyclerView listNews;
    private ProgressBar progress;
    private TextView tvEmpty;

    private final NewsRepository repo = new NewsRepository();
    private final List<NewsItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news);

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "news");

        listNews = findViewById(R.id.listNews);
        progress = findViewById(R.id.newsProgress);
        tvEmpty  = findViewById(R.id.tvNewsEmpty);

        listNews.setLayoutManager(new LinearLayoutManager(this));
        toolbar.setTitle("Aktualności ZUT");

        new LoadNewsTask().execute();
    }

    private class LoadNewsTask extends AsyncTask<Void, Void, Boolean> {
        Exception error;
        List<NewsItem> loaded;

        @Override
        protected void onPreExecute() {
            progress.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                loaded = repo.loadNews();
                return true;
            } catch (Exception e) {
                error = e;
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            progress.setVisibility(View.GONE);

            if (!ok || loaded == null) {
                tvEmpty.setVisibility(View.VISIBLE);
                if (error != null) {
                    Toast.makeText(NewsActivity.this,
                            "Błąd pobierania RSS: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }

            items.clear();
            items.addAll(loaded);

            if (items.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }

            listNews.setAdapter(new NewsAdapter(NewsActivity.this, items));
        }
    }
}
