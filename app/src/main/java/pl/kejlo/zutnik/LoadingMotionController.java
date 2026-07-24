package pl.kejlo.zutnik;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class LoadingMotionController {

    private static final long SKELETON_DURATION_MS = 1120L;
    private static final long REVEAL_DURATION_MS = 240L;
    private static final Map<View, ValueAnimator> SKELETON_ANIMATORS = new WeakHashMap<>();
    private static final Map<View, ObjectAnimator> SPIN_ANIMATORS = new WeakHashMap<>();
    private static final Map<View, View.OnAttachStateChangeListener> SKELETON_DETACH_LISTENERS =
            new WeakHashMap<>();
    private static final Map<View, View.OnAttachStateChangeListener> SPIN_DETACH_LISTENERS =
            new WeakHashMap<>();

    private LoadingMotionController() {
    }

    static void showSkeleton(@Nullable View skeleton, @Nullable View content) {
        if (content != null) {
            content.animate().cancel();
            content.setVisibility(View.GONE);
        }
        if (skeleton == null) {
            return;
        }
        skeleton.animate().cancel();
        skeleton.setAlpha(1f);
        skeleton.setVisibility(View.VISIBLE);
        startSkeleton(skeleton);
    }

    static void revealContent(@Nullable View skeleton, @Nullable View content) {
        if (skeleton != null) {
            stopSkeleton(skeleton);
            skeleton.animate().cancel();
            skeleton.animate()
                    .alpha(0f)
                    .setDuration(140L)
                    .withEndAction(() -> {
                        skeleton.setVisibility(View.GONE);
                        skeleton.setAlpha(1f);
                    })
                    .start();
        }
        if (content == null) {
            return;
        }
        content.animate().cancel();
        float offset = 7f * content.getResources().getDisplayMetrics().density;
        content.setVisibility(View.VISIBLE);
        content.setAlpha(0f);
        content.setTranslationY(offset);
        content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(REVEAL_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.45f))
                .start();
    }

    static void startSkeleton(@Nullable View skeleton) {
        if (skeleton == null || SKELETON_ANIMATORS.containsKey(skeleton)) {
            return;
        }

        List<View> blocks = new ArrayList<>();
        collectBlocks(skeleton, blocks);
        if (blocks.isEmpty()) {
            blocks.add(skeleton);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            for (View block : blocks) {
                block.setAlpha(0.72f);
            }
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SKELETON_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(valueAnimator -> {
            float progress = (float) valueAnimator.getAnimatedValue();
            int count = Math.max(1, blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                float phase = progress + (i / (float) count) * 0.7f;
                float wave = (float) ((Math.sin(phase * Math.PI * 2.0 - Math.PI / 2.0) + 1.0) * 0.5);
                blocks.get(i).setAlpha(0.58f + wave * 0.38f);
            }
        });
        animator.start();
        SKELETON_ANIMATORS.put(skeleton, animator);
        View.OnAttachStateChangeListener detachListener =
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        stopSkeleton(view);
                    }
                };
        skeleton.addOnAttachStateChangeListener(detachListener);
        SKELETON_DETACH_LISTENERS.put(skeleton, detachListener);
    }

    static void stopSkeleton(@Nullable View skeleton) {
        if (skeleton == null) {
            return;
        }
        ValueAnimator animator = SKELETON_ANIMATORS.remove(skeleton);
        if (animator != null) {
            animator.cancel();
        }
        View.OnAttachStateChangeListener detachListener =
                SKELETON_DETACH_LISTENERS.remove(skeleton);
        if (detachListener != null) {
            skeleton.removeOnAttachStateChangeListener(detachListener);
        }
        List<View> blocks = new ArrayList<>();
        collectBlocks(skeleton, blocks);
        for (View block : blocks) {
            block.setAlpha(1f);
        }
    }

    static void setRefreshing(@Nullable View indicator, boolean refreshing) {
        if (indicator == null) {
            return;
        }
        ObjectAnimator current = SPIN_ANIMATORS.remove(indicator);
        if (current != null) {
            current.cancel();
        }
        View.OnAttachStateChangeListener oldDetachListener =
                SPIN_DETACH_LISTENERS.remove(indicator);
        if (oldDetachListener != null) {
            indicator.removeOnAttachStateChangeListener(oldDetachListener);
        }
        indicator.animate().cancel();
        if (!refreshing) {
            indicator.animate()
                    .rotation(0f)
                    .alpha(1f)
                    .setDuration(160L)
                    .start();
            indicator.setEnabled(true);
            return;
        }

        indicator.setEnabled(false);
        indicator.setAlpha(0.72f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        ObjectAnimator spinner = ObjectAnimator.ofFloat(indicator, View.ROTATION, 0f, 360f);
        spinner.setDuration(820L);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.start();
        SPIN_ANIMATORS.put(indicator, spinner);
        View.OnAttachStateChangeListener detachListener =
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        ObjectAnimator detachedAnimator = SPIN_ANIMATORS.remove(view);
                        if (detachedAnimator != null) {
                            detachedAnimator.cancel();
                        }
                        View.OnAttachStateChangeListener listener =
                                SPIN_DETACH_LISTENERS.remove(view);
                        if (listener != null) {
                            view.removeOnAttachStateChangeListener(listener);
                        }
                    }
                };
        indicator.addOnAttachStateChangeListener(detachListener);
        SPIN_DETACH_LISTENERS.put(indicator, detachListener);
    }

    private static void collectBlocks(View view, List<View> out) {
        Object tag = view.getTag();
        if ("skeleton_block".equals(tag)) {
            out.add(view);
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectBlocks(group.getChildAt(i), out);
        }
    }
}
