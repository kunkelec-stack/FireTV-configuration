package tv.evma.search;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private local profiles. Never contains streaming credentials or catalog API keys. */
final class Library {
    static final String[] NAMES = {"Together", "Evan", "Emma"};
    private static final String[] IDS = {"together", "evan", "emma"};
    private final Context context;
    private final SharedPreferences profiles, legacy;
    Library(Context context) {
        this.context = context.getApplicationContext();
        profiles = context.getSharedPreferences("profiles", Context.MODE_PRIVATE);
        legacy = context.getSharedPreferences("catalog", Context.MODE_PRIVATE);
    }
    int active() {
        String id = profiles.getString("active", IDS[0]);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }
    String name() { return NAMES[active()]; }
    boolean activate(int index) {
        return index >= 0 && index < IDS.length && profiles.edit().putString("active", IDS[index]).commit();
    }
    private SharedPreferences store() {
        return context.getSharedPreferences("library-" + IDS[active()], Context.MODE_PRIVATE);
    }
    Set<String> services() {
        Set<String> defaults = new HashSet<>();
        for (String service : Catalog.SERVICES) defaults.add(Catalog.serviceKey(service));
        return new HashSet<>(store().getStringSet("services", legacy.getStringSet("services", defaults)));
    }
    boolean services(Set<String> choice) { return store().edit().putStringSet("services", new HashSet<>(choice)).commit(); }
    List<String> recent() {
        List<String> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store().getString("recent", "[]"));
            for (int i = 0; i < Math.min(array.length(), 20); i++) values.add(array.getString(i));
        } catch (JSONException ignored) { /* Do not expose stored data in logs. */ }
        return values;
    }
    void remember(String term) {
        List<String> recent = recent();
        recent.removeIf(value -> value.equalsIgnoreCase(term));
        recent.add(0, term);
        store().edit().putString("recent", new JSONArray(recent.subList(0, Math.min(recent.size(), 20))).toString()).apply();
    }
    boolean clearRecent() { return store().edit().remove("recent").commit(); }
    static String backup(Context context) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "evma-library-backup").put("version", 1)
                    .put("exportedAt", System.currentTimeMillis());
            root.put("active", context.getSharedPreferences("profiles", Context.MODE_PRIVATE).getString("active", IDS[0]));
            JSONObject profiles = new JSONObject();
            Set<String> defaults = new HashSet<>();
            for (String service : Catalog.SERVICES) defaults.add(Catalog.serviceKey(service));
            Set<String> legacyServices = context.getSharedPreferences("catalog", Context.MODE_PRIVATE).getStringSet("services", defaults);
            for (String id : IDS) {
                SharedPreferences data = context.getSharedPreferences("library-" + id, Context.MODE_PRIVATE);
                List<Catalog.Title> titles = Catalog.parse(data.getString("watchlist", "[]"), new HashSet<String>());
                JSONArray watchlist = new JSONArray();
                for (Catalog.Title title : titles) watchlist.put(title.snapshot());
                JSONObject profile = new JSONObject();
                profile.put("watchlist", watchlist);
                profile.put("recent", new JSONArray(data.getString("recent", "[]")));
                profile.put("watched", new JSONArray(data.getStringSet("watched", new HashSet<String>())));
                profile.put("services", new JSONArray(data.getStringSet("services", legacyServices)));
                profile.put("lastPick", data.getString("lastPick", ""));
                JSONObject filters = new JSONObject();
                for (String prefix : new String[] {"filter.", "pick."}) {
                    filters.put(prefix + "type", data.getInt(prefix + "type", 0));
                    filters.put(prefix + "watched", data.getInt(prefix + "watched", 0));
                    filters.put(prefix + "minutes", data.getInt(prefix + "minutes", 0));
                    filters.put(prefix + "genre", data.getString(prefix + "genre", ""));
                }
                profile.put("filters", filters);
                profiles.put(id, profile);
            }
            root.put("profiles", profiles);
            String json = root.toString();
            if (json.length() > 8 * 1024 * 1024) throw new IOException("Backup is too large");
            return json;
        } catch (JSONException | ClassCastException e) { throw new IOException("Saved library could not be exported", e); }
    }
    boolean watched(Catalog.Title item) {
        return store().getStringSet("watched", new HashSet<String>()).contains(item.identity());
    }
    void watched(Catalog.Title item, boolean seen) throws IOException {
        Set<String> values = new HashSet<>(store().getStringSet("watched", new HashSet<String>()));
        if (seen) values.add(item.identity()); else values.remove(item.identity());
        if (!store().edit().putStringSet("watched", values).commit())
            throw new IOException("Watched status could not be saved. Please try again.");
    }
    String lastPick() { return store().getString("lastPick", ""); }
    void lastPick(String id) { store().edit().putString("lastPick", id).apply(); }
    WatchFilter filter(boolean picker) {
        String prefix = picker ? "pick." : "filter.";
        SharedPreferences data = store();
        WatchFilter filter = new WatchFilter();
        filter.type = Math.max(0, Math.min(2, data.getInt(prefix + "type", 0)));
        filter.watched = picker ? 1 : Math.max(0, Math.min(2, data.getInt(prefix + "watched", 0)));
        filter.genre = data.getString(prefix + "genre", "");
        int minutes = data.getInt(prefix + "minutes", 0);
        filter.minutes = minutes == 30 || minutes == 60 || minutes == 120 || minutes == 180 ? minutes : 0;
        return filter;
    }
    boolean filter(boolean picker, WatchFilter filter) {
        String prefix = picker ? "pick." : "filter.";
        return store().edit().putInt(prefix + "type", filter.type)
                .putInt(prefix + "watched", picker ? 1 : filter.watched)
                .putInt(prefix + "minutes", filter.minutes).putString(prefix + "genre", filter.genre).commit();
    }
    List<Catalog.Title> filtered(WatchFilter filter) throws IOException {
        List<Catalog.Title> values = saved();
        values.removeIf(title -> !filter.matches(title, watched(title)));
        return values;
    }
    List<Catalog.Title> saved() throws IOException {
        return Catalog.parse(store().getString("watchlist", "[]"), new HashSet<String>());
    }
    boolean contains(Catalog.Title item) throws IOException {
        for (Catalog.Title title : saved()) if (title.identity().equals(item.identity())) return true;
        return false;
    }
    void setSaved(Catalog.Title item, boolean on) throws IOException {
        List<Catalog.Title> saved = saved();
        saved.removeIf(title -> title.identity().equals(item.identity()));
        if (on) {
            if (saved.size() >= 100) throw new IOException("This profile's watchlist has 100 titles. Remove one before saving another.");
            saved.add(0, item);
        }
        writeSaved(saved);
    }
    void refreshSaved(Catalog.Title item) throws IOException {
        List<Catalog.Title> saved = saved();
        for (int i = 0; i < saved.size(); i++) if (saved.get(i).identity().equals(item.identity())) {
            saved.set(i, item); writeSaved(saved); return;
        }
    }
    private void writeSaved(List<Catalog.Title> saved) throws IOException {
        try {
            JSONArray json = new JSONArray();
            for (Catalog.Title title : saved) json.put(title.snapshot());
            if (json.toString().length() > 2 * 1024 * 1024)
                throw new IOException("The watchlist metadata is too large to save safely. Remove a title before trying again.");
            if (!store().edit().putString("watchlist", json.toString()).commit())
                throw new IOException("The watchlist could not be saved. Please try again.");
        } catch (JSONException e) { throw new IOException("The title could not be saved.", e); }
    }
}
