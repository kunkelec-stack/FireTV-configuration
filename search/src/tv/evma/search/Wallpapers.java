package tv.evma.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote-friendly local photo gallery, preview controls, and explicit foreground uploads. */
public final class Wallpapers extends Activity {
    private WallpaperStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<String> photos = new ArrayList<>();
    private PhotoUpload upload;
    private AlertDialog uploadDialog;
    private Dialog preview;
    private TextView status;
    private int generation;
    private int uploadGeneration;
    private boolean visible;
    private final Runnable expireUpload = () -> {
        if (uploadDialog != null) uploadDialog.dismiss();
        message("Upload expired. Choose Upload photo to start again.");
    };
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reuse, ViewGroup parent) {
            String id = photos.get(position);
            LinearLayout tile = new LinearLayout(Wallpapers.this); tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(6), dp(6), dp(6), dp(6)); tile.setBackground(control());
            ImageView image = new ImageView(Wallpapers.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new LinearLayout.LayoutParams(-1, dp(120)));
            TextView name = text(WallpaperStore.ORIGINAL.equals(id) ? "Original mountain" : "Saved photo " + position, 16);
            name.setPadding(dp(4), dp(8), 0, dp(4)); tile.addView(name);
            int ticket = generation;
            images.execute(() -> {
                Bitmap bitmap = null;
                try { bitmap = store.decode(id, 384); } catch (IOException | RuntimeException ignored) { }
                Bitmap ready = bitmap;
                ui.post(() -> { if (!isDestroyed() && ticket == generation) image.setImageBitmap(ready); else if (ready != null) ready.recycle(); });
            });
            return tile;
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new WallpaperStore(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xff0b1422); page.setPadding(dp(40), dp(24), dp(40), dp(20));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("EVMA ♥  /  Wallpapers", 26), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(button("Upload photo", this::upload)); head.addView(button("Original", () -> preview(WallpaperStore.ORIGINAL)));
        head.addView(button("Rotation", this::rotation)); head.addView(button("Close", this::finish)); page.addView(head);
        status = text("Choose a photo to preview. Hold OK on a saved photo to remove it. Up to 20 photos.", 16);
        status.setPadding(0, dp(16), 0, dp(16)); page.addView(status);
        GridView grid = new GridView(this); grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(200));
        grid.setHorizontalSpacing(dp(14)); grid.setVerticalSpacing(dp(14)); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> preview(photos.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> { remove(photos.get(position)); return true; });
        grid.setClipToPadding(false);
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); refresh();
    }
    private void refresh() { generation++; photos.clear(); photos.addAll(store.list()); adapter.notifyDataSetChanged(); }
    private void message(String value) { if (!isDestroyed()) status.setText(value); }
    private AlertDialog.Builder menu() { return new AlertDialog.Builder(this, R.style.SearchMenu); }
    private void rotation() {
        String[] labels = {"Off", "Every 15 minutes", "Every hour", "Every day"}; int[] minutes = {0, 15, 60, 1440};
        int selected = 0; for (int i = 0; i < minutes.length; i++) if (store.rotation() == minutes[i]) selected = i;
        menu().setTitle("Rotate all saved wallpapers (including the original)")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    try { store.rotate(minutes[which]); message("Wallpaper rotation: " + labels[which]); }
                    catch (IOException e) { message(e.getMessage()); }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }
    private void remove(String id) {
        if (WallpaperStore.ORIGINAL.equals(id)) { message("The original mountain wallpaper is always kept."); return; }
        menu().setTitle("Remove this photo?").setMessage("This removes only the TV's copy.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    try { store.remove(id); refresh(); message("Photo removed."); } catch (IOException e) { message(e.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }
    private void upload() {
        stopUpload();
        int ticket = uploadGeneration;
        message("Preparing photo upload…");
        network.execute(() -> {
          try {
            PhotoUpload receiver = new PhotoUpload(store, id -> ui.post(() -> {
                if (isDestroyed() || ticket != uploadGeneration || uploadDialog == null || !uploadDialog.isShowing()) return;
                refresh(); message("Photo saved. Close Upload, then select the new photo to preview and apply it.");
            }));
            ui.post(() -> {
            if (isDestroyed() || !visible || ticket != uploadGeneration) { receiver.close(); return; }
            upload = receiver;
            uploadDialog = menu().setTitle("Upload from your PC or phone")
                    .setMessage("On the same home network, open:\n\n" + upload.url + "\n\nPIN: " + upload.pin
                            + "\n\nChoose a JPG, PNG or WebP under 12 MB. Photos stay on the TV. This local connection is unencrypted; use your trusted home network."
                            + "\n\nCloses after five minutes or when you leave this screen.")
                    .setNegativeButton("Done", null).create();
            uploadDialog.setOnDismissListener(dialog -> stopUpload()); uploadDialog.show();
            ui.postDelayed(expireUpload, 300000);
            });
          } catch (IOException | RuntimeException e) {
            ui.post(() -> { if (ticket == uploadGeneration && !isDestroyed()) message("Could not start upload. Check the TV's network connection and try again."); });
          }
        });
    }
    private void stopUpload() {
        uploadGeneration++;
        ui.removeCallbacks(expireUpload);
        if (upload != null) { upload.close(); upload = null; }
        uploadDialog = null;
    }
    private void preview(String id) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen); preview = dialog;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        ImageView photo = new ImageView(this); root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        View dimmer = new View(this); root.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(14), dp(28), dp(18)); panel.setBackgroundColor(0xdd101e30);
        panel.addView(text("Preview · changes apply to Home and Search", 22));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        String[] fit = {store.fit()}; int[] brightness = {store.brightness()}, dim = {store.dim()};
        Button fitButton = button("", () -> { }); Button brightButton = button("", () -> { }); Button dimButton = button("", () -> { });
        Runnable update = () -> {
            photo.setScaleType("fit".equals(fit[0]) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            float factor = brightness[0] / 100f; ColorMatrix matrix = new ColorMatrix(); matrix.setScale(factor, factor, factor, 1);
            photo.setColorFilter(new ColorMatrixColorFilter(matrix)); dimmer.setBackgroundColor(Color.argb(dim[0] * 255 / 100, 0, 0, 0));
            fitButton.setText("Mode: " + fit[0]); brightButton.setText("Brightness: " + brightness[0] + "%"); dimButton.setText("Dim: " + dim[0] + "%");
        };
        fitButton.setOnClickListener(v -> { fit[0] = "fit".equals(fit[0]) ? "fill" : "fit"; update.run(); });
        brightButton.setOnClickListener(v -> { brightness[0] = brightness[0] >= 140 ? 60 : brightness[0] + 20; update.run(); });
        dimButton.setOnClickListener(v -> { dim[0] = dim[0] >= 80 ? 0 : dim[0] + 20; update.run(); });
        controls.addView(fitButton); controls.addView(brightButton); controls.addView(dimButton);
        controls.addView(button("Apply", () -> {
            try { store.apply(id, fit[0], brightness[0], dim[0]); dialog.dismiss(); message("Wallpaper applied to Home and Search."); }
            catch (IOException e) { message(e.getMessage()); }
        })); controls.addView(button("Cancel", dialog::dismiss)); panel.addView(controls);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); root.addView(panel, bottom);
        // Sample decoding stays off the TV's UI thread. A cancelled preview never applies anything.
        images.execute(() -> {
            Bitmap image = null; try { image = store.decode(id, 1920); } catch (IOException | RuntimeException ignored) { }
            Bitmap ready = image; ui.post(() -> {
                if (!isDestroyed() && dialog.isShowing()) photo.setImageBitmap(ready); else if (ready != null) ready.recycle();
            });
        });
        dialog.setOnDismissListener(value -> { photo.setImageDrawable(null); if (preview == dialog) preview = null; });
        update.run(); dialog.setContentView(root); dialog.show(); fitButton.requestFocus();
    }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE); return view; }
    private Button button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setTextSize(15); button.setTextColor(Color.WHITE);
        button.setAllCaps(false); button.setPadding(dp(14), dp(8), dp(14), dp(8)); button.setBackground(control());
        button.setOnClickListener(v -> action.run()); return button;
    }
    private StateListDrawable control() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focus = new GradientDrawable(); focus.setColor(0xff35587b); focus.setCornerRadius(dp(10)); focus.setStroke(dp(2), 0xffcbe5ff);
        GradientDrawable normal = new GradientDrawable(); normal.setColor(0xff172a40); normal.setCornerRadius(dp(10));
        states.addState(new int[] {android.R.attr.state_focused}, focus); states.addState(new int[] {android.R.attr.state_pressed}, focus);
        states.addState(new int[] {android.R.attr.state_selected}, focus);
        states.addState(new int[0], normal); return states;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); visible = true; }
    @Override protected void onPause() {
        visible = false;
        if (uploadDialog != null) uploadDialog.dismiss(); stopUpload(); super.onPause();
    }
    @Override protected void onDestroy() {
        stopUpload(); if (preview != null) preview.dismiss(); images.shutdownNow(); network.shutdownNow(); generation++; super.onDestroy();
    }
}
