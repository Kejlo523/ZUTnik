package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class CreditSummaryStore {

    private static final String PREFS_NAME = "grades_cache";
    private static final String KEY_PREFIX = "credit_summary_";

    private CreditSummaryStore() {
    }

    static GradesRepository.CreditSummary load(
            Context context,
            Study study,
            long ttlMs,
            boolean allowExpired) {
        String key = buildKey(study);
        if (context == null || key == null) {
            return null;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = SecureLocalData.readString(context, prefs, key, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        try {
            JSONObject wrapper = new JSONObject(raw);
            long timestamp = wrapper.optLong("timestamp", 0L);
            if (timestamp <= 0L) {
                return null;
            }
            if (!allowExpired && ttlMs > 0L
                    && System.currentTimeMillis() - timestamp > ttlMs) {
                return null;
            }

            Double programme = readNonNegativeDouble(wrapper, "programme");
            Double overall = readNonNegativeDouble(wrapper, "overall");
            List<GradesRepository.ProgrammeCredit> programmeCredits = new ArrayList<>();
            JSONArray programmes = wrapper.optJSONArray("programmes");
            if (programmes != null) {
                for (int i = 0; i < programmes.length(); i++) {
                    JSONObject item = programmes.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    programmeCredits.add(new GradesRepository.ProgrammeCredit(
                            item.optString("id", ""),
                            item.optString("label", ""),
                            readNonNegativeDouble(item, "used")));
                }
            }

            return new GradesRepository.CreditSummary(
                    wrapper.optString("studentProgrammeId", ""),
                    programme,
                    overall,
                    programmeCredits);
        } catch (JSONException ignored) {
            return null;
        }
    }

    static void save(
            Context context,
            Study study,
            GradesRepository.CreditSummary summary) {
        String key = buildKey(study);
        if (context == null || key == null || summary == null) {
            return;
        }

        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put(
                    "studentProgrammeId",
                    summary.studentProgrammeId != null ? summary.studentProgrammeId : "");
            putNonNegativeDouble(wrapper, "programme", summary.programmeUsed);
            putNonNegativeDouble(wrapper, "overall", summary.overallUsed);

            JSONArray programmes = new JSONArray();
            if (summary.programmeCredits != null) {
                for (GradesRepository.ProgrammeCredit credit : summary.programmeCredits) {
                    if (credit == null) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put(
                            "id",
                            credit.studentProgrammeId != null ? credit.studentProgrammeId : "");
                    item.put("label", credit.label != null ? credit.label : "");
                    putNonNegativeDouble(item, "used", credit.used);
                    programmes.put(item);
                }
            }
            wrapper.put("programmes", programmes);

            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SecureLocalData.putString(context, prefs, key, wrapper.toString());
        } catch (JSONException ignored) {
            // Cache is optional; the freshly loaded values remain visible in memory.
        }
    }

    private static String buildKey(Study study) {
        if (study == null || study.przynaleznoscId == null) {
            return null;
        }
        String userId = ZutnikSession.getInstance().getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            userId = "unknown";
        }
        return KEY_PREFIX + userId + "_" + study.przynaleznoscId;
    }

    private static Double readNonNegativeDouble(JSONObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        double value = object.optDouble(key, Double.NaN);
        return Double.isNaN(value) || value < 0.0 ? null : value;
    }

    private static void putNonNegativeDouble(
            JSONObject object,
            String key,
            Double value) throws JSONException {
        if (value != null && !Double.isNaN(value) && value >= 0.0) {
            object.put(key, value);
        }
    }
}
