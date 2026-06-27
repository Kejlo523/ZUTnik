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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GroupedGradesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static class GradeGroup {
        public final String subject;
        public Grade finalGrade;
        public boolean finalMissing = false;
        public boolean hasNew = false;
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
            if (g.expanded) {
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

        int childCount = g.others.size();
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
        if (g == null) {
            h.subject.setText((CharSequence) null);
            h.finalRow.setVisibility(View.GONE);
            h.previewRow.removeAllViews();
            h.expandIcon.setRotation(0f);
            return;
        }

        String subject = GradesTextUtils.extractBaseSubject(g.subject);
        if (subject.isEmpty()) {
            subject = GradesTextUtils.clean(g.subject);
        }
        h.subject.setText(subject);

        float target = g.expanded ? 90f : 0f;
        if (h.expandIcon.getRotation() != target) {
            h.expandIcon.animate().rotation(target).setDuration(180).start();
        } else {
            h.expandIcon.setRotation(target);
        }

        if (g.hasNew && (g.finalGrade == null || g.finalMissing)) {
            h.finalRow.setVisibility(View.VISIBLE);
            h.finalPill.setText(R.string.grades_new_badge);
            h.finalPill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            h.finalPill.setBackgroundResource(R.drawable.bg_grade_type_chip);
            h.finalPill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzPrimary));
            h.finalLabel.setText(R.string.grades_new_badge_label);
        } else if (!(g.finalMissing || g.finalGrade == null)) {
            h.finalRow.setVisibility(View.VISIBLE);
            h.finalPill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            String finalLabel = ctx.getString(R.string.grades_final_grade_label);
            String raw = GradesTextUtils.clean(g.finalGrade.grade);
            h.finalPill.setText(raw.isEmpty() ? ctx.getString(R.string.common_dash) : raw);
            styleGradePill(ctx, h.finalPill, raw, raw.isEmpty());
            double ects = resolveGroupEcts(g);
            finalLabel = finalLabel + "\n" + ctx.getString(R.string.grades_ects_format, ects);
            h.finalLabel.setText(finalLabel);
        } else {
            h.finalRow.setVisibility(View.GONE);
        }

        bindPreview(ctx, h.previewRow, g);

        View clickTarget = h.root != null ? h.root : h.itemView;
        clickTarget.setOnClickListener(v -> toggleGroup(g));
    }

    private void bindGrade(GradeViewHolder h, Grade g) {
        Context ctx = h.itemView.getContext();

        String rawGrade = GradesTextUtils.clean(g != null ? g.grade : "");
        if (rawGrade.isEmpty()) {
            rawGrade = ctx.getString(R.string.common_dash);
            styleGradePill(ctx, h.gradePill, "", true);
        } else {
            styleGradePill(ctx, h.gradePill, rawGrade, false);
        }
        h.gradePill.setText(rawGrade);

        String type = GradesTextUtils.clean(g != null ? g.type : "");
        if (type.isEmpty()) {
            type = GradesTextUtils.extractTypeFromSubject(g != null ? g.subjectName : "");
        }
        String typeDisplay = GradesTextUtils.formatTypeDisplay(ctx, type);
        if (!typeDisplay.isEmpty()) {
            h.type.setText(typeDisplay);
            h.type.setVisibility(View.VISIBLE);
        } else {
            h.type.setVisibility(View.GONE);
        }

        String date = GradesTextUtils.clean(g != null ? g.date : "");
        if (!date.isEmpty()) {
            h.date.setText(date);
            h.date.setVisibility(View.VISIBLE);
        } else {
            h.date.setVisibility(View.GONE);
        }

        String teacher = GradesTextUtils.clean(g != null ? g.teacher : "");
        if (!teacher.isEmpty()) {
            h.teacher.setText(teacher);
            h.teacher.setVisibility(View.VISIBLE);
        } else {
            h.teacher.setVisibility(View.GONE);
        }
    }

    private void bindPreview(Context ctx, LinearLayout container, GradeGroup group) {
        container.removeAllViews();
        if (group == null || group.others.isEmpty()) {
            return;
        }

        int max = 3;
        int total = group.others.size();
        int show = Math.min(max, total);

        for (int i = 0; i < show; i++) {
            Grade g = group.others.get(i);
            String raw = GradesTextUtils.clean(g != null ? g.grade : "");
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

    private double resolveGroupEcts(GradeGroup group) {
        if (group == null) {
            return 0.0;
        }
        double best = 0.0;
        if (group.finalGrade != null && group.finalGrade.weight > best) {
            best = group.finalGrade.weight;
        }
        for (Grade g : group.others) {
            if (g != null && g.weight > best) {
                best = g.weight;
            }
        }
        return best;
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        TextView subject;
        LinearLayout finalRow;
        TextView finalPill;
        TextView finalLabel;
        LinearLayout previewRow;
        ImageView expandIcon;
        View root;

        GroupViewHolder(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.groupRoot);
            subject = v.findViewById(R.id.groupSubject);
            finalRow = v.findViewById(R.id.groupFinalRow);
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
