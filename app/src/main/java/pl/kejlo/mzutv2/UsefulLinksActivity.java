package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UsefulLinksActivity extends PhoneAwareActivity {

    private static final String PREF_OG_CACHE = "useful_links_og_cache";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ZUTnik-Android/2.0)";
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile(
            "<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ExecutorService bgExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(10))
            .build();

    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;
    private RecyclerView listLinks;
    private TextView tvEmpty;
    private SharedPreferences ogCachePrefs;
    private LinksAdapter adapter;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

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
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);
        toolbar = findViewById(R.id.toolbar);
        listLinks = findViewById(R.id.listLinks);
        tvEmpty = findViewById(R.id.tvLinksEmpty);

        if (toolbar != null) {
            toolbar.setTitle(R.string.nav_useful_links);
        }

        MainNavHelper.setup(
                this,
                drawerContentRoot,
                bottomNavigation,
                toolbar,
                MainNavHelper.Screen.USEFUL);

        listLinks.setLayoutManager(new LinearLayoutManager(this));
        listLinks.setItemAnimator(null);
        adapter = new LinksAdapter();
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

    @Override
    protected void onDestroy() {
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadAndSortLinksForUser() {
        List<LinkItem> links = buildAllLinks();
        if (links.isEmpty()) {
            adapter.replaceItems(links);
            setEmptyState(true);
            return;
        }

        Set<String> majorCodes = new HashSet<>();
        Set<String> facultyCodes = new HashSet<>();
        detectUserCodes(majorCodes, facultyCodes);

        for (LinkItem item : links) {
            item.sortWeight = resolveSortWeight(item, majorCodes, facultyCodes);
        }

        links.sort(Comparator
                .comparingInt((LinkItem item) -> item.sortWeight)
                .thenComparing(item -> item.title.toLowerCase(Locale.ROOT)));

        adapter.replaceItems(links);
        setEmptyState(false);
    }

    private void setEmptyState(boolean empty) {
        if (tvEmpty == null) {
            return;
        }
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            tvEmpty.setText(R.string.useful_links_empty);
        }
    }

    private void detectUserCodes(Set<String> majors, Set<String> faculties) {
        List<Study> studies = MzutSession.getInstance().getStudies();
        if (studies == null) {
            return;
        }

        for (Study study : studies) {
            if (study == null) {
                continue;
            }

            String label = study.toString();
            if (label == null) {
                continue;
            }

            String normalized = label.toLowerCase(Locale.ROOT);
            if (normalized.contains("informatyka")) {
                majors.add("INF");
                faculties.add("WI");
            }
            if (normalized.contains("ekonomia")) {
                majors.add("EKO");
                faculties.add("WNEIZ");
            }
            if (normalized.contains("mechanika") || normalized.contains("budowa maszyn")) {
                majors.add("MIB");
                faculties.add("WIMIM");
            }
            if (normalized.contains("elektrotechnika") || normalized.contains("automatyka")) {
                majors.add("ELE");
                faculties.add("WE");
            }
            if (normalized.contains("budownictwo") || normalized.contains("architektura")) {
                majors.add("BUD");
                faculties.add("WBIA");
            }
        }
    }

    private int resolveSortWeight(LinkItem item, Set<String> majors, Set<String> faculties) {
        if (item.scope == LinkScope.MAJOR
                && item.majorCode != null
                && majors.contains(item.majorCode)) {
            item.highlighted = true;
            return 0;
        }

        if (item.scope == LinkScope.FACULTY
                && item.facultyCode != null
                && faculties.contains(item.facultyCode)) {
            item.highlighted = true;
            return 1;
        }

        if (item.scope == LinkScope.GLOBAL) {
            return 2;
        }

        return 3;
    }

    private List<LinkItem> buildAllLinks() {
        List<LinkItem> links = new ArrayList<>();
        try {
            JSONArray rawLinks = new JSONArray(readRawTextResource(R.raw.useful_links));
            for (int i = 0; i < rawLinks.length(); i++) {
                JSONObject raw = rawLinks.optJSONObject(i);
                if (raw == null) {
                    continue;
                }

                String id = raw.optString("id", "");
                String title = raw.optString("title", "");
                String url = raw.optString("url", "");
                if (id.isEmpty() || title.isEmpty() || url.isEmpty()) {
                    continue;
                }

                links.add(new LinkItem(
                        id,
                        title,
                        url,
                        raw.optString("description", ""),
                        parseLinkScope(raw.optString("scope", "")),
                        emptyToNull(raw.optString("facultyCode", null)),
                        emptyToNull(raw.optString("majorCode", null))));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return links;
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
        StringBuilder builder = new StringBuilder();
        try (InputStream input = getResources().openRawResource(resId);
                InputStreamReader streamReader = new InputStreamReader(input, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(streamReader)) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void fetchOpenGraphData(LinkItem item) {
        if (item.metadataRequested) {
            return;
        }
        item.metadataRequested = true;

        if (applyCachedPreview(item)) {
            return;
        }

        bgExecutor.submit(() -> {
            try {
                Request request = new Request.Builder()
                        .url(item.url)
                        .header("User-Agent", USER_AGENT)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        markMetadataLoaded(item);
                        return;
                    }

                    String html = response.body().string();
                    item.previewTitle = firstNonEmpty(
                            extractMeta(html, "og:title"),
                            extractTitle(html));
                    item.previewImageUrl = emptyToNull(extractMeta(html, "og:image"));
                    savePreviewToCache(item);

                    if (item.previewImageUrl != null) {
                        downloadImageToCache(item.previewImageUrl);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            markMetadataLoaded(item);
        });
    }

    private boolean applyCachedPreview(LinkItem item) {
        String cachedJson = ogCachePrefs.getString(item.url, null);
        if (cachedJson == null) {
            return false;
        }

        try {
            JSONObject json = new JSONObject(cachedJson);
            item.previewTitle = emptyToNull(json.optString("title", null));
            item.previewImageUrl = emptyToNull(json.optString("image", null));
            item.metadataLoaded = true;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void savePreviewToCache(LinkItem item) {
        try {
            JSONObject json = new JSONObject();
            json.put("title", item.previewTitle);
            json.put("image", item.previewImageUrl);
            ogCachePrefs.edit().putString(item.url, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void markMetadataLoaded(LinkItem item) {
        item.metadataLoaded = true;
        mainHandler.post(() -> adapter.refreshItem(item));
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE_TAG_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return emptyToNull(matcher.group(1));
    }

    private String extractMeta(String html, String property) {
        String quotedProperty = Pattern.quote(property);
        Pattern pattern = Pattern.compile(
                "<meta\\s+(?:property=[\"']" + quotedProperty + "[\"']\\s+content=[\"'](.*?)[\"']"
                        + "|content=[\"'](.*?)[\"']\\s+property=[\"']" + quotedProperty + "[\"'])",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return emptyToNull(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    private String firstNonEmpty(String first, String second) {
        String firstValue = emptyToNull(first);
        if (firstValue != null) {
            return firstValue;
        }
        return emptyToNull(second);
    }

    private void downloadImageToCache(String url) {
        if (url == null || ImageCache.getInstance().getFromDisk(url) != null) {
            return;
        }

        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }

                byte[] bytes = response.body().bytes();
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    ImageCache.getInstance().put(url, bitmap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String formatDomain(String url) {
        String domain = url
                .replace("https://", "")
                .replace("http://", "")
                .replace("www.", "");
        int slash = domain.indexOf('/');
        if (slash >= 0) {
            domain = domain.substring(0, slash);
        }
        if (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain;
    }

    private String getDomainInitial(String url) {
        String domain = formatDomain(url);
        if (domain.isEmpty()) {
            return "?";
        }
        int dot = domain.indexOf('.');
        String host = dot > 0 ? domain.substring(0, dot) : domain;
        if (host.isEmpty()) {
            return domain.substring(0, 1).toUpperCase(Locale.ROOT);
        }
        return host.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private void openLink(View source, LinkItem item) {
        try {
            String url = WebLinkActivity.normalizeUrl(item.url);
            if (url == null) {
                Toast.makeText(this, R.string.web_link_no_url, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(source.getContext(), WebLinkActivity.class);
            intent.putExtra(
                    WebLinkActivity.EXTRA_TITLE,
                    item.previewTitle != null ? item.previewTitle : item.title);
            intent.putExtra(WebLinkActivity.EXTRA_URL, url);
            source.getContext().startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.web_link_open_external_error, Toast.LENGTH_SHORT).show();
        }
    }

    enum LinkScope {
        GLOBAL,
        FACULTY,
        MAJOR,
        OTHER
    }

    static final class LinkItem {
        final String id;
        final String title;
        final String url;
        final String description;
        final LinkScope scope;
        final String facultyCode;
        final String majorCode;

        int sortWeight = 3;
        boolean highlighted;
        boolean metadataRequested;
        boolean metadataLoaded;
        String previewTitle;
        String previewImageUrl;

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

        boolean sameItem(@NonNull LinkItem other) {
            return id.equals(other.id);
        }

        boolean sameContent(@NonNull LinkItem other) {
            return title.equals(other.title)
                    && url.equals(other.url)
                    && Objects.equals(description, other.description)
                    && scope == other.scope
                    && Objects.equals(facultyCode, other.facultyCode)
                    && Objects.equals(majorCode, other.majorCode)
                    && sortWeight == other.sortWeight
                    && highlighted == other.highlighted
                    && metadataRequested == other.metadataRequested
                    && metadataLoaded == other.metadataLoaded
                    && Objects.equals(previewTitle, other.previewTitle)
                    && Objects.equals(previewImageUrl, other.previewImageUrl);
        }
    }

    private final class LinksAdapter extends RecyclerView.Adapter<LinksAdapter.ViewHolder> {

        private final List<LinkItem> data = new ArrayList<>();

        void replaceItems(List<LinkItem> newItems) {
            List<LinkItem> updatedItems = newItems != null
                    ? new ArrayList<>(newItems)
                    : new ArrayList<>();
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return data.size();
                }

                @Override
                public int getNewListSize() {
                    return updatedItems.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return data.get(oldItemPosition).sameItem(updatedItems.get(newItemPosition));
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return data.get(oldItemPosition).sameContent(updatedItems.get(newItemPosition));
                }
            });

            data.clear();
            data.addAll(updatedItems);
            diffResult.dispatchUpdatesTo(this);
        }

        void refreshItem(LinkItem item) {
            if (listLinks == null) {
                return;
            }
            listLinks.post(() -> {
                int index = data.indexOf(item);
                if (index >= 0) {
                    notifyItemChanged(index);
                }
            });
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.row_useful_link_card, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LinkItem item = data.get(position);
            if (!item.metadataRequested) {
                fetchOpenGraphData(item);
            }

            holder.title.setText(item.title);
            String description = item.description != null ? item.description.trim() : "";
            if (description.isEmpty()) {
                holder.desc.setVisibility(View.GONE);
            } else {
                holder.desc.setVisibility(View.VISIBLE);
                holder.desc.setText(description);
            }
            holder.url.setText(formatDomain(item.url));
            holder.badge.setVisibility(item.highlighted ? View.VISIBLE : View.GONE);
            bindPreviewImage(holder, item);
            holder.itemView.setOnClickListener(view -> openLink(view, item));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private void bindPreviewImage(@NonNull ViewHolder holder, @NonNull LinkItem item) {
            holder.loading.setVisibility(View.GONE);
            holder.image.setVisibility(View.GONE);
            holder.placeholder.setVisibility(View.GONE);

            if (item.previewImageUrl != null) {
                Bitmap memoryBitmap = ImageCache.getInstance().getFromMemory(item.previewImageUrl);
                if (memoryBitmap != null) {
                    showPreviewImage(holder, memoryBitmap);
                    return;
                }

                holder.loading.setVisibility(View.VISIBLE);
                bgExecutor.submit(() -> {
                    Bitmap loadedBitmap = ImageCache.getInstance().getFromDisk(item.previewImageUrl);
                    mainHandler.post(() -> {
                        int adapterPosition = holder.getAdapterPosition();
                        if (adapterPosition == RecyclerView.NO_POSITION
                                || adapterPosition >= data.size()
                                || data.get(adapterPosition) != item) {
                            return;
                        }

                        if (loadedBitmap != null) {
                            showPreviewImage(holder, loadedBitmap);
                        } else if (item.metadataLoaded) {
                            showPreviewPlaceholder(holder, item);
                        } else {
                            holder.loading.setVisibility(View.VISIBLE);
                        }
                    });
                });
                return;
            }

            if (!item.metadataLoaded) {
                holder.loading.setVisibility(View.VISIBLE);
                return;
            }

            showPreviewPlaceholder(holder, item);
        }

        private void showPreviewImage(@NonNull ViewHolder holder, @NonNull Bitmap bitmap) {
            holder.loading.setVisibility(View.GONE);
            holder.placeholder.setVisibility(View.GONE);
            holder.image.setVisibility(View.VISIBLE);
            holder.image.setImageBitmap(bitmap);
        }

        private void showPreviewPlaceholder(@NonNull ViewHolder holder, @NonNull LinkItem item) {
            holder.loading.setVisibility(View.GONE);
            holder.image.setVisibility(View.GONE);
            holder.placeholder.setVisibility(View.VISIBLE);

            String initial = getDomainInitial(item.url);
            if (initial.isEmpty() || "?".equals(initial)) {
                holder.placeholderLetter.setVisibility(View.GONE);
                holder.placeholderIcon.setVisibility(View.VISIBLE);
            } else {
                holder.placeholderLetter.setVisibility(View.VISIBLE);
                holder.placeholderLetter.setText(initial);
                holder.placeholderIcon.setVisibility(View.GONE);
            }
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView desc;
            final TextView url;
            final TextView badge;
            final ImageView image;
            final View placeholder;
            final TextView placeholderLetter;
            final ImageView placeholderIcon;
            final ProgressBar loading;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.linkTitle);
                desc = itemView.findViewById(R.id.linkDesc);
                url = itemView.findViewById(R.id.linkUrl);
                badge = itemView.findViewById(R.id.linkBadge);
                image = itemView.findViewById(R.id.linkImage);
                placeholder = itemView.findViewById(R.id.linkPlaceholder);
                placeholderLetter = itemView.findViewById(R.id.linkPlaceholderLetter);
                placeholderIcon = itemView.findViewById(R.id.linkPlaceholderIcon);
                loading = itemView.findViewById(R.id.linkImageLoading);
            }
        }
    }
}
