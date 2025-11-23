package pl.kejlo.mzutv2;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

public class AboutActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        drawerLayout   = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar        = findViewById(R.id.toolbar);

        toolbar.setTitle("O aplikacji");

        // podpinamy nav drawer z identyfikatorem ekranu "about"
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "about");
    }
}
