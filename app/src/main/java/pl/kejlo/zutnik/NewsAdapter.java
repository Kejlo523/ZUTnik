package pl.kejlo.zutnik;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.ViewHolder> {

    private final List<NewsItem> items = new ArrayList<>();
    private final Context context;

    private static final java.util.concurrent.ExecutorService IMAGE_EXECUTOR = java.util.concurrent.Executors
            .newFixedThreadPool(4);
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public NewsAdapter(Context context) {
        this.context = context;
    }

    public void replaceItems(List<NewsItem> newsItems) {
        List<NewsItem> updatedItems = newsItems != null
                ? new ArrayList<>(newsItems)
                : new ArrayList<>();
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return items.size();
            }

            @Override
            public int getNewListSize() {
                return updatedItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return sameItem(items.get(oldItemPosition), updatedItems.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return sameContent(items.get(oldItemPosition), updatedItems.get(newItemPosition));
            }
        });

        items.clear();
        items.addAll(updatedItems);
        diffResult.dispatchUpdatesTo(this);
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
        bindThumbnail(h.thumb, n.thumbUrl);
        h.itemView.setOnClickListener(v -> openDetails(n));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean sameItem(NewsItem oldItem, NewsItem newItem) {
        if (oldItem == null || newItem == null) {
            return oldItem == newItem;
        }
        if (oldItem.id > 0 && newItem.id > 0) {
            return oldItem.id == newItem.id;
        }
        return Objects.equals(oldItem.link, newItem.link)
                && Objects.equals(oldItem.title, newItem.title);
    }

    private boolean sameContent(NewsItem oldItem, NewsItem newItem) {
        if (oldItem == null || newItem == null) {
            return oldItem == newItem;
        }
        return oldItem.id == newItem.id
                && Objects.equals(oldItem.title, newItem.title)
                && Objects.equals(oldItem.date, newItem.date)
                && Objects.equals(oldItem.pubDateRaw, newItem.pubDateRaw)
                && Objects.equals(oldItem.snippet, newItem.snippet)
                && Objects.equals(oldItem.link, newItem.link)
                && Objects.equals(oldItem.descriptionHtml, newItem.descriptionHtml)
                && Objects.equals(oldItem.descriptionText, newItem.descriptionText)
                && Objects.equals(oldItem.contentHtml, newItem.contentHtml)
                && Objects.equals(oldItem.thumbUrl, newItem.thumbUrl);
    }

    private void bindThumbnail(ImageView imageView, String url) {
        if (url == null || url.trim().isEmpty()) {
            imageView.setVisibility(View.GONE);
            imageView.setTag(null);
            imageView.setImageDrawable(null);
            return;
        }

        imageView.setVisibility(View.VISIBLE);
        imageView.setTag(url);

        Bitmap cached = ImageCache.getInstance().getFromMemory(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);
        loadThumbnail(imageView, url);
    }

    private void openDetails(NewsItem item) {
        Intent intent = new Intent(context, NewsDetailActivity.class);
        intent.putExtra("id", item.id);
        intent.putExtra("title", item.title);
        intent.putExtra("date", item.date);
        intent.putExtra("link", item.link);
        intent.putExtra("contentHtml", item.contentHtml);
        intent.putExtra("descriptionText", item.descriptionText);
        context.startActivity(intent);
        if (context instanceof AppCompatActivity) {
            ((AppCompatActivity) context).overridePendingTransition(0, 0);
        }
    }

    private void loadThumbnail(ImageView iv, String url) {
        if (iv == null || url == null) {
            return;
        }

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;

            try {
                Bitmap fromDisk = ImageCache.getInstance().getFromDisk(url);
                if (fromDisk != null) {
                    bitmap = fromDisk;
                } else {
                    bitmap = NewsRepository.downloadImage(url);
                }
            } catch (Exception ignored) {
            }

            final Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                if (finalBitmap == null) {
                    return;
                }

                Object tag = iv.getTag();
                if (!(tag instanceof String)) {
                    return;
                }

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
