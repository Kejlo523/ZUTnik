package pl.kejlo.mzutv2;

import android.annotation.SuppressLint;
import android.content.Intent;

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



    /**
     * Logical screens that can be opened from the navigation drawer.
     * Each enum constant has a string id used internally in the app.
     */
    public enum Screen {
        HOME("home"),
        INFO("info"),
        PLAN("plan"),
        GRADES("grades"),
        NEWS("news"),
        USEFUL("useful"),
        ABOUT("about");

        private final String id;

        Screen(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static Screen fromId(String id) {
            if (id == null) {
                return null;
            }
            for (Screen screen : values()) {
                if (screen.id.equals(id)) {
                    return screen;
                }
            }
            return null;
        }
    }

    // region Public API

    /**
     * Preferred overload: uses enum Screen instead of raw string.
     */
    public static void setupNavigation(
            AppCompatActivity activity,
            DrawerLayout drawerLayout,
            NavigationView navigationView,
            Toolbar toolbar,
            Screen currentScreen
    ) {
        String currentScreenId = currentScreen != null ? currentScreen.getId() : null;
        setupNavigation(activity, drawerLayout, navigationView, toolbar, currentScreenId);
    }

    /**
     * Legacy overload.
     * Sets up navigation drawer for the given screen.
     * currentScreen: "home", "info", "plan", "grades", "news", "about", "useful"
     */
    @SuppressLint("StringFormatInvalid")
    public static void setupNavigation(
            AppCompatActivity activity,
            DrawerLayout drawerLayout,
            NavigationView navigationView,
            Toolbar toolbar,
            String currentScreen
    ) {
        // Hamburger icon
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity,
                drawerLayout,
                toolbar,
                R.string.app_name,
                R.string.app_name
        );
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // IDs from nav_header.xml (no getHeaderView)
        TextView navHeaderUser = navigationView.findViewById(R.id.navHeaderUser);
        TextView navLinkHome = navigationView.findViewById(R.id.navLinkHome);
        TextView navLinkPlan = navigationView.findViewById(R.id.navLinkPlan);
        TextView navLinkGrades = navigationView.findViewById(R.id.navLinkGrades);
        TextView navLinkInfo = navigationView.findViewById(R.id.navLinkInfo);
        TextView navLinkNews = navigationView.findViewById(R.id.navLinkNews);
        TextView navLinkUseful = navigationView.findViewById(R.id.navLinkUsefull);
        TextView navLinkAbout = navigationView.findViewById(R.id.navLinkAbout);
        View navSettings = navigationView.findViewById(R.id.navSettings);
        TextView navAppVersion = navigationView.findViewById(R.id.navAppVersion);
        TextView navLogout = navigationView.findViewById(R.id.navLogout);

        if (navHeaderUser == null || navLogout == null) {
            // Something is wrong with the XML, better bail out
            return;
        }

        if (navAppVersion != null) {
            String versionName = BuildConfig.VERSION_NAME; // z Gradle (versionName = "1.0.2")
            navAppVersion.setText(
                    activity.getString(R.string.app_version, versionName)
            );
        }

        // User name in header
        MzutSession s = MzutSession.getInstance(activity);
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            // Default fallback username
            username = activity.getString(R.string.nav_header_default_username);
        }
        navHeaderUser.setText(username);

        View.OnClickListener listener = v -> {
            int id = v.getId();

            Screen targetScreen = null;
            Class<?> targetActivity = null;

            if (id == R.id.navLinkHome) {
                targetScreen = Screen.HOME;
                targetActivity = HomeActivity.class;
            } else if (id == R.id.navLinkInfo) {
                targetScreen = Screen.INFO;
                targetActivity = InfoActivity.class;
            } else if (id == R.id.navLinkPlan) {
                targetScreen = Screen.PLAN;
                targetActivity = PlanActivity.class;
            } else if (id == R.id.navLinkGrades) {
                targetScreen = Screen.GRADES;
                targetActivity = GradesActivity.class;
            } else if (id == R.id.navLinkNews) {
                targetScreen = Screen.NEWS;
                targetActivity = NewsActivity.class;
            } else if (id == R.id.navLinkUsefull) {
                targetScreen = Screen.USEFUL;
                targetActivity = UsefulLinksActivity.class;
            } else if (id == R.id.navLinkAbout) {
                targetScreen = Screen.ABOUT;
                targetActivity = AboutActivity.class;
            } else if (id == R.id.navSettings) {
                // ZĘBATKA -> USTAWIENIA
                Intent intent = new Intent(activity, SettingsActivity.class);
                activity.startActivity(intent);
                drawerLayout.closeDrawers();
                return;
            } else if (id == R.id.navLogout) {
                showLogoutConfirmation(activity, drawerLayout);
                return;
            }

            if (targetActivity == null || targetScreen == null) {
                return;
            }

            // If the same screen is selected, just close the drawer
            if (targetScreen.getId().equals(currentScreen)) {
                drawerLayout.closeDrawers();
                return;
            }

            Intent intent = new Intent(activity, targetActivity);

            // Home as root – CLEAR_TOP/SINGLE_TOP
            if (targetScreen == Screen.HOME) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }

            activity.startActivity(intent);
            drawerLayout.closeDrawers();

            // If we are not on Home, finish the current activity so the stack is [Home, NewScreen]
            if (!Screen.HOME.getId().equals(currentScreen)) {
                activity.finish();
            }
        };

        navLinkHome.setOnClickListener(listener);
        navLinkPlan.setOnClickListener(listener);
        navLinkGrades.setOnClickListener(listener);
        navLinkInfo.setOnClickListener(listener);
        navLinkNews.setOnClickListener(listener);
        navLinkUseful.setOnClickListener(listener);
        navLinkAbout.setOnClickListener(listener);
        navSettings.setOnClickListener(listener);
        navLogout.setOnClickListener(listener);
        
        // Ensure drawer is unlocked
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        
        // Boost the swipe edge size (EXCEPT for PlanActivity where it conflicts with ViewPager)
        if (currentScreen != null && !currentScreen.equals(Screen.PLAN.getId())) {
            boostDrawerLeftEdgeSize(drawerLayout);
        }
    }

    private static void boostDrawerLeftEdgeSize(DrawerLayout drawerLayout) {
        if (drawerLayout == null) return;
        
        try {
            // Robustly find the ViewDragHelper field(s) in DrawerLayout
            java.lang.reflect.Field[] fields = DrawerLayout.class.getDeclaredFields();
            
            for (java.lang.reflect.Field field : fields) {
                // We are looking for fields of type ViewDragHelper
                if (field.getType().getName().contains("ViewDragHelper")) {
                    field.setAccessible(true);
                    Object draggerObj = field.get(drawerLayout);
                    
                    if (draggerObj != null) {
                        // Now find the mEdgeSize field in this ViewDragHelper instance
                        java.lang.reflect.Field[] draggerFields = draggerObj.getClass().getDeclaredFields();
                        
                        for (java.lang.reflect.Field dField : draggerFields) {
                            if (dField.getName().equals("mEdgeSize") || dField.getName().equals("mDefaultEdgeSize")) {
                                dField.setAccessible(true);
                                
                                // Set edge size to full screen width
                                android.util.DisplayMetrics metrics = drawerLayout.getResources().getDisplayMetrics();
                                dField.setInt(draggerObj, metrics.widthPixels);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fail silently or log if needed
        }
    }

    // endregion



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

        Toast.makeText(activity, R.string.logout_success_message, Toast.LENGTH_SHORT).show();

        Intent i = new Intent(activity, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        activity.finish();
    }

    private static void showLogoutConfirmation(AppCompatActivity activity,
                                               DrawerLayout drawerLayout) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setPositiveButton(R.string.logout_confirm_positive, (dialog, which) -> {
                    doLogout(activity);
                    drawerLayout.closeDrawers();
                })
                .setNegativeButton(R.string.logout_confirm_negative, (dialog, which) -> {
                    drawerLayout.closeDrawers();
                    dialog.dismiss();
                })
                .show();
    }
}
