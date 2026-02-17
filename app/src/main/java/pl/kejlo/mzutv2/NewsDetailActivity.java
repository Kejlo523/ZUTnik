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
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class NewsDetailActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    private static final String ZUT_BASE_URL = "https://www.zut.edu.pl";

    // Cache for the generated placeholder image (PNG bytes)
    private byte[] offlinePlaceholderBytes = null;

    private byte[] generateOfflinePlaceholder() {
        try {
            int drawableId = R.drawable.ic_newspaper;
            android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(this, drawableId);
            if (d != null) {
                int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96;
                int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96;
                if (w < 128) {
                    w *= 2;
                    h *= 2;
                }

                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                c.drawColor(0xFFEEEEEE);
                d.setBounds(20, 20, w - 20, h - 20);
                d.setTint(0xFFAAAAAA);
                d.draw(c);

                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                return bos.toByteArray();
            }
        } catch (Exception ignored) {
        }
        // Fallback
        String base64Grey = "iVBORw0KGgoAAAANSUhEUgAAAGQAAABkCAQAAADa6x0KAAAAZElEQVR42u3PQREAAAgEwS971rqWUDTi2uaAyUgZmIyUgclIGZiMlIHJSBmYjJSByUgZmIyUgclIGZiMlIHJSBmYjJSByUgZmIyUgclIGZiMlIHJSBmYjJSByUgZmIyUgclIGajBA17wAQLy6ZEvAAAAAElFTkSuQmCC";
        return android.util.Base64.decode(base64Grey, android.util.Base64.DEFAULT);
    }

    private Toolbar toolbar;
    private TextView tvTitle;
    private TextView tvDate;
    private TextView tvSource;
    private TextView tvFallback;
    private WebView webView;
    private LinearLayout contentRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_news_detail);
        ThemeManager.applySystemBars(this);

        contentRoot = findViewById(R.id.contentRoot);
        toolbar = findViewById(R.id.toolbarDetail);
        tvTitle = findViewById(R.id.tvNewsDetailTitle);
        tvDate = findViewById(R.id.tvNewsDetailDate);
        tvSource = findViewById(R.id.tvNewsDetailSource);
        tvFallback = findViewById(R.id.tvNewsDetailFallback);
        webView = findViewById(R.id.webNewsDetail);

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

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
        if (!TextUtils.isEmpty(date)) {
            tvDate.setText(date);
            tvDate.setVisibility(View.VISIBLE);
        } else {
            tvDate.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(link)) {
            tvSource.setText(R.string.news_detail_source_label);
            tvSource.setVisibility(View.VISIBLE);
            tvSource.setOnClickListener(v -> {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                startActivity(browser);
            });
        } else {
            tvSource.setText("");
            tvSource.setVisibility(View.GONE);
            tvSource.setOnClickListener(null);
        }

        if (!TextUtils.isEmpty(contentHtml)) {
            String fixed = fixImagePaths(contentHtml);
            String cleaned = cleanHtmlStyles(fixed);
            String styled = wrapInThemedTemplate(cleaned);

            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
                        android.webkit.WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) {
                        return super.shouldInterceptRequest(view, request);
                    }
                    String url = request.getUrl().toString();
                    String lower = url.toLowerCase();

                    // Intercept images to enforce resizing/compression/caching
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png") || lower.endsWith(".gif")
                            || lower.endsWith(".webp")) {

                        try {
                            // 1. Ensure it's in cache (download/resize if needed)
                            // This runs on background thread (WebView calls this on background usually)
                            if (NetworkStatusHelper.isNetworkAvailable(NewsDetailActivity.this)) {
                                NewsRepository.downloadImage(url);
                            }

                            // 2. Get stream from cache (it's saved as JPEG 50%)
                            java.io.InputStream is = ImageCache.getInstance().getStream(url);
                            if (is != null) {
                                // Always serving as jpeg per our cache logic
                                return new android.webkit.WebResourceResponse("image/jpeg", "UTF-8", is);
                            } else if (!NetworkStatusHelper.isNetworkAvailable(NewsDetailActivity.this)) {
                                // 3. Offline and missing from cache -> Placeholder (Icon)
                                // Use cached bytes to avoid "colossal lag" from repeated Bitmap compression
                                if (offlinePlaceholderBytes == null) {
                                    offlinePlaceholderBytes = generateOfflinePlaceholder();
                                }
                                if (offlinePlaceholderBytes != null) {
                                    return new android.webkit.WebResourceResponse(
                                            "image/png",
                                            "UTF-8",
                                            new java.io.ByteArrayInputStream(offlinePlaceholderBytes));
                                }
                            }
                        } catch (Exception e) {
                            // fall back to network
                        }
                    }
                    return super.shouldInterceptRequest(view, request);
                }
            });

            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);

            webView.loadDataWithBaseURL(
                    ZUT_BASE_URL,
                    styled,
                    "text/html",
                    "utf-8",
                    null);

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
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
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
                "src=\"" + ZUT_BASE_URL + "/$1\"");

        // src="fileadmin/..."
        out = out.replaceAll(
                "src=\"(fileadmin/[^\"]*)\"",
                "src=\"" + ZUT_BASE_URL + "/$1\"");

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

        // Get colors from resources (will automatically use -night in dark mode)
        int bg = ThemeManager.resolveColor(this, R.attr.mzBg);
        int text = ThemeManager.resolveColor(this, R.attr.mzText);
        int link = ThemeManager.resolveColor(this, R.attr.mzPrimary);
        int border = ThemeManager.resolveColor(this, R.attr.mzBorderStrong);

        String bgHex = toHtmlColor(bg);
        String textHex = toHtmlColor(text);
        String linkHex = toHtmlColor(link);
        String borderHex = toHtmlColor(border);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
                .append("<meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<style>")
                .append("body{margin:0;padding:10px 10px 20px 10px;")
                .append("background:").append(bgHex).append(";")
                .append("color:").append(textHex).append(";")
                .append("font-family:sans-serif;font-size:15px;line-height:1.62;word-wrap:break-word;}")
                .append("p{margin:0 0 12px 0;}")
                .append("a{color:").append(linkHex).append(";text-decoration:none;font-weight:600;}")
                .append("a:hover{text-decoration:underline;}")
                .append("img{max-width:100%;height:auto;border-radius:14px;")
                .append("border:1px solid ").append(borderHex).append(";margin:10px 0;}")
                .append("ul,ol{margin:0 0 12px 22px;padding:0;}")
                .append("li{margin-bottom:6px;}")
                .append("table{border-collapse:collapse;width:100%;margin:10px 0;")
                .append("border:1px solid ").append(borderHex).append(";}")
                .append("td,th{border:1px solid ").append(borderHex)
                .append(";padding:6px;font-size:13px;}")
                .append("blockquote{margin:10px 0;padding:8px 12px;border-left:3px solid ")
                .append(linkHex).append(";}")
                .append("h1,h2,h3,h4{margin:0 0 10px 0;font-weight:700;line-height:1.28;color:")
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
