package tv.evma.search;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Optional OMDb lookup by exact IMDb ID. Never substitutes IMDb's score for RT. */
final class Ratings {
    private static final int NONE = -1, ERROR = -2, AUTH = -3, QUOTA = -4;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(64));
    private final Map<String, List<TextView>> pending = new HashMap<>();
    private final SharedPreferences prefs, cache;
    private volatile int generation, blocked;

    Ratings(Context context) {
        prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE);
        cache = context.getSharedPreferences("ratings-cache", Context.MODE_PRIVATE);
    }

    void load(String imdbId, TextView badge) {
        badge.setTag(imdbId);
        String key = prefs.getString("ratingsKey", "");
        if (key.isEmpty()) { label(badge, "RT · Set up", "Add an OMDb key in Settings → Rotten Tomatoes setup."); return; }
        if (imdbId.isEmpty()) {
            label(badge, "RT —", "The catalog did not provide an IMDb ID, so a verified ratings lookup is unavailable."); return;
        }
        int cached = cached(imdbId);
        if (cached >= NONE) { show(badge, cached); return; }
        if (blocked != 0) { show(badge, blocked); return; }
        label(badge, "RT …", "Checking the Rotten Tomatoes critics score via OMDb.");
        List<TextView> waiting = pending.get(imdbId);
        if (waiting != null) { waiting.add(badge); return; }
        waiting = new ArrayList<>(); waiting.add(badge); pending.put(imdbId, waiting);
        int ticket = generation;
        try {
            workers.execute(() -> {
                if (ticket != generation) return;
                int score = blocked == 0 ? fetch(imdbId, key) : blocked;
                ui.post(() -> {
                    if (ticket != generation) return;
                    if (score == AUTH || score == QUOTA) blocked = score;
                    List<TextView> targets = pending.remove(imdbId);
                    if (score >= NONE) save(imdbId, score);
                    if (targets != null) for (TextView target : targets) {
                        if (imdbId.equals(target.getTag())) show(target, score);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) { pending.remove(imdbId); show(badge, ERROR); }
    }

    void cancelPending() { ++generation; workers.getQueue().clear(); pending.clear(); }
    void reset() { cancelPending(); blocked = 0; }
    void close() { cancelPending(); workers.shutdownNow(); }

    private void label(TextView view, String label, String explanation) {
        view.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        view.setText(label);
        view.setContentDescription(explanation);
        view.setTooltipText(explanation);
        if (view.getParent() instanceof View) {
            View art = (View) view.getParent();
            if (art.getParent() instanceof View) {
                View card = (View) art.getParent();
                if (card.getTag() instanceof CharSequence)
                    card.setContentDescription(card.getTag().toString() + ". " + explanation);
            }
        }
    }

    private void show(TextView view, int score) {
        if (score >= 0) {
            label(view, "RT " + score + "%", "Rotten Tomatoes critics score: " + score + " percent. Data via OMDb.");
            view.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_tomato, 0, 0, 0);
            view.setCompoundDrawablePadding(Math.round(5 * view.getResources().getDisplayMetrics().density));
        } else if (score == AUTH) {
            label(view, "RT · Check key", "OMDb rejected the ratings key. Check Rotten Tomatoes setup in Settings.");
        } else if (score == QUOTA) {
            label(view, "RT · Limit", "OMDb's request limit was reached. Try again later.");
        } else if (score == ERROR) {
            label(view, "RT · Retry", "The ratings lookup failed. Search again to retry; this does not mean a score is missing.");
        } else {
            label(view, "RT —", "No Rotten Tomatoes critics score is available from OMDb for this exact title. TV season scores may differ.");
        }
    }

    private int cached(String id) {
        try {
            JSONObject entry = new JSONObject(cache.getString(id, "{}"));
            int score = entry.getInt("score");
            long age = System.currentTimeMillis() - entry.getLong("checkedAt");
            long ttl = (score == NONE ? 1 : 7) * 24 * 60 * 60 * 1000L;
            return score >= NONE && score <= 100 && age >= 0 && age < ttl ? score : ERROR;
        } catch (JSONException ignored) { return ERROR; }
    }

    private void save(String id, int score) {
        // Bound the on-disk cache; no API keys or search text are stored in it.
        Map<String, ?> all = cache.getAll();
        SharedPreferences.Editor edit = cache.edit();
        if (all.size() >= 256 && !all.containsKey(id)) edit.clear();
        try {
            JSONObject entry = new JSONObject();
            entry.put("score", score); entry.put("checkedAt", System.currentTimeMillis());
            edit.putString(id, entry.toString()).apply();
        } catch (JSONException ignored) { /* The score remains visible without caching. */ }
    }

    private int fetch(String id, String key) {
        HttpsURLConnection connection = null;
        try {
            // OMDb requires query-string authentication. Never log the URL or raw exceptions.
            URL url = new URL("https://www.omdbapi.com/?i=" + id + "&r=json&apikey=" + URLEncoder.encode(key, "UTF-8"));
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) return AUTH;
            if (code == 429) return QUOTA;
            if (code != 200) return ERROR;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > 65536) return ERROR;
                    bytes.write(chunk, 0, count);
                }
                return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8), id);
            }
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            return ERROR;
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static int parse(String body, String id) throws JSONException {
        JSONObject result = new JSONObject(body);
        if (!"True".equalsIgnoreCase(result.optString("Response"))) {
            String error = result.optString("Error").toLowerCase(Locale.ROOT);
            if (error.contains("limit")) return QUOTA;
            if (error.contains("api key") || error.contains("apikey")) return AUTH;
            return error.contains("not found") ? NONE : ERROR;
        }
        if (!id.equals(result.optString("imdbID"))) return ERROR;
        JSONArray ratings = result.optJSONArray("Ratings");
        if (ratings == null) return NONE;
        for (int i = 0; i < ratings.length(); i++) {
            JSONObject rating = ratings.getJSONObject(i);
            if (!"Rotten Tomatoes".equals(rating.optString("Source"))) continue;
            String value = rating.optString("Value");
            if (!value.matches("(?:100|[0-9]{1,2})%")) return NONE;
            return Integer.parseInt(value.substring(0, value.length() - 1));
        }
        return NONE;
    }
}
