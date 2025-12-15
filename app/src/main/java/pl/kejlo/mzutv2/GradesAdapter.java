package pl.kejlo.mzutv2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        h.colGrade.setText(g.grade);
        h.colDate.setText(g.date);

        // Show ECTS only for 'ocena końcowa'
        if (g.type != null && g.type.trim().equalsIgnoreCase("ocena końcowa")) {
            h.colEcts.setVisibility(View.VISIBLE);
            h.colEcts.setText(String.format(java.util.Locale.getDefault(), "%.1f ECTS", g.weight));
        } else {
            h.colEcts.setVisibility(View.GONE);
        }

        // Grade pill style
        String raw = g.grade != null ? g.grade.trim() : "";
        String lower = raw.toLowerCase();

        boolean isFail = "2".equals(raw) || "nk".equalsIgnoreCase(lower);
        boolean isPass = false;

        if (!isFail && !raw.isEmpty()) {
            // Try to interpret the grade as a number
            String normalized = raw.replace(",", ".");
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
            h.colGrade.setBackgroundResource(R.drawable.bg_grade_fail);
            h.colGrade.setTextColor(0xFFDC2626); // red
        } else if (isPass) {
            h.colGrade.setBackgroundResource(R.drawable.bg_grade_pass);
            h.colGrade.setTextColor(0xFF22C55E); // green
        } else {
            // No grade or other value – reset style
            h.colGrade.setBackground(null);
            h.colGrade.setTextColor(0xFFFFFFFF);
        }
    }

    @Override
    public int getItemCount() {
        return grades.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView colSubject, colType, colGrade, colDate, colEcts;

        public ViewHolder(@NonNull View v) {
            super(v);
            colSubject = v.findViewById(R.id.colSubject);
            colType = v.findViewById(R.id.colType);
            colGrade = v.findViewById(R.id.colGrade);
            colDate = v.findViewById(R.id.colDate);
            colEcts = v.findViewById(R.id.colEcts);
        }
    }
}
