package tv.evma.search;

import android.app.Activity;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** ADB-only single-use import. Intent extras and command lines never contain the key. */
public final class Provision extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        File dir = getExternalFilesDir(null);
        if (dir == null) { finish(); return; }
        File transfer = new File(dir, "catalog-key.once"), status = new File(dir, "key-import.status");
        String result = "error";
        try {
            if (!transfer.isFile() || transfer.length() > 4096) throw new IOException("Invalid transfer");
            String key = new String(Files.readAllBytes(transfer.toPath()), StandardCharsets.UTF_8).trim();
            if (!key.matches("[\\x21-\\x7E]+")) throw new IOException("Invalid key");
            if (!transfer.delete()) throw new IOException("Cannot remove transfer");
            if (!getSharedPreferences("catalog", MODE_PRIVATE).edit().putString("key", key).commit()) throw new IOException("Cannot save key");
            result = "configured";
        } catch (IOException | SecurityException ignored) {
            // Never log input values or raw key-handling exceptions.
        } finally {
            if (transfer.exists()) transfer.delete();
            try { Files.write(status.toPath(), result.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException | SecurityException ignored) { /* Updater reports missing status. */ }
            finish();
        }
    }
}
