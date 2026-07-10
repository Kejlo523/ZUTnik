package pl.kejlo.zutnik;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TileView extends FrameLayout {

    private Tile tile;
    private TextView textTitle;
    private TextView textDesc;
    private ImageView iconView;
    private androidx.cardview.widget.CardView cardContent;
    private View editBorder;
    private ImageView btnDelete;
    private ImageView btnResizeRight;
    private ImageView btnResizeLeft;
    private ImageView btnDrag;

    private boolean isEditMode = false;

    // Listeners for edit actions
    public interface OnDeleteListener {
        void onDelete(TileView view);
    }

    private OnDeleteListener deleteListener;

    public TileView(@NonNull Context context) {
        super(context);
        init();
    }

    public TileView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.item_tile, this, true);
        textTitle = findViewById(R.id.textTitle);
        textDesc = findViewById(R.id.textDesc);
        iconView = findViewById(R.id.iconView);
        cardContent = findViewById(R.id.cardContent);
        editBorder = findViewById(R.id.editBorder);
        btnDelete = findViewById(R.id.btnDelete);
        btnResizeRight = findViewById(R.id.btnResizeRight);
        btnResizeLeft = findViewById(R.id.btnResizeLeft);
        btnDrag = findViewById(R.id.btnDrag);

        btnDelete.setOnClickListener(v -> {
            if (deleteListener != null)
                deleteListener.onDelete(this);
        });

        // Resize handle logic Right
        btnResizeRight.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getParent() instanceof TileGridLayout) {
                    ((TileGridLayout) getParent()).startResizing(TileView.this, 1);
                }
            }
            return true;
        });

        // Resize handle logic Left
        btnResizeLeft.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getParent() instanceof TileGridLayout) {
                    ((TileGridLayout) getParent()).startResizing(TileView.this, -1);
                }
            }
            return true;
        });

        // Drag handle logic
        btnDrag.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getParent() instanceof TileGridLayout) {
                    ((TileGridLayout) getParent()).startDragging(TileView.this, event.getRawX(), event.getRawY());
                }
            }
            return true;
        });
    }

    public void setTile(Tile tile) {
        this.tile = tile;
        updateLayout();
    }

    private void updateLayout() {
        if (tile == null)
            return;

        // Content
        if (tile.titleResId != 0) {
            textTitle.setText(tile.titleResId);
        } else {
            textTitle.setText(tile.title);
        }

        if (tile.descResId != 0) {
            textDesc.setText(tile.descResId);
        } else {
            textDesc.setText(tile.description);
        }

        // Mode: 1x1 (Icon), 1x2 (Small Title), Default (Normal)
        boolean is1x1 = (tile.colSpan == 1 && tile.rowSpan == 1);
        boolean is1x2 = (tile.colSpan == 1 && tile.rowSpan == 2); // Unlikely to be row=2 col=1? But possible vertical.
        // Wait, user said "if 1x2 to only title small font". Usually width x height.
        // Let's assume user meant 1 column X 2 rows (vertical), or maybe 2 columns X 1
        // row?
        // "1x2 -> small font". 1 width, 2 height is TALL.
        // If 2 width, 1 height (WIDE) -> plenty of space.
        // Let's assume standard grid notation: Col x Row.
        // If 1x1: Icon.
        // If 1x2 (1 wide, 2 high): Title Only.
        // If 2x1 (2 wide, 1 high): Title + Desc ? Or Title only if short?
        // Let's stick strictly to what user implies: small tiles get simplified.

        if (is1x1) {
            // Show Icon, Hide Text
            textTitle.setVisibility(GONE);
            textDesc.setVisibility(GONE);
            iconView.setVisibility(VISIBLE);

            // Resolve icon
            iconView.setImageResource(getIconForAction(tile));

        } else if (tile.rowSpan == 1 || tile.colSpan == 1) {
            // Small/Narrow tiles (1x2, 2x1, 3x1, 1x3 etc) - logic: hide desc, small title
            iconView.setVisibility(GONE);
            textTitle.setVisibility(VISIBLE);
            textDesc.setVisibility(GONE);
            textTitle.setTextSize(15f);
            textTitle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        } else {
            // Default (Big tiles, e.g. 2x2, 2x4)
            iconView.setVisibility(VISIBLE);
            iconView.setImageResource(getIconForAction(tile));
            textTitle.setVisibility(VISIBLE);
            textDesc.setVisibility(VISIBLE);
            textTitle.setTextSize(17f);
            textTitle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); // Reset to default
            // Ensure textDesc is also aligned if needed, usually it is below title
        }

        // Color Logic
        if (tile.color != 0) {
            cardContent.setCardBackgroundColor(tile.color);
            boolean isDark = androidx.core.graphics.ColorUtils.calculateLuminance(tile.color) < 0.5;
            int tint = isDark ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;

            textTitle.setTextColor(tint);
            textDesc.setTextColor(isDark ? 0xFFCCCCCC : 0xFF666666); // Muted variant
            iconView.setColorFilter(tint);
        } else {
            // Default colors (Theme-dependent)
            cardContent.setCardBackgroundColor(ThemeManager.resolveColor(getContext(), R.attr.mzCardSoft));
            textTitle.setTextColor(ThemeManager.resolveColor(getContext(), R.attr.mzText));
            textDesc.setTextColor(ThemeManager.resolveColor(getContext(), R.attr.mzMuted));
            iconView.setColorFilter(ThemeManager.resolveColor(getContext(), R.attr.mzPrimary));
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (tile == null || tile.colSpan <= 1 || tile.rowSpan <= 1) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        boolean compact = width < 150f * density || height < 132f * density;
        textDesc.setVisibility(compact ? GONE : VISIBLE);
        textTitle.setMaxLines(compact ? 2 : 3);
        ViewGroup.MarginLayoutParams iconParams = (ViewGroup.MarginLayoutParams) iconView.getLayoutParams();
        iconParams.bottomMargin = Math.round((compact ? 6f : 10f) * density);
        iconView.setLayoutParams(iconParams);
    }

    private int getIconForAction(Tile t) {
        String action = t.actionType;
        if (action == null)
            return R.drawable.ic_android;

        switch (action) {
            case Tile.ACTION_PLAN:
                return R.drawable.ic_calendar;
            case Tile.ACTION_GRADES:
                return R.drawable.ic_school;
            case Tile.ACTION_NEWS:
                return R.drawable.ic_newspaper;
            case Tile.ACTION_NEWS_LATEST:
                return R.drawable.ic_flash_on;
            case Tile.ACTION_INFO:
                return R.drawable.ic_map;
            case Tile.ACTION_URL:
                return R.drawable.ic_link;
            case Tile.ACTION_PLAN_SEARCH:
                return R.drawable.ic_search;
            case Tile.ACTION_ACTIVITY:
                // Check data
                if (t.actionData != null) {
                    if (t.actionData.contains("UsefulLinksActivity"))
                        return R.drawable.ic_link;
                    if (t.actionData.contains("AboutActivity"))
                        return R.drawable.ic_info;
                }
                return R.drawable.ic_android;
            default:
                return R.drawable.ic_android;
        }
    }

    public Tile getTile() {
        return tile;
    }

    public void setEditMode(boolean editMode) {
        this.isEditMode = editMode;
        if (editMode) {
            editBorder.setVisibility(VISIBLE);
            btnDelete.setVisibility(VISIBLE);
            btnResizeRight.setVisibility(VISIBLE);
            btnResizeLeft.setVisibility(VISIBLE);
            btnDrag.setVisibility(VISIBLE);
            cardContent.setForeground(null);

            // Visual "Shrink" for edit mode feeling
            animate().scaleX(0.92f).scaleY(0.92f).setDuration(200).start();
        } else {
            editBorder.setVisibility(GONE);
            btnDelete.setVisibility(GONE);
            btnResizeRight.setVisibility(GONE);
            btnResizeLeft.setVisibility(GONE);
            btnDrag.setVisibility(GONE);
            restoreCardRipple();

            // Restore scale
            animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        }
    }

    public void setOnDeleteListener(OnDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setOnTileClickListener(@Nullable View.OnClickListener listener) {
        cardContent.setOnClickListener(listener);
    }

    private void restoreCardRipple() {
        TypedValue out = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            cardContent.setForeground(
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(getContext(), out.resourceId));
        }
    }
}
