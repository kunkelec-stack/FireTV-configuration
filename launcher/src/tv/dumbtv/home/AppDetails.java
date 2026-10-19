package tv.dumbtv.home;

import java.util.Locale;

/** Short local descriptions: changing focus never makes a catalog request. */
final class AppDetails {
    private AppDetails() {}

    static String description(String label) {
        String name = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        switch (name) {
            case "netflix":
                return "Movies, series and Netflix originals. Pick a profile and find your next watch.";
            case "primevideo": case "amazonprimevideo": case "amazonvideo":
                return "Explore movies and series in Prime Video. Some titles need a rental, purchase or extra channel.";
            case "youtube": case "youtubeonfiretv":
                return "Your favorite creators, videos and channels, ready for the big screen.";
            case "disney": case "disneyplus":
                return "Browse movies, series and favorites for your next Disney+ night.";
            case "tubi": case "tubitv":
                return "Find movies and TV shows to watch free with ads.";
            case "hulu":
                return "Discover shows, movies and Hulu originals. Your plan determines what is included.";
            case "appletv": case "appletvplus":
                return "Explore Apple TV. Check each title for subscription, rental or purchase options.";
            case "peacock": case "peacocktv":
                return "Find shows, movies and more on Peacock. Availability depends on your plan.";
            case "spotify":
                return "Set the mood with music, playlists and podcasts. Make the room your own.";
            case "crunchyroll":
                return "Explore anime series and movies, and pick up where you left off.";
            case "paramount": case "paramountplus":
                return "Browse movies, series and more on Paramount+. Your plan determines what is included.";
            case "hbo": case "max": case "hbomax":
                return "Find movies, series and HBO favorites for your next watch.";
            default:
                return "Ready when you are. Open this app to explore what is inside.";
        }
    }
}
