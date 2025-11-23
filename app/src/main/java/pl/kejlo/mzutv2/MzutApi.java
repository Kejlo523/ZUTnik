package pl.kejlo.mzutv2;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MzutApi {

    private static final String MZUT_API_BASE = "https://www.zut.edu.pl/app-json-proxy/index.php";

    public static JSONObject callApi(String fn, Map<String, String> params)
            throws IOException, JSONException {

        // URL z ?f=
        String urlStr = MZUT_API_BASE + "?f=" + URLEncoder.encode(fn, "UTF-8");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "mZUT-ANDROID-V2/1.0");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        // Body: http_build_query($params)
        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (postData.length() != 0) postData.append('&');
            postData.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }

        byte[] postBytes = postData.toString().getBytes(StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postBytes);
        }

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP error: " + code);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }

        String resp = sb.toString();
        if (resp.isEmpty()) {
            return null;
        }

        return new JSONObject(resp); // json_decode(..., true)
    }
}
