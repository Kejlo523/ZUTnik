package pl.kejlo.mzutv2;

public class Study {

    public String przynaleznoscId;
    public String label; // Display label (e.g., "Computer Science full-time BSc")

    @Override
    public String toString() {
        if (label != null) {
            return label;
        }
        if (przynaleznoscId != null) {
            return przynaleznoscId;
        }
        return "Kierunek";
    }
}
