package com.yuriaaaaa.sonicr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads GameBanana screenshots into ImageViews in the background, with a small memory cache. */
final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(ImageView view, String url) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap bitmap = null;
            try {
                HttpURLConnection connection = GameBanana.open(url);
                try (InputStream in = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
            }
            if (bitmap == null)
                return;
            CACHE.put(url, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> {
                if (url.equals(view.getTag()))
                    view.setImageBitmap(result);
            });
        });
    }
}
