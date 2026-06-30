package pl.kejlo.zutnik;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

public class MainNavHelper {

    private static final String TAG = "ZUTnik-MainNav";
    private static boolean suppressNavCallback;
    private static com.google.android.material.bottomsheet.BottomSheetDialog activeMoreSheet;

    public enum Screen {
        HOME("home", R.string.home_toolbar_title),
        INFO("info", R.string.info_title),
        FINANCE("finance", R.string.finance_title),
        PLAN("plan", R.string.plan_title),
        GRADES("grades", R.string.grades_title),
        ATTENDANCE("attendance", R.string.attendance_title),
        NEWS("news", R.string.news_title),
        USEFUL("useful", R.string.nav_useful_links),
        ABOUT("about", R.string.about_title);

        private final String id;
        private final int titleResId;

        Screen(String id, int titleResId) {
            this.id = id;
            this.titleResId = titleResId;
        }

        public String getId() {
            return id;
        }

        public int getTitleResId() {
            return titleResId;
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

    public static boolean isMainTab(Screen screen) {
        return screen == Screen.HOME
                || screen == Screen.PLAN
                || screen == Screen.INFO
                || screen == Screen.GRADES;
    }

    private static boolean handleMoreNavClick(
            AppCompatActivity activity,
            NavigationBarView shellNav,
            String currentScreen) {
        if (activeMoreSheet != null && activeMoreSheet.isShowing()) {
            activeMoreSheet.dismiss();
            return true;
        }
        showMoreSheet(activity, shellNav, currentScreen);
        return true;
    }

    private static void dismissMoreSheet() {
        if (activeMoreSheet != null && activeMoreSheet.isShowing()) {
            activeMoreSheet.dismiss();
        }
        activeMoreSheet = null;
    }

    public static boolean dismissMoreSheetIfShowing() {
        if (activeMoreSheet == null || !activeMoreSheet.isShowing()) {
            return false;
        }
        dismissMoreSheet();
        return true;
    }

    public static void setupShell(
            MainShellActivity activity,
            View contentRoot,
            NavigationBarView shellNav) {
        if (shellNav == null) {
            return;
        }
        applyWindowInsets(contentRoot, shellNav);

        suppressNavCallback = true;
        shellNav.setSelectedItemId(menuItemForScreen(activity.getCurrentTabId()));
        suppressNavCallback = false;

        shellNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) {
                return true;
            }
            int itemId = item.getItemId();
            if (itemId == R.id.nav_more) {
                return handleMoreNavClick(activity, shellNav, activity.getCurrentTabId());
            }
            Screen target = screenForMenuItem(itemId);
            if (target == null) {
                return false;
            }
            if (target.getId().equals(activity.getCurrentTabId())) {
                return true;
            }
            activity.switchToTab(target, false);
            return true;
        });

        shellNav.setOnItemReselectedListener(item -> {
            if (suppressNavCallback) {
                return;
            }
            if (item.getItemId() == R.id.nav_more) {
                handleMoreNavClick(activity, shellNav, activity.getCurrentTabId());
            }
        });
    }

    public static void updateShellSelection(NavigationBarView shellNav, String currentScreen) {
        if (shellNav == null) {
            return;
        }
        int itemId = menuItemForScreen(currentScreen);
        if (shellNav.getSelectedItemId() == itemId) {
            return;
        }
        suppressNavCallback = true;
        shellNav.setSelectedItemId(itemId);
        suppressNavCallback = false;
    }

    public static void setup(
            AppCompatActivity activity,
            View contentRoot,
            NavigationBarView shellNav,
            Toolbar toolbar,
            Screen currentScreen) {
        String currentScreenId = currentScreen != null ? currentScreen.getId() : null;
        setup(activity, contentRoot, shellNav, toolbar, currentScreenId);
    }

    @SuppressLint("StringFormatInvalid")
    public static void setup(
            AppCompatActivity activity,
            View contentRoot,
            NavigationBarView shellNav,
            Toolbar toolbar,
            String currentScreen) {
        if (shellNav == null) {
            return;
        }

        if (toolbar != null) {
            activity.setSupportActionBar(toolbar);
        }
        styleToolbar(activity, toolbar);
        applyToolbarTitle(activity, toolbar, Screen.fromId(currentScreen));
        applyWindowInsets(contentRoot, shellNav);

        suppressNavCallback = true;
        shellNav.setSelectedItemId(menuItemForScreen(currentScreen));
        suppressNavCallback = false;

        shellNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) {
                return true;
            }
            int itemId = item.getItemId();
            if (itemId == R.id.nav_more) {
                return handleMoreNavClick(activity, shellNav, currentScreen);
            }
            Screen target = screenForMenuItem(itemId);
            if (target == null) {
                return false;
            }
            if (target.getId().equals(currentScreen)) {
                return true;
            }
            navigateTo(activity, target, currentScreen);
            return true;
        });

        shellNav.setOnItemReselectedListener(item -> {
            if (suppressNavCallback) {
                return;
            }
            if (item.getItemId() == R.id.nav_more) {
                handleMoreNavClick(activity, shellNav, currentScreen);
            }
        });
    }

    public static NavigationBarView findShellNavigation(AppCompatActivity activity) {
        if (activity == null) {
            return null;
        }
        NavigationBarView rail = activity.findViewById(R.id.navigationRail);
        if (rail != null) {
            return rail;
        }
        return activity.findViewById(R.id.bottomNavigation);
    }

    public static void setBottomNavVisible(BottomNavigationView bottomNav, boolean visible) {
        setShellNavigationVisible(bottomNav, visible);
    }

    public static void setShellNavigationVisible(NavigationBarView shellNav, boolean visible) {
        if (shellNav == null) {
            return;
        }
        int nextVisibility = visible ? View.VISIBLE : View.GONE;
        if (!visible) {
            dismissMoreSheet();
        }
        if (shellNav.getVisibility() == nextVisibility) {
            return;
        }
        shellNav.setVisibility(nextVisibility);
        ViewCompat.requestApplyInsets(shellNav);
        if (shellNav.getParent() instanceof View) {
            ViewCompat.requestApplyInsets((View) shellNav.getParent());
        }
    }

    public static int bottomNavigationInset(NavigationBarView shellNav) {
        if (shellNav == null || shellNav.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (shellNav instanceof NavigationRailView) {
            return 0;
        }
        return shellNav.getHeight();
    }

    public static void styleToolbarPublic(AppCompatActivity activity, Toolbar toolbar) {
        if (toolbar == null) {
            return;
        }
        int toolbarBg = ThemeManager.resolveColor(activity, R.attr.mzBg);
        int toolbarText = ThemeManager.resolveColor(activity, R.attr.mzText);
        toolbar.setBackgroundColor(toolbarBg);
        toolbar.setTitleTextColor(toolbarText);
        toolbar.setSubtitleTextColor(ColorUtils.setAlphaComponent(toolbarText, 180));
        toolbar.setNavigationIcon(null);
        android.graphics.drawable.Drawable overflow = toolbar.getOverflowIcon();
        if (overflow != null) {
            overflow = overflow.mutate();
            overflow.setTint(toolbarText);
            toolbar.setOverflowIcon(overflow);
        }
        ensureToolbarFitsSubtitle(activity, toolbar);
        applyStatusBarInsetToToolbar(toolbar);
    }

    private static void styleToolbar(AppCompatActivity activity, Toolbar toolbar) {
        styleToolbarPublic(activity, toolbar);
    }

    public static void applyToolbarTitle(
            AppCompatActivity activity,
            Toolbar toolbar,
            Screen screen) {
        if (screen == null || screen.getTitleResId() == 0) {
            return;
        }
        if (toolbar != null) {
            toolbar.setTitle(screen.getTitleResId());
        }
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(screen.getTitleResId());
        }
    }

    public static void navigateFrom(AppCompatActivity activity, Screen target) {
        if (activity == null || target == null) {
            return;
        }
        String currentScreen = activity instanceof MainShellActivity
                ? ((MainShellActivity) activity).getCurrentTabId()
                : null;
        navigateTo(activity, target, currentScreen);
    }

    private static void ensureToolbarFitsSubtitle(AppCompatActivity activity, Toolbar toolbar) {
        TypedValue actionBarSize = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, actionBarSize, true)) {
            toolbar.setMinimumHeight(TypedValue.complexToDimensionPixelSize(
                    actionBarSize.data,
                    activity.getResources().getDisplayMetrics()));
        }
        ViewGroup.LayoutParams layoutParams = toolbar.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            toolbar.setLayoutParams(layoutParams);
        }
    }

    public static void applyStatusBarInsetToToolbar(Toolbar toolbar) {
        if (toolbar == null) {
            return;
        }
        Object existing = toolbar.getTag(R.id.tag_toolbar_status_insets);
        if (existing instanceof ToolbarInsetState) {
            ViewCompat.requestApplyInsets(toolbar);
            return;
        }

        final int subtitleBottomPadding = (int) (6f * toolbar.getResources().getDisplayMetrics().density);
        ToolbarInsetState state = new ToolbarInsetState(
                toolbar.getPaddingLeft(),
                toolbar.getPaddingTop(),
                toolbar.getPaddingRight(),
                Math.max(toolbar.getPaddingBottom(), subtitleBottomPadding));
        toolbar.setTag(R.id.tag_toolbar_status_insets, state);

        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, windowInsets) -> {
            Insets statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(
                    state.baseLeft + statusBars.left,
                    state.baseTop + statusBars.top,
                    state.baseRight + statusBars.right,
                    state.baseBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(toolbar);
    }

    private static final class ToolbarInsetState {
        final int baseLeft;
        final int baseTop;
        final int baseRight;
        final int baseBottom;

        ToolbarInsetState(int baseLeft, int baseTop, int baseRight, int baseBottom) {
            this.baseLeft = baseLeft;
            this.baseTop = baseTop;
            this.baseRight = baseRight;
            this.baseBottom = baseBottom;
        }
    }

    public static void applyRootContentInsets(View contentRoot) {
        if (contentRoot == null) {
            return;
        }
        final int baseLeft = contentRoot.getPaddingLeft();
        final int baseTop = contentRoot.getPaddingTop();
        final int baseRight = contentRoot.getPaddingRight();
        final int baseBottom = contentRoot.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    baseLeft + bars.left,
                    baseTop,
                    baseRight + bars.right,
                    baseBottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(contentRoot);
    }

    private static void applyWindowInsets(View contentRoot, NavigationBarView shellNav) {
        boolean isRail = shellNav instanceof NavigationRailView;
        if (contentRoot != null) {
            final int baseLeft = contentRoot.getPaddingLeft();
            final int baseTop = contentRoot.getPaddingTop();
            final int baseRight = contentRoot.getPaddingRight();
            final int baseBottom = contentRoot.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                if (isRail) {
                    view.setPadding(
                            baseLeft,
                            baseTop,
                            baseRight + bars.right,
                            baseBottom);
                } else {
                    view.setPadding(
                            baseLeft + bars.left,
                            baseTop,
                            baseRight + bars.right,
                            baseBottom);
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(contentRoot);
        }

        if (shellNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(shellNav, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                if (isRail) {
                    view.setPadding(
                            bars.left,
                            bars.top,
                            view.getPaddingRight(),
                            bars.bottom);
                } else {
                    view.setPadding(
                            view.getPaddingLeft(),
                            view.getPaddingTop(),
                            view.getPaddingRight(),
                            bars.bottom);
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(shellNav);
        }
    }

    private static int menuItemForScreen(String currentScreen) {
        Screen screen = Screen.fromId(currentScreen);
        if (screen == null) {
            return R.id.nav_more;
        }
        switch (screen) {
            case HOME:
                return R.id.nav_home;
            case PLAN:
                return R.id.nav_plan;
            case INFO:
                return R.id.nav_studies;
            case FINANCE:
                return R.id.nav_more;
            case GRADES:
                return R.id.nav_grades;
            default:
                return R.id.nav_more;
        }
    }

    private static Screen screenForMenuItem(int itemId) {
        if (itemId == R.id.nav_home) {
            return Screen.HOME;
        }
        if (itemId == R.id.nav_plan) {
            return Screen.PLAN;
        }
        if (itemId == R.id.nav_studies) {
            return Screen.INFO;
        }
        if (itemId == R.id.nav_grades) {
            return Screen.GRADES;
        }
        return null;
    }

    private static void restoreBottomNavSelection(NavigationBarView shellNav, String currentScreen) {
        if (shellNav == null) {
            return;
        }
        int itemId = menuItemForScreen(currentScreen);
        if (shellNav.getSelectedItemId() == itemId) {
            return;
        }
        suppressNavCallback = true;
        shellNav.setSelectedItemId(itemId);
        suppressNavCallback = false;
    }

    private static void navigateTo(AppCompatActivity activity, Screen target, String currentScreen) {
        dismissMoreSheet();

        if (activity instanceof MainShellActivity && isMainTab(target)) {
            ((MainShellActivity) activity).switchToTab(target, false);
            return;
        }

        Class<?> targetActivity;
        switch (target) {
            case HOME:
                targetActivity = HomeActivity.class;
                break;
            case INFO:
                targetActivity = InfoActivity.class;
                break;
            case FINANCE:
                targetActivity = FinanceActivity.class;
                break;
            case PLAN:
                targetActivity = PlanActivity.class;
                break;
            case GRADES:
                targetActivity = GradesActivity.class;
                break;
            case ATTENDANCE:
                targetActivity = AttendanceActivity.class;
                break;
            case NEWS:
                targetActivity = NewsActivity.class;
                break;
            case USEFUL:
                targetActivity = UsefulLinksActivity.class;
                break;
            case ABOUT:
                targetActivity = AboutActivity.class;
                break;
            default:
                return;
        }

        Intent intent = new Intent(activity, targetActivity);
        if (target == Screen.HOME) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        if (isMainTab(target)) {
            intent = MainShellActivity.createIntent(activity, target);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
        if (!Screen.HOME.getId().equals(currentScreen) && !(activity instanceof MainShellActivity)) {
            activity.finish();
            activity.overridePendingTransition(0, 0);
        }
    }

    private static void showMoreSheet(
            AppCompatActivity activity,
            NavigationBarView shellNav,
            String currentScreen) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        dismissMoreSheet();

        View content = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_more, null, false);

        TextView versionView = content.findViewById(R.id.moreAppVersion);
        if (versionView != null) {
            versionView.setText(activity.getString(R.string.app_version, BuildConfig.VERSION_NAME));
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(
                        activity, R.style.ThemeOverlay_ZUTnik_BottomSheetDialog);
        dialog.setContentView(content);
        dialog.setOnShowListener(d -> expandMoreSheetForDisplay(activity, dialog));
        dialog.setOnDismissListener(d -> {
            activeMoreSheet = null;
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                restoreBottomNavSelection(shellNav, currentScreen);
            }
        });
        activeMoreSheet = dialog;

        View.OnClickListener sheetListener = v -> {
            Screen target = null;
            int id = v.getId();
            if (id == R.id.moreLinkFinance) {
                target = Screen.FINANCE;
            } else if (id == R.id.moreLinkAttendance) {
                target = Screen.ATTENDANCE;
            } else if (id == R.id.moreLinkNews) {
                target = Screen.NEWS;
            } else if (id == R.id.moreLinkUseful) {
                target = Screen.USEFUL;
            } else if (id == R.id.moreLinkAbout) {
                target = Screen.ABOUT;
            } else if (id == R.id.moreLinkSettings) {
                activity.startActivity(new Intent(activity, SettingsActivity.class));
                dialog.dismiss();
                return;
            } else if (id == R.id.moreLinkLogout) {
                dialog.dismiss();
                showLogoutConfirmation(activity);
                return;
            }

            dialog.dismiss();
            if (target != null && !target.getId().equals(currentScreen)) {
                navigateTo(activity, target, currentScreen);
            }
        };

        bindMoreClick(content, R.id.moreLinkFinance, sheetListener);
        bindMoreClick(content, R.id.moreLinkAttendance, sheetListener);
        bindMoreClick(content, R.id.moreLinkNews, sheetListener);
        bindMoreClick(content, R.id.moreLinkUseful, sheetListener);
        bindMoreClick(content, R.id.moreLinkAbout, sheetListener);
        bindMoreClick(content, R.id.moreLinkSettings, sheetListener);
        bindMoreClick(content, R.id.moreLinkLogout, sheetListener);

        dialog.show();
    }

    private static void expandMoreSheetForDisplay(
            AppCompatActivity activity,
            com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        android.widget.FrameLayout bottomSheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) {
            return;
        }

        bottomSheet.setBackgroundResource(R.drawable.bg_more_sheet_root);
        bottomSheet.setClipToOutline(true);

        BottomSheetBehavior<android.widget.FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        Configuration config = activity.getResources().getConfiguration();
        boolean tabletLandscape = config.smallestScreenWidthDp >= 600
                && config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (tabletLandscape) {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int maxWidth = (int) (480f * metrics.density);
            int targetWidth = Math.min(metrics.widthPixels, maxWidth);
            android.view.ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
            if (lp != null) {
                lp.width = targetWidth;
                bottomSheet.setLayoutParams(lp);
            }
        }
    }

    private static void bindMoreClick(View root, int viewId, View.OnClickListener listener) {
        View view = root.findViewById(viewId);
        if (view != null) {
            view.setOnClickListener(listener);
        }
    }

    private static void doLogout(AppCompatActivity activity) {
        Context appContext = activity.getApplicationContext();
        ZutnikSession.clearSessionData(appContext);
        NotificationSyncManager.cancelWorker(appContext);

        Toast.makeText(activity, R.string.logout_success_message, Toast.LENGTH_SHORT).show();

        Intent i = new Intent(activity, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        activity.finish();
    }

    private static void showLogoutConfirmation(AppCompatActivity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setPositiveButton(R.string.logout_confirm_positive, (dialog, which) -> doLogout(activity))
                .setNegativeButton(R.string.logout_confirm_negative, null)
                .show();
    }
}
