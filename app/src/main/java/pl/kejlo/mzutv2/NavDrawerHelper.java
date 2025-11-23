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

import org.w3c.dom.Text;

public class NavDrawerHelper {

    // progi dla gestów
    private static final int SWIPE_VELOCITY_THRESHOLD = 800;
    private static final int SWIPE_DISTANCE_THRESHOLD = 120;

    /**
     * @param activity       aktualna Activity (HomeActivity, InfoActivity, ...)
     * @param drawerLayout   DrawerLayout z layoutu
     * @param navigationView NavigationView z headerem nav_header
     * @param toolbar        Toolbar (z hamburgerem)
     * @param currentScreen  "home", "info", "plan", "grades", "news" – żeby wiedzieć, gdzie jesteśmy
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

        // 🔥 WAŻNE: bez getHeaderView(0)!
        TextView navHeaderUser = navigationView.findViewById(R.id.navHeaderUser);
        TextView navLinkHome   = navigationView.findViewById(R.id.navLinkHome);
        TextView navLinkPlan   = navigationView.findViewById(R.id.navLinkPlan);
        TextView navLinkGrades = navigationView.findViewById(R.id.navLinkGrades);
        TextView navLinkInfo   = navigationView.findViewById(R.id.navLinkInfo);
        TextView navLinkNews   = navigationView.findViewById(R.id.navLinkNews);
        TextView navLinkAbout  = navigationView.findViewById(R.id.navLinkAbout);
        TextView navLogout     = navigationView.findViewById(R.id.navLogout);

        // (opcjonalnie zabezpieczenie)
        if (navHeaderUser == null || navLogout == null) {
            // coś jest nie tak z XML (include / id), inaczej nie będzie null
            return;
        }

        // ustaw nazwę użytkownika
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

            if (id == R.id.navLinkHome) {
                if ("home".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, HomeActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLinkInfo) {
                if ("info".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, InfoActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLinkPlan) {
                if ("plan".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, PlanActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLinkGrades) {
                if ("grades".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, GradesActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLinkNews) {
                if ("news".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, NewsActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLinkAbout) {
                if ("about".equals(currentScreen)) {
                    drawerLayout.closeDrawers();
                } else {
                    activity.startActivity(new Intent(activity, AboutActivity.class));
                    drawerLayout.closeDrawers();
                }
            } else if (id == R.id.navLogout) {
                doLogout(activity);
                drawerLayout.closeDrawers();
            }
        };

        navLinkHome.setOnClickListener(listener);
        navLinkPlan.setOnClickListener(listener);
        navLinkGrades.setOnClickListener(listener);
        navLinkInfo.setOnClickListener(listener);
        navLinkNews.setOnClickListener(listener);
        navLinkAbout.setOnClickListener(listener);
        navLogout.setOnClickListener(listener);

        attachSwipeToOpenClose(activity, drawerLayout);
    }

    private static void attachSwipeToOpenClose(AppCompatActivity activity, DrawerLayout drawerLayout) {
        // zakładamy, że child 0 to "główna" zawartość (LinearLayout z toolbar + content)
        View contentView = drawerLayout.getChildAt(0);
        if (contentView == null) return;

        GestureDetector gestureDetector = new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;

                        float diffX = e2.getX() - e1.getX();
                        float diffY = e2.getY() - e1.getY();

                        // interesuje nas prawie poziomy ruch
                        if (Math.abs(diffX) > Math.abs(diffY)
                                && Math.abs(diffX) > SWIPE_DISTANCE_THRESHOLD
                                && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                            if (diffX > 0) {
                                // swipe w prawo -> otwórz drawer
                                if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                    drawerLayout.openDrawer(GravityCompat.START);
                                }
                            } else {
                                // swipe w lewo -> zamknij drawer
                                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                    drawerLayout.closeDrawer(GravityCompat.START);
                                }
                            }
                            return true;
                        }
                        return false;
                    }
                });

        contentView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            // zwracamy false, żeby normalne kliki / scroll dalej działały
            return false;
        });
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
