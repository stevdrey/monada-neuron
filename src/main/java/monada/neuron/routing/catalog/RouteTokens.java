package monada.neuron.routing.catalog;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Token-set canonicalization and merge helpers for catalog routes and requests. Single-token rules and code point
 * ordering (Forge Routing Contract v1, section 2) live in {@link OpaqueTokens}.
 */
final class RouteTokens {

    /** Maximum tokens in any route-side or request-side token set. */
    static final int MAX_SET = 16;

    private RouteTokens() {
    }

    /**
     * Validates a token set (size first, at most {@value #MAX_SET}, no duplicates) and returns it in code point order.
     * Already canonical input costs one pass and, when immutable, no copy.
     */
    static List<String> canonicalSet(Collection<String> source, String name) {
        Objects.requireNonNull(source, name + " must not be null");
        if (source.size() > MAX_SET) {
            throw new IllegalArgumentException(name + " allows at most " + MAX_SET + " tokens, got: " + source.size());
        }
        for (String token : source) {
            OpaqueTokens.require(token, name + " token");
        }
        return canonicalOrder(source, OpaqueTokens.CODE_POINT_ORDER, duplicate -> {
            throw new IllegalArgumentException(name + " contains a duplicate token: " + duplicate);
        });
    }

    /**
     * Returns an immutable copy of {@code source} in {@code order}. Strictly ascending input (the common case) is
     * verified in one pass without sorting; otherwise a sorted copy is made and equal neighbours are reported to
     * {@code onDuplicate}, which is expected to throw.
     */
    static <T> List<T> canonicalOrder(Collection<T> source, Comparator<? super T> order,
            Consumer<? super T> onDuplicate) {
        boolean ascending = true;
        T previous = null;
        for (T element : source) {
            Objects.requireNonNull(element, "element must not be null");
            if (previous != null && order.compare(previous, element) >= 0) {
                ascending = false;
                break;
            }
            previous = element;
        }
        if (ascending) {
            return List.copyOf(source);
        }
        List<T> sorted = new ArrayList<>(source);
        sorted.sort(order);
        for (int i = 1; i < sorted.size(); i++) {
            if (order.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
                onDuplicate.accept(sorted.get(i));
            }
        }
        return List.copyOf(sorted);
    }

    /** True when the (at most {@value #MAX_SET}) token list contains {@code token}. */
    static boolean contains(List<String> tokens, String token) {
        for (String candidate : tokens) {
            if (candidate.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** True when every element of the sorted {@code required} list occurs in the sorted {@code offered} list. */
    static boolean containsAll(List<String> offered, List<String> required) {
        int i = 0;
        for (String need : required) {
            while (i < offered.size() && OpaqueTokens.compareCodePoints(offered.get(i), need) < 0) {
                i++;
            }
            if (i == offered.size() || !offered.get(i).equals(need)) {
                return false;
            }
            i++;
        }
        return true;
    }

    /** First element, in code point order, common to two sorted lists; {@code null} when disjoint. */
    static String firstCommon(List<String> left, List<String> right) {
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            int c = OpaqueTokens.compareCodePoints(left.get(i), right.get(j));
            if (c == 0) {
                return left.get(i);
            }
            if (c < 0) {
                i++;
            } else {
                j++;
            }
        }
        return null;
    }
}
