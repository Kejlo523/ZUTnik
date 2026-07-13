package pl.kejlo.zutnik;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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

    private boolean listenerRegistered;
    private boolean updateFlowStarting;
    private boolean updateDownloaded;
    private boolean completionStarting;
    private int updateInfoRequestGeneration;

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
        if (!listenerRegistered) {
            appUpdateManager.registerListener(this);
            listenerRegistered = true;
        }
        checkForUpdates();
    }

    public void onPause() {
        if (listenerRegistered) {
            appUpdateManager.unregisterListener(this);
            listenerRegistered = false;
        }
        updateInfoRequestGeneration++;
        pendingUpdateInfo = null;
    }

    public void onDestroy() {
        if (listenerRegistered) {
            appUpdateManager.unregisterListener(this);
            listenerRegistered = false;
        }
        updateInfoRequestGeneration++;
        bannerRoot = null;
        messageView = null;
        actionButton = null;
        dismissButton = null;
    }

    private void checkForUpdates() {
        requestUpdateInfo(false);
    }

    private void requestUpdateInfo(boolean startWhenAvailable) {
        int requestGeneration = ++updateInfoRequestGeneration;
        pendingUpdateInfo = null;

        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(activity, info -> {
                    if (requestGeneration != updateInfoRequestGeneration) {
                        return;
                    }
                    handleUpdateInfo(info, startWhenAvailable);
                })
                .addOnFailureListener(activity, error -> {
                    if (requestGeneration != updateInfoRequestGeneration) {
                        return;
                    }
                    pendingUpdateInfo = null;
                    if (startWhenAvailable) {
                        updateFlowStarting = false;
                        showAvailableBanner();
                        showUpdateStartFailure();
                    }
                });
    }

    private void handleUpdateInfo(@NonNull AppUpdateInfo info, boolean startWhenAvailable) {
        int installStatus = info.installStatus();
        if (installStatus == InstallStatus.DOWNLOADED) {
            pendingUpdateInfo = info;
            updateDownloaded = true;
            updateFlowStarting = false;
            showReadyToInstallBanner();
            return;
        }

        if (installStatus == InstallStatus.PENDING
                || installStatus == InstallStatus.DOWNLOADING) {
            pendingUpdateInfo = info;
            updateDownloaded = false;
            updateFlowStarting = false;
            showDownloadingBanner();
            return;
        }

        if (installStatus == InstallStatus.INSTALLING) {
            pendingUpdateInfo = info;
            updateDownloaded = false;
            updateFlowStarting = false;
            showInstallingBanner();
            return;
        }

        if (installStatus == InstallStatus.INSTALLED) {
            pendingUpdateInfo = null;
            updateDownloaded = false;
            updateFlowStarting = false;
            completionStarting = false;
            hideBanner();
            return;
        }

        boolean updateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE;
        boolean flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE);
        if (!updateAvailable || !flexibleAllowed) {
            pendingUpdateInfo = null;
            updateDownloaded = false;
            updateFlowStarting = false;
            hideBanner();
            if (startWhenAvailable && updateAvailable) {
                showUpdateStartFailure();
            }
            return;
        }

        pendingUpdateInfo = info;
        updateDownloaded = false;
        if (startWhenAvailable) {
            startUpdateFlow(info, false);
        } else if (isSnoozed()) {
            updateFlowStarting = false;
            hideBanner();
        } else {
            updateFlowStarting = false;
            showAvailableBanner();
        }
    }

    @Override
    public void onStateUpdate(@NonNull InstallState state) {
        switch (state.installStatus()) {
            case InstallStatus.PENDING:
            case InstallStatus.DOWNLOADING:
                updateDownloaded = false;
                updateFlowStarting = false;
                showDownloadingBanner();
                break;
            case InstallStatus.DOWNLOADED:
                updateDownloaded = true;
                updateFlowStarting = false;
                showReadyToInstallBanner();
                break;
            case InstallStatus.INSTALLING:
                updateDownloaded = false;
                updateFlowStarting = false;
                showInstallingBanner();
                break;
            case InstallStatus.INSTALLED:
                updateDownloaded = false;
                updateFlowStarting = false;
                completionStarting = false;
                hideBanner();
                break;
            case InstallStatus.CANCELED:
            case InstallStatus.FAILED:
                pendingUpdateInfo = null;
                updateDownloaded = false;
                updateFlowStarting = false;
                completionStarting = false;
                if (state.installStatus() == InstallStatus.FAILED) {
                    showUpdateStartFailure();
                }
                requestUpdateInfo(false);
                break;
            default:
                break;
        }
    }

    public void onUpdateFlowResult(int resultCode) {
        updateFlowStarting = false;
        pendingUpdateInfo = null;

        if (resultCode == Activity.RESULT_OK) {
            showDownloadingBanner();
        } else {
            showAvailableBanner();
            if (resultCode
                    == com.google.android.play.core.install.model.ActivityResult.RESULT_IN_APP_UPDATE_FAILED) {
                showUpdateStartFailure();
            }
        }

        requestUpdateInfo(false);
    }

    private void onActionClicked() {
        if (updateFlowStarting || completionStarting) {
            return;
        }

        if (updateDownloaded) {
            completeDownloadedUpdate();
            return;
        }

        AppUpdateInfo info = pendingUpdateInfo;
        if (info == null
                || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE
                || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            requestFreshInfoAndStart();
            return;
        }

        startUpdateFlow(info, true);
    }

    private void requestFreshInfoAndStart() {
        updateFlowStarting = true;
        showStartingBanner();
        requestUpdateInfo(true);
    }

    private void startUpdateFlow(@NonNull AppUpdateInfo info, boolean retryWithFreshInfo) {
        updateFlowStarting = true;
        pendingUpdateInfo = null;
        showStartingBanner();

        boolean started;
        try {
            started = appUpdateManager.startUpdateFlowForResult(
                    info,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
        } catch (RuntimeException error) {
            started = false;
        }

        if (started) {
            return;
        }

        updateFlowStarting = false;
        if (retryWithFreshInfo) {
            requestFreshInfoAndStart();
        } else {
            showAvailableBanner();
            showUpdateStartFailure();
        }
    }

    private void completeDownloadedUpdate() {
        completionStarting = true;
        showInstallingBanner();
        appUpdateManager.completeUpdate().addOnFailureListener(activity, error -> {
            completionStarting = false;
            updateDownloaded = true;
            showReadyToInstallBanner();
            showUpdateStartFailure();
        });
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

    private void showStartingBanner() {
        if (bannerRoot == null) {
            return;
        }
        bannerRoot.setVisibility(View.VISIBLE);
        if (messageView != null) {
            messageView.setText(R.string.app_update_starting);
        }
        if (actionButton != null) {
            actionButton.setVisibility(View.GONE);
        }
        if (dismissButton != null) {
            dismissButton.setVisibility(View.GONE);
        }
    }

    private void showInstallingBanner() {
        if (bannerRoot == null) {
            return;
        }
        bannerRoot.setVisibility(View.VISIBLE);
        if (messageView != null) {
            messageView.setText(R.string.app_update_installing);
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

    private void showUpdateStartFailure() {
        Toast.makeText(activity, R.string.app_update_start_failed, Toast.LENGTH_SHORT).show();
    }

    private void hideBanner() {
        if (bannerRoot != null) {
            bannerRoot.setVisibility(View.GONE);
        }
    }
}
