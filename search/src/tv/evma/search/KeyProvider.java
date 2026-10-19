package tv.evma.search;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shell-only streaming import: no external storage or key-bearing arguments. */
public final class KeyProvider extends ContentProvider {
    private volatile String status = "idle";

    @Override public boolean onCreate() { return true; }

    private void check(Uri uri, String path) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("ADB shell required");
        if (!path.equals(uri.getPath())) throw new IllegalArgumentException("Unsupported path");
    }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        check(uri, "/key");
        if (!"w".equals(mode) || "importing".equals(status))
            throw new FileNotFoundException("Import unavailable");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException ignored) { throw new FileNotFoundException("Cannot open import"); }
        status = "importing";
        new Thread(() -> {
            String outcome = "error";
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[512];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > 4096) throw new IOException("Input too large");
                    bytes.write(chunk, 0, count);
                }
                String key = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
                if (!key.matches("[\\x21-\\x7E]+")) throw new IOException("Invalid key");
                if (getContext().getSharedPreferences("catalog", 0).edit()
                        .putString("key", key).commit()) outcome = "configured";
            } catch (IOException | SecurityException ignored) {
                // Never log key material or raw exceptions.
            } finally {
                status = outcome;
            }
        }, "catalog-key-import").start();
        return pipe[1];
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] arguments, String sortOrder) {
        check(uri, "/status");
        MatrixCursor cursor = new MatrixCursor(new String[] { "status" });
        cursor.addRow(new Object[] { status });
        return cursor;
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] arguments) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] arguments) {
        throw new UnsupportedOperationException();
    }
}
