package pl.kejlo.zutnik;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Checks Google Play for app updates and shows a bottom banner in the main shell.
 * Uses the flexible in-app update flow so users can keep using the app while downloading.
 */
public final class AppUpdateHelper implements InstallStateUpdatedListener {

    private static final String PREFS = "app_update_helper";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until_ms";
    private static final long SNOOZE_MS = 3L * 24L * 60L * 60L * 1000L;

    private final AppCompatActivity activity;
    private final AppUpdateManager appUpdateManager;
    private final ActivityResultLauncher<IntentSenderRequest> updateLauncher;
    private final SharedPreferences prefs;

    @Nullable
    private View bannerRoot;
    @Nullable
    private TextView messageView;
    @Nullable
    private MaterialButton actionButton;
    @Nullable
    private View dismissButton;

    @Nullable
    private AppUpdateInfo pendingUpdateInfo;

    public AppUpdateHelper(
            @NonNull AppCompatActivity activity,
            @NonNull ActivityResultLauncher<IntentSenderRequest> updateLauncher) {
        this.activity = activity;
        this.updateLauncher = updateLauncher;
        this.appUpdateManager = AppUpdateManagerFactory.create(activity);
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public void bindBanner(@Nullable View root) {
        bannerRoot = root;
        if (root == null) {
            return;
        }
        messageView = root.findViewById(R.id.updateBannerMessage);
        actionButton = root.findViewById(R.id.updateBannerAction);
        dismissButton = root.findViewById(R.id.updateBannerDismiss);

        if (actionButton != null) {
            actionButton.setOnClickListener(v -> onActionClicked());
        }
        if (dismissButton != null) {
            dismissButton.setOnClickListener(v -> snoozeAndHide());
        }
    }

    public void onResume() {
        appUpdateManager.registerListener(this);
        checkForUpdates();
    }

    public void onPause() {
        appUpdateManager.unregisterListener(this);
    }

    public void onDestroy() {
        appUpdateManager.unregisterListener(this);
        bannerRoot = null;
        messageView = null;
        actionButton = null;
        dismissButton = null;
    }

    private void checkForUpdates() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            pendingUpdateInfo = info;

            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                showReadyToInstallBanner();
                return;
            }

            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    && info.installStatus() == InstallStatus.DOWNLOADING) {
                showDownloadingBanner();
                return;
            }

            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE
                    || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                hideBanner();
                return;
            }

            if (isSnoozed()) {
                hideBanner();
                return;
            }

            showAvailableBanner();
        });
    }

    @Override
    public void onStateUpdate(@NonNull InstallState state) {
        switch (state.installStatus()) {
            case InstallStatus.DOWNLOADING:
                showDownloadingBanner();
                break;
            case InstallStatus.DOWNLOADED:
                showReadyToInstallBanner();
                break;
            case InstallStatus.CANCELED:
            case InstallStatus.FAILED:
                if (!isSnoozed()) {
                    showAvailableBanner();
                } else {
                    hideBanner();
                }
                break;
            default:
                break;
        }
    }

    private void onActionClicked() {
        AppUpdateInfo info = pendingUpdateInfo;
        if (info == null) {
            return;
        }

        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            appUpdateManager.completeUpdate();
            return;
        }

        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE
                && info.updateAvailability() != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            return;
        }

        if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            return;
        }

        appUpdateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
    }

    private void showAvailableBanner() {
        if (bannerRoot == null) {
            return;
        }
        bannerRoot.setVisibility(View.VISIBLE);
        if (messageView != null) {
            messageView.setText(R.string.app_update_available);
        }
        if (actionButton != null) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(R.string.app_update_action);
            actionButton.setEnabled(true);
        }
        if (dismissButton != null) {
            dismissButton.setVisibility(View.VISIBLE);
        }
    }

    private void showDownloadingBanner() {
        if (bannerRoot == null) {
            return;
        }
        bannerRoot.setVisibility(View.VISIBLE);
        if (messageView != null) {
            messageView.setText(R.string.app_update_downloading);
        }
        if (actionButton != null) {
            actionButton.setVisibility(View.GONE);
        }
        if (dismissButton != null) {
            dismissButton.setVisibility(View.GONE);
        }
    }

    private void showReadyToInstallBanner() {
        if (bannerRoot == null) {
            return;
        }
        bannerRoot.setVisibility(View.VISIBLE);
        if (messageView != null) {
            messageView.setText(R.string.app_update_ready);
        }
        if (actionButton != null) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(R.string.app_update_restart);
            actionButton.setEnabled(true);
        }
        if (dismissButton != null) {
            dismissButton.setVisibility(View.GONE);
        }
    }

    private void snoozeAndHide() {
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply();
        hideBanner();
    }

    private boolean isSnoozed() {
        long snoozeUntil = prefs.getLong(KEY_SNOOZE_UNTIL, 0L);
        return snoozeUntil > System.currentTimeMillis();
    }

    private void hideBanner() {
        if (bannerRoot != null) {
            bannerRoot.setVisibility(View.GONE);
        }
    }
}
