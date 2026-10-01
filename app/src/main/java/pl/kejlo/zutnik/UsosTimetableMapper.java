package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import pl.kejlo.zutnik.PlanRepository.PlanEventRaw;

final class UsosTimetableMapper {
    private static final DateTimeFormatter USOS_DATE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LOCAL_ISO_DATE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private UsosTimetableMapper() {}

    static List<PlanEventRaw> parseActivities(JSONArray activities, JSONObject groupOrNull) {
        List<PlanEventRaw> events = new ArrayList<>();
        if (activities == null) {
            return events;
        }
        Map<List<String>, Integer> classMeetings = new HashMap<>();
        for (int i = 0; i < activities.length(); i++) {
            JSONObject activity = activities.optJSONObject(i);
            if (activity == null) {
                continue;
            }
            String type = text(activity, "type");
            boolean classGroup = "classgroup".equals(type) || "classgroup2".equals(type);
            String frequency = text(activity, "frequency").toLowerCase(Locale.ROOT);
            // These generated dates are possibilities, not scheduled meetings.
            if ("classgroup".equals(type)
                    && ("other".equals(frequency) || "once".equals(frequency))) {
                continue;
            }
            LocalDateTime start = dateTime(text(activity, "start_time"));
            LocalDateTime end = dateTime(text(activity, "end_time"));
            if (start == null || end == null || !end.isAfter(start)) {
                continue;
            }

            JSONObject group = classGroup ? matchingGroup(activity, groupOrNull) : null;
            String unitId = first(text(activity, "unit_id"), text(group, "course_unit_id"));
            String groupNumber = first(text(activity, "group_number"), text(group, "group_number"));
            String courseId = first(text(activity, "course_id"), text(group, "course_id"));
            String name = languageText(activity, "name");
            String courseName = first(languageText(activity, "course_name"),
                    languageText(group, "course_name"));
            String classTypeName = classGroup ? first(languageText(activity, "classtype_name"),
                    languageText(group, "class_type")) : "";
            String classTypeId = classGroup ? first(text(activity, "classtype_id"),
                    text(group, "class_type_id")) : "";
            String building = languageText(activity, "building_name");
            String roomNumber = text(activity, "room_number");

            PlanEventRaw event = new PlanEventRaw();
            event.title = classGroup || "exam".equals(type) ? first(courseName, name) : name;
            event.subject = event.title;
            event.description = name;
            event.start = start.format(LOCAL_ISO_DATE_TIME);
            event.end = end.format(LOCAL_ISO_DATE_TIME);
            event.workerTitle = "";
            event.worker = lecturers(group);
            event.lessonForm = first(classTypeName, classTypeId);
            event.lessonFormShort = classGroup ? shortForm(classTypeName, classTypeId) : "";
            event.groupName = groupNumber;
            event.tokName = "";
            event.room = join(building, roomNumber, ", ");
            event.lessonStatus = "exam".equals(type) ? "Egzamin" : "";
            event.lessonStatusShort = "exam".equals(type) ? "E" : "";
            event.hours = "";
            event.color = "";
            event.borderColor = "";
            event.sourceUnitId = unitId;
            event.sourceGroupNumber = groupNumber;
            event.sourceType = type;

            if (classGroup && !unitId.isEmpty() && !groupNumber.isEmpty()) {
                List<String> key = Arrays.asList(unitId, groupNumber, event.start, event.end);
                // Both classgroup variants identify the same meeting, regardless of confirmation.
                event.sourceId = identity("classgroup", key);
                Integer previousIndex = classMeetings.get(key);
                if (previousIndex == null) {
                    classMeetings.put(key, events.size());
                    events.add(event);
                } else if ("classgroup2".equals(type)
                        && !"classgroup2".equals(events.get(previousIndex).sourceType)) {
                    events.set(previousIndex, event);
                }
            } else {
                // Never infer an exam or an unknown activity's identity from the supplied group.
                event.sourceId = identity(type, Arrays.asList(courseId, unitId, groupNumber,
                        event.start, event.end, name, courseName, classTypeId, building, roomNumber));
                events.add(event);
            }
        }
        return events;
    }

    private static JSONObject matchingGroup(JSONObject activity, JSONObject group) {
        if (group == null) {
            return null;
        }
        String unitId = text(activity, "unit_id");
        String groupUnitId = text(group, "course_unit_id");
        String groupNumber = text(activity, "group_number");
        String metadataGroupNumber = text(group, "group_number");
        String courseId = text(activity, "course_id");
        String metadataCourseId = text(group, "course_id");
        if (conflicts(unitId, groupUnitId) || conflicts(groupNumber, metadataGroupNumber)
                || conflicts(courseId, metadataCourseId)) {
            return null;
        }
        return group;
    }

    private static boolean conflicts(String value, String metadata) {
        return !value.isEmpty() && !metadata.isEmpty() && !value.equals(metadata);
    }

    private static LocalDateTime dateTime(String value) {
        if (value.length() != 19) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, USOS_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String languageText(JSONObject object, String field) {
        Object value = object == null ? null : object.opt(field);
        if (value instanceof JSONObject) {
            JSONObject languages = (JSONObject) value;
            return first(text(languages, "pl"), text(languages, "en"));
        }
        return scalar(value);
    }

    private static String text(JSONObject object, String field) {
        return scalar(object == null ? null : object.opt(field));
    }

    private static String scalar(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            return "";
        }
        String text = value.toString().trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static String first(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static String join(String first, String second, String separator) {
        if (first.isEmpty()) {
            return second;
        }
        return second.isEmpty() ? first : first + separator + second;
    }

    private static String lecturers(JSONObject group) {
        JSONArray lecturers = group == null ? null : group.optJSONArray("lecturers");
        Set<String> names = new LinkedHashSet<>();
        if (lecturers != null) {
            for (int i = 0; i < lecturers.length(); i++) {
                JSONObject lecturer = lecturers.optJSONObject(i);
                String name = join(text(lecturer, "first_name"), text(lecturer, "last_name"), " ");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return String.join(", ", names);
    }

    private static String shortForm(String name, String id) {
        String fromName = shortForm(name);
        return fromName.isEmpty() ? shortForm(id) : fromName;
    }

    private static String shortForm(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('\u0142', 'l');
        if (normalized.contains("wychowanie fizyczne") || "wf".equals(normalized)) {
            return "WF";
        }
        if (normalized.contains("lektorat") || normalized.contains("lectorate")
                || normalized.contains("language") || "lek".equals(normalized)
                || "le".equals(normalized)) {
            return "Lek";
        }
        if (normalized.contains("laborator") || "l".equals(normalized)
                || "lab".equals(normalized) || "lb".equals(normalized)) {
            return "L";
        }
        if (normalized.contains("wyklad") || normalized.contains("lecture")
                || "w".equals(normalized) || "wyk".equals(normalized) || "wk".equals(normalized)) {
            return "W";
        }
        if (normalized.contains("projekt") || normalized.contains("project")
                || "p".equals(normalized) || "proj".equals(normalized)) {
            return "P";
        }
        if (normalized.contains("semin") || "s".equals(normalized)) {
            return "S";
        }
        if (normalized.contains("audytor") || normalized.contains("auditor")
                || normalized.contains("cwiczen") || normalized.contains("tutorial")
                || normalized.contains("exercise") || normalized.contains("classroom")
                || "a".equals(normalized) || "cw".equals(normalized) || "cwa".equals(normalized)) {
            return "A";
        }
        return "";
    }

    private static String identity(String type, List<String> parts) {
        StringBuilder identity = new StringBuilder("usos:");
        // Length prefixes keep arbitrary names and delimiters from creating identity collisions.
        identity.append(type.length()).append(':').append(type);
        for (String part : parts) {
            identity.append(':').append(part.length()).append(':').append(part);
        }
        return identity.toString();
    }
}
