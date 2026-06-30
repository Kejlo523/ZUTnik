package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainShellActivity extends ZutnikBaseActivity {

    public static final String EXTRA_INITIAL_TAB = "extra_initial_tab";
    public static final String EXTRA_REQUEST_NOTIF_PERMISSION = "extra_request_notif_permission";

    private BottomNavigationView bottomNavigation;
    private String currentTabId = MainNavHelper.Screen.HOME.getId();
    private boolean initialTabApplied;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
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

        bottomNavigation = findViewById(R.id.bottomNavigation);
        MainNavHelper.setupShell(this, findViewById(R.id.mainShellRoot), bottomNavigation);
        setupBackNavigation();

        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getString("current_tab", currentTabId);
            MainNavHelper.updateShellSelection(bottomNavigation, currentTabId);
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

    public void setBottomNavVisible(boolean visible) {
        MainNavHelper.setBottomNavVisible(bottomNavigation, visible);
    }

    public void switchToTab(@NonNull MainNavHelper.Screen screen, boolean animateFirstShow) {
        if (!MainNavHelper.isMainTab(screen)) {
            return;
        }
        if (screen.getId().equals(currentTabId) && findFragment(screen) != null && findFragment(screen).isVisible()) {
            Fragment alreadyVisible = findFragment(screen);
            if (alreadyVisible instanceof ZutnikTabFragment) {
                ((ZutnikTabFragment) alreadyVisible).onTabActivated();
            }
            return;
        }

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

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

        tx.commit();
        getSupportFragmentManager().executePendingTransactions();
        currentTabId = screen.getId();
        MainNavHelper.updateShellSelection(bottomNavigation, currentTabId);
        if (target instanceof ZutnikTabFragment) {
            ((ZutnikTabFragment) target).onTabActivated();
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
        if (!initialTabApplied && getSupportFragmentManager().getFragments().isEmpty()) {
            switchToTab(MainNavHelper.Screen.HOME, true);
            initialTabApplied = true;
        }
        postHandleNotificationPermission();
    }

    private void postHandleNotificationPermission() {
        findViewById(R.id.fragmentContainer).post(this::handleNotificationPermissionFlow);
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
            ((AppCompatActivity) context).overridePendingTransition(0, 0);
        }
    }
}
