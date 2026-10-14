package monada.neuron.routing.feedback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, canonical, immutable copies: sorted by a total order, duplicates rejected. */
final class Canonical {

    private Canonical() {
    }

    /** Returns an immutable sorted copy; rejects null elements, more than {@code max} elements and equal neighbours. */
    static <T> List<T> sortedUnique(Collection<T> source, Comparator<? super T> order, int max, String name) {
        Objects.requireNonNull(source, name + " must not be null");
        if (source.size() > max) {
            throw new IllegalArgumentException(name + " allows at most " + max + " entries, got: " + source.size());
        }
        List<T> sorted = new ArrayList<>(source);
        for (T element : sorted) {
            Objects.requireNonNull(element, name + " must not contain null");
        }
        sorted.sort(order);
        for (int i = 1; i < sorted.size(); i++) {
            if (order.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
                throw new IllegalArgumentException(name + " contains a duplicate: " + sorted.get(i));
            }
        }
        return List.copyOf(sorted);
    }
}
