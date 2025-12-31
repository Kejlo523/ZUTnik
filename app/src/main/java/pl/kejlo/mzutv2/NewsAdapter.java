package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.ViewHolder> {

    private final List<NewsItem> items;
    private final Context ctx;

    // Static executor for image loading across all adapter instances
    private static final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
            .newFixedThreadPool(4);
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    public NewsAdapter(Context ctx, List<NewsItem> items) {
        this.ctx = ctx;
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.news_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        NewsItem n = items.get(pos);
        h.title.setText(n.title);
        h.date.setText(n.date);
        h.snippet.setText(n.snippet);

        // Thumbnail on the right – try memory cache first
        if (n.thumbUrl != null && !n.thumbUrl.trim().isEmpty()) {
            h.thumb.setVisibility(View.VISIBLE);
            h.thumb.setTag(n.thumbUrl);

            // First, try to load from memory cache (FAST, safe for UI thread)
            Bitmap cached = ImageCache.getInstance().getFromMemory(n.thumbUrl);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                // Not in memory – clear and load async (checks disk -> downloads)
                h.thumb.setImageDrawable(null);
                loadThumbnail(h.thumb, n.thumbUrl);
            }
        } else {
            h.thumb.setVisibility(View.GONE);
            h.thumb.setTag(null);
            h.thumb.setImageDrawable(null);
        }

        // Click opens details screen in the app
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(ctx, NewsDetailActivity.class);
            i.putExtra("id", n.id);
            i.putExtra("title", n.title);
            i.putExtra("date", n.date);
            i.putExtra("link", n.link);
            i.putExtra("contentHtml", n.contentHtml);
            i.putExtra("descriptionText", n.descriptionText);
            ctx.startActivity(i);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // Thumbnail loader with ImageCache integration
    private void loadThumbnail(ImageView iv, String url) {
        if (iv == null || url == null)
            return;

        executor.execute(() -> {
            Bitmap bitmap = null;

            // 1. Try Disk Cache (background thread is safe for IO)
            try {
                Bitmap fromDisk = ImageCache.getInstance().getFromDisk(url);
                if (fromDisk != null) {
                    bitmap = fromDisk;
                } else {
                    // 2. Download via NewsRepository (uses MzutNetwork for SSL)
                    bitmap = NewsRepository.downloadImage(url);
                }
            } catch (Exception ignored) {
            }

            final Bitmap finalBitmap = bitmap;
            handler.post(() -> {
                if (finalBitmap == null)
                    return;

                Object tag = iv.getTag();
                if (!(tag instanceof String))
                    return;

                if (url.equals(tag)) {
                    iv.setImageBitmap(finalBitmap);
                }
            });
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title, date, snippet;
        ImageView thumb;

        ViewHolder(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.newsTitle);
            date = v.findViewById(R.id.newsDate);
            snippet = v.findViewById(R.id.newsSnippet);
            thumb = v.findViewById(R.id.newsThumb);
        }
    }
}
