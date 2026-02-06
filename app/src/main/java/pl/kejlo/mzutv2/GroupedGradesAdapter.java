package pl.kejlo.mzutv2;

import android.content.Context;
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

        h.subject.setText(g.subject);
        float target = g.expanded ? 90f : 0f;
        if (h.expandIcon.getRotation() != target) {
            h.expandIcon.animate().rotation(target).setDuration(180).start();
        } else {
            h.expandIcon.setRotation(target);
        }

        if (g.finalMissing || g.finalGrade == null) {
            h.finalLabel.setText(R.string.grades_final_grade_missing);
            h.finalPill.setText("-");
            styleGradePill(ctx, h.finalPill, "-", true);
        } else {
            h.finalLabel.setText(R.string.grades_final_grade_label);
            String raw = g.finalGrade.grade != null ? g.finalGrade.grade : "";
            h.finalPill.setText(raw);
            styleGradePill(ctx, h.finalPill, raw, false);
        }

        bindPreview(ctx, h.previewRow, g);

        View clickTarget = h.root != null ? h.root : h.itemView;
        clickTarget.setOnClickListener(v -> toggleGroup(g));
    }

    private void bindGrade(GradeViewHolder h, Grade g) {
        Context ctx = h.itemView.getContext();
        String raw = g != null ? g.grade : "";
        h.gradePill.setText(raw);
        styleGradePill(ctx, h.gradePill, raw, false);

        String type = g != null ? g.type : "";
        if (type != null && !type.trim().isEmpty()) {
            h.type.setText(formatTypeDisplay(type));
            h.type.setVisibility(View.VISIBLE);
        } else {
            String fallback = extractTypeFromSubject(g != null ? g.subjectName : "");
            if (fallback != null && !fallback.trim().isEmpty()) {
                h.type.setText(formatTypeDisplay(fallback));
                h.type.setVisibility(View.VISIBLE);
            } else {
                h.type.setVisibility(View.GONE);
            }
        }

        String date = g != null ? g.date : "";
        if (date != null && !date.trim().isEmpty()) {
            h.date.setText(date);
            h.date.setVisibility(View.VISIBLE);
        } else {
            h.date.setVisibility(View.GONE);
        }

        String teacher = g != null ? g.teacher : "";
        if (teacher != null && !teacher.trim().isEmpty()) {
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
            String label = getTypeLabel(g);
            TextView pill = createPreviewPill(ctx, label, false);
            String rawGrade = g != null ? g.grade : "";
            styleGradePill(ctx, pill, rawGrade, false);
            pill.setText(label);
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
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        pill.setTypeface(null, android.graphics.Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);

        int size = dp(ctx, 36);
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

        String lower = raw.trim().toLowerCase();
        boolean isFail = "2".equals(raw.trim()) || "2.0".equals(raw.trim())
                || "nk".equalsIgnoreCase(lower) || "nzal".equalsIgnoreCase(lower);
        boolean isPass = false;

        if (!isFail) {
            String normalized = raw.trim().replace(",", ".");
            try {
                double val = Double.parseDouble(normalized);
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

    private String getTypeLabel(Grade g) {
        if (g == null) {
            return "-";
        }
        String type = g.type;
        if (type == null || type.trim().isEmpty()) {
            type = extractTypeFromSubject(g.subjectName);
        }
        if (type == null || type.trim().isEmpty()) {
            return "-";
        }
        return shortenType(type.trim());
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

    private String shortenType(String type) {
        String normalized = normalize(type);
        if (normalized.contains("wyklad")) return "W";
        if (normalized.contains("audytoryjne")) return "A";
        if (normalized.contains("konwersatorium")) return "K";
        if (normalized.contains("laboratorium")) return "L";
        if (normalized.contains("cwiczen")) return "Ć";
        if (normalized.contains("projekt")) return "P";
        if (normalized.contains("semin")) return "S";
        if (normalized.contains("egzamin")) return "E";
        if (normalized.contains("zaliczen")) return "Z";
        if (normalized.contains("lektora")) return "L";
        if (normalized.contains("praktyk")) return "P";

        String trimmed = type.trim();
        if (trimmed.isEmpty()) {
            return "-";
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        String n = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private String formatTypeDisplay(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String normalized = normalize(value);
        if (normalized.contains("wyklad")) return "Wykład";
        if (normalized.contains("audytoryjne")) return "Audytoryjne";
        if (normalized.contains("konwersatorium")) return "Konwersatorium";
        if (normalized.contains("laboratorium")) return "Laboratorium";
        if (normalized.contains("cwiczen")) return "Ćwiczenia";
        if (normalized.contains("projekt")) return "Projekt";
        if (normalized.contains("semin")) return "Seminarium";
        if (normalized.contains("egzamin")) return "Egzamin";
        if (normalized.contains("zaliczen")) return "Zaliczenie";
        if (normalized.contains("lektora")) return "Lektorat";
        if (normalized.contains("praktyk")) return "Praktyka";

        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
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
