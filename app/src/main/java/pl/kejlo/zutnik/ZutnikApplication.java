package pl.kejlo.zutnik;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class ZutnikApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private static final long BACKGROUND_LOCK_DELAY_MS = 700L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int startedActivities;
    private boolean changingConfiguration;
    private final Runnable backgroundLock = () -> {
        if (startedActivities == 0 && !changingConfiguration && PrivacyManager.isEnabled(this)) {
            PrivacyManager.lock();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        if (PrivacyManager.isEnabled(this)) {
            PrivacyManager.lock();
        } else {
            PrivacyManager.markUnlocked();
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        handler.removeCallbacks(backgroundLock);
        if (startedActivities == 0) {
            changingConfiguration = false;
        }
        startedActivities++;
    }

    @Override
    public void onActivityStopped(Activity activity) {
        changingConfiguration = activity.isChangingConfigurations();
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0 && !changingConfiguration) {
            handler.postDelayed(backgroundLock, BACKGROUND_LOCK_DELAY_MS);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
