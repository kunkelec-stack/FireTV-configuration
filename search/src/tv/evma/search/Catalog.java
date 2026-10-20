package tv.evma.search;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/** Catalog queries only. No credentials, search terms, or responses are logged. */
final class Catalog {
    static final String[] SERVICES = {"Netflix", "Prime Video", "YouTube", "Disney+", "Tubi",
            "Hulu", "Apple TV", "Peacock", "Crunchyroll", "Paramount+", "HBO Max"};

    private Catalog() {}

    static final class Offer {
        final String service, key, link, type;
        Offer(String service, String key, String link, String type) {
            this.service = service; this.key = key; this.link = link; this.type = type;
        }
        String label() { return service + ("free".equals(type) ? " · Free" : " · Subscription"); }
    }

    static final class Title {
        final String name, detail, poster, imdbId, id, overview, genres, showType, credits;
        final List<String> genreNames;
        final int runtime;
        private final JSONObject metadata;
        final List<Offer> offers;
        Title(String name, String detail, String poster, String imdbId, List<Offer> offers, JSONObject show) {
            this.name = name; this.detail = detail; this.poster = poster; this.imdbId = imdbId; this.offers = offers;
            String rawId = show.optString("id", "");
            id = rawId.matches("[0-9]{1,20}") ? rawId : "";
            overview = show.optString("overview", "");
            runtime = Math.max(0, show.optInt("runtime", 0));
            showType = show.optString("showType", "");
            List<String> names = new ArrayList<>();
            JSONArray source = show.optJSONArray("genres");
            if (source != null) for (int i = 0; i < Math.min(source.length(), 8); i++) {
                JSONObject genre = source.optJSONObject(i);
                if (genre != null && !genre.optString("name").isEmpty()) names.add(genre.optString("name"));
            }
            genres = String.join(" · ", names);
            genreNames = java.util.Collections.unmodifiableList(names);
            List<String> people = new ArrayList<>();
            addCredits(people, show.optJSONArray("directors"), "Directed by");
            addCredits(people, show.optJSONArray("creators"), "Created by");
            addCredits(people, show.optJSONArray("cast"), "Cast");
            credits = String.join("\n\n", people);
            metadata = show;
        }
        private static void addCredits(List<String> sections, JSONArray names, String heading) {
            if (names == null) return;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < Math.min(names.length(), 80); i++) {
                String name = names.optString(i, "").trim();
                if (!name.isEmpty() && !values.contains(name)) values.add(name);
            }
            if (!values.isEmpty()) sections.add(heading + "\n" + String.join("Cast".equals(heading) ? "\n" : " · ", values));
        }
        String identity() {
            return !id.isEmpty() ? "show:" + id : !imdbId.isEmpty() ? imdbId : name + "|" + detail;
        }
        JSONObject snapshot() throws JSONException {
            JSONObject saved = new JSONObject();
            for (String field : new String[] {"id", "title", "showType", "releaseYear", "firstAirYear", "imdbId", "overview", "runtime", "genres", "cast", "directors", "creators"}) {
                if (metadata.has(field)) saved.put(field, metadata.get(field));
            }
            saved.put("imageSet", new JSONObject().put("verticalPoster", new JSONObject().put("w240", poster)));
            saved.put("streamingOptions", new JSONObject()); // Offers must be refreshed, never restored as current.
            return saved;
        }
    }

    static String serviceKey(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        switch (s) {
            case "amazonprimevideo": case "amazonprime": case "amazonvideo": case "prime": return "primevideo";
            case "disneyplus": return "disney";
            case "appletvplus": return "appletv";
            case "peacocktv": return "peacock";
            case "paramountplus": return "paramount";
            case "hbo": case "max": return "hbomax";
            case "tubitv": return "tubi";
            default: return s;
        }
    }

    static List<Title> search(String query, String key, Set<String> selected) throws IOException {
        URL url = new URL("https://api.movieofthenight.com/v4/shows/search/title?country=us&title="
                + URLEncoder.encode(query, "UTF-8"));
        return parse(request(url, key), selected);
    }

    static Title refresh(Title saved, String key, Set<String> selected) throws IOException {
        String id = !saved.id.isEmpty() ? saved.id : saved.imdbId;
        if (id.isEmpty()) throw new IOException("This saved title has no catalog ID. Search its name again.");
        try {
            JSONObject show = new JSONObject(request(new URL("https://api.movieofthenight.com/v4/shows/" + id + "?country=us"), key));
            Title title = parse(new JSONArray().put(show).toString(), selected).get(0);
            boolean same = !saved.id.isEmpty() ? saved.id.equals(title.id) : saved.imdbId.equals(title.imdbId);
            if (!same)
                throw new IOException("The catalog returned a different title. Search its name again.");
            return title;
        } catch (JSONException e) { throw new IOException("The catalog returned an unexpected title response.", e); }
    }

    private static String request(URL url, String key) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-API-Key", key);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("The catalog key was rejected. Check Search setup.");
            if (code == 429) throw new IOException("The catalog's request limit was reached. Please try again later.");
            if (code == 404) throw new IOException("This title is no longer listed in the catalog. Try searching again.");
            if (code != 200) throw new IOException("The catalog is unavailable right now. Please try again later.");
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > 4 * 1024 * 1024) throw new IOException("The catalog response was too large.");
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("The catalog timed out. Check the connection and try again.", e);
        } finally {
            connection.disconnect();
        }
    }

    static List<Title> parse(String json, Set<String> selected) throws IOException {
        try {
            JSONArray shows = new JSONArray(json);
            List<Title> titles = new ArrayList<>();
            for (int i = 0; i < shows.length(); i++) {
                JSONObject show = shows.getJSONObject(i);
                String name = show.getString("title");
                JSONObject byCountry = show.optJSONObject("streamingOptions");
                // A missing availability field is a catalog failure, not proof of no offers.
                if (byCountry == null) throw new IOException("The catalog returned incomplete availability data.");
                JSONArray options = byCountry.optJSONArray("us");
                Map<String, Offer> offers = new LinkedHashMap<>();
                if (options != null) for (int n = 0; n < options.length(); n++) {
                    JSONObject option = options.getJSONObject(n);
                    String type = option.optString("type");
                    if (!"subscription".equals(type) && !"free".equals(type)) continue;
                    if (option.has("addon") && !option.isNull("addon")) continue;
                    JSONObject service = option.getJSONObject("service");
                    String serviceName = service.getString("name");
                    String serviceId = serviceKey(serviceName);
                    if (!selected.contains(serviceId)) continue;
                    Offer offer = new Offer(serviceName, serviceId, option.getString("link"), type);
                    // Prefer a free offer if a service reports both kinds; never duplicate services.
                    if (!offers.containsKey(serviceId) || "free".equals(type)) offers.put(serviceId, offer);
                }
                int year = show.optInt("releaseYear", show.optInt("firstAirYear", 0));
                String detail = ("series".equals(show.optString("showType")) ? "TV series" : "Movie")
                        + (year > 0 ? " · " + year : "");
                JSONObject images = show.optJSONObject("imageSet");
                JSONObject vertical = images == null ? null : images.optJSONObject("verticalPoster");
                String poster = vertical == null ? "" : vertical.optString("w240", "");
                if (poster.isEmpty() && vertical != null) poster = vertical.optString("w360", "");
                String imdbId = show.optString("imdbId", "");
                if (!imdbId.matches("tt[0-9]{7,10}")) imdbId = "";
                titles.add(new Title(name, detail, poster, imdbId, new ArrayList<>(offers.values()), show));
            }
            return titles;
        } catch (JSONException e) {
            throw new IOException("The catalog returned an unexpected response. Please try again later.", e);
        }
    }
}
