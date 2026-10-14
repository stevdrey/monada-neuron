package monada.neuron.routing.token;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, canonical, immutable copies shared by the routing packages: sorted by a total order, null elements and
 * duplicates rejected. Public only because the artifact has no JPMS boundary (ADR 0025, ADR 0028); it is shared
 * infrastructure, not a supported extension point.
 */
public final class CanonicalLists {

    private CanonicalLists() {
    }

    /**
     * Returns an immutable sorted copy.
     *
     * @param source elements, in any order
     * @param order total order that also defines equality for the duplicate check
     * @param max maximum number of elements
     * @param name name used in exception messages
     * @return the sorted immutable copy
     * @throws NullPointerException if the source or an element is null
     * @throws IllegalArgumentException if there are more than {@code max} elements or two compare equal
     */
    public static <T> List<T> canonical(Collection<T> source, Comparator<? super T> order, int max, String name) {
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
