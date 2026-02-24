package pl.kejlo.mzutv2;

/**
 * Grade information retrieved from USOS API.
 * 
 * Maps to the structure returned by services/grades/grade,
 * services/grades/exam, and services/grades/terms2 methods.
 */
public class Grade {
    // Primary fields (from USOS API)
    public String subjectName; // Course name (from course_name or equivalent)
    public String courseId; // Course ID from USOS
    public String grade; // Symbolic representation (e.g., "5", "ZAL")
    public double weight; // ECTS credits (from course edition)
    public String gradeType; // e.g., "ECTS", "PF" (pass/fail)
    public String gradeDescription; // Localized grade name
    public boolean passes; // True if grade counts as passing
    public String type; // Type of examination (e.g., exam, credit unit)
    public String teacher; // Teacher/lecturer name
    public String date; // Date of acquisition or modification
    public String comment; // Comment visible for student
    public boolean countsIntoAverage; // Whether grade affects average
    
    // Additional USOS API fields
    public String examId; // ID of the exam
    public int examSessionNumber; // Session number
    public String dateModified; // Last modification time
    public String dateAcquisition; // Time grade was acquired
    public String modificationAuthor; // User ID who last edited grade
    public String decimalValue; // Numeric value of grade
    public int gradeTypeId; // ID of grade type system
    
    // Utility field
    public java.util.List<String> gradeHistory = new java.util.ArrayList<>(); // History of grades

    @Override
    public String toString() {
        String desc = grade;
        if (gradeDescription != null && !gradeDescription.isEmpty()) {
            desc += " (" + gradeDescription + ")";
        }
        return subjectName + " - " + desc;
    }
}
