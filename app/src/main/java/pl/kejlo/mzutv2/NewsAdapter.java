package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.ViewHolder> {

    private final List<NewsItem> items;
    private final Context ctx;

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

        // miniaturka po prawej – najpierw CACHE
        if (n.thumbUrl != null && !n.thumbUrl.trim().isEmpty()) {
            h.thumb.setVisibility(View.VISIBLE);
            h.thumb.setTag(n.thumbUrl);

            // 1️⃣ spróbuj z pamięci
            Bitmap cached = ImageMemoryCache.get(n.thumbUrl);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                // 2️⃣ brak w cache – wyczyść i pobierz raz
                h.thumb.setImageDrawable(null);
                new ThumbLoader(h.thumb, n.thumbUrl)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, n.thumbUrl);
            }
        } else {
            h.thumb.setVisibility(View.GONE);
            h.thumb.setTag(null);
            h.thumb.setImageDrawable(null);
        }

        // klik – przejście do szczegółów w apce
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

    // ładowanie miniatury – z dopięciem do ImageMemoryCache
    private static class ThumbLoader extends AsyncTask<String, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final String url;

        ThumbLoader(ImageView iv, String url) {
            this.imageViewRef = new WeakReference<>(iv);
            this.url = url;
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            String src = params[0];

            // 1️⃣ jeszcze raz spróbuj cache (gdyby ktoś inny już pobrał)
            Bitmap fromCache = ImageMemoryCache.get(src);
            if (fromCache != null) {
                return fromCache;
            }

            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                URL u = new URL(src);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "mZUTv2-Android-Img/1.0");
                int code = conn.getResponseCode();
                if (code != 200) return null;
                is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);

                // 2️⃣ zapis do cache
                if (bmp != null) {
                    ImageMemoryCache.put(src, bmp);
                }
                return bmp;
            } catch (Exception ignore) {
                return null;
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap == null) return;
            ImageView iv = imageViewRef.get();
            if (iv == null) return;
            Object tag = iv.getTag();
            if (!(tag instanceof String)) return;
            if (!url.equals(tag)) return; // recykling wierszy
            iv.setImageBitmap(bitmap);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title, date, snippet;
        ImageView thumb;

        ViewHolder(@NonNull View v) {
            super(v);
            title   = v.findViewById(R.id.newsTitle);
            date    = v.findViewById(R.id.newsDate);
            snippet = v.findViewById(R.id.newsSnippet);
            thumb   = v.findViewById(R.id.newsThumb);
        }
    }
}
