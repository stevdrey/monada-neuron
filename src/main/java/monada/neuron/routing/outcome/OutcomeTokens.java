package monada.neuron.routing.outcome;

import java.util.Objects;

/** Opaque-token rules of contract section 2, mirrored because the helpers of the other routing packages are package-private. */
final class OutcomeTokens {

    /** Maximum identifier length in Unicode code points. */
    static final int MAX_TOKEN = 128;

    /** Maximum length of an artifact reference or free text, as in the Store's opaque artifact references. */
    static final int MAX_TEXT = 512;

    private OutcomeTokens() {
    }

    /** Requires 1 to 128 code points, no control characters and no leading or trailing padding. */
    static String token(String value, String name) {
        return require(value, name, MAX_TOKEN);
    }

    /** Requires 1 to 512 code points under the same character rules. */
    static String text(String value, String name) {
        return require(value, name, MAX_TEXT);
    }

    private static String require(String value, String name, int max) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.length() > max * 2) {
            throw new IllegalArgumentException(name + " exceeds " + max + " code points");
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > max) {
            throw new IllegalArgumentException(name + " must have 1 to " + max + " code points, got: " + codePoints);
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        if (isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException(name + " must not have leading or trailing whitespace");
        }
        return value;
    }

    /** Orders strings by Unicode code point, not by UTF-16 code unit. */
    static int compare(String left, String right) {
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

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
