package pl.kejlo.mzutv2;

import android.util.Base64;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OAuth 1.0a engine for USOS API.
 *
 * Flow:
 *   1. fetchRequestToken()   → get temp token + secret
 *   2. authorizationUrl()    → open in browser for user login
 *   3. fetchAccessToken()    → exchange verifier for permanent token
 *   4. buildAuthHeader()     → sign every subsequent API request
 */
public final class UsosOAuth {

    public static final String CALLBACK_URL = "mzutv2://oauth/callback";

    private static final String REQUEST_TOKEN_URL =
            BuildConfig.USOS_BASE_URL + "services/oauth/request_token";
    private static final String AUTHORIZE_URL =
            BuildConfig.USOS_BASE_URL + "services/oauth/authorize";
    private static final String ACCESS_TOKEN_URL =
            BuildConfig.USOS_BASE_URL + "services/oauth/access_token";

    // region Value objects

    public static final class RequestToken {
        public final String token;
        public final String tokenSecret;

        RequestToken(String token, String tokenSecret) {
            this.token = token;
            this.tokenSecret = tokenSecret;
        }
    }

    public static final class AccessToken {
        public final String token;
        public final String tokenSecret;

        AccessToken(String token, String tokenSecret) {
            this.token = token;
            this.tokenSecret = tokenSecret;
        }
    }

    // endregion

    // region Public API

    /**
     * Step 1 – Fetch a temporary request token.
     * Call from a background thread.
     */
    public static RequestToken fetchRequestToken(String consumerKey, String consumerSecret, String scopes)
            throws IOException {

        Map<String, String> oauthParams = baseOAuthParams(consumerKey);
        oauthParams.put("oauth_callback", CALLBACK_URL);

        Map<String, String> allForSig = new TreeMap<>(oauthParams);
        if (scopes != null && !scopes.isEmpty()) {
            allForSig.put("scopes", scopes);
        }

        String sig = sign("POST", REQUEST_TOKEN_URL, allForSig, consumerSecret, "");
        String authHeader = authHeader(oauthParams, sig);

        FormBody.Builder bodyBuilder = new FormBody.Builder();
        if (scopes != null && !scopes.isEmpty()) {
            bodyBuilder.add("scopes", scopes);
        }

        Request req = new Request.Builder()
                .url(REQUEST_TOKEN_URL)
                .post(bodyBuilder.build())
                .header("Authorization", authHeader)
                .header("User-Agent", "ZUTnik-Android/2.0")
                .build();

        try (Response resp = MzutNetwork.getClient().newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("Request token HTTP " + resp.code() + ": " + body);
            }
            String[] tv = parseTokenPair(body);
            return new RequestToken(tv[0], tv[1]);
        }
    }

    /**
     * Step 2 – Build the URL the user must open in a browser.
     */
    public static String authorizationUrl(String requestToken) {
        return AUTHORIZE_URL
                + "?oauth_token=" + pct(requestToken)
                + "&interactivity=confirm_user";
    }

    /**
     * Step 3 – Exchange the verifier for a permanent access token.
     * Call from a background thread.
     */
    public static AccessToken fetchAccessToken(
            String consumerKey, String consumerSecret,
            String requestToken, String requestTokenSecret,
            String verifier) throws IOException {

        Map<String, String> oauthParams = baseOAuthParams(consumerKey);
        oauthParams.put("oauth_token", requestToken);
        oauthParams.put("oauth_verifier", verifier);

        String sig = sign("POST", ACCESS_TOKEN_URL, oauthParams, consumerSecret, requestTokenSecret);
        String authHeader = authHeader(oauthParams, sig);

        FormBody body = new FormBody.Builder()
                .add("oauth_verifier", verifier)
                .build();

        Request req = new Request.Builder()
                .url(ACCESS_TOKEN_URL)
                .post(body)
                .header("Authorization", authHeader)
                .header("User-Agent", "ZUTnik-Android/2.0")
                .build();

        try (Response resp = MzutNetwork.getClient().newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("Access token HTTP " + resp.code() + ": " + respBody);
            }
            String[] tv = parseTokenPair(respBody);
            return new AccessToken(tv[0], tv[1]);
        }
    }

    /**
     * Builds an Authorization header for any signed USOS API request.
     * Used by {@link UsosApi} for every call after login.
     *
     * @param extraRequestParams Query / body params that must be included in the signature
     *                           (but NOT added to the Authorization header itself).
     */
    public static String buildAuthHeader(
            String method, String baseUrl,
            String consumerKey, String consumerSecret,
            String accessToken, String accessTokenSecret,
            Map<String, String> extraRequestParams) {

        Map<String, String> oauthParams = baseOAuthParams(consumerKey);
        if (accessToken != null && !accessToken.isEmpty()) {
            oauthParams.put("oauth_token", accessToken);
        }

        // All params (OAuth + request) go into the signature computation.
        Map<String, String> allForSig = new TreeMap<>(oauthParams);
        if (extraRequestParams != null) {
            allForSig.putAll(extraRequestParams);
        }

        String sig = sign(method, baseUrl, allForSig, consumerSecret, accessTokenSecret);
        return authHeader(oauthParams, sig);
    }

    // endregion

    // region HMAC-SHA1 signing internals

    private static Map<String, String> baseOAuthParams(String consumerKey) {
        Map<String, String> p = new TreeMap<>();
        p.put("oauth_consumer_key", consumerKey);
        p.put("oauth_nonce", UUID.randomUUID().toString().replace("-", ""));
        p.put("oauth_signature_method", "HMAC-SHA1");
        p.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        p.put("oauth_version", "1.0");
        return p;
    }

    /**
     * Builds and returns the HMAC-SHA1 base64 signature.
     * All param values must be their RAW (non-encoded) form; encoding is done here.
     */
    private static String sign(
            String method, String baseUrl,
            Map<String, String> params,
            String consumerSecret, String tokenSecret) {

        // Percent-encode each key=value pair, sort them, then join
        List<String> pairs = new ArrayList<>(params.size());
        for (Map.Entry<String, String> e : params.entrySet()) {
            pairs.add(pct(e.getKey()) + "=" + pct(e.getValue()));
        }
        Collections.sort(pairs);
        String normalizedParams = join(pairs);

        // Signature base string: METHOD & pct(baseUrl) & pct(normalizedParams)
        String sigBase = method.toUpperCase(Locale.ROOT)
                + "&" + pct(baseUrl)
                + "&" + pct(normalizedParams);

        // Signing key: pct(consumerSecret) & pct(tokenSecret)
        String signingKey = pct(consumerSecret) + "&" + pct(tokenSecret != null ? tokenSecret : "");

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(signingKey.getBytes("UTF-8"), "HmacSHA1"));
            byte[] raw = mac.doFinal(sigBase.getBytes("UTF-8"));
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
            throw new RuntimeException("HMAC-SHA1 signing failed", e);
        }
    }

    /** Builds the OAuth Authorization header (without oauth_signature in the params map). */
    private static String authHeader(Map<String, String> oauthParams, String signature) {
        StringBuilder sb = new StringBuilder("OAuth ");
        boolean first = true;
        for (Map.Entry<String, String> e : oauthParams.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(pct(e.getKey())).append("=\"").append(pct(e.getValue())).append("\"");
            first = false;
        }
        // Append the signature last
        if (!first) sb.append(", ");
        sb.append("oauth_signature=\"").append(pct(signature)).append("\"");
        return sb.toString();
    }

    // endregion

    // region Utility

    /** RFC 3986 percent-encoding. */
    static String pct(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /** Parses a URL-encoded body and returns [oauth_token, oauth_token_secret]. */
    private static String[] parseTokenPair(String body) throws IOException {
        String token = null;
        String secret = null;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    String k = URLDecoder.decode(kv[0], "UTF-8");
                    String v = URLDecoder.decode(kv[1], "UTF-8");
                    if ("oauth_token".equals(k))        token  = v;
                    else if ("oauth_token_secret".equals(k)) secret = v;
                } catch (UnsupportedEncodingException ignored) { /* UTF-8 always available */ }
            }
        }
        if (token == null || secret == null) {
            throw new IOException("Missing oauth_token or oauth_token_secret in: " + body);
        }
        return new String[]{token, secret};
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private UsosOAuth() {}

    // endregion
}
