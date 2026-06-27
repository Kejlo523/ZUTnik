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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for managing attendance data.
 * Persists absence records and user-defined hours in SharedPreferences.
 */
public class AttendanceRepository {
    private static final String TAG = "mZUTv2-ATTENDANCE";
    private static final String PREFS_NAME = "attendance_prefs";
    private static final String KEY_ABSENCES = "absences_json";
    private static final String KEY_HOURS = "hours_json";
    private static final String KEY_SUBJECTS_CACHE = "subjects_cache_json";
    private static final String KEY_SUBJECTS_CACHE_TS = "subjects_cache_ts";
    private static final long SUBJECTS_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    private final Context context;
    private final PlanRepository planRepository;
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor();

    private Map<String, Integer> hoursCache;
    private Map<String, Integer> absencesCache;
    private boolean prefsLoaded;

    public AttendanceRepository(Context context) {
        this.context = context.getApplicationContext();
        this.planRepository = new PlanRepository(context);
    }

    /**
     * Load all subjects with their user-defined hours and any saved absences.
     */
    public List<Absence> loadSubjectsWithAbsences() {
        return loadSubjectsWithAbsences(false);
    }

    public List<Absence> loadSubjectsWithAbsences(boolean forceRefresh) {
        if (!forceRefresh) {
            List<Absence> cached = loadCachedSubjects();
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        List<PlanRepository.SubjectFilterItem> subjects;
        try {
            subjects = planRepository.loadSubjectsForFilter(forceRefresh);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load subjects: " + e.getMessage());
            subjects = new ArrayList<>();
        }

        List<Absence> result = buildAbsenceList(subjects);
        if (!result.isEmpty()) {
            saveSubjectsCache(subjects);
        }
        return result;
    }

    private List<Absence> buildAbsenceList(List<PlanRepository.SubjectFilterItem> subjects) {
        ensurePrefsLoaded();
        List<Absence> result = new ArrayList<>();
        for (PlanRepository.SubjectFilterItem item : subjects) {
            Absence absence = new Absence();
            absence.subjectKey = item.filterKey;
            absence.subjectName = item.label;
            absence.subjectType = item.typeLabel;
            absence.totalHours = hoursCache.getOrDefault(item.filterKey, 0);
            absence.absenceCount = absencesCache.getOrDefault(item.filterKey, 0);
            result.add(absence);
        }
        return result;
    }

    private List<Absence> loadCachedSubjects() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long cacheTs = prefs.getLong(KEY_SUBJECTS_CACHE_TS, 0L);
        if (cacheTs <= 0L || System.currentTimeMillis() - cacheTs > SUBJECTS_CACHE_TTL_MS) {
            return null;
        }

        String json = prefs.getString(KEY_SUBJECTS_CACHE, null);
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            JSONArray arr = new JSONArray(json);
            List<PlanRepository.SubjectFilterItem> subjects = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                PlanRepository.SubjectFilterItem item = new PlanRepository.SubjectFilterItem();
                item.filterKey = obj.optString("key", "");
                item.label = obj.optString("name", "");
                item.typeLabel = obj.optString("type", "");
                if (!item.filterKey.isEmpty()) {
                    subjects.add(item);
                }
            }
            if (subjects.isEmpty()) {
                return null;
            }
            return buildAbsenceList(subjects);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse subjects cache: " + e.getMessage());
            return null;
        }
    }

    private void saveSubjectsCache(List<PlanRepository.SubjectFilterItem> subjects) {
        try {
            JSONArray arr = new JSONArray();
            for (PlanRepository.SubjectFilterItem item : subjects) {
                JSONObject obj = new JSONObject();
                obj.put("key", item.filterKey);
                obj.put("name", item.label);
                obj.put("type", item.typeLabel);
                arr.put(obj);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SUBJECTS_CACHE, arr.toString())
                    .putLong(KEY_SUBJECTS_CACHE_TS, System.currentTimeMillis())
                    .apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to save subjects cache: " + e.getMessage());
        }
    }

    private void ensurePrefsLoaded() {
        if (prefsLoaded) {
            return;
        }
        hoursCache = loadSavedHoursFromDisk();
        absencesCache = loadSavedAbsencesFromDisk();
        prefsLoaded = true;
    }

    private Map<String, Integer> loadSavedHoursFromDisk() {
        Map<String, Integer> result = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HOURS, null);
        if (json == null) {
            return result;
        }
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

    public void saveHours(String subjectKey, int hours) {
        ensurePrefsLoaded();
        hoursCache.put(subjectKey, hours);
        persistHoursAsync();
    }

    private void persistHoursAsync() {
        Map<String, Integer> snapshot = new HashMap<>(hoursCache);
        persistExecutor.execute(() -> writeHoursToDisk(snapshot));
    }

    private void writeHoursToDisk(Map<String, Integer> hoursMap) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Integer> entry : hoursMap.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HOURS, obj.toString())
                    .apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to save hours: " + e.getMessage());
        }
    }

    private Map<String, Integer> loadSavedAbsencesFromDisk() {
        Map<String, Integer> result = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ABSENCES, null);
        if (json == null) {
            return result;
        }
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

    public void saveAbsence(String subjectKey, int absenceCount) {
        ensurePrefsLoaded();
        absencesCache.put(subjectKey, absenceCount);
        persistAbsencesAsync();
    }

    private void persistAbsencesAsync() {
        Map<String, Integer> snapshot = new HashMap<>(absencesCache);
        persistExecutor.execute(() -> writeAbsencesToDisk(snapshot));
    }

    private void writeAbsencesToDisk(Map<String, Integer> absencesMap) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Integer> entry : absencesMap.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ABSENCES, obj.toString())
                    .apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to save absences: " + e.getMessage());
        }
    }

    public double calculateOverallAttendance(List<Absence> absences) {
        int totalHours = 0;
        int totalAbsences = 0;

        for (Absence a : absences) {
            totalHours += a.totalHours;
            totalAbsences += a.absenceCount;
        }

        if (totalHours <= 0) {
            return 100.0;
        }
        int attended = Math.max(0, totalHours - totalAbsences);
        return (attended / (double) totalHours) * 100.0;
    }
}
