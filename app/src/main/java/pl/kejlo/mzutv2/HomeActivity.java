package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.GridLayout; // ⬅️ DODANY IMPORT

import com.google.android.material.navigation.NavigationView;

public class HomeActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private TextView textWelcome;
    private TextView textWelcomeSub;

    private LinearLayout homeHero;
    private GridLayout   homeGrid;    // ⬅️ ZMIANA: GridLayout zamiast LinearLayout
    private LinearLayout homeSection;

    private LinearLayout tilePlan, tileGrades, tileInfo, tileNews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        toolbar.setTitle("Pulpit Główny");

        // NavDrawer – ekran startowy
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "home");

        // HERO / sekcje
        textWelcome    = findViewById(R.id.textWelcome);
        textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero       = findViewById(R.id.homeHero);
        homeGrid       = findViewById(R.id.homeGrid);   // teraz typ się zgadza
        homeSection    = findViewById(R.id.homeSection);

        // Kafelki
        tilePlan   = findViewById(R.id.tilePlan);
        tileGrades = findViewById(R.id.tileGrades);
        tileInfo   = findViewById(R.id.tileInfo);
        tileNews   = findViewById(R.id.tileNews);

        setupWelcomeText();
        setupTilesClicks();
        setupTilesHints();
        runIntroAnimations();
    }

    private void setupWelcomeText() {
        MzutSession s = MzutSession.getInstance();
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            username = "Student";
        }
        textWelcome.setText("Witaj, " + username + " 👋");

        textWelcomeSub.setText(
                "Szybki dostęp do planu, ocen, informacji o studiach i aktualności z ZUT – w jednym miejscu."
        );
    }

    private void setupTilesClicks() {
        View.OnClickListener listener = v -> {
            int id = v.getId();

            if (id == R.id.tilePlan) {
                startActivity(new Intent(HomeActivity.this, PlanActivity.class));
                return;
            }

            if (id == R.id.tileGrades) {
                try {
                    Intent i = new Intent(HomeActivity.this, GradesActivity.class);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(
                            HomeActivity.this,
                            "Ekran ocen nie jest jeszcze podpięty 🤔",
                            Toast.LENGTH_SHORT
                    ).show();
                }
                return;
            }

            if (id == R.id.tileInfo) {
                startActivity(new Intent(HomeActivity.this, InfoActivity.class));
                return;
            }

            if (id == R.id.tileNews) {
                try {
                    Intent i = new Intent(HomeActivity.this, NewsActivity.class);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(
                            HomeActivity.this,
                            "Ekran aktualności nie jest jeszcze podpięty 🤔",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
        };

        tilePlan.setOnClickListener(listener);
        tileGrades.setOnClickListener(listener);
        tileInfo.setOnClickListener(listener);
        tileNews.setOnClickListener(listener);
    }

    private void setupTilesHints() {
        tilePlan.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Plan zajęć: widok dnia, tygodnia, miesiąca + filtr przedmiotów.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileGrades.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Oceny: zestawienie ocen, ECTS, zaliczenia, egzaminy.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileInfo.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Informacje: kierunek, semestr, status studenta i inne dane z mZUT.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileNews.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Aktualności: komunikaty i wydarzenia z RSS ZUT (studenci).",
                    Toast.LENGTH_LONG).show();
            return true;
        });
    }

    private void runIntroAnimations() {
        animateInFromBottom(homeHero, 0);
        animateInFromBottom(homeGrid, 60);
        animateInFromBottom(homeSection, 120);
    }

    private void animateInFromBottom(View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(16f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(220)
                .start();
    }
}
