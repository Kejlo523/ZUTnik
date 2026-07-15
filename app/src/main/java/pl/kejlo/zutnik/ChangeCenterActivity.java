package pl.kejlo.zutnik;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChangeCenterActivity extends ZutnikBaseActivity {

    private final List<ChangeCenterStore.Event> allEvents = new ArrayList<>();
    private ChangeAdapter adapter;
    private TextView summary;
    private TextView empty;
    private View markAllRead;
    private View clearAll;
    private String categoryFilter = "all";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_center);
        ThemeManager.applySystemBars(this);

        View contentRoot = findViewById(R.id.contentRoot);
        MainNavHelper.applyRootContentInsets(contentRoot);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(this, toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.change_center_title);
        }

        summary = findViewById(R.id.changeCenterSummary);
        empty = findViewById(R.id.changeCenterEmpty);
        RecyclerView list = findViewById(R.id.changeCenterList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChangeAdapter();
        list.setAdapter(adapter);

        bindFilter(R.id.filterAll, "all");
        bindFilter(R.id.filterGrades, ChangeCenterStore.CATEGORY_GRADES);
        bindFilter(R.id.filterPlan, ChangeCenterStore.CATEGORY_PLAN);
        bindFilter(R.id.filterFinance, ChangeCenterStore.CATEGORY_FINANCE);

        markAllRead = findViewById(R.id.btnChangeCenterReadAll);
        clearAll = findViewById(R.id.btnChangeCenterClear);
        markAllRead.setOnClickListener(v -> {
            ChangeCenterStore.markAllRead(this);
            loadEvents();
        });
        clearAll.setOnClickListener(v -> confirmClear());
        loadEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEvents();
    }

    @Override
    protected void onPause() {
        ChangeCenterStore.markAllRead(this);
        super.onPause();
    }

    private void bindFilter(int viewId, String category) {
        Chip chip = findViewById(viewId);
        chip.setOnClickListener(v -> {
            categoryFilter = category;
            renderFiltered();
        });
    }

    private void loadEvents() {
        allEvents.clear();
        allEvents.addAll(ChangeCenterStore.read(this));
        int unread = 0;
        for (ChangeCenterStore.Event event : allEvents) {
            if (!event.read) {
                unread++;
            }
        }
        summary.setText(allEvents.isEmpty()
                ? getString(R.string.change_center_summary_empty)
                : getResources().getQuantityString(
                        R.plurals.change_center_summary,
                        unread,
                        allEvents.size(),
                        unread));
        markAllRead.setEnabled(unread > 0);
        markAllRead.setAlpha(unread > 0 ? 1f : 0.45f);
        clearAll.setEnabled(!allEvents.isEmpty());
        clearAll.setAlpha(allEvents.isEmpty() ? 0.45f : 1f);
        renderFiltered();
    }

    private void renderFiltered() {
        List<ChangeCenterStore.Event> filtered = new ArrayList<>();
        for (ChangeCenterStore.Event event : allEvents) {
            if ("all".equals(categoryFilter) || categoryFilter.equals(event.category)) {
                filtered.add(event);
            }
        }
        adapter.submit(filtered);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmClear() {
        if (allEvents.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.change_center_clear_title)
                .setMessage(R.string.change_center_clear_message)
                .setPositiveButton(R.string.change_center_clear_action, (dialog, which) -> {
                    ChangeCenterStore.clear(this);
                    loadEvents();
                })
                .setNegativeButton(R.string.dialog_add_edit_tile_btn_cancel, null)
                .show();
    }

    private void openEvent(ChangeCenterStore.Event event) {
        if (ChangeCenterStore.CATEGORY_GRADES.equals(event.category)) {
            startActivity(MainShellActivity.createIntent(this, MainNavHelper.Screen.GRADES));
        } else if (ChangeCenterStore.CATEGORY_PLAN.equals(event.category)) {
            startActivity(MainShellActivity.createIntent(this, MainNavHelper.Screen.PLAN));
        } else if (ChangeCenterStore.CATEGORY_FINANCE.equals(event.category)) {
            startActivity(new Intent(this, FinanceActivity.class));
        }
        overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final class ChangeAdapter extends RecyclerView.Adapter<ChangeAdapter.Holder> {
        private final List<ChangeCenterStore.Event> items = new ArrayList<>();

        void submit(List<ChangeCenterStore.Event> events) {
            items.clear();
            items.addAll(events);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_change_center, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView category;
            final TextView title;
            final TextView message;
            final TextView details;
            final TextView time;
            final View unread;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.changeItemIcon);
                category = itemView.findViewById(R.id.changeItemCategory);
                title = itemView.findViewById(R.id.changeItemTitle);
                message = itemView.findViewById(R.id.changeItemMessage);
                details = itemView.findViewById(R.id.changeItemDetails);
                time = itemView.findViewById(R.id.changeItemTime);
                unread = itemView.findViewById(R.id.changeItemUnread);
            }

            void bind(ChangeCenterStore.Event event) {
                title.setText(event.title);
                message.setText(event.message);
                message.setVisibility(event.message.isEmpty() ? View.GONE : View.VISIBLE);
                String joined = android.text.TextUtils.join("\n", event.details);
                details.setText(joined);
                details.setVisibility(joined.isEmpty() ? View.GONE : View.VISIBLE);
                time.setText(formatTimestamp(event.timestamp));
                unread.setVisibility(event.read ? View.INVISIBLE : View.VISIBLE);

                if (ChangeCenterStore.CATEGORY_GRADES.equals(event.category)) {
                    category.setText(R.string.change_center_category_grades);
                    icon.setImageResource(R.drawable.ic_school);
                } else if (ChangeCenterStore.CATEGORY_FINANCE.equals(event.category)) {
                    category.setText(R.string.change_center_category_finance);
                    icon.setImageResource(R.drawable.ic_wallet);
                } else {
                    category.setText(R.string.change_center_category_plan);
                    icon.setImageResource(R.drawable.ic_calendar);
                }
                icon.setColorFilter(ThemeManager.resolveColor(ChangeCenterActivity.this, R.attr.mzPrimary));
                itemView.setOnClickListener(v -> openEvent(event));
            }
        }
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }
        java.time.ZonedDateTime dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
        if (LocalDate.now().equals(dateTime.toLocalDate())) {
            return getString(R.string.change_center_today_at,
                    dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())));
        }
        return dateTime.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()));
    }
}
