package fr.lecteursimple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A shuffled pass visits every title once; previous follows that same order. */
final class PlaybackOrder {
    private final List<Integer> order = new ArrayList<>();

    void reset(int count, int current, boolean shuffle) {
        order.clear();
        for (int i = 0; i < count; i++) if (!shuffle || i != current) order.add(i);
        if (shuffle && count > 0) {
            Collections.shuffle(order);
            order.add(0, current);
        }
    }

    int adjacent(int current, int direction) {
        int position = order.indexOf(current);
        int target = position + direction;
        return position < 0 || target < 0 || target >= order.size() ? -1 : order.get(target);
    }
}
