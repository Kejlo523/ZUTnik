package pl.kejlo.mzutv2;

public class NewsItem {

    public int id;
    public String title;
    public String date;            // formatted date (dd.MM.yyyy HH:mm)
    public String pubDateRaw;      // raw pubDate from RSS
    public String snippet;         // short text snippet (no HTML)
    public String link;            // full article URL
    public String descriptionHtml; // <description> (HTML)
    public String descriptionText; // <description> (plain text)
    public String contentHtml;     // <content:encoded> – full article HTML
    public String thumbUrl;        // thumbnail URL (first <img> in content)
}
