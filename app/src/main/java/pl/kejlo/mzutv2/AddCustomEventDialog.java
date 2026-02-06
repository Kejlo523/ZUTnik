package pl.kejlo.mzutv2;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Quick dialog for adding or marking custom plan events (exam/pass/test).
 */
public class AddCustomEventDialog extends DialogFragment {

    private static final String ARG_DATE = "arg_date";
    private static final String ARG_START_MIN = "arg_start_min";
    private static final String ARG_END_MIN = "arg_end_min";
    private static final String ARG_SUBJECT = "arg_subject";
    private static final String ARG_LOCK_SUBJECT = "arg_lock_subject";
    private static final String ARG_LOCK_TIME = "arg_lock_time";
    private static final String ARG_CUSTOM_EVENT_ID = "arg_custom_event_id";
    private static final String ARG_EXISTING_TYPE = "arg_existing_type";
    private static final String ARG_ROOM = "arg_room";
    private static final String ARG_GROUP = "arg_group";
    private static final String ARG_TEACHER = "arg_teacher";
    private static final String ARG_IS_MARKER = "arg_is_marker";

    private static final DateTimeFormatter DATE_DISPLAY_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_DISPLAY_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private TextView textTitle;
    private ImageButton btnClose;
    private TextInputLayout inputSubjectLayout;
    private AutoCompleteTextView editSubject;
    private ChipGroup chipGroupType;
    private Chip chipExam;
    private Chip chipPass;
    private Chip chipTest;
    private TextView textDate;
    private TextView textTime;
    private LinearLayout detailsContainer;
    private TextView textDetails;
    private TextInputEditText editNotes;
    private Button btnCancel;
    private Button btnSave;
    private Button btnDelete;

    private LocalDate selectedDate = LocalDate.now();
    private LocalTime selectedStartTime = LocalTime.of(8, 0);
    private LocalTime selectedEndTime = LocalTime.of(9, 30);
    private String selectedSubject = "";
    private String selectedEventType = CustomPlanEvent.TYPE_EXAM;
    private boolean lockSubject = false;
    private boolean lockTime = false;
    private boolean isMarker = false;
    private long editingEventId = -1L;
    private CustomPlanEvent editingEvent = null;
    private String detailsRoom = "";
    private String detailsGroup = "";
    private String detailsTeacher = "";

    public interface OnEventSavedListener {
        void onEventSaved(CustomPlanEvent event);
    }

    private OnEventSavedListener listener;

    public void setListener(OnEventSavedListener listener) {
        this.listener = listener;
    }

    public static AddCustomEventDialog newForSlot(LocalDate date, int startMin, int endMin) {
        AddCustomEventDialog dialog = new AddCustomEventDialog();
        Bundle args = new Bundle();
        if (date != null) {
            args.putString(ARG_DATE, date.toString());
        }
        args.putInt(ARG_START_MIN, startMin);
        args.putInt(ARG_END_MIN, endMin);
        args.putBoolean(ARG_LOCK_SUBJECT, false);
        args.putBoolean(ARG_LOCK_TIME, false);
        args.putBoolean(ARG_IS_MARKER, false);
        dialog.setArguments(args);
        return dialog;
    }

    public static AddCustomEventDialog newForEvent(LocalDate date, PlanRepository.PlanEventUi ev) {
        AddCustomEventDialog dialog = new AddCustomEventDialog();
        Bundle args = new Bundle();
        if (date != null) {
            args.putString(ARG_DATE, date.toString());
        }
        if (ev != null) {
            args.putInt(ARG_START_MIN, ev.startMin);
            args.putInt(ARG_END_MIN, ev.endMin);
            args.putString(ARG_SUBJECT, extractBaseSubject(ev.title));
            args.putString(ARG_ROOM, ev.room);
            args.putString(ARG_GROUP, ev.group);
            args.putString(ARG_TEACHER, ev.teacher);
            if (ev.customEventType != null) {
                args.putString(ARG_EXISTING_TYPE, ev.customEventType);
            }
            if (ev.customEventId != null) {
                try {
                    args.putLong(ARG_CUSTOM_EVENT_ID, Long.parseLong(ev.customEventId));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        args.putBoolean(ARG_LOCK_SUBJECT, true);
        args.putBoolean(ARG_LOCK_TIME, true);
        args.putBoolean(ARG_IS_MARKER, true);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_MZUTv2_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_add_custom_event, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        readArgs();
        setupUi();
        loadSubjectsAsync();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));

            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            window.getWindowManager().getDefaultDisplay().getMetrics(dm);
            boolean isLandscape = dm.widthPixels > dm.heightPixels;
            float widthRatio = isLandscape ? 0.62f : 0.86f;
            int maxWidth = (int) (dm.density * (isLandscape ? 480f : 620f));
            int targetWidth = Math.min((int) (dm.widthPixels * widthRatio), maxWidth);
            int maxHeight = (int) (dm.heightPixels * 0.88f);
            window.setGravity(android.view.Gravity.CENTER);

            View content = getView();
            if (content != null) {
                content.post(() -> {
                    int h = content.getHeight();
                    int targetHeight = Math.min(h, maxHeight);
                    window.setLayout(targetWidth, targetHeight);
                });
            } else {
                window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    private void bindViews(View view) {
        textTitle = view.findViewById(R.id.textTitle);
        btnClose = view.findViewById(R.id.btnClose);
        inputSubjectLayout = view.findViewById(R.id.inputSubjectLayout);
        editSubject = view.findViewById(R.id.editSubject);
        chipGroupType = view.findViewById(R.id.chipGroupType);
        chipExam = view.findViewById(R.id.chipExam);
        chipPass = view.findViewById(R.id.chipPass);
        chipTest = view.findViewById(R.id.chipTest);
        textDate = view.findViewById(R.id.textDate);
        textTime = view.findViewById(R.id.textTime);
        detailsContainer = view.findViewById(R.id.detailsContainer);
        textDetails = view.findViewById(R.id.textDetails);
        editNotes = view.findViewById(R.id.editNotes);
        btnCancel = view.findViewById(R.id.btnCancel);
        btnSave = view.findViewById(R.id.btnSave);
        btnDelete = view.findViewById(R.id.btnDelete);
    }

    private void readArgs() {
        Bundle args = getArguments();
        if (args == null) {
            return;
        }

        String dateStr = args.getString(ARG_DATE, null);
        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                selectedDate = LocalDate.parse(dateStr);
            } catch (Exception ignored) {
            }
        }

        int startMin = args.getInt(ARG_START_MIN, -1);
        if (startMin >= 0) {
            selectedStartTime = LocalTime.of(startMin / 60, startMin % 60);
        }

        int endMin = args.getInt(ARG_END_MIN, -1);
        if (endMin >= 0) {
            selectedEndTime = LocalTime.of(endMin / 60, endMin % 60);
        } else {
            selectedEndTime = selectedStartTime.plusMinutes(90);
        }

        selectedSubject = args.getString(ARG_SUBJECT, "");
        lockSubject = args.getBoolean(ARG_LOCK_SUBJECT, false);
        lockTime = args.getBoolean(ARG_LOCK_TIME, false);
        isMarker = args.getBoolean(ARG_IS_MARKER, false);
        editingEventId = args.getLong(ARG_CUSTOM_EVENT_ID, -1L);

        String existingType = args.getString(ARG_EXISTING_TYPE, null);
        if (existingType != null && !existingType.isEmpty()) {
            selectedEventType = existingType;
        }

        detailsRoom = args.getString(ARG_ROOM, "");
        detailsGroup = args.getString(ARG_GROUP, "");
        detailsTeacher = args.getString(ARG_TEACHER, "");
    }

    private void setupUi() {
        textTitle.setText(isMarker ? R.string.plan_quick_dialog_title_mark : R.string.plan_quick_dialog_title_add);

        editSubject.setText(selectedSubject);
        editSubject.setThreshold(0);
        if (lockSubject) {
            editSubject.setEnabled(false);
            editSubject.setFocusable(false);
            editSubject.setClickable(false);
            inputSubjectLayout.setHint(getString(R.string.plan_quick_subject_label));
        }

        updateDateTimeLabels();
        setupDetailsSection();

        if (CustomPlanEvent.TYPE_PASS.equals(selectedEventType)) {
            chipPass.setChecked(true);
        } else if (CustomPlanEvent.TYPE_TEST.equals(selectedEventType)) {
            chipTest.setChecked(true);
        } else {
            chipExam.setChecked(true);
        }

        chipGroupType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chipPass) {
                selectedEventType = CustomPlanEvent.TYPE_PASS;
            } else if (checkedId == R.id.chipTest) {
                selectedEventType = CustomPlanEvent.TYPE_TEST;
            } else {
                selectedEventType = CustomPlanEvent.TYPE_EXAM;
            }
        });

        if (!lockTime) {
            textDate.setOnClickListener(v -> showDatePicker());
            textTime.setOnClickListener(v -> showTimePicker());
        } else {
            textDate.setEnabled(false);
            textTime.setEnabled(false);
        }

        btnClose.setOnClickListener(v -> dismiss());
        btnCancel.setOnClickListener(v -> dismiss());
        btnSave.setOnClickListener(v -> saveEvent());

        if (editingEventId > 0) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
            loadEditingEvent();
        }
    }

    private void setupDetailsSection() {
        StringBuilder details = new StringBuilder();
        if (detailsRoom != null && !detailsRoom.isEmpty()) {
            details.append(getString(R.string.plan_event_room_prefix)).append(detailsRoom);
        }
        if (detailsGroup != null && !detailsGroup.isEmpty()) {
            if (details.length() > 0)
                details.append("\n");
            details.append(getString(R.string.plan_event_group_prefix)).append(detailsGroup);
        }
        if (detailsTeacher != null && !detailsTeacher.isEmpty()) {
            if (details.length() > 0)
                details.append("\n");
            details.append(getString(R.string.plan_event_teacher_prefix)).append(detailsTeacher);
        }

        if (details.length() > 0) {
            detailsContainer.setVisibility(View.VISIBLE);
            textDetails.setText(details.toString());
        } else {
            detailsContainer.setVisibility(View.GONE);
        }
    }

    private void updateDateTimeLabels() {
        textDate.setText(selectedDate.format(DATE_DISPLAY_FMT));
        String range = getString(R.string.plan_quick_time_range,
                selectedStartTime.format(TIME_DISPLAY_FMT),
                selectedEndTime.format(TIME_DISPLAY_FMT));
        textTime.setText(range);
    }

    private void loadSubjectsAsync() {
        android.content.Context ctx = getContext();
        if (ctx == null) {
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        editSubject.setAdapter(adapter);

        Executors.newSingleThreadExecutor().execute(() -> {
            Set<String> subjectSet = new HashSet<>();
            try {
                PlanRepository repo = new PlanRepository(ctx);
                List<PlanRepository.SubjectFilterItem> filterItems = repo.loadSubjectsForFilter();
                if (filterItems != null) {
                    for (PlanRepository.SubjectFilterItem item : filterItems) {
                        if (item != null && item.label != null && !item.label.trim().isEmpty()) {
                            subjectSet.add(extractBaseSubject(item.label));
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                CustomPlanEventRepository customRepo = new CustomPlanEventRepository(ctx);
                subjectSet.addAll(customRepo.getSavedSubjectNames());
            } catch (Exception ignored) {
            }

            List<String> subjects = new ArrayList<>(subjectSet);
            java.util.Collections.sort(subjects);

            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    adapter.clear();
                    adapter.addAll(subjects);
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void loadEditingEvent() {
        try {
            CustomPlanEventRepository repo = new CustomPlanEventRepository(requireContext());
            editingEvent = repo.getEventById(editingEventId);
            if (editingEvent != null) {
                if (!lockSubject && editingEvent.subjectName != null) {
                    editSubject.setText(editingEvent.subjectName);
                }
                if (editingEvent.eventType != null) {
                    selectedEventType = editingEvent.eventType;
                }
                if (editingEvent.date != null) {
                    selectedDate = editingEvent.date;
                }
                if (editingEvent.startTime != null) {
                    selectedStartTime = editingEvent.startTime;
                }
                if (editingEvent.endTime != null) {
                    selectedEndTime = editingEvent.endTime;
                }
                if (editingEvent.notes != null) {
                    editNotes.setText(editingEvent.notes);
                }
                updateDateTimeLabels();
                if (CustomPlanEvent.TYPE_PASS.equals(selectedEventType)) {
                    chipPass.setChecked(true);
                } else if (CustomPlanEvent.TYPE_TEST.equals(selectedEventType)) {
                    chipTest.setChecked(true);
                } else {
                    chipExam.setChecked(true);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void showDatePicker() {
        DatePickerDialog picker = new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                    updateDateTimeLabels();
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
                    updateDateTimeLabels();
                },
                selectedStartTime.getHour(),
                selectedStartTime.getMinute(),
                true);
        picker.show();
    }

    private void saveEvent() {
        String subject = editSubject.getText() != null ? editSubject.getText().toString().trim() : "";
        if (subject.isEmpty()) {
            Toast.makeText(getContext(), R.string.plan_quick_error_subject, Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedEventType == null || selectedEventType.isEmpty()) {
            Toast.makeText(getContext(), R.string.plan_quick_error_type, Toast.LENGTH_SHORT).show();
            return;
        }

        CustomPlanEvent event = editingEvent != null ? editingEvent : new CustomPlanEvent();
        event.subjectName = subject;
        event.eventType = selectedEventType;
        event.date = selectedDate;
        event.startTime = selectedStartTime;
        event.endTime = selectedEndTime;
        event.isAutoTime = false;

        String notes = editNotes.getText() != null ? editNotes.getText().toString().trim() : "";
        event.notes = notes.isEmpty() ? null : notes;

        CustomPlanEventRepository repo = new CustomPlanEventRepository(requireContext());
        if (editingEvent != null) {
            repo.updateEvent(event);
            Toast.makeText(getContext(), R.string.plan_custom_updated_toast, Toast.LENGTH_SHORT).show();
        } else {
            repo.addEvent(event);
            Toast.makeText(getContext(), R.string.plan_custom_saved_toast, Toast.LENGTH_SHORT).show();
        }

        if (listener != null) {
            listener.onEventSaved(event);
        }
        dismiss();
    }

    private void confirmDelete() {
        if (getContext() == null)
            return;

        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.plan_custom_delete_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteEvent())
                .setNegativeButton(R.string.plan_quick_btn_cancel, null)
                .show();
    }

    private void deleteEvent() {
        if (editingEventId <= 0)
            return;

        CustomPlanEventRepository repo = new CustomPlanEventRepository(requireContext());
        repo.deleteEvent(editingEventId);

        int toastRes = isMarker ? R.string.plan_custom_removed_toast : R.string.plan_custom_deleted_toast;
        Toast.makeText(getContext(), toastRes, Toast.LENGTH_SHORT).show();

        if (listener != null) {
            listener.onEventSaved(null);
        }
        dismiss();
    }

    private static String extractBaseSubject(String label) {
        if (label == null)
            return "";
        String name = label.trim();
        int parenIdx = name.lastIndexOf(" (");
        if (parenIdx > 0) {
            name = name.substring(0, parenIdx);
        }
        return name.trim();
    }
}
