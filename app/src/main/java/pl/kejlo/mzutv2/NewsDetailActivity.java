package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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

public class NewsDetailActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private TextView tvTitle, tvDate, tvSource, tvFallback;
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
            getSupportActionBar().setTitle("Wpis");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        Intent i = getIntent();
        String title = i.getStringExtra("title");
        String date = i.getStringExtra("date");
        String link = i.getStringExtra("link");
        String contentHtml = i.getStringExtra("contentHtml");
        String descriptionTx = i.getStringExtra("descriptionText");

        tvTitle.setText(!TextUtils.isEmpty(title) ? title : "");
        tvDate.setText(!TextUtils.isEmpty(date) ? date : "");

        if (!TextUtils.isEmpty(link)) {
            tvSource.setText("Źródło: zut.edu.pl");
            tvSource.setOnClickListener(v -> {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                startActivity(browser);
            });
        } else {
            tvSource.setText("");
        }

        if (!TextUtils.isEmpty(contentHtml)) {
            // 1) Fix image paths
            String fixed = fixImagePaths(contentHtml);
            // 2) Clean inline styles (background, color:white, etc.)
            String cleaned = cleanHtmlStyles(fixed);
            // 3) Wrap in dark theme template
            String styled = wrapInDarkTemplate(cleaned);

            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setWebViewClient(new WebViewClient());
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);

            webView.loadDataWithBaseURL(
                    "https://www.zut.edu.pl",
                    styled,
                    "text/html",
                    "utf-8",
                    null
            );

            webView.setVisibility(WebView.VISIBLE);
            tvFallback.setVisibility(TextView.GONE);

        } else if (!TextUtils.isEmpty(descriptionTx)) {
            // Fallback – plain text from descriptionText
            webView.setVisibility(WebView.GONE);
            tvFallback.setVisibility(TextView.VISIBLE);
            tvFallback.setText(descriptionTx);

        } else {
            webView.setVisibility(WebView.GONE);
            tvFallback.setVisibility(TextView.VISIBLE);
            tvFallback.setText("Brak treści w kanale RSS dla tego wpisu.");
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
                "src=\"https://www.zut.edu.pl/$1\""
        );

        // src="fileadmin/..."
        out = out.replaceAll(
                "src=\"(fileadmin/[^\"]*)\"",
                "src=\"https://www.zut.edu.pl/$1\""
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
    private String wrapInDarkTemplate(String innerHtml) {
        if (innerHtml == null) {
            innerHtml = "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
                .append("<meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<style>")
                // base
                .append("body{margin:0;padding:0 0 16px 0;background:#020617;color:#e5e7eb;font-family:sans-serif;font-size:13px;line-height:1.5;}")
                .append("p{margin:6px 0;}")
                .append("a{color:#60a5fa;text-decoration:none;}")
                .append("a:hover{text-decoration:underline;}")
                // images
                .append("img{max-width:100%;height:auto;border-radius:12px;border:1px solid #111827;margin:8px 0;}")
                // lists
                .append("ul,ol{margin:6px 0 6px 20px;}")
                // tables
                .append("table{border-collapse:collapse;width:100%;margin:6px 0;border:1px solid #111827;}")
                .append("td,th{border:1px solid #111827;padding:4px;font-size:12px;}")
                // headings
                .append("h1,h2,h3,h4{margin:8px 0;font-weight:600;color:#f9fafb;}")
                .append("</style>")
                .append("</head><body>")
                .append(innerHtml)
                .append("</body></html>");

        return sb.toString();
    }
}
