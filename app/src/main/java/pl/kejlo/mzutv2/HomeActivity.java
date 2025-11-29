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
import android.content.Context;

import com.google.android.material.navigation.NavigationView;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

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

        // Initialize session from SharedPreferences
        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        // If there is no valid session, go back to the login screen
        if (session.getAuthKey() == null || session.getUserId() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            // Clear the back stack so the user cannot return to an empty Home screen
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        // Title from resources
        toolbar.setTitle(R.string.home_title);

        // Navigation drawer – home screen (enum version)
        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.HOME
        );

        // Hero / sections
        textWelcome = findViewById(R.id.textWelcome);
        textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero = findViewById(R.id.homeHero);
        homeShortcuts = findViewById(R.id.homeShortcuts);
        homeSection = findViewById(R.id.homeSection);

        // Main buttons
        btnHeroPlan = findViewById(R.id.btnHeroPlan);
        btnHeroGrades = findViewById(R.id.btnHeroGrades);

        // Shortcut tiles
        tilePlan = findViewById(R.id.tilePlan);
        tileGrades = findViewById(R.id.tileGrades);
        tileInfo = findViewById(R.id.tileInfo);
        tileNews = findViewById(R.id.tileNews);

        setupWelcomeText();
        setupClicks();
        runIntroAnimations();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Only observe the gesture, do not block the event
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    private void setupWelcomeText() {
        // Use the version with context to ensure the session is loaded
        MzutSession s = MzutSession.getInstance(this);
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            // Default username from resources (the same as in NavDrawerHelper)
            username = getString(R.string.nav_header_default_username);
        }

        // "Witaj, %1$s"
        textWelcome.setText(getString(R.string.home_welcome_message, username));

        // Subtitle from resources
        textWelcomeSub.setText(R.string.home_welcome_subtitle);
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
                        R.string.home_grades_not_available,
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
                        R.string.home_news_not_available,
                        Toast.LENGTH_SHORT
                ).show();
            }
        };

        // Hero buttons
        btnHeroPlan.setOnClickListener(openPlan);
        btnHeroGrades.setOnClickListener(openGrades);

        // Tiles
        tilePlan.setOnClickListener(openPlan);
        tileGrades.setOnClickListener(openGrades);
        tileInfo.setOnClickListener(openInfo);
        tileNews.setOnClickListener(openNews);

        // Long press tips (optional)
        tilePlan.setOnLongClickListener(v -> {
            Toast.makeText(
                    this,
                    R.string.home_tip_plan,
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });

        tileGrades.setOnLongClickListener(v -> {
            Toast.makeText(
                    this,
                    R.string.home_tip_grades,
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });

        tileInfo.setOnLongClickListener(v -> {
            Toast.makeText(
                    this,
                    R.string.home_tip_info,
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });

        tileNews.setOnLongClickListener(v -> {
            Toast.makeText(
                    this,
                    R.string.home_tip_news,
                    Toast.LENGTH_LONG
            ).show();
            return true;
        });
    }

    private void runIntroAnimations() {
        animateInFromBottom(homeHero, 0);
        animateInFromBottom(homeShortcuts, 60);
        animateInFromBottom(homeSection, 120);
    }

    private void animateInFromBottom(View v, long delayMs) {
        if (v == null) {
            return;
        }
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
