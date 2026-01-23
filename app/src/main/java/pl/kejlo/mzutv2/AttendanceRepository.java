package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for managing attendance data.
 * Persists absence records and user-defined hours in SharedPreferences.
 */
public class AttendanceRepository {
    private static final String TAG = "mZUTv2-ATTENDANCE";
    private static final String PREFS_NAME = "attendance_prefs";
    private static final String KEY_ABSENCES = "absences_json";
    private static final String KEY_HOURS = "hours_json";

    private final Context context;
    private final PlanRepository planRepository;

    public AttendanceRepository(Context context) {
        this.context = context.getApplicationContext();
        this.planRepository = new PlanRepository(context);
    }

    /**
     * Load all subjects with their user-defined hours and any saved absences.
     */
    public List<Absence> loadSubjectsWithAbsences() {
        // Get subjects from plan
        List<PlanRepository.SubjectFilterItem> subjects;
        try {
            subjects = planRepository.loadSubjectsForFilter();
        } catch (Exception e) {
            Log.w(TAG, "Failed to load subjects: " + e.getMessage());
            subjects = new ArrayList<>();
        }

        // Load user-defined hours
        Map<String, Integer> savedHours = loadSavedHours();

        // Load saved absences
        Map<String, Integer> savedAbsences = loadSavedAbsences();

        // Build result list
        List<Absence> result = new ArrayList<>();
        for (PlanRepository.SubjectFilterItem item : subjects) {
            Absence absence = new Absence();
            absence.subjectKey = item.filterKey;
            absence.subjectName = item.label;
            absence.subjectType = item.typeLabel;
            // Use user-defined hours, default to 0 if not set
            absence.totalHours = savedHours.getOrDefault(item.filterKey, 0);
            absence.absenceCount = savedAbsences.getOrDefault(item.filterKey, 0);
            result.add(absence);
        }

        return result;
    }

    /**
     * Load user-defined hours from SharedPreferences.
     */
    private Map<String, Integer> loadSavedHours() {
        Map<String, Integer> result = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HOURS, null);

        if (json == null)
            return result;

        try {
            JSONObject obj = new JSONObject(json);
            JSONArray keys = obj.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i);
                    result.put(key, obj.getInt(key));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse hours: " + e.getMessage());
        }

        return result;
    }

    /**
     * Save user-defined hours for a specific subject.
     */
    public void saveHours(String subjectKey, int hours) {
        Map<String, Integer> hoursMap = loadSavedHours();
        hoursMap.put(subjectKey, hours);

        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Integer> entry : hoursMap.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_HOURS, obj.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to save hours: " + e.getMessage());
        }
    }

    /**
     * Load saved absence counts from SharedPreferences.
     */
    private Map<String, Integer> loadSavedAbsences() {
        Map<String, Integer> result = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ABSENCES, null);

        if (json == null)
            return result;

        try {
            JSONObject obj = new JSONObject(json);
            JSONArray keys = obj.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i);
                    result.put(key, obj.getInt(key));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse absences: " + e.getMessage());
        }

        return result;
    }

    /**
     * Save absence count for a specific subject.
     */
    public void saveAbsence(String subjectKey, int absenceCount) {
        Map<String, Integer> absences = loadSavedAbsences();
        absences.put(subjectKey, absenceCount);

        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Integer> entry : absences.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_ABSENCES, obj.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to save absences: " + e.getMessage());
        }
    }

    /**
     * Calculate overall attendance percentage across all subjects.
     */
    public double calculateOverallAttendance(List<Absence> absences) {
        int totalHours = 0;
        int totalAbsences = 0;

        for (Absence a : absences) {
            totalHours += a.totalHours;
            totalAbsences += a.absenceCount;
        }

        if (totalHours <= 0)
            return 100.0;
        int attended = Math.max(0, totalHours - totalAbsences);
        return (attended / (double) totalHours) * 100.0;
    }
}
