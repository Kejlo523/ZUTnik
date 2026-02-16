package pl.kejlo.mzutv2;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;

import java.io.IOException;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;

public class MzutApi {

    private static final String MZUT_API_BASE = "https://www.zut.edu.pl/app-json-proxy/index.php";

    public static JSONObject callApi(String fn, Map<String, String> params)
            throws IOException, JSONException {

        // Build URL
        String urlStr = MZUT_API_BASE + "?f=" + java.net.URLEncoder.encode(fn, "UTF-8");

        // Build POST body
        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            formBuilder.add(e.getKey(), e.getValue());
        }

        // Build request using custom client
        Request request = new Request.Builder()
                .url(urlStr)
                .post(formBuilder.build())
                .header("User-Agent", "mZUT-ANDROID-V2/1.0")
                .build();

        // Execute request
        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) {
                Context appContext = MzutSession.getAppContextOrNull();
                if (appContext != null) {
                    SessionExpiryManager.handleSessionExpired(appContext, "HTTP " + code);
                }
                throw new SessionExpiredException("Session unauthorized (HTTP " + code + ")");
            }

            if (!response.isSuccessful()) {
                throw new IOException("HTTP error: " + code);
            }

            String respBody = response.body() != null ? response.body().string() : "";
            if (respBody.isEmpty()) {
                return null;
            }

            JSONObject json = new JSONObject(respBody);
            if (SessionExpiryManager.isSessionExpiredResponse(json)) {
                Context appContext = MzutSession.getAppContextOrNull();
                if (appContext != null) {
                    SessionExpiryManager.handleSessionExpired(
                            appContext,
                            SessionExpiryManager.extractSessionExpiredReason(json));
                }
                throw new SessionExpiredException("Session expired");
            }

            return json;
        }
    }
}
