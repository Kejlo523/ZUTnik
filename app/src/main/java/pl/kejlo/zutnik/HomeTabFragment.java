package pl.kejlo.zutnik;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class HomeTabFragment extends ZutnikTabFragment {

    private static final String TAG = "ZUTnik-HomeTab";

    private Toolbar toolbar;
    private LinearLayout homeHero;
    private LinearLayout homeSection;
    private androidx.cardview.widget.CardView indicatorOverlay;
    private TileGridLayout tileGrid;
    private HomeRepository homeRepository;
    private boolean introScheduled;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    @Nullable
    protected Toolbar getTabToolbar() {
        return toolbar;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotificationPermissionResult);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View skeleton = view.findViewById(R.id.homeSkeleton);
        View contentRoot = view.findViewById(R.id.homeContentRoot);
        showSkeleton(skeleton, contentRoot);

        toolbar = view.findViewById(R.id.toolbar);
        shellActivity().setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(shellActivity(), toolbar);
        if (shellActivity().getSupportActionBar() != null) {
            shellActivity().getSupportActionBar().setTitle(R.string.home_toolbar_title);
        } else {
            toolbar.setTitle(R.string.home_toolbar_title);
        }

        indicatorOverlay = view.findViewById(R.id.indicatorOverlay);
        TextView textWelcome = view.findViewById(R.id.textWelcome);
        TextView textWelcomeSub = view.findViewById(R.id.textWelcomeSub);
        homeHero = view.findViewById(R.id.homeHero);
        homeSection = view.findViewById(R.id.homeSection);
        tileGrid = view.findViewById(R.id.tileGrid);

        setupWelcomeText(textWelcome, textWelcomeSub);
        setupGrid();
        prepareIntroAnimations();
        scheduleIntroAnimations(view.findViewById(R.id.drawerContentRoot));

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (!isTabCurrentlyVisible()) {
                    return;
                }
                inflater.inflate(R.menu.home_menu, menu);
            }

            @Override
            public void onPrepareMenu(@NonNull Menu menu) {
                if (!isTabCurrentlyVisible() || tileGrid == null) {
                    return;
                }
                boolean isEdit = tileGrid.isEditMode();
                MenuItem editItem = menu.findItem(R.id.action_edit_home);
                if (editItem != null) {
                    editItem.setIcon(isEdit ? android.R.drawable.ic_menu_save : R.drawable.ic_edit_small);
                    editItem.setTitle(isEdit ? R.string.menu_home_save : R.string.menu_home_edit);
                    editItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                }
                MenuItem addItem = menu.findItem(R.id.action_add_tile);
                if (addItem != null) {
                    addItem.setVisible(isEdit);
                    addItem.setShowAsAction(isEdit ? MenuItem.SHOW_AS_ACTION_IF_ROOM : MenuItem.SHOW_AS_ACTION_NEVER);
                }
                MenuItem cancelItem = menu.findItem(R.id.action_cancel_edit);
                if (cancelItem != null) {
                    cancelItem.setVisible(isEdit);
                    cancelItem.setShowAsAction(isEdit ? MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_NEVER);
                }
                MenuItem resetItem = menu.findItem(R.id.action_reset_defaults);
                if (resetItem != null) {
                    resetItem.setVisible(isEdit);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (!isTabCurrentlyVisible()) {
                    return false;
                }
                return onHomeMenuItemSelected(item);
            }
        }, getViewLifecycleOwner());

        view.post(() -> revealContent(skeleton, contentRoot));
    }

    void handleNotificationPermissionFlow(Intent activityIntent) {
        if (activityIntent == null) {
            return;
        }
        SharedPreferences settings = requireContext()
                .getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean asked = settings.getBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, false);
        boolean hasPermission = NotificationSyncManager.hasNotificationPermission(requireContext());
        boolean shouldRequestPrompt = activityIntent.getBooleanExtra(
                MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION, false);
        if (shouldRequestPrompt) {
            activityIntent.removeExtra(MainShellActivity.EXTRA_REQUEST_NOTIF_PERMISSION);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!asked) {
                settings.edit()
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, true)
                        .apply();
            }
            NotificationSyncManager.syncWorkerSchedule(requireContext().getApplicationContext());
            return;
        }

        if (hasPermission) {
            if (!asked) {
                settings.edit()
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                        .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, true)
                        .apply();
            }
            NotificationSyncManager.syncWorkerSchedule(requireContext().getApplicationContext());
            return;
        }

        if (shouldRequestPrompt && !asked) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }

        NotificationSyncManager.syncWorkerSchedule(requireContext().getApplicationContext());
    }

    private void onNotificationPermissionResult(boolean granted) {
        SharedPreferences settings = requireContext()
                .getSharedPreferences(SettingsPrefs.PREFS_SETTINGS, Context.MODE_PRIVATE);
        settings.edit()
                .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED, true)
                .putBoolean(SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED, granted)
                .apply();

        if (!granted) {
            Toast.makeText(requireContext(), R.string.settings_notifications_permission_denied, Toast.LENGTH_LONG).show();
        }

        NotificationSyncManager.syncWorkerSchedule(requireContext().getApplicationContext());
    }

    private boolean onHomeMenuItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_edit_home) {
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
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.home_reset_dialog_title)
                    .setMessage(R.string.home_reset_dialog_message)
                    .setPositiveButton(R.string.home_reset_dialog_yes, (d, w) -> {
                        tileGrid.setTiles(homeRepository.restoreDefaults());
                        tileGrid.setEditMode(false);
                        invalidateActivityMenu();
                        shellActivity().setBottomNavVisible(true);
                        showActionIndicator();
                    })
                    .setNegativeButton(R.string.home_reset_dialog_no, null)
                    .show();
            return true;
        }
        return false;
    }

    @Override
    protected void onTabActivated() {
        if (!isTabCurrentlyVisible()) {
            return;
        }
        ensureHomeToolbarActive();
        invalidateActivityMenu();
    }

    private void setupGrid() {
        homeRepository = new HomeRepository(requireContext());
        List<Tile> tiles = homeRepository.loadTiles();
        tileGrid.setGap((int) (8f * getResources().getDisplayMetrics().density));
        tileGrid.setTiles(tiles);
        tileGrid.setOnTileClickListener(this::onTileClicked);
    }

    private void enterEditMode() {
        ensureHomeToolbarActive();
        tileGrid.setEditMode(true);
        invalidateActivityMenu();
        requireActivity().getWindow().getDecorView().post(this::invalidateActivityMenu);
        shellActivity().setBottomNavVisible(false);
    }

    private void ensureHomeToolbarActive() {
        if (toolbar == null) {
            return;
        }
        shellActivity().setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(shellActivity(), toolbar);
        if (shellActivity().getSupportActionBar() != null) {
            shellActivity().getSupportActionBar().setTitle(R.string.home_toolbar_title);
        }
    }

    private void saveAndExitEditMode() {
        ensureHomeToolbarActive();
        homeRepository.saveTiles(tileGrid.getTiles());
        tileGrid.setEditMode(false);
        invalidateActivityMenu();
        shellActivity().setBottomNavVisible(true);
        showActionIndicator();
    }

    private void cancelEditMode() {
        ensureHomeToolbarActive();
        tileGrid.setTiles(homeRepository.loadTiles());
        tileGrid.setEditMode(false);
        invalidateActivityMenu();
        shellActivity().setBottomNavVisible(true);
    }

    private void showActionIndicator() {
        if (indicatorOverlay == null) {
            return;
        }
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

    private void setupWelcomeText(TextView textWelcome, TextView textWelcomeSub) {
        ZutnikSession s = ZutnikSession.getInstance(requireContext());
        String username = s.getUsername();

        if ((username == null || username.trim().isEmpty()) && s.isUsosLogin()) {
            textWelcome.setText(getString(R.string.home_welcome_message,
                    getString(R.string.nav_header_default_username)));
            textWelcomeSub.setText(R.string.home_welcome_subtitle);
            refreshUsosUsername(textWelcome, s);
            return;
        }

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

    private void refreshUsosUsername(TextView textWelcome, ZutnikSession s) {
        Context appContext = requireContext().getApplicationContext();
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                NetworkRefreshPolicy.Decision decision = NetworkRefreshPolicy.evaluate(
                        appContext,
                        NetworkRefreshPolicy.Module.HOME_USER,
                        NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                        "home_user",
                        0L);
                if (!decision.allowNetwork) {
                    return;
                }
                NetworkRefreshPolicy.recordAttempt(
                        appContext,
                        NetworkRefreshPolicy.Module.HOME_USER,
                        NetworkRefreshPolicy.Mode.SCREEN_AUTO,
                        "home_user");
                org.json.JSONObject user = UsosApi.get("services/users/user", null);
                org.json.JSONObject firstObj = user.optJSONObject("first_name");
                org.json.JSONObject lastObj = user.optJSONObject("last_name");
                String first = firstObj != null
                        ? firstObj.optString("pl", firstObj.optString("en", ""))
                        : user.optString("first_name", "");
                String last = lastObj != null
                        ? lastObj.optString("pl", lastObj.optString("en", ""))
                        : user.optString("last_name", "");
                String name = (first + " " + last).trim();
                if (!name.isEmpty()) {
                    s.setUsername(name);
                    s.saveToPreferences(appContext);
                    NetworkRefreshPolicy.recordSuccess(
                            appContext,
                            NetworkRefreshPolicy.Module.HOME_USER,
                            "home_user");
                    requireActivity().runOnUiThread(() -> {
                        if (textWelcome != null) {
                            textWelcome.setText(getString(R.string.home_welcome_message,
                                    extractFirstName(name)));
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not refresh USOS username: " + e.getMessage());
            }
        });
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
        if (homeHero != null) {
            homeHero.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }
        if (tileGrid != null) {
            tileGrid.setAlpha(1f);
            tileGrid.post(() -> {
                if (tileGrid.getChildCount() > 0) {
                    tileGrid.animateTilesEntrance(80);
                }
            });
        }
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
                    Toast.makeText(requireContext(), R.string.home_open_url_error, Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            switch (tile.actionType) {
                case Tile.ACTION_PLAN:
                    shellActivity().switchToTab(MainNavHelper.Screen.PLAN, false);
                    return;
                case Tile.ACTION_GRADES:
                    shellActivity().switchToTab(MainNavHelper.Screen.GRADES, false);
                    return;
                case Tile.ACTION_INFO:
                    shellActivity().switchToTab(MainNavHelper.Screen.INFO, false);
                    return;
                case Tile.ACTION_NEWS:
                    MainNavHelper.navigateFrom(shellActivity(), MainNavHelper.Screen.NEWS);
                    return;
                case Tile.ACTION_NEWS_LATEST:
                    Intent latestIntent = new Intent(requireContext(), NewsActivity.class);
                    latestIntent.putExtra("EXTRA_OPEN_LATEST", true);
                    startActivity(latestIntent);
                    requireActivity().overridePendingTransition(0, 0);
                    return;
                case Tile.ACTION_PLAN_SEARCH:
                    shellActivity().switchToTab(MainNavHelper.Screen.PLAN, false);
                    return;
                case Tile.ACTION_ACTIVITY:
                    try {
                        if (tile.actionData != null) {
                            Class<?> cls = Class.forName(tile.actionData);
                            intent = new Intent(requireContext(), cls);
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
                requireActivity().overridePendingTransition(0, 0);
            } catch (Exception e) {
                Toast.makeText(requireContext(), getString(R.string.home_open_error, tile.title), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showAddEditDialog(Tile tile) {
        AddEditTileDialog dialog = AddEditTileDialog.newInstance(tile);
        dialog.setListener(savedTile -> {
            if (tile == null) {
                tileGrid.addTile(savedTile);
            } else {
                tileGrid.refreshTileView(savedTile);
                tileGrid.requestLayout();
            }
        });
        dialog.show(getParentFragmentManager(), "add_edit_tile");
    }

}
