package monada.neuron.routing.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Validation and ordering rules for opaque host tokens (Forge Routing Contract v1, section 2).
 *
 * <p>Deliberately mirrors the package-private rules of the {@code features} package, which this issue may not edit.
 */
final class RouteTokens {

    /** Maximum token length in Unicode code points. */
    static final int MAX_CODE_POINTS = 128;

    /** Maximum tokens in any route-side or request-side token set. */
    static final int MAX_SET = 16;

    /** Orders strings by Unicode code point, not by UTF-16 code unit. */
    static final Comparator<String> CODE_POINT_ORDER = RouteTokens::compareCodePoints;

    private RouteTokens() {
    }

    /** Requires 1 to 128 code points, no control characters and no invisible leading or trailing padding. */
    static String require(String token, String name) {
        Objects.requireNonNull(token, name + " must not be null");
        if (token.length() > MAX_CODE_POINTS * 2) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_CODE_POINTS + " code points");
        }
        int codePoints = token.codePointCount(0, token.length());
        if (codePoints < 1 || codePoints > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    name + " must have 1 to " + MAX_CODE_POINTS + " code points, got: " + codePoints);
        }
        if (token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        if (isPadding(token.codePointAt(0)) || isPadding(token.codePointBefore(token.length()))) {
            throw new IllegalArgumentException(name + " must not have leading or trailing whitespace");
        }
        return token;
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
            require(token, name + " token");
        }
        return canonicalOrder(source, CODE_POINT_ORDER, duplicate -> {
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
            while (i < offered.size() && compareCodePoints(offered.get(i), need) < 0) {
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
            int c = compareCodePoints(left.get(i), right.get(j));
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

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }
}
