package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        public String label;   // e.g. "1 zimowy - 2023/2024"
        public String status;  // e.g. "zaliczony"
    }

    private String firstNonEmpty(String... args) {
        if (args == null) {
            return "";
        }
        for (String s : args) {
            if (s != null && !s.trim().isEmpty()) {
                return s.trim();
            }
        }
        return "";
    }

    private String extractLocalized(JSONObject obj, String key) {
        if (obj == null) return "";
        JSONObject localized = obj.optJSONObject(key);
        if (localized != null) {
            return localized.optString("pl", localized.optString("en", ""));
        }
        return obj.optString(key, "");
    }

    private String getActivePrzynaleznoscId() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            // Reuse GradesRepository, which already fetches getMenuStudent
            GradesRepository repo = new GradesRepository();
            studies = repo.loadStudies();
        }
        if (studies == null || studies.isEmpty()) {
            return null;
        }

        Study active = session.getActiveStudy();
        if (active == null && !studies.isEmpty()) {
            session.setActiveStudyIndex(0);
            active = session.getActiveStudy();
        }
        if (active == null) {
            return null;
        }
        if (active.przynaleznoscId == null) {
            return null;
        }
        String id = active.przynaleznoscId.trim();
        return id.isEmpty() ? null : id;
    }

    public StudyDetails loadCurrentStudyDetails() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || (!session.isUsosLogin() && authKey == null)) {
            return null;
        }

        String przynaleznoscId = getActivePrzynaleznoscId();
        if (przynaleznoscId == null) {
            return null;
        }

        if (session.isUsosLogin()) {
            StudyDetails d = new StudyDetails();

            // Album number — prefer session cache, then fresh API call
            d.album = session.getStudentNumber();
            if (d.album == null || d.album.isEmpty()) {
                try {
                    Map<String, String> userParams = new HashMap<>();
                    userParams.put("fields", "student_number");
                    JSONObject userObj = UsosApi.get("services/users/user", userParams);
                    d.album = userObj.optString("student_number", "");
                } catch (Exception ignored) {}
            }

            // Fetch student programmes — active_only=false to include past ones.
            // przynaleznoscId is programme.id (e.g. "S1-INF"), so match on that.
            Map<String, String> progsParams = new HashMap<>();
            progsParams.put("fields", "programme[id|description|mode_of_studies|level_of_studies]|status");
            progsParams.put("active_only", "false");
            JSONArray progs = UsosApi.getArray("services/progs/student", progsParams);
            for (int i = 0; i < progs.length(); i++) {
                JSONObject row = progs.getJSONObject(i);
                JSONObject prog = row.optJSONObject("programme");
                if (prog == null) continue;

                String pid = prog.optString("id", "");
                // Match the selected study; fall back to first entry if nothing matches
                if (!pid.equals(przynaleznoscId) && i < progs.length() - 1) continue;

                JSONObject desc = prog.optJSONObject("description");
                d.kierunek = desc != null
                        ? desc.optString("pl", desc.optString("en", pid))
                        : pid;

                // Faculty — separate lightweight call (faculty[id|name] avoids subfield error)
                try {
                    Map<String, String> pParams = new HashMap<>();
                    pParams.put("programme_id", pid);
                    pParams.put("fields", "faculty[id|name]");
                    JSONObject pObj = UsosApi.get("services/progs/programme", pParams);
                    if (pObj != null) {
                        JSONObject fac = pObj.optJSONObject("faculty");
                        if (fac != null) {
                            d.wydzial = extractLocalized(fac, "name");
                        }
                    }
                } catch (Exception ignored) {}

                int mode = prog.optInt("mode_of_studies", 0);
                d.forma = mode == 1 ? "Stacjonarne" : "Niestacjonarne";
                d.poziom = extractLocalized(prog, "level_of_studies");
                d.specjalnosc = "";
                d.specjalizacja = "";

                // Status — API returns string like "active", "graduated_diploma" etc.
                String statusRaw = row.optString("status", "");
                switch (statusRaw) {
                    case "active": d.status = "Aktywny"; break;
                    case "cancelled": d.status = "Anulowany"; break;
                    case "graduated_diploma": d.status = "Absolwent"; break;
                    case "graduated_end_of_study":
                    case "graduated_before_diploma": d.status = "Absolwent (ukończone)"; break;
                    default: d.status = statusRaw;
                }

                // Active term → academic year + season
                d.rokAkademicki = "";
                d.semestrLabel = "";
                try {
                    Map<String, String> ceParams = new HashMap<>();
                    ceParams.put("active_terms_only", "true");
                    ceParams.put("fields", "course_editions");
                    JSONObject ceResp = UsosApi.get("services/courses/user", ceParams);
                    JSONObject editions = ceResp != null ? ceResp.optJSONObject("course_editions") : null;
                    if (editions != null && editions.keys().hasNext()) {
                        String tid = editions.keys().next(); // e.g. "2024/25Z"
                        d.semestrLabel = tid.endsWith("Z") ? "zimowy"
                                : (tid.endsWith("L") ? "letni" : "");
                        if (tid.length() >= 7) {
                            // "2024/25Z" → "2024/2025"
                            d.rokAkademicki = tid.substring(0, 4) + "/20" + tid.substring(5, 7);
                        } else {
                            d.rokAkademicki = tid.replaceAll("[ZL]$", "");
                        }
                    }
                } catch (Exception ignored) {}

                break;
            }
            return d;
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", przynaleznoscId);

        JSONObject study = MzutApi.callApi("getStudy", params);
        if (study == null || !study.has("album")) {
            return null;
        }

        StudyDetails d = new StudyDetails();
        d.album = study.optString("album", "");
        d.wydzial = firstNonEmpty(
                study.optString("wydzial", ""),
                study.optString("wydzialAng", "")
        );
        d.kierunek = firstNonEmpty(
                study.optString("kierunek", ""),
                study.optString("kierunekAng", "")
        );
        d.forma = firstNonEmpty(
                study.optString("forma", ""),
                study.optString("formaAng", "")
        );
        d.poziom = firstNonEmpty(
                study.optString("poziom", ""),
                study.optString("poziomAng", "")
        );
        d.specjalnosc = firstNonEmpty(
                study.optString("specjalnosc", ""),
                study.optString("specjalnoscO", "")
        );
        d.specjalizacja = firstNonEmpty(
                study.optString("specjalizacja", ""),
                study.optString("specjalizacjaO", "")
        );
        d.status = firstNonEmpty(
                study.optString("status", ""),
                study.optString("statusAng", "")
        );
        d.rokAkademicki = study.optString("rokAkademicki", "");

        String nrSemestru = study.optString("nrSemestru", "");
        String pora = study.optString("pora", "");
        d.semestrLabel = (nrSemestru + " " + pora).trim();

        return d;
    }

    public List<StudyHistoryItem> loadStudyHistory() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || (!session.isUsosLogin() && authKey == null)) {
            return new ArrayList<>();
        }

        String przynaleznoscId = getActivePrzynaleznoscId();
        if (przynaleznoscId == null) {
            return new ArrayList<>();
        }

        if (session.isUsosLogin()) {
             List<StudyHistoryItem> result = new ArrayList<>();
             Map<String, String> params = new HashMap<>();
             params.put("fields", "terms");
             JSONObject termsObj = UsosApi.get("services/courses/user", params);
             JSONArray termsArray = termsObj.optJSONArray("terms");
             if (termsArray != null) {
                 for (int i = 0; i < termsArray.length(); i++) {
                     JSONObject termItem = termsArray.optJSONObject(i);
                     if (termItem != null) {
                         String termId = termItem.optString("id");
                         if (termId != null && !termId.isEmpty()) {
                             StudyHistoryItem item = new StudyHistoryItem();
                             String pora = termId.endsWith("Z") ? "Zimowy" : (termId.endsWith("L") ? "Letni" : "");
                             item.label = termId + " " + pora;
                             item.status = "Zaliczone/Aktywne";
                             result.add(item);
                         }
                     }
                 }
                 result.sort((a, b) -> b.label.compareTo(a.label));
             }
             return result;
        }

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
            String nrSemestru = row.optString("nrSemestru", "");
            String pora = row.optString("pora", "");
            String rokAkademicki = row.optString("rokAkademicki", "");
            String status = firstNonEmpty(
                    row.optString("status", ""),
                    row.optString("statusO", "")
            );

            StudyHistoryItem item = new StudyHistoryItem();
            item.label = (nrSemestru + " " + pora + " - " + rokAkademicki).trim();
            item.status = status;
            result.add(item);
        }

        return result;
    }
}
