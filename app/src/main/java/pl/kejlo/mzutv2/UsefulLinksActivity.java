package pl.kejlo.mzutv2;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

/**
 * "Useful links" screen.
 *
 * Assumptions:
 * - Shows selected, most frequently used links (global + faculty + major).
 * - Links matched to the user's majors / faculties go to the top,
 * remaining links are below.
 * - No spinners / selectors – priority is based on data from MzutSession.
 *
 * Expected layout: res/layout/activity_useful_links.xml
 * - DrawerLayout @+id/drawerLayout
 * - NavigationView @+id/navigationView
 * - Toolbar @+id/toolbar
 * - RecyclerView @+id/listLinks
 * - TextView @+id/tvLinksEmpty (optional "no data" label)
 */
public class UsefulLinksActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private RecyclerView listLinks;
    private TextView tvEmpty;

    private ExecutorService bgExecutor = Executors.newFixedThreadPool(4);
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private SharedPreferences ogCachePrefs;
    private static final String PREF_OG_CACHE = "useful_links_og_cache";

    private LinksAdapter adapter;
    private final List<LinkItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_useful_links);
        ThemeManager.applySystemBars(this);

        ImageCache.init(getApplicationContext());
        ogCachePrefs = getSharedPreferences(PREF_OG_CACHE, MODE_PRIVATE);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);
        listLinks = findViewById(R.id.listLinks);
        tvEmpty = findViewById(R.id.tvLinksEmpty);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        if (toolbar != null) {
            toolbar.setTitle(R.string.nav_useful_links);
        }

        // NavDrawer – use "links" menu item
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "links");

        listLinks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LinksAdapter(items);
        listLinks.setAdapter(adapter);

        loadAndSortLinksForUser();
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

    // Main logic

    private void loadAndSortLinksForUser() {
        // 1) Build full (but already trimmed) list of links
        List<LinkItem> all = buildAllLinks();

        if (all.isEmpty()) {
            if (tvEmpty != null) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.useful_links_empty);
            }
            return;
        }

        // 2) Read user's majors and faculty codes
        Set<String> majorCodesOfUser = new HashSet<>();
        Set<String> facultyCodesOfUser = new HashSet<>();
        detectUserCodes(majorCodesOfUser, facultyCodesOfUser);

        // 3) Compute "weight" of each link for this user
        for (LinkItem li : all) {
            li.priorityWeight = computeWeightForUser(li, majorCodesOfUser, facultyCodesOfUser);
        }

        // 4) Sort: first links matching the user, then global, then the rest
        Collections.sort(all, new Comparator<LinkItem>() {
            @Override
            public int compare(LinkItem a, LinkItem b) {
                int w = Integer.compare(a.priorityWeight, b.priorityWeight);
                if (w != 0) {
                    return w;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });

        items.clear();
        items.addAll(all);
        adapter.notifyDataSetChanged();

        if (tvEmpty != null) {
            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Detects, based on current studies:
     * - major codes (INF, EKO, ...),
     * - faculty codes (WI, WNEIZ, ...).
     */
    private void detectUserCodes(Set<String> majors, Set<String> faculties) {
        MzutSession s = MzutSession.getInstance();
        List<Study> studies = s.getStudies();

        if (studies == null) {
            return;
        }

        for (Study st : studies) {
            if (st == null) {
                continue;
            }
            String label = st.toString();
            if (label == null) {
                continue;
            }
            String l = label.toLowerCase(Locale.ROOT);

            // Computer Science (WI)
            if (l.contains("informatyka")) {
                majors.add("INF");
                faculties.add("WI");
            }

            // Economics (WNEIZ)
            if (l.contains("ekonomia")) {
                majors.add("EKO");
                faculties.add("WNEIZ");
            }

            // Mechanical Engineering (WIMiM)
            if (l.contains("mechanika") || l.contains("budowa maszyn")) {
                majors.add("MIB");
                faculties.add("WIMIM");
            }

            // Electrical Engineering (WE)
            if (l.contains("elektrotechnika") || l.contains("automatyka")) {
                majors.add("ELE");
                faculties.add("WE");
            }

            // Architecture and Civil Engineering (WBiA)
            if (l.contains("budownictwo") || l.contains("architektura")) {
                majors.add("BUD");
                faculties.add("WBIA");
            }
        }
    }

    /**
     * Link weight – lower value means higher position in the list.
     * 0 – matches user's MAJOR,
     * 1 – matches user's FACULTY,
     * 2 – GLOBAL,
     * 3 – everything else.
     */
    private int computeWeightForUser(LinkItem li,
            Set<String> majors,
            Set<String> faculties) {

        if (li.scope == LinkScope.MAJOR && li.majorCode != null &&
                majors.contains(li.majorCode)) {
            li.highlight = true;
            return 0;
        }

        if (li.scope == LinkScope.FACULTY && li.facultyCode != null &&
                faculties.contains(li.facultyCode)) {
            li.highlight = true;
            return 1;
        }

        if (li.scope == LinkScope.GLOBAL) {
            return 2;
        }

        return 3;
    }
    /** Builds the list of useful links. */
    private List<LinkItem> buildAllLinks() {
        List<LinkItem> list = new ArrayList<>();
        try {
            JSONArray links = new JSONArray(readRawTextResource(R.raw.useful_links));
            for (int i = 0; i < links.length(); i++) {
                JSONObject raw = links.optJSONObject(i);
                if (raw == null) {
                    continue;
                }

                String id = raw.optString("id", "");
                String title = raw.optString("title", "");
                String url = raw.optString("url", "");
                String description = raw.optString("description", "");
                LinkScope scope = parseLinkScope(raw.optString("scope", ""));
                String facultyCode = emptyToNull(raw.optString("facultyCode", null));
                String majorCode = emptyToNull(raw.optString("majorCode", null));

                if (id.isEmpty() || title.isEmpty() || url.isEmpty()) {
                    continue;
                }

                list.add(new LinkItem(
                        id,
                        title,
                        url,
                        description,
                        scope,
                        facultyCode,
                        majorCode));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private LinkScope parseLinkScope(String rawScope) {
        if (rawScope == null) {
            return LinkScope.OTHER;
        }
        try {
            return LinkScope.valueOf(rawScope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LinkScope.OTHER;
        }
    }

    private String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    private String readRawTextResource(int resId) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getResources().openRawResource(resId);
                InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(isr)) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void fetchOgData(LinkItem item) {
        if (item.fetched) return;
        item.fetched = true; // Mark as started to avoid duplicate calls

        // 1. Check SharedPreferences cache first
        String cachedJson = ogCachePrefs.getString(item.url, null);
        if (cachedJson != null) {
            try {
                JSONObject json = new JSONObject(cachedJson);
                item.ogTitle = json.optString("title", null);
                item.ogDescription = json.optString("description", null);
                item.ogImageUrl = json.optString("image", null);
                // Refresh item in adapter on main thread
                mainHandler.post(() -> adapter.notifyDataSetChanged());
                return;
            } catch (Exception e) {
                // cache corrupted
            }
        }

        bgExecutor.submit(() -> {
            try {
                Request request = new Request.Builder()
                        .url(item.url)
                        .header("User-Agent", "Mozilla/5.0 (compatible; mZUTv2/1.0)")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) return;
                    String html = response.body().string();

                    // Simple Regex parsing for <meta property="og:..." content="..." />
                    item.ogTitle = extractMeta(html, "og:title");
                    item.ogDescription = extractMeta(html, "og:description");
                    item.ogImageUrl = extractMeta(html, "og:image");

                    // Fallback to <title> if og:title missing
                    if (item.ogTitle == null) {
                        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
                        if (m.find()) item.ogTitle = m.group(1);
                    }

                    // Save to cache
                    JSONObject json = new JSONObject();
                    json.put("title", item.ogTitle);
                    json.put("description", item.ogDescription);
                    json.put("image", item.ogImageUrl);
                    ogCachePrefs.edit().putString(item.url, json.toString()).apply();

                    // If image URL found, pre-download it to cache
                    if (item.ogImageUrl != null) {
                        downloadImageToCache(item.ogImageUrl);
                    }

                    mainHandler.post(() -> adapter.notifyDataSetChanged());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String extractMeta(String html, String property) {
        // Matches <meta property="og:image" content="..." /> OR <meta content="..." property="og:image" />
        // Simplistic approach but works for most sites
        Pattern p = Pattern.compile("<meta\\s+(?:property=[\"']" + property + "[\"']\\s+content=[\"'](.*?)[\"']|content=[\"'](.*?)[\"']\\s+property=[\"']" + property + "[\"'])", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private void downloadImageToCache(String url) {
        if (url == null) return;
        if (ImageCache.getInstance().getFromDisk(url) != null) return; // already cached

        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    byte[] bytes = response.body().bytes();
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        ImageCache.getInstance().put(url, bmp);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Model + adapter

    enum LinkScope {
        GLOBAL,
        FACULTY,
        MAJOR,
        OTHER
    }

    static class LinkItem {
        String id;
        String title;
        String url;
        String description;
        LinkScope scope;
        String facultyCode; // e.g. WI, WNEIZ, WIMIM
        String majorCode; // e.g. INF, EKO, MIB

        int priorityWeight = 3;
        boolean highlight = false;

        // Async fetched data
        boolean fetched = false;
        String ogTitle;
        String ogDescription;
        String ogImageUrl;

        LinkItem(String id,
                String title,
                String url,
                String description,
                LinkScope scope,
                String facultyCode,
                String majorCode) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.description = description;
            this.scope = scope;
            this.facultyCode = facultyCode;
            this.majorCode = majorCode;
        }
    }

    private class LinksAdapter extends RecyclerView.Adapter<LinksAdapter.VH> {

        private final List<LinkItem> data;

        LinksAdapter(List<LinkItem> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.row_useful_link_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            LinkItem li = data.get(position);

            // Trigger fetch if not yet started
            if (!li.fetched) {
                fetchOgData(li);
            }

            // Title: always use manual title
            h.title.setText(li.title);

            // Description: always use manual description
            h.desc.setText(li.description != null ? li.description : "");

            // Source URL (domain only)
            String domain = li.url.replace("https://", "").replace("http://", "").replace("www.", "");
            if (domain.endsWith("/")) domain = domain.substring(0, domain.length() - 1);
            h.url.setText(domain);

            // Badge
            if (li.highlight) {
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }

            // Image handling
            h.loading.setVisibility(View.GONE);
            h.image.setImageResource(R.drawable.bg_header_gradient); // default placeholder

            if (li.ogImageUrl != null) {
                Bitmap bmp = ImageCache.getInstance().getFromMemory(li.ogImageUrl);
                if (bmp == null) {
                    // Check disk async
                    bgExecutor.submit(() -> {
                        Bitmap diskBmp = ImageCache.getInstance().getFromDisk(li.ogImageUrl);
                        if (diskBmp != null) {
                             mainHandler.post(() -> {
                                 int pos = h.getAdapterPosition();
                                 if (pos != RecyclerView.NO_POSITION && pos < data.size() && data.get(pos) == li) {
                                     h.image.setImageBitmap(diskBmp);
                                 }
                             });
                        } else {
                             // Not on disk, maybe downloading? show loading
                             mainHandler.post(() -> {
                                 int pos = h.getAdapterPosition();
                                 if (pos != RecyclerView.NO_POSITION && pos < data.size() && data.get(pos) == li) {
                                     h.loading.setVisibility(View.VISIBLE);
                                 }
                             });
                        }
                    });
                } else {
                    h.image.setImageBitmap(bmp);
                }
            } else {
                // No image URL yet (or failed), show placeholder or hide image area?
                // Keeping placeholder for consistent layout
                if (!li.fetched) {
                     h.loading.setVisibility(View.VISIBLE);
                }
            }

            h.itemView.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(v.getContext(), WebLinkActivity.class);
                    i.putExtra(WebLinkActivity.EXTRA_TITLE, li.ogTitle != null ? li.ogTitle : li.title);
                    i.putExtra(WebLinkActivity.EXTRA_URL, li.url);
                    v.getContext().startActivity(i);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public int getItemCount() {
            return data != null ? data.size() : 0;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView title;
            TextView desc;
            TextView url;
            TextView badge;
            ImageView image;
            ProgressBar loading;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.linkTitle);
                desc = itemView.findViewById(R.id.linkDesc);
                url = itemView.findViewById(R.id.linkUrl);
                badge = itemView.findViewById(R.id.linkBadge);
                image = itemView.findViewById(R.id.linkImage);
                loading = itemView.findViewById(R.id.linkImageLoading);
            }
        }
    }
}
