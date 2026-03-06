package pl.kejlo.mzutv2;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PlanNotificationDiffEngineTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    public void diffIgnoresPureWindowShift() {
        LocalDate firstStart = LocalDate.of(2026, 3, 6);
        LocalDate firstEnd = firstStart.plusDays(13);
        LocalDate secondStart = firstStart.plusDays(1);
        LocalDate secondEnd = firstEnd.plusDays(1);

        PlanNotificationDiffEngine.Snapshot previous = PlanNotificationDiffEngine.buildSnapshot(
                firstStart,
                firstEnd,
                mapOf(
                        event(firstStart, 8, 0, 9, 30, "Matematyka", "W", "Wykład", "A-1", "1", "Jan Kowalski", "", "", ""),
                        event(firstEnd, 10, 0, 11, 30, "Fizyka", "L", "Laboratorium", "B-2", "2", "Anna Nowak", "", "", "")));

        PlanNotificationDiffEngine.Snapshot current = PlanNotificationDiffEngine.buildSnapshot(
                secondStart,
                secondEnd,
                mapOf(
                        event(firstEnd, 10, 0, 11, 30, "Fizyka", "L", "Laboratorium", "B-2", "2", "Anna Nowak", "", "", ""),
                        event(secondEnd, 12, 0, 13, 30, "Chemia", "A", "Audytoryjne", "C-3", "3", "Piotr Zielinski", "", "", "")));

        PlanNotificationDiffEngine.Diff diff = PlanNotificationDiffEngine.diff(previous, current);

        assertEquals(0, diff.moved.size());
        assertEquals(0, diff.updated.size());
        assertEquals(0, diff.cancelled.size());
        assertEquals(0, diff.removed.size());
        assertEquals(0, diff.added.size());
    }

    @Test
    public void diffDetectsRoomChangeAsUpdate() {
        LocalDate day = LocalDate.of(2026, 3, 10);
        PlanNotificationDiffEngine.Snapshot previous = snapshot(day,
                event(day, 8, 0, 9, 30, "Algorytmy", "L", "Laboratorium", "A-12", "1", "Jan Kowalski", "", "", ""));
        PlanNotificationDiffEngine.Snapshot current = snapshot(day,
                event(day, 8, 0, 9, 30, "Algorytmy", "L", "Laboratorium", "B-15", "1", "Jan Kowalski", "", "", ""));

        PlanNotificationDiffEngine.Diff diff = PlanNotificationDiffEngine.diff(previous, current);

        assertEquals(1, diff.updated.size());
        assertEquals(0, diff.moved.size());
        assertEquals(0, diff.added.size());
        assertEquals(0, diff.removed.size());
    }

    @Test
    public void diffDetectsMoveWithoutAddRemoveNoise() {
        LocalDate day = LocalDate.of(2026, 3, 11);
        PlanNotificationDiffEngine.Snapshot previous = snapshot(day,
                event(day, 8, 0, 9, 30, "Bazy Danych", "W", "Wykład", "A-1", "1", "Jan Kowalski", "", "", ""));
        PlanNotificationDiffEngine.Snapshot current = snapshot(day,
                event(day, 10, 0, 11, 30, "Bazy Danych", "W", "Wykład", "A-1", "1", "Jan Kowalski", "", "", ""));

        PlanNotificationDiffEngine.Diff diff = PlanNotificationDiffEngine.diff(previous, current);

        assertEquals(1, diff.moved.size());
        assertEquals(0, diff.added.size());
        assertEquals(0, diff.removed.size());
        assertEquals(0, diff.updated.size());
    }

    @Test
    public void diffDetectsCancellationOnSameSlot() {
        LocalDate day = LocalDate.of(2026, 3, 12);
        PlanNotificationDiffEngine.Snapshot previous = snapshot(day,
                event(day, 14, 0, 15, 30, "Sieci", "L", "Laboratorium", "C-2", "2", "Anna Nowak", "", "", ""));
        PlanNotificationDiffEngine.Snapshot current = snapshot(day,
                event(day, 14, 0, 15, 30, "Sieci", "L", "Laboratorium", "C-2", "2", "Anna Nowak", "", "Odwołane", "o"));

        PlanNotificationDiffEngine.Diff diff = PlanNotificationDiffEngine.diff(previous, current);

        assertEquals(1, diff.cancelled.size());
        assertEquals(0, diff.updated.size());
        assertEquals(0, diff.added.size());
        assertEquals(0, diff.removed.size());
    }

    private PlanNotificationDiffEngine.Snapshot snapshot(LocalDate day, PlanRepository.PlanEventRaw event) {
        return PlanNotificationDiffEngine.buildSnapshot(day, day, mapOf(event));
    }

    private Map<LocalDate, List<PlanRepository.PlanEventRaw>> mapOf(PlanRepository.PlanEventRaw... events) {
        Map<LocalDate, List<PlanRepository.PlanEventRaw>> map = new HashMap<>();
        if (events == null) {
            return map;
        }
        for (PlanRepository.PlanEventRaw event : events) {
            if (event == null || event.start == null || event.start.length() < 10) {
                continue;
            }
            LocalDate date = LocalDate.parse(event.start.substring(0, 10));
            map.computeIfAbsent(date, key -> new ArrayList<>()).add(event);
        }
        return map;
    }

    private PlanRepository.PlanEventRaw event(
            LocalDate date,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            String subject,
            String formShort,
            String form,
            String room,
            String group,
            String worker,
            String workerTitle,
            String lessonStatus,
            String lessonStatusShort) {
        PlanRepository.PlanEventRaw raw = new PlanRepository.PlanEventRaw();
        raw.start = LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), startHour, startMinute)
                .format(ISO);
        raw.end = LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), endHour, endMinute)
                .format(ISO);
        raw.subject = subject;
        raw.title = subject;
        raw.lessonFormShort = formShort;
        raw.lessonForm = form;
        raw.room = room;
        raw.groupName = group;
        raw.worker = worker;
        raw.workerTitle = workerTitle;
        raw.lessonStatus = lessonStatus;
        raw.lessonStatusShort = lessonStatusShort;
        return raw;
    }
}