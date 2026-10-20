package tv.evma.search;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Bounded public artwork requests. Never sends the catalog key to image hosts. */
final class Posters {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(64));
    private final Map<String, List<ImageView>> pending = new HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8192) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getAllocationByteCount() / 1024);
        }
    };
    private volatile int generation;

    void load(String address, ImageView view) {
        view.setTag(address);
        Bitmap cached = cache.get(address);
        if (cached != null) { view.setImageBitmap(cached); return; }
        if (address.isEmpty()) return;
        List<ImageView> waiting = pending.get(address);
        if (waiting != null) { waiting.add(view); return; }
        waiting = new ArrayList<>();
        waiting.add(view);
        pending.put(address, waiting);
        int ticket = generation;
        try {
            workers.execute(() -> {
                if (ticket != generation) return;
                Bitmap image = download(address);
                ui.post(() -> {
                    if (ticket != generation) return;
                    List<ImageView> targets = pending.remove(address);
                    if (image == null) return;
                    cache.put(address, image);
                    if (targets != null) for (ImageView target : targets) {
                        if (address.equals(target.getTag())) target.setImageBitmap(image);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            pending.remove(address); // Title-only artwork remains usable.
        }
    }

    void cancelPending() {
        ++generation;
        workers.getQueue().clear();
        pending.clear();
    }

    void close() {
        cancelPending();
        workers.shutdownNow();
        cache.evictAll();
    }

    private Bitmap download(String address) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) return null;
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2 * 1024 * 1024) return null;
                    bytes.write(buffer, 0, count);
                }
                byte[] data = bytes.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0
                        || options.outWidth > 8192 || options.outHeight > 8192) return null;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 360
                        || options.outHeight / options.inSampleSize > 540) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeByteArray(data, 0, data.length, options);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            return null; // Missing, SVG, unsupported, or offline artwork uses the title placeholder.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
