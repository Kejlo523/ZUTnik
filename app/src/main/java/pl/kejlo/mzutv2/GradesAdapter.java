package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class GradesAdapter extends RecyclerView.Adapter<GradesAdapter.ViewHolder> {

    private static final int MAX_VISIBLE_GRADE_PILLS = 3;
    private final List<Grade> grades = new ArrayList<>();

    public GradesAdapter(List<Grade> grades) {
        setGrades(grades);
    }

    public void setGrades(List<Grade> source) {
        grades.clear();
        if (source != null) {
            grades.addAll(source);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.grade_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int i) {
        Grade g = grades.get(i);
        Context ctx = h.itemView.getContext();

        String subject = GradesTextUtils.extractBaseSubject(g != null ? g.subjectName : "");
        if (subject.isEmpty()) {
            subject = GradesTextUtils.clean(g != null ? g.subjectName : "");
        }
        h.colSubject.setText(subject);

        String typeRaw = GradesTextUtils.clean(g != null ? g.type : "");
        if (typeRaw.isEmpty()) {
            typeRaw = GradesTextUtils.extractTypeFromSubject(g != null ? g.subjectName : "");
        }
        String typeDisplay = GradesTextUtils.formatTypeDisplay(ctx, typeRaw);
        if (!typeDisplay.isEmpty()) {
            h.colType.setText(typeDisplay);
            h.colType.setVisibility(View.VISIBLE);
        } else {
            h.colType.setVisibility(View.GONE);
        }

        String date = GradesTextUtils.clean(g != null ? g.date : "");
        if (!date.isEmpty()) {
            h.colDate.setText(date);
            h.colDate.setVisibility(View.VISIBLE);
        } else {
            h.colDate.setVisibility(View.GONE);
        }

        if (GradesTextUtils.isFinalGradeLabel(typeRaw) && g != null && g.weight > 0.0) {
            h.colEcts.setVisibility(View.VISIBLE);
            h.colEcts.setText(ctx.getString(R.string.grades_ects_format, g.weight));
        } else {
            h.colEcts.setVisibility(View.GONE);
        }

        bindGradePills(h, g);
    }

    private void bindGradePills(@NonNull ViewHolder h, Grade g) {
        h.gradesContainer.removeAllViews();

        Context ctx = h.itemView.getContext();
        Resources res = ctx.getResources();

        List<String> history = g != null ? g.gradeHistory : null;
        if (history == null || history.isEmpty()) {
            String rawSingle = GradesTextUtils.clean(g != null ? g.grade : "");
            if (!rawSingle.isEmpty()) {
                history = Collections.singletonList(rawSingle);
            }
        }

        if (history == null || history.isEmpty()) {
            TextView dash = createGradePill(ctx, res, ctx.getString(R.string.common_dash), true);
            styleGradePill(ctx, dash, "", true);
            h.gradesContainer.addView(dash);
            return;
        }

        int show = Math.min(MAX_VISIBLE_GRADE_PILLS, history.size());
        int rendered = 0;

        for (int idx = 0; idx < show; idx++) {
            String rawGrade = GradesTextUtils.clean(history.get(idx));
            if (rawGrade.isEmpty()) {
                continue;
            }

            TextView pill = createGradePill(ctx, res, rawGrade, false);
            styleGradePill(ctx, pill, rawGrade, false);
            h.gradesContainer.addView(pill);
            rendered++;
        }

        if (rendered == 0) {
            TextView dash = createGradePill(ctx, res, ctx.getString(R.string.common_dash), true);
            styleGradePill(ctx, dash, "", true);
            h.gradesContainer.addView(dash);
        }

        if (history.size() > show) {
            TextView more = createCountPill(ctx, res, history.size() - show);
            h.gradesContainer.addView(more);
        }
    }

    private TextView createGradePill(Context ctx, Resources res, String text, boolean compact) {
        TextView pill = new TextView(ctx);
        pill.setText(text);
        pill.setSingleLine();
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, compact ? 12f : 13f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);

        int size = dp(res, compact ? 38 : 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(dp(res, 4));
        pill.setLayoutParams(lp);

        return pill;
    }

    private TextView createCountPill(Context ctx, Resources res, int remaining) {
        TextView pill = createGradePill(ctx, res, "+" + remaining, true);
        pill.setBackgroundResource(R.drawable.bg_card_primary);
        pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzMuted));
        return pill;
    }

    private void styleGradePill(Context ctx, TextView pill, String raw, boolean missing) {
        if (missing || raw == null || raw.trim().isEmpty()) {
            pill.setBackgroundResource(R.drawable.bg_grade_missing);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzMuted));
            return;
        }

        String normalizedRaw = raw.trim();
        String lower = normalizedRaw.toLowerCase(Locale.ROOT);

        boolean isFail = "2".equals(normalizedRaw)
                || "2.0".equals(normalizedRaw)
                || "2,0".equals(normalizedRaw)
                || "nk".equalsIgnoreCase(lower)
                || "nzal".equalsIgnoreCase(lower);
        boolean isPass = false;

        if (!isFail) {
            String numeric = normalizedRaw.replace(",", ".");
            try {
                double val = Double.parseDouble(numeric);
                if (val > 2.0) {
                    isPass = true;
                }
            } catch (NumberFormatException e) {
                if ("zal".equalsIgnoreCase(lower) || "z".equalsIgnoreCase(lower)) {
                    isPass = true;
                }
            }
        }

        if (isFail) {
            pill.setBackgroundResource(R.drawable.bg_grade_fail);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzDanger));
        } else if (isPass) {
            pill.setBackgroundResource(R.drawable.bg_grade_pass);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzSuccess));
        } else {
            pill.setBackgroundResource(R.drawable.bg_card_primary);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzText));
        }
    }

    private int dp(Resources res, int dp) {
        return Math.round(dp * res.getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return grades.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView colSubject;
        TextView colType;
        TextView colDate;
        TextView colEcts;
        LinearLayout gradesContainer;

        public ViewHolder(@NonNull View v) {
            super(v);
            colSubject = v.findViewById(R.id.colSubject);
            colType = v.findViewById(R.id.colType);
            gradesContainer = v.findViewById(R.id.gradesContainer);
            colDate = v.findViewById(R.id.colDate);
            colEcts = v.findViewById(R.id.colEcts);
        }
    }
}
