package tv.dumbtv.home;

import java.util.Arrays;
import java.util.List;

public final class OrderTest {
    private OrderTest() {}
    public static void main(String[] args) {
        List<String> original = Arrays.asList("Netflix", "YouTube", "Spotify", "Hulu");
        check(Order.move(original, 0, 3).equals(Arrays.asList("YouTube", "Spotify", "Hulu", "Netflix")), "First to last");
        check(Order.move(original, 3, 0).equals(Arrays.asList("Hulu", "Netflix", "YouTube", "Spotify")), "Last to first");
        check(Order.move(original, 2, 1).equals(Arrays.asList("Netflix", "Spotify", "YouTube", "Hulu")), "Move earlier");
        check(Order.move(original, 1, 2).equals(Arrays.asList("Netflix", "Spotify", "YouTube", "Hulu")), "Move later");
        check(Order.move(original, 1, 1).equals(original), "Same position");
        check(original.equals(Arrays.asList("Netflix", "YouTube", "Spotify", "Hulu")), "Original list is preserved");
        try { Order.move(original, -1, 0); throw new AssertionError("Invalid source accepted"); }
        catch (IllegalArgumentException expected) { /* Correct. */ }
        System.out.println("App ordering tests passed.");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
