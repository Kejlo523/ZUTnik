package pl.kejlo.mzutv2;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step-by-step wizard dialog for adding custom plan events (exams, tests, etc.)
 */
public class AddCustomEventDialog extends DialogFragment {

    private static final int TOTAL_STEPS = 5;
    private static final DateTimeFormatter DATE_DISPLAY_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_DISPLAY_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Views
    private TextView textStepIndicator;
    private TextView textStepTitle;
    private View step1Subject, step2Type, step3Date, step4Time, step5Summary;
    private Button btnBack, btnNext, btnCancel;
    private android.widget.ImageButton btnClose, btnBackArrow;

    // Step 1 - Subject
    private EditText editSubject;
    private RecyclerView recyclerSubjects;
    private TextView textNoSubjects;
    private SubjectAdapter subjectAdapter;

    // Step 2 - Type
    private RadioGroup radioGroupType;

    // Step 3 - Date
    private TextView textSelectedDate;
    private Button btnPickDate;

    // Step 4 - Time
    private TextView textTimeInfo;
    private RadioGroup radioGroupTime;
    private RadioButton radioTimeAuto, radioTimeManual;
    private LinearLayout layoutManualTime;
    private TextView textManualTime;
    private Button btnPickTime;

    // Step 5 - Summary
    private TextView summarySubject, summaryType, summaryDate, summaryTime;
    private EditText editNotes;
    private TextView textDuplicateWarning;

    // State
    private int currentStep = 1;
    private String selectedSubject = "";
    private String selectedEventType = CustomPlanEvent.TYPE_EXAM;
    private LocalDate selectedDate = LocalDate.now().plusDays(7);
    private LocalTime selectedStartTime = LocalTime.of(8, 0);
    private LocalTime selectedEndTime = LocalTime.of(9, 30);
    private boolean isAutoTime = true;
    private LocalTime autoDetectedStartTime = null;
    private LocalTime autoDetectedEndTime = null;

    // Editing mode
    private CustomPlanEvent editingEvent = null;

    // Callback
    public interface OnEventSavedListener {
        void onEventSaved(CustomPlanEvent event);
    }

    private OnEventSavedListener listener;

    public void setListener(OnEventSavedListener listener) {
        this.listener = listener;
    }

    /**
     * Create dialog for editing existing event
     */
    public static AddCustomEventDialog newInstance(CustomPlanEvent eventToEdit) {
        AddCustomEventDialog dialog = new AddCustomEventDialog();
        dialog.editingEvent = eventToEdit;
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_MZUTv2_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_add_custom_event, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupListeners();
        loadSubjectsFromPlan();

        // If editing, pre-populate fields
        if (editingEvent != null) {
            populateFromEvent(editingEvent);
        }

        updateStepUI();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void bindViews(View view) {
        textStepIndicator = view.findViewById(R.id.textStepIndicator);
        textStepTitle = view.findViewById(R.id.textStepTitle);

        step1Subject = view.findViewById(R.id.step1Subject);
        step2Type = view.findViewById(R.id.step2Type);
        step3Date = view.findViewById(R.id.step3Date);
        step4Time = view.findViewById(R.id.step4Time);
        step5Summary = view.findViewById(R.id.step5Summary);

        btnBack = view.findViewById(R.id.btnBack);
        btnNext = view.findViewById(R.id.btnNext);
        btnCancel = view.findViewById(R.id.btnCancel);
        btnClose = view.findViewById(R.id.btnClose);
        btnBackArrow = view.findViewById(R.id.btnBackArrow);

        // Step 1
        editSubject = view.findViewById(R.id.editSubject);
        recyclerSubjects = view.findViewById(R.id.recyclerSubjects);
        textNoSubjects = view.findViewById(R.id.textNoSubjects);

        // Step 2
        radioGroupType = view.findViewById(R.id.radioGroupType);

        // Step 3
        textSelectedDate = view.findViewById(R.id.textSelectedDate);
        btnPickDate = view.findViewById(R.id.btnPickDate);

        // Step 4
        textTimeInfo = view.findViewById(R.id.textTimeInfo);
        radioGroupTime = view.findViewById(R.id.radioGroupTime);
        radioTimeAuto = view.findViewById(R.id.radioTimeAuto);
        radioTimeManual = view.findViewById(R.id.radioTimeManual);
        layoutManualTime = view.findViewById(R.id.layoutManualTime);
        textManualTime = view.findViewById(R.id.textManualTime);
        btnPickTime = view.findViewById(R.id.btnPickTime);

        // Step 5
        summarySubject = view.findViewById(R.id.summarySubject);
        summaryType = view.findViewById(R.id.summaryType);
        summaryDate = view.findViewById(R.id.summaryDate);
        summaryTime = view.findViewById(R.id.summaryTime);
        editNotes = view.findViewById(R.id.editNotes);
        textDuplicateWarning = view.findViewById(R.id.textDuplicateWarning);
    }

    private void setupListeners() {
        btnCancel.setOnClickListener(v -> dismiss());
        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());

        // Close (X) button
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }

        // Back arrow button
        if (btnBackArrow != null) {
            btnBackArrow.setOnClickListener(v -> goBack());
        }

        // Step 1 - Subject text change
        editSubject.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                selectedSubject = s.toString().trim();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // Step 2 - Type selection
        radioGroupType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioExam) {
                selectedEventType = CustomPlanEvent.TYPE_EXAM;
            } else if (checkedId == R.id.radioPass) {
                selectedEventType = CustomPlanEvent.TYPE_PASS;
            } else if (checkedId == R.id.radioTest) {
                selectedEventType = CustomPlanEvent.TYPE_TEST;
            }
        });

        // Step 3 - Date picker
        btnPickDate.setOnClickListener(v -> showDatePicker());

        // Step 4 - Time mode
        radioGroupTime.setOnCheckedChangeListener((group, checkedId) -> {
            isAutoTime = (checkedId == R.id.radioTimeAuto);
            layoutManualTime.setVisibility(isAutoTime ? View.GONE : View.VISIBLE);
            if (!isAutoTime) {
                updateManualTimeDisplay();
            }
        });

        btnPickTime.setOnClickListener(v -> showTimePicker());

        // Subject adapter - clicking subject auto-advances to next step
        subjectAdapter = new SubjectAdapter(subject -> {
            selectedSubject = subject;
            editSubject.setText(subject);
            // Auto-advance to next step when subject is selected
            goNext();
        });
        recyclerSubjects.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerSubjects.setAdapter(subjectAdapter);
    }

    private void loadSubjectsFromPlan() {
        // Get unique subjects from PlanRepository cache
        List<String> subjects = new ArrayList<>();

        try {
            PlanRepository repo = new PlanRepository(getContext());
            List<PlanRepository.SubjectFilterItem> filterItems = repo.loadSubjectsForFilter();
            Set<String> uniqueSubjects = new HashSet<>();

            if (filterItems != null) {
                for (PlanRepository.SubjectFilterItem item : filterItems) {
                    if (item.label != null && !item.label.trim().isEmpty()) {
                        // Extract subject name (remove type suffix like " (Lab)")
                        String name = item.label;
                        int parenIdx = name.lastIndexOf(" (");
                        if (parenIdx > 0) {
                            name = name.substring(0, parenIdx);
                        }
                        uniqueSubjects.add(name.trim());
                    }
                }
            }

            subjects.addAll(uniqueSubjects);
            java.util.Collections.sort(subjects);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (subjects.isEmpty()) {
            recyclerSubjects.setVisibility(View.GONE);
            textNoSubjects.setVisibility(View.VISIBLE);
        } else {
            recyclerSubjects.setVisibility(View.VISIBLE);
            textNoSubjects.setVisibility(View.GONE);
            subjectAdapter.setSubjects(subjects);
        }
    }

    private void populateFromEvent(CustomPlanEvent event) {
        selectedSubject = event.subjectName != null ? event.subjectName : "";
        selectedEventType = event.eventType != null ? event.eventType : CustomPlanEvent.TYPE_EXAM;
        selectedDate = event.date != null ? event.date : LocalDate.now().plusDays(7);
        selectedStartTime = event.startTime != null ? event.startTime : LocalTime.of(8, 0);
        selectedEndTime = event.endTime != null ? event.endTime : selectedStartTime.plusMinutes(90);
        isAutoTime = event.isAutoTime;

        editSubject.setText(selectedSubject);

        switch (selectedEventType) {
            case CustomPlanEvent.TYPE_PASS:
                radioGroupType.check(R.id.radioPass);
                break;
            case CustomPlanEvent.TYPE_TEST:
                radioGroupType.check(R.id.radioTest);
                break;
            default:
                radioGroupType.check(R.id.radioExam);
        }
    }

    private void updateStepUI() {
        // Update step indicator
        textStepIndicator.setText(getString(R.string.plan_custom_wizard_step, currentStep, TOTAL_STEPS));

        // Update step title
        String[] titles = {
                getString(R.string.plan_custom_step_subject),
                getString(R.string.plan_custom_step_type),
                getString(R.string.plan_custom_step_date),
                getString(R.string.plan_custom_step_time),
                getString(R.string.plan_custom_step_summary)
        };
        textStepTitle.setText(titles[currentStep - 1]);

        // Show/hide step content
        step1Subject.setVisibility(currentStep == 1 ? View.VISIBLE : View.GONE);
        step2Type.setVisibility(currentStep == 2 ? View.VISIBLE : View.GONE);
        step3Date.setVisibility(currentStep == 3 ? View.VISIBLE : View.GONE);
        step4Time.setVisibility(currentStep == 4 ? View.VISIBLE : View.GONE);
        step5Summary.setVisibility(currentStep == 5 ? View.VISIBLE : View.GONE);

        // Show/hide back button
        btnBack.setVisibility(currentStep > 1 ? View.VISIBLE : View.GONE);
        if (btnBackArrow != null) {
            btnBackArrow.setVisibility(currentStep > 1 ? View.VISIBLE : View.GONE);
        }

        // Update next button text
        if (currentStep == TOTAL_STEPS) {
            btnNext.setText(R.string.plan_custom_btn_save);
        } else {
            btnNext.setText(R.string.plan_custom_btn_next);
        }

        // Step-specific updates
        if (currentStep == 3) {
            textSelectedDate.setText(selectedDate.format(DATE_DISPLAY_FMT));
        } else if (currentStep == 4) {
            updateStep4TimeUI();
        } else if (currentStep == 5) {
            updateSummary();
        }
    }

    private void updateStep4TimeUI() {
        // Try to auto-detect time from plan for selected subject on selected date
        autoDetectedStartTime = null;
        autoDetectedEndTime = null;

        try {
            // This would require looking up the plan for the selected date and subject
            // For now, we'll detect if there are classes and get their time
            PlanRepository repo = new PlanRepository(getContext());
            PlanRepository.PlanResult result = repo.loadPlan("day", selectedDate);

            if (result != null && result.dayColumns != null) {
                for (PlanRepository.DayColumn col : result.dayColumns) {
                    if (col.events != null) {
                        for (PlanRepository.PlanEventUi event : col.events) {
                            if (event.title != null &&
                                    event.title.toLowerCase().contains(selectedSubject.toLowerCase())) {
                                // Found matching class
                                autoDetectedStartTime = LocalTime.of(event.startMin / 60, event.startMin % 60);
                                autoDetectedEndTime = LocalTime.of(event.endMin / 60, event.endMin % 60);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (autoDetectedStartTime != null) {
            textTimeInfo.setVisibility(View.VISIBLE);
            textTimeInfo.setText("Znaleziono zajęcia: " + autoDetectedStartTime.format(TIME_DISPLAY_FMT) +
                    " - " + autoDetectedEndTime.format(TIME_DISPLAY_FMT));
            radioTimeAuto.setEnabled(true);
            radioTimeAuto.setText(getString(R.string.plan_custom_time_auto) +
                    " (" + autoDetectedStartTime.format(TIME_DISPLAY_FMT) + ")");
        } else {
            textTimeInfo.setVisibility(View.VISIBLE);
            textTimeInfo.setText(R.string.plan_custom_time_no_classes);
            radioTimeAuto.setEnabled(false);
            radioGroupTime.check(R.id.radioTimeManual);
            isAutoTime = false;
            layoutManualTime.setVisibility(View.VISIBLE);
        }

        updateManualTimeDisplay();
    }

    private void updateManualTimeDisplay() {
        textManualTime.setText(selectedStartTime.format(TIME_DISPLAY_FMT) +
                " - " + selectedEndTime.format(TIME_DISPLAY_FMT));
    }

    private void updateSummary() {
        summarySubject.setText(selectedSubject);

        CustomPlanEvent tempEvent = new CustomPlanEvent();
        tempEvent.eventType = selectedEventType;
        summaryType.setText(tempEvent.getTypeLabel(getContext()));

        summaryDate.setText(selectedDate.format(DATE_DISPLAY_FMT));

        LocalTime displayStart = isAutoTime && autoDetectedStartTime != null ? autoDetectedStartTime
                : selectedStartTime;
        LocalTime displayEnd = isAutoTime && autoDetectedEndTime != null ? autoDetectedEndTime : selectedEndTime;
        summaryTime.setText(displayStart.format(TIME_DISPLAY_FMT) + " - " + displayEnd.format(TIME_DISPLAY_FMT));

        if (editingEvent != null && editingEvent.notes != null) {
            editNotes.setText(editingEvent.notes);
        }

        // Check for duplicates in official plan
        checkForDuplicates();
    }

    private void checkForDuplicates() {
        textDuplicateWarning.setVisibility(View.GONE);

        try {
            PlanRepository repo = new PlanRepository(getContext());
            PlanRepository.PlanResult result = repo.loadPlan("day", selectedDate);

            if (result != null && result.dayColumns != null) {
                for (PlanRepository.DayColumn col : result.dayColumns) {
                    if (col.events != null) {
                        for (PlanRepository.PlanEventUi event : col.events) {
                            // Check if there's already an exam/pass for this subject
                            if (event.typeClass != null &&
                                    (event.typeClass.contains("exam") || event.typeClass.contains("pass"))) {
                                if (event.title != null &&
                                        event.title.toLowerCase().contains(selectedSubject.toLowerCase())) {
                                    // Found duplicate!
                                    textDuplicateWarning.setVisibility(View.VISIBLE);
                                    textDuplicateWarning.setText(
                                            getString(R.string.plan_custom_duplicate_warning, event.typeLabel));
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void goBack() {
        if (currentStep > 1) {
            currentStep--;
            updateStepUI();
        }
    }

    private void goNext() {
        // Validate current step
        if (!validateCurrentStep()) {
            return;
        }

        if (currentStep < TOTAL_STEPS) {
            currentStep++;
            updateStepUI();
        } else {
            // Save event
            saveEvent();
        }
    }

    private boolean validateCurrentStep() {
        switch (currentStep) {
            case 1:
                if (selectedSubject.isEmpty()) {
                    Toast.makeText(getContext(), "Podaj nazwę przedmiotu", Toast.LENGTH_SHORT).show();
                    return false;
                }
                break;
            case 3:
                if (selectedDate == null) {
                    Toast.makeText(getContext(), "Wybierz datę", Toast.LENGTH_SHORT).show();
                    return false;
                }
                break;
        }
        return true;
    }

    private void showDatePicker() {
        DatePickerDialog picker = new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                    textSelectedDate.setText(selectedDate.format(DATE_DISPLAY_FMT));
                },
                selectedDate.getYear(),
                selectedDate.getMonthValue() - 1,
                selectedDate.getDayOfMonth());
        picker.show();
    }

    private void showTimePicker() {
        TimePickerDialog picker = new TimePickerDialog(requireContext(),
                (view, hourOfDay, minute) -> {
                    selectedStartTime = LocalTime.of(hourOfDay, minute);
                    selectedEndTime = selectedStartTime.plusMinutes(90);
                    updateManualTimeDisplay();
                },
                selectedStartTime.getHour(),
                selectedStartTime.getMinute(),
                true // 24h format
        );
        picker.show();
    }

    private void saveEvent() {
        CustomPlanEvent event;
        if (editingEvent != null) {
            event = editingEvent;
        } else {
            event = new CustomPlanEvent();
        }

        event.subjectName = selectedSubject;
        event.eventType = selectedEventType;
        event.date = selectedDate;
        event.isAutoTime = isAutoTime;

        if (isAutoTime && autoDetectedStartTime != null) {
            event.startTime = autoDetectedStartTime;
            event.endTime = autoDetectedEndTime;
        } else {
            event.startTime = selectedStartTime;
            event.endTime = selectedEndTime;
        }

        String notes = editNotes.getText().toString().trim();
        event.notes = notes.isEmpty() ? null : notes;

        // Save to repository
        CustomPlanEventRepository repo = new CustomPlanEventRepository(requireContext());
        if (editingEvent != null) {
            repo.updateEvent(event);
            Toast.makeText(getContext(), R.string.plan_custom_updated_toast, Toast.LENGTH_SHORT).show();
        } else {
            repo.addEvent(event);
            Toast.makeText(getContext(), R.string.plan_custom_saved_toast, Toast.LENGTH_SHORT).show();
        }

        // Notify listener
        if (listener != null) {
            listener.onEventSaved(event);
        }

        dismiss();
    }

    // ==================== Subject Adapter ====================

    private static class SubjectAdapter extends RecyclerView.Adapter<SubjectAdapter.ViewHolder> {
        private List<String> subjects = new ArrayList<>();
        private final OnSubjectClickListener listener;

        interface OnSubjectClickListener {
            void onSubjectClick(String subject);
        }

        SubjectAdapter(OnSubjectClickListener listener) {
            this.listener = listener;
        }

        void setSubjects(List<String> subjects) {
            this.subjects = subjects;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(32, 24, 32, 24);
            tv.setTextSize(15f);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            return new ViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String subject = subjects.get(position);
            holder.textView.setText(subject);
            holder.textView.setOnClickListener(v -> listener.onSubjectClick(subject));
        }

        @Override
        public int getItemCount() {
            return subjects.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            ViewHolder(View itemView) {
                super(itemView);
                textView = (TextView) itemView;
            }
        }
    }
}
