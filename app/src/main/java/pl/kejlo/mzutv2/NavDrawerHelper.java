package pl.kejlo.mzutv2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import android.view.View;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
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
        private final DrawerLayout drawerLayout;
        private final int touchSlop;
        private final float openSwipeThreshold;
        private float downX;
        private float downY;
        private boolean trackingSwipe;
        private boolean consumedBySwipe;

        public SwipeWindowCallback(android.view.Window.Callback wrapped, android.content.Context context,
                DrawerLayout drawerLayout) {
            this.wrapped = wrapped;
            this.drawerLayout = drawerLayout;
            android.view.ViewConfiguration config = android.view.ViewConfiguration.get(context);
            this.touchSlop = config.getScaledTouchSlop();
            this.openSwipeThreshold = Math.max(
                    touchSlop * 2f,
                    context.getResources().getDisplayMetrics().density * 56f);
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            if (event == null) {
                return false;
            }

            int action = event.getActionMasked();
            if (consumedBySwipe && action != android.view.MotionEvent.ACTION_DOWN) {
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    consumedBySwipe = false;
                    trackingSwipe = false;
                }
                return true;
            }

            switch (action) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    trackingSwipe = true;
                    consumedBySwipe = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (trackingSwipe && canOpenDrawer()) {
                        float deltaX = event.getX() - downX;
                        float deltaY = event.getY() - downY;
                        float absDeltaY = Math.abs(deltaY);

                        // Vertical scroll should keep normal interaction flow.
                        if (absDeltaY > touchSlop && absDeltaY > Math.abs(deltaX)) {
                            trackingSwipe = false;
                            break;
                        }

                        // Distinct horizontal swipe-right opens drawer and consumes tap sequence.
                        if (deltaX > openSwipeThreshold && Math.abs(deltaX) > absDeltaY * 1.15f) {
                            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                            consumedBySwipe = true;
                            trackingSwipe = false;
                            return true;
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (consumedBySwipe) {
                        consumedBySwipe = false;
                        trackingSwipe = false;
                        return true;
                    }
                    trackingSwipe = false;
                    break;
                default:
                    break;
            }
            return wrapped.dispatchTouchEvent(event);
        }

        private boolean canOpenDrawer() {
            return drawerLayout.getDrawerLockMode(androidx.core.view.GravityCompat.START) != DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                    && !drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START);
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
        Context appContext = activity.getApplicationContext();
        MzutSession.clearSessionData(appContext);
        SessionExpiryManager.clearSessionExpiredNotice(appContext);
        NotificationSyncManager.cancelWorker(appContext);

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
        // 1. Inflate and show dialog IMMEDIATELY
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_watch_status, null, false);

        // Find views
        View loadingView = content.findViewById(R.id.watchStatusLoading);
        View contentView = content.findViewById(R.id.watchStatusContent);
        View disconnectedHint = content.findViewById(R.id.watchDisconnectedHint);
        View batteryCard = content.findViewById(R.id.watchBatteryCard);
        View infoContainer = content.findViewById(R.id.watchInfoContainer);
        View installContainer = content.findViewById(R.id.watchInstallContainer);
        View installButton = content.findViewById(R.id.watchInstallButton);

        TextView titleView = content.findViewById(R.id.watchStatusTitle);
        TextView subtitleView = content.findViewById(R.id.watchStatusSubtitle);
        TextView nameView = content.findViewById(R.id.watchStatusName);
        TextView batteryView = content.findViewById(R.id.watchStatusBattery);
        TextView btValue = content.findViewById(R.id.watchInfoBluetoothValue);
        TextView lastSyncValue = content.findViewById(R.id.watchInfoLastSyncValue);
        TextView autoSyncValue = content.findViewById(R.id.watchInfoAutoSyncValue);
        TextView tileValue = content.findViewById(R.id.watchInfoTileValue);
        CircularProgressIndicator ring = content.findViewById(R.id.watchBatteryRing);
        TextView ringPercent = content.findViewById(R.id.watchBatteryPercent);
        ImageView batteryIcon = content.findViewById(R.id.watchBatteryIcon);

        // Show dialog now
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        drawerLayout.closeDrawers();

        // 2. Fetch data (ASYNC)
        pl.kejlo.mzutv2.wear.WearSyncManager.getWatchStatusWithPingAsync(activity, status -> {
            if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) {
                return;
            }

            // Hide loading, show content
            loadingView.setVisibility(View.GONE);
            contentView.setVisibility(View.VISIBLE);
            installContainer.setVisibility(View.GONE);

            boolean isConnected = status != null && status.connected;
            boolean isPaired = status != null && status.paired;
            String name = (status != null && status.name != null && !status.name.isEmpty())
                    ? status.name
                    : activity.getString(R.string.nav_watch_label);

            nameView.setVisibility(View.VISIBLE);
            nameView.setText(activity.getString(R.string.nav_watch_status_name, name));

            // Not paired and not connected: keep the dialog minimal.
            if (!isConnected && !isPaired) {
                titleView.setText(R.string.nav_watch_not_found_title);
                subtitleView.setText(R.string.nav_watch_not_found_msg);
                nameView.setVisibility(View.GONE);
                disconnectedHint.setVisibility(View.GONE);
                batteryCard.setVisibility(View.GONE);
                infoContainer.setVisibility(View.GONE);
                return;
            }

            // Paired but disconnected: hide technical cards and show only key guidance.
            if (!isConnected) {
                titleView.setText(R.string.nav_watch_paired_title);
                subtitleView.setText(R.string.nav_watch_status_paired);
                if (disconnectedHint instanceof TextView) {
                    ((TextView) disconnectedHint).setText(
                            activity.getString(R.string.nav_watch_paired_msg, name));
                }
                disconnectedHint.setVisibility(View.VISIBLE);
                batteryCard.setVisibility(View.GONE);
                infoContainer.setVisibility(View.GONE);
                return;
            }

            disconnectedHint.setVisibility(View.GONE);
            batteryCard.setVisibility(View.VISIBLE);
            infoContainer.setVisibility(View.VISIBLE);

            String statusLine = activity.getString(status.responsive
                        ? R.string.nav_watch_status_connected
                        : R.string.nav_watch_status_connected_unresponsive);
            titleView.setText(R.string.nav_watch_found_title);
            subtitleView.setText(statusLine);

            int battery = status.battery;
            if (battery >= 0 && battery <= 100) {
                batteryView.setText(activity.getString(
                        R.string.nav_watch_status_battery, battery));
                ringPercent.setText(activity.getString(R.string.common_percent_format, battery));
                ring.setProgress(battery);
            } else {
                batteryView.setText(R.string.nav_watch_info_last_sync_none);
                ringPercent.setText(R.string.common_percent_placeholder);
                ring.setProgress(0);
            }

            // Colors logic
            int colorDanger = MaterialColors.getColor(content, R.attr.mzDanger, 0xFFE53935);
            int colorAccent = MaterialColors.getColor(content, R.attr.mzAccent, 0xFF4F8DFF);
            int colorLime = MaterialColors.getColor(content, R.attr.mzLime, 0xFF9CCC65);
            int colorSuccess = MaterialColors.getColor(content, R.attr.mzSuccess, 0xFF43A047);
            int colorMuted = MaterialColors.getColor(content, R.attr.mzMuted, 0xFFB0B7C3);
            int ringColor;
            if (battery < 0) {
                ringColor = colorMuted;
            } else if (battery <= 15) {
                ringColor = colorDanger;
            } else if (battery <= 35) {
                ringColor = ColorUtils.blendARGB(colorDanger, colorAccent, 0.5f);
            } else if (battery <= 70) {
                ringColor = colorLime;
            } else {
                ringColor = colorSuccess;
            }
            int trackColor = MaterialColors.getColor(content, R.attr.mzBorderSoft, 0x33222222);
            ring.setIndicatorColor(ringColor);
            ring.setTrackColor(trackColor);
            batteryIcon.setColorFilter(ringColor);
            batteryIcon.setImageResource(battery >= 0 && battery <= 15
                    ? R.drawable.ic_battery_alert
                    : R.drawable.ic_battery_outline);

            btValue.setText(isBluetoothEnabled(activity)
                    ? R.string.nav_watch_status_bt_on
                    : R.string.nav_watch_status_bt_off);

            long lastSync = pl.kejlo.mzutv2.wear.WearSyncManager.getLastSyncTimestamp(activity);
            if (lastSync > 0) {
                CharSequence rel = DateUtils.getRelativeTimeSpanString(
                        lastSync,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
                lastSyncValue.setText(rel);
            } else {
                lastSyncValue.setText(R.string.nav_watch_info_last_sync_none);
            }

            long intervalMin = pl.kejlo.mzutv2.wear.WearSyncManager.getAutoSyncIntervalMinutes();
            boolean auto = pl.kejlo.mzutv2.wear.WearSyncManager.isAutoSyncEnabled(activity);
            String intervalLabel = intervalMin % 60 == 0
                    ? activity.getString(R.string.nav_watch_status_interval_h, intervalMin / 60)
                    : activity.getString(R.string.nav_watch_status_interval_m, intervalMin);
            String autoLabel = activity.getString(auto
                    ? R.string.nav_watch_status_auto_on
                    : R.string.nav_watch_status_auto_off);
            autoSyncValue.setText(activity.getString(
                    R.string.nav_watch_info_auto_sync_value, autoLabel, intervalLabel));

            long tileSeen = status.tileSeenTimestamp;
            long now = System.currentTimeMillis();
            if (tileSeen > 0 && now - tileSeen <= 7L * 24 * 60 * 60 * 1000L) {
                CharSequence relTile = DateUtils.getRelativeTimeSpanString(
                        tileSeen,
                        now,
                        DateUtils.MINUTE_IN_MILLIS);
                tileValue.setText(activity.getString(
                        R.string.nav_watch_info_tile_active, relTile));
            } else {
                tileValue.setText(R.string.nav_watch_info_tile_missing);
            }

            // Now check if app is missing to decide button
            pl.kejlo.mzutv2.wear.WearSyncManager.checkIfWatchMissingApp(activity, missingNodeId -> {
                if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) {
                    return;
                }
                if (missingNodeId != null) {
                    // App is missing! Show "Install" button
                    installContainer.setVisibility(View.VISIBLE);
                    installButton.setOnClickListener(v -> {
                        pl.kejlo.mzutv2.wear.WearSyncManager.openPlayStoreOnWatch(activity, missingNodeId);
                        Toast.makeText(activity, R.string.wear_install_sent_toast, Toast.LENGTH_SHORT).show();
                    });
                } else {
                    installContainer.setVisibility(View.GONE);
                }
            });
        });
    }

    private static boolean isBluetoothEnabled(Context context) {
        try {
            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            return adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }
}
