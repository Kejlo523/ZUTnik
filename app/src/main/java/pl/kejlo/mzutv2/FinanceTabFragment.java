package pl.kejlo.mzutv2;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FinanceTabFragment extends MzutTabFragment implements FinanceAdapter.Listener {

    private Toolbar toolbar;

    private Spinner spinnerStudies;
    private MaterialButtonToggleGroup filterGroup;
    private RecyclerView listFinance;
    private TextView emptyView;
    private android.view.View progressView;
    private android.widget.ImageView refreshButton;

    private TextView summaryDueValue;
    private TextView summaryPaidValue;
    private TextView summaryOpenCountValue;
    private TextView summaryOverpaidValue;
    private TextView noticeDateView;
    private View noticeHeader;
    private View noticeContent;
    private ImageView noticeChevron;

    private ArrayAdapter<String> studiesAdapter;
    private boolean suppressStudySelectionCallback = false;

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.concurrent.Future<?> currentLoadFuture;

    private FinanceRepository financeRepository;
    private FinanceAdapter financeAdapter;
    private final List<FinanceRecord> currentRecords = new ArrayList<>();

    private FinanceRecord.Status currentFilter = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return ShellLayoutInflater.inflateTabContent(inflater, R.layout.activity_finance, container);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        toolbar = view.findViewById(R.id.toolbar);
        hostActivity().setSupportActionBar(toolbar);
        MainNavHelper.styleToolbarPublic(hostActivity(), toolbar);
        toolbar.setTitle(R.string.finance_title);

        spinnerStudies = view.findViewById(R.id.spinnerStudies);
        filterGroup = view.findViewById(R.id.financeFilterGroup);
        listFinance = view.findViewById(R.id.listFinance);
        emptyView = view.findViewById(R.id.financeEmptyView);
        progressView = view.findViewById(R.id.financeProgress);
        refreshButton = view.findViewById(R.id.btnFinanceRefresh);
        summaryDueValue = view.findViewById(R.id.tvFinanceSummaryDueValue);
        summaryPaidValue = view.findViewById(R.id.tvFinanceSummaryPaidValue);
        summaryOpenCountValue = view.findViewById(R.id.tvFinanceSummaryOpenValue);
        summaryOverpaidValue = view.findViewById(R.id.tvFinanceSummaryOverpaidValue);
        noticeDateView = view.findViewById(R.id.tvFinanceNoticeDate);
        noticeHeader = view.findViewById(R.id.financeNoticeHeader);
        noticeContent = view.findViewById(R.id.financeNoticeContent);
        noticeChevron = view.findViewById(R.id.financeNoticeChevron);

        financeRepository = new FinanceRepository(requireContext());

        financeAdapter = new FinanceAdapter(this);
        listFinance.setLayoutManager(new LinearLayoutManager(requireContext()));
        listFinance.setAdapter(financeAdapter);

        setupFilterGroup();
        setupStudiesSpinner();
        setupNoticeSection();
        bindNoticeDate(0L);

        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> startLoad(true));
        }

        startLoad(false);
    }

    private void setupFilterGroup() {
        if (filterGroup == null) {
            return;
        }
        filterGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.filterDue) {
                currentFilter = FinanceRecord.Status.DUE;
            } else if (checkedId == R.id.filterPaid) {
                currentFilter = FinanceRecord.Status.PAID;
            } else if (checkedId == R.id.filterOverpaid) {
                currentFilter = FinanceRecord.Status.OVERPAID;
            } else {
                currentFilter = null;
            }
            applyFilter();
        });
        filterGroup.check(R.id.filterAll);
    }

    private void setupStudiesSpinner() {
        studiesAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item_dark, new ArrayList<>());
        studiesAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerStudies.setAdapter(studiesAdapter);
        spinnerStudies.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressStudySelectionCallback) {
                    return;
                }
                MzutSession session = MzutSession.getInstance();
                if (position == session.getActiveStudyIndex()) {
                    return;
                }
                session.setActiveStudyIndex(position);
                session.saveToPreferences(requireContext());
                startLoad(false);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void setupNoticeSection() {
        if (noticeHeader == null || noticeContent == null || noticeChevron == null) {
            return;
        }
        noticeContent.setVisibility(View.GONE);
        noticeChevron.setRotation(0f);
        noticeHeader.setOnClickListener(v -> {
            boolean expand = noticeContent.getVisibility() != View.VISIBLE;
            noticeContent.setVisibility(expand ? View.VISIBLE : View.GONE);
            noticeChevron.animate()
                    .rotation(expand ? 180f : 0f)
                    .setDuration(180L)
                    .start();
        });
    }

    private void bindNoticeDate(long fetchedAt) {
        if (noticeDateView == null) {
            return;
        }
        if (fetchedAt <= 0L) {
            noticeDateView.setText(R.string.finance_notice_loading);
            return;
        }
        Locale locale = getResources().getConfiguration().getLocales().isEmpty()
                ? Locale.getDefault()
                : getResources().getConfiguration().getLocales().get(0);
        String formattedDate = DateFormat.getDateTimeInstance(
                DateFormat.LONG,
                DateFormat.SHORT,
                locale).format(new Date(fetchedAt));
        noticeDateView.setText(getString(R.string.finance_notice_main, formattedDate));
    }

    private void startLoad(boolean forceRefreshStudies) {
        if (currentLoadFuture != null) {
            currentLoadFuture.cancel(true);
        }

        updateFinanceDataFreshnessText(getString(R.string.data_status_syncing));
        progressView.setVisibility(android.view.View.VISIBLE);
        emptyView.setVisibility(android.view.View.GONE);
        listFinance.setAlpha(0.35f);

        currentLoadFuture = executor.submit(() -> {
            List<Study> studies = new ArrayList<>();
            List<FinanceRecord> records = new ArrayList<>();
            long fetchedAt = 0L;
            boolean fromCache = false;
            Exception error = null;
            String resultingStudyId;

            try {
                studies = financeRepository.loadStudies(forceRefreshStudies);
                FinanceRepository.FinanceSnapshot snapshot =
                        financeRepository.loadPaymentsForActiveStudy(forceRefreshStudies);
                records = snapshot.records;
                fetchedAt = snapshot.fetchedAt;
                fromCache = snapshot.fromCache;
            } catch (Exception e) {
                error = e;
            }
            resultingStudyId = getCurrentStudyScopeKey();

            final List<Study> finalStudies = studies;
            final List<FinanceRecord> finalRecords = records;
            final long finalFetchedAt = fetchedAt;
            final boolean finalFromCache = fromCache;
            final Exception finalError = error;
            final String finalStudyId = resultingStudyId;

            handler.post(() -> {
                progressView.setVisibility(android.view.View.GONE);
                listFinance.setAlpha(1f);

                if (finalStudyId != null && !finalStudyId.equals(getCurrentStudyScopeKey())) {
                    return;
                }

                bindStudies(finalStudies);

                if (finalError != null) {
                    financeAdapter.submitList(new ArrayList<>());
                    currentRecords.clear();
                    updateSummary(new ArrayList<>());
                    bindNoticeDate(0L);
                    updateFinanceDataFreshness(false, 0L);
                    emptyView.setVisibility(android.view.View.VISIBLE);
                    emptyView.setText(getString(R.string.finance_error_loading, safeMessage(finalError)));
                    Toast.makeText(
                            requireContext(),
                            getString(R.string.finance_error_loading, safeMessage(finalError)),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                currentRecords.clear();
                currentRecords.addAll(finalRecords);
                sortRecords(currentRecords);
                updateSummary(currentRecords);
                bindNoticeDate(finalFetchedAt);
                updateFinanceDataFreshness(!finalFromCache, finalFetchedAt);
                applyFilter();
            });
        });
    }

    private void updateFinanceDataFreshness(boolean fetchedFromNetwork, long fetchedAt) {
        long now = System.currentTimeMillis();
        if (fetchedFromNetwork) {
            updateFinanceDataFreshnessText(getString(R.string.data_status_online_now));
            return;
        }

        if (fetchedAt > 0L) {
            if ((now - fetchedAt) < DateUtils.MINUTE_IN_MILLIS) {
                updateFinanceDataFreshnessText(getString(R.string.data_status_online_now));
                return;
            }
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    fetchedAt,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            updateFinanceDataFreshnessText(getString(R.string.data_status_cache_since, rel));
        } else {
            updateFinanceDataFreshnessText(getString(R.string.data_status_cache));
        }
    }

    private void updateFinanceDataFreshnessText(String text) {
        if (toolbar != null) {
            String safe = text != null ? text : "";
            SpannableString subtitle = new SpannableString(safe);
            if (!safe.isEmpty()) {
                subtitle.setSpan(
                        new RelativeSizeSpan(0.78f),
                        0,
                        safe.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            toolbar.setSubtitle(subtitle);
        }
    }

    private void bindStudies(List<Study> studies) {
        if (studies == null || studies.isEmpty()) {
            spinnerStudies.setVisibility(android.view.View.GONE);
            return;
        }

        spinnerStudies.setVisibility(android.view.View.VISIBLE);
        List<String> labels = new ArrayList<>(studies.size());
        for (Study study : studies) {
            labels.add(study != null ? study.toString() : getString(R.string.finance_study_fallback));
        }

        MzutSession session = MzutSession.getInstance();
        int activeIndex = session.getActiveStudyIndex();
        if (activeIndex < 0 || activeIndex >= labels.size()) {
            activeIndex = 0;
        }

        suppressStudySelectionCallback = true;
        try {
            studiesAdapter.clear();
            studiesAdapter.addAll(labels);
            studiesAdapter.notifyDataSetChanged();
            spinnerStudies.setSelection(activeIndex);
        } finally {
            suppressStudySelectionCallback = false;
        }
    }

    private void applyFilter() {
        List<FinanceRecord> filtered = new ArrayList<>();
        for (FinanceRecord record : currentRecords) {
            if (record == null) {
                continue;
            }
            if (currentFilter == null || record.matchesFilter(currentFilter)) {
                filtered.add(record);
            }
        }

        financeAdapter.submitList(filtered);
        if (filtered.isEmpty()) {
            emptyView.setVisibility(android.view.View.VISIBLE);
            emptyView.setText(getEmptyStateText());
        } else {
            emptyView.setVisibility(android.view.View.GONE);
        }
    }

    private void updateSummary(List<FinanceRecord> records) {
        double dueTotal = 0.0d;
        double paidTotal = 0.0d;
        double overpaidTotal = 0.0d;
        int openItems = 0;

        for (FinanceRecord record : records) {
            if (record == null) {
                continue;
            }
            paidTotal += Math.max(0.0d, record.paidValue);
            if (record.getStatus() == FinanceRecord.Status.DUE) {
                dueTotal += Math.abs(record.balanceValue);
                openItems++;
            } else if (record.getStatus() == FinanceRecord.Status.OVERPAID) {
                overpaidTotal += record.balanceValue;
            }
        }

        summaryDueValue.setText(formatMoney(dueTotal));
        summaryPaidValue.setText(formatMoney(paidTotal));
        summaryOpenCountValue.setText(String.valueOf(openItems));
        summaryOverpaidValue.setText(formatMoney(overpaidTotal));
    }

    private String getEmptyStateText() {
        if (currentFilter == FinanceRecord.Status.DUE) {
            return getString(R.string.finance_empty_due);
        }
        if (currentFilter == FinanceRecord.Status.PAID) {
            return getString(R.string.finance_empty_paid);
        }
        if (currentFilter == FinanceRecord.Status.OVERPAID) {
            return getString(R.string.finance_empty_overpaid);
        }
        return getString(R.string.finance_empty_all);
    }

    private void sortRecords(List<FinanceRecord> records) {
        if (records == null || records.size() < 2) {
            return;
        }

        Collections.sort(records, new Comparator<FinanceRecord>() {
            @Override
            public int compare(FinanceRecord left, FinanceRecord right) {
                int statusCompare = Integer.compare(getStatusRank(left), getStatusRank(right));
                if (statusCompare != 0) {
                    return statusCompare;
                }

                long leftDate = left != null ? left.getRelevantDateSortKey() : Long.MAX_VALUE;
                long rightDate = right != null ? right.getRelevantDateSortKey() : Long.MAX_VALUE;
                boolean descendingDate = left != null && left.getStatus() == FinanceRecord.Status.PAID;
                int dateCompare = descendingDate
                        ? Long.compare(rightDate, leftDate)
                        : Long.compare(leftDate, rightDate);
                if (dateCompare != 0) {
                    return dateCompare;
                }

                String leftTitle = left != null ? left.getSafeTitle() : "";
                String rightTitle = right != null ? right.getSafeTitle() : "";
                return leftTitle.compareToIgnoreCase(rightTitle);
            }
        });
    }

    private int getStatusRank(FinanceRecord record) {
        if (record == null) {
            return Integer.MAX_VALUE;
        }
        switch (record.getStatus()) {
            case DUE:
                return 0;
            case OVERPAID:
                return 1;
            case PAID:
                return 2;
            default:
                return 3;
        }
    }

    private String formatMoney(double value) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(Math.abs(value - Math.rint(value)) > 0.0001d ? 2 : 0);
        return getString(R.string.finance_money_value, nf.format(value));
    }

    private String getCurrentStudyScopeKey() {
        MzutSession session = MzutSession.getInstance();
        String activeId = session.getActiveStudyId();
        if (activeId != null && !activeId.trim().isEmpty()) {
            return activeId.trim();
        }
        Study active = session.getActiveStudy();
        if (active != null && active.przynaleznoscId != null && !active.przynaleznoscId.trim().isEmpty()) {
            return active.przynaleznoscId.trim();
        }
        return null;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private String buildDetailsText(FinanceRecord record) {
        List<String> lines = new ArrayList<>();
        lines.add(record.getSafeTitle());

        if (record.amountText != null) {
            lines.add(getString(R.string.finance_line_amount, FinanceRecord.formatMoneyText(record.amountText)));
        }
        if (record.paidText != null) {
            lines.add(getString(R.string.finance_line_paid, FinanceRecord.formatMoneyText(record.paidText)));
        }
        if (record.dueDateText != null) {
            lines.add(getString(R.string.finance_line_due_date, record.dueDateText));
        }
        if (record.paidDateText != null) {
            lines.add(getString(R.string.finance_line_paid_date, record.paidDateText));
        }
        if (record.balanceText != null) {
            lines.add(getString(R.string.finance_line_balance, FinanceRecord.formatMoneyText(record.balanceText)));
        }
        if (record.hasAccount()) {
            lines.add(getString(R.string.finance_line_account, record.getCopyableAccount()));
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    @Override
    public void onCopyAccount(FinanceRecord record) {
        if (record == null || !record.hasAccount()) {
            Toast.makeText(requireContext(), R.string.finance_copy_account_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("finance_account", record.getCopyableAccount()));
            showAccountVerificationDialog();
        }
    }

    @Override
    public void onCopyDetails(FinanceRecord record) {
        if (record == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("finance_details", buildDetailsText(record)));
            Toast.makeText(requireContext(), R.string.finance_copy_details_success, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAccountVerificationDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.finance_copy_account_dialog_title)
                .setMessage(R.string.finance_copy_account_dialog_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (currentLoadFuture != null) {
            currentLoadFuture.cancel(true);
        }
        executor.shutdownNow();
    }
}
