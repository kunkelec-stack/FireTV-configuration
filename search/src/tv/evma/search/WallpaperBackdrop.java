package tv.evma.search;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Same-signed, read-only wallpaper bridge. No networking or storage permission in Home. */
final class WallpaperBackdrop implements AutoCloseable {
    private static final Uri STATE = Uri.parse("content://tv.evma.search.wallpaper/state");
    private final Activity activity;
    private final ImageView image;
    private final View shade;
    private final Runnable fallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running, active;
    private int generation;
    private String revision = "";
    private final Runnable poll = this::load;
    WallpaperBackdrop(Activity activity, ImageView image, View shade, Runnable fallback) {
        this.activity = activity; this.image = image; this.shade = shade; this.fallback = fallback;
    }
    boolean isActive() { return active; }
    void start() { if (running) return; running = true; generation++; load(); }
    void stop() { running = false; generation++; ui.removeCallbacks(poll); }
    private void load() {
        if (!running) return;
        final int ticket = generation;
        final String previous = revision;
        worker.execute(() -> {
            Bitmap bitmap = null;
            String next = "", fit = "fill"; int brightness = 100, dim = 40;
            boolean enabled = false;
            try (Cursor cursor = activity.getContentResolver().query(STATE, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1) {
                    String id = cursor.getString(1); fit = cursor.getString(2);
                    brightness = Math.max(60, Math.min(140, cursor.getInt(3)));
                    dim = Math.max(0, Math.min(80, cursor.getInt(4))); next = cursor.getString(5);
                    if (!next.equals(previous)) {
                        Uri uri = Uri.parse("content://tv.evma.search.wallpaper/image/" + Uri.encode(id));
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
                        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid wallpaper");
                        options.inSampleSize = 1;
                        while (options.outWidth / options.inSampleSize > 1920 || options.outHeight / options.inSampleSize > 1920)
                            options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
                        if (bitmap == null) throw new IOException("Invalid wallpaper");
                    }
                    enabled = true;
                }
            } catch (IOException | RuntimeException ignored) { enabled = false; }
            final Bitmap ready = bitmap;
            final String version = next, mode = fit;
            final int light = brightness, darkness = dim;
            final boolean use = enabled;
            ui.post(() -> {
                if (!running || ticket != generation || activity.isDestroyed()) { if (ready != null) ready.recycle(); return; }
                if (use) {
                    active = true; revision = version;
                    if (ready != null) image.setImageBitmap(ready);
                    image.setBackgroundColor(Color.BLACK);
                    image.setScaleType("fit".equals(mode) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                    ColorMatrix matrix = new ColorMatrix(); float factor = light / 100f; matrix.setScale(factor, factor, factor, 1);
                    image.setColorFilter(new ColorMatrixColorFilter(matrix));
                    shade.setBackgroundColor(Color.argb(darkness * 255 / 100, 0, 0, 0));
                } else {
                    boolean wasActive = active; active = false; revision = "";
                    if (ready != null) ready.recycle();
                    if (wasActive) {
                        image.clearColorFilter(); image.setBackgroundColor(Color.TRANSPARENT);
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP); fallback.run();
                    }
                }
                ui.postDelayed(poll, 30000);
            });
        });
    }
    @Override public void close() { stop(); worker.shutdownNow(); }
}
