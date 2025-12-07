package pl.kejlo.mzutv2;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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
    private LinearLayout drawerContentRoot;

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
        EdgeToEdge.enable(this);

        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        if (session.getAuthKey() == null || session.getUserId() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_home);
        
        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setTitle(R.string.home_title);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.HOME
        );

        textWelcome = findViewById(R.id.textWelcome);
        textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero = findViewById(R.id.homeHero);
        homeShortcuts = findViewById(R.id.homeShortcuts);
        homeSection = findViewById(R.id.homeSection);

        btnHeroPlan = findViewById(R.id.btnHeroPlan);
        btnHeroGrades = findViewById(R.id.btnHeroGrades);

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
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    private void setupWelcomeText() {
        MzutSession s = MzutSession.getInstance(this);
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            username = getString(R.string.nav_header_default_username);
        }

        textWelcome.setText(getString(R.string.home_welcome_message, username));
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

        btnHeroPlan.setOnClickListener(openPlan);
        btnHeroGrades.setOnClickListener(openGrades);

        tilePlan.setOnClickListener(openPlan);
        tileGrades.setOnClickListener(openGrades);
        tileInfo.setOnClickListener(openInfo);
        tileNews.setOnClickListener(openNews);

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
        homeHero.setAlpha(0f);
        tilePlan.setAlpha(0f);
        tileGrades.setAlpha(0f);
        tileInfo.setAlpha(0f);
        tileNews.setAlpha(0f);
        homeSection.setAlpha(0f);

        animateIn(homeHero, 100);
        animateIn(tilePlan, 200);
        animateIn(tileGrades, 250);
        animateIn(tileInfo, 300);
        animateIn(tileNews, 350);
        animateIn(homeSection, 450);
    }

    private void animateIn(View v, long delayMs) {
        if (v == null) {
            return;
        }
        v.setTranslationY(100f);
        v.setScaleX(0.95f);
        v.setScaleY(0.95f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(400)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }
}