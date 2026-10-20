package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.List;

final class Order {
    private Order() {}

    static <T> List<T> move(List<T> items, int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size()) {
            throw new IllegalArgumentException("App position is outside the list");
        }
        List<T> ordered = new ArrayList<>(items);
        T selected = ordered.remove(from);
        ordered.add(to, selected);
        return ordered;
    }
}
