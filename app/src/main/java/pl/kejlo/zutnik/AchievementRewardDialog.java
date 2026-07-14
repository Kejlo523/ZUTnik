package pl.kejlo.zutnik;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class AchievementRewardDialog {

    private AchievementRewardDialog() {
    }

    static void show(Activity activity, AchievementManager.Record record) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || record == null
                || !record.isUnlocked()) {
            return;
        }

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_about_easter_egg);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        View root = dialog.findViewById(R.id.easterRoot);
        View rewardCard = dialog.findViewById(R.id.easterRewardCard);
        View accentLine = dialog.findViewById(R.id.easterAccentLine);
        ImageButton closeButton = dialog.findViewById(R.id.easterCloseButton);
        ImageView centerLogo = dialog.findViewById(R.id.easterCenterLogo);
        TextView title = dialog.findViewById(R.id.easterRewardTitle);
        TextView message = dialog.findViewById(R.id.easterRewardMessage);
        TextView discoveryDate = dialog.findViewById(R.id.easterDiscoveryDate);
        TextView discoveryCode = dialog.findViewById(R.id.easterDiscoveryCode);
        TextView savedMessage = dialog.findViewById(R.id.easterSavedMessage);
        View doneButton = dialog.findViewById(R.id.easterDoneButton);

        AchievementManager.Achievement achievement = record.achievement;
        title.setText(achievement.titleRes);
        message.setText(achievement.descriptionRes);
        centerLogo.setImageResource(achievement.iconRes);
        centerLogo.setContentDescription(activity.getString(achievement.titleRes));
        if (achievement.tintIcon) {
            centerLogo.setColorFilter(ContextCompat.getColor(activity, android.R.color.white));
            centerLogo.setPadding(dpToPx(activity, 25f), dpToPx(activity, 25f),
                    dpToPx(activity, 25f), dpToPx(activity, 25f));
        } else {
            centerLogo.clearColorFilter();
            centerLogo.setPadding(dpToPx(activity, 13f), dpToPx(activity, 13f),
                    dpToPx(activity, 13f), dpToPx(activity, 13f));
        }

        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale);
        discoveryDate.setText(Instant.ofEpochMilli(record.unlockedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(formatter));
        discoveryCode.setText(record.code);
        savedMessage.setText(R.string.easter_saved_message);

        root.setOnClickListener(v -> dialog.dismiss());
        rewardCard.setOnClickListener(v -> { });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        doneButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            dialog.dismiss();
        });

        rewardCard.setAlpha(0f);
        rewardCard.setTranslationY(dpToPx(activity, 28f));
        rewardCard.setScaleX(0.98f);
        rewardCard.setScaleY(0.98f);
        centerLogo.setScaleX(0.82f);
        centerLogo.setScaleY(0.82f);
        centerLogo.setRotation(-8f);
        accentLine.setPivotX(0f);
        accentLine.setScaleX(0f);
        doneButton.setAlpha(0f);
        doneButton.setTranslationY(dpToPx(activity, 10f));

        dialog.setOnShowListener(ignored -> {
            DecelerateInterpolator interpolator = new DecelerateInterpolator();
            rewardCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420L)
                    .setInterpolator(interpolator)
                    .start();
            centerLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setStartDelay(90L)
                    .setDuration(480L)
                    .setInterpolator(interpolator)
                    .start();
            accentLine.animate()
                    .scaleX(1f)
                    .setStartDelay(120L)
                    .setDuration(520L)
                    .setInterpolator(interpolator)
                    .start();
            doneButton.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(220L)
                    .setDuration(320L)
                    .setInterpolator(interpolator)
                    .start();
        });

        dialog.show();
        rewardCard.post(() -> {
            int availableWidth = root.getWidth() - dpToPx(activity, 32f);
            int targetWidth = Math.min(availableWidth, dpToPx(activity, 440f));
            ViewGroup.LayoutParams layoutParams = rewardCard.getLayoutParams();
            layoutParams.width = Math.max(0, targetWidth);
            rewardCard.setLayoutParams(layoutParams);
        });
    }

    private static int dpToPx(Activity activity, float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
