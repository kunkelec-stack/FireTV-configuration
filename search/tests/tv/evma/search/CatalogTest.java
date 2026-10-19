package tv.evma.search;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline contract tests: no API key, network, TV, or streaming account required. */
public final class CatalogTest {
    private CatalogTest() {}

    public static void main(String[] args) throws Exception {
        Set<String> selected = new HashSet<>(Arrays.asList("netflix", "primevideo", "hulu", "appletv"));
        String options = offer("Netflix", "subscription", "") + ","
                + offer("Netflix", "free", "") + ","
                + offer("Amazon Prime Video", "rent", "") + ","
                + offer("Apple TV", "subscription", "") + ","
                + offer("Hulu", "subscription", ",\"addon\":{\"name\":\"Extra channel\"}");
        Catalog.Title title = Catalog.parse(show("us", options), selected).get(0);
        check(title.offers.size() == 2, "Exclude rentals and add-ons; deduplicate services");
        check("free".equals(title.offers.get(0).type), "Prefer Netflix's free offer");
        check("Apple TV".equals(title.offers.get(1).service), "Keep a selected subscription service");
        check(Catalog.parse(show("gb", offer("Netflix", "subscription", "")), selected).get(0).offers.isEmpty(), "Ignore non-US offers");
        check(Catalog.parse(show("us", offer("Netflix", "buy", "")), selected).get(0).offers.isEmpty(), "Exclude purchases");
        check(Catalog.parse(show("us", offer("Other Service", "free", "")), selected).get(0).offers.isEmpty(), "Exclude unselected services");
        check(Catalog.parse("[]", selected).isEmpty(), "Empty title results remain empty");
        check("hbomax".equals(Catalog.serviceKey("Max")), "Recognize Max name");
        check("disney".equals(Catalog.serviceKey("Disney Plus")), "Recognize Disney name");
        expectFailure("not-json", selected);
        expectFailure("[{\"title\":\"Example\"}]", selected);
        List<Catalog.Title> unavailable = Catalog.parse(show("us", ""), selected);
        check(unavailable.size() == 1 && unavailable.get(0).offers.isEmpty(), "Keep a real title with no matching offers");
        System.out.println("Availability-filter tests passed (US, offer types, add-ons, duplicates, service selection, and malformed data).");
    }

    private static String offer(String service, String type, String extra) {
        return "{\"service\":{\"name\":\"" + service + "\"},\"type\":\"" + type
                + "\",\"link\":\"https://example.com/title\"" + extra + "}";
    }

    private static String show(String country, String offers) {
        return "[{\"title\":\"Example\",\"showType\":\"movie\",\"releaseYear\":2020,"
                + "\"streamingOptions\":{\"" + country + "\":[" + offers + "]}}]";
    }

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }

    private static void expectFailure(String json, Set<String> selected) {
        try { Catalog.parse(json, selected); }
        catch (IOException expected) { return; }
        throw new AssertionError("Malformed or incomplete data must not be reported as unavailability");
    }
}
