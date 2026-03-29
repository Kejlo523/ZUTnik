package pl.kejlo.mzutv2;

import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Locks app rotation on phones while leaving tablets free to rotate.
 */
public abstract class PhoneAwareActivity extends AppCompatActivity {

    private static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (getResources().getConfiguration().smallestScreenWidthDp < TABLET_MIN_SMALLEST_WIDTH_DP) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
    }
}
