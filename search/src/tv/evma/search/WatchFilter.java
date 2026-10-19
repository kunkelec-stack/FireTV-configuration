package tv.evma.search;

/** Local metadata filters. A runtime cap requires a known runtime below the cap. */
final class WatchFilter {
    int type, watched, minutes;
    String genre = "";
    boolean matches(Catalog.Title title, boolean seen) {
        if (type == 1 && !"movie".equals(title.showType)) return false;
        if (type == 2 && !"series".equals(title.showType)) return false;
        if (watched == 1 && seen || watched == 2 && !seen) return false;
        if (minutes > 0 && (title.runtime <= 0 || title.runtime >= minutes)) return false;
        if (!genre.isEmpty()) {
            boolean found = false;
            for (String name : title.genreNames) if (genre.equalsIgnoreCase(name)) found = true;
            if (!found) return false;
        }
        return true;
    }
    String summary() {
        String value = type == 1 ? "Movies" : type == 2 ? "TV shows" : "All types";
        if (!genre.isEmpty()) value += " · " + genre;
        if (minutes > 0) value += " · Under " + minutes + " min";
        if (watched > 0) value += watched == 1 ? " · Unwatched" : " · Watched";
        return value;
    }
}
