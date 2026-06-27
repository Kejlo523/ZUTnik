package pl.kejlo.mzutv2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainNavHelper {

    private static final String TAG = "ZUTnik-MainNav";
    private static boolean suppressNavCallback;
    private static com.google.android.material.bottomsheet.BottomSheetDialog activeMoreSheet;

    public enum Screen {
        HOME("home"),
        INFO("info"),
        FINANCE("finance"),
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

    public static boolean isMainTab(Screen screen) {
        return screen == Screen.HOME
                || screen == Screen.PLAN
                || screen == Screen.INFO
                || screen == Screen.GRADES;
    }

    private static boolean handleMoreNavClick(
            AppCompatActivity activity,
            BottomNavigationView bottomNav,
            String currentScreen) {
        if (activeMoreSheet != null && activeMoreSheet.isShowing()) {
            activeMoreSheet.dismiss();
            return true;
        }
        showMoreSheet(activity, bottomNav, currentScreen);
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
            BottomNavigationView bottomNav) {
        if (bottomNav == null) {
            return;
        }
        applyWindowInsets(contentRoot, bottomNav);

        suppressNavCallback = true;
        bottomNav.setSelectedItemId(menuItemForScreen(activity.getCurrentTabId()));
        suppressNavCallback = false;

        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) {
                return true;
            }
            int itemId = item.getItemId();
            if (itemId == R.id.nav_more) {
                return handleMoreNavClick(activity, bottomNav, activity.getCurrentTabId());
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

        bottomNav.setOnItemReselectedListener(item -> {
            if (suppressNavCallback) {
                return;
            }
            if (item.getItemId() == R.id.nav_more) {
                handleMoreNavClick(activity, bottomNav, activity.getCurrentTabId());
            }
        });
    }

    public static void updateShellSelection(BottomNavigationView bottomNav, String currentScreen) {
        if (bottomNav == null) {
            return;
        }
        int itemId = menuItemForScreen(currentScreen);
        if (bottomNav.getSelectedItemId() == itemId) {
            return;
        }
        suppressNavCallback = true;
        bottomNav.setSelectedItemId(itemId);
        suppressNavCallback = false;
    }

    public static void setup(
            AppCompatActivity activity,
            View contentRoot,
            BottomNavigationView bottomNav,
            Toolbar toolbar,
            Screen currentScreen) {
        String currentScreenId = currentScreen != null ? currentScreen.getId() : null;
        setup(activity, contentRoot, bottomNav, toolbar, currentScreenId);
    }

    @SuppressLint("StringFormatInvalid")
    public static void setup(
            AppCompatActivity activity,
            View contentRoot,
            BottomNavigationView bottomNav,
            Toolbar toolbar,
            String currentScreen) {
        if (bottomNav == null) {
            return;
        }

        if (toolbar != null) {
            activity.setSupportActionBar(toolbar);
        }
        styleToolbar(activity, toolbar);
        applyWindowInsets(contentRoot, bottomNav);

        suppressNavCallback = true;
        bottomNav.setSelectedItemId(menuItemForScreen(currentScreen));
        suppressNavCallback = false;

        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) {
                return true;
            }
            int itemId = item.getItemId();
            if (itemId == R.id.nav_more) {
                return handleMoreNavClick(activity, bottomNav, currentScreen);
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

        bottomNav.setOnItemReselectedListener(item -> {
            if (suppressNavCallback) {
                return;
            }
            if (item.getItemId() == R.id.nav_more) {
                handleMoreNavClick(activity, bottomNav, currentScreen);
            }
        });
    }

    public static void setBottomNavVisible(BottomNavigationView bottomNav, boolean visible) {
        if (bottomNav == null) {
            return;
        }
        bottomNav.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void styleToolbar(AppCompatActivity activity, Toolbar toolbar) {
        styleToolbarPublic(activity, toolbar);
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

    private static void applyWindowInsets(View contentRoot, BottomNavigationView bottomNav) {
        if (contentRoot != null) {
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
                        baseBottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(contentRoot);
        }

        if (bottomNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(bottomNav);
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

    private static void restoreBottomNavSelection(BottomNavigationView bottomNav, String currentScreen) {
        if (bottomNav == null) {
            return;
        }
        int itemId = menuItemForScreen(currentScreen);
        if (bottomNav.getSelectedItemId() == itemId) {
            return;
        }
        suppressNavCallback = true;
        bottomNav.setSelectedItemId(itemId);
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
            BottomNavigationView bottomNav,
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
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        dialog.setContentView(content);
        dialog.setOnDismissListener(d -> {
            activeMoreSheet = null;
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                restoreBottomNavSelection(bottomNav, currentScreen);
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
            } else if (id == R.id.moreLinkWatch) {
                dialog.dismiss();
                handleWatchClick(activity);
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
        bindMoreClick(content, R.id.moreLinkWatch, sheetListener);
        bindMoreClick(content, R.id.moreLinkLogout, sheetListener);

        dialog.show();
    }

    private static void bindMoreClick(View root, int viewId, View.OnClickListener listener) {
        View view = root.findViewById(viewId);
        if (view != null) {
            view.setOnClickListener(listener);
        }
    }

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

    private static void showLogoutConfirmation(AppCompatActivity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setPositiveButton(R.string.logout_confirm_positive, (dialog, which) -> doLogout(activity))
                .setNegativeButton(R.string.logout_confirm_negative, null)
                .show();
    }

    private static void handleWatchClick(AppCompatActivity activity) {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_watch_status, null, false);

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

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();

        pl.kejlo.mzutv2.wear.WearSyncManager.getWatchStatusWithPingAsync(activity, status -> {
            if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) {
                return;
            }

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

            if (!isConnected && !isPaired) {
                titleView.setText(R.string.nav_watch_not_found_title);
                subtitleView.setText(R.string.nav_watch_not_found_msg);
                nameView.setVisibility(View.GONE);
                disconnectedHint.setVisibility(View.GONE);
                batteryCard.setVisibility(View.GONE);
                infoContainer.setVisibility(View.GONE);
                return;
            }

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
                batteryView.setText(activity.getString(R.string.nav_watch_status_battery, battery));
                ringPercent.setText(activity.getString(R.string.common_percent_format, battery));
                ring.setProgress(battery);
            } else {
                batteryView.setText(R.string.nav_watch_info_last_sync_none);
                ringPercent.setText(R.string.common_percent_placeholder);
                ring.setProgress(0);
            }

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
                lastSyncValue.setText(DateUtils.getRelativeTimeSpanString(
                        lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
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
                tileValue.setText(activity.getString(
                        R.string.nav_watch_info_tile_active,
                        DateUtils.getRelativeTimeSpanString(tileSeen, now, DateUtils.MINUTE_IN_MILLIS)));
            } else {
                tileValue.setText(R.string.nav_watch_info_tile_missing);
            }

            pl.kejlo.mzutv2.wear.WearSyncManager.checkIfWatchMissingApp(activity, missingNodeId -> {
                if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) {
                    return;
                }
                if (missingNodeId != null) {
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
            android.bluetooth.BluetoothManager manager =
                    (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            return adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }
}
