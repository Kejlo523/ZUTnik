package pl.kejlo.mzutv2;

public class Grade {
    public String subjectName; // Subject name
    public String grade; // e.g., "5.0"
    public double weight; // Weight (ECTS)
    public String type; // e.g., "exam", "credit", etc.
    public String teacher; // Optional teacher name
    public String date; // Date of issuance
    public java.util.List<String> gradeHistory = new java.util.ArrayList<>(); // History of grades for this subject

    @Override
    public String toString() {
        return subjectName + " - " + grade;
    }
}
