package pl.kejlo.zutnik;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Request;
import okhttp3.Response;

public class NewsRepository {

    private static final String TAG = "ZUTnikNews";
    private static final String NEWS_CACHE_FILE = "news_cache.json";
    private static final String RSS_URL = "https://www.zut.edu.pl/rssfeed-studenci";
    private static final String USOS_NEWS_FIELDS =
            "items[article[id|publication_date|title|headline_html|content_html|image_urls[720x405|360x203|original]]]|next_page|total";
    private static final Pattern IMG_PATTERN = Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);

    private Context context;

    public NewsRepository(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
        }
    }

    public NewsRepository() {
    }

    public List<NewsItem> loadNews() throws Exception {
        Exception networkError = null;
        try {
            return fetchFromNetwork();
        } catch (Exception e) {
            networkError = e;
            Log.w(TAG, "Network failed, trying cache: " + e.getMessage());
        }

        List<NewsItem> cached = loadFromDisk();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        if (networkError != null) {
            throw networkError;
        }
        return new ArrayList<>();
    }

    private List<NewsItem> fetchFromNetwork() throws Exception {
        List<NewsItem> rssItems = fetchFromRss();
        if (!rssItems.isEmpty()) {
            saveToDisk(rssItems);
            return rssItems;
        }

        List<NewsItem> usosItems = fetchFromUsos();
        saveToDisk(usosItems);
        return usosItems;
    }

    private List<NewsItem> fetchFromRss() throws Exception {
        Request request = new Request.Builder()
                .url(RSS_URL)
                .header("User-Agent", "ZUTnik-Android-News/2.0-RSS")
                .build();

        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + " przy pobieraniu RSS");
            }
            if (response.body() == null) {
                throw new RuntimeException("Pusta odpowiedź z RSS");
            }

            InputStream is = response.body().byteStream();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            doc.getDocumentElement().normalize();

            NodeList itemNodes = doc.getElementsByTagName("item");
            List<NewsItem> items = new ArrayList<>();
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Node itemNode = itemNodes.item(i);
                if (itemNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                String title = getChildText(itemNode, "title");
                String link = getChildText(itemNode, "link");
                String pub = getChildText(itemNode, "pubDate");
                String descHtml = fixImageUrls(getChildText(itemNode, "description"));
                String contentHtml = fixImageUrls(getChildTextNs(
                        itemNode,
                        "http://purl.org/rss/1.0/modules/content/",
                        "encoded"));

                String descriptionText = htmlToText(firstNonEmpty(descHtml, contentHtml));
                String snippet = buildSnippet(descriptionText);
                String thumbUrl = extractFirstImageUrl(firstNonEmpty(contentHtml, descHtml));

                NewsItem item = new NewsItem();
                item.id = i;
                item.title = firstNonEmpty(title);
                item.link = firstNonEmpty(link);
                item.pubDateRaw = firstNonEmpty(pub);
                item.date = formatRssDate(pub);
                item.snippet = snippet;
                item.descriptionHtml = sanitizeHtmlFragment(descHtml);
                item.descriptionText = descriptionText;
                item.contentHtml = sanitizeHtmlFragment(contentHtml);
                item.thumbUrl = thumbUrl;
                items.add(item);
            }
            return items;
        }
    }

    private List<NewsItem> fetchFromUsos() throws Exception {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("num", "30");
        params.put("fields", USOS_NEWS_FIELDS);
        JSONObject response = UsosApi.get("services/news/search", null, null, params);
        JSONArray itemsArray = response != null ? response.optJSONArray("items") : null;
        List<NewsItem> items = new ArrayList<>();
        if (itemsArray == null) {
            return items;
        }

        for (int i = 0; i < itemsArray.length(); i++) {
            JSONObject wrapper = itemsArray.optJSONObject(i);
            JSONObject article = wrapper != null ? wrapper.optJSONObject("article") : null;
            if (article == null) {
                article = wrapper;
            }
            if (article == null) {
                continue;
            }

            String idText = firstNonEmpty(article.optString("id", String.valueOf(i)));
            String descriptionHtml = sanitizeHtmlFragment(localizedField(article, "headline_html"));
            String contentHtml = sanitizeHtmlFragment(localizedField(article, "content_html"));
            String descriptionText = htmlToText(firstNonEmpty(descriptionHtml, contentHtml));

            NewsItem item = new NewsItem();
            item.id = safeInt(idText, i);
            item.title = firstNonEmpty(localizedField(article, "title"), "Aktualność " + idText);
            item.pubDateRaw = firstNonEmpty(article.optString("publication_date", ""));
            item.date = formatUsosDate(item.pubDateRaw);
            item.snippet = buildSnippet(descriptionText);
            item.link = "https://usosweb.zut.edu.pl/kontroler.php?_action=news/default&article_id=" + idText;
            item.descriptionHtml = descriptionHtml;
            item.descriptionText = descriptionText;
            item.contentHtml = contentHtml;
            item.thumbUrl = pickImageUrl(article.optJSONObject("image_urls"));
            items.add(item);
        }
        return items;
    }

    public static Bitmap downloadImage(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        Bitmap cached = ImageCache.getInstance().getFromMemory(url);
        if (cached != null) {
            return cached;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ZUTnik-Android-Images/2.0")
                .build();

        try (Response response = ZutnikNetwork.getClient().newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                byte[] data = response.body().bytes();
                if (data.length == 0) {
                    return null;
                }

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);

                int reqHeight = 480;
                int inSampleSize = 1;
                if (options.outHeight > reqHeight) {
                    final int halfHeight = options.outHeight / 2;
                    while ((halfHeight / inSampleSize) >= reqHeight) {
                        inSampleSize *= 2;
                    }
                }

                options.inSampleSize = inSampleSize;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap != null) {
                    ImageCache.getInstance().put(url, bitmap);
                    return bitmap;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error downloading image: " + url, e);
        }
        return null;
    }

    private String getChildText(Node parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equalsIgnoreCase(n.getNodeName())) {
                return n.getTextContent();
            }
        }
        return "";
    }

    private String getChildTextNs(Node parent, String namespaceUri, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            String ns = n.getNamespaceURI();
            String loc = n.getLocalName();
            String nn = n.getNodeName();
            boolean matchesNs = namespaceUri.equals(ns) && localName.equals(loc);
            boolean matchesFallback =
                    "content:encoded".equalsIgnoreCase(nn) || "encoded".equalsIgnoreCase(nn);

            if (matchesNs || matchesFallback) {
                return n.getTextContent();
            }
        }
        return "";
    }

    private String localizedField(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        Object value = obj.opt(key);
        if (value instanceof JSONObject) {
            JSONObject localized = (JSONObject) value;
            return firstNonEmpty(
                    localized.optString("pl", ""),
                    localized.optString("en", ""),
                    localized.optString("name", ""));
        }
        return firstNonEmpty(value instanceof String ? (String) value : "");
    }

    private String firstNonEmpty(String... values) {
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

    private String formatRssDate(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return "";
        }
        SimpleDateFormat inFmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        SimpleDateFormat outFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("pl", "PL"));
        try {
            Date d = inFmt.parse(rawValue.trim());
            return outFmt.format(d);
        } catch (ParseException e) {
            return rawValue;
        }
    }

    private String formatUsosDate(String rawValue) {
        String normalized = firstNonEmpty(rawValue);
        if (normalized.isEmpty()) {
            return "";
        }

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };

        for (String pattern : patterns) {
            try {
                SimpleDateFormat inFmt = new SimpleDateFormat(pattern, Locale.US);
                Date d = inFmt.parse(normalized);
                if (d != null) {
                    return new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("pl", "PL")).format(d);
                }
            } catch (ParseException ignored) {
            }
        }
        return normalized;
    }

    private String fixImageUrls(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Matcher matcher = Pattern.compile(
                "<img([^>]*?)src=[\"']([^\"']+)[\"']([^>]*?)>",
                Pattern.CASE_INSENSITIVE)
                .matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String normalized = normalizeAssetUrl(matcher.group(2));
            if (normalized == null) {
                normalized = matcher.group(2);
            }
            String replacement = "<img" + matcher.group(1) + "src=\"" + normalized + "\"" + matcher.group(3) + ">";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String extractFirstImageUrl(String html) {
        if (html == null || html.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = IMG_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return normalizeAssetUrl(matcher.group(1));
    }

    private String normalizeAssetUrl(String src) {
        if (src == null || src.trim().isEmpty()) {
            return null;
        }
        if (src.startsWith("http")) {
            return src;
        }
        if (src.startsWith("/")) {
            return "https://www.zut.edu.pl" + src;
        }
        return "https://www.zut.edu.pl/" + src;
    }

    private String pickImageUrl(JSONObject imageUrls) {
        if (imageUrls == null) {
            return null;
        }
        return firstNonEmpty(
                normalizeAssetUrl(imageUrls.optString("720x405", "")),
                normalizeAssetUrl(imageUrls.optString("360x203", "")),
                normalizeAssetUrl(imageUrls.optString("original", "")));
    }

    private String sanitizeHtmlFragment(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        String out = html
                .replaceAll("(?is)<script[\\s\\S]*?</script>", " ")
                .replaceAll("(?is)<style[\\s\\S]*?</style>", " ")
                .replaceAll("(?is)<(iframe|object|embed|form|input|button|svg|math)[\\s\\S]*?</\\1>", " ")
                .replaceAll("(?is)<(iframe|object|embed|form|input|button|svg|math)\\b[^>]*>", " ");
        return out;
    }

    private String htmlToText(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        CharSequence text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        return String.valueOf(text)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildSnippet(String descriptionText) {
        String source = firstNonEmpty(descriptionText);
        if (source.length() > 220) {
            return source.substring(0, 217) + "...";
        }
        return source;
    }

    private int safeInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(rawValue);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void saveToDisk(List<NewsItem> items) {
        if (context == null || items == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (NewsItem ni : items) {
                JSONObject o = new JSONObject();
                o.put("id", ni.id);
                o.put("title", ni.title);
                o.put("link", ni.link);
                o.put("pub", ni.pubDateRaw);
                o.put("date", ni.date);
                o.put("snp", ni.snippet);
                o.put("dh", ni.descriptionHtml);
                o.put("dt", ni.descriptionText);
                o.put("ch", ni.contentHtml);
                o.put("tu", ni.thumbUrl);
                arr.put(o);
            }
            try (FileOutputStream fos = context.openFileOutput(NEWS_CACHE_FILE, Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save news cache: " + e.getMessage());
        }
    }

    private List<NewsItem> loadFromDisk() {
        if (context == null) {
            return null;
        }
        try (FileInputStream fis = context.openFileInput(NEWS_CACHE_FILE)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            JSONArray arr = new JSONArray(sb.toString());
            List<NewsItem> meta = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                NewsItem ni = new NewsItem();
                ni.id = o.optInt("id");
                ni.title = o.optString("title");
                ni.link = o.optString("link");
                ni.pubDateRaw = o.optString("pub");
                ni.date = o.optString("date");
                ni.snippet = o.optString("snp");
                ni.descriptionHtml = o.optString("dh");
                ni.descriptionText = o.optString("dt");
                ni.contentHtml = o.optString("ch");
                ni.thumbUrl = o.optString("tu");
                meta.add(ni);
            }
            return meta;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load news cache: " + e.getMessage());
            return null;
        }
    }
}
