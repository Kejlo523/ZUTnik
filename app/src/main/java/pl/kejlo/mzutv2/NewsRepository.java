package pl.kejlo.mzutv2;

import android.text.Html;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

public class NewsRepository {

    private static final String TAG = "mZUTv2-NEWS";

    // Same RSS feed as in news.php
    private static final String RSS_URL = "https://www.zut.edu.pl/rssfeed-studenci";

    // Pattern for extracting <img src="...">
    private static final Pattern IMG_PATTERN =
            Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE);

    public List<NewsItem> loadNews() throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(RSS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "mZUTv2-Android-News/1.2-RSS");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code + " przy pobieraniu RSS");
            }

            is = conn.getInputStream();

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

                // Full HTML from <content:encoded> (content namespace)
                String contentHtml = getChildTextNs(
                        itemNode,
                        "http://purl.org/rss/1.0/modules/content/",
                        "encoded"
                );

                // Plain text description from <description> (HTML stripped)
                String descText = "";
                if (descHtml != null && !descHtml.trim().isEmpty()) {
                    descText = Html.fromHtml(descHtml, Html.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .trim();
                }

                // Short snippet (~220 characters)
                String snippetSource = descText;
                if (snippetSource == null || snippetSource.trim().isEmpty()) {
                    snippetSource = "";
                }
                String snippet = snippetSource;
                if (snippet.length() > 220) {
                    snippet = snippet.substring(0, 217) + "…";
                }

                // Thumbnail – first <img> from contentHtml
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
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) { }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

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

    // Reads <content:encoded> using namespace
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

            boolean matchesNs =
                    namespaceUri.equals(ns) && localName.equals(loc);

            // Fallback if parser does not expose namespaces correctly
            boolean matchesFallback =
                    "content:encoded".equalsIgnoreCase(nn) ||
                            "encoded".equalsIgnoreCase(nn);

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

        // RSS example: "Wed, 20 Nov 2024 13:45:00 +0100"
        SimpleDateFormat inFmt =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        SimpleDateFormat outFmt =
                new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("pl", "PL"));
        try {
            Date d = inFmt.parse(pubDateRaw.trim());
            return outFmt.format(d);
        } catch (ParseException e) {
            Log.w(TAG, "Nie udało się sparsować daty RSS: " + pubDateRaw, e);
            return pubDateRaw;
        }
    }

    // Same logic as in PHP: relative -> absolute
    private String fixImageUrl(String src) {
        if (src == null || src.isEmpty()) {
            return src;
        }
        if (src.startsWith("http")) {
            return src;
        }

        if (src.startsWith("/")) {
            return "https://www.zut.edu.pl" + src;
        } else {
            return "https://www.zut.edu.pl/" + src;
        }
    }
}
