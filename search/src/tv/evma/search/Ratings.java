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

/** Optional exact-ID critics (OMDb) or audience (MDBList) ratings. */
final class Ratings {
    private static final int NONE = -1, ERROR = -2, AUTH = -3, QUOTA = -4;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(64));
    private final Map<String, List<TextView>> pending = new HashMap<>();
    private final SharedPreferences prefs, cache;
    private final boolean audience;
    private volatile int generation, blocked;

    Ratings(Context context) {
        this(context, false);
    }
    Ratings(Context context, boolean audience) {
        this.audience = audience;
        prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE);
        cache = context.getSharedPreferences(audience ? "audience-cache" : "ratings-cache", Context.MODE_PRIVATE);
    }

    void load(String imdbId, TextView badge) {
        load(imdbId, "any", badge);
    }
    void load(Catalog.Title title, TextView badge) {
        load(title.imdbId, "series".equals(title.showType) ? "show" : "movie".equals(title.showType) ? "movie" : "any", badge);
    }
    private String name() { return audience ? "Audience" : "Critics"; }
    private String provider() { return audience ? "MDBList" : "OMDb"; }
    private void load(String imdbId, String type, TextView badge) {
        String identity = audience ? type + ":" + imdbId : imdbId;
        badge.setTag(identity);
        String key = prefs.getString(audience ? "audienceKey" : "ratingsKey", "");
        if (key.isEmpty()) {
            label(badge, name() + " · Set up", audience
                    ? "Add an MDBList key in Settings → Audience score setup."
                    : "Add an OMDb key in Settings → Rotten Tomatoes setup."); return;
        }
        if (imdbId.isEmpty()) {
            label(badge, name() + " —", "The catalog did not provide an IMDb ID, so an exact ratings lookup is unavailable.");
            icon(badge, -1); return;
        }
        int cached = cached(identity);
        if (cached >= NONE) { show(badge, cached); return; }
        if (blocked != 0) { show(badge, blocked); return; }
        label(badge, name() + " …", "Checking the Rotten Tomatoes " + name().toLowerCase(Locale.ROOT) + " score via " + provider() + ".");
        List<TextView> waiting = pending.get(identity);
        if (waiting != null) { waiting.add(badge); return; }
        waiting = new ArrayList<>(); waiting.add(badge); pending.put(identity, waiting);
        int ticket = generation;
        try {
            workers.execute(() -> {
                if (ticket != generation) return;
                int score = blocked == 0 ? fetch(imdbId, type, key) : blocked;
                ui.post(() -> {
                    if (ticket != generation) return;
                    if (score == AUTH || score == QUOTA) blocked = score;
                    List<TextView> targets = pending.remove(identity);
                    if (score >= NONE) save(identity, score);
                    if (targets != null) for (TextView target : targets) {
                        if (identity.equals(target.getTag())) show(target, score);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) { pending.remove(identity); show(badge, ERROR); }
    }

    void cancelPending() { ++generation; workers.getQueue().clear(); pending.clear(); }
    void reset() { cancelPending(); blocked = 0; }
    void close() { cancelPending(); workers.shutdownNow(); }

    private void label(TextView view, String label, String explanation) {
        view.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        view.setText(label);
        view.setContentDescription(explanation);
        view.setTooltipText(explanation);
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < 4 && parent instanceof View; depth++) {
            View card = (View) parent;
            if (card.getTag() instanceof CharSequence) {
                card.setTag(audience ? R.id.audience_description : R.id.critics_description, explanation);
                Object critics = card.getTag(R.id.critics_description), viewers = card.getTag(R.id.audience_description);
                card.setContentDescription(card.getTag().toString() + ". "
                            + (critics == null ? "" : critics.toString()) + " " + (viewers == null ? "" : viewers.toString()));
                break;
            }
            parent = card.getParent();
        }
    }

    private void show(TextView view, int score) {
        if (score >= 0) {
            label(view, name() + " " + score + "%", "Rotten Tomatoes " + name().toLowerCase(Locale.ROOT)
                    + " score: " + score + " percent. " + (score >= 60 ? "Positive" : "Negative")
                    + ". Data via " + provider() + ". "
                    + (audience ? "Verified versus all-audience status was not supplied. " : "")
                    + "No special award status is implied.");
            icon(view, score);
        } else if (score == AUTH) {
            label(view, name() + " · Key", provider() + " denied access. Check its key and plan access in Settings.");
        } else if (score == QUOTA) {
            label(view, name() + " · Limit", provider() + "'s request limit was reached. Try again later.");
        } else if (score == ERROR) {
            label(view, name() + " · Retry", "The ratings lookup failed. Search again to retry; this does not mean a score is missing.");
        } else {
            label(view, name() + " —", "No Rotten Tomatoes " + name().toLowerCase(Locale.ROOT)
                    + " score was provided by " + provider() + " for this title. This may be a coverage gap; TV season scores may differ.");
            icon(view, -1);
        }
    }

    private void icon(TextView view, int score) {
        int drawable = audience ? (score < 0 ? R.drawable.rt_audience_empty
                : score >= 60 ? R.drawable.rt_audience_positive : R.drawable.rt_audience_negative)
                : (score < 0 ? R.drawable.rt_critic_empty
                : score >= 60 ? R.drawable.rt_critic_positive : R.drawable.rt_critic_negative);
        view.setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0);
        view.setCompoundDrawablePadding(Math.round(5 * view.getResources().getDisplayMetrics().density));
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

    private int fetch(String id, String type, String key) {
        HttpsURLConnection connection = null;
        try {
            // Both providers require query-string authentication. Never log the URL or raw exceptions.
            URL url = new URL(audience
                    ? "https://api.mdblist.com/imdb/" + type + "/" + id + "/?apikey=" + URLEncoder.encode(key, "UTF-8")
                    : "https://www.omdbapi.com/?i=" + id + "&r=json&apikey=" + URLEncoder.encode(key, "UTF-8"));
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) return AUTH;
            if (code == 429) return QUOTA;
            if (audience && code == 404) return NONE;
            if (code != 200) return ERROR;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > 262144) return ERROR;
                    bytes.write(chunk, 0, count);
                }
                String body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                return audience ? parseAudience(body, id) : parse(body, id);
            }
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            return ERROR;
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static int parseAudience(String body, String id) throws JSONException {
        JSONObject result = new JSONObject(body);
        String returned = result.optString("imdb_id", result.optString("imdbid", ""));
        JSONObject ids = result.optJSONObject("ids");
        if (returned.isEmpty() && ids != null) returned = ids.optString("imdb", "");
        if (!id.equals(returned)) return ERROR;
        JSONArray ratings = result.optJSONArray("ratings");
        if (ratings == null) return ERROR;
        for (int i = 0; i < ratings.length(); i++) {
            JSONObject rating = ratings.getJSONObject(i);
            String source = rating.optString("source");
            if (!"popcorn".equals(source) && !"tomatoesaudience".equals(source)) continue;
            double value = rating.optDouble("value", Double.NaN);
            if (!Double.isFinite(value)) return NONE;
            return value >= 0 && value <= 100 && value == Math.rint(value) ? (int) value : ERROR;
        }
        return NONE;
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
