package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class StudiesInfoRepository {

    public static class StudyDetails {
        public String album;
        public String wydzial;
        public String kierunek;
        public String forma;
        public String poziom;
        public String specjalnosc;
        public String specjalizacja;
        public String status;
        public String rokAkademicki;
        public String semestrLabel;
    }

    public static class StudyHistoryItem {
        public String label;   // np. "1 zimowy - 2023/2024"
        public String status;  // np. "zaliczony"
    }

    private String firstNonEmpty(String... args) {
        if (args == null) return "";
        for (String s : args) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    private String getActivePrzynaleznoscId() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            // korzystamy z GradesRepository, który już ściąga getMenuStudent
            GradesRepository repo = new GradesRepository();
            studies = repo.loadStudies();
        }
        if (studies == null || studies.isEmpty()) return null;

        int idx = session.getActiveStudyIndex();
        if (idx < 0 || idx >= studies.size()) idx = 0;

        Study active = studies.get(idx);
        return active.przynaleznoscId;
    }

    public StudyDetails loadCurrentStudyDetails() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId  = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null) return null;

        String przynaleznoscId = getActivePrzynaleznoscId();
        if (przynaleznoscId == null) return null;

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", przynaleznoscId);

        JSONObject study = MzutApi.callApi("getStudy", params);
        if (study == null || !study.has("album")) {
            return null;
        }

        StudyDetails d = new StudyDetails();
        d.album         = study.optString("album", "");
        d.wydzial       = firstNonEmpty(
                study.optString("wydzial", ""),
                study.optString("wydzialAng", "")
        );
        d.kierunek      = firstNonEmpty(
                study.optString("kierunek", ""),
                study.optString("kierunekAng", "")
        );
        d.forma         = firstNonEmpty(
                study.optString("forma", ""),
                study.optString("formaAng", "")
        );
        d.poziom        = firstNonEmpty(
                study.optString("poziom", ""),
                study.optString("poziomAng", "")
        );
        d.specjalnosc   = firstNonEmpty(
                study.optString("specjalnosc", ""),
                study.optString("specjalnoscO", "")
        );
        d.specjalizacja = firstNonEmpty(
                study.optString("specjalizacja", ""),
                study.optString("specjalizacjaO", "")
        );
        d.status        = firstNonEmpty(
                study.optString("status", ""),
                study.optString("statusAng", "")
        );
        d.rokAkademicki = study.optString("rokAkademicki", "");

        String nrSemestru = study.optString("nrSemestru", "");
        String pora       = study.optString("pora", "");
        d.semestrLabel    = (nrSemestru + " " + pora).trim();

        return d;
    }

    public List<StudyHistoryItem> loadStudyHistory() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId  = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) return new ArrayList<>();

        String przynaleznoscId = getActivePrzynaleznoscId();
        if (przynaleznoscId == null) return new ArrayList<>();

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", przynaleznoscId);
        params.put("oceny", "true");

        JSONObject studies = MzutApi.callApi("getStudies", params);
        if (studies == null || !studies.has("Przebieg")) {
            return new ArrayList<>();
        }

        Object block = studies.get("Przebieg");
        JSONArray arr = (block instanceof JSONArray)
                ? (JSONArray) block
                : new JSONArray().put(block);

        List<StudyHistoryItem> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            String nrSemestru    = row.optString("nrSemestru", "");
            String pora          = row.optString("pora", "");
            String rokAkademicki = row.optString("rokAkademicki", "");
            String status        = firstNonEmpty(
                    row.optString("status", ""),
                    row.optString("statusO", "")
            );

            StudyHistoryItem item = new StudyHistoryItem();
            item.label  = (nrSemestru + " " + pora + " - " + rokAkademicki).trim();
            item.status = status;
            result.add(item);
        }

        return result;
    }
}
