package pl.kejlo.mzutv2;

public class Semester {
    public String listaSemestrowId;
    public String nrSemestru;
    public String pora;           // np. "zimowy", "letni"
    public String rokAkademicki;  // np. "2024/2025"
    public String status;

    public String getLabel() {
        String base = "";

        if (nrSemestru != null && !nrSemestru.isEmpty()) {
            base += "Semestr " + nrSemestru;
        }

        if (pora != null && !pora.isEmpty()) {
            if (!base.isEmpty()) base += " ";
            base += "(" + pora + ")";
        }

        if (rokAkademicki != null && !rokAkademicki.isEmpty()) {
            if (!base.isEmpty()) base += " ";
            base += rokAkademicki;
        }

        return base.isEmpty() ? "Semestr" : base;
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
