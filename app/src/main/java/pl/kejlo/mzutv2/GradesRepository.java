package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class GradesRepository {

    // pomocnicze – pierwsza niepusta wartość
    private String firstNonEmpty(String... args) {
        if (args == null) return "";
        for (String s : args) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    /**
     * Ładuje listę kierunków (getMenuStudent),
     * cache'uje w MzutSession (jak $_SESSION['STUDIES'] w PHP).
     *
     * W PHP etykieta to:
     *   $txt = ($s['nazwa'] ?? 'kierunek') . ' (' . ($s['poziom'] ?? '') . ')';
     */
    public List<Study> loadStudies() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        if (session.getStudies() != null && !session.getStudies().isEmpty()) {
            return session.getStudies();
        }

        String userId  = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) {
            return Collections.emptyList();
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);

        JSONObject menu = MzutApi.callApi("getMenuStudent", params);
        if (menu == null || !menu.has("Menu")) {
            return Collections.emptyList();
        }

        Object block = menu.get("Menu");
        JSONArray arr;
        if (block instanceof JSONArray) {
            arr = (JSONArray) block;
        } else {
            arr = new JSONArray();
            arr.put(block);
        }

        List<Study> studies = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            Study s = new Study();
            s.przynaleznoscId = row.optString("przynaleznoscId", null);

            // zgodnie z oceny.php: 'nazwa' i 'poziom'
            String nazwa = row.optString("nazwa", "");
            String poziom = row.optString("poziom", "");

            String label = nazwa != null ? nazwa.trim() : "";
            if (!poziom.trim().isEmpty()) {
                if (!label.isEmpty()) label += " ";
                label += "(" + poziom.trim() + ")";
            }

            if (label.isEmpty()) {
                label = s.przynaleznoscId; // fallback – to co teraz widzisz jako "SPS"
            }

            s.label = label;
            studies.add(s);
        }

        session.setStudies(studies);
        session.setActiveStudyIndex(0);

        return studies;
    }

    /**
     * Ładuje listę semestrów dla aktywnego kierunku (getStudies z parametrem oceny=true).
     */
    public List<Semester> loadSemesters() throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();

        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            studies = loadStudies();
        }
        if (studies == null || studies.isEmpty()) {
            return Collections.emptyList();
        }

        int idx = session.getActiveStudyIndex();
        if (idx < 0 || idx >= studies.size()) idx = 0;
        Study active = studies.get(idx);

        String userId  = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null || active.przynaleznoscId == null) {
            return Collections.emptyList();
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", active.przynaleznoscId);
        params.put("oceny", "true");  // tak jak w oceny.php

        JSONObject resp = MzutApi.callApi("getStudies", params);
        if (resp == null || !resp.has("Przebieg")) {
            return Collections.emptyList();
        }

        Object block = resp.get("Przebieg");
        JSONArray arr;
        if (block instanceof JSONArray) {
            arr = (JSONArray) block;
        } else {
            arr = new JSONArray();
            arr.put(block);
        }

        List<Semester> semesters = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            Semester s = new Semester();
            s.listaSemestrowId = row.optString("listaSemestrowId", null);
            s.nrSemestru       = row.optString("nrSemestru", "");
            s.pora             = row.optString("pora", "");
            s.rokAkademicki    = row.optString("rokAkademicki", "");
            s.status           = row.optString("status", row.optString("statusO", ""));
            semesters.add(s);
        }

        return semesters;
    }

    /**
     * Ładuje oceny dla wybranego semestru (getGrade).
     * Klucze zgodne z oceny.php:
     *  - przedmiot / przedmiotO
     *  - formaZajec / formaZajecO
     *  - ects
     *  - ocena
     *  - termin / terminO
     *  - data
     *  - pracownik
     */
    public List<Grade> loadGradesForSemester(String listaSemestrowId)
            throws IOException, JSONException {

        MzutSession session = MzutSession.getInstance();
        String userId  = session.getUserId();
        String authKey = session.getAuthKey();

        if (userId == null || authKey == null || listaSemestrowId == null) {
            return Collections.emptyList();
        }

        HashMap<String, String> params = new HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("listaSemestrowId", listaSemestrowId);

        JSONObject resp = MzutApi.callApi("getGrade", params);
        if (resp == null || !resp.has("Ocena")) {
            return Collections.emptyList();
        }

        Object block = resp.get("Ocena");
        JSONArray arr;
        if (block instanceof JSONArray) {
            arr = (JSONArray) block;
        } else {
            arr = new JSONArray();
            arr.put(block);
        }

        List<Grade> grades = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            Grade g = new Grade();

            // nazwa przedmiotu + ewentualna forma
            String przedmiot = firstNonEmpty(
                    row.optString("przedmiot", ""),
                    row.optString("przedmiotO", "")
            );
            String formaZajec = firstNonEmpty(
                    row.optString("formaZajec", ""),
                    row.optString("formaZajecO", "")
            );
            if (!formaZajec.isEmpty()) {
                if (!przedmiot.isEmpty()) przedmiot += " ";
                przedmiot += "(" + formaZajec + ")";
            }
            g.subjectName = przedmiot;

            g.grade  = row.optString("ocena", "");
            g.weight = row.optDouble("ects", 0.0); // możesz wykorzystać do ECTS

            // "rodzaj" w UI – dajmy formę zajęć (wykład, ćwiczenia itd.)
            g.type = formaZajec;

            g.teacher = row.optString("pracownik", "");

            String termin = firstNonEmpty(
                    row.optString("termin", ""),
                    row.optString("terminO", "")
            );
            String data = row.optString("data", "");
            g.date = !termin.isEmpty()
                    ? (data.isEmpty() ? termin : (termin + " " + data))
                    : data;

            grades.add(g);
        }

        return grades;
    }
}
