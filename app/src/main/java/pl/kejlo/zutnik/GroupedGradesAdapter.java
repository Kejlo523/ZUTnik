package pl.kejlo.zutnik;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
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
        public boolean emptyFromPlanFilter = false;
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

        if (g.emptyFromPlanFilter) {
            h.finalRow.setVisibility(View.VISIBLE);
            h.finalPill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            h.finalPill.setText(ctx.getString(R.string.common_dash));
            styleGradePill(ctx, h.finalPill, "", true);
            h.finalLabel.setText(R.string.grades_no_grades_label);
        } else if (g.hasNew && (g.finalGrade == null || g.finalMissing)) {
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
        h.expandIcon.setVisibility(g.others.isEmpty() ? View.GONE : View.VISIBLE);

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

            container.addView(createPreviewGradeItem(ctx, label, raw, missing, g));
        }

        if (total > show) {
            int remaining = total - show;
            container.addView(createPreviewCountItem(ctx, remaining));
        }
    }

    private View createPreviewGradeItem(Context ctx, String label, String raw, boolean missing, Grade grade) {
        LinearLayout item = createPreviewItemContainer(ctx);
        addTypeSlot(ctx, item, resolveTypeChipText(grade));

        TextView pill = createPreviewPill(ctx, label, false);
        styleGradePill(ctx, pill, raw, missing);
        String typeDisplay = resolveTypeDisplay(ctx, grade);
        pill.setContentDescription(typeDisplay.isEmpty() ? label : label + ", " + typeDisplay);
        item.addView(pill);
        return item;
    }

    private View createPreviewCountItem(Context ctx, int remaining) {
        LinearLayout item = createPreviewItemContainer(ctx);
        addTypeSlot(ctx, item, "");

        TextView more = createPreviewPill(ctx, "+" + remaining, true);
        item.addView(more);
        return item;
    }

    private LinearLayout createPreviewItemContainer(Context ctx) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(ctx, 8));
        item.setLayoutParams(lp);
        return item;
    }

    private void addTypeSlot(Context ctx, LinearLayout item, String typeText) {
        String label = GradesTextUtils.clean(typeText);
        if (label.isEmpty()) {
            View spacer = new View(ctx);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 1), dp(ctx, 18)));
            item.addView(spacer);
            return;
        }

        TextView chip = new TextView(ctx);
        chip.setText(label);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzMuted));
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8.5f);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setBackgroundResource(R.drawable.bg_grade_preview_type_chip);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(ctx, 18));
        lp.bottomMargin = dp(ctx, 4);
        chip.setMinWidth(dp(ctx, 34));
        chip.setMaxWidth(dp(ctx, 56));
        chip.setLayoutParams(lp);
        item.addView(chip);
    }

    private TextView createPreviewPill(Context ctx, String text, boolean isCount) {
        TextView pill = new TextView(ctx);
        pill.setText(text);
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isCount ? 10f : 11f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        pill.setSingleLine(true);
        pill.setMaxLines(1);
        pill.setEllipsize(TextUtils.TruncateAt.END);

        int size = dp(ctx, isCount ? 30 : 34);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        pill.setLayoutParams(lp);

        if (isCount) {
            pill.setBackgroundResource(R.drawable.bg_card_primary);
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzMuted));
        } else {
            pill.setTextColor(ThemeManager.resolveColor(ctx, R.attr.mzText));
        }
        return pill;
    }

    private String resolveTypeDisplay(Context ctx, Grade grade) {
        String type = GradesTextUtils.clean(grade != null ? grade.type : "");
        if (type.isEmpty()) {
            type = GradesTextUtils.extractTypeFromSubject(grade != null ? grade.subjectName : "");
        }
        return GradesTextUtils.formatTypeDisplay(ctx, type);
    }

    private String resolveTypeChipText(Grade grade) {
        String typeKey = PlanSubjectFilterHelper.resolveGradeTypeKey(grade);
        if ("lec".equals(typeKey)) {
            return "Wykład";
        }
        if ("lab".equals(typeKey)) {
            return "Lab";
        }
        if ("aud".equals(typeKey)) {
            return "\u0106w.";
        }
        if ("lek".equals(typeKey)) {
            return "Lek.";
        }

        String type = GradesTextUtils.clean(grade != null ? grade.type : "");
        if (type.isEmpty()) {
            type = GradesTextUtils.extractTypeFromSubject(grade != null ? grade.subjectName : "");
        }

        String normalized = GradesTextUtils.normalizeKey(type);
        if (normalized.isEmpty()) {
            return "Ocena";
        }
        if (normalized.contains("zaliczen") || normalized.contains("pass")) {
            return "Zal.";
        }
        if (normalized.contains("egzamin") || normalized.contains("exam")) {
            return "Egz.";
        }
        if (normalized.contains("koncowa") || normalized.contains("final")) {
            return "Ocena";
        }
        if (normalized.contains("wyklad")
                || normalized.contains("lecture")
                || normalized.contains("vorlesung")
                || normalized.contains("lekcija")) {
            return "Wykład";
        }
        if (normalized.contains("laboratorium")
                || normalized.contains("laboratory")
                || normalized.contains("labor")
                || normalized.contains("lab")) {
            return "Lab";
        }
        if (normalized.contains("cwiczen")
                || normalized.contains("audytoryjne")
                || normalized.contains("auditory")
                || normalized.contains("ubung")
                || normalized.contains("zanyat")) {
            return "\u0106w.";
        }
        return "Ocena";
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
