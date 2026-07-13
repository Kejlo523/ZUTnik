package pl.kejlo.zutnik;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TileGridLayout extends ViewGroup {

    private static final int COLUMN_COUNT = 4;
    /** Height as a fraction of cell width — tuned so 2×2 tiles fit title + description. */
    private static final float CELL_HEIGHT_RATIO = 0.84f;
    private int cellWidth;
    private int cellHeight;
    private int gap = 0;
    private int maxCellSizePx = 0;

    private List<Tile> tiles = new ArrayList<>();
    private boolean isEditMode = false;
    private boolean entrancePrepared = false;

    private OnTilesChangedListener tilesChangedListener;
    private OnTileClickListener tileClickListener;

    public interface OnTileClickListener {
        void onTileClick(Tile tile);
    }

    private TileView activeTileView;
    private float touchDownX, touchDownY;
    private float initialTileX, initialTileY;
    private boolean isDragging = false;
    private boolean isResizing = false;
    private int resizeDirection = 0; // 1 = Right, -1 = Left

    // For drawing placement preview
    private final Rect previewRect = new Rect();
    private final Paint previewPaint = new Paint();

    // Optimization state
    private int lastPreviewCol = -1;
    private int lastPreviewRow = -1;
    private int lastPreviewColSpan = -1;
    private int lastPreviewRowSpan = -1;

    public interface OnTilesChangedListener {
        void onTilesChanged(List<Tile> newTiles);
    }

    public TileGridLayout(Context context) {
        super(context);
        init(context);
    }

    public TileGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TileGridLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // No LayoutTransition - we handle animations manually during drag
        setWillNotDraw(false); // To draw preview rect

        maxCellSizePx = context.getResources().getDimensionPixelSize(R.dimen.tile_cell_max);

        int base = ThemeManager.resolveColor(context, R.attr.mzPrimary);
        previewPaint.setColor(applyAlpha(base, 0.25f));
        previewPaint.setStyle(Paint.Style.FILL);
        previewPaint.setAntiAlias(true);
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public void setOnTilesChangedListener(OnTilesChangedListener listener) {
        this.tilesChangedListener = listener;
    }

    public void setOnTileClickListener(OnTileClickListener listener) {
        this.tileClickListener = listener;
    }

    public void setGap(int gapPx) {
        this.gap = gapPx;
        requestLayout();
    }

    public void setEditMode(boolean editMode) {
        this.isEditMode = editMode;
        if (!editMode) {
            // Cancel any active drag
            activeTileView = null;
            isDragging = false;
            isResizing = false;
            invalidate();
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                ((TileView) child).setEditMode(editMode);
            }
        }
    }

    public boolean isEditMode() {
        return isEditMode;
    }

    public void setTiles(List<Tile> newTiles) {
        this.tiles = new ArrayList<>(newTiles);
        removeAllViews();

        for (Tile tile : tiles) {
            addTileView(tile);
        }

        resolveCollisions();
        requestLayout();
    }

    /**
     * Prepares children for entrance animation before first draw.
     * Call before animateTilesEntrance to avoid visible flicker.
     */
    public void prepareTilesForEntrance() {
        entrancePrepared = true;
        float offsetY = 18f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                child.animate().cancel();
                child.setAlpha(0f);
                child.setScaleX(0.92f);
                child.setScaleY(0.92f);
                child.setTranslationY(offsetY);
            }
        }
    }

    /**
     * Animate tiles entrance with staggered delay.
     * Call this after layout is complete (e.g., via post or postDelayed).
     * 
     * @param staggerDelayMs Delay between each tile's animation start
     */
    public void animateTilesEntrance(int staggerDelayMs) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                child.animate().cancel();

                if (!entrancePrepared) {
                    child.setAlpha(1f);
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                    child.setTranslationY(0f);
                }

                // Animate with stagger
                child.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setStartDelay(i * staggerDelayMs)
                        .setDuration(350)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
            }
        }
        entrancePrepared = false;
    }

    public List<Tile> getTiles() {
        return new ArrayList<>(tiles);
    }

    private void addTileView(Tile tile) {
        TileView view = new TileView(getContext());
        view.setTile(tile);
        view.setEditMode(isEditMode);

        // Handle deletion
        view.setOnDeleteListener(v -> {
            removeView(v);
            tiles.remove(v.getTile());
            resolveCollisions();
            requestLayout();
            if (tilesChangedListener != null)
                tilesChangedListener.onTilesChanged(tiles);
        });

        // Handle click on cardContent — it owns clickable/ripple in item_tile.xml
        view.setOnTileClickListener(v -> {
            if (tileClickListener != null) {
                tileClickListener.onTileClick(tile);
            }
        });

        addView(view);
    }

    public void addTile(Tile tile) {
        // Find best position (append to bottom)
        int maxRow = 0;
        for (Tile t : tiles) {
            maxRow = Math.max(maxRow, t.row + t.rowSpan);
        }
        tile.row = maxRow;
        tile.col = 0;

        tiles.add(tile);
        addTileView(tile);
        resolveCollisions();
        requestLayout();
        if (tilesChangedListener != null)
            tilesChangedListener.onTilesChanged(tiles);
    }

    // Called by TileView when drag handle is touched
    public void startDragging(TileView tileView, float rawX, float rawY) {
        if (!isEditMode)
            return;
        activeTileView = tileView;
        isDragging = true;
        initialTileX = tileView.getX();
        initialTileY = tileView.getY();

        // Reset optimization state
        lastPreviewCol = -1;
        lastPreviewRow = -1;
        lastPreviewColSpan = -1;
        lastPreviewRowSpan = -1;
        updatePreviewRect(); // Initialize preview rect immediately

        // We use raw coords for offsets to be safe from coordinate space confusion
        touchDownX = rawX;
        touchDownY = rawY;

        // Stop ScrollView from stealing
        requestDisallowInterceptTouchEvent(true);
        if (getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
    }

    // Called by TileView when resize handle is touched
    // direction: 1 = Right, -1 = Left
    public void startResizing(TileView tileView, int direction) {
        if (!isEditMode)
            return;
        activeTileView = tileView;
        isResizing = true;
        resizeDirection = direction;

        // Reset optimization state
        lastPreviewCol = -1;
        lastPreviewRow = -1;
        lastPreviewColSpan = -1;
        lastPreviewRowSpan = -1;

        // Stop ScrollView from stealing
        requestDisallowInterceptTouchEvent(true);
        if (getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEditMode)
            return false;

        // If we established a mode via child callbacks, steal the stream
        if ((isDragging || isResizing) && activeTileView != null) {
            requestDisallowInterceptTouchEvent(true);
            return true;
        }

        // We no longer auto-detect click on body for dragging.
        // We only listen for explicit startDragging calls from children.

        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEditMode)
            return super.onTouchEvent(event);

        // Lock scroll again just in case
        if (isDragging || isResizing) {
            requestDisallowInterceptTouchEvent(true);
            if (getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (isDragging && activeTileView != null) {
                // Determine delta using RAW coords to match startDragging
                float dx = event.getRawX() - touchDownX;
                float dy = event.getRawY() - touchDownY;

                activeTileView.setTranslationX(dx);
                activeTileView.setTranslationY(dy);

                activeTileView.setTranslationX(dx);
                activeTileView.setTranslationY(dy);

                if (updatePreviewRect()) {
                    invalidate();
                    simulateLayout(); // Only simulate if grid pos changed
                }
                return true;
            }

            if (isResizing && activeTileView != null) {
                if (handleResizeDrag(event)) {
                    simulateLayout(); // Only simulate if grid pos changed
                }
                return true;
            }

        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            handleTouchUp(event);
            return true;
        }

        return true;
    }

    private void handleTouchUp(MotionEvent event) {
        if (isDragging && activeTileView != null) {
            finishDrag(event);
        } else if (isResizing && activeTileView != null) {
            finishResize();
        }

        isDragging = false;
        isResizing = false;
        activeTileView = null;
        previewRect.setEmpty();
        invalidate();
    }

    // Improved Bi-directional Resize Logic (Snapped)
    // Returns TRUE if grid state changed
    private boolean handleResizeDrag(MotionEvent event) {
        float rawX = event.getX();
        float rawY = event.getY();

        // ... (rest of logic same until calculation)

        // ... (This function needs to be rewritten slightly to return boolean, see next
        // Chunk)
        // Actually I'll rewrite the whole method signature and end part.

        int tileTop = activeTileView.getTop();
        int tileLeft = activeTileView.getLeft();
        int tileRight = activeTileView.getRight();

        Tile t = activeTileView.getTile();
        int anchorCol = t.col;
        int anchorRow = t.row;

        // Calculate target spans

        int newColSpan = t.colSpan;
        int newRowSpan = t.rowSpan;
        int newCol = t.col; // Only changes for Left Resize

        // HEIGHT (Bottom Control)
        float targetHeight = rawY - tileTop;
        newRowSpan = (int) Math.round((targetHeight + gap / 2.0) / (cellHeight + gap));
        newRowSpan = Math.max(1, Math.min(4, newRowSpan));

        // WIDTH
        if (resizeDirection == -1) { // Left Handle
            // Moving Left edge, Right edge is Anchor
            int currentRightCol = t.col + t.colSpan;
            int rightEdgePx = getPaddingLeft() + currentRightCol * (cellWidth + gap);

            float distFromRight = rightEdgePx - rawX;
            int potentialSpan = (int) Math.round((distFromRight + gap / 2.0) / (cellWidth + gap));
            // Clamp span
            potentialSpan = Math.max(1, Math.min(currentRightCol, potentialSpan));

            newColSpan = potentialSpan;
            newCol = currentRightCol - newColSpan;

        } else { // Right Handle
            // Moving Right edge, Left edge is Anchor
            int leftEdgePx = getPaddingLeft() + anchorCol * (cellWidth + gap);
            float targetWidth = rawX - leftEdgePx;
            int potentialSpan = (int) Math.round((targetWidth + gap / 2.0) / (cellWidth + gap));
            // Clamp
            potentialSpan = Math.max(1, Math.min(COLUMN_COUNT - anchorCol, potentialSpan));

            newColSpan = potentialSpan;
        }

        // Check change
        boolean changed = (newCol != lastPreviewCol || anchorRow != lastPreviewRow ||
                newColSpan != lastPreviewColSpan || newRowSpan != lastPreviewRowSpan);

        if (!changed)
            return false;

        lastPreviewCol = newCol;
        lastPreviewRow = anchorRow;
        lastPreviewColSpan = newColSpan;
        lastPreviewRowSpan = newRowSpan;

        // Calculate pixel bounds
        int l = getPaddingLeft() + newCol * (cellWidth + gap);
        int top = getPaddingTop() + anchorRow * (cellHeight + gap);
        int w = newColSpan * cellWidth + (newColSpan - 1) * gap;
        int h = newRowSpan * cellHeight + (newRowSpan - 1) * gap;
        int r = l + w;
        int b = top + h;

        // Update preview rect
        previewRect.set(l, top, r, b);

        // Update view position
        t.colSpan = newColSpan;
        t.rowSpan = newRowSpan;
        t.col = newCol;
        t.row = anchorRow;

        activeTileView.setTile(t); // Force visual refresh (Icon vs Text)

        int wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int hSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        activeTileView.measure(wSpec, hSpec);

        // Layout to snapped position
        activeTileView.layout(l, top, r, b);

        invalidate(); // Redraw preview
        return true;
    }

    // Returns TRUE if grid position changed
    private boolean updatePreviewRect() {
        if (activeTileView == null)
            return false;
        // With snapped logic, handleResizeDrag updates it.
        // We only need this for Drag (Move) logic.

        if (isDragging) {
            float x = activeTileView.getX();
            float y = activeTileView.getY();

            int col = Math.round((x - getPaddingLeft()) / (cellWidth + gap));
            int row = Math.round((y - getPaddingTop()) / (cellHeight + gap));

            col = Math.max(0, Math.min(COLUMN_COUNT - activeTileView.getTile().colSpan, col));
            row = Math.max(0, row); // Bound row > 0

            // Optimization Check
            if (col == lastPreviewCol && row == lastPreviewRow) {
                return false;
            }

            lastPreviewCol = col;
            lastPreviewRow = row;

            int l = getPaddingLeft() + col * (cellWidth + gap);
            int t = getPaddingTop() + row * (cellHeight + gap);
            int w = activeTileView.getTile().colSpan * cellWidth + (activeTileView.getTile().colSpan - 1) * gap;
            int h = activeTileView.getTile().rowSpan * cellHeight + (activeTileView.getTile().rowSpan - 1) * gap;

            previewRect.set(l, t, l + w, t + h);
            return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isEditMode && !previewRect.isEmpty()) {
            canvas.drawRect(previewRect, previewPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = width - getPaddingLeft() - getPaddingRight();

        if (COLUMN_COUNT > 0 && availableWidth > 0) {
            int desired = (availableWidth - (COLUMN_COUNT - 1) * gap) / COLUMN_COUNT;
            if (widthMode == MeasureSpec.EXACTLY
                    || (widthMode == MeasureSpec.AT_MOST && width >= availableWidth)) {
                cellWidth = desired;
            } else if (maxCellSizePx > 0) {
                cellWidth = Math.min(desired, maxCellSizePx);
            } else {
                cellWidth = desired;
            }
        } else {
            cellWidth = 0;
        }

        cellHeight = Math.round(cellWidth * CELL_HEIGHT_RATIO);

        int maxRow = 0;
        for (Tile t : tiles) {
            maxRow = Math.max(maxRow, t.row + t.rowSpan);
        }

        // Add some extra space at the bottom for dragging
        if (isEditMode)
            maxRow += 2;

        int totalHeight = getPaddingTop() + getPaddingBottom() +
                maxRow * cellHeight + (Math.max(0, maxRow - 1)) * gap;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                Tile t = ((TileView) child).getTile();
                int childWidth = t.colSpan * cellWidth + (t.colSpan - 1) * gap;
                int childHeight = t.rowSpan * cellHeight + (t.rowSpan - 1) * gap;

                int wSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY);
                int hSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY);
                child.measure(wSpec, hSpec);
            }
        }

        int contentWidth = getPaddingLeft() + getPaddingRight()
                + COLUMN_COUNT * cellWidth + (COLUMN_COUNT - 1) * gap;
        int measuredWidth = widthMode == MeasureSpec.EXACTLY ? width : Math.min(width, contentWidth);

        setMeasuredDimension(measuredWidth, resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                Tile tile = ((TileView) child).getTile();

                int left = paddingLeft + tile.col * (cellWidth + gap);
                int top = paddingTop + tile.row * (cellHeight + gap);
                int right = left + child.getMeasuredWidth();
                int bottom = top + child.getMeasuredHeight();

                child.layout(left, top, right, bottom);

                // Only reset visuals if we are NOT currently interacting
                if (!isDragging && !isResizing) {
                    child.setTranslationX(0f);
                    child.setTranslationY(0f);
                }
            }
        }
    }

    // Core Logic: Resolve Collisions & Gravity
    // Now static/pure so we can run it on simulated lists
    private void resolveCollisions(List<Tile> targetTiles) {
        // First, apply "Gravity" - Try to move everything UP if possible
        compactTiles(targetTiles);

        // Limit iterations to prevent ANR
        int iterations = 0;
        int MAX_ITERATIONS = 100;

        boolean changed = true;
        while (changed && iterations < MAX_ITERATIONS) {
            changed = false;
            iterations++;

            // Sort to ensure deterministic behavior (top-left first)
            Collections.sort(targetTiles, new Comparator<Tile>() {
                @Override
                public int compare(Tile o1, Tile o2) {
                    if (o1.row != o2.row)
                        return Integer.compare(o1.row, o2.row);
                    return Integer.compare(o1.col, o2.col);
                }
            });

            for (Tile tile : targetTiles) {
                for (Tile other : targetTiles) {
                    if (tile == other)
                        continue;

                    if (rectsOverlap(tile, other)) {
                        // Decide which one to push down
                        Tile stationary = tile;
                        Tile toMove = other;

                        // If one is the active tile currently being manipulated (in simulation),
                        // we generally want to treat it as the "intruder" or "stationary force"
                        // depending on logic.
                        // But here we are working on a list. The caller should have already positioned
                        // the 'active' tile.
                        // In simulation, the active tile is at its NEW dragged position.
                        // We usually want the active tile to DISPLACE others.
                        // So if 'tile' is the one corresponding to active, 'other' should move.

                        // However, since we don't pass 'activeTile' here easily, we can rely on
                        // ID/reference
                        // if we are careful, OR we rely on the heuristic that we just moved one tile
                        // and we want others to react.

                        // Current logic:
                        if (tile.row < other.row) {
                            stationary = tile;
                            toMove = other;
                        } else if (other.row < tile.row) {
                            stationary = other;
                            toMove = tile;
                        } else {
                            // Same row
                            if (tile.col < other.col) {
                                stationary = tile;
                                toMove = other;
                            } else {
                                stationary = other;
                                toMove = tile;
                            }
                        }

                        // Push toMove below stationary
                        int targetRow = stationary.row + stationary.rowSpan;
                        if (toMove.row < targetRow) {
                            toMove.row = targetRow;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private void compactTiles(List<Tile> targetTiles) {
        // Sort by row to process top-down
        Collections.sort(targetTiles, (o1, o2) -> Integer.compare(o1.row, o2.row));

        for (Tile t : targetTiles) {
            // Note: In simulation, we might NOT want to compact the active dragged tile
            // if we want it to stay exactly where the user is holding it.
            // But if we moved it to a 'preview' grid slot, maybe we do?
            // Usually, the dragged tile is 'floating' in the preview slot.
            // We should pin it so gravity doesn't pull it away from the finger.

            // We need a way to identify the active tile to SKIP gravity for it during
            // simulation.
            boolean isActive = false;
            if (activeTileView != null && activeTileView.getTile() != null) {
                // If this 't' is the clone of active tile.
                // Since 't' is a clone, 't == activeTileView.getTile()' is FALSE.
                // We need IDs. Assuming Tile has ID or unique title.
                // If not, we rely on the fact that we modify 't' before calling this.
                // Actually, let's pass a list of "pinned" tiles or similar?
                // Or simply: check if this tile overlaps the previewRect (roughly) AND matches
                // metadata?

                if (isEditMode && (isDragging || isResizing) && activeTileView != null) {
                    // We match by reference if we are running on REAL list.
                    if (t == activeTileView.getTile())
                        isActive = true;

                    // If we are running on COPY list, we need another way.
                    // Let's assume for now we don't have IDs.
                    // We can rely on the caller to have set a flag or we can skip this check for
                    // simplicity
                    // and assume the preview rect forces position later?
                    // No, gravity will pull it up.
                }
            }

            // SIMPLIFIED: usage of compactTiles in simulation needs to know which tile is
            // "fixed".
            // Implementation detail: I will add an optional "fixedTile" arg to these
            // methods.
        }
    }

    // Internal collision resolution
    private void resolveCollisions() {
        resolveCollisions(this.tiles, activeTileView != null ? activeTileView.getTile() : null);
    }

    private void resolveCollisions(List<Tile> targetTiles, Tile fixedTile) {
        // Collision Resolution - only move tiles that actually overlap
        // This preserves intentional gaps in the layout

        if (fixedTile == null) {
            // No fixed tile, just ensure no overlaps between tiles
            resolveOverlaps(targetTiles);
            return;
        }

        // Build grid with fixedTile position
        boolean[][] grid = new boolean[50][COLUMN_COUNT];
        markGrid(grid, fixedTile);

        // Check each other tile for collision with fixedTile
        for (Tile t : targetTiles) {
            if (t == fixedTile || tilesMatch(t, fixedTile)) {
                continue;
            }

            // Check if this tile overlaps with fixedTile
            if (rectsOverlap(t, fixedTile)) {
                // Find new position for this tile - try to stay close to original
                Point newPos = findNearestAvailableSlot(grid, t.col, t.row, t.colSpan, t.rowSpan);
                t.col = newPos.x;
                t.row = newPos.y;
            }

            // Mark this tile's position as occupied
            markGrid(grid, t);
        }
    }

    /**
     * Resolve overlaps between tiles without a fixed tile.
     * Only moves tiles that actually collide with each other.
     */
    private void resolveOverlaps(List<Tile> targetTiles) {
        boolean[][] grid = new boolean[50][COLUMN_COUNT];

        // Sort by position to process top-left first
        List<Tile> sorted = new ArrayList<>(targetTiles);
        Collections.sort(sorted, (o1, o2) -> {
            if (o1.row != o2.row)
                return Integer.compare(o1.row, o2.row);
            return Integer.compare(o1.col, o2.col);
        });

        for (Tile t : sorted) {
            // Check if current position is free
            if (!isSlotFree(grid, t.col, t.row, t.colSpan, t.rowSpan)) {
                // Find nearest available slot
                Point newPos = findNearestAvailableSlot(grid, t.col, t.row, t.colSpan, t.rowSpan);
                t.col = newPos.x;
                t.row = newPos.y;
            }
            markGrid(grid, t);
        }
    }

    /**
     * Find the nearest available slot to the preferred position.
     * Tries to stay close to original position rather than packing to top-left.
     */
    private Point findNearestAvailableSlot(boolean[][] grid, int prefCol, int prefRow, int colSpan, int rowSpan) {
        // Try original position first
        if (isSlotFree(grid, prefCol, prefRow, colSpan, rowSpan)) {
            return new Point(prefCol, prefRow);
        }

        // Search in expanding rings around preferred position
        for (int distance = 1; distance < 20; distance++) {
            // Try same row first (horizontal displacement)
            for (int dc = -distance; dc <= distance; dc++) {
                int c = prefCol + dc;
                if (c >= 0 && c <= COLUMN_COUNT - colSpan) {
                    // Try at preferred row
                    if (isSlotFree(grid, c, prefRow, colSpan, rowSpan)) {
                        return new Point(c, prefRow);
                    }
                    // Try below preferred row
                    for (int dr = 1; dr <= distance; dr++) {
                        int r = prefRow + dr;
                        if (r >= 0 && r < grid.length - rowSpan) {
                            if (isSlotFree(grid, c, r, colSpan, rowSpan)) {
                                return new Point(c, r);
                            }
                        }
                    }
                }
            }
        }

        // Fallback: append to bottom
        int maxRow = 0;
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                if (grid[r][c])
                    maxRow = Math.max(maxRow, r + 1);
            }
        }
        return new Point(0, maxRow);
    }

    private static class Point {
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private void markGrid(boolean[][] grid, Tile t) {
        for (int r = t.row; r < t.row + t.rowSpan; r++) {
            for (int c = t.col; c < t.col + t.colSpan; c++) {
                if (r >= 0 && r < grid.length && c >= 0 && c < COLUMN_COUNT) {
                    grid[r][c] = true;
                }
            }
        }
    }

    private boolean isSlotFree(boolean[][] grid, int col, int row, int colSpan, int rowSpan) {
        for (int r = row; r < row + rowSpan; r++) {
            for (int c = col; c < col + colSpan; c++) {
                if (grid[r][c])
                    return false;
            }
        }
        return true;
    }

    public void refreshTileView(Tile tile) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                TileView tv = (TileView) child;
                if (tv.getTile() == tile) {
                    tv.setTile(tile); // Refresh content (title/desc)
                    tv.measure(MeasureSpec.makeMeasureSpec(tv.getWidth(), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(tv.getHeight(), MeasureSpec.EXACTLY));
                    tv.layout(tv.getLeft(), tv.getTop(), tv.getRight(), tv.getBottom());
                    return;
                }
            }
        }
    }

    private boolean tilesMatch(Tile t1, Tile t2) {
        return t1 == t2;
    }

    private void simulateLayout() {
        if (activeTileView == null)
            return;
        Tile activeTile = activeTileView.getTile();

        // Calculate where the CENTER of the dragged tile is
        float centerX = activeTileView.getX() + activeTileView.getWidth() / 2f;
        float centerY = activeTileView.getY() + activeTileView.getHeight() / 2f;

        // Find which tile the center is over (if any)
        TileView targetView = null;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView))
                continue;
            TileView tv = (TileView) child;
            if (tv == activeTileView)
                continue;

            // Get the current visual bounds (including any animation)
            float tvLeft = tv.getX();
            float tvTop = tv.getY();
            float tvRight = tvLeft + tv.getWidth();
            float tvBottom = tvTop + tv.getHeight();

            // Add a dead zone - only trigger swap when entering central 60% of tile
            float deadZone = 0.2f;
            float dzWidth = tv.getWidth() * deadZone;
            float dzHeight = tv.getHeight() * deadZone;

            if (centerX > tvLeft + dzWidth && centerX < tvRight - dzWidth &&
                    centerY > tvTop + dzHeight && centerY < tvBottom - dzHeight) {
                targetView = tv;
                break;
            }
        }

        if (targetView != null) {
            Tile targetTile = targetView.getTile();

            // Only swap if sizes are compatible OR if target would fit in active's spot
            boolean canSwap = true;

            // Simple swap: exchange row/col positions
            int tempRow = activeTile.row;
            int tempCol = activeTile.col;

            // Check if target can fit in active's original spot
            if (targetTile.col + targetTile.colSpan > COLUMN_COUNT) {
                // Would overflow, adjust
                targetTile.col = COLUMN_COUNT - targetTile.colSpan;
            }

            activeTile.row = targetTile.row;
            activeTile.col = targetTile.col;
            targetTile.row = tempRow;
            targetTile.col = tempCol;

            // Clamp active tile position
            if (activeTile.col + activeTile.colSpan > COLUMN_COUNT) {
                activeTile.col = COLUMN_COUNT - activeTile.colSpan;
            }
            if (activeTile.col < 0)
                activeTile.col = 0;
            if (activeTile.row < 0)
                activeTile.row = 0;

            // Animate target tile to its new position
            int targetLeft = getPaddingLeft() + targetTile.col * (cellWidth + gap);
            int targetTop = getPaddingTop() + targetTile.row * (cellHeight + gap);

            targetView.animate()
                    .x(targetLeft)
                    .y(targetTop)
                    .setDuration(150)
                    .start();

            // Update preview rect to new active tile position
            lastPreviewCol = activeTile.col;
            lastPreviewRow = activeTile.row;
            int l = getPaddingLeft() + activeTile.col * (cellWidth + gap);
            int t = getPaddingTop() + activeTile.row * (cellHeight + gap);
            int w = activeTile.colSpan * cellWidth + (activeTile.colSpan - 1) * gap;
            int h = activeTile.rowSpan * cellHeight + (activeTile.rowSpan - 1) * gap;
            previewRect.set(l, t, l + w, t + h);
            invalidate();
        }
    }

    private void finishResize() {
        if (activeTileView == null)
            return;

        // Position was already updated during handleResizeDrag
        // Just resolve any collisions and relayout
        resolveCollisions();
        requestLayout();
        if (tilesChangedListener != null)
            tilesChangedListener.onTilesChanged(tiles);
    }

    private void finishDrag(MotionEvent event) {
        if (activeTileView == null)
            return;

        Tile activeTile = activeTileView.getTile();

        // Calculate final grid position from preview rect (where user dropped)
        if (!previewRect.isEmpty()) {
            int col = Math.round((previewRect.left - getPaddingLeft()) / (float) (cellWidth + gap));
            int row = Math.round((previewRect.top - getPaddingTop()) / (float) (cellHeight + gap));

            // Clamp to valid range
            col = Math.max(0, Math.min(COLUMN_COUNT - activeTile.colSpan, col));
            row = Math.max(0, row);

            activeTile.col = col;
            activeTile.row = row;
        }

        // Reset view translation (was used for drag visual)
        activeTileView.setTranslationX(0f);
        activeTileView.setTranslationY(0f);

        // Reset all tile animations to their final grid positions
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                child.animate().cancel();
                child.setTranslationX(0f);
                child.setTranslationY(0f);
            }
        }

        // Resolve collisions - only tiles that overlap will be moved
        resolveCollisions();
        requestLayout();
        if (tilesChangedListener != null)
            tilesChangedListener.onTilesChanged(tiles);
    }

    private boolean rectsOverlap(Tile t1, Tile t2) {
        int l1 = t1.col;
        int r1 = t1.col + t1.colSpan;
        int top1 = t1.row;
        int b1 = t1.row + t1.rowSpan;

        int l2 = t2.col;
        int r2 = t2.col + t2.colSpan;
        int top2 = t2.row;
        int b2 = t2.row + t2.rowSpan;

        return l1 < r2 && r1 > l2 && top1 < b2 && b1 > top2;
    }
}
