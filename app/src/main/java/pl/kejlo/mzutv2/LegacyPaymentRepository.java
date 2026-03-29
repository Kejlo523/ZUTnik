package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LegacyPaymentRepository {

    public static class LegacyPayment {
        public String name;
        public String chargeAmount;
        public String paidAmount;
        public String dueDate;
        public String paidDate;
        public String balance;
        public String account;
    }

    public List<LegacyPayment> loadPaymentsForActiveStudy(boolean forceRefreshStudies)
            throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        if (session.isUsosLogin()) {
            return new ArrayList<>();
        }

        String userId = session.getUserId();
        String authKey = session.getAuthKey();
        if (userId == null || authKey == null) {
            return new ArrayList<>();
        }

        String przynaleznoscId = getActivePrzynaleznoscId(forceRefreshStudies);
        if (przynaleznoscId == null) {
            return new ArrayList<>();
        }

        java.util.HashMap<String, String> params = new java.util.HashMap<>();
        params.put("login", userId);
        params.put("token", authKey);
        params.put("przynaleznoscId", przynaleznoscId);

        JSONObject response = MzutApi.callApi("getPayment", params);
        return parsePayments(response, shouldPreferPolishLabels());
    }

    private String getActivePrzynaleznoscId(boolean forceRefreshStudies)
            throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        List<Study> studies = session.getStudies();
        if (studies == null || studies.isEmpty()) {
            GradesRepository repo = new GradesRepository();
            studies = repo.loadStudies(forceRefreshStudies);
        }
        if (studies == null || studies.isEmpty()) {
            return null;
        }

        Study active = session.getActiveStudy();
        if (active == null) {
            session.setActiveStudyIndex(0);
            session.saveToPreferences();
            active = session.getActiveStudy();
        }
        if (active == null || active.przynaleznoscId == null) {
            return null;
        }

        String normalized = active.przynaleznoscId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean shouldPreferPolishLabels() {
        Locale locale = Locale.getDefault();
        return locale != null && "pl".equalsIgnoreCase(locale.getLanguage());
    }

    static List<LegacyPayment> parsePayments(JSONObject response, boolean preferPolish)
            throws JSONException {
        List<LegacyPayment> items = new ArrayList<>();
        if (response == null || !response.has("Oplata")) {
            return items;
        }

        Object rawBlock = response.opt("Oplata");
        if (rawBlock == null) {
            return items;
        }

        JSONArray arr;
        if (rawBlock instanceof JSONArray) {
            arr = (JSONArray) rawBlock;
        } else if (rawBlock instanceof JSONObject) {
            arr = new JSONArray().put(rawBlock);
        } else {
            return items;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) {
                continue;
            }

            LegacyPayment payment = new LegacyPayment();
            String primaryName = preferPolish
                    ? row.optString("nazwa", "")
                    : row.optString("nazwaO", "");
            payment.name = firstNonEmpty(primaryName, row.optString("nazwa", ""), row.optString("nazwaO", ""));
            payment.chargeAmount = normalize(row.optString("naleznosc", ""));
            payment.paidAmount = normalize(row.optString("wplata", ""));
            payment.dueDate = normalize(row.optString("dataPlatnosci", ""));
            payment.paidDate = normalize(row.optString("dataWplaty", ""));
            payment.balance = normalize(row.optString("saldo", ""));
            payment.account = normalize(row.optString("konto", ""));
            items.add(payment);
        }

        return items;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
