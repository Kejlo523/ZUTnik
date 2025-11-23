package pl.kejlo.mzutv2;

public class Study {
    public String przynaleznoscId;
    public String label;   // jak wyświetlić kierunek (np. "Informatyka dzienne I stopień")

    @Override
    public String toString() {
        return label != null ? label : (przynaleznoscId != null ? przynaleznoscId : "Kierunek");
    }
}
