package pl.kejlo.zutnik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GradesNotificationTrackerTest {

    @Test
    public void unchangedVisibleGradeIsNotReportedAgain() {
        Grade grade = grade("MAT-1", "Matematyka", "5", "2026-07-10 12:30:00");
        Map<String, String> visibleSnapshot = GradesNotificationTracker.buildSnapshot(
                Collections.singletonList(grade));
        Map<String, String> workerSnapshot = GradesNotificationTracker.buildSnapshot(
                Collections.singletonList(grade(" MAT-1 ", " Matematyka ", "5", "2026-07-10")));

        List<String> added = GradesNotificationTracker.findAddedKeys(
                visibleSnapshot.keySet(),
                workerSnapshot);

        assertTrue(added.isEmpty());
    }

    @Test
    public void genuinelyNewGradeIsReported() {
        Grade oldGrade = grade("MAT-1", "Matematyka", "4", "2026-07-09");
        Grade newGrade = grade("PRG-1", "Programowanie", "5", "2026-07-10");
        Map<String, String> previous = GradesNotificationTracker.buildSnapshot(
                Collections.singletonList(oldGrade));
        Map<String, String> current = GradesNotificationTracker.buildSnapshot(
                java.util.Arrays.asList(oldGrade, newGrade));

        List<String> added = GradesNotificationTracker.findAddedKeys(previous.keySet(), current);

        assertEquals(1, added.size());
        assertEquals(GradesNotificationTracker.buildKey(newGrade), added.get(0));
    }

    @Test
    public void correctedGradeHasItsOwnIdentity() {
        Grade original = grade("STAT-1", "Statystyka", "2", "2026-06-26");
        Grade corrected = grade("STAT-1", "Statystyka", "3", "2026-07-07");

        assertNotEquals(
                GradesNotificationTracker.buildKey(original),
                GradesNotificationTracker.buildKey(corrected));
    }

    private Grade grade(String courseId, String subject, String value, String date) {
        Grade grade = new Grade();
        grade.courseId = courseId;
        grade.subjectName = subject;
        grade.grade = value;
        grade.type = "Wykład";
        grade.dateAcquisition = date;
        grade.date = date;
        return grade;
    }
}
