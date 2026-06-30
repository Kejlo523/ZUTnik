package pl.kejlo.zutnik;

/**
 * Model representing an absence record for a subject.
 */
public class Absence {
    public String subjectName; // e.g., "Programowanie obiektowe"
    public String subjectType; // e.g., "Laboratorium", "Wykład"
    public String subjectKey; // Unique key: "subject||type"
    public int absenceCount; // Number of missed lessons (in hours)
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
        int attended = Math.max(0, totalHours - absenceCount);
        return (attended / (double) totalHours) * 100.0;
    }
}
