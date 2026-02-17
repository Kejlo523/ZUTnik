package pl.kejlo.mzutv2;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GroupedGradesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static class GradeGroup {
        public final String subject;
        public Grade finalGrade;
        public boolean finalMissing = false;
        public final List<Grade> others = new ArrayList<>();
        public boolean expanded = false;

        public GradeGroup(String subject) {
            this.subject = subject;
        }
    }

    private static class ListItem {
        final GradeGroup group;
        final Grade grade;
        final boolean isGroup;

        private ListItem(GradeGroup group, Grade grade, boolean isGroup) {
            this.group = group;
            this.grade = grade;
            this.isGroup = isGroup;
        }

        static ListItem group(GradeGroup group) {
            return new ListItem(group, null, true);
        }

        static ListItem grade(GradeGroup group, Grade grade) {
            return new ListItem(group, grade, false);
        }
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_GRADE = 1;

    private final List<GradeGroup> groups = new ArrayList<>();
    private final List<ListItem> items = new ArrayList<>();

    public void setGroups(List<GradeGroup> newGroups) {
        groups.clear();
        if (newGroups != null) {
            groups.addAll(newGroups);
        }
        rebuildItems();
        notifyDataSetChanged();
    }

    private void rebuildItems() {
        items.clear();
        for (GradeGroup g : groups) {
            items.add(ListItem.group(g));
            if (g.expanded && g.others != null) {
                for (Grade child : g.others) {
                    items.add(ListItem.grade(g, child));
                }
            }
        }
    }

    private int findGroupPosition(GradeGroup group) {
        if (group == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            ListItem item = items.get(i);
            if (item.isGroup && item.group == group) {
                return i;
            }
        }
        return -1;
    }

    private void toggleGroup(GradeGroup g) {
        int pos = findGroupPosition(g);
        if (pos < 0) {
            return;
        }

        int childCount = g.others != null ? g.others.size() : 0;
        if (childCount == 0) {
            return;
        }

        if (g.expanded) {
            g.expanded = false;
            int from = pos + 1;
            int to = Math.min(items.size(), from + childCount);
            if (to > from) {
                items.subList(from, to).clear();
                notifyItemRangeRemoved(from, to - from);
            }
            notifyItemChanged(pos);
        } else {
            g.expanded = true;
            int insertPos = pos + 1;
            List<ListItem> children = new ArrayList<>();
            for (Grade child : g.others) {
                children.add(ListItem.grade(g, child));
            }
            items.addAll(insertPos, children);
            notifyItemRangeInserted(insertPos, children.size());
            notifyItemChanged(pos);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isGroup ? TYPE_GROUP : TYPE_GRADE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) {
            View v = inflater.inflate(R.layout.grade_group_row, parent, false);
            return new GroupViewHolder(v);
        }
        View v = inflater.inflate(R.layout.grade_detail_row, parent, false);
        return new GradeViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ListItem item = items.get(position);
        if (item.isGroup) {
            bindGroup((GroupViewHolder) holder, item.group);
        } else {
            bindGrade((GradeViewHolder) holder, item.grade);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void bindGroup(GroupViewHolder h, GradeGroup g) {
        Context ctx = h.itemView.getContext();

        String subject = extractBaseSubject(g != null ? g.subject : "");
        if (subject.isEmpty()) {
            subject = safe(g != null ? g.subject : "");
        }
        h.subject.setText(subject);

        float target = g.expanded ? 90f : 0f;
        if (h.expandIcon.getRotation() != target) {
            h.expandIcon.animate().rotation(target).setDuration(180).start();
        } else {
            h.expandIcon.setRotation(target);
        }

        String finalLabel;
        if (g.finalMissing || g.finalGrade == null) {
            finalLabel = ctx.getString(R.string.grades_final_grade_missing);
            String dash = ctx.getString(R.string.common_dash);
            h.finalPill.setText(dash);
            styleGradePill(ctx, h.finalPill, dash, true);
        } else {
            finalLabel = ctx.getString(R.string.grades_final_grade_label);
            String raw = safe(g.finalGrade.grade);
            h.finalPill.setText(raw.isEmpty() ? ctx.getString(R.string.common_dash) : raw);
            styleGradePill(ctx, h.finalPill, raw, raw.isEmpty());
        }

        int othersCount = g.others != null ? g.others.size() : 0;
        if (othersCount > 0) {
            finalLabel = finalLabel + " | " + ctx.getString(R.string.grades_group_items_count, othersCount);
        }

        double ects = resolveGroupEcts(g);
        if (ects > 0.0) {
            finalLabel = finalLabel + "\n" + ctx.getString(R.string.grades_ects_format, ects);
        }
        h.finalLabel.setText(finalLabel);

        bindPreview(ctx, h.previewRow, g);

        View clickTarget = h.root != null ? h.root : h.itemView;
        clickTarget.setOnClickListener(v -> toggleGroup(g));
    }

    private void bindGrade(GradeViewHolder h, Grade g) {
        Context ctx = h.itemView.getContext();

        String rawGrade = safe(g != null ? g.grade : "");
        if (rawGrade.isEmpty()) {
            rawGrade = ctx.getString(R.string.common_dash);
            styleGradePill(ctx, h.gradePill, "", true);
        } else {
            styleGradePill(ctx, h.gradePill, rawGrade, false);
        }
        h.gradePill.setText(rawGrade);

        String type = safe(g != null ? g.type : "");
        if (type.isEmpty()) {
            type = extractTypeFromSubject(g != null ? g.subjectName : "");
        }
        String typeDisplay = formatTypeDisplay(ctx, type);
        if (!typeDisplay.isEmpty()) {
            h.type.setText(typeDisplay);
            h.type.setVisibility(View.VISIBLE);
        } else {
            h.type.setVisibility(View.GONE);
        }

        String date = safe(g != null ? g.date : "");
        if (!date.isEmpty()) {
            h.date.setText(date);
            h.date.setVisibility(View.VISIBLE);
        } else {
            h.date.setVisibility(View.GONE);
        }

        String teacher = safe(g != null ? g.teacher : "");
        if (!teacher.isEmpty()) {
            h.teacher.setText(teacher);
            h.teacher.setVisibility(View.VISIBLE);
        } else {
            h.teacher.setVisibility(View.GONE);
        }
    }

    private void bindPreview(Context ctx, LinearLayout container, GradeGroup group) {
        container.removeAllViews();
        if (group == null || group.others == null || group.others.isEmpty()) {
            return;
        }

        int max = 3;
        int total = group.others.size();
        int show = Math.min(max, total);

        for (int i = 0; i < show; i++) {
            Grade g = group.others.get(i);
            String raw = safe(g != null ? g.grade : "");
            boolean missing = raw.isEmpty();
            String label = missing ? ctx.getString(R.string.common_dash) : raw;

            TextView pill = createPreviewPill(ctx, label, false);
            styleGradePill(ctx, pill, raw, missing);
            container.addView(pill);
        }

        if (total > show) {
            int remaining = total - show;
            TextView more = createPreviewPill(ctx, "+" + remaining, true);
            container.addView(more);
        }
    }

    private TextView createPreviewPill(Context ctx, String text, boolean isCount) {
        TextView pill = new TextView(ctx);
        pill.setText(text);
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isCount ? 10f : 11f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);

        int size = dp(ctx, isCount ? 30 : 34);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(ctx, 6));
        pill.setLayoutParams(lp);

        if (isCount) {
            pill.setBackgroundResource(R.drawable.bg_card_primary);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzMuted));
        } else {
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzText));
        }
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

    private int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
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

    private String formatTypeDisplay(Context ctx, String value) {
        String raw = safe(value);
        if (raw.isEmpty()) {
            return "";
        }

        String normalized = normalize(raw);
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
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = repairMojibake(value).trim().toLowerCase(Locale.ROOT);
        String n = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
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

    private double resolveGroupEcts(GradeGroup group) {
        if (group == null) {
            return 0.0;
        }
        if (group.finalGrade != null && group.finalGrade.weight > 0) {
            return group.finalGrade.weight;
        }
        if (group.others != null) {
            for (Grade g : group.others) {
                if (g != null && g.weight > 0) {
                    return g.weight;
                }
            }
        }
        return 0.0;
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        TextView subject;
        TextView finalPill;
        TextView finalLabel;
        LinearLayout previewRow;
        ImageView expandIcon;
        View root;

        GroupViewHolder(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.groupRoot);
            subject = v.findViewById(R.id.groupSubject);
            finalPill = v.findViewById(R.id.groupFinalPill);
            finalLabel = v.findViewById(R.id.groupFinalLabel);
            previewRow = v.findViewById(R.id.groupPreviewRow);
            expandIcon = v.findViewById(R.id.groupExpandIcon);
        }
    }

    static class GradeViewHolder extends RecyclerView.ViewHolder {
        TextView gradePill;
        TextView type;
        TextView date;
        TextView teacher;

        GradeViewHolder(@NonNull View v) {
            super(v);
            gradePill = v.findViewById(R.id.detailGradePill);
            type = v.findViewById(R.id.detailType);
            date = v.findViewById(R.id.detailDate);
            teacher = v.findViewById(R.id.detailTeacher);
        }
    }
}
