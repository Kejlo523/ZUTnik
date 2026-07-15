package pl.kejlo.zutnik;

/**
 * Model representing an absence record for a subject.
 */
public class Absence {
    public static final int DEFAULT_REQUIRED_ATTENDANCE_PERCENT = 80;
    public static final int HOURS_PER_ABSENCE = 2;
    public String subjectName; // e.g., "Programowanie obiektowe"
    public String subjectType; // e.g., "Laboratorium", "Wykład"
    public String subjectKey; // Unique key: "subject||type"
    public int absenceCount; // Number of missed class meetings
    public int totalHours; // Total scheduled hours for this subject

    public Absence() {
    }

    public Absence(String subjectKey, String subjectName, String subjectType, int totalHours) {
        this.subjectKey = subjectKey;
        this.subjectName = subjectName;
        this.subjectType = subjectType;
        this.totalHours = totalHours;
        this.absenceCount = 0;
    }

    /**
     * Calculate attendance percentage.
     * 
     * @return Percentage (0-100) or 100 if no hours scheduled.
     */
    public double getAttendancePercent() {
        if (totalHours <= 0)
            return 100.0;
        int attended = Math.max(0, totalHours - getMissedHours());
        return (attended / (double) totalHours) * 100.0;
    }

    public int getMissedHours() {
        if (totalHours <= 0 || absenceCount <= 0) {
            return 0;
        }
        return Math.min(totalHours, absenceCount * HOURS_PER_ABSENCE);
    }

    public int getAllowedAbsenceCount() {
        if (totalHours <= 0) {
            return 0;
        }
        return Math.max(0,
                totalHours * (100 - DEFAULT_REQUIRED_ATTENDANCE_PERCENT)
                        / (100 * HOURS_PER_ABSENCE));
    }

    public int getRemainingSafeAbsenceCount() {
        return Math.max(0, getAllowedAbsenceCount() - absenceCount);
    }

    public int getMaximumAbsenceCount() {
        if (totalHours <= 0) {
            return 0;
        }
        return (totalHours + HOURS_PER_ABSENCE - 1) / HOURS_PER_ABSENCE;
    }

    public boolean isBelowRequiredAttendance() {
        return totalHours > 0 && getAttendancePercent() < DEFAULT_REQUIRED_ATTENDANCE_PERCENT;
    }
}
