package pl.kejlo.mzutv2;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import java.util.List;

public class HomeActivity extends MzutBaseActivity {

    private static final String TAG = "mZUTv2-Home";
    public static final String EXTRA_REQUEST_NOTIF_PERMISSION = "extra_request_notif_permission";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private DrawerLayout drawerLayout;

    private LinearLayout homeHero;
    private LinearLayout homeSection;

    private androidx.cardview.widget.CardView indicatorOverlay;

    // New Grid
    private TileGridLayout tileGrid;
    private HomeRepository homeRepository;
    private boolean introScheduled = false;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotificationPermissionResult);

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
        ThemeManager.applySystemBars(this);

        android.widget.FrameLayout drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        Toolbar toolbar = findViewById(R.id.toolbar);
        indicatorOverlay = findViewById(R.id.indicatorOverlay);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Setup Toolbar
        setSupportActionBar(toolbar);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.HOME);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.home_toolbar_title);
        } else {
            toolbar.setTitle(R.string.home_toolbar_title);
        }

        TextView textWelcome = findViewById(R.id.textWelcome);
        TextView textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero = findViewById(R.id.homeHero);
        homeSection = findViewById(R.id.homeSection);

        tileGrid = findViewById(R.id.tileGrid);

        setupSyncIndicator();
        setupWelcomeText(textWelcome, textWelcomeSub);
        setupGrid();
        prepareIntroAnimations();
        scheduleIntroAnimations(drawerContentRoot);
        drawerContentRoot.post(this::handleNotificationPermissionFlow);
    }

    private void handleNotificationPermissionFlow() {
        SharedPreferences settings = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
        boolean asked = settings.getBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, false);
        boolean hasPermission = NotificationSyncManager.hasNotificationPermission(this);
        boolean shouldRequestPrompt = getIntent().getBooleanExtra(EXTRA_REQUEST_NOTIF_PERMISSION, false);
        if (shouldRequestPrompt) {
            getIntent().removeExtra(EXTRA_REQUEST_NOTIF_PERMISSION);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!asked) {
                settings.edit()
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, true)
                        .apply();
            }
            NotificationSyncManager.syncWorkerSchedule(getApplicationContext());
            return;
        }

        if (hasPermission) {
            if (!asked) {
                settings.edit()
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, true)
                        .apply();
            }
            NotificationSyncManager.syncWorkerSchedule(getApplicationContext());
            return;
        }

        if (shouldRequestPrompt && !asked) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }

        NotificationSyncManager.syncWorkerSchedule(getApplicationContext());
    }

    private void onNotificationPermissionResult(boolean granted) {
        SharedPreferences settings = getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, MODE_PRIVATE);
        settings.edit()
                .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, granted)
                .apply();

        if (!granted) {
            Toast.makeText(this, R.string.settings_notifications_permission_denied, Toast.LENGTH_LONG).show();
        }

        NotificationSyncManager.syncWorkerSchedule(getApplicationContext());
    }



    private void setupGrid() {
        homeRepository = new HomeRepository(this);
        List<Tile> tiles = homeRepository.loadTiles();

        tileGrid.setGap((int) (8f * getResources().getDisplayMetrics().density));
        tileGrid.setTiles(tiles);

        // Removed auto-save. Save is manual via menu.
        tileGrid.setOnTileClickListener(this::onTileClicked);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.home_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@androidx.annotation.NonNull android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_edit_home) {
            // This is the "Edit" / "Save" button
            // If we are currently in edit mode, this acts as SAVE.
            if (tileGrid.isEditMode()) {
                saveAndExitEditMode();
            } else {
                enterEditMode();
            }
            return true;
        } else if (item.getItemId() == R.id.action_add_tile) {
            showAddEditDialog(null);
            return true;
        } else if (item.getItemId() == R.id.action_cancel_edit) {
            cancelEditMode();
            return true;
        } else if (item.getItemId() == R.id.action_reset_defaults) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.home_reset_dialog_title)
                    .setMessage(R.string.home_reset_dialog_message)
                    .setPositiveButton(R.string.home_reset_dialog_yes, (d, w) -> {
                        tileGrid.setTiles(homeRepository.restoreDefaults());
                        tileGrid.setEditMode(false);
                        invalidateOptionsMenu();
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                        showActionIndicator(); // Replaces Toast
                    })
                    .setNegativeButton(R.string.home_reset_dialog_no, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void enterEditMode() {
        tileGrid.setEditMode(true);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

    }

    private void saveAndExitEditMode() {
        // Save changes
        homeRepository.saveTiles(tileGrid.getTiles());
        tileGrid.setEditMode(false);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        showActionIndicator(); // Replaces "Saved" toast
    }

    private void cancelEditMode() {
        // Revert changes by reloading
        tileGrid.setTiles(homeRepository.loadTiles());
        tileGrid.setEditMode(false);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        // No toast for cancel needed, visuals revert is enough.
        // Toast.makeText(this, R.string.home_cancel_toast, Toast.LENGTH_SHORT).show();
    }

    private void showActionIndicator() {
        if (indicatorOverlay == null)
            return;
        indicatorOverlay.setAlpha(0f);
        indicatorOverlay.setVisibility(View.VISIBLE);
        indicatorOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction(() -> indicatorOverlay.postDelayed(() -> indicatorOverlay.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(() -> indicatorOverlay.setVisibility(View.GONE))
                            .start(), 1000))
                .start();
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        boolean isEdit = tileGrid.isEditMode();

        android.view.MenuItem editItem = menu.findItem(R.id.action_edit_home);
        if (editItem != null) {
            editItem.setIcon(isEdit ? android.R.drawable.ic_menu_save : R.drawable.ic_edit_small);
            editItem.setTitle(isEdit ? R.string.menu_home_save : R.string.menu_home_edit);
        }

        android.view.MenuItem addItem = menu.findItem(R.id.action_add_tile);
        if (addItem != null) {
            addItem.setVisible(isEdit);
        }

        android.view.MenuItem cancelItem = menu.findItem(R.id.action_cancel_edit);
        if (cancelItem != null) {
            cancelItem.setVisible(isEdit);
        }

        android.view.MenuItem resetItem = menu.findItem(R.id.action_reset_defaults);
        if (resetItem != null) {
            resetItem.setVisible(isEdit);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    private void setupWelcomeText(TextView textWelcome, TextView textWelcomeSub) {
        MzutSession s = MzutSession.getInstance(this);
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            username = getString(R.string.nav_header_default_username);
        }
        username = extractFirstName(username);

        textWelcome.setText(getString(R.string.home_welcome_message, username));
        textWelcomeSub.setText(R.string.home_welcome_subtitle);
    }

    private String extractFirstName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }
        int firstSpace = normalized.indexOf(' ');
        if (firstSpace > 0) {
            return normalized.substring(0, firstSpace);
        }
        return normalized;
    }

    private void runIntroAnimations() {
        // Hero section - fade + slide up
        if (homeHero != null) {
            homeHero.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
        }

        // Tile grid - staggered entrance for each tile
        // We need to wait for layout to complete before animating tiles
        if (tileGrid != null) {
            tileGrid.setAlpha(1f); // Grid container is visible
            tileGrid.post(() -> tileGrid.animateTilesEntrance(80)); // 80ms delay between tiles
        }

        // Footer section - subtle fade in
        if (homeSection != null) {
            homeSection.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(400)
                    .setDuration(400)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void prepareIntroAnimations() {
        if (homeHero != null) {
            homeHero.setAlpha(0f);
            homeHero.setTranslationY(60f);
        }
        if (tileGrid != null) {
            tileGrid.prepareTilesForEntrance();
        }
        if (homeSection != null) {
            homeSection.setAlpha(0f);
            homeSection.setTranslationY(40f);
        }
    }

    private void scheduleIntroAnimations(View drawerContentRoot) {
        if (introScheduled) {
            return;
        }
        introScheduled = true;
        View root = drawerContentRoot != null ? drawerContentRoot : homeHero;
        if (root == null) {
            runIntroAnimations();
            return;
        }
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                runIntroAnimations();
                return true;
            }
        });
    }

    public void onTileClicked(Tile tile) {
        if (tileGrid.isEditMode()) {
            showAddEditDialog(tile);
            return;
        }

        Intent intent = null;
        if (Tile.ACTION_URL.equals(tile.actionType)) {
            if (tile.actionData != null && !tile.actionData.isEmpty()) {
                try {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tile.actionData));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.home_open_url_error, Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            switch (tile.actionType) {
                case Tile.ACTION_PLAN:
                    intent = new Intent(this, PlanActivity.class);
                    break;
                case Tile.ACTION_GRADES:
                    intent = new Intent(this, GradesActivity.class);
                    break;
                case Tile.ACTION_INFO:
                    intent = new Intent(this, InfoActivity.class);
                    break;
                case Tile.ACTION_NEWS:
                    intent = new Intent(this, NewsActivity.class);
                    break;
                case Tile.ACTION_NEWS_LATEST:
                    intent = new Intent(this, NewsActivity.class);
                    intent.putExtra("EXTRA_OPEN_LATEST", true);
                    break;
                case Tile.ACTION_PLAN_SEARCH:
                    intent = new Intent(this, PlanActivity.class);
                    if (tile.actionData != null) {
                        try {
                            org.json.JSONObject o = new org.json.JSONObject(tile.actionData);
                            intent.putExtra("EXTRA_SEARCH_CATEGORY", o.optString("ck"));
                            intent.putExtra("EXTRA_SEARCH_QUERY", o.optString("q"));
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                case Tile.ACTION_ACTIVITY:
                    try {
                        if (tile.actionData != null) {
                            Class<?> cls = Class.forName(tile.actionData);
                            intent = new Intent(this, cls);
                        }
                    } catch (ClassNotFoundException e) {
                        Log.w(TAG, "Failed to open activity: " + tile.actionData, e);
                    }
                    break;
            }
        }

        if (intent != null) {
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.home_open_error, tile.title), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showAddEditDialog(Tile tile) {
        AddEditTileDialog dialog = AddEditTileDialog.newInstance(tile);
        dialog.setListener(savedTile -> {
            if (tile == null) {
                // Add new
                tileGrid.addTile(savedTile);
            } else {
                // Dialog modified 'tileToEdit' references if passed.
                // We should force grid refresh.
                tileGrid.refreshTileView(savedTile);
                tileGrid.requestLayout(); // Still needed for size/pos potentially
            }
        });
        dialog.show(getSupportFragmentManager(), "add_edit_tile");
    }

    // =============================================================================================
    // WearOS Sync Indicator Logic
    // =============================================================================================

    private androidx.cardview.widget.CardView syncIndicator;
    private android.widget.ProgressBar syncProgress;
    private android.widget.ImageView syncDoneIcon;
    private TextView syncText;
    private final android.content.BroadcastReceiver syncReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_PROGRESS.equals(intent.getAction())) {
                int progress = intent.getIntExtra(pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_PROGRESS, 0);
                String status = intent.getStringExtra(pl.kejlo.mzutv2.wear.WearSyncConstants.KEY_STATUS);
                updateSyncIndicator(progress, status);
            }
        }
    };

    private void setupSyncIndicator() {
        syncIndicator = findViewById(R.id.syncIndicator);
        syncProgress = findViewById(R.id.syncProgress);
        syncDoneIcon = findViewById(R.id.syncDoneIcon);
        syncText = findViewById(R.id.syncText);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register for global broadcast from WearSyncManager
        android.content.IntentFilter filter = new android.content.IntentFilter(pl.kejlo.mzutv2.wear.WearSyncConstants.ACTION_WEAR_SYNC_PROGRESS);
        ContextCompat.registerReceiver(this, syncReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(syncReceiver);
        } catch (Exception e) {
            // Ignore (already unregistered or not registered)
        }
    }

    private void updateSyncIndicator(int progress, String status) {
        if (syncIndicator == null) return;

        if (progress > 0 && progress < 100) {
            if (syncIndicator.getVisibility() != View.VISIBLE) {
                syncIndicator.setVisibility(View.VISIBLE);
                syncIndicator.setAlpha(0f);
                syncIndicator.setTranslationY(100f);
                syncIndicator.animate().alpha(1f).translationY(0f).setDuration(300).start();
            }
            syncProgress.setVisibility(View.VISIBLE);
            syncDoneIcon.setVisibility(View.GONE);
            syncText.setText(status != null ? status : getString(R.string.wear_main_status_syncing));
        } else if (progress >= 100) {
            // Success
            syncProgress.setVisibility(View.GONE);
            syncDoneIcon.setVisibility(View.VISIBLE);
            syncText.setText(R.string.wear_sync_status_watch_received);

            // Hide after delay
            syncIndicator.postDelayed(() -> {
                if (syncIndicator != null) {
                    syncIndicator.animate()
                            .alpha(0f)
                            .translationY(100f)
                            .setDuration(300)
                            .withEndAction(() -> syncIndicator.setVisibility(View.GONE))
                            .start();
                }
            }, 3000);
        } else if (progress == 0 && status != null) {
            // Error starts with 0
            // Just hide it or show error? Let's hide for now to avoid annoyance, or show briefly.
            // If status contains "Error"
             if (syncIndicator.getVisibility() == View.VISIBLE) {
                 syncIndicator.postDelayed(() -> {
                     if (syncIndicator != null) {
                        syncIndicator.animate().alpha(0f).withEndAction(() -> syncIndicator.setVisibility(View.GONE)).start();
                     }
                 }, 2000);
             }
        }
    }
}
