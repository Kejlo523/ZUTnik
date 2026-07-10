package pl.kejlo.zutnik;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Shared activity behavior for phones and larger screens.
 */
public abstract class PhoneAwareActivity extends AppCompatActivity {

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.screen_pop_enter, R.anim.screen_pop_exit);
    }
}
