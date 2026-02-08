package pl.kejlo.mzutv2.wear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main WearOS activity for MZUT.
 * Vertical list with snapping, Rotary for horizontal navigation.
 */
public class MainWearActivity extends Activity {

    private static final String TAG = "MZUTWearSync/WEAR";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Views
    private FrameLayout rootContainer;
    private LinearLayout dayIndicator;
    private FrameLayout pageContainer;
    private LinearLayout dayPageContent;
    private LinearLayout syncPageContent;
    private TextView dayTitle;
    private RecyclerView eventsList;
    
    // Sync views
    private TextView syncTitle;
    private TextView syncDesc;
    private LinearLayout syncStatusCard;
    private TextView syncStatus;
    private ProgressBar syncProgress;
    private Button btnSync;

    // State
    private int currentPageIndex = 0;
    private int totalDays = 0;
    private pl.kejlo.mzutv2.wear.model.WearPlanSnapshot currentSnap;
    private boolean isNavigating = false;

    // Theme colors
    private int themeBg = 0xFF0B1020;
    private int themeCard = 0xFF1A2235;
    private int themeText = 0xFFFFFFFF;
    private int themeMuted = 0xFFB0B7C3;
    private int themeSubtle = 0xFF8F96A3;
    private int themeAccent = 0xFF4F8DFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = () -> {
        publishWatchStatus();
        handler.postDelayed(this.statusRunnable, 5 * 60 * 1000);
    };

    private final BroadcastReceiver snapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SNAPSHOT_UPDATED.equals(action)) {
                updateUI();
                if (syncPageContent != null && syncPageContent.getVisibility() == View.VISIBLE) {
                    if (syncStatus != null) syncStatus.setText("Pobrano pomyślnie");
                    if (syncProgress != null) syncProgress.setVisibility(View.GONE);
                    if (btnSync != null) btnSync.setEnabled(true);
                }
            } else if (pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SYNC_PROGRESS.equals(action)) {
                // Let updateUI or showSyncPage handle progress if needed, or just update valid views
                if (syncPageContent != null && syncPageContent.getVisibility() == View.VISIBLE) {
                    showSyncPage(); // Refreshes status from store
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        buildUI();
        setupRotaryInput();
        applyRoundInsets();
        updateUI();
    }

    private void buildUI() {
        // Root container
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(themeBg);
        setContentView(rootContainer);

        // Day indicator at top
        dayIndicator = new LinearLayout(this);
        dayIndicator.setOrientation(LinearLayout.HORIZONTAL);
        dayIndicator.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        indicatorLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        indicatorLp.topMargin = dp(6);
        rootContainer.addView(dayIndicator, indicatorLp);

        // Page container
        pageContainer = new FrameLayout(this);
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        pageLp.topMargin = dp(16); // Reduced to minimize top space but clear dots
        rootContainer.addView(pageContainer, pageLp);

        // Day page
        buildDayPage();
        
        // Sync page
        buildSyncPage();
    }

    private void buildDayPage() {
        dayPageContent = new LinearLayout(this);
        dayPageContent.setOrientation(LinearLayout.VERTICAL);
        dayPageContent.setGravity(Gravity.CENTER_HORIZONTAL);
        pageContainer.addView(dayPageContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Day title
        dayTitle = new TextView(this);
        dayTitle.setTextSize(12f);
        dayTitle.setTextColor(themeText);
        dayTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(4);
        dayPageContent.addView(dayTitle, titleLp);

        // Events RecyclerView
        eventsList = new RecyclerView(this);
        eventsList.setLayoutManager(new LinearLayoutManager(this));
        eventsList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        eventsList.setClipToPadding(false); // Important for centering
        
        // Snapping
        new TopSnapHelper().attachToRecyclerView(eventsList);
        
        // Scaling listener
        eventsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                applyScaling();
            }
        });
        
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT);
        dayPageContent.addView(eventsList, listLp);
    }

    private void buildSyncPage() {
        syncPageContent = new LinearLayout(this);
        syncPageContent.setOrientation(LinearLayout.VERTICAL);
        syncPageContent.setGravity(Gravity.CENTER);
        syncPageContent.setPadding(dp(18), dp(12), dp(18), dp(12));
        syncPageContent.setVisibility(View.GONE);
        pageContainer.addView(syncPageContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        syncTitle = new TextView(this);
        syncTitle.setText(R.string.wear_main_title);
        syncTitle.setTextSize(13f);
        syncTitle.setTextColor(themeText);
        syncTitle.setGravity(Gravity.CENTER);
        syncPageContent.addView(syncTitle);

        syncDesc = new TextView(this);
        syncDesc.setText(R.string.wear_main_desc);
        syncDesc.setTextSize(10f);
        syncDesc.setTextColor(themeMuted);
        syncDesc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(4);
        syncPageContent.addView(syncDesc, descLp);

        syncStatusCard = new LinearLayout(this);
        syncStatusCard.setOrientation(LinearLayout.VERTICAL);
        syncStatusCard.setGravity(Gravity.CENTER);
        syncStatusCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(themeCard);
        cardBg.setCornerRadius(dp(10));
        syncStatusCard.setBackground(cardBg);
        syncStatusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        syncPageContent.addView(syncStatusCard, cardLp);

        syncStatus = new TextView(this);
        syncStatus.setTextSize(10f);
        syncStatus.setTextColor(themeText);
        syncStatus.setGravity(Gravity.CENTER);
        syncStatusCard.addView(syncStatus);

        syncProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        syncProgress.setMax(100);
        syncProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        progLp.topMargin = dp(6);
        syncStatusCard.addView(syncProgress, progLp);

        btnSync = new Button(this);
        btnSync.setText(R.string.wear_main_sync);
        btnSync.setTextColor(0xFFFFFFFF);
        btnSync.setTextSize(12f);
        btnSync.setAllCaps(false);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(themeAccent);
        btnBg.setCornerRadius(dp(20));
        btnSync.setBackground(btnBg);
        btnSync.setOnClickListener(v -> requestSync());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        btnLp.topMargin = dp(12);
        syncPageContent.addView(btnSync, btnLp);
    }

    private void setupRotaryInput() {
        rootContainer.setFocusableInTouchMode(true);
        rootContainer.requestFocus();
        
        final float scrollFactor = ViewConfiguration.get(this).getScaledVerticalScrollFactor();
        final float[] accumulatedRotation = {0f};
        final float ROTATION_THRESHOLD = 50f;
        
        View.OnGenericMotionListener rotaryListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL
                    && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                
                float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor;
                accumulatedRotation[0] += delta;
                
                if (Math.abs(accumulatedRotation[0]) > ROTATION_THRESHOLD && !isNavigating) {
                    // Check if horizontal navigation logic applies
                    int direction = accumulatedRotation[0] > 0 ? 1 : -1;
                    accumulatedRotation[0] = 0;
                    
                    // Consume event for navigation
                    navigateWithAnimation(direction);
                    return true;
                }
                
                // If not navigating, let it bubble (maybe relevant for list scroll?)
                // Actually, standard RecyclerView handles rotary by itself usually?
                // But we return true to consume it if we want custom nav.
                // If we want RecyclerView to scroll vertically, we should return false.
                // WEAR UX: Rotary usually scrolls list. User wants rotary to SWITCH DAYS.
                // So we always return true to consume and navigate days.
                // "Palcem można góra dół przewijać" -> Finger for vertical scroll.
                // "Tarcze zegarka obrotowe ruch lewo prawo" -> Rotary for horizontal scroll.
                return true; 
            }
            return false;
        };

        rootContainer.setOnGenericMotionListener(rotaryListener);
        // Also attach to list so it doesn't steal focus and handle scroll itself
        if (eventsList != null) {
            eventsList.setOnGenericMotionListener(rotaryListener);
        }
    }

    // Animation logic
    private void navigateWithAnimation(int direction) {
        int nextIndex = currentPageIndex + direction;
        int maxPage = totalDays;
        
        if (nextIndex < 0 || nextIndex > maxPage) return;
        
        isNavigating = true;
        
        // Slide OUT
        float translationDest = direction > 0 ? -dp(50) : dp(50); // Move left if going right, etc
        
        View activeView = (currentPageIndex >= totalDays) ? syncPageContent : dayPageContent;
        if (activeView.getVisibility() != View.VISIBLE) activeView = dayPageContent; // Fallback
        
        activeView.animate()
                .translationX(translationDest)
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    currentPageIndex = nextIndex;
                    renderCurrentPage();
                    
                    // Prepare slide IN
                    View newView = (currentPageIndex >= totalDays) ? syncPageContent : dayPageContent;
                    newView.setTranslationX(-translationDest);
                    newView.setAlpha(0f);
                    
                    newView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(150)
                            .withEndAction(() -> isNavigating = false)
                            .start();
                })
                .start();
    }

    private void renderCurrentPage() {
        renderDayIndicator();
        
        if (currentPageIndex >= totalDays) {
            showSyncPage();
        } else {
            showDayPage();
        }
    }

    private void showDayPage() {
        if (dayPageContent != null) dayPageContent.setVisibility(View.VISIBLE);
        if (syncPageContent != null) syncPageContent.setVisibility(View.GONE);
        
        if (currentSnap == null || currentSnap.weekDays == null || 
            currentPageIndex >= currentSnap.weekDays.size()) {
            return;
        }

        var day = currentSnap.weekDays.get(currentPageIndex);

        if (dayTitle != null) {
            dayTitle.setText(day.dateLabel != null ? day.dateLabel : "");
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM"));
            boolean isToday = day.dateLabel != null && day.dateLabel.contains(today);
            dayTitle.setTextColor(isToday ? themeAccent : themeText);
        }

        // Update List
        if (eventsList != null) {
            List<EventItem> items = new ArrayList<>();
            if (day.events != null) {
                for (var ev : day.events) {
                    items.add(new EventItem(ev.title, ev.time, ev.room, ev.color));
                }
            }
            if (items.isEmpty()) {
                items.add(new EventItem(getString(R.string.tile_no_events), null, null, 0));
            }
            eventsList.setAdapter(new EventAdapter(items));
            
            // Wait for layout to center first item or scroll to 0
            eventsList.scrollToPosition(0);
            eventsList.post(this::applyScaling);
        }
    }
    
    // ... showSyncPage same as before ...
    private void showSyncPage() {
        if (dayPageContent != null) dayPageContent.setVisibility(View.GONE);
        if (syncPageContent != null) syncPageContent.setVisibility(View.VISIBLE);
        
        String statusText = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.getStatus(this);
        int progressValue = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.getProgress(this);
        boolean busy = progressValue > 0 && progressValue < 100;

        if (syncStatus != null) {
            if (statusText != null && !statusText.isEmpty()) {
                syncStatus.setText(statusText);
            } else if (currentSnap != null && currentSnap.loginRequired) {
                syncStatus.setText(R.string.wear_main_status_login_required);
            } else {
                syncStatus.setText(R.string.wear_main_status_idle);
            }
        }
        
        if (syncStatusCard != null) syncStatusCard.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (syncProgress != null) {
            syncProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            syncProgress.setProgress(progressValue);
        }
        if (btnSync != null) {
            btnSync.setEnabled(!busy);
            btnSync.setAlpha(busy ? 0.6f : 1f);
        }
    }

    private void renderDayIndicator() {
        if (dayIndicator == null) return;
        
        int totalPages = totalDays + 1;
        if (totalPages <= 1) {
            dayIndicator.removeAllViews();
            return;
        }

        int windowSize = Math.min(totalPages, 3);
        int start = Math.max(0, currentPageIndex - 1);
        if (start + windowSize > totalPages) start = totalPages - windowSize;
        int end = start + windowSize - 1;

        // Rebuild if count differs (simple approach, or optimize later)
        if (dayIndicator.getChildCount() != windowSize) {
            dayIndicator.removeAllViews();
            for (int i = 0; i < windowSize; i++) {
                View dot = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
                lp.setMargins(dp(3), 0, dp(3), 0);
                // Background shape
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(themeSubtle);
                dot.setBackground(bg);
                dayIndicator.addView(dot, lp);
            }
        }

        // Animate state
        for (int i = 0; i < windowSize; i++) {
            View dot = dayIndicator.getChildAt(i);
            int logicalIndex = start + i; // The page index this dot represents
            boolean isActive = (logicalIndex == currentPageIndex);
            
            int targetSize = isActive ? dp(8) : dp(6);
            int targetColor = isActive ? themeAccent : themeSubtle;
            
            // Animate layout params (size) - difficult smoothly without Transition, 
            // but we can scale.
            float targetScale = isActive ? 1.3f : 1.0f;
            
            dot.animate().cancel();
            dot.animate()
               .scaleX(targetScale)
               .scaleY(targetScale)
               .setDuration(200)
               .start();

            // Color animation
            GradientDrawable bg = (GradientDrawable) dot.getBackground();
            // bg.setColor() is instant. For animation we'd need ValueAnimator / ArgbEvaluator.
            // Keeping it simple for now, but apply color immediately.
            bg.setColor(targetColor); 
            // Ideally use color filter or animator
        }
    }

    private void applyScaling() {
        if (eventsList == null) return;
        
        int parentHeight = eventsList.getHeight();
        if (parentHeight == 0) return;

        // Focal point is near the top (where items snap)
        // Item center should be approx at paddingTop + halfItemHeight.
        // Focal point matches new tight top padding
        int paddingTop = eventsList.getPaddingTop();
        int focalCenterY = paddingTop + dp(40); // Approx center of first item
        // If first item is highlighted, it should be fully opaque/scaled at this point.
        
        // Range for scaling effect
        float maxDist = parentHeight * 0.4f;

        for (int i = 0; i < eventsList.getChildCount(); i++) {
            View child = eventsList.getChildAt(i);
            if (child == null) continue;
            
            int childCenterY = (child.getTop() + child.getBottom()) / 2;
            float dist = Math.abs(childCenterY - focalCenterY);
            
            float scale = 1f;
            float alpha = 1f;
            
            if (dist < maxDist) {
                float norm = dist / maxDist; 
                // Sharper curve?
                scale = 1f - (norm * 0.25f); // Max shrink to 0.75
                alpha = 1f - (norm * 0.5f);
            } else {
                scale = 0.75f;
                alpha = 0.5f;
            }
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);
        }
    }

    private void applyRoundInsets() {
        boolean isRound = getResources().getConfiguration().isScreenRound();
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        
        // Asymmetric padding to force snapping to Top
        // Asymmetric padding to force snapping to Top
        // Top padding: minimized to remove "pusta przestrzeń"
        // Title is outside (above) the list, so list padding can be small.
        int paddingVerticalTop = dp(4); 
        int paddingVerticalBottom = (int) (screenHeight * 0.55f); 
        
        if (eventsList != null) {
            eventsList.setPadding(0, paddingVerticalTop, 0, paddingVerticalBottom);
        }

        if (isRound) {
            int inset = (int) (screenWidth * 0.1f);
            if (dayPageContent != null) {
                // Apply horizontal padding to list
                 eventsList.setPadding(inset, paddingVerticalTop, inset, paddingVerticalBottom);
            }
            if (syncPageContent != null) syncPageContent.setPadding(inset, dp(8), inset, inset);
        }
    }

    private void updateUI() {
        currentSnap = pl.kejlo.mzutv2.wear.sync.WearSnapshotStore.load(this);
        applyThemeFromSnapshot(currentSnap);
        boolean wasSyncPage = (currentPageIndex >= totalDays && totalDays > 0);
        int oldDayIndex = currentPageIndex < totalDays ? currentPageIndex : 0;

        if (currentSnap != null && currentSnap.weekDays != null && !currentSnap.weekDays.isEmpty()) {
            totalDays = currentSnap.weekDays.size();
            if (wasSyncPage) {
                currentPageIndex = totalDays;
            } else {
                if (oldDayIndex < totalDays) {
                    currentPageIndex = oldDayIndex;
                } else {
                    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM"));
                    currentPageIndex = 0;
                    for (int i = 0; i < currentSnap.weekDays.size(); i++) {
                        var day = currentSnap.weekDays.get(i);
                        if (day.dateLabel != null && day.dateLabel.contains(today)) {
                            currentPageIndex = i;
                            break;
                        }
                    }
                }
            }
        } else {
            totalDays = 0;
            currentPageIndex = 0;
        }
        renderCurrentPage();
    }
    
    // ... helpers ...
    private void applyThemeFromSnapshot(pl.kejlo.mzutv2.wear.model.WearPlanSnapshot snap) {
        if (snap == null) return;
        if (snap.colorBg != 0) themeBg = snap.colorBg;
        if (snap.colorCard != 0) themeCard = snap.colorCard;
        if (snap.colorText != 0) themeText = snap.colorText;
        if (snap.colorMuted != 0) themeMuted = snap.colorMuted;
        if (snap.colorSubtle != 0) themeSubtle = snap.colorSubtle;
        if (snap.colorAccent != 0) themeAccent = snap.colorAccent;

        if (rootContainer != null) rootContainer.setBackgroundColor(themeBg);
        if (dayTitle != null) dayTitle.setTextColor(themeText);
        if (syncTitle != null) syncTitle.setTextColor(themeText);
        // ... rest same ...
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    
    // ... onStart/onStop ...
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SNAPSHOT_UPDATED);
        filter.addAction(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.ACTION_SYNC_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(snapshotReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(snapshotReceiver, filter);
        }
        handler.post(statusRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(snapshotReceiver);
        handler.removeCallbacks(statusRunnable);
    }

    // ... publishWatchStatus, requestSync ...
    private void publishWatchStatus() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            String deviceName = Build.MODEL != null ? Build.MODEL : getString(R.string.wear_device_default_name);
            PutDataMapRequest req = PutDataMapRequest.create(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_WATCH_STATUS);
            DataMap map = req.getDataMap();
            map.putInt(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_BATTERY, level);
            map.putString(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_DEVICE_NAME, deviceName);
            map.putLong(pl.kejlo.mzutv2.wear.sync.WearSyncConstants.KEY_TIMESTAMP, System.currentTimeMillis());
            Wearable.getDataClient(this).putDataItem(req.asPutDataRequest());
        } catch (Exception e) { Log.e(TAG, "fail", e); }
    }
    
    private void requestSync() {
        executor.execute(() -> {
            try {
                // ... same logic as before ...
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes == null || nodes.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.wear_main_no_phone, Toast.LENGTH_SHORT).show());
                    return;
                }
                runOnUiThread(() -> {
                   if (syncStatus != null) syncStatus.setText(R.string.wear_main_status_syncing);
                   // ...
                });
                for (Node n : nodes) {
                   Wearable.getMessageClient(this).sendMessage(n.getId(),
                       pl.kejlo.mzutv2.wear.sync.WearSyncConstants.PATH_REQUEST_SYNC, new byte[0]);
                }
                // ...
            } catch (Exception e) {}
        });
    }

    // Custom Spot for SnapHelper
    private static class TopSnapHelper extends LinearSnapHelper {
        @Override
        public int[] calculateDistanceToFinalSnap(@NonNull RecyclerView.LayoutManager layoutManager, @NonNull View targetView) {
            int[] out = new int[2];
            int viewTop = layoutManager.getDecoratedTop(targetView);
            int snapTop = layoutManager.getPaddingTop(); 
            out[1] = viewTop - snapTop;
            out[0] = 0;
            return out;
        }

        @Override
        public View findSnapView(RecyclerView.LayoutManager layoutManager) {
            if (!(layoutManager instanceof LinearLayoutManager)) return null;
            LinearLayoutManager llm = (LinearLayoutManager) layoutManager;
            
            int snapTop = llm.getPaddingTop();
            int minDist = Integer.MAX_VALUE;
            View closest = null;
            
            int childCount = llm.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = llm.getChildAt(i);
                if (child == null) continue;
                int top = llm.getDecoratedTop(child);
                int dist = Math.abs(top - snapTop);
                if (dist < minDist) {
                    minDist = dist;
                    closest = child;
                }
            }
            return closest;
        }
    }

    // Adapter for RecyclerView
    private static class EventItem {
        String title, time, room;
        int color;
        EventItem(String t, String ti, String r, int c) { title=t; time=ti; room=r; color=c; }
    }

    private class EventAdapter extends RecyclerView.Adapter<EventAdapter.ViewHolder> {
        private final List<EventItem> items;
        EventAdapter(List<EventItem> items) { this.items = items; }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MainWearActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(4), dp(4), dp(4)); // Reduced to 4dp (very tight)
            
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(themeCard);
            bg.setCornerRadius(dp(16)); // Slightly smaller radius
            row.setBackground(bg);
            
            // Layout params for RecyclerView
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 0); // Reset here
            row.setLayoutParams(lp);
            return new ViewHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            EventItem item = items.get(position);
            LinearLayout row = (LinearLayout) holder.itemView;
            
            // Dynamic margins
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) row.getLayoutParams();
            if (position == 0) {
                // "ten wyróżniony jeszcze bardziej w dół przesuń" -> Push first item down using MARGIN, not global padding
                lp.setMargins(0, dp(40), 0, dp(1)); 
            } else {
                // "zmniejsz odstępy" -> Gap is just bottom margin (1dp)
                lp.setMargins(0, 0, 0, dp(1));
            }
            row.setLayoutParams(lp);

            row.removeAllViews();
            
            // Stripe
            if (item.color != 0 || item.time != null) {
                View stripe = new View(MainWearActivity.this);
                stripe.setBackground(makeRounded(item.color != 0 ? item.color : themeAccent, dp(2)));
                LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(dp(4), dp(36));
                stripeLp.setMarginEnd(dp(12));
                row.addView(stripe, stripeLp);
            }
            
            // Content
            LinearLayout col = new LinearLayout(MainWearActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            
            TextView title = new TextView(MainWearActivity.this);
            // Filter out group (e.g. "(A)")
            String safeTitle = item.title.replaceAll("\\s*\\(.*?\\)$", "");
            title.setText(safeTitle);
            title.setTextSize(13f);
            title.setTextColor(themeText);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(title);

            if (item.time != null) {
                TextView timeTv = new TextView(MainWearActivity.this);
                timeTv.setText(item.time);
                timeTv.setTextSize(10f);
                timeTv.setTextColor(themeMuted); // Revert to muted
                timeTv.setSingleLine(true);
                col.addView(timeTv);
            }
            
            if (item.room != null) {
                TextView roomTv = new TextView(MainWearActivity.this);
                roomTv.setText(item.room);
                roomTv.setTextSize(10f);
                roomTv.setTextColor(themeMuted);
                roomTv.setSingleLine(true);
                col.addView(roomTv);
            }
            row.addView(col);
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View v) { super(v); }
        }
    }
    
    private android.graphics.drawable.Drawable makeRounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
