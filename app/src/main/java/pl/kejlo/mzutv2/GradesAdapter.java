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

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class GradesAdapter extends RecyclerView.Adapter<GradesAdapter.ViewHolder> {

    private static final int MAX_VISIBLE_GRADE_PILLS = 3;
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
        Context ctx = h.itemView.getContext();

        String subject = extractBaseSubject(g != null ? g.subjectName : "");
        if (subject.isEmpty()) {
            subject = safe(g != null ? g.subjectName : "");
        }
        h.colSubject.setText(subject);

        String typeRaw = safe(g != null ? g.type : "");
        if (typeRaw.isEmpty()) {
            typeRaw = extractTypeFromSubject(g != null ? g.subjectName : "");
        }
        String typeDisplay = formatTypeDisplay(ctx, typeRaw);
        if (!typeDisplay.isEmpty()) {
            h.colType.setText(typeDisplay);
            h.colType.setVisibility(View.VISIBLE);
        } else {
            h.colType.setVisibility(View.GONE);
        }

        String date = safe(g != null ? g.date : "");
        if (!date.isEmpty()) {
            h.colDate.setText(date);
            h.colDate.setVisibility(View.VISIBLE);
        } else {
            h.colDate.setVisibility(View.GONE);
        }

        if (isFinalGradeType(typeRaw) && g != null && g.weight > 0.0) {
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
            String rawSingle = safe(g != null ? g.grade : "");
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
            String rawGrade = safe(history.get(idx));
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

    private boolean isFinalGradeType(String type) {
        String normalized = normalizeKey(type);
        return normalized.contains("ocena koncowa")
                || normalized.equals("koncowa")
                || normalized.contains("koncowa")
                || normalized.contains("final")
                || normalized.contains("abschluss");
    }

    private String formatTypeDisplay(Context ctx, String value) {
        String raw = safe(value);
        if (raw.isEmpty()) {
            return "";
        }

        String normalized = normalizeKey(raw);
        if (normalized.contains("wyklad")) {
            return ctx.getString(R.string.plan_type_lecture);
        }
        if (normalized.contains("laboratorium")) {
            return ctx.getString(R.string.plan_type_lab);
        }
        if (normalized.contains("audytoryjne")) {
            return ctx.getString(R.string.plan_type_auditory);
        }
        if (normalized.contains("egzamin")) {
            return ctx.getString(R.string.plan_type_exam);
        }
        if (normalized.contains("zaliczen")) {
            return ctx.getString(R.string.plan_type_pass);
        }
        return toTitleCase(raw);
    }

    private String extractTypeFromSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String name = subject.trim();
        int start = name.lastIndexOf(" (");
        if (start > 0 && name.endsWith(")")) {
            return name.substring(start + 2, name.length() - 1).trim();
        }
        return "";
    }

    private String extractBaseSubject(String label) {
        if (label == null) {
            return "";
        }
        String name = label.trim();
        int parenIdx = name.lastIndexOf(" (");
        if (parenIdx > 0 && name.endsWith(")")) {
            name = name.substring(0, parenIdx);
        }
        return name.trim();
    }

    private String toTitleCase(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.toLowerCase(Locale.getDefault()).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String repaired = repairMojibake(value);
        String lower = repaired.trim().toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return repairMojibake(value).trim();
    }

    private String repairMojibake(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("Ä…", "ą")
                .replace("Ä‡", "ć")
                .replace("Ä™", "ę")
                .replace("Å‚", "ł")
                .replace("Å„", "ń")
                .replace("Ã³", "ó")
                .replace("Å›", "ś")
                .replace("Å¼", "ż")
                .replace("Åº", "ź")
                .replace("Ä„", "Ą")
                .replace("Ä†", "Ć")
                .replace("Ä˜", "Ę")
                .replace("Å�", "Ł")
                .replace("Åƒ", "Ń")
                .replace("Ã“", "Ó")
                .replace("Åš", "Ś")
                .replace("Å»", "Ż")
                .replace("Å¹", "Ź")
                .replace("Ĺ‚", "ł")
                .replace("Ĺ„", "ń");
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
