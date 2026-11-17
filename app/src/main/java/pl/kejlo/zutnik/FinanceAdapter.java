package pl.kejlo.zutnik;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FinanceAdapter extends RecyclerView.Adapter<FinanceAdapter.ViewHolder> {

    public interface Listener {
        void onCopyAccount(FinanceRecord record);
        void onCopyDetails(FinanceRecord record);
    }

    private final Listener listener;
    private final List<FinanceRecord> items = new ArrayList<>();

    public FinanceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<FinanceRecord> records) {
        List<FinanceRecord> updatedItems = records != null
                ? new ArrayList<>(records)
                : new ArrayList<>();
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return items.size();
            }

            @Override
            public int getNewListSize() {
                return updatedItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return sameItem(items.get(oldItemPosition), updatedItems.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return sameContent(items.get(oldItemPosition), updatedItems.get(newItemPosition));
            }
        });

        items.clear();
        items.addAll(updatedItems);
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_finance_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FinanceRecord item = items.get(position);
        holder.titleView.setText(item.getSafeTitle());
        bindStatusChip(holder, item);
        holder.amountLabelView.setText(item.isPayment
                ? R.string.finance_label_outstanding : R.string.finance_label_amount);
        holder.balanceLabelView.setText(item.isPayment
                ? R.string.finance_label_settlements : R.string.finance_label_balance);
        bindMoneyRow(holder.chargeRow, holder.chargeValueView, item.formatMoney(item.chargeText));

        bindMoneyCard(holder.amountCard, holder.amountValueView, item.formatMoney(item.amountText));
        bindMoneyCard(holder.balanceCard, holder.balanceValueView, item.formatMoney(item.balanceText));
        bindMoneyRow(holder.paidRow, holder.paidValueView, item.formatMoney(item.paidText));
        bindTextRow(holder.dueDateRow, holder.dueDateValueView, item.dueDateText);
        bindTextRow(holder.paidDateRow, holder.paidDateValueView, item.paidDateText);
        bindBalanceColor(holder, item);
        updateMetaVisibility(holder);

        if (item.hasAccount()) {
            holder.accountContainer.setVisibility(View.VISIBLE);
            holder.accountValueView.setText(item.getFormattedAccount());
            holder.copyAccountButton.setEnabled(true);
            holder.accountContainer.setOnClickListener(v -> listener.onCopyAccount(item));
        } else {
            holder.accountContainer.setVisibility(View.GONE);
            holder.accountValueView.setText("");
            holder.copyAccountButton.setEnabled(false);
            holder.accountContainer.setOnClickListener(null);
        }

        holder.copyAccountButton.setOnClickListener(v -> listener.onCopyAccount(item));
        holder.copyDetailsButton.setOnClickListener(v -> listener.onCopyDetails(item));
        holder.accountValueView.setOnClickListener(v -> {
            if (item.hasAccount()) {
                listener.onCopyAccount(item);
            }
        });
    }

    private void bindStatusChip(@NonNull ViewHolder holder, FinanceRecord item) {
        int backgroundRes;
        int textRes;
        int textColorAttr;

        switch (item.getStatus()) {
            case DUE:
                backgroundRes = R.drawable.bg_finance_due;
                textRes = R.string.finance_status_due;
                textColorAttr = R.attr.mzDanger;
                break;
            case PAID:
                backgroundRes = R.drawable.bg_finance_paid;
                textRes = R.string.finance_status_paid;
                textColorAttr = R.attr.mzSuccess;
                break;
            case OVERPAID:
                backgroundRes = R.drawable.bg_finance_overpaid;
                textRes = R.string.finance_status_overpaid;
                textColorAttr = R.attr.mzAccent;
                break;
            default:
                backgroundRes = R.drawable.bg_grade_type_chip;
                textRes = R.string.finance_status_unknown;
                textColorAttr = R.attr.mzPrimary;
                break;
        }

        holder.statusChipView.setText(textRes);
        holder.statusChipView.setBackgroundResource(backgroundRes);
        holder.statusChipView.setTextColor(
                ThemeManager.resolveColor(holder.itemView.getContext(), textColorAttr));
        holder.statusChipView.setTypeface(holder.statusChipView.getTypeface(), Typeface.BOLD);
    }

    private void bindTextRow(@NonNull View row, @NonNull android.widget.TextView valueView, String value) {
        if (value == null || value.trim().isEmpty()) {
            row.setVisibility(View.GONE);
            valueView.setText("");
            return;
        }
        row.setVisibility(View.VISIBLE);
        valueView.setText(value.trim());
    }

    private void bindMoneyRow(@NonNull View row, @NonNull android.widget.TextView valueView, String value) {
        bindTextRow(row, valueView, value);
    }

    private void bindMoneyCard(@NonNull View card, @NonNull android.widget.TextView valueView, String value) {
        String formatted = FinanceRecord.normalizeText(value);
        if (formatted == null || formatted.trim().isEmpty()) {
            card.setVisibility(View.GONE);
            valueView.setText("");
            return;
        }
        card.setVisibility(View.VISIBLE);
        valueView.setText(formatted);
    }

    private void updateMetaVisibility(@NonNull ViewHolder holder) {
        boolean hasMeta = holder.chargeRow.getVisibility() == View.VISIBLE
                || holder.paidRow.getVisibility() == View.VISIBLE
                || holder.dueDateRow.getVisibility() == View.VISIBLE
                || holder.paidDateRow.getVisibility() == View.VISIBLE;
        holder.metaContainer.setVisibility(hasMeta ? View.VISIBLE : View.GONE);
    }

    private void bindBalanceColor(@NonNull ViewHolder holder, @NonNull FinanceRecord item) {
        int colorAttr;
        switch (item.getStatus()) {
            case DUE:
                colorAttr = R.attr.mzDanger;
                break;
            case PAID:
                colorAttr = R.attr.mzSuccess;
                break;
            case OVERPAID:
                colorAttr = R.attr.mzAccent;
                break;
            default:
                colorAttr = R.attr.mzText;
                break;
        }
        holder.balanceValueView.setTextColor(
                ThemeManager.resolveColor(holder.itemView.getContext(), colorAttr));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean sameItem(FinanceRecord oldItem, FinanceRecord newItem) {
        if (oldItem == null || newItem == null) {
            return oldItem == newItem;
        }
        return Objects.equals(oldItem.getStableKey(), newItem.getStableKey());
    }

    private boolean sameContent(FinanceRecord oldItem, FinanceRecord newItem) {
        if (oldItem == null || newItem == null) {
            return oldItem == newItem;
        }
        return Objects.equals(oldItem.recordId, newItem.recordId)
                && Objects.equals(oldItem.title, newItem.title)
                && Objects.equals(oldItem.amountText, newItem.amountText)
                && Objects.equals(oldItem.chargeText, newItem.chargeText)
                && Objects.equals(oldItem.paidText, newItem.paidText)
                && Objects.equals(oldItem.dueDateText, newItem.dueDateText)
                && Objects.equals(oldItem.paidDateText, newItem.paidDateText)
                && Objects.equals(oldItem.balanceText, newItem.balanceText)
                && Objects.equals(oldItem.accountText, newItem.accountText)
                && Objects.equals(oldItem.currency, newItem.currency)
                && oldItem.isPayment == newItem.isPayment
                && oldItem.getStatus() == newItem.getStatus()
                && Double.compare(oldItem.amountValue, newItem.amountValue) == 0
                && Double.compare(oldItem.paidValue, newItem.paidValue) == 0
                && Double.compare(oldItem.balanceValue, newItem.balanceValue) == 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final android.widget.TextView titleView;
        final android.widget.TextView statusChipView;
        final View amountCard;
        final android.widget.TextView amountValueView;
        final android.widget.TextView amountLabelView;
        final View balanceCard;
        final android.widget.TextView balanceValueView;
        final android.widget.TextView balanceLabelView;
        final View chargeRow;
        final android.widget.TextView chargeValueView;
        final View metaContainer;
        final View paidRow;
        final android.widget.TextView paidValueView;
        final View dueDateRow;
        final android.widget.TextView dueDateValueView;
        final View paidDateRow;
        final android.widget.TextView paidDateValueView;
        final View accountContainer;
        final android.widget.TextView accountValueView;
        final MaterialButton copyAccountButton;
        final MaterialButton copyDetailsButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            titleView = itemView.findViewById(R.id.financeTitle);
            statusChipView = itemView.findViewById(R.id.financeStatusChip);
            amountCard = itemView.findViewById(R.id.financeAmountCard);
            amountValueView = itemView.findViewById(R.id.financeAmountValue);
            amountLabelView = itemView.findViewById(R.id.financeAmountLabel);
            balanceCard = itemView.findViewById(R.id.financeBalanceCard);
            balanceValueView = itemView.findViewById(R.id.financeBalanceValue);
            balanceLabelView = itemView.findViewById(R.id.financeBalanceLabel);
            chargeRow = itemView.findViewById(R.id.financeChargeRow);
            chargeValueView = itemView.findViewById(R.id.financeChargeValue);
            metaContainer = itemView.findViewById(R.id.financeMetaContainer);
            paidRow = itemView.findViewById(R.id.financePaidRow);
            paidValueView = itemView.findViewById(R.id.financePaidValue);
            dueDateRow = itemView.findViewById(R.id.financeDueDateRow);
            dueDateValueView = itemView.findViewById(R.id.financeDueDateValue);
            paidDateRow = itemView.findViewById(R.id.financePaidDateRow);
            paidDateValueView = itemView.findViewById(R.id.financePaidDateValue);
            accountContainer = itemView.findViewById(R.id.financeAccountContainer);
            accountValueView = itemView.findViewById(R.id.financeAccountValue);
            copyAccountButton = itemView.findViewById(R.id.btnFinanceCopyAccount);
            copyDetailsButton = itemView.findViewById(R.id.btnFinanceCopyDetails);
        }
    }
}
