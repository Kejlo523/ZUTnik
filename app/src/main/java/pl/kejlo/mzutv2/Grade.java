package pl.kejlo.mzutv2;

public class Grade {
    public String subjectName;   // nazwa przedmiotu
    public String grade;         // np. "5.0"
    public double weight;        // waga (ECTS)
    public String type;          // np. "egzamin", "zaliczenie", itp.
    public String teacher;       // opcjonalnie prowadzący
    public String date;          // data wystawienia

    @Override
    public String toString() {
        return subjectName + " - " + grade;
    }
}
