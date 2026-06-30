package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * USOS API client.
 *
 * Makes OAuth 1.0a signed GET requests to the USOS API.
 * Requires the user to be logged in via USOS (see {@link ZutnikSession#isUsosLogin()}).
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
        ZutnikSession session = ZutnikSession.getInstance();
        return get(endpoint,
                session.getUsosAccessToken(),
                session.getUsosAccessTokenSecret(),
                queryParams);
    }

    /**
     * Signed GET request that returns the raw response body.
     * Some USOS endpoints return a scalar JSON value instead of an object/array.
     */
    public static String getRaw(String endpoint, Map<String, String> queryParams)
            throws IOException {
        ZutnikSession session = ZutnikSession.getInstance();
        return getRaw(endpoint,
                session.getUsosAccessToken(),
                session.getUsosAccessTokenSecret(),
                queryParams);
    }

    /**
     * Signed GET that expects a JSON **array** response (e.g. payments/user_payments).
     */
    public static JSONArray getArray(String endpoint, Map<String, String> queryParams)
            throws IOException, JSONException {
        ZutnikSession session = ZutnikSession.getInstance();
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
                .header("User-Agent", "ZUTnik-Android/2.0")
                .build();

        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
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
                .header("User-Agent", "ZUTnik-Android/2.0")
                .build();

        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("USOS API HTTP " + response.code() + ": " + body);
            }
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        }
    }

    public static String getRaw(
            String endpoint,
            String accessToken, String accessTokenSecret,
            Map<String, String> queryParams) throws IOException {

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
                .header("User-Agent", "ZUTnik-Android/2.0")
                .build();

        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("USOS API HTTP " + response.code() + ": " + body);
            }
            return body;
        }
    }

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /**
     * Converts raw USOS API error bodies (often HTML gateway pages) into a short user-facing message.
     */
    public static String friendlyErrorMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype")) {
            return "USOS zwrócił nieczytelną odpowiedź. Spróbuj ponownie za chwilę.";
        }
        if (message.contains("HTTP 502")
                || message.contains("HTTP 503")
                || message.contains("HTTP 504")
                || lower.contains("service unavailable")
                || lower.contains("bad gateway")) {
            return "USOS jest teraz chwilowo niedostępny. Spróbuj ponownie za chwilę.";
        }
        String cleaned = HTML_TAG.matcher(message).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 220) {
            return cleaned.substring(0, 217).trim() + "...";
        }
        return cleaned;
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
