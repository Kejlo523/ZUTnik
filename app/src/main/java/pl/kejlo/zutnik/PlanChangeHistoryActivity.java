package pl.kejlo.zutnik;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlanChangeHistoryActivity extends ZutnikBaseActivity {

    private static final String DATE_PATTERN = "dd.MM.yyyy";
    private static final String TIMESTAMP_PATTERN = "dd.MM.yyyy HH:mm";

    private LinearLayout historyContainer;
    private View scrollHistory;
    private TextView tvSummary;
    private TextView tvEmpty;
    private TextView btnClearHistory;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_plan_change_history);
        ThemeManager.applySystemBars(this);

        View contentRoot = findViewById(R.id.contentRoot);
        MainNavHelper.applyRootContentInsets(contentRoot);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(this, toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.plan_change_history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        historyContainer = findViewById(R.id.historyContainer);
        scrollHistory = findViewById(R.id.scrollHistory);
        tvSummary = findViewById(R.id.tvHistorySummary);
        tvEmpty = findViewById(R.id.tvHistoryEmpty);
        btnClearHistory = findViewById(R.id.btnClearHistory);

        if (btnClearHistory != null) {
            btnClearHistory.setOnClickListener(v -> showClearHistoryConfirmation());
        }

        renderHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void renderHistory() {
        if (historyContainer == null || scrollHistory == null || tvSummary == null || tvEmpty == null) {
            return;
        }

        List<PlanChangeHistoryStore.ChangeRecord> entries = PlanChangeHistoryStore.read(this);
        historyContainer.removeAllViews();
        tvSummary.setText(getString(R.string.plan_change_history_count, entries.size()));
        updateClearButtonState(!entries.isEmpty());

        if (entries.isEmpty()) {
            scrollHistory.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        scrollHistory.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (PlanChangeHistoryStore.ChangeRecord entry : entries) {
            View itemView = inflater.inflate(R.layout.item_plan_change_history, historyContainer, false);
            bindEntry(itemView, entry);
            historyContainer.addView(itemView);
        }
    }

    private void updateClearButtonState(boolean hasEntries) {
        if (btnClearHistory == null) {
            return;
        }
        btnClearHistory.setEnabled(hasEntries);
        btnClearHistory.setAlpha(hasEntries ? 1f : 0.45f);
    }

    private void showClearHistoryConfirmation() {
        List<PlanChangeHistoryStore.ChangeRecord> entries = PlanChangeHistoryStore.read(this);
        if (entries.isEmpty()) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.plan_change_history_clear_title)
                .setMessage(R.string.plan_change_history_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.plan_change_history_clear, (dialog, which) -> {
                    PlanChangeHistoryStore.clear(this);
                    renderHistory();
                    Toast.makeText(this, R.string.plan_change_history_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void bindEntry(View itemView, PlanChangeHistoryStore.ChangeRecord entry) {
        View accent = itemView.findViewById(R.id.viewAccent);
        TextView tvType = itemView.findViewById(R.id.tvType);
        TextView tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        TextView tvTitle = itemView.findViewById(R.id.tvTitle);
        TextView tvSummaryText = itemView.findViewById(R.id.tvSummaryText);
        View fromSection = itemView.findViewById(R.id.layoutFromSection);
        TextView tvFromLabel = itemView.findViewById(R.id.tvFromLabel);
        TextView tvFromValue = itemView.findViewById(R.id.tvFromValue);
        View arrow = itemView.findViewById(R.id.tvArrow);
        View toSection = itemView.findViewById(R.id.layoutToSection);
        TextView tvToLabel = itemView.findViewById(R.id.tvToLabel);
        TextView tvToValue = itemView.findViewById(R.id.tvToValue);

        int accentColor = resolveAccentColor(entry.type);
        accent.setBackgroundColor(accentColor);
        tvType.setText(resolveTypeLabel(entry.type));
        tvType.setTextColor(accentColor);
        tvTimestamp.setText(formatTimestamp(entry.notifiedAt));

        CharSequence title = entry.title;
        if (title.length() == 0) {
            title = resolveTypeLabel(entry.type);
        }
        tvTitle.setText(title);

        if (entry.summary.isEmpty()) {
            tvSummaryText.setVisibility(View.GONE);
        } else {
            tvSummaryText.setVisibility(View.VISIBLE);
            tvSummaryText.setText(entry.summary);
        }

        if (entry.isType(PlanChangeHistoryStore.TYPE_REFRESHED)) {
            fromSection.setVisibility(View.GONE);
            arrow.setVisibility(View.GONE);
            toSection.setVisibility(View.GONE);
            return;
        }

        if (entry.hasFromSlot()) {
            fromSection.setVisibility(View.VISIBLE);
            tvFromLabel.setText(resolveFromLabel(entry.type));
            tvFromValue.setText(formatSlot(
                    entry.fromDate,
                    entry.fromStartMin,
                    entry.fromEndMin,
                    entry.fromRoom,
                    entry.fromGroup,
                    entry.fromTeacher,
                    entry.fromTypeLabel));
        } else {
            fromSection.setVisibility(View.GONE);
        }

        if (entry.hasToSlot()) {
            toSection.setVisibility(View.VISIBLE);
            tvToLabel.setText(resolveToLabel(entry.type));
            tvToValue.setText(formatSlot(
                    entry.toDate,
                    entry.toStartMin,
                    entry.toEndMin,
                    entry.toRoom,
                    entry.toGroup,
                    entry.toTeacher,
                    entry.toTypeLabel));
        } else {
            toSection.setVisibility(View.GONE);
        }

        arrow.setVisibility(entry.hasFromSlot() && entry.hasToSlot() ? View.VISIBLE : View.GONE);
    }

    private CharSequence resolveTypeLabel(String type) {
        if (PlanChangeHistoryStore.TYPE_MOVED.equals(type)) {
            return getString(R.string.plan_change_type_moved);
        }
        if (PlanChangeHistoryStore.TYPE_UPDATED.equals(type)) {
            return getString(R.string.plan_change_type_updated);
        }
        if (PlanChangeHistoryStore.TYPE_CANCELLED.equals(type)) {
            return getString(R.string.plan_change_type_cancelled);
        }
        if (PlanChangeHistoryStore.TYPE_ADDED.equals(type)) {
            return getString(R.string.plan_change_type_added);
        }
        if (PlanChangeHistoryStore.TYPE_REMOVED.equals(type)) {
            return getString(R.string.plan_change_type_removed);
        }
        return getString(R.string.plan_change_type_refreshed);
    }

    private int resolveAccentColor(String type) {
        if (PlanChangeHistoryStore.TYPE_MOVED.equals(type)) {
            return ContextCompat.getColor(this, R.color.mz_primary);
        }
        if (PlanChangeHistoryStore.TYPE_UPDATED.equals(type)) {
            return ContextCompat.getColor(this, R.color.mz_accent);
        }
        if (PlanChangeHistoryStore.TYPE_CANCELLED.equals(type)) {
            return ContextCompat.getColor(this, R.color.mz_danger);
        }
        if (PlanChangeHistoryStore.TYPE_ADDED.equals(type)) {
            return ContextCompat.getColor(this, R.color.mz_success);
        }
        if (PlanChangeHistoryStore.TYPE_REMOVED.equals(type)) {
            return ContextCompat.getColor(this, R.color.mz_muted);
        }
        return ContextCompat.getColor(this, R.color.mz_primary_alt);
    }

    private CharSequence resolveFromLabel(String type) {
        if (PlanChangeHistoryStore.TYPE_REMOVED.equals(type)) {
            return getString(R.string.plan_change_label_removed);
        }
        return getString(R.string.plan_change_label_before);
    }

    private CharSequence resolveToLabel(String type) {
        if (PlanChangeHistoryStore.TYPE_ADDED.equals(type)
                || PlanChangeHistoryStore.TYPE_CANCELLED.equals(type)) {
            return getString(R.string.plan_change_label_current);
        }
        return getString(R.string.plan_change_label_after);
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault());
        return dateTime.format(createFormatter(TIMESTAMP_PATTERN));
    }

    private String formatSlot(
            String dateIso,
            int startMin,
            int endMin,
            String room,
            String group,
            String teacher,
            String typeLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append(formatDate(dateIso))
                .append("  ")
                .append(formatTimeRange(startMin, endMin));

        List<String> meta = new ArrayList<>();
        if (!room.isEmpty()) {
            meta.add(getString(R.string.plan_change_field_room) + ": " + room);
        }
        if (!group.isEmpty()) {
            meta.add(getString(R.string.plan_change_field_group) + ": " + group);
        }
        if (!teacher.isEmpty()) {
            meta.add(getString(R.string.plan_change_field_teacher) + ": " + teacher);
        }
        if (!typeLabel.isEmpty()) {
            meta.add(getString(R.string.plan_change_field_type) + ": " + typeLabel);
        }

        if (!meta.isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < meta.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(meta.get(i));
            }
        }

        return sb.toString();
    }

    private String formatDate(String dateIso) {
        if (dateIso == null || dateIso.trim().isEmpty()) {
            return "--.--.----";
        }
        try {
            return LocalDate.parse(dateIso.trim()).format(createFormatter(DATE_PATTERN));
        } catch (Exception ignored) {
            return dateIso;
        }
    }

    private String formatTimeRange(int startMinutes, int endMinutes) {
        String start = formatTime(startMinutes);
        if (endMinutes <= startMinutes) {
            return start;
        }
        return start + "-" + formatTime(endMinutes);
    }

    private String formatTime(int minutes) {
        int safeMinutes = Math.max(0, minutes);
        int hours = safeMinutes / 60;
        int mins = safeMinutes % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", hours, mins);
    }

    private DateTimeFormatter createFormatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault());
    }
}
