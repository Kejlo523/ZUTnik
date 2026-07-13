package pl.kejlo.zutnik;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class TileGridLayout extends ViewGroup {

    private static final int COLUMN_COUNT = 4;
    private static final int MAX_GRID_ROWS = 80;
    private static final int EXTRA_EDIT_ROWS = 2;
    private static final int MAX_TILE_ROW_SPAN = 4;
    private static final float CELL_HEIGHT_RATIO = 0.96f;
    private static final long SETTLE_DURATION_MS = 170L;
    private static final long NEIGHBOR_DURATION_MS = 145L;

    public interface OnTileClickListener {
        void onTileClick(Tile tile);
    }

    public interface OnTilesChangedListener {
        void onTilesChanged(List<Tile> newTiles);
    }

    private static final class GridState {
        final int col;
        final int row;
        final int colSpan;
        final int rowSpan;

        GridState(Tile tile) {
            this(tile.col, tile.row, tile.colSpan, tile.rowSpan);
        }

        GridState(int col, int row, int colSpan, int rowSpan) {
            this.col = col;
            this.row = row;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
        }

        void applyTo(Tile tile) {
            tile.col = col;
            tile.row = row;
            tile.colSpan = colSpan;
            tile.rowSpan = rowSpan;
        }
    }

    private static final class GridPoint {
        final int col;
        final int row;

        GridPoint(int col, int row) {
            this.col = col;
            this.row = row;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private final Map<Tile, GridState> gestureBaseline = new IdentityHashMap<>();
    private final RectF previewRect = new RectF();
    private final RectF gridSlotRect = new RectF();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int cellWidth;
    private int cellHeight;
    private int gridOffsetX;
    private int gap;
    private int maxCellSizePx;
    private int touchSlop;

    private boolean editMode;
    private boolean entrancePrepared;
    private boolean dragging;
    private boolean resizing;
    private boolean gestureMoved;
    private boolean settling;
    private boolean settleShouldNotify;

    private TileView activeTileView;
    private float touchDownRawX;
    private float touchDownRawY;
    private float initialTileLeft;
    private float initialTileTop;
    private int lastPreviewCol = -1;
    private int lastPreviewRow = -1;
    private int lastPreviewColSpan = -1;
    private int lastPreviewRowSpan = -1;

    private OnTilesChangedListener tilesChangedListener;
    private OnTileClickListener tileClickListener;

    private final Runnable settleRunnable = () -> completeInteraction(settleShouldNotify);

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
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        maxCellSizePx = context.getResources().getDimensionPixelSize(R.dimen.tile_cell_max);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        int primary = ThemeManager.resolveColor(context, R.attr.mzPrimary);
        int border = ThemeManager.resolveColor(context, R.attr.mzBorderSoft);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(withAlpha(border, 0.48f));

        previewFillPaint.setStyle(Paint.Style.FILL);
        previewFillPaint.setColor(withAlpha(primary, 0.16f));

        previewStrokePaint.setStyle(Paint.Style.STROKE);
        previewStrokePaint.setStrokeWidth(dp(1.5f));
        previewStrokePaint.setColor(withAlpha(primary, 0.9f));
    }

    public void setOnTilesChangedListener(OnTilesChangedListener listener) {
        tilesChangedListener = listener;
    }

    public void setOnTileClickListener(OnTileClickListener listener) {
        tileClickListener = listener;
    }

    public void setGap(int gapPx) {
        gap = Math.max(0, gapPx);
        requestLayout();
    }

    public void setEditMode(boolean enabled) {
        if (editMode == enabled) {
            return;
        }
        editMode = enabled;
        if (!enabled) {
            cancelInteractionImmediately();
        }
        forEachTileView(view -> view.setEditMode(enabled));
        requestLayout();
        invalidate();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setTiles(List<Tile> newTiles) {
        cancelInteractionImmediately();
        tiles.clear();
        if (newTiles != null) {
            tiles.addAll(newTiles);
        }
        normalizeLayout();

        removeAllViews();
        for (Tile tile : tiles) {
            addTileView(tile);
        }
        requestLayout();
    }

    public List<Tile> getTiles() {
        return new ArrayList<>(tiles);
    }

    public void addTile(Tile tile) {
        if (tile == null) {
            return;
        }
        sanitizeSize(tile);
        boolean[][] occupied = buildOccupiedGrid(null);
        GridPoint slot = findNearestSlot(occupied, 0, 0, tile.colSpan, tile.rowSpan);
        tile.col = slot.col;
        tile.row = slot.row;
        tiles.add(tile);
        addTileView(tile);
        requestLayout();
        post(() -> {
            TileView view = findViewForTile(tile);
            if (view != null) {
                view.setAlpha(0f);
                view.setScaleX(0.9f);
                view.setScaleY(0.9f);
                view.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .start();
            }
        });
        notifyTilesChanged();
    }

    public void refreshTileView(Tile tile) {
        TileView view = findViewForTile(tile);
        if (view != null) {
            view.setTile(tile);
            requestLayout();
        }
    }

    public void prepareTilesForEntrance() {
        entrancePrepared = true;
        float offset = dp(12);
        forEachTileView(view -> {
            view.animate().cancel();
            view.setAlpha(0f);
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            view.setTranslationY(offset);
        });
    }

    public void animateTilesEntrance(int staggerDelayMs) {
        int index = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView)) {
                continue;
            }
            child.animate().cancel();
            if (!entrancePrepared) {
                child.setAlpha(1f);
                child.setScaleX(1f);
                child.setScaleY(1f);
                child.setTranslationY(0f);
            }
            child.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setStartDelay((long) index * staggerDelayMs)
                    .setDuration(240L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
            index++;
        }
        entrancePrepared = false;
    }

    public void startDragging(TileView tileView, float rawX, float rawY) {
        if (!canStartInteraction(tileView)) {
            return;
        }
        beginInteraction(tileView, rawX, rawY);
        dragging = true;
        updatePreview(tileView.getTile().col, tileView.getTile().row,
                tileView.getTile().colSpan, tileView.getTile().rowSpan, false);
    }

    public void startResizing(TileView tileView, float rawX, float rawY) {
        if (!canStartInteraction(tileView)) {
            return;
        }
        beginInteraction(tileView, rawX, rawY);
        resizing = true;
        updatePreview(tileView.getTile().col, tileView.getTile().row,
                tileView.getTile().colSpan, tileView.getTile().rowSpan, false);
    }

    public void finishHandleTap() {
        if ((dragging || resizing) && !gestureMoved && !settling) {
            restoreBaseline();
            completeInteraction(false);
        }
    }

    private boolean canStartInteraction(TileView tileView) {
        return editMode && tileView != null && !settling && activeTileView == null;
    }

    private void beginInteraction(TileView tileView, float rawX, float rawY) {
        removeCallbacks(settleRunnable);
        activeTileView = tileView;
        touchDownRawX = rawX;
        touchDownRawY = rawY;
        initialTileLeft = tileView.getLeft();
        initialTileTop = tileView.getTop();
        gestureMoved = false;
        snapshotBaseline();
        resetPreviewState();

        tileView.bringToFront();
        tileView.setManipulating(true);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!editMode || activeTileView == null || (!dragging && !resizing)) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - touchDownRawX;
            float dy = event.getRawY() - touchDownRawY;
            return Math.hypot(dx, dy) >= touchSlop;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editMode || activeTileView == null) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                gestureMoved = true;
                if (dragging) {
                    handleDragMove(event);
                } else if (resizing) {
                    handleResizeMove(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
                settleInteraction(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                settleInteraction(true);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleDragMove(MotionEvent event) {
        float dx = event.getRawX() - touchDownRawX;
        float dy = event.getRawY() - touchDownRawY;
        activeTileView.setTranslationX(dx);
        activeTileView.setTranslationY(dy);

        Tile active = activeTileView.getTile();
        int maxRow = Math.min(
                Math.max(getContentRowCount() + EXTRA_EDIT_ROWS - active.rowSpan, 0),
                MAX_GRID_ROWS - active.rowSpan);
        int col = snapColumn(initialTileLeft + dx, active.colSpan);
        int row = clamp(Math.round((initialTileTop + dy - getPaddingTop()) / (float) rowPitch()), 0, maxRow);
        updatePreview(col, row, active.colSpan, active.rowSpan, true);
    }

    private void handleResizeMove(MotionEvent event) {
        GridState start = gestureBaseline.get(activeTileView.getTile());
        if (start == null) {
            return;
        }

        float dx = event.getRawX() - touchDownRawX;
        float dy = event.getRawY() - touchDownRawY;
        int startWidth = spanWidth(start.colSpan);
        int startHeight = spanHeight(start.rowSpan);
        int colSpan = Math.round((startWidth + dx + gap) / (float) columnPitch());
        int rowSpan = Math.round((startHeight + dy + gap) / (float) rowPitch());
        colSpan = clamp(colSpan, 1, COLUMN_COUNT - start.col);
        rowSpan = clamp(rowSpan, 1, MAX_TILE_ROW_SPAN);

        if (!updatePreview(start.col, start.row, colSpan, rowSpan, true)) {
            return;
        }

        int width = spanWidth(colSpan);
        int height = spanHeight(rowSpan);
        activeTileView.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        activeTileView.layout(
                Math.round(initialTileLeft),
                Math.round(initialTileTop),
                Math.round(initialTileLeft) + width,
                Math.round(initialTileTop) + height);
        activeTileView.updateSizeBadge();
    }

    private boolean updatePreview(int col, int row, int colSpan, int rowSpan, boolean haptic) {
        col = clamp(col, 0, COLUMN_COUNT - colSpan);
        row = Math.max(0, row);
        if (col == lastPreviewCol && row == lastPreviewRow
                && colSpan == lastPreviewColSpan && rowSpan == lastPreviewRowSpan) {
            return false;
        }

        lastPreviewCol = col;
        lastPreviewRow = row;
        lastPreviewColSpan = colSpan;
        lastPreviewRowSpan = rowSpan;
        applyCandidateLayout(col, row, colSpan, rowSpan);

        float radiusInset = dp(2);
        previewRect.set(
                columnLeft(col) + radiusInset,
                rowTop(row) + radiusInset,
                columnLeft(col) + spanWidth(colSpan) - radiusInset,
                rowTop(row) + spanHeight(rowSpan) - radiusInset);
        invalidate();

        if (haptic && activeTileView != null) {
            activeTileView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        return true;
    }

    private void applyCandidateLayout(int activeCol, int activeRow, int activeColSpan, int activeRowSpan) {
        if (activeTileView == null) {
            return;
        }
        restoreBaseline();

        Tile active = activeTileView.getTile();
        GridState activeBaseline = gestureBaseline.get(active);
        active.col = activeCol;
        active.row = activeRow;
        active.colSpan = activeColSpan;
        active.rowSpan = activeRowSpan;

        boolean[][] occupied = new boolean[MAX_GRID_ROWS][COLUMN_COUNT];
        markOccupied(occupied, active);

        List<Tile> remaining = new ArrayList<>(tiles);
        remaining.remove(active);
        Collections.sort(remaining, baselineComparator());

        for (Tile tile : remaining) {
            GridState baseline = gestureBaseline.get(tile);
            int preferredCol = baseline == null ? tile.col : baseline.col;
            int preferredRow = baseline == null ? tile.row : baseline.row;
            if (baseline != null && activeBaseline != null
                    && overlaps(baseline.col, baseline.row, baseline.colSpan, baseline.rowSpan,
                    activeCol, activeRow, activeColSpan, activeRowSpan)) {
                preferredCol = activeBaseline.col;
                preferredRow = activeBaseline.row;
            }
            preferredCol = clamp(preferredCol, 0, COLUMN_COUNT - tile.colSpan);
            preferredRow = Math.max(0, preferredRow);

            GridPoint point;
            if (isSlotFree(occupied, preferredCol, preferredRow, tile.colSpan, tile.rowSpan)) {
                point = new GridPoint(preferredCol, preferredRow);
            } else {
                point = findNearestSlot(occupied, preferredCol, preferredRow, tile.colSpan, tile.rowSpan);
            }
            tile.col = point.col;
            tile.row = point.row;
            markOccupied(occupied, tile);
        }

        animateNeighborsToModel();
    }

    private boolean overlaps(int firstCol, int firstRow, int firstColSpan, int firstRowSpan,
            int secondCol, int secondRow, int secondColSpan, int secondRowSpan) {
        return firstCol < secondCol + secondColSpan
                && firstCol + firstColSpan > secondCol
                && firstRow < secondRow + secondRowSpan
                && firstRow + firstRowSpan > secondRow;
    }

    private Comparator<Tile> baselineComparator() {
        return (left, right) -> {
            GridState a = gestureBaseline.get(left);
            GridState b = gestureBaseline.get(right);
            int aRow = a == null ? left.row : a.row;
            int bRow = b == null ? right.row : b.row;
            if (aRow != bRow) {
                return Integer.compare(aRow, bRow);
            }
            int aCol = a == null ? left.col : a.col;
            int bCol = b == null ? right.col : b.col;
            return Integer.compare(aCol, bCol);
        };
    }

    private void animateNeighborsToModel() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView) || child == activeTileView) {
                continue;
            }
            Tile tile = ((TileView) child).getTile();
            child.animate().cancel();
            child.animate()
                    .x(columnLeft(tile.col))
                    .y(rowTop(tile.row))
                    .setDuration(NEIGHBOR_DURATION_MS)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    private void settleInteraction(boolean cancelled) {
        if (activeTileView == null || settling) {
            return;
        }
        settling = true;
        settleShouldNotify = !cancelled;
        if (cancelled) {
            restoreBaseline();
        }

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView)) {
                continue;
            }
            Tile tile = ((TileView) child).getTile();
            child.animate().cancel();
            child.animate()
                    .x(columnLeft(tile.col))
                    .y(rowTop(tile.row))
                    .setDuration(SETTLE_DURATION_MS)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        }
        removeCallbacks(settleRunnable);
        postDelayed(settleRunnable, SETTLE_DURATION_MS + 20L);
    }

    private void completeInteraction(boolean notify) {
        removeCallbacks(settleRunnable);
        TileView previousActive = activeTileView;
        dragging = false;
        resizing = false;
        settling = false;
        settleShouldNotify = false;
        gestureMoved = false;
        activeTileView = null;
        gestureBaseline.clear();
        previewRect.setEmpty();
        resetPreviewState();

        forEachTileView(view -> {
            view.animate().cancel();
            view.setTranslationX(0f);
            view.setTranslationY(0f);
            view.setManipulating(false);
            view.updateSizeBadge();
        });
        if (previousActive != null) {
            previousActive.setManipulating(false);
        }
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        requestLayout();
        invalidate();
        if (notify) {
            notifyTilesChanged();
        }
    }

    private void cancelInteractionImmediately() {
        removeCallbacks(settleRunnable);
        if (!gestureBaseline.isEmpty()) {
            restoreBaseline();
        }
        dragging = false;
        resizing = false;
        settling = false;
        settleShouldNotify = false;
        gestureMoved = false;
        activeTileView = null;
        gestureBaseline.clear();
        previewRect.setEmpty();
        resetPreviewState();
        forEachTileView(view -> {
            view.animate().cancel();
            view.setTranslationX(0f);
            view.setTranslationY(0f);
            view.setManipulating(false);
        });
    }

    private void snapshotBaseline() {
        gestureBaseline.clear();
        for (Tile tile : tiles) {
            gestureBaseline.put(tile, new GridState(tile));
        }
    }

    private void restoreBaseline() {
        for (Map.Entry<Tile, GridState> entry : gestureBaseline.entrySet()) {
            entry.getValue().applyTo(entry.getKey());
        }
    }

    private void resetPreviewState() {
        lastPreviewCol = -1;
        lastPreviewRow = -1;
        lastPreviewColSpan = -1;
        lastPreviewRowSpan = -1;
    }

    private void addTileView(Tile tile) {
        TileView view = new TileView(getContext());
        view.setTile(tile);
        view.setEditMode(editMode);
        view.setOnTileClickListener(clicked -> {
            if (tileClickListener != null && !dragging && !resizing && !settling) {
                tileClickListener.onTileClick(tile);
            }
        });
        view.setOnDeleteListener(deleted -> removeTileView(deleted));
        addView(view);
    }

    private void removeTileView(TileView view) {
        if (!editMode || view == null || settling) {
            return;
        }
        Tile tile = view.getTile();
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .setDuration(140L)
                .withEndAction(() -> {
                    removeView(view);
                    tiles.remove(tile);
                    normalizeLayout();
                    requestLayout();
                    notifyTilesChanged();
                })
                .start();
    }

    private void normalizeLayout() {
        boolean[][] occupied = new boolean[MAX_GRID_ROWS][COLUMN_COUNT];
        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt((Tile tile) -> tile.row).thenComparingInt(tile -> tile.col));
        for (Tile tile : sorted) {
            sanitizeSize(tile);
            int preferredCol = clamp(tile.col, 0, COLUMN_COUNT - tile.colSpan);
            int preferredRow = Math.max(0, tile.row);
            GridPoint point = isSlotFree(occupied, preferredCol, preferredRow, tile.colSpan, tile.rowSpan)
                    ? new GridPoint(preferredCol, preferredRow)
                    : findNearestSlot(occupied, preferredCol, preferredRow, tile.colSpan, tile.rowSpan);
            tile.col = point.col;
            tile.row = point.row;
            markOccupied(occupied, tile);
        }
    }

    private void sanitizeSize(Tile tile) {
        tile.colSpan = clamp(tile.colSpan <= 0 ? 1 : tile.colSpan, 1, COLUMN_COUNT);
        tile.rowSpan = clamp(tile.rowSpan <= 0 ? 1 : tile.rowSpan, 1, MAX_TILE_ROW_SPAN);
        tile.col = clamp(tile.col, 0, COLUMN_COUNT - tile.colSpan);
        tile.row = Math.max(0, tile.row);
    }

    private boolean[][] buildOccupiedGrid(@Nullable Tile excluded) {
        boolean[][] occupied = new boolean[MAX_GRID_ROWS][COLUMN_COUNT];
        for (Tile tile : tiles) {
            if (tile != excluded) {
                markOccupied(occupied, tile);
            }
        }
        return occupied;
    }

    private GridPoint findNearestSlot(boolean[][] occupied, int preferredCol, int preferredRow,
            int colSpan, int rowSpan) {
        preferredCol = clamp(preferredCol, 0, COLUMN_COUNT - colSpan);
        preferredRow = clamp(preferredRow, 0, MAX_GRID_ROWS - rowSpan);
        GridPoint best = null;
        int bestScore = Integer.MAX_VALUE;

        int searchBottom = MAX_GRID_ROWS - rowSpan;
        for (int row = 0; row <= searchBottom; row++) {
            for (int col = 0; col <= COLUMN_COUNT - colSpan; col++) {
                if (!isSlotFree(occupied, col, row, colSpan, rowSpan)) {
                    continue;
                }
                int verticalDistance = Math.abs(row - preferredRow);
                int horizontalDistance = Math.abs(col - preferredCol);
                int score = verticalDistance * 10 + horizontalDistance * 3;
                if (score < bestScore) {
                    best = new GridPoint(col, row);
                    bestScore = score;
                }
            }
        }
        return best != null ? best : new GridPoint(0, Math.max(0, getContentRowCount()));
    }

    private boolean isSlotFree(boolean[][] occupied, int col, int row, int colSpan, int rowSpan) {
        if (col < 0 || row < 0 || col + colSpan > COLUMN_COUNT || row + rowSpan > occupied.length) {
            return false;
        }
        for (int r = row; r < row + rowSpan; r++) {
            for (int c = col; c < col + colSpan; c++) {
                if (occupied[r][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    private void markOccupied(boolean[][] occupied, Tile tile) {
        for (int row = tile.row; row < Math.min(tile.row + tile.rowSpan, occupied.length); row++) {
            for (int col = tile.col; col < Math.min(tile.col + tile.colSpan, COLUMN_COUNT); col++) {
                if (row >= 0 && col >= 0) {
                    occupied[row][col] = true;
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int desiredCellWidth = COLUMN_COUNT == 0
                ? 0
                : Math.max(0, (availableWidth - (COLUMN_COUNT - 1) * gap) / COLUMN_COUNT);
        cellWidth = maxCellSizePx > 0 ? Math.min(desiredCellWidth, maxCellSizePx) : desiredCellWidth;
        cellHeight = Math.round(cellWidth * CELL_HEIGHT_RATIO);

        int gridWidth = COLUMN_COUNT * cellWidth + Math.max(0, COLUMN_COUNT - 1) * gap;
        gridOffsetX = getPaddingLeft() + Math.max(0, (availableWidth - gridWidth) / 2);

        int rowCount = getContentRowCount() + (editMode ? EXTRA_EDIT_ROWS : 0);
        int totalHeight = getPaddingTop() + getPaddingBottom();
        if (rowCount > 0) {
            totalHeight += rowCount * cellHeight + (rowCount - 1) * gap;
        }

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView)) {
                continue;
            }
            Tile tile = ((TileView) child).getTile();
            child.measure(
                    MeasureSpec.makeMeasureSpec(spanWidth(tile.colSpan), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(spanHeight(tile.rowSpan), MeasureSpec.EXACTLY));
        }

        int measuredWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? gridWidth + getPaddingLeft() + getPaddingRight()
                : width;
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TileView)) {
                continue;
            }
            Tile tile = ((TileView) child).getTile();
            int childLeft = Math.round(columnLeft(tile.col));
            int childTop = Math.round(rowTop(tile.row));
            child.layout(childLeft, childTop,
                    childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
            if (!dragging && !resizing && !settling) {
                child.setTranslationX(0f);
                child.setTranslationY(0f);
            }
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!editMode || cellWidth <= 0 || cellHeight <= 0) {
            return;
        }

        float inset = dp(2);
        float radius = dp(7);
        int rows = getContentRowCount() + EXTRA_EDIT_ROWS;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLUMN_COUNT; col++) {
                gridSlotRect.set(
                        columnLeft(col) + inset,
                        rowTop(row) + inset,
                        columnLeft(col) + cellWidth - inset,
                        rowTop(row) + cellHeight - inset);
                canvas.drawRoundRect(gridSlotRect, radius, radius, gridPaint);
            }
        }

        if (!previewRect.isEmpty()) {
            canvas.drawRoundRect(previewRect, radius, radius, previewFillPaint);
            canvas.drawRoundRect(previewRect, radius, radius, previewStrokePaint);
        }
    }

    private int snapColumn(float visualLeft, int colSpan) {
        return clamp(Math.round((visualLeft - gridOffsetX) / (float) columnPitch()),
                0, COLUMN_COUNT - colSpan);
    }

    private int getContentRowCount() {
        int rows = 0;
        for (Tile tile : tiles) {
            rows = Math.max(rows, tile.row + tile.rowSpan);
        }
        return rows;
    }

    private int spanWidth(int colSpan) {
        return colSpan * cellWidth + Math.max(0, colSpan - 1) * gap;
    }

    private int spanHeight(int rowSpan) {
        return rowSpan * cellHeight + Math.max(0, rowSpan - 1) * gap;
    }

    private int columnPitch() {
        return Math.max(1, cellWidth + gap);
    }

    private int rowPitch() {
        return Math.max(1, cellHeight + gap);
    }

    private float columnLeft(int col) {
        return gridOffsetX + col * columnPitch();
    }

    private float rowTop(int row) {
        return getPaddingTop() + row * rowPitch();
    }

    private void notifyTilesChanged() {
        if (tilesChangedListener != null) {
            tilesChangedListener.onTilesChanged(getTiles());
        }
    }

    @Nullable
    private TileView findViewForTile(Tile tile) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView && ((TileView) child).getTile() == tile) {
                return (TileView) child;
            }
        }
        return null;
    }

    private interface TileViewAction {
        void run(TileView view);
    }

    private void forEachTileView(TileViewAction action) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                action.run((TileView) child);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, float alpha) {
        int resultAlpha = Math.round(Color.alpha(color) * alpha);
        return (color & 0x00FFFFFF) | (resultAlpha << 24);
    }
}
