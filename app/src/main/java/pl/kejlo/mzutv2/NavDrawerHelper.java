package pl.kejlo.mzutv2;

import android.content.Intent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

public class NavDrawerHelper {

    // trochę łagodniejsze progi dla gestu
    private static final int SWIPE_VELOCITY_THRESHOLD = 400;
    private static final int SWIPE_DISTANCE_THRESHOLD = 80;

    /**
     * @param activity       aktualna Activity (HomeActivity, InfoActivity, ...)
     * @param drawerLayout   DrawerLayout z layoutu
     * @param navigationView NavigationView z headerem nav_header
     * @param toolbar        Toolbar (z hamburgerem)
     * @param currentScreen  "home", "info", "plan", "grades", "news", "about"
     */
    public static void setupNavigation(
            AppCompatActivity activity,
            DrawerLayout drawerLayout,
            NavigationView navigationView,
            Toolbar toolbar,
            String currentScreen
    ) {
        // hamburger
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity,
                drawerLayout,
                toolbar,
                R.string.app_name,
                R.string.app_name
        );
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // ID-ki z nav_header.xml (bez getHeaderView)
        TextView navHeaderUser = navigationView.findViewById(R.id.navHeaderUser);
        TextView navLinkHome   = navigationView.findViewById(R.id.navLinkHome);
        TextView navLinkPlan   = navigationView.findViewById(R.id.navLinkPlan);
        TextView navLinkGrades = navigationView.findViewById(R.id.navLinkGrades);
        TextView navLinkInfo   = navigationView.findViewById(R.id.navLinkInfo);
        TextView navLinkNews   = navigationView.findViewById(R.id.navLinkNews);
        TextView navLinkAbout  = navigationView.findViewById(R.id.navLinkAbout);
        TextView navLogout     = navigationView.findViewById(R.id.navLogout);

        if (navHeaderUser == null || navLogout == null) {
            // coś jest nie tak z XML-em – wolimy się wycofać
            return;
        }

        // nazwa użytkownika
        MzutSession s = MzutSession.getInstance();
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            username = "Student";
        }
        navHeaderUser.setText(username);

        View.OnClickListener listener = v -> {
            int id = v.getId();

            String targetScreen = null;
            Class<?> targetActivity = null;

            if (id == R.id.navLinkHome) {
                targetScreen = "home";
                targetActivity = HomeActivity.class;
            } else if (id == R.id.navLinkInfo) {
                targetScreen = "info";
                targetActivity = InfoActivity.class;
            } else if (id == R.id.navLinkPlan) {
                targetScreen = "plan";
                targetActivity = PlanActivity.class;
            } else if (id == R.id.navLinkGrades) {
                targetScreen = "grades";
                targetActivity = GradesActivity.class;
            } else if (id == R.id.navLinkNews) {
                targetScreen = "news";
                targetActivity = NewsActivity.class;
            } else if (id == R.id.navLinkAbout) {
                targetScreen = "about";
                targetActivity = AboutActivity.class;
            } else if (id == R.id.navLogout) {
                doLogout(activity);
                drawerLayout.closeDrawers();
                return;
            }

            if (targetActivity == null || targetScreen == null) {
                return;
            }

            // jeśli klikamy w ten sam ekran, tylko zamykamy drawer
            if (targetScreen.equals(currentScreen)) {
                drawerLayout.closeDrawers();
                return;
            }

            Intent intent = new Intent(activity, targetActivity);

            // Home jako root – CLEAR_TOP/SINGLE_TOP
            if ("home".equals(targetScreen)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }

            activity.startActivity(intent);
            drawerLayout.closeDrawers();

            // jeśli jesteśmy na czymś innym niż Home, zamykamy aktywność,
            // żeby stack był [Home, NowyEkran] i BACK zawsze wracał do Home
            if (!"home".equals(currentScreen)) {
                activity.finish();
            }
        };

        navLinkHome.setOnClickListener(listener);
        navLinkPlan.setOnClickListener(listener);
        navLinkGrades.setOnClickListener(listener);
        navLinkInfo.setOnClickListener(listener);
        navLinkNews.setOnClickListener(listener);
        navLinkAbout.setOnClickListener(listener);
        navLogout.setOnClickListener(listener);
    }

    /**
     * Wołane z dispatchTouchEvent() w Activity.
     * Zwraca true, jeśli gest został obsłużony (otwarcie/zamknięcie szuflady).
     */
    public static void handleDrawerSwipe(AppCompatActivity activity,
                                         DrawerLayout drawerLayout,
                                         MotionEvent event) {
        if (drawerLayout == null) return;

        Object tag = drawerLayout.getTag(R.id.nav_swipe_detector);
        GestureDetector detector;

        if (tag instanceof GestureDetector) {
            detector = (GestureDetector) tag;
        } else {
            detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {

                @Override
                public boolean onDown(MotionEvent e) {
                    // musi być true, żeby onFling w ogóle działał
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2,
                                       float velocityX, float velocityY) {
                    if (e1 == null || e2 == null) return false;

                    float diffX = e2.getX() - e1.getX();
                    float diffY = e2.getY() - e1.getY();

                    if (Math.abs(diffX) > Math.abs(diffY)
                            && Math.abs(diffX) > SWIPE_DISTANCE_THRESHOLD
                            && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                        if (diffX > 0) {
                            // swipe w prawo -> otwórz
                            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                drawerLayout.openDrawer(GravityCompat.START);
                            }
                        } else {
                            // swipe w lewo -> zamknij
                            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                drawerLayout.closeDrawer(GravityCompat.START);
                            }
                        }
                        // tu może być true, ale i tak NIE BLOKUJEMY dispatchTouchEvent
                        return true;
                    }
                    return false;
                }
            });

            drawerLayout.setTag(R.id.nav_swipe_detector, detector);
        }

        // Po prostu przekazujemy event – bez zwracania wyniku dalej.
        detector.onTouchEvent(event);
    }

    private static void doLogout(AppCompatActivity activity) {
        MzutSession s = MzutSession.getInstance();
        s.setUserId(null);
        s.setUsername(null);
        s.setAuthKey(null);
        s.setImageUrl(null);

        activity.getSharedPreferences("mzut_prefs", AppCompatActivity.MODE_PRIVATE)
                .edit()
                .remove("user_id")
                .remove("auth_key")
                .remove("username")
                .remove("image_url")
                .apply();

        Toast.makeText(activity, "Wylogowano", Toast.LENGTH_SHORT).show();

        Intent i = new Intent(activity, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        activity.finish();
    }
}
