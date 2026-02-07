package pl.kejlo.mzutv2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class GradesAdapter extends RecyclerView.Adapter<GradesAdapter.ViewHolder> {

    private final List<Grade> grades;

    public GradesAdapter(List<Grade> grades) {
        this.grades = grades;
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
        h.colSubject.setText(g.subjectName);
        h.colType.setText(g.type);
        // Populate grades container (pills)
        h.gradesContainer.removeAllViews();

        List<String> history = g.gradeHistory;
        if (history == null || history.isEmpty()) {
            // Fallback if history empty but legacy grade exists
            if (g.grade != null && !g.grade.isEmpty()) {
                history = java.util.Collections.singletonList(g.grade);
            }
        }

        if (history != null && !history.isEmpty()) {
            android.content.Context ctx = h.itemView.getContext();
            android.content.res.Resources res = ctx.getResources();
            int danger = ThemeManager.resolveColor(ctx, R.attr.mzDanger);
            int success = ThemeManager.resolveColor(ctx, R.attr.mzSuccess);
            int text = ThemeManager.resolveColor(ctx, R.attr.mzText);

            for (String rawGrade : history) {
                if (rawGrade == null || rawGrade.trim().isEmpty())
                    continue;

                TextView pill = new TextView(ctx);

                // Style the pill
                pill.setText(rawGrade);
                pill.setTextColor(0xFFFFFFFF); // Default text color (white)
                pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                pill.setTypeface(null, android.graphics.Typeface.BOLD);
                pill.setGravity(android.view.Gravity.CENTER);

                // Layout params
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        (int) (54 * res.getDisplayMetrics().density), // width 54dp
                        (int) (54 * res.getDisplayMetrics().density) // height 54dp
                );
                lp.setMarginStart((int) (4 * res.getDisplayMetrics().density));
                pill.setLayoutParams(lp);

                // Background logic
                String lower = rawGrade.trim().toLowerCase();
                boolean isFail = "2".equals(rawGrade.trim()) || "nk".equalsIgnoreCase(lower);
                boolean isPass = false;

                if (!isFail) {
                    // Try to interpret the grade as a number
                    String normalized = rawGrade.trim().replace(",", ".");
                    try {
                        double val = Double.parseDouble(normalized);
                        if (val > 2.0) {
                            isPass = true;
                        }
                    } catch (NumberFormatException e) {
                        // Non-numeric grade like "zal"
                        if ("zal".equalsIgnoreCase(lower) || "z".equalsIgnoreCase(lower)) {
                            isPass = true;
                        }
                    }
                }

                if (isFail) {
                    pill.setBackgroundResource(R.drawable.bg_grade_fail);
                    pill.setTextColor(danger);
                } else if (isPass) {
                    pill.setBackgroundResource(R.drawable.bg_grade_pass);
                    pill.setTextColor(success);
                } else {
                    pill.setBackgroundResource(R.drawable.bg_card_primary); // default/neutral
                    pill.setTextColor(text);
                }

                h.gradesContainer.addView(pill);
            }
        }

        // Show ECTS only for final grade
        if (g.type != null && g.type.trim().equalsIgnoreCase("ocena końcowa")) {
            h.colEcts.setVisibility(View.VISIBLE);
            h.colEcts.setText(
                    h.itemView.getContext().getString(R.string.grades_ects_format, g.weight));
        } else {
            h.colEcts.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return grades.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView colSubject, colType, colDate, colEcts;
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
