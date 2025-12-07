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
            if (tilesChangedListener != null) tilesChangedListener.onTilesChanged(tiles);
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
        if (tilesChangedListener != null) tilesChangedListener.onTilesChanged(tiles);
    }

    // Called by TileView when drag handle is touched
    public void startDragging(TileView tileView, float rawX, float rawY) {
        if (!isEditMode) return;
        activeTileView = tileView;
        isDragging = true;
        initialTileX = tileView.getX();
        initialTileY = tileView.getY();
        
        // We use raw coords for offsets to be safe from coordinate space confusion
        touchDownX = rawX; 
        touchDownY = rawY;
        
        // Stop ScrollView from stealing
        requestDisallowInterceptTouchEvent(true);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    // Called by TileView when resize handle is touched
    // direction: 1 = Right, -1 = Left
    public void startResizing(TileView tileView, int direction) {
        if (!isEditMode) return;
        activeTileView = tileView;
        isResizing = true;
        resizeDirection = direction;
        
        // Stop ScrollView from stealing
        requestDisallowInterceptTouchEvent(true);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEditMode) return false;
        
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
        if (!isEditMode) return super.onTouchEvent(event);
        
        // Lock scroll again just in case
        if (isDragging || isResizing) {
             requestDisallowInterceptTouchEvent(true);
             if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
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
                return true;
            } 
            
            if (isResizing && activeTileView != null) {
                handleResizeDrag(event);
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
        // We MUST measure before layout to ensure generic view content updates (like text wrapping)
        int wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int hSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        activeTileView.measure(wSpec, hSpec);
        
        // Layout to snapped position
        activeTileView.layout(l, top, r, b);
        
        invalidate(); // Redraw preview (it will overlap view exactly, acts as border/highlight)
    }

    private void updatePreviewRect() {
        if (activeTileView == null) return;
        // With snapped logic, handleResizeDrag updates it. 
        // We only need this for Drag (Move) logic.
        
        if (isDragging) {
             float centerX = activeTileView.getX() + activeTileView.getTranslationX() + activeTileView.getWidth() / 2f;
             float centerY = activeTileView.getY() + activeTileView.getTranslationY() + activeTileView.getHeight() / 2f;
             
             int col = (int) ((centerX - getPaddingLeft()) / (cellWidth + gap));
             int row = (int) ((centerY - getPaddingTop()) / (cellHeight + gap));
             
             col = Math.max(0, Math.min(COLUMN_COUNT - activeTileView.getTile().colSpan, col));
             row = Math.max(0, row); // Bound row > 0
             
             int l = getPaddingLeft() + col * (cellWidth + gap);
             int t = getPaddingTop() + row * (cellHeight + gap);
             int w = activeTileView.getTile().colSpan * cellWidth + (activeTileView.getTile().colSpan - 1) * gap;
             int h = activeTileView.getTile().rowSpan * cellHeight + (activeTileView.getTile().rowSpan - 1) * gap;
             
             previewRect.set(l, t, l + w, t + h);
        }
    }

    private void finishResize() {
        if (activeTileView != null && !previewRect.isEmpty()) {
             // Convert previewRect back to Grid Coords
             int col = (previewRect.left - getPaddingLeft()) / (cellWidth + gap);
             int row = (previewRect.top - getPaddingTop()) / (cellHeight + gap);
             
             // Calculate spans from width/height
             // w = span * cellW + (span-1)*gap
             // w = span(cellW + gap) - gap
             // w + gap = span(cellW + gap)
             // span = (w + gap) / (cellW + gap)
             
             int spanX = (previewRect.width() + gap) / (cellWidth + gap);
             int spanY = (previewRect.height() + gap) / (cellHeight + gap);
             
             // Safety Clamps
             col = Math.max(0, col);
             row = Math.max(0, row);
             spanX = Math.max(1, spanX);
             spanY = Math.max(1, spanY);
             
             Tile t = activeTileView.getTile();
             t.col = col;
             t.row = row;
             t.colSpan = spanX;
             t.rowSpan = spanY;
             
             // Refresh view appearance (1x1 icon vs text etc)
             activeTileView.setTile(t);
             
             resolveCollisions();
             requestLayout();
             if (tilesChangedListener != null) tilesChangedListener.onTilesChanged(tiles);
        }
    }
    
    private void finishDrag(MotionEvent event) {
        // Commit the drop
        float centerX = activeTileView.getX() + activeTileView.getTranslationX() + activeTileView.getWidth() / 2f;
        float centerY = activeTileView.getY() + activeTileView.getTranslationY() + activeTileView.getHeight() / 2f;
        
        int col = (int) ((centerX - getPaddingLeft()) / (cellWidth + gap));
        int row = (int) ((centerY - getPaddingTop()) / (cellHeight + gap));
        
        Tile t = activeTileView.getTile();
        
        // Bound checks
        int maxCol = COLUMN_COUNT - t.colSpan;
        if (col < 0) col = 0;
        if (col > maxCol) col = maxCol;
        if (row < 0) row = 0;
        
        t.col = col;
        t.row = row;
        
        // Reset view props
        activeTileView.setTranslationX(0f);
        activeTileView.setTranslationY(0f);
        
        resolveCollisions();
        requestLayout();
        
        if (tilesChangedListener != null) tilesChangedListener.onTilesChanged(tiles);
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
        if (isEditMode) maxRow += 2;

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
            }
        }
    }
    
    // Core Logic: Resolve Collisions & Gravity
    private void resolveCollisions() {
        // First, apply "Gravity" - Try to move everything UP if possible
        compactTiles();

        // Limit iterations to prevent ANR
        int iterations = 0;
        int MAX_ITERATIONS = 100;

        boolean changed = true;
        while (changed && iterations < MAX_ITERATIONS) {
            changed = false;
            iterations++;
            
            // Sort to ensure deterministic behavior (top-left first)
            Collections.sort(tiles, new Comparator<Tile>() {
                @Override
                public int compare(Tile o1, Tile o2) {
                    if (o1.row != o2.row) return Integer.compare(o1.row, o2.row);
                    return Integer.compare(o1.col, o2.col);
                }
            });

            for (Tile tile : tiles) {
                for (Tile other : tiles) {
                    if (tile == other) continue;
                    
                    if (rectsOverlap(tile, other)) {
                        // Decide which one to push down
                        Tile stationary = tile;
                        Tile toMove = other;
                        
                        boolean tileIsActive = (activeTileView != null && activeTileView.getTile() == tile);
                        boolean otherIsActive = (activeTileView != null && activeTileView.getTile() == other);
                        
                        if (tileIsActive && !otherIsActive) {
                            stationary = tile;
                            toMove = other;
                        } else if (!tileIsActive && otherIsActive) {
                            stationary = other;
                            toMove = tile;
                        } else {
                            // Standard gravity: upper one stays, lower one moves
                            // If rows equal, left one stays? Or Arbitrary.
                            if (tile.row < other.row) {
                                stationary = tile;
                                toMove = other;
                            } else if (other.row < tile.row) {
                                stationary = other;
                                toMove = tile;
                            } else {
                                // Same row, maybe push the one on the right?
                                if (tile.col < other.col) {
                                    stationary = tile;
                                    toMove = other;
                                } else {
                                    stationary = other;
                                    toMove = tile;
                                }
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
    
    private void compactTiles() {
        // Sort by row to process top-down
        Collections.sort(tiles, (o1, o2) -> Integer.compare(o1.row, o2.row));
        
        for (Tile t : tiles) {
            if (activeTileView != null && activeTileView.getTile() == t && (isDragging || isResizing)) {
                 // Don't move the active tile being dragged by gravity!
                 continue;
            }
            
            // Try moving up as much as possible
            while (t.row > 0) {
                t.row--;
                if (checkCollision(t)) {
                    t.row++; // Revert
                    break;
                }
            }
        }
    }
    
    // Check if Tile t collides with ANY other tile
    private boolean checkCollision(Tile t) {
        for (Tile other : tiles) {
            if (t == other) continue;
            if (rectsOverlap(t, other)) return true;
        }
        return false;
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
