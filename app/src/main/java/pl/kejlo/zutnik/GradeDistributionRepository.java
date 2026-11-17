package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

final class GradeDistributionRepository {
    private static final long TTL_MS = 24L * 60L * 60L * 1000L;

    static GradeDistribution load(Context context, Grade grade, boolean force) throws Exception {
        ZutnikSession session = ZutnikSession.getInstance(context);
        if (session.isDemoLogin()) {
            return GradeDistribution.parse(new JSONObject("{\"grades_distribution\":["
                    + "{\"grade_symbol\":\"2\",\"percentage\":8},"
                    + "{\"grade_symbol\":\"3\",\"percentage\":17},"
                    + "{\"grade_symbol\":\"3.5\",\"percentage\":20},"
                    + "{\"grade_symbol\":\"4\",\"percentage\":28},"
                    + "{\"grade_symbol\":\"4.5\",\"percentage\":15},"
                    + "{\"grade_symbol\":\"5\",\"percentage\":12}]}"), false);
        }
        String user = session.getUserId();
        String token = session.getUsosAccessToken();
        String secret = session.getUsosAccessTokenSecret();
        String key = BuildConfig.USOS_BASE_URL + ":" + user + ":" + grade.examId;
        SharedPreferences prefs = context.getSharedPreferences("zutnik_grade_distributions", Context.MODE_PRIVATE);
        GradeDistribution cached = null;
        long timestamp = 0L;
        try {
            String raw = SecureLocalData.readString(context, prefs, key, null);
            if (raw != null) {
                JSONObject saved = new JSONObject(raw);
                timestamp = saved.optLong("fetched_at");
                cached = GradeDistribution.parse(saved, true);
            }
        } catch (Exception ignored) { }
        if (!force && cached != null && System.currentTimeMillis() - timestamp < TTL_MS) return cached;
        if (!NetworkStatusHelper.isNetworkAvailable(context) && cached != null) return cached;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", grade.examId);
        params.put("fields", "grades_distribution");
        try {
            JSONObject response = UsosApi.get("services/examrep/exam", token, secret, params);
            GradeDistribution result = GradeDistribution.parse(response, false);
            if (!java.util.Objects.equals(user, ZutnikSession.getInstance().getUserId())
                    || !java.util.Objects.equals(token, ZutnikSession.getInstance().getUsosAccessToken())) {
                throw new java.io.IOException("Session changed");
            }
            response.put("fetched_at", System.currentTimeMillis());
            SecureLocalData.putString(context, prefs, key, response.toString());
            return result;
        } catch (Exception error) {
            if (cached != null) return cached;
            throw error;
        }
    }
}
