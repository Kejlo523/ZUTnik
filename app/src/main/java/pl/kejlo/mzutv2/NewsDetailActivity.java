package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

public class NewsDetailActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
    private static final String ZUT_BASE_URL = "https://www.zut.edu.pl";

    private Toolbar toolbar;
    private TextView tvTitle;
    private TextView tvDate;
    private TextView tvSource;
    private TextView tvFallback;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news_detail);

        toolbar = findViewById(R.id.toolbarDetail);
        tvTitle = findViewById(R.id.tvNewsDetailTitle);
        tvDate = findViewById(R.id.tvNewsDetailDate);
        tvSource = findViewById(R.id.tvNewsDetailSource);
        tvFallback = findViewById(R.id.tvNewsDetailFallback);
        webView = findViewById(R.id.webNewsDetail);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.news_detail_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        Intent intent = getIntent();
        String title = intent.getStringExtra("title");
        String date = intent.getStringExtra("date");
        String link = intent.getStringExtra("link");
        String contentHtml = intent.getStringExtra("contentHtml");
        String descriptionText = intent.getStringExtra("descriptionText");

        tvTitle.setText(!TextUtils.isEmpty(title) ? title : "");
        tvDate.setText(!TextUtils.isEmpty(date) ? date : "");

        if (!TextUtils.isEmpty(link)) {
            tvSource.setText(R.string.news_detail_source_label);
            tvSource.setOnClickListener(v -> {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                startActivity(browser);
            });
        } else {
            tvSource.setText("");
        }

        if (!TextUtils.isEmpty(contentHtml)) {
            String fixed = fixImagePaths(contentHtml);
            String cleaned = cleanHtmlStyles(fixed);
            String styled = wrapInThemedTemplate(cleaned);

            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setWebViewClient(new WebViewClient());

            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);

            webView.loadDataWithBaseURL(
                    ZUT_BASE_URL,
                    styled,
                    "text/html",
                    "utf-8",
                    null
            );

            webView.setVisibility(WebView.VISIBLE);
            tvFallback.setVisibility(TextView.GONE);

        } else if (!TextUtils.isEmpty(descriptionText)) {
            webView.setVisibility(WebView.GONE);
            tvFallback.setVisibility(TextView.VISIBLE);
            tvFallback.setText(descriptionText);

        } else {
            webView.setVisibility(WebView.GONE);
            tvFallback.setVisibility(TextView.VISIBLE);
            tvFallback.setText(R.string.news_detail_no_content);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Replace relative src="fileadmin/..." with absolute ZUT URLs
    private String fixImagePaths(String html) {
        if (html == null) {
            return "";
        }
        String out = html;

        // src="/fileadmin/..."
        out = out.replaceAll(
                "src=\"/(fileadmin/[^\"]*)\"",
                "src=\"" + ZUT_BASE_URL + "/$1\""
        );

        // src="fileadmin/..."
        out = out.replaceAll(
                "src=\"(fileadmin/[^\"]*)\"",
                "src=\"" + ZUT_BASE_URL + "/$1\""
        );

        return out;
    }

    // Remove unwanted inline styles (background, white text, etc.) for dark mode
    private String cleanHtmlStyles(String html) {
        if (html == null) {
            return "";
        }

        String out = html;

        // Remove all backgrounds
        out = out.replaceAll("background[^:>]*:\\s*[^;\"']+;?", "");
        out = out.replaceAll("background-color:\\s*[^;\"']+;?", "");

        // Remove white text color
        out = out.replaceAll("color:\\s*#?fff[^;>]*;?", "");
        out = out.replaceAll("color:\\s*white[^;>]*;?", "");

        // Remove empty style attributes
        out = out.replaceAll("style=\"\\s*\"", "");

        return out;
    }

    // Wrap content in a simple dark-themed HTML template
    private String wrapInThemedTemplate(String innerHtml) {
        if (innerHtml == null) {
            innerHtml = "";
        }

        // pobranie kolorów z resources (automatycznie weźmie -night w dark mode)
        int bg = ContextCompat.getColor(this, R.color.mz_bg);
        int text = ContextCompat.getColor(this, R.color.mz_text);
        int link = ContextCompat.getColor(this, R.color.mz_primary);
        int border = ContextCompat.getColor(this, R.color.mz_border_strong);

        String bgHex = toHtmlColor(bg);
        String textHex = toHtmlColor(text);
        String linkHex = toHtmlColor(link);
        String borderHex = toHtmlColor(border);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
                .append("<meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<style>")
                // base
                .append("body{margin:0;padding:0 0 16px 0;")
                .append("background:").append(bgHex).append(";")
                .append("color:").append(textHex).append(";")
                .append("font-family:sans-serif;font-size:13px;line-height:1.5;}")
                .append("p{margin:6px 0;}")
                .append("a{color:").append(linkHex).append(";text-decoration:none;}")
                .append("a:hover{text-decoration:underline;}")
                // images
                .append("img{max-width:100%;height:auto;border-radius:12px;")
                .append("border:1px solid ").append(borderHex).append(";margin:8px 0;}")
                // lists
                .append("ul,ol{margin:6px 0 6px 20px;}")
                // tables
                .append("table{border-collapse:collapse;width:100%;margin:6px 0;")
                .append("border:1px solid ").append(borderHex).append(";}")
                .append("td,th{border:1px solid ").append(borderHex)
                .append(";padding:4px;font-size:12px;}")
                // headings
                .append("h1,h2,h3,h4{margin:8px 0;font-weight:600;color:")
                .append(textHex).append(";}")
                .append("</style>")
                .append("</head><body>")
                .append(innerHtml)
                .append("</body></html>");

        return sb.toString();
    }

    // pomocnicza
    private String toHtmlColor(int color) {
        // obetnij alpha, WebView i tak tego nie potrzebuje
        return String.format("#%06X", (0xFFFFFF & color));
    }
}
