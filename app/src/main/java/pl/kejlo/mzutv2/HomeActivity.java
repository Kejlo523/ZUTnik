package pl.kejlo.mzutv2;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;

import com.google.android.material.navigation.NavigationView;

import java.util.List;
import java.util.ArrayList;

public class HomeActivity extends MzutBaseActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private android.widget.FrameLayout drawerContentRoot;

    private TextView textWelcome;
    private TextView textWelcomeSub;

    private LinearLayout homeHero;
    private LinearLayout homeSection;

    private TextView btnHeroPlan, btnHeroGrades;
    private androidx.cardview.widget.CardView indicatorOverlay;
    private android.widget.ImageView indicatorIcon;

    // New Grid
    private TileGridLayout tileGrid;
    private HomeRepository homeRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        EdgeToEdge.enable(this);

        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        if (session.getAuthKey() == null || session.getUserId() == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);
        indicatorOverlay = findViewById(R.id.indicatorOverlay);
        indicatorIcon = findViewById(R.id.indicatorIcon);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Setup Toolbar
        setSupportActionBar(toolbar);
        toolbar.setTitle(R.string.home_title);

        NavDrawerHelper.setupNavigation(
                this,
                drawerLayout,
                navigationView,
                toolbar,
                NavDrawerHelper.Screen.HOME);

        textWelcome = findViewById(R.id.textWelcome);
        textWelcomeSub = findViewById(R.id.textWelcomeSub);
        homeHero = findViewById(R.id.homeHero);
        homeSection = findViewById(R.id.homeSection);

        btnHeroPlan = findViewById(R.id.btnHeroPlan);
        btnHeroGrades = findViewById(R.id.btnHeroGrades);

        tileGrid = findViewById(R.id.tileGrid);

        setupWelcomeText();
        setupClicks();
        setupGrid();
        runIntroAnimations();

        // RESET: Clean implementation of "Swipe to Open".
        // We use a helper to detect a distinct "Swipe Right" gesture.
        // We do not mess with dragging or reflection to avoid visual glitches.
        gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                        if (e1 == null || e2 == null)
                            return false;

                        float deltaX = e2.getX() - e1.getX();
                        float deltaY = e2.getY() - e1.getY();

                        // Logic:
                        // 1. Swipe Right (deltaX > 0)
                        // 2. Horizontal separation significant (> 50px)
                        // 3. Horizontal velocity significant (> 100)
                        // 4. Horizontal motion dominant (deltaX > deltaY) - prevents opening on
                        // diagonal scroll
                        if (deltaX > 50 && velocityX > 100 && Math.abs(deltaX) > Math.abs(deltaY)) {
                            // BLOCK if in edit mode
                            if (tileGrid.isEditMode()) {
                                return false;
                            }
                            if (!drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                                return true;
                            }
                        }
                        return false;
                    }
                });
    }

    private android.view.GestureDetector gestureDetector;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Pass touch events to detector first
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(ev);
        }
        // Always pass to generic handler so clicks/scrolls still work
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void setupGrid() {
        homeRepository = new HomeRepository(this);
        List<Tile> tiles = homeRepository.loadTiles();

        tileGrid.setGap(dpToPx(8));
        tileGrid.setTiles(tiles);

        // Removed auto-save. Save is manual via menu.
        tileGrid.setOnTileClickListener(this::onTileClicked);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.home_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@androidx.annotation.NonNull android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_edit_home) {
            // This is the "Edit" / "Save" button
            // If we are currently in edit mode, this acts as SAVE.
            if (tileGrid.isEditMode()) {
                saveAndExitEditMode();
            } else {
                enterEditMode();
            }
            return true;
        } else if (item.getItemId() == R.id.action_add_tile) {
            showAddEditDialog(null);
            return true;
        } else if (item.getItemId() == R.id.action_cancel_edit) {
            cancelEditMode();
            return true;
        } else if (item.getItemId() == R.id.action_reset_defaults) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.home_reset_dialog_title)
                    .setMessage(R.string.home_reset_dialog_message)
                    .setPositiveButton(R.string.home_reset_dialog_yes, (d, w) -> {
                        tileGrid.setTiles(homeRepository.restoreDefaults());
                        tileGrid.setEditMode(false);
                        invalidateOptionsMenu();
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                        showActionIndicator(); // Replaces Toast
                    })
                    .setNegativeButton(R.string.home_reset_dialog_no, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void enterEditMode() {
        tileGrid.setEditMode(true);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

    }

    private void saveAndExitEditMode() {
        // Save changes
        homeRepository.saveTiles(tileGrid.getTiles());
        tileGrid.setEditMode(false);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        showActionIndicator(); // Replaces "Saved" toast
    }

    private void cancelEditMode() {
        // Revert changes by reloading
        tileGrid.setTiles(homeRepository.loadTiles());
        tileGrid.setEditMode(false);
        invalidateOptionsMenu();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        // No toast for cancel needed, visuals revert is enough.
        // Toast.makeText(this, R.string.home_cancel_toast, Toast.LENGTH_SHORT).show();
    }

    private void showActionIndicator() {
        if (indicatorOverlay == null)
            return;
        indicatorOverlay.setAlpha(0f);
        indicatorOverlay.setVisibility(View.VISIBLE);
        indicatorOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction(() -> {
                    indicatorOverlay.postDelayed(() -> {
                        indicatorOverlay.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction(() -> indicatorOverlay.setVisibility(View.GONE))
                                .start();
                    }, 1000);
                })
                .start();
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        boolean isEdit = tileGrid.isEditMode();

        android.view.MenuItem editItem = menu.findItem(R.id.action_edit_home);
        if (editItem != null) {
            editItem.setIcon(isEdit ? android.R.drawable.ic_menu_save : R.drawable.ic_edit_small);
            editItem.setTitle(isEdit ? R.string.menu_home_save : R.string.menu_home_edit);
        }

        android.view.MenuItem addItem = menu.findItem(R.id.action_add_tile);
        if (addItem != null) {
            addItem.setVisible(isEdit);
        }

        android.view.MenuItem cancelItem = menu.findItem(R.id.action_cancel_edit);
        if (cancelItem != null) {
            cancelItem.setVisible(isEdit);
        }

        android.view.MenuItem resetItem = menu.findItem(R.id.action_reset_defaults);
        if (resetItem != null) {
            resetItem.setVisible(isEdit);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    private void setupWelcomeText() {
        MzutSession s = MzutSession.getInstance(this);
        String username = s.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = s.getUserId();
        }
        if (username == null || username.trim().isEmpty()) {
            username = getString(R.string.nav_header_default_username);
        }

        textWelcome.setText(getString(R.string.home_welcome_message, username));
        textWelcomeSub.setText(R.string.home_welcome_subtitle);
    }

    private void setupClicks() {
        View.OnClickListener openPlan = v -> startActivity(new Intent(HomeActivity.this, PlanActivity.class));
        View.OnClickListener openGrades = v -> {
            try {
                startActivity(new Intent(HomeActivity.this, GradesActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, R.string.home_grades_not_available, Toast.LENGTH_SHORT).show();
            }
        };

        btnHeroPlan.setOnClickListener(openPlan);
        btnHeroGrades.setOnClickListener(openGrades);
    }

    private void runIntroAnimations() {
        homeHero.setAlpha(0f);
        tileGrid.setAlpha(0f);
        homeSection.setAlpha(0f);

        animateIn(homeHero, 100);
        animateIn(tileGrid, 200);
        animateIn(homeSection, 300);
    }

    private void animateIn(View v, long delayMs) {
        if (v == null) {
            return;
        }
        v.setTranslationY(100f);
        v.setScaleX(0.95f);
        v.setScaleY(0.95f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(400)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    public void onTileClicked(Tile tile) {
        if (tileGrid.isEditMode()) {
            showAddEditDialog(tile);
            return;
        }

        Intent intent = null;
        if (Tile.ACTION_URL.equals(tile.actionType)) {
            if (tile.actionData != null && !tile.actionData.isEmpty()) {
                try {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tile.actionData));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.home_open_url_error, Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            switch (tile.actionType) {
                case Tile.ACTION_PLAN:
                    intent = new Intent(this, PlanActivity.class);
                    break;
                case Tile.ACTION_GRADES:
                    intent = new Intent(this, GradesActivity.class);
                    break;
                case Tile.ACTION_INFO:
                    intent = new Intent(this, InfoActivity.class);
                    break;
                case Tile.ACTION_NEWS:
                    intent = new Intent(this, NewsActivity.class);
                    break;
                case Tile.ACTION_NEWS_LATEST:
                    intent = new Intent(this, NewsActivity.class);
                    intent.putExtra("EXTRA_OPEN_LATEST", true);
                    break;
                case Tile.ACTION_PLAN_SEARCH:
                    intent = new Intent(this, PlanActivity.class);
                    if (tile.actionData != null) {
                        try {
                            org.json.JSONObject o = new org.json.JSONObject(tile.actionData);
                            intent.putExtra("EXTRA_SEARCH_CATEGORY", o.optString("ck"));
                            intent.putExtra("EXTRA_SEARCH_QUERY", o.optString("q"));
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                case Tile.ACTION_ACTIVITY:
                    try {
                        if (tile.actionData != null) {
                            Class<?> cls = Class.forName(tile.actionData);
                            intent = new Intent(this, cls);
                        }
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                    break;
            }
        }

        if (intent != null) {
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.home_open_error, tile.title), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showAddEditDialog(Tile tile) {
        AddEditTileDialog dialog = AddEditTileDialog.newInstance(tile);
        dialog.setListener(savedTile -> {
            if (tile == null) {
                // Add new
                tileGrid.addTile(savedTile);
            } else {
                // Dialog modified 'tileToEdit' references if passed.
                // We should force grid refresh.
                tileGrid.refreshTileView(savedTile);
                tileGrid.requestLayout(); // Still needed for size/pos potentially
            }
        });
        dialog.show(getSupportFragmentManager(), "add_edit_tile");
    }
}