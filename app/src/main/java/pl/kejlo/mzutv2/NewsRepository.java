package pl.kejlo.mzutv2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
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

    private static final String TAG = "mZUTv2-NEWS";

    // Same RSS feed as in news.php
    private static final String RSS_URL = "https://www.zut.edu.pl/rssfeed-studenci";

    // Pattern for extracting <img src="...">
    private static final Pattern IMG_PATTERN =
            Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE);

    // --- Fetch news list (RSS) ---
    public List<NewsItem> loadNews() throws Exception {
        Request request = new Request.Builder()
                .url(RSS_URL)
                .header("User-Agent", "mZUTv2-Android-News/1.2-RSS")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
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
                String descHtml = getChildText(itemNode, "description");

                String contentHtml = getChildTextNs(
                        itemNode,
                        "http://purl.org/rss/1.0/modules/content/",
                        "encoded"
                );

                String descText = "";
                if (descHtml != null && !descHtml.trim().isEmpty()) {
                    descText = Html.fromHtml(descHtml, Html.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .trim();
                }

                String snippetSource = descText;
                if (snippetSource == null || snippetSource.trim().isEmpty()) {
                    snippetSource = "";
                }
                String snippet = snippetSource;
                if (snippet.length() > 220) {
                    snippet = snippet.substring(0, 217) + "…";
                }

                String thumbUrl = null;
                if (contentHtml != null && !contentHtml.trim().isEmpty()) {
                    Matcher m = IMG_PATTERN.matcher(contentHtml);
                    if (m.find()) {
                        String src = m.group(1);
                        thumbUrl = fixImageUrl(src);
                    }
                }

                String dateFmt = formatRssDate(pub);

                NewsItem ni = new NewsItem();
                ni.id = i;
                ni.title = title != null ? title : "";
                ni.link = link;
                ni.pubDateRaw = pub;
                ni.date = dateFmt;
                ni.snippet = snippet;
                ni.descriptionHtml = descHtml;
                ni.descriptionText = descText;
                ni.contentHtml = contentHtml;
                ni.thumbUrl = thumbUrl;

                items.add(ni);
            }

            return items;
        }
    }

    // --- New method: Image download (uses MzutNetwork) ---
    // Static for easy calls from Adapters: NewsRepository.downloadImage(url)
    public static Bitmap downloadImage(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        // 1. Check cache
        Bitmap cached = ImageMemoryCache.get(url);
        if (cached != null) {
            return cached;
        }

        // 2. Fetch via MzutNetwork (bypasses SSL error)
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "mZUTv2-Android-Images/1.0")
                .build();

        try (Response response = MzutNetwork.getClient().newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                // 3. Read bytes to allow double-decoding (bounds checks + actual decode)
                byte[] data = response.body().bytes();
                if (data.length == 0) return null;

                // 4. Decode bounds only
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);

                // 5. Calculate inSampleSize (Resize to max ~480px height)
                int reqHeight = 480;
                int inSampleSize = 1;
                if (options.outHeight > reqHeight) {
                    final int halfHeight = options.outHeight / 2;
                    while ((halfHeight / inSampleSize) >= reqHeight) {
                        inSampleSize *= 2;
                    }
                }

                // 6. Decode with sample size (RAM optimization)
                options.inSampleSize = inSampleSize;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);

                if (bitmap != null) {
                    // 7. Save to cache (ImageCache saves to disk as JPG now)
                    ImageMemoryCache.put(url, bitmap);
                    return bitmap;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd pobierania obrazka: " + url, e);
        }
        return null;
    }

    // --- Helpers ---

    private String getChildText(Node parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE &&
                    tagName.equalsIgnoreCase(n.getNodeName())) {
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
            boolean matchesFallback = "content:encoded".equalsIgnoreCase(nn) || "encoded".equalsIgnoreCase(nn);

            if (matchesNs || matchesFallback) {
                return n.getTextContent();
            }
        }
        return "";
    }

    private String formatRssDate(String pubDateRaw) {
        if (pubDateRaw == null || pubDateRaw.trim().isEmpty()) {
            return "";
        }
        SimpleDateFormat inFmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        SimpleDateFormat outFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("pl", "PL"));
        try {
            Date d = inFmt.parse(pubDateRaw.trim());
            return outFmt.format(d);
        } catch (ParseException e) {
            return pubDateRaw;
        }
    }

    private String fixImageUrl(String src) {
        if (src == null || src.isEmpty()) return src;
        if (src.startsWith("http")) return src;
        if (src.startsWith("/")) return "https://www.zut.edu.pl" + src;
        return "https://www.zut.edu.pl/" + src;
    }
}