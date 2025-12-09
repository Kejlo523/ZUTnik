package pl.kejlo.mzutv2;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TileGridLayout extends ViewGroup {

    private static final int COLUMN_COUNT = 4;
    private int cellWidth;
    private int cellHeight;
    private int gap = 0;

    private List<Tile> tiles = new ArrayList<>();
    private boolean isEditMode = false;

    private OnTilesChangedListener tilesChangedListener;
    private OnTileClickListener tileClickListener;

    public interface OnTileClickListener {
        void onTileClick(Tile tile);
    }

    private TileView activeTileView;
    private float touchDownX, touchDownY;
    private float initialTileX, initialTileY;
    private int touchSlop;
    private boolean isDragging = false;
    private boolean isResizing = false;
    private int resizeDirection = 0; // 1 = Right, -1 = Left

    // For drawing placement preview
    private final Rect previewRect = new Rect();
    private final Paint previewPaint = new Paint();

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
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        setLayoutTransition(lt);

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false); // To draw preview rect

        previewPaint.setColor(0x402196F3); // Semi-transparent blue
        previewPaint.setStyle(Paint.Style.FILL);
        previewPaint.setAntiAlias(true);
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

        // Handle click
        view.setOnClickListener(v -> {
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

                updatePreviewRect();
                invalidate();
                simulateLayout(); // Live Preview
                return true;
            }

            if (isResizing && activeTileView != null) {
                handleResizeDrag(event);
                simulateLayout(); // Live Preview
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
    private void handleResizeDrag(MotionEvent event) {
        float rawX = event.getX();
        float rawY = event.getY();

        int tileTop = activeTileView.getTop();
        int tileLeft = activeTileView.getLeft();
        int tileRight = activeTileView.getRight();

        Tile t = activeTileView.getTile();
        int anchorCol = t.col;
        int anchorRow = t.row;

        // --- Calculate Target Spans (Logic from updatePreviewRect) ---

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

        // --- Calculate Target Pixel Bounds (Snapped) ---
        int l = getPaddingLeft() + newCol * (cellWidth + gap);
        int top = getPaddingTop() + anchorRow * (cellHeight + gap);
        int w = newColSpan * cellWidth + (newColSpan - 1) * gap;
        int h = newRowSpan * cellHeight + (newRowSpan - 1) * gap;
        int r = l + w;
        int b = top + h;

        // --- Update Preview Rect ---
        previewRect.set(l, top, r, b);

        // --- Update Actual View (Snapped) ---
        // We MUST measure before layout to ensure generic view content updates (like
        // text wrapping)

        // Update spans and position on the tile object so setTile triggers correct
        // visual mode
        t.colSpan = newColSpan;
        t.rowSpan = newRowSpan;
        t.col = newCol;
        // t.row doesn't change for resize (top anchor is fixed for height resize
        // usually, unless we resized top? No, resize is Height (Bottom)).
        // Wait, current logic:
        // Height (Bottom Control): anchorRow = t.row. newRowSpan calculated.
        // So Row doesn't change.
        t.row = anchorRow;

        activeTileView.setTile(t); // Force visual refresh (Icon vs Text)

        int wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int hSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        activeTileView.measure(wSpec, hSpec);

        // Layout to snapped position
        activeTileView.layout(l, top, r, b);

        invalidate(); // Redraw preview (it will overlap view exactly, acts as border/highlight)
    }

    private void updatePreviewRect() {
        if (activeTileView == null)
            return;
        // With snapped logic, handleResizeDrag updates it.
        // We only need this for Drag (Move) logic.

        if (isDragging) {
            float x = activeTileView.getX();
            float y = activeTileView.getY();

            int col = Math.round((x - getPaddingLeft()) / (cellWidth + gap));
            int row = Math.round((y - getPaddingTop()) / (cellHeight + gap));

            col = Math.max(0, Math.min(COLUMN_COUNT - activeTileView.getTile().colSpan, col));
            row = Math.max(0, row); // Bound row > 0

            int l = getPaddingLeft() + col * (cellWidth + gap);
            int t = getPaddingTop() + row * (cellHeight + gap);
            int w = activeTileView.getTile().colSpan * cellWidth + (activeTileView.getTile().colSpan - 1) * gap;
            int h = activeTileView.getTile().rowSpan * cellHeight + (activeTileView.getTile().rowSpan - 1) * gap;

            previewRect.set(l, t, l + w, t + h);
        }
    }

    private boolean isPointInView(View view, int x, int y) {
        return x >= view.getLeft() && x <= view.getRight() &&
                y >= view.getTop() && y <= view.getBottom();
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
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = width - getPaddingLeft() - getPaddingRight();

        if (COLUMN_COUNT > 0) {
            cellWidth = (availableWidth - (COLUMN_COUNT - 1) * gap) / COLUMN_COUNT;
        } else {
            cellWidth = 0;
        }

        cellHeight = cellWidth; // Square cells

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

        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec));
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

                if (child != activeTileView || (!isDragging && !isResizing)) {
                    child.setTranslationX(0f);
                    child.setTranslationY(0f);
                    child.animate().cancel();
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

    // --- Overloaded for internal usage with current state ---
    private void resolveCollisions() {
        resolveCollisions(this.tiles, activeTileView != null ? activeTileView.getTile() : null);
    }

    private void resolveCollisions(List<Tile> targetTiles, Tile fixedTile) {
        // Flow Layout Algorithm / Bin Packing
        // 1. Mark the 'fixedTile' (dragged tile) as occupying its desired space.
        // 2. Sort all OTHER tiles by their current position (to maintain relative
        // order).
        // 3. Place other tiles into the first available slots that don't overlap with
        // 'fixedTile' or already placed tiles.

        // Map of occupied cells: boolean[ROW][COL]
        // Since rows can expand, we need dynamic or sufficiently large array.
        // 20 rows should be enough for this app (scrolling).
        boolean[][] grid = new boolean[50][COLUMN_COUNT];

        // 1. Place Fixed Tile
        if (fixedTile != null) {
            markGrid(grid, fixedTile);
        }

        // 2. Separate and Sort Others
        List<Tile> others = new ArrayList<>();
        for (Tile t : targetTiles) {
            if (t != fixedTile && (fixedTile == null || !tilesMatch(t, fixedTile))) {
                others.add(t);
            }
        }

        Collections.sort(others, (o1, o2) -> {
            if (o1.row != o2.row)
                return Integer.compare(o1.row, o2.row);
            return Integer.compare(o1.col, o2.col);
        });

        // 3. Reflow Others
        for (Tile t : others) {
            Point pos = findFirstAvailableSlot(grid, t.colSpan, t.rowSpan);
            t.col = pos.x;
            t.row = pos.y;
            markGrid(grid, t);
        }
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

    private Point findFirstAvailableSlot(boolean[][] grid, int colSpan, int rowSpan) {
        // Search for a gap that fits colSpan x rowSpan
        for (int r = 0; r < grid.length - rowSpan; r++) {
            for (int c = 0; c <= COLUMN_COUNT - colSpan; c++) {
                if (isSlotFree(grid, c, r, colSpan, rowSpan)) {
                    return new Point(c, r);
                }
            }
        }
        // Fallback if full (append to bottom)
        return new Point(0, grid.length);
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

    // Unified calculation method for WYSIWYG
    private List<Tile> calculateSimulation() {
        if (activeTileView == null)
            return new ArrayList<>(); // Should not happen
        Tile activeTile = activeTileView.getTile();

        // 1. Create Deep Copy of Tiles
        List<Tile> simulatedTiles = new ArrayList<>();
        Tile simulatedActiveTile = null;

        for (Tile t : tiles) {
            Tile clone = new Tile();
            clone.title = t.title;
            clone.actionType = t.actionType;
            clone.actionData = t.actionData;
            clone.row = t.row;
            clone.col = t.col;
            clone.rowSpan = t.rowSpan;
            clone.colSpan = t.colSpan;

            if (t == activeTile) {
                simulatedActiveTile = clone;
            }
            simulatedTiles.add(clone);
        }

        if (simulatedActiveTile == null)
            return simulatedTiles;

        // 2. Move Simulated Active Tile to Preview Position

        // Calculate Snap-To-Grid
        // Use Top-Left for snapping
        int col = (previewRect.left - getPaddingLeft()) / (cellWidth + gap);
        int row = (previewRect.top - getPaddingTop()) / (cellHeight + gap);

        if (isResizing) {
            int spanX = (previewRect.width() + gap) / (cellWidth + gap);
            int spanY = (previewRect.height() + gap) / (cellHeight + gap);
            simulatedActiveTile.colSpan = Math.min(COLUMN_COUNT, Math.max(1, spanX));
            simulatedActiveTile.rowSpan = Math.max(1, spanY);
        }

        // Clamp Position
        int maxCol = COLUMN_COUNT - simulatedActiveTile.colSpan;
        if (col > maxCol)
            col = maxCol;
        if (col < 0)
            col = 0; // Ensure 0 minimum
        if (row < 0)
            row = 0;

        simulatedActiveTile.col = col;
        simulatedActiveTile.row = row;

        // 3. Run Physics on simulated list
        resolveCollisions(simulatedTiles, simulatedActiveTile);

        return simulatedTiles;
    }

    private void simulateLayout() {
        if (activeTileView == null)
            return;
        Tile activeTile = activeTileView.getTile();

        List<Tile> simulatedTiles = calculateSimulation();
        if (simulatedTiles.isEmpty())
            return;

        // 4. Animate Real Views to Simulated Positions
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView))
                continue;
            TileView tv = (TileView) child;
            Tile realTile = tv.getTile();

            if (realTile == activeTile)
                continue;

            int index = tiles.indexOf(realTile);
            if (index == -1 || index >= simulatedTiles.size())
                continue;

            Tile sim = simulatedTiles.get(index);

            int targetLeft = getPaddingLeft() + sim.col * (cellWidth + gap);
            int targetTop = getPaddingTop() + sim.row * (cellHeight + gap);

            tv.animate()
                    .x(targetLeft)
                    .y(targetTop)
                    .setDuration(200)
                    .start();
        }
    }

    private void finishResize() {
        if (activeTileView == null)
            return;

        // Ensure preview rect is up to date (though handleResizeDrag keeps it updated)
        List<Tile> finalState = calculateSimulation();
        applyFinalState(finalState);
    }

    private void finishDrag(MotionEvent event) {
        if (activeTileView == null)
            return;

        // Ensure preview rect is up to date based on final position
        updatePreviewRect();
        List<Tile> finalState = calculateSimulation();
        applyFinalState(finalState);

        // Reset view props (translation was used for drag visual)
        activeTileView.setTranslationX(0f);
        activeTileView.setTranslationY(0f);
    }

    private void applyFinalState(List<Tile> finalState) {
        if (finalState.size() != tiles.size())
            return; // Sanity check

        // Copy state back to real tiles
        for (int i = 0; i < tiles.size(); i++) {
            Tile real = tiles.get(i);
            Tile sim = finalState.get(i);

            real.row = sim.row;
            real.col = sim.col;
            real.rowSpan = sim.rowSpan;
            real.colSpan = sim.colSpan;
        }

        // We don't need to resolveCollisions() again because finalState is already
        // resolved!
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
