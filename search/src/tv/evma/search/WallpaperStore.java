package tv.evma.search;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Local photos only; no catalog requests, storage permission, or external file access. */
final class WallpaperStore {
    static final String ORIGINAL = "original";
    final SharedPreferences prefs;
    private final Context context;
    private final File folder;
    WallpaperStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        folder = new File(context.getFilesDir(), "wallpapers");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Cannot open photo gallery");
    }
    List<String> list() {
        List<String> values = new ArrayList<>(); values.add(ORIGINAL);
        File[] photos = folder.listFiles(file -> file.getName().matches("[a-f0-9]{32}\\.jpg"));
        if (photos != null) {
            Arrays.sort(photos, Comparator.comparingLong(File::lastModified).reversed());
            for (File photo : photos) values.add(photo.getName().substring(0, 32));
        }
        return values;
    }
    File file(String id) throws IOException {
        if (ORIGINAL.equals(id)) {
            File original = new File(context.getCacheDir(), "evma-original.jpg");
            if (!original.isFile()) {
                File temporary = File.createTempFile("evma-original", ".tmp", context.getCacheDir());
                try (InputStream input = context.getResources().openRawResource(R.drawable.wallpaper);
                        FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) out.write(chunk, 0, count);
                }
                if (!temporary.renameTo(original)) { temporary.delete(); throw new IOException("Cannot open original wallpaper"); }
            }
            return original;
        }
        if (!id.matches("[a-f0-9]{32}")) throw new IOException("Invalid photo");
        File file = new File(folder, id + ".jpg");
        if (!file.isFile()) throw new IOException("Photo no longer exists");
        return file;
    }
    String current() {
        List<String> photos = list();
        String selected = prefs.getString("selected", ORIGINAL);
        int position = photos.indexOf(selected);
        if (position < 0) position = 0;
        int minutes = rotation();
        long elapsed = System.currentTimeMillis() - prefs.getLong("anchor", System.currentTimeMillis());
        if (minutes > 0 && elapsed > 0) position = (int) ((position + elapsed / (minutes * 60000L)) % photos.size());
        return photos.get(position);
    }
    String fit() { return "fit".equals(prefs.getString("fit", "fill")) ? "fit" : "fill"; }
    int brightness() { return Math.max(60, Math.min(140, prefs.getInt("brightness", 100))); }
    int dim() { return Math.max(0, Math.min(80, prefs.getInt("dim", 40))); }
    int rotation() {
        int value = prefs.getInt("rotation", 0);
        return value == 15 || value == 60 || value == 1440 ? value : 0;
    }
    void apply(String id, String fit, int brightness, int dim) throws IOException {
        file(id);
        if (!prefs.edit().putBoolean("enabled", true).putString("selected", id)
                .putString("fit", fit).putInt("brightness", brightness).putInt("dim", dim)
                .putLong("anchor", System.currentTimeMillis()).commit()) throw new IOException("Wallpaper could not be saved");
    }
    void rotate(int minutes) throws IOException {
        if (!prefs.edit().putBoolean("enabled", true).putInt("rotation", minutes).putString("selected", current())
                .putLong("anchor", System.currentTimeMillis()).commit()) throw new IOException("Rotation could not be saved");
    }
    void remove(String id) throws IOException {
        if (ORIGINAL.equals(id)) throw new IOException("The original wallpaper is always kept");
        if (!file(id).delete()) throw new IOException("Photo could not be removed");
        if (id.equals(prefs.getString("selected", ORIGINAL))) apply(ORIGINAL, fit(), brightness(), dim());
    }
    Bitmap decode(String id, int limit) throws IOException {
        File file = file(id);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Photo cannot be decoded");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > limit || options.outHeight / options.inSampleSize > limit)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
        if (image == null) throw new IOException("Photo cannot be decoded");
        return image;
    }
    synchronized String add(byte[] data) throws IOException {
        if (list().size() > 20) throw new IOException("Gallery is full. Remove a photo before uploading another.");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16384 || options.outHeight > 16384
                || (long) options.outWidth * options.outHeight > 120000000L) throw new IOException("Choose a JPG, PNG, or WebP photo under 12 MB.");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1920 || options.outHeight / options.inSampleSize > 1920)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) throw new IOException("This image format is not supported");
        Matrix matrix = new Matrix();
        try {
            int orientation = new ExifInterface(new ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(270); break;
                default: break;
            }
        } catch (IOException ignored) { /* Valid photos without EXIF keep their original orientation. */ }
        if (!matrix.isIdentity()) {
            Bitmap oriented = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
            if (oriented != image) image.recycle(); image = oriented;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        File temporary = new File(folder, id + ".tmp"), target = new File(folder, id + ".jpg");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new IOException("Photo could not be saved");
            }
            if (!temporary.renameTo(target)) throw new IOException("Photo could not be saved");
        } finally { image.recycle(); if (temporary.exists()) temporary.delete(); }
        return id;
    }
}
