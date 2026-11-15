package pl.kejlo.zutnik;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GradeDistributionDialog {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    static boolean available(Grade grade) {
        return grade != null && (ZutnikSession.getInstance().isDemoLogin()
                || !GradesTextUtils.clean(grade.examId).isEmpty());
    }

    static void show(Context context, Grade grade) {
        if (!available(grade)) return;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 16));
        TextView subject = label(context, grade.subjectName, 16, R.attr.mzText);
        content.addView(subject);
        TextView explanation = label(context, context.getString(ZutnikSession.getInstance().isDemoLogin()
                ? R.string.grades_distribution_demo : R.string.grades_distribution_desc), 13, R.attr.mzMuted);
        explanation.setPadding(0, dp(context, 8), 0, dp(context, 16));
        content.addView(explanation);
        LinearLayout chart = new LinearLayout(context);
        chart.setOrientation(LinearLayout.VERTICAL);
        chart.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(chart);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.grades_distribution_title)
                .setView(scroll)
                .setPositiveButton(R.string.common_close, null)
                .setNeutralButton(R.string.grades_distribution_retry, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> load(context, grade, chart, dialog, true));
            load(context, grade, chart, dialog, false);
        });
        dialog.show();
    }

    private static void load(Context context, Grade grade, LinearLayout chart, AlertDialog dialog, boolean force) {
        chart.removeAllViews();
        chart.addView(label(context, context.getString(R.string.grades_distribution_loading), 14, R.attr.mzMuted));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        Context app = context.getApplicationContext();
        String token = ZutnikSession.getInstance().getUsosAccessToken();
        EXECUTOR.execute(() -> {
            GradeDistribution result = null;
            try { result = GradeDistributionRepository.load(app, grade, force); }
            catch (Exception ignored) { }
            GradeDistribution distribution = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dialog.isShowing()) return;
                if (!java.util.Objects.equals(token, ZutnikSession.getInstance().getUsosAccessToken())) {
                    dialog.dismiss();
                    return;
                }
                chart.removeAllViews();
                if (distribution == null || distribution.entries.isEmpty()) {
                    chart.addView(label(context, context.getString(distribution == null
                            ? R.string.grades_distribution_error : R.string.grades_distribution_empty), 14, R.attr.mzMuted));
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
                    return;
                }
                for (GradeDistribution.Entry entry : distribution.entries) addBar(context, chart, entry, grade.grade);
                if (distribution.fromCache) chart.addView(label(context,
                        context.getString(R.string.grades_distribution_cached), 12, R.attr.mzMuted));
            });
        });
    }

    private static void addBar(Context context, LinearLayout chart, GradeDistribution.Entry entry, String ownGrade) {
        boolean own = GradeDistribution.sameSymbol(entry.symbol, ownGrade);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));
        String percent = String.format(java.util.Locale.getDefault(), "%.1f%%", entry.percentage);
        row.setContentDescription(context.getString(R.string.grades_distribution_bar, entry.symbol, percent)
                + (own ? ", " + context.getString(R.string.grades_distribution_yours) : ""));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView symbol = label(context, entry.symbol.replace('.', ','), 14, own ? R.attr.mzPrimary : R.attr.mzText);
        if (own) symbol.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(context, 40), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearProgressIndicator bar = new LinearProgressIndicator(context);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        bar.setTrackThickness(dp(context, 8));
        bar.setTrackCornerRadius(dp(context, 4));
        bar.setTrackColor(ThemeManager.resolveColor(context, R.attr.mzCardSoft));
        int primary = ThemeManager.resolveColor(context, R.attr.mzPrimary);
        bar.setIndicatorColor(own ? primary : androidx.core.graphics.ColorUtils.blendARGB(
                ThemeManager.resolveColor(context, R.attr.mzCardSoft), primary, 0.5f));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(context, 12), 1f);
        barParams.setMarginEnd(dp(context, 12));
        row.addView(bar, barParams);
        TextView value = label(context, percent, 13, own ? R.attr.mzPrimary : R.attr.mzMuted);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(context, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chart.addView(row);
        int target = (int) Math.round(entry.percentage * 10d);
        bar.post(() -> bar.setProgressCompat(target, ValueAnimator.areAnimatorsEnabled()));
    }

    private static TextView label(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ThemeManager.resolveColor(context, color));
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
