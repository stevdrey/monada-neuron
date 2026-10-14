package monada.neuron.routing.outcome;

import monada.neuron.routing.token.CanonicalLists;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Bounded, canonical, immutable copies: sorted by a total order, duplicates rejected. */
final class Ordering {

    private Ordering() {
    }

    /** Returns an immutable sorted copy; rejects null elements, more than {@code max} elements and equal neighbours. */
    static <T> List<T> canonical(Collection<T> source, Comparator<? super T> order, int max, String name) {
        return CanonicalLists.canonical(source, order, max, name);
    }
}
