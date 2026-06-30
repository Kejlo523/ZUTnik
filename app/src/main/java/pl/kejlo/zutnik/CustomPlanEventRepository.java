package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Repository for managing custom plan events (exams, tests, etc.)
 * Persists data in SharedPreferences as JSON.
 */
public class CustomPlanEventRepository {

    private static final String PREFS_NAME = "zutnik_custom_events";
    private static final String KEY_EVENTS = "custom_events_json";

    private final Context context;

    public CustomPlanEventRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    // ========== CRUD Operations ==========

    /**
     * Load all custom events
     */
    public List<CustomPlanEvent> loadAll() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_EVENTS, null);

        List<CustomPlanEvent> list = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    CustomPlanEvent event = CustomPlanEvent.fromJson(obj);
                    if (event != null) {
                        list.add(event);
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Sort by date, then time
        Collections.sort(list, (a, b) -> {
            if (a.date == null && b.date == null)
                return 0;
            if (a.date == null)
                return 1;
            if (b.date == null)
                return -1;
            int dateComp = a.date.compareTo(b.date);
            if (dateComp != 0)
                return dateComp;
            if (a.startTime == null && b.startTime == null)
                return 0;
            if (a.startTime == null)
                return 1;
            if (b.startTime == null)
                return -1;
            return a.startTime.compareTo(b.startTime);
        });

        return list;
    }

    /**
     * Save all events (replaces existing)
     */
    public void saveAll(List<CustomPlanEvent> events) {
        JSONArray arr = new JSONArray();
        for (CustomPlanEvent e : events) {
            try {
                arr.put(e.toJson());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EVENTS, arr.toString())
                .apply();
    }

    /**
     * Add a new event
     */
    public void addEvent(CustomPlanEvent event) {
        List<CustomPlanEvent> events = loadAll();
        events.add(event);
        saveAll(events);
    }

    /**
     * Update an existing event
     */
    public void updateEvent(CustomPlanEvent event) {
        List<CustomPlanEvent> events = loadAll();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id == event.id) {
                events.set(i, event);
                break;
            }
        }
        saveAll(events);
    }

    /**
     * Delete an event by ID
     */
    public void deleteEvent(long eventId) {
        List<CustomPlanEvent> events = loadAll();
        events.removeIf(e -> e.id == eventId);
        saveAll(events);
    }

    // ========== Query Methods ==========

    /**
     * Get events for a specific date
     */
    public List<CustomPlanEvent> getEventsForDate(LocalDate date) {
        List<CustomPlanEvent> all = loadAll();
        List<CustomPlanEvent> result = new ArrayList<>();
        for (CustomPlanEvent e : all) {
            if (e.date != null && e.date.equals(date)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Get events for a date range (inclusive)
     */
    public List<CustomPlanEvent> getEventsForDateRange(LocalDate start, LocalDate end) {
        List<CustomPlanEvent> all = loadAll();
        List<CustomPlanEvent> result = new ArrayList<>();
        for (CustomPlanEvent e : all) {
            if (e.date != null && !e.date.isBefore(start) && !e.date.isAfter(end)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Get events for a specific subject
     */
    public List<CustomPlanEvent> getEventsForSubject(String subjectName) {
        List<CustomPlanEvent> all = loadAll();
        List<CustomPlanEvent> result = new ArrayList<>();
        String lower = subjectName != null ? subjectName.toLowerCase(Locale.ROOT).trim() : "";
        for (CustomPlanEvent e : all) {
            if (e.subjectName != null && e.subjectName.toLowerCase(Locale.ROOT).trim().equals(lower)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Check if there's a custom event for subject on date
     */
    public CustomPlanEvent findEventForSubjectOnDate(String subjectName, LocalDate date) {
        String lower = subjectName != null ? subjectName.toLowerCase(Locale.ROOT).trim() : "";
        for (CustomPlanEvent e : getEventsForDate(date)) {
            if (e.subjectName != null && e.subjectName.toLowerCase(Locale.ROOT).trim().equals(lower)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Get upcoming events (from today onwards)
     */
    public List<CustomPlanEvent> getUpcomingEvents(int limit) {
        LocalDate today = LocalDate.now();
        List<CustomPlanEvent> all = loadAll();
        List<CustomPlanEvent> upcoming = new ArrayList<>();

        for (CustomPlanEvent e : all) {
            if (e.date != null && !e.date.isBefore(today)) {
                upcoming.add(e);
            }
        }

        // Sort by date
        Collections.sort(upcoming, Comparator.comparing(e -> e.date));

        if (limit > 0 && upcoming.size() > limit) {
            return upcoming.subList(0, limit);
        }
        return upcoming;
    }

    // ========== Helper Methods ==========

    /**
     * Get unique subject names from all saved events
     */
    public List<String> getSavedSubjectNames() {
        Set<String> subjects = new HashSet<>();
        for (CustomPlanEvent e : loadAll()) {
            if (e.subjectName != null && !e.subjectName.trim().isEmpty()) {
                subjects.add(e.subjectName.trim());
            }
        }
        List<String> list = new ArrayList<>(subjects);
        Collections.sort(list);
        return list;
    }

    /**
     * Get event by ID
     */
    public CustomPlanEvent getEventById(long id) {
        for (CustomPlanEvent e : loadAll()) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    /**
     * Count total events
     */
    public int getEventCount() {
        return loadAll().size();
    }

    /**
     * Clear all events
     */
    public void clearAll() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_EVENTS)
                .apply();
    }
}
