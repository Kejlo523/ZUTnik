package pl.kejlo.mzutv2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.text.format.DateUtils;

import android.view.View;
import android.view.LayoutInflater;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

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
        ATTENDANCE("attendance"),
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
            Screen currentScreen) {
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
            String currentScreen) {
        // Force drawer status bar scrim to theme background (prevents default purple).
        drawerLayout.setStatusBarBackgroundColor(ThemeManager.resolveColor(activity, R.attr.mzBg));

        // Hamburger icon
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity,
                drawerLayout,
                toolbar,
                R.string.app_name,
                R.string.app_name);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // IDs from nav_header.xml (no getHeaderView)
        TextView navHeaderUser = navigationView.findViewById(R.id.navHeaderUser);
        TextView navLinkHome = navigationView.findViewById(R.id.navLinkHome);
        TextView navLinkPlan = navigationView.findViewById(R.id.navLinkPlan);
        TextView navLinkGrades = navigationView.findViewById(R.id.navLinkGrades);
        TextView navLinkAttendance = navigationView.findViewById(R.id.navLinkAttendance);
        TextView navLinkInfo = navigationView.findViewById(R.id.navLinkInfo);
        TextView navLinkNews = navigationView.findViewById(R.id.navLinkNews);
        TextView navLinkUseful = navigationView.findViewById(R.id.navLinkUsefull);
        TextView navLinkAbout = navigationView.findViewById(R.id.navLinkAbout);
        View navSettings = navigationView.findViewById(R.id.navSettings);
        View navWatch = navigationView.findViewById(R.id.navWatch);
        TextView navAppVersion = navigationView.findViewById(R.id.navAppVersion);
        TextView navLogout = navigationView.findViewById(R.id.navLogout);
        View navHeaderRoot = navigationView.findViewById(R.id.navHeaderRoot);

        if (navHeaderUser == null || navLogout == null) {
            // Something is wrong with the XML, better bail out
            return;
        }

        if (navAppVersion != null) {
            String versionName = BuildConfig.VERSION_NAME; // z Gradle (versionName = "1.0.2")
            navAppVersion.setText(
                    activity.getString(R.string.app_version, versionName));
        }

        if (navHeaderRoot != null) {
            final int baseLeft = navHeaderRoot.getPaddingLeft();
            final int baseTop = navHeaderRoot.getPaddingTop();
            final int baseRight = navHeaderRoot.getPaddingRight();
            final int baseBottom = navHeaderRoot.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(navHeaderRoot, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(baseLeft, baseTop, baseRight, baseBottom + bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(navHeaderRoot);
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
            } else if (id == R.id.navLinkAttendance) {
                targetScreen = Screen.ATTENDANCE;
                targetActivity = AttendanceActivity.class;
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
                // Settings
                Intent intent = new Intent(activity, SettingsActivity.class);
                activity.startActivity(intent);
                drawerLayout.closeDrawers();
                return;
            } else if (id == R.id.navWatch) {
                handleWatchClick(activity, drawerLayout);
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

            // If we are not on Home, finish the current activity so the stack is [Home,
            // NewScreen]
            if (!Screen.HOME.getId().equals(currentScreen)) {
                activity.finish();
            }
        };

        navLinkHome.setOnClickListener(listener);
        navLinkPlan.setOnClickListener(listener);
        navLinkGrades.setOnClickListener(listener);
        navLinkAttendance.setOnClickListener(listener);
        navLinkInfo.setOnClickListener(listener);
        navLinkNews.setOnClickListener(listener);
        navLinkUseful.setOnClickListener(listener);
        navLinkAbout.setOnClickListener(listener);
        navSettings.setOnClickListener(listener);
        if (navWatch != null) {
            navWatch.setOnClickListener(listener);
        }
        navLogout.setOnClickListener(listener);

        // Ensure drawer is unlocked
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        if (currentScreen != null && !currentScreen.equals(Screen.PLAN.getId())) {
            enableFullScreenSwipe(activity, drawerLayout);
        }
    }

    private static void enableFullScreenSwipe(AppCompatActivity activity, DrawerLayout drawerLayout) {
        android.view.Window.Callback originalCallback = activity.getWindow().getCallback();
        if (originalCallback instanceof SwipeWindowCallback) {
            return; // Already wrapped
        }
        activity.getWindow().setCallback(new SwipeWindowCallback(originalCallback, activity, drawerLayout));
    }

    private static class SwipeWindowCallback implements android.view.Window.Callback {
        private final android.view.Window.Callback wrapped;
        private final android.view.GestureDetector detector;
        private final DrawerLayout drawerLayout;

        public SwipeWindowCallback(android.view.Window.Callback wrapped, android.content.Context context,
                DrawerLayout drawerLayout) {
            this.wrapped = wrapped;
            this.drawerLayout = drawerLayout;
            this.detector = new android.view.GestureDetector(context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                float velocityX, float velocityY) {
                            if (e1 == null || e2 == null)
                                return false;
                            float deltaX = e2.getX() - e1.getX();
                            float deltaY = e2.getY() - e1.getY();
                            if (deltaX > 50 && velocityX > 100 && Math.abs(deltaX) > Math.abs(deltaY)) {
                                if (drawerLayout.getDrawerLockMode(
                                        androidx.core.view.GravityCompat.START) == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                                    return false;
                                }
                                if (!drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                                    drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                                    return true;
                                }
                            }
                            return false;
                        }
                    });
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            detector.onTouchEvent(event);
            return wrapped.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            return wrapped.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {
            return wrapped.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(android.view.MotionEvent event) {
            return wrapped.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
            return wrapped.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
            return wrapped.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public android.view.View onCreatePanelView(int featureId) {
            return wrapped.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) {
            return wrapped.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, android.view.View view, android.view.Menu menu) {
            return wrapped.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, android.view.Menu menu) {
            return wrapped.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) {
            return wrapped.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {
            wrapped.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            wrapped.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            wrapped.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            wrapped.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            wrapped.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, android.view.Menu menu) {
            wrapped.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return wrapped.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(android.view.SearchEvent searchEvent) {
            return wrapped.onSearchRequested(searchEvent);
        }

        @Override
        public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) {
            return wrapped.onWindowStartingActionMode(callback);
        }

        @Override
        public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) {
            return wrapped.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(android.view.ActionMode mode) {
            wrapped.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(android.view.ActionMode mode) {
            wrapped.onActionModeFinished(mode);
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
        new MaterialAlertDialogBuilder(activity)
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

    private static void handleWatchClick(AppCompatActivity activity, DrawerLayout drawerLayout) {
        pl.kejlo.mzutv2.wear.WearSyncManager.getWatchStatusWithPingAsync(activity, status -> {
            if (status == null || (!status.connected && !status.paired)) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.nav_watch_not_found_title)
                        .setMessage(R.string.nav_watch_not_found_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            String name = (status.name != null && !status.name.isEmpty())
                    ? status.name
                    : activity.getString(R.string.nav_watch_label);
            StringBuilder msg = new StringBuilder();
            if (status.connected) {
                if (!status.responsive) {
                    msg.append(activity.getString(R.string.nav_watch_status_connected_unresponsive));
                } else {
                    msg.append(activity.getString(R.string.nav_watch_status_connected));
                }
            } else {
                msg.append(activity.getString(R.string.nav_watch_status_paired));
            }
            msg.append("\n");
            msg.append(activity.getString(R.string.nav_watch_status_name, name));
            msg.append("\n");
            msg.append(activity.getString(R.string.nav_watch_status_bluetooth,
                    isBluetoothEnabled(activity)
                            ? activity.getString(R.string.nav_watch_status_bt_on)
                            : activity.getString(R.string.nav_watch_status_bt_off)));
            if (status.battery >= 0 && status.battery <= 100) {
                msg.append("\n");
                msg.append(activity.getString(R.string.nav_watch_status_battery, status.battery));
            }
            long lastSync = pl.kejlo.mzutv2.wear.WearSyncManager.getLastSyncTimestamp(activity);
            if (lastSync > 0) {
                CharSequence rel = DateUtils.getRelativeTimeSpanString(
                        lastSync,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
                msg.append("\n");
                msg.append(activity.getString(R.string.nav_watch_status_last_sync, rel));
            }
            long intervalMin = pl.kejlo.mzutv2.wear.WearSyncManager.getAutoSyncIntervalMinutes();
            boolean auto = pl.kejlo.mzutv2.wear.WearSyncManager.isAutoSyncEnabled(activity);
            String intervalLabel;
            if (intervalMin % 60 == 0) {
                intervalLabel = activity.getString(
                        R.string.nav_watch_status_interval_h, intervalMin / 60);
            } else {
                intervalLabel = activity.getString(
                        R.string.nav_watch_status_interval_m, intervalMin);
            }
            msg.append("\n");
            msg.append(activity.getString(
                    R.string.nav_watch_status_auto_sync,
                    auto ? activity.getString(R.string.nav_watch_status_auto_on)
                            : activity.getString(R.string.nav_watch_status_auto_off),
                    intervalLabel));
            long tileSeen = status.tileSeenTimestamp;
            long now = System.currentTimeMillis();
            if (tileSeen > 0 && now - tileSeen <= 7L * 24 * 60 * 60 * 1000L) {
                CharSequence relTile = DateUtils.getRelativeTimeSpanString(
                        tileSeen,
                        now,
                        DateUtils.MINUTE_IN_MILLIS);
                msg.append("\n");
                msg.append(activity.getString(R.string.nav_watch_status_tile_seen, relTile));
            } else {
                msg.append("\n");
                msg.append(activity.getString(R.string.nav_watch_status_tile_missing));
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(status.connected
                            ? R.string.nav_watch_found_title
                            : R.string.nav_watch_paired_title)
                    .setMessage(msg.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            drawerLayout.closeDrawers();
        });
    }

    private static boolean isBluetoothEnabled(Context context) {
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }
}
