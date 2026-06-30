package pl.kejlo.zutnik;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class TabContentAnimator {

    private static final int FADE_DURATION_MS = 160;

    private TabContentAnimator() {
    }

    static void playFadeIn(View content) {
        if (content == null) {
            return;
        }
        content.animate().cancel();
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.setTranslationY(0f);
        content.setAlpha(0.88f);
        content.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.1f))
                .start();
    }

    static void revealContent(View skeleton, View content) {
        if (skeleton != null) {
            skeleton.setVisibility(View.GONE);
        }
        if (content == null) {
            return;
        }
        content.setVisibility(View.VISIBLE);
        View fadeTarget = content;
        if (content.getId() == R.id.homeContentRoot && content instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) content;
            if (group.getChildCount() > 1) {
                fadeTarget = group.getChildAt(1);
            }
        }
        playFadeIn(fadeTarget);
    }
}
