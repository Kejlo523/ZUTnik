package pl.kejlo.zutnik;

import android.app.Activity;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class AchievementDetailsDialog {

    private AchievementDetailsDialog() {
    }

    static void show(Activity activity, AchievementManager.Record record) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || record == null) {
            return;
        }

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_achievement_details, null, false);
        ImageView icon = content.findViewById(R.id.achievementDetailsIcon);
        TextView state = content.findViewById(R.id.achievementDetailsState);
        TextView title = content.findViewById(R.id.achievementDetailsTitle);
        TextView description = content.findViewById(R.id.achievementDetailsDescription);
        View discoveryBlock = content.findViewById(R.id.achievementDetailsDiscoveryBlock);
        TextView date = content.findViewById(R.id.achievementDetailsDate);
        TextView code = content.findViewById(R.id.achievementDetailsCode);

        AchievementManager.Achievement achievement = record.achievement;
        boolean unlocked = record.isUnlocked();
        icon.setImageResource(achievement.iconRes);
        icon.setContentDescription(activity.getString(achievement.titleRes));
        title.setText(achievement.titleRes);
        state.setText(unlocked
                ? R.string.achievement_details_state_unlocked
                : R.string.achievement_details_state_locked);
        description.setText(unlocked ? achievement.descriptionRes : achievement.hintRes);

        if (unlocked) {
            Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale);
            String discoveredDate = Instant.ofEpochMilli(record.unlockedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(formatter);
            date.setText(activity.getString(R.string.achievement_details_date, discoveredDate));
            code.setText(activity.getString(R.string.achievement_details_code, record.code));
            discoveryBlock.setVisibility(View.VISIBLE);
        } else {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            icon.setColorFilter(new ColorMatrixColorFilter(matrix));
            icon.setAlpha(0.38f);
            discoveryBlock.setVisibility(View.GONE);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(
                activity,
                R.style.ThemeOverlay_ZUTnik_AlertDialog_Dark)
                .setView(content)
                .setPositiveButton(R.string.common_close, null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    ContextCompat.getDrawable(activity, R.drawable.bg_dialog_rounded_dark));
        }
        dialog.show();
    }
}
