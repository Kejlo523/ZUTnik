package pl.kejlo.mzutv2;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Model for user-created custom plan events (exams, tests, etc.)
 */
public class CustomPlanEvent {

    // Event types
    public static final String TYPE_EXAM = "exam"; // Egzamin
    public static final String TYPE_PASS = "pass"; // Zaliczenie
    public static final String TYPE_TEST = "test"; // Kolokwium

    public long id; // Unique identifier
    public String subjectName; // Subject name (e.g., "Matematyka dyskretna")
    public String eventType; // TYPE_EXAM, TYPE_PASS, TYPE_TEST
    public LocalDate date; // Event date
    public LocalTime startTime; // Start time
    public LocalTime endTime; // End time (optional, default +1h30m)
    public String notes; // Optional notes
    public boolean isAutoTime; // Whether time was auto-detected from schedule

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_TIME;

    public CustomPlanEvent() {
        this.id = System.currentTimeMillis();
    }

    public CustomPlanEvent(String subjectName, String eventType, LocalDate date,
            LocalTime startTime, LocalTime endTime) {
        this();
        this.subjectName = subjectName;
        this.eventType = eventType;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime != null ? endTime : (startTime != null ? startTime.plusMinutes(90) : null);
    }

    /**
     * Get display label for event type
     */
    public String getTypeLabel(android.content.Context context) {
        if (context == null) {
            switch (eventType) {
                case TYPE_EXAM:
                    return "Egzamin";
                case TYPE_PASS:
                    return "Zaliczenie";
                case TYPE_TEST:
                    return "Kolokwium";
                default:
                    return eventType;
            }
        }
        switch (eventType) {
            case TYPE_EXAM:
                return context.getString(R.string.plan_custom_type_exam);
            case TYPE_PASS:
                return context.getString(R.string.plan_custom_type_pass);
            case TYPE_TEST:
                return context.getString(R.string.plan_custom_type_test);
            default:
                return eventType;
        }
    }

    /**
     * Get short type label for overlay
     */
    public String getTypeShortLabel(android.content.Context context) {
        if (context == null) {
            switch (eventType) {
                case TYPE_EXAM:
                    return "EGZAMIN!";
                case TYPE_PASS:
                    return "ZALICZENIE!";
                case TYPE_TEST:
                    return "KOLOKWIUM!";
                default:
                    return eventType.toUpperCase();
            }
        }
        switch (eventType) {
            case TYPE_EXAM:
                return context.getString(R.string.plan_custom_type_exam_short);
            case TYPE_PASS:
                return context.getString(R.string.plan_custom_type_pass_short);
            case TYPE_TEST:
                return context.getString(R.string.plan_custom_type_test_short);
            default:
                return eventType.toUpperCase();
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("subjectName", subjectName);
        json.put("eventType", eventType);
        json.put("date", date != null ? date.format(DATE_FMT) : null);
        json.put("startTime", startTime != null ? startTime.format(TIME_FMT) : null);
        json.put("endTime", endTime != null ? endTime.format(TIME_FMT) : null);
        json.put("notes", notes);
        json.put("isAutoTime", isAutoTime);
        return json;
    }

    public static CustomPlanEvent fromJson(JSONObject json) {
        CustomPlanEvent e = new CustomPlanEvent();
        e.id = json.optLong("id", System.currentTimeMillis());
        e.subjectName = json.optString("subjectName", "");
        e.eventType = json.optString("eventType", TYPE_TEST);

        String dateStr = json.optString("date", null);
        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                e.date = LocalDate.parse(dateStr, DATE_FMT);
            } catch (Exception ignored) {
            }
        }

        String startStr = json.optString("startTime", null);
        if (startStr != null && !startStr.isEmpty()) {
            try {
                e.startTime = LocalTime.parse(startStr, TIME_FMT);
            } catch (Exception ignored) {
            }
        }

        String endStr = json.optString("endTime", null);
        if (endStr != null && !endStr.isEmpty()) {
            try {
                e.endTime = LocalTime.parse(endStr, TIME_FMT);
            } catch (Exception ignored) {
            }
        }

        e.notes = json.optString("notes", null);
        e.isAutoTime = json.optBoolean("isAutoTime", false);

        return e;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CustomPlanEvent that = (CustomPlanEvent) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
