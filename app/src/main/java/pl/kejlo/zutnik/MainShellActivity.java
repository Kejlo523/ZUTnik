package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

public class MainShellActivity extends ZutnikBaseActivity {

    public static final String EXTRA_INITIAL_TAB = "extra_initial_tab";
    public static final String EXTRA_REQUEST_NOTIF_PERMISSION = "extra_request_notif_permission";

    private AppUpdateHelper appUpdateHelper;
    @Nullable
    private InAppReviewPrompter inAppReviewPrompter;

    private final ActivityResultLauncher<IntentSenderRequest> appUpdateLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (appUpdateHelper != null) {
                            appUpdateHelper.onUpdateFlowResult(result.getResultCode());
                        }
                    });

    private BottomNavigationView bottomNavigation;
    private NavigationRailView navigationRail;
    private NavigationBarView shellNavigation;
    private String currentTabId = MainNavHelper.Screen.HOME.getId();
    private boolean initialTabApplied;
    private boolean tabTransitionRunning;
    @Nullable
    private MainNavHelper.Screen pendingTab;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applySystemBars(this);

        ZutnikSession.initializeFromPreferences(this);
        if (!ZutnikSession.getInstance().isLoggedIn()) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_main_shell);
        ThemeManager.applySystemBars(this);

        appUpdateHelper = new AppUpdateHelper(this, appUpdateLauncher);
        appUpdateHelper.bindBanner(findViewById(R.id.updateBanner));
        if (!ZutnikSession.getInstance().isDemoLogin()) {
            inAppReviewPrompter = new InAppReviewPrompter(this, savedInstanceState == null);
        }

        bottomNavigation = findViewById(R.id.bottomNavigation);
        navigationRail = findViewById(R.id.navigationRail);
        shellNavigation = MainNavHelper.findShellNavigation(this);
        MainNavHelper.setupShell(this, findViewById(R.id.mainShellRoot), shellNavigation);
        keepNavigationAboveContent();
        setupBackNavigation();

        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getString("current_tab", currentTabId);
            MainNavHelper.updateShellSelection(shellNavigation, currentTabId);
        } else {
            String initialTab = getIntent().getStringExtra(EXTRA_INITIAL_TAB);
            MainNavHelper.Screen start = MainNavHelper.Screen.fromId(initialTab);
            if (start == null || !MainNavHelper.isMainTab(start)) {
                start = MainNavHelper.Screen.HOME;
            }
            switchToTab(start, true);
            initialTabApplied = true;
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (MainNavHelper.dismissMoreSheetIfShowing()) {
                    return;
                }
                if (navigateBackToHome()) {
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private boolean navigateBackToHome() {
        if (MainNavHelper.Screen.HOME.getId().equals(currentTabId)) {
            return false;
        }
        switchToTab(MainNavHelper.Screen.HOME, false);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String initialTab = intent.getStringExtra(EXTRA_INITIAL_TAB);
        MainNavHelper.Screen start = MainNavHelper.Screen.fromId(initialTab);
        if (start != null && MainNavHelper.isMainTab(start)) {
            switchToTab(start, false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("current_tab", currentTabId);
    }

    public String getCurrentTabId() {
        return currentTabId;
    }

    public BottomNavigationView getBottomNavigation() {
        return bottomNavigation;
    }

    public NavigationBarView getShellNavigation() {
        return shellNavigation;
    }

    public void setBottomNavVisible(boolean visible) {
        MainNavHelper.setShellNavigationVisible(shellNavigation, visible);
    }

    public void openPlanSearch(@NonNull String category, @NonNull String query) {
        Fragment existing = findFragment(MainNavHelper.Screen.PLAN);
        getIntent().putExtra("EXTRA_SEARCH_CATEGORY", category);
        getIntent().putExtra("EXTRA_SEARCH_QUERY", query);
        switchToTab(MainNavHelper.Screen.PLAN, false);
        if (existing instanceof PlanTabFragment) {
            ((PlanTabFragment) existing).applyExternalSearch(category, query);
            getIntent().removeExtra("EXTRA_SEARCH_CATEGORY");
            getIntent().removeExtra("EXTRA_SEARCH_QUERY");
        }
    }

    public void switchToTab(@NonNull MainNavHelper.Screen screen, boolean animateFirstShow) {
        if (!MainNavHelper.isMainTab(screen)) {
            return;
        }
        if (tabTransitionRunning) {
            pendingTab = screen;
            return;
        }
        if (screen.getId().equals(currentTabId) && findFragment(screen) != null && findFragment(screen).isVisible()) {
            Fragment alreadyVisible = findFragment(screen);
            if (alreadyVisible instanceof ZutnikTabFragment) {
                ((ZutnikTabFragment) alreadyVisible).onTabActivated();
            }
            return;
        }

        MainNavHelper.setShellNavigationVisible(shellNavigation, true);

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        boolean forward = tabOrder(screen) >= tabOrder(MainNavHelper.Screen.fromId(currentTabId));
        tx.setCustomAnimations(
                forward ? R.anim.tab_enter_forward : R.anim.tab_enter_back,
                forward ? R.anim.tab_exit_forward : R.anim.tab_exit_back);

        for (Fragment fragment : fm.getFragments()) {
            if (fragment.isAdded()) {
                tx.hide(fragment);
            }
        }

        Fragment target = findFragment(screen);
        boolean firstAdd = target == null;
        if (firstAdd) {
            target = createFragment(screen);
            tx.add(R.id.fragmentContainer, target, screen.getId());
        } else {
            tx.show(target);
        }

        currentTabId = screen.getId();
        tabTransitionRunning = true;
        tx.commit();
        getSupportFragmentManager().executePendingTransactions();
        keepNavigationAboveContent();
        MainNavHelper.updateShellSelection(shellNavigation, currentTabId);
        if (target instanceof ZutnikTabFragment) {
            ((ZutnikTabFragment) target).onTabActivated();
        }
        View fragmentContainer = findViewById(R.id.fragmentContainer);
        fragmentContainer.postDelayed(this::finishTabTransition, 220L);
    }

    private void finishTabTransition() {
        tabTransitionRunning = false;
        MainNavHelper.Screen next = pendingTab;
        pendingTab = null;
        if (next != null && !next.getId().equals(currentTabId)) {
            switchToTab(next, false);
        } else {
            MainNavHelper.updateShellSelection(shellNavigation, currentTabId);
        }
    }

    private void keepNavigationAboveContent() {
        if (shellNavigation != null) {
            shellNavigation.bringToFront();
        }
    }

    private int tabOrder(@Nullable MainNavHelper.Screen screen) {
        if (screen == null) {
            return 0;
        }
        switch (screen) {
            case PLAN:
                return 1;
            case INFO:
                return 2;
            case GRADES:
                return 3;
            case HOME:
            default:
                return 0;
        }
    }

    @Nullable
    private Fragment findFragment(@NonNull MainNavHelper.Screen screen) {
        return getSupportFragmentManager().findFragmentByTag(screen.getId());
    }

    @NonNull
    private Fragment createFragment(@NonNull MainNavHelper.Screen screen) {
        switch (screen) {
            case PLAN:
                return new PlanTabFragment();
            case INFO:
                return new InfoTabFragment();
            case GRADES:
                return new GradesTabFragment();
            case HOME:
            default:
                return new HomeTabFragment();
        }
    }

    public void handleNotificationPermissionFlow() {
        Fragment home = findFragment(MainNavHelper.Screen.HOME);
        if (home instanceof HomeTabFragment) {
            ((HomeTabFragment) home).handleNotificationPermissionFlow(getIntent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appUpdateHelper != null) {
            appUpdateHelper.onResume();
        }
        if (inAppReviewPrompter != null) {
            inAppReviewPrompter.onResume();
        }
        if (!initialTabApplied && getSupportFragmentManager().getFragments().isEmpty()) {
            switchToTab(MainNavHelper.Screen.HOME, true);
            initialTabApplied = true;
        }
        postHandleNotificationPermission();
    }

    @Override
    protected void onPause() {
        if (appUpdateHelper != null) {
            appUpdateHelper.onPause();
        }
        if (inAppReviewPrompter != null) {
            inAppReviewPrompter.onPause();
        }
        super.onPause();
    }

    private void postHandleNotificationPermission() {
        findViewById(R.id.fragmentContainer).post(this::handleNotificationPermissionFlow);
    }

    @Override
    protected void onDestroy() {
        if (appUpdateHelper != null) {
            appUpdateHelper.onDestroy();
        }
        if (inAppReviewPrompter != null) {
            inAppReviewPrompter.onDestroy();
        }
        MainNavHelper.dismissMoreSheetIfShowing();
        super.onDestroy();
    }

    public static Intent createIntent(Context context, @NonNull MainNavHelper.Screen tab) {
        return new Intent(context, MainShellActivity.class)
                .putExtra(EXTRA_INITIAL_TAB, tab.getId());
    }

    public static void openTab(Context context, @NonNull MainNavHelper.Screen tab) {
        Context appContext = context.getApplicationContext();
        Intent intent = createIntent(context, tab);
        if (!(context instanceof AppCompatActivity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
        if (context instanceof AppCompatActivity) {
            ((AppCompatActivity) context).overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit);
        }
    }
}
