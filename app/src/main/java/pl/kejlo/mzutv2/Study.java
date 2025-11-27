package pl.kejlo.mzutv2;

public class Study {

    public String przynaleznoscId;
    public String label; // Display label for the study (e.g., "Informatyka dzienne I stopień")

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
