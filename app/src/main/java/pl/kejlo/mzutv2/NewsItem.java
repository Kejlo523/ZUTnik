package pl.kejlo.mzutv2;

public class NewsItem {
    public int    id;
    public String title;
    public String date;          // "dd.MM.yyyy HH:mm"
    public String pubDateRaw;    // oryginalny pubDate z RSS
    public String snippet;       // skrócony opis (bez HTML)
    public String link;          // pełny URL artykułu

    // dodatkowo – jak w news.php:
    public String descriptionHtml;  // <description> (HTML)
    public String descriptionText;  // <description> (tekst)
    public String contentHtml;      // <content:encoded> – HTML całego wpisu
    public String thumbUrl;         // URL miniaturki (pierwszy <img> z contentHtml)
}
