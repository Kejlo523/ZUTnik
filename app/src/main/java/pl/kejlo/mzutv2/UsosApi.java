package pl.kejlo.mzutv2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Request;
import okhttp3.Response;

/**
 * USOS API client.
 *
 * Makes OAuth 1.0a signed GET requests to the USOS API.
 * Requires the user to be logged in via USOS (see {@link MzutSession#isUsosLogin()}).
 *
 * Query parameters are appended to the URL as raw strings — OkHttp / HttpUrl
 * builders are intentionally avoided because they percent-encode commas (%2C),
 * while USOS API expects literal commas in field-selector values.
 */
public final class UsosApi {

    /**
     * Signed GET request using tokens stored in the current session.
     *
     * @param endpoint    e.g. "services/users/user"
     * @param queryParams additional query parameters (may be null)
     */
    public static JSONObject get(String endpoint, Map<String, String> queryParams)
            throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        return get(endpoint,
                session.getUsosAccessToken(),
                session.getUsosAccessTokenSecret(),
                queryParams);
    }

    /**
     * Signed GET that expects a JSON **array** response (e.g. payments/user_payments).
     */
    public static JSONArray getArray(String endpoint, Map<String, String> queryParams)
            throws IOException, JSONException {
        MzutSession session = MzutSession.getInstance();
        return getArray(endpoint,
                session.getUsosAccessToken(),
                session.getUsosAccessTokenSecret(),
                queryParams);
    }

    public static JSONArray getArray(
            String endpoint,
            String accessToken, String accessTokenSecret,
            Map<String, String> queryParams) throws IOException, JSONException {

        String baseUrl = BuildConfig.USOS_BASE_URL + endpoint;
        String fullUrl = buildUrl(baseUrl, queryParams);
        Map<String, String> paramsForSig = queryParams != null
                ? new TreeMap<>(queryParams) : new TreeMap<>();

        String authHeader = UsosOAuth.buildAuthHeader(
                "GET", baseUrl,
                BuildConfig.USOS_CONSUMER_KEY, BuildConfig.USOS_CONSUMER_SECRET,
                accessToken, accessTokenSecret,
                paramsForSig);

        Request request = new Request.Builder()
                .url(fullUrl)
                .get()
                .header("Authorization", authHeader)
                .header("User-Agent", "mZUT-ANDROID-V2/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("USOS API HTTP " + response.code() + ": " + body);
            }
            return body.isEmpty() ? new JSONArray() : new JSONArray(body);
        }
    }

    /**
     * Signed GET request with explicit tokens (used during the login flow before
     * the session is fully populated).
     *
     * @param endpoint          e.g. "services/users/user"
     * @param accessToken       OAuth access token
     * @param accessTokenSecret OAuth access token secret
     * @param queryParams       additional query parameters (may be null)
     */
    public static JSONObject get(
            String endpoint,
            String accessToken, String accessTokenSecret,
            Map<String, String> queryParams) throws IOException, JSONException {

        String baseUrl = BuildConfig.USOS_BASE_URL + endpoint;

        // Build the full URL manually so query parameter values (especially
        // comma-separated USOS field selectors) are NOT percent-encoded.
        // OkHttp's HttpUrl.Builder and addQueryParameter/addEncodedQueryParameter
        // both encode commas to %2C, which USOS API rejects.
        String fullUrl = buildUrl(baseUrl, queryParams);

        // Query params (raw values) go into the OAuth signature computation too.
        Map<String, String> paramsForSig = queryParams != null
                ? new TreeMap<>(queryParams) : new TreeMap<>();

        String authHeader = UsosOAuth.buildAuthHeader(
                "GET", baseUrl,
                BuildConfig.USOS_CONSUMER_KEY, BuildConfig.USOS_CONSUMER_SECRET,
                accessToken, accessTokenSecret,
                paramsForSig);

        // Pass the raw URL string directly — OkHttp accepts String URLs and
        // will not re-encode characters that are valid in query strings (commas
        // are sub-delimiters per RFC 3986 and are preserved).
        Request request = new Request.Builder()
                .url(fullUrl)
                .get()
                .header("Authorization", authHeader)
                .header("User-Agent", "mZUT-ANDROID-V2/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("USOS API HTTP " + response.code() + ": " + body);
            }
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        }
    }

    /** Appends query parameters to baseUrl as a plain string (no encoding of values). */
    private static String buildUrl(String baseUrl, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl;
        }
        StringBuilder sb = new StringBuilder(baseUrl).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            if (!first) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private UsosApi() {}
}
