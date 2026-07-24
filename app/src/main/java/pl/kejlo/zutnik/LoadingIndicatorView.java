package pl.kejlo.zutnik;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class LoadingIndicatorView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private ValueAnimator animator;
    private float phase;
    private int primaryColor;
    private int mutedColor;

    public LoadingIndicatorView(Context context) {
        this(context, null);
    }

    public LoadingIndicatorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LoadingIndicatorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        primaryColor = ThemeManager.resolveColor(context, R.attr.mzPrimary);
        mutedColor = ThemeManager.resolveColor(context, R.attr.mzBorderStrong);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = dp(46);
        int desiredHeight = dp(32);
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float barWidth = Math.max(dp(4), width * 0.11f);
        float gap = barWidth * 0.85f;
        float total = barWidth * 3f + gap * 2f;
        float startX = (width - total) / 2f;
        float centerY = height / 2f;
        float minHeight = height * 0.24f;
        float maxHeight = height * 0.72f;
        float radius = barWidth / 2f;

        for (int i = 0; i < 3; i++) {
            float wave = (float) ((Math.sin(phase + i * 1.35f) + 1.0) * 0.5);
            float currentHeight = minHeight + (maxHeight - minHeight) * wave;
            paint.setColor(i == 1 ? primaryColor : mutedColor);
            paint.setAlpha(i == 1 ? 255 : 150 + Math.round(70f * wave));
            float left = startX + i * (barWidth + gap);
            bar.set(left, centerY - currentHeight / 2f, left + barWidth, centerY + currentHeight / 2f);
            canvas.drawRoundRect(bar, radius, radius, paint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isAttachedToWindow()) {
            updateAnimator();
        }
    }

    private void updateAnimator() {
        if (getVisibility() == VISIBLE && isShown()) {
            startAnimator();
        } else {
            stopAnimator();
        }
    }

    private void startAnimator() {
        if (animator != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            phase = (float) (Math.PI * 0.5);
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        animator.setDuration(980L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(valueAnimator -> {
            phase = (float) valueAnimator.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
