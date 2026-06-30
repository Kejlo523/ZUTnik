package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PlanNotificationDiffEngine {

    private static final DateTimeFormatter ISO_LOCAL_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Comparator<Event> BY_DATE_TIME = Comparator
            .comparing((Event e) -> e.date)
            .thenComparingInt(e -> e.startMin)
            .thenComparing(e -> e.title);

    private PlanNotificationDiffEngine() {
    }

    static Snapshot buildSnapshot(
            LocalDate windowStart,
            LocalDate windowEnd,
            Map<LocalDate, List<PlanRepository.PlanEventRaw>> byDate) {
        List<Event> events = new ArrayList<>();
        if (windowStart == null || windowEnd == null || windowEnd.isBefore(windowStart)) {
            return new Snapshot(windowStart, windowEnd, events);
        }

        LocalDate iter = windowStart;
        while (!iter.isAfter(windowEnd)) {
            List<PlanRepository.PlanEventRaw> dayEvents = byDate != null ? byDate.get(iter) : null;
            if (dayEvents != null) {
                for (PlanRepository.PlanEventRaw raw : dayEvents) {
                    Event event = Event.fromRaw(iter, raw);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
            iter = iter.plusDays(1);
        }

        events.sort(BY_DATE_TIME);
        return new Snapshot(windowStart, windowEnd, events);
    }

    static Diff diff(Snapshot previous, Snapshot current) {
        Diff diff = new Diff();
        if (previous == null || current == null) {
            return diff;
        }

        LocalDate compareStart = maxDate(previous.windowStart, current.windowStart);
        LocalDate compareEnd = minDate(previous.windowEnd, current.windowEnd);
        if (compareStart == null || compareEnd == null || compareEnd.isBefore(compareStart)) {
            return diff;
        }

        List<Event> previousEvents = previous.eventsInRange(compareStart, compareEnd);
        List<Event> currentEvents = current.eventsInRange(compareStart, compareEnd);
        boolean[] matchedPrevious = new boolean[previousEvents.size()];
        boolean[] matchedCurrent = new boolean[currentEvents.size()];

        for (int i = 0; i < previousEvents.size(); i++) {
            Event oldEvent = previousEvents.get(i);
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int j = 0; j < currentEvents.size(); j++) {
                if (matchedCurrent[j]) {
                    continue;
                }
                Event newEvent = currentEvents.get(j);
                if (!sameSlot(oldEvent, newEvent) || !sameSubjectIdentity(oldEvent, newEvent)) {
                    continue;
                }
                int score = sameSlotScore(oldEvent, newEvent);
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = j;
                }
            }

            if (bestIndex >= 0) {
                matchedPrevious[i] = true;
                matchedCurrent[bestIndex] = true;
                registerMatchedChange(diff, oldEvent, currentEvents.get(bestIndex));
            }
        }

        for (int i = 0; i < previousEvents.size(); i++) {
            if (matchedPrevious[i]) {
                continue;
            }
            Event oldEvent = previousEvents.get(i);
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int j = 0; j < currentEvents.size(); j++) {
                if (matchedCurrent[j]) {
                    continue;
                }
                Event newEvent = currentEvents.get(j);
                if (!sameMoveIdentity(oldEvent, newEvent)) {
                    continue;
                }
                int score = moveScore(oldEvent, newEvent);
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = j;
                }
            }

            if (bestIndex >= 0) {
                matchedPrevious[i] = true;
                matchedCurrent[bestIndex] = true;
                Event newEvent = currentEvents.get(bestIndex);
                if (isVisibleSlotChanged(oldEvent, newEvent)) {
                    diff.moved.add(new Move(oldEvent, newEvent));
                } else {
                    registerMatchedChange(diff, oldEvent, newEvent);
                }
            }
        }

        for (int i = 0; i < previousEvents.size(); i++) {
            if (!matchedPrevious[i]) {
                diff.removed.add(previousEvents.get(i));
            }
        }

        for (int i = 0; i < currentEvents.size(); i++) {
            if (matchedCurrent[i]) {
                continue;
            }
            Event event = currentEvents.get(i);
            if (event.cancelled) {
                diff.cancelled.add(event);
            } else {
                diff.added.add(event);
            }
        }

        diff.cancelled.sort(BY_DATE_TIME);
        diff.removed.sort(BY_DATE_TIME);
        diff.added.sort(BY_DATE_TIME);
        diff.moved.sort(Comparator
                .comparing((Move move) -> move.to.date)
                .thenComparingInt(move -> move.to.startMin)
                .thenComparing(move -> move.to.title));
        diff.updated.sort(Comparator
                .comparing((Update update) -> update.to.date)
                .thenComparingInt(update -> update.to.startMin)
                .thenComparing(update -> update.to.title));
        return diff;
    }

    private static void registerMatchedChange(Diff diff, Event oldEvent, Event newEvent) {
        if (!oldEvent.cancelled && newEvent.cancelled) {
            diff.cancelled.add(newEvent);
            return;
        }
        if (isMeaningfulDetailChange(oldEvent, newEvent)) {
            diff.updated.add(new Update(oldEvent, newEvent));
        }
    }

    private static boolean sameSlot(Event first, Event second) {
        if (first == null || second == null) {
            return false;
        }
        return first.date.equals(second.date)
                && first.startMin == second.startMin
                && first.endMin == second.endMin;
    }

    private static boolean sameSubjectIdentity(Event first, Event second) {
        if (first == null || second == null) {
            return false;
        }
        return first.titleKey.equals(second.titleKey)
                && first.durationMinutes() == second.durationMinutes();
    }

    private static boolean sameMoveIdentity(Event first, Event second) {
        if (!sameSubjectIdentity(first, second)) {
            return false;
        }
        if (first.typeKey.isEmpty() || second.typeKey.isEmpty()) {
            return true;
        }
        return first.typeKey.equals(second.typeKey);
    }

    private static boolean isMeaningfulDetailChange(Event first, Event second) {
        if (first == null || second == null) {
            return false;
        }
        return first.cancelled != second.cancelled
                || !first.roomKey.equals(second.roomKey)
                || !first.groupKey.equals(second.groupKey)
                || !first.teacherKey.equals(second.teacherKey)
                || !first.typeKey.equals(second.typeKey);
    }

    private static int sameSlotScore(Event first, Event second) {
        int score = 0;
        score += mismatchPenalty(first.typeKey, second.typeKey, 2);
        score += mismatchPenalty(first.groupKey, second.groupKey, 7);
        score += mismatchPenalty(first.roomKey, second.roomKey, 3);
        score += mismatchPenalty(first.teacherKey, second.teacherKey, 3);
        if (first.cancelled != second.cancelled) {
            score += 1;
        }
        return score;
    }

    private static int moveScore(Event first, Event second) {
        int dayDiff = Math.abs((int) (second.date.toEpochDay() - first.date.toEpochDay()));
        int timeDiff = Math.abs(second.startMin - first.startMin);
        int score = dayDiff * 4000 + timeDiff * 4;
        score += mismatchPenalty(first.typeKey, second.typeKey, 200);
        score += mismatchPenalty(first.groupKey, second.groupKey, 40);
        score += mismatchPenalty(first.roomKey, second.roomKey, 8);
        score += mismatchPenalty(first.teacherKey, second.teacherKey, 8);
        if (first.cancelled != second.cancelled) {
            score += 2;
        }
        return score;
    }

    private static int mismatchPenalty(String first, String second, int penalty) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return 0;
        }
        return first.equals(second) ? 0 : penalty;
    }

    private static boolean isVisibleSlotChanged(Event first, Event second) {
        if (first == null || second == null) {
            return false;
        }
        return !first.date.equals(second.date)
                || first.startMin != second.startMin
                || first.endMin != second.endMin;
    }

    private static LocalDate maxDate(LocalDate first, LocalDate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static LocalDate minDate(LocalDate first, LocalDate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }

    private static LocalDateTime parseIsoLocal(String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso.trim()).toLocalDateTime();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(iso.trim(), ISO_LOCAL_DT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildDisplayTitle(String subject, String formShort) {
        String cleanSubject = clean(subject);
        String cleanFormShort = clean(formShort);
        if (cleanSubject.isEmpty()) {
            return cleanFormShort;
        }
        if (cleanFormShort.isEmpty()) {
            return cleanSubject;
        }
        if (cleanSubject.endsWith("(" + cleanFormShort + ")")) {
            return cleanSubject;
        }
        return cleanSubject + " (" + cleanFormShort + ")";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String cleanValue = clean(value);
            if (!cleanValue.isEmpty()) {
                return cleanValue;
            }
        }
        return "";
    }

    private static String pickTeacher(String worker, String workerTitle) {
        String plain = clean(worker);
        String titled = clean(workerTitle);
        if (plain.isEmpty()) {
            return titled;
        }
        if (titled.isEmpty()) {
            return plain;
        }
        String plainKey = normalizeLoose(plain);
        String titledKey = normalizeLoose(titled);
        if (!plainKey.isEmpty() && titledKey.contains(plainKey)) {
            return plain;
        }
        if (!titledKey.isEmpty() && plainKey.contains(titledKey)) {
            return titled;
        }
        return plain.length() <= titled.length() ? plain : titled;
    }

    private static boolean isCancelled(PlanRepository.PlanEventRaw raw) {
        String statusShort = normalize(raw != null ? raw.lessonStatusShort : null);
        if ("o".equals(statusShort)) {
            return true;
        }
        String haystack = stripDiacritics(
                (clean(raw != null ? raw.lessonStatus : null) + " " + clean(raw != null ? raw.lessonForm : null))
                        .toLowerCase(Locale.ROOT));
        return haystack.contains("odwol")
                || haystack.contains("cancel")
                || haystack.contains("canceled");
    }

    private static String buildTypeKey(PlanRepository.PlanEventRaw raw) {
        String shortForm = normalize(raw != null ? raw.lessonFormShort : null);
        if (!shortForm.isEmpty()) {
            if ("o".equals(shortForm)) {
                return "";
            }
            return shortForm;
        }

        String form = stripDiacritics(clean(raw != null ? raw.lessonForm : null).toLowerCase(Locale.ROOT));
        if (form.isEmpty()) {
            return "";
        }
        if (form.contains("wyklad") || form.contains("lecture")) {
            return "w";
        }
        if (form.contains("labor") || form.contains(" lab")) {
            return "l";
        }
        if (form.contains("audyt") || form.contains("classroom")) {
            return "a";
        }
        if (form.contains("seminar")) {
            return "s";
        }
        if (form.contains("projekt") || form.contains("project")) {
            return "p";
        }
        if (form.contains("lektorat") || form.contains("lectorate")) {
            return "lek";
        }
        if (form.contains("egzamin") || form.contains("exam")) {
            return "exam";
        }
        if (form.contains("zaliczenie") || form.contains("credit") || form.contains("pass")) {
            return "zal";
        }
        if (form.contains("odwol") || form.contains("cancel") || form.contains("canceled")) {
            return "";
        }
        return normalize(form);
    }

    private static String buildTypeLabel(PlanRepository.PlanEventRaw raw) {
        String form = clean(raw != null ? raw.lessonForm : null);
        if (!form.isEmpty()) {
            return form;
        }
        return clean(raw != null ? raw.lessonFormShort : null);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalize(String value) {
        String cleanValue = clean(value);
        return cleanValue.isEmpty() ? "" : cleanValue.toLowerCase(Locale.ROOT);
    }

    private static String normalizeLoose(String value) {
        String normalized = stripDiacritics(normalize(value));
        return normalized.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String stripDiacritics(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized
                .replace('ł', 'l')
                .replace('Ł', 'l');
    }

    static final class Snapshot {
        final LocalDate windowStart;
        final LocalDate windowEnd;
        final List<Event> events;

        Snapshot(LocalDate windowStart, LocalDate windowEnd, List<Event> events) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
        }

        List<Event> eventsInRange(LocalDate start, LocalDate end) {
            List<Event> result = new ArrayList<>();
            if (start == null || end == null || end.isBefore(start)) {
                return result;
            }
            for (Event event : events) {
                if (event == null || event.date == null) {
                    continue;
                }
                if (event.date.isBefore(start) || event.date.isAfter(end)) {
                    continue;
                }
                result.add(event);
            }
            result.sort(BY_DATE_TIME);
            return result;
        }

        String toJsonString() {
            JSONObject root = new JSONObject();
            try {
                root.put("start", windowStart != null ? windowStart.toString() : "");
                root.put("end", windowEnd != null ? windowEnd.toString() : "");
                JSONArray arr = new JSONArray();
                for (Event event : events) {
                    if (event != null) {
                        arr.put(event.toJson());
                    }
                }
                root.put("events", arr);
            } catch (Exception ignored) {
            }
            return root.toString();
        }

        static Snapshot fromJsonString(String json) {
            if (json == null || json.trim().isEmpty()) {
                return new Snapshot(null, null, new ArrayList<>());
            }
            try {
                JSONObject root = new JSONObject(json);
                LocalDate start = parseDate(root.optString("start", ""));
                LocalDate end = parseDate(root.optString("end", ""));
                JSONArray arr = root.optJSONArray("events");
                List<Event> events = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        Event event = Event.fromJson(obj);
                        if (event != null) {
                            events.add(event);
                        }
                    }
                }
                events.sort(BY_DATE_TIME);
                return new Snapshot(start, end, events);
            } catch (Exception ignored) {
                return new Snapshot(null, null, new ArrayList<>());
            }
        }

        private static LocalDate parseDate(String value) {
            String cleanValue = clean(value);
            if (cleanValue.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(cleanValue);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static final class Event {
        final String title;
        final String titleKey;
        final String room;
        final String roomKey;
        final String group;
        final String groupKey;
        final String teacher;
        final String teacherKey;
        final String typeLabel;
        final String typeKey;
        final LocalDate date;
        final int startMin;
        final int endMin;
        final boolean cancelled;

        Event(
                String title,
                String titleKey,
                String room,
                String roomKey,
                String group,
                String groupKey,
                String teacher,
                String teacherKey,
                String typeLabel,
                String typeKey,
                LocalDate date,
                int startMin,
                int endMin,
                boolean cancelled) {
            this.title = title;
            this.titleKey = titleKey;
            this.room = room;
            this.roomKey = roomKey;
            this.group = group;
            this.groupKey = groupKey;
            this.teacher = teacher;
            this.teacherKey = teacherKey;
            this.typeLabel = typeLabel;
            this.typeKey = typeKey;
            this.date = date;
            this.startMin = startMin;
            this.endMin = endMin;
            this.cancelled = cancelled;
        }

        static Event fromRaw(LocalDate fallbackDate, PlanRepository.PlanEventRaw raw) {
            if (raw == null) {
                return null;
            }
            LocalDateTime start = parseIsoLocal(raw.start);
            LocalDateTime end = parseIsoLocal(raw.end);
            if (start == null || end == null) {
                return null;
            }

            String subject = firstNonEmpty(raw.subject, raw.title);
            String formShort = clean(raw.lessonFormShort);
            String title = buildDisplayTitle(subject, formShort);
            if (title.isEmpty()) {
                title = subject;
            }
            if (title.isEmpty()) {
                return null;
            }

            String room = clean(raw.room);
            String group = firstNonEmpty(raw.groupName, raw.tokName);
            String teacher = pickTeacher(raw.worker, raw.workerTitle);
            String typeLabel = buildTypeLabel(raw);
            String typeKey = buildTypeKey(raw);
            boolean cancelled = isCancelled(raw);

            LocalDate date = fallbackDate != null ? fallbackDate : start.toLocalDate();

            return new Event(
                    title,
                    normalizeLoose(subject.isEmpty() ? title : subject),
                    room,
                    normalizeLoose(room),
                    group,
                    normalizeLoose(group),
                    teacher,
                    normalizeLoose(teacher),
                    typeLabel,
                    normalize(typeKey),
                    date,
                    start.getHour() * 60 + start.getMinute(),
                    end.getHour() * 60 + end.getMinute(),
                    cancelled);
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("t", title);
                obj.put("k", titleKey);
                obj.put("r", room);
                obj.put("rk", roomKey);
                obj.put("g", group);
                obj.put("gk", groupKey);
                obj.put("w", teacher);
                obj.put("wk", teacherKey);
                obj.put("l", typeLabel);
                obj.put("lk", typeKey);
                obj.put("d", date != null ? date.toString() : "");
                obj.put("s", startMin);
                obj.put("e", endMin);
                obj.put("x", cancelled);
            } catch (Exception ignored) {
            }
            return obj;
        }

        static Event fromJson(JSONObject obj) {
            if (obj == null) {
                return null;
            }
            try {
                LocalDate date = LocalDate.parse(obj.optString("d", ""));
                return new Event(
                        obj.optString("t", ""),
                        obj.optString("k", ""),
                        obj.optString("r", ""),
                        obj.optString("rk", ""),
                        obj.optString("g", ""),
                        obj.optString("gk", ""),
                        obj.optString("w", ""),
                        obj.optString("wk", ""),
                        obj.optString("l", ""),
                        obj.optString("lk", ""),
                        date,
                        obj.optInt("s", 0),
                        obj.optInt("e", 0),
                        obj.optBoolean("x", false));
            } catch (Exception ignored) {
                return null;
            }
        }

        int durationMinutes() {
            return Math.max(0, endMin - startMin);
        }

        String signature() {
            return titleKey
                    + "|" + date
                    + "|" + startMin
                    + "|" + endMin
                    + "|" + roomKey
                    + "|" + groupKey
                    + "|" + teacherKey
                    + "|" + typeKey
                    + "|" + cancelled;
        }
    }

    static final class Diff {
        final List<Event> cancelled = new ArrayList<>();
        final List<Event> removed = new ArrayList<>();
        final List<Event> added = new ArrayList<>();
        final List<Move> moved = new ArrayList<>();
        final List<Update> updated = new ArrayList<>();
    }

    static final class Move {
        final Event from;
        final Event to;

        Move(Event from, Event to) {
            this.from = from;
            this.to = to;
        }
    }

    static final class Update {
        final Event from;
        final Event to;

        Update(Event from, Event to) {
            this.from = from;
            this.to = to;
        }
    }
}