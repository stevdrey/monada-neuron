package monada.neuron.routing.features;

import java.util.Comparator;
import java.util.Objects;

/** Validation and ordering rules for opaque host tokens (Forge Routing Contract v1, section 2). */
final class FeatureTokens {

    /** Maximum token length in Unicode code points. */
    static final int MAX_CODE_POINTS = 128;

    /** Orders strings by Unicode code point, not by UTF-16 code unit. */
    static final Comparator<String> CODE_POINT_ORDER = FeatureTokens::compareCodePoints;

    private FeatureTokens() {
    }

    /**
     * Requires 1 to 128 code points, no control characters and no leading or trailing whitespace, Unicode
     * space separator (for example NBSP, which {@link String#strip()} does not remove) or format character
     * (for example U+200B). Interior format characters stay valid, so emoji ZWJ sequences work.
     *
     * @return the same token
     */
    static String require(String token, String name) {
        Objects.requireNonNull(token, name + " must not be null");
        // A code point is at most 2 UTF-16 units, so more than 256 units means more than 128 code points.
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

    /** Invisible padding: whitespace, Unicode space separators and format characters such as U+200B or U+202E. */
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
