package pl.kejlo.zutnik;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;

public class TileView extends FrameLayout {

    public interface OnDeleteListener {
        void onDelete(TileView view);
    }

    private Tile tile;
    private LinearLayout contentContainer;
    private FrameLayout iconSurface;
    private TextView textTitle;
    private TextView textDesc;
    private TextView textSize;
    private ImageView iconView;
    private MaterialCardView cardContent;
    private View editBorder;
    private ImageView btnDelete;
    private ImageView btnResize;
    private ImageView btnDrag;

    private boolean editMode;
    private boolean manipulating;
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
        setClipChildren(false);
        setClipToPadding(false);
        LayoutInflater.from(getContext()).inflate(R.layout.item_tile, this, true);

        contentContainer = findViewById(R.id.contentContainer);
        iconSurface = findViewById(R.id.iconSurface);
        textTitle = findViewById(R.id.textTitle);
        textDesc = findViewById(R.id.textDesc);
        textSize = findViewById(R.id.textTileSize);
        iconView = findViewById(R.id.iconView);
        cardContent = findViewById(R.id.cardContent);
        editBorder = findViewById(R.id.editBorder);
        btnDelete = findViewById(R.id.btnDelete);
        btnResize = findViewById(R.id.btnResize);
        btnDrag = findViewById(R.id.btnDrag);

        btnDelete.setOnClickListener(view -> {
            if (deleteListener != null) {
                deleteListener.onDelete(this);
            }
        });
        attachMoveHandle(btnDrag, false);
        attachMoveHandle(btnResize, true);
    }

    private void attachMoveHandle(View handle, boolean resize) {
        handle.setOnTouchListener((view, event) -> {
            if (!(getParent() instanceof TileGridLayout)) {
                return false;
            }
            TileGridLayout grid = (TileGridLayout) getParent();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (resize) {
                        grid.startResizing(this, event.getRawX(), event.getRawY());
                    } else {
                        grid.startDragging(this, event.getRawX(), event.getRawY());
                    }
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    view.performClick();
                    grid.finishHandleTap();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    public void setTile(Tile tile) {
        this.tile = tile;
        bindContent();
        refreshResponsiveContent();
    }

    private void bindContent() {
        if (tile == null) {
            return;
        }
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
        // Safe initial state until the measured size selects the final compact layout.
        textTitle.setVisibility(VISIBLE);
        textTitle.setAlpha(1f);
        textDesc.setAlpha(1f);
        iconSurface.setAlpha(1f);
        iconView.setImageResource(getIconForAction(tile));
        updateSizeBadge();
        applyColors();
    }

    private void applyColors() {
        int background = tile.color != 0
                ? tile.color
                : ThemeManager.resolveColor(getContext(), R.attr.mzCardSoft);
        int text = tile.color != 0
                ? (ColorUtils.calculateLuminance(background) < 0.46 ? Color.WHITE : Color.BLACK)
                : ThemeManager.resolveColor(getContext(), R.attr.mzText);
        int muted = tile.color != 0
                ? ColorUtils.setAlphaComponent(text, 184)
                : ThemeManager.resolveColor(getContext(), R.attr.mzMuted);
        int accent = tile.color != 0
                ? text
                : ThemeManager.resolveColor(getContext(), R.attr.mzPrimary);

        cardContent.setCardBackgroundColor(background);
        textTitle.setTextColor(text);
        textDesc.setTextColor(muted);
        ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(accent));
        ImageViewCompat.setImageTintList(btnDrag, ColorStateList.valueOf(accent));
        ImageViewCompat.setImageTintList(btnResize, ColorStateList.valueOf(accent));
        iconSurface.setBackgroundTintList(ColorStateList.valueOf(
                ColorUtils.blendARGB(background, accent, tile.color == 0 ? 0.10f : 0.16f)));

        cardContent.setStrokeColor(manipulating
                ? ThemeManager.resolveColor(getContext(), R.attr.mzPrimary)
                : ThemeManager.resolveColor(getContext(), R.attr.mzBorderSoft));
        cardContent.setStrokeWidth(dp(manipulating ? 2 : 1));
    }

    private void applyResponsiveContent(int width, int height) {
        if (tile == null || width <= 0 || height <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float widthDp = width / density;
        float heightDp = height / density;
        boolean iconOnly = widthDp < 92f && heightDp < 96f;
        boolean narrow = widthDp < 124f;
        boolean shortTile = heightDp < 112f;
        boolean showIcon = iconOnly || (!narrow && heightDp >= 126f);
        boolean showDescription = !editMode && widthDp >= 142f && heightDp >= 138f
                && textDesc.getText() != null && textDesc.length() > 0;

        int horizontalPadding = narrow ? 11 : 15;
        int verticalPadding = shortTile ? 10 : 14;
        int horizontalPaddingPx = dp(horizontalPadding);
        int verticalPaddingPx = dp(verticalPadding);
        if (contentContainer.getPaddingLeft() != horizontalPaddingPx
                || contentContainer.getPaddingTop() != verticalPaddingPx
                || contentContainer.getPaddingRight() != horizontalPaddingPx
                || contentContainer.getPaddingBottom() != verticalPaddingPx) {
            contentContainer.setPadding(horizontalPaddingPx, verticalPaddingPx,
                    horizontalPaddingPx, verticalPaddingPx);
        }

        if (iconOnly) {
            contentContainer.setGravity(Gravity.CENTER);
            iconSurface.setVisibility(VISIBLE);
            textTitle.setVisibility(GONE);
            textDesc.setVisibility(GONE);
            return;
        }

        contentContainer.setGravity(Gravity.START | (shortTile ? Gravity.CENTER_VERTICAL : Gravity.TOP));
        iconSurface.setVisibility(showIcon ? VISIBLE : GONE);
        textTitle.setVisibility(VISIBLE);
        textDesc.setVisibility(showDescription ? VISIBLE : GONE);

        textTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, narrow ? 14f : 16f);
        textTitle.setMaxLines(narrow ? Math.max(2, Math.min(5, Math.round(heightDp / 30f)))
                : (showDescription ? 2 : Math.max(2, Math.min(4, Math.round(heightDp / 32f)))));
        textDesc.setMaxLines(heightDp >= 196f ? 3 : 2);

        LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) iconSurface.getLayoutParams();
        int iconBottomMargin = dp(heightDp < 154f ? 7 : 11);
        if (iconParams.bottomMargin != iconBottomMargin) {
            iconParams.bottomMargin = iconBottomMargin;
            iconSurface.setLayoutParams(iconParams);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyResponsiveContent(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void refreshResponsiveContent() {
        if (getWidth() > 0 && getHeight() > 0) {
            applyResponsiveContent(getWidth(), getHeight());
        }
        requestLayout();
    }

    public Tile getTile() {
        return tile;
    }

    public void setEditMode(boolean enabled) {
        editMode = enabled;
        setControlVisible(editBorder, enabled, 0L);
        setControlVisible(btnDrag, enabled, 20L);
        setControlVisible(btnDelete, enabled, 45L);
        setControlVisible(btnResize, enabled, 70L);
        setControlVisible(textSize, enabled && hasRoomForSizeBadge(), 80L);
        cardContent.setForeground(enabled ? null : selectableForeground());
        refreshResponsiveContent();
    }

    private void setControlVisible(View view, boolean visible, long delay) {
        view.animate().cancel();
        if (visible) {
            view.setVisibility(VISIBLE);
            view.setAlpha(0f);
            view.setScaleX(0.86f);
            view.setScaleY(0.86f);
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(delay).setDuration(150L).start();
        } else {
            view.setVisibility(GONE);
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    public void setManipulating(boolean active) {
        manipulating = active;
        animate().cancel();
        animate().scaleX(active ? 1.025f : 1f).scaleY(active ? 1.025f : 1f)
                .setDuration(active ? 110L : 150L).start();
        cardContent.setCardElevation(dp(active ? 8 : 0));
        applyColors();
    }

    public void updateSizeBadge() {
        if (tile != null) {
            textSize.setText(getResources().getString(R.string.home_tile_size,
                    tile.colSpan, tile.rowSpan));
            if (editMode) {
                textSize.setVisibility(hasRoomForSizeBadge() ? VISIBLE : GONE);
            }
        }
    }

    private boolean hasRoomForSizeBadge() {
        return tile != null && tile.colSpan > 1 && tile.rowSpan > 1;
    }

    public void setOnDeleteListener(OnDeleteListener listener) {
        deleteListener = listener;
    }

    public void setOnTileClickListener(@Nullable View.OnClickListener listener) {
        cardContent.setOnClickListener(listener);
    }

    private android.graphics.drawable.Drawable selectableForeground() {
        TypedValue out = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            return AppCompatResources.getDrawable(getContext(), out.resourceId);
        }
        return null;
    }

    private int getIconForAction(Tile value) {
        String action = value.actionType;
        if (action == null) {
            return R.drawable.ic_android;
        }
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
                if (value.actionData != null && value.actionData.contains("UsefulLinksActivity")) {
                    return R.drawable.ic_link;
                }
                if (value.actionData != null && value.actionData.contains("AboutActivity")) {
                    return R.drawable.ic_info;
                }
                return R.drawable.ic_android;
            default:
                return R.drawable.ic_android;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
