package tv.evma.search;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

/** Read-only wallpaper access for this app and the same-signed EVMA home launcher. */
public final class WallpaperProvider extends ContentProvider {
    private WallpaperStore store;
    @Override public boolean onCreate() { store = new WallpaperStore(getContext()); return true; }
    private void trusted() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains("tv.dumbtv.home")
                || pm.checkSignatures("tv.dumbtv.home", getContext().getPackageName()) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("EVMA wallpaper access only");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        trusted();
        if (!"/state".equals(uri.getPath())) throw new IllegalArgumentException("Unsupported path");
        MatrixCursor cursor = new MatrixCursor(new String[] {"enabled", "id", "fit", "brightness", "dim", "revision"});
        String id = store.current();
        long modified = 0;
        try { modified = store.file(id).lastModified(); } catch (IOException ignored) { /* Client retains its fallback. */ }
        String revision = id + ":" + modified + ":" + store.fit() + ":" + store.brightness() + ":" + store.dim();
        cursor.addRow(new Object[] {store.prefs.getBoolean("enabled", false) ? 1 : 0,
                id, store.fit(), store.brightness(), store.dim(), revision});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        trusted();
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 || !"image".equals(uri.getPathSegments().get(0)))
            throw new FileNotFoundException("Read-only photos");
        try {
            File file = store.file(uri.getLastPathSegment());
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException("Photo unavailable"); }
    }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
