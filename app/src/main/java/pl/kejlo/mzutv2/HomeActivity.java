package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.navigation.NavigationView;

public class HomeActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private TextView textWelcome;
    private TextView textWelcomeSub;

    private LinearLayout homeHero;
    private LinearLayout homeShortcuts;
    private LinearLayout homeSection;

    private LinearLayout tilePlan, tileGrades, tileInfo, tileNews;
    private TextView btnHeroPlan, btnHeroGrades;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1️⃣ Zainicjalizuj sesję z SharedPreferences
        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        // 2️⃣ Jeśli nie mamy ważnej sesji → wróć do ekranu logowania
        if (session.getAuthKey() == null || session.getUserId() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            // czyścimy stack, żeby nie można było wrócić "wstecz" do pustego Home
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        toolbar.setTitle("Pulpit główny");

        // NavDrawer – ekran startowy
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "home");

        // HERO / sekcje
        textWelcome    = findViewById(R.id.textWelcome);
        textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero       = findViewById(R.id.homeHero);
        homeShortcuts  = findViewById(R.id.homeShortcuts);
        homeSection    = findViewById(R.id.homeSection);

        // główne przyciski
        btnHeroPlan   = findViewById(R.id.btnHeroPlan);
        btnHeroGrades = findViewById(R.id.btnHeroGrades);

        // kafelki skrótów
        tilePlan   = findViewById(R.id.tilePlan);
        tileGrades = findViewById(R.id.tileGrades);
        tileInfo   = findViewById(R.id.tileInfo);
        tileNews   = findViewById(R.id.tileNews);

        setupWelcomeText();
        setupClicks();
        runIntroAnimations();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // tylko podglądamy gest, NIE blokujemy eventu
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    private void setupWelcomeText() {
        // używamy wersji z kontekstem, żeby mieć pewność, że sesja jest wczytana
        MzutSession s = MzutSession.getInstance(this);
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

    private void setupClicks() {
        View.OnClickListener openPlan = v ->
                startActivity(new Intent(HomeActivity.this, PlanActivity.class));

        View.OnClickListener openGrades = v -> {
            try {
                startActivity(new Intent(HomeActivity.this, GradesActivity.class));
            } catch (Exception e) {
                Toast.makeText(
                        HomeActivity.this,
                        "Ekran ocen nie jest jeszcze podpięty 🤔",
                        Toast.LENGTH_SHORT
                ).show();
            }
        };

        View.OnClickListener openInfo = v ->
                startActivity(new Intent(HomeActivity.this, InfoActivity.class));

        View.OnClickListener openNews = v -> {
            try {
                startActivity(new Intent(HomeActivity.this, NewsActivity.class));
            } catch (Exception e) {
                Toast.makeText(
                        HomeActivity.this,
                        "Ekran aktualności nie jest jeszcze podpięty 🤔",
                        Toast.LENGTH_SHORT
                ).show();
            }
        };

        // hero buttons
        btnHeroPlan.setOnClickListener(openPlan);
        btnHeroGrades.setOnClickListener(openGrades);

        // tiles
        tilePlan.setOnClickListener(openPlan);
        tileGrades.setOnClickListener(openGrades);
        tileInfo.setOnClickListener(openInfo);
        tileNews.setOnClickListener(openNews);

        // długie tapnięcie – krótkie tipy (opcjonalnie, zostawiłem)
        tilePlan.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Plan: widok dnia, tygodnia, miesiąca + filtr przedmiotów.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileGrades.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Oceny: średnia ECTS, lista zaliczeń, egzaminy.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileInfo.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Informacje: kierunek, semestr, status studenta.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        tileNews.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    "Aktualności: komunikaty z RSS ZUT (studenci).",
                    Toast.LENGTH_LONG).show();
            return true;
        });
    }

    private void runIntroAnimations() {
        animateInFromBottom(homeHero, 0);
        animateInFromBottom(homeShortcuts, 60);
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
