package pl.kejlo.zutnik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AttendanceAssistantTest {

    @Test
    public void calculatesSafeAbsenceBudgetAtEightyPercent() {
        Absence absence = new Absence("math-lab", "Matematyka", "Laboratorium", 30);
        absence.absenceCount = 2;

        assertEquals(3, absence.getAllowedAbsenceCount());
        assertEquals(1, absence.getRemainingSafeAbsenceCount());
        assertEquals(4, absence.getMissedHours());
        assertEquals(86.666, absence.getAttendancePercent(), 0.001);
        assertFalse(absence.isBelowRequiredAttendance());
    }

    @Test
    public void reportsThresholdBeforeNextAbsence() {
        Absence absence = new Absence("net-lab", "Sieci", "Laboratorium", 30);
        absence.absenceCount = 3;

        assertEquals(80.0, absence.getAttendancePercent(), 0.001);
        assertEquals(0, absence.getRemainingSafeAbsenceCount());
        assertFalse(absence.isBelowRequiredAttendance());
    }

    @Test
    public void detectsAttendanceBelowThreshold() {
        Absence absence = new Absence("db-lab", "Bazy danych", "Laboratorium", 30);
        absence.absenceCount = 4;

        assertTrue(absence.isBelowRequiredAttendance());
        assertEquals(0, absence.getRemainingSafeAbsenceCount());
    }

    @Test
    public void handlesMissingHoursWithoutFalseWarning() {
        Absence absence = new Absence("unknown", "Przedmiot", "Ćwiczenia", 0);
        absence.absenceCount = 1;

        assertEquals(100.0, absence.getAttendancePercent(), 0.001);
        assertFalse(absence.isBelowRequiredAttendance());
    }
}
