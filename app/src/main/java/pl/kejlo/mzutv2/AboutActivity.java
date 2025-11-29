package pl.kejlo.mzutv2;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);

        toolbar.setTitle(R.string.about_title);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.ABOUT
        );
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Only observe the gesture, do not block the event
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }
}
