package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class StudiesInfoRepository {

    private static final String PROGRAMME_FIELDS =
            "id|programme[id|name|description]|status|admission_date|is_primary";
    private static final String PROGRAMME_FALLBACK_FIELDS =
            "id|programme[id|name|description]|status|admission_date|is_primary";
    private static final String PROGRAMME_MIN_FIELDS =
            "id|programme|status|admission_date|is_primary";
    private static final String STUDENT_PROGRAMME_FIELDS =
            "id|programme[id|name|description]|status|admission_date|is_primary";

    public static class StudyDetails {
        public String album;
        public String kierunek;
        public String status;
        public String rokAkademicki;
        public String semestrLabel;
        public Double ectsProgramme;
        public Double ectsOverall;
        public String elsId;
        public String elsExpirationDate;
        public String elsStatus;
    }

    public static class StudyHistoryItem {
        public String label;
        public String status;
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

    private String normalizeStudyId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String normalized = rawId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String localizedValue(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            return firstNonEmpty(
                    obj.optString("pl", ""),
                    obj.optString("en", ""),
                    obj.optString("name", ""));
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        return firstNonEmpty(value instanceof String ? (String) value : "");
    }

    private String localizedField(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        return localizedValue(obj.opt(key));
    }

    private String mapStudyStatus(String status) {
        switch (String.valueOf(status)) {
            case "active":
                return "Aktywny";
            case "cancelled":
                return "Anulowany";
            case "graduated_diploma":
                return "Absolwent";
            case "graduated_end_of_study":
            case "graduated_before_diploma":
                return "Absolwent (ukończone)";
            default:
                return firstNonEmpty(status);
        }
    }

    private JSONArray fetchStudentProgrammesRaw() throws IOException, JSONException {
        String[] selectors = { PROGRAMME_FIELDS, PROGRAMME_FALLBACK_FIELDS, PROGRAMME_MIN_FIELDS };
        for (String selector : selectors) {
            try {
                java.util.Map<String, String> params = new java.util.HashMap<>();
                params.put("active_only", "false");
                params.put("old_programs", "false");
                params.put("fields", selector);
                return UsosApi.getArray("services/progs/student", params);
            } catch (IOException | JSONException ignored) {
                // Some selectors are too rich for certain USOS deployments.
            }
        }
        return new JSONArray();
    }

    private JSONObject fetchDetailedStudentProgramme(String studentProgrammeId) {
        if (studentProgrammeId == null || studentProgrammeId.trim().isEmpty()
                || "usos-profile".equals(studentProgrammeId)) {
            return null;
        }

        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("student_programme_id", studentProgrammeId);
            params.put("fields", STUDENT_PROGRAMME_FIELDS);
            return UsosApi.get("services/progs/student_programme", params);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject pickSelectedProgramme(JSONArray programmes, String activeStudyId) {
        if (programmes == null || programmes.length() == 0) {
            return null;
        }

        String expectedId = normalizeStudyId(activeStudyId);
        JSONObject first = null;
        for (int i = 0; i < programmes.length(); i++) {
            JSONObject row = programmes.optJSONObject(i);
            if (row == null) {
                continue;
            }
            if (first == null) {
                first = row;
            }
            String candidateId = normalizeStudyId(row.optString("id", null));
            if (expectedId != null && expectedId.equals(candidateId)) {
                return row;
            }
        }
        return first;
    }

    private JSONObject loadActiveTerm() {
        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("fields", "terms");
            params.put("active_terms_only", "true");
            JSONObject response = UsosApi.get("services/courses/user", params);
            JSONArray terms = response != null ? response.optJSONArray("terms") : null;
            if (terms != null && terms.length() > 0) {
                return terms.optJSONObject(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void loadElsInfo(StudyDetails details) {
        if (details == null) {
            return;
        }
        try {
            JSONArray cards = UsosApi.getArray("services/cards/user", null);
            JSONObject selected = null;
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.optJSONObject(i);
                if (card == null) {
                    continue;
                }
                String type = firstNonEmpty(card.optString("type", "")).toLowerCase(Locale.ROOT);
                if (selected == null) {
                    selected = card;
                }
                if ("student".equals(type) || "phd".equals(type)) {
                    selected = card;
                    break;
                }
            }
            if (selected == null) {
                return;
            }

            details.elsId = firstNonEmpty(
                    selected.optString("id", ""),
                    selected.optString("barcode_number", ""),
                    selected.optString("number", ""));
            details.elsExpirationDate = firstNonEmpty(selected.optString("expiration_date", ""));
            if (details.elsExpirationDate.isEmpty()) {
                details.elsStatus = "Aktywna";
                return;
            }
            boolean active = !LocalDate.parse(details.elsExpirationDate).isBefore(LocalDate.now());
            details.elsStatus = active ? "Aktywna" : "Nieaktywna";
        } catch (Exception ignored) {
        }
    }

    private String termSeason(JSONObject term) {
        String id = firstNonEmpty(term != null ? term.optString("id", "") : "").toUpperCase(Locale.ROOT);
        String name = localizedField(term, "name").toLowerCase(Locale.ROOT);
        if (id.endsWith("L") || name.contains("letni") || name.contains("summer")) {
            return "letni";
        }
        if (id.endsWith("Z") || name.contains("zimowy") || name.contains("winter")) {
            return "zimowy";
        }
        return "";
    }

    private String academicYearFromTerm(JSONObject term) {
        String name = localizedField(term, "name");
        java.util.regex.Matcher nameYear = java.util.regex.Pattern
                .compile("20\\d{2}\\s*[/\\\\-]\\s*(?:20)?\\d{2}")
                .matcher(name);
        if (nameYear.find()) {
            return nameYear.group().replaceAll("\\s+", "");
        }

        String id = firstNonEmpty(term != null ? term.optString("id", "") : "");
        java.util.regex.Matcher idYear = java.util.regex.Pattern
                .compile("(20\\d{2})\\D?(\\d{2})?")
                .matcher(id);
        if (idYear.find()) {
            String start = idYear.group(1);
            String end = idYear.group(2) != null
                    ? "20" + idYear.group(2)
                    : String.valueOf(Integer.parseInt(start) + 1);
            return start + "/" + end;
        }

        return firstNonEmpty(name, id);
    }

    private String getActivePrzynaleznoscId() throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
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
        return normalizeStudyId(active.przynaleznoscId);
    }

    public StudyDetails loadCurrentStudyDetails() throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (!session.isUsosLogin() || session.getUserId() == null) {
            return null;
        }

        String activeStudyId = getActivePrzynaleznoscId();
        JSONArray programmes = fetchStudentProgrammesRaw();
        JSONObject selected = pickSelectedProgramme(programmes, activeStudyId);
        JSONObject detailed = fetchDetailedStudentProgramme(selected != null ? selected.optString("id", null) : null);
        JSONObject row = detailed != null ? detailed : selected;
        JSONObject programme = row != null ? row.optJSONObject("programme") : null;

        java.util.Map<String, String> userParams = new java.util.HashMap<>();
        userParams.put("fields", "id|student_number");
        JSONObject user = UsosApi.get("services/users/user", userParams);

        StudyDetails details = new StudyDetails();
        details.album = firstNonEmpty(
                user != null ? user.optString("student_number", "") : "",
                session.getStudentNumber(),
                user != null ? user.optString("id", "") : "");
        details.kierunek = firstNonEmpty(
                localizedField(programme, "name"),
                localizedField(programme, "description"),
                programme != null ? programme.optString("id", "") : "",
                activeStudyId);
        details.status = mapStudyStatus(row != null ? row.optString("status", "") : "");

        JSONObject activeTerm = loadActiveTerm();
        details.rokAkademicki = activeTerm != null ? academicYearFromTerm(activeTerm) : "";
        details.semestrLabel = activeTerm != null ? termSeason(activeTerm) : "";

        try {
            GradesRepository.CreditSummary credits = new GradesRepository().loadCreditSummary();
            details.ectsProgramme = credits != null ? credits.programmeUsed : null;
            details.ectsOverall = credits != null ? credits.overallUsed : null;
        } catch (Exception ignored) {
            details.ectsProgramme = null;
            details.ectsOverall = null;
        }
        loadElsInfo(details);

        return details;
    }

    public List<StudyHistoryItem> loadStudyHistory() throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
        if (!session.isUsosLogin() || session.getUserId() == null) {
            return new ArrayList<>();
        }

        JSONArray programmes = fetchStudentProgrammesRaw();
        List<StudyHistoryItem> result = new ArrayList<>();
        for (int i = 0; i < programmes.length(); i++) {
            JSONObject row = programmes.optJSONObject(i);
            if (row == null) {
                continue;
            }

            JSONObject programme = row.optJSONObject("programme");
            String label = firstNonEmpty(
                    localizedField(programme, "name"),
                    localizedField(programme, "description"),
                    programme != null ? programme.optString("id", "") : "",
                    row.optString("id", ""));
            if (label.isEmpty()) {
                continue;
            }

            StudyHistoryItem item = new StudyHistoryItem();
            item.label = label;
            item.status = mapStudyStatus(row.optString("status", ""));
            result.add(item);
        }
        return result;
    }
}
