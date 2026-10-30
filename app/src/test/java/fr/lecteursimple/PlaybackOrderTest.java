package fr.lecteursimple;

import java.util.HashSet;
import java.util.Set;

/** Standalone JVM regression test, run with java -ea. */
public final class PlaybackOrderTest {
    public static void main(String[] args) {
        PlaybackOrder order = new PlaybackOrder();
        order.reset(0, 0, true);
        assert order.adjacent(0, 1) == -1;
        order.reset(1, 0, true);
        assert order.adjacent(0, 1) == -1;
        assert order.adjacent(0, -1) == -1;
        order.reset(5, 2, false);
        assert order.adjacent(2, 1) == 3;
        assert order.adjacent(2, -1) == 1;
        assert order.adjacent(0, -1) == -1;
        assert order.adjacent(4, 1) == -1;
        for (int pass = 0; pass < 100; pass++) {
            order.reset(12, 5, true);
            Set<Integer> visited = new HashSet<>();
            int current = 5;
            assert order.adjacent(current, -1) == -1;
            while (current >= 0) {
                assert current < 12;
                assert visited.add(current) : "Repeated title";
                int next = order.adjacent(current, 1);
                if (next >= 0) assert order.adjacent(next, -1) == current : "Previous must retrace the order";
                current = next;
            }
            assert visited.size() == 12 : "Missing title";
        }
        order.reset(3, 1, false);
        assert order.adjacent(1, 1) == 2 : "Disabling shuffle restores alphabetical order";
        System.out.println("PlaybackOrder: all checks passed");
    }
}
