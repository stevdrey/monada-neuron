package monada.neuron.routing.token;

import java.util.Comparator;
import java.util.Objects;

/**
 * Validation and ordering rules for opaque host tokens (Forge Routing Contract v1, section 2), shared by the routing
 * packages. Public only because the Java baseline has no module boundary; see ADR 0028.
 */
public final class OpaqueTokens {

    /** Maximum token length in Unicode code points. */
    public static final int MAX_CODE_POINTS = 128;

    /**
     * Maximum length of an artifact reference or free text, as in the Store's opaque artifact references. A Neuron
     * outcome-package extension: contract section 2 itself only defines the {@value #MAX_CODE_POINTS}-code-point token.
     */
    public static final int MAX_TEXT_CODE_POINTS = 512;

    /** Orders strings by Unicode code point, not by UTF-16 code unit. */
    public static final Comparator<String> CODE_POINT_ORDER = OpaqueTokens::compareCodePoints;

    private OpaqueTokens() {
    }

    /**
     * Requires 1 to 128 code points, no control characters and no leading or trailing whitespace, Unicode
     * space separator (for example NBSP, which {@link String#strip()} does not remove) or format character
     * (for example U+200B). Interior format characters stay valid, so emoji ZWJ sequences work.
     *
     * @return the same token
     */
    public static String require(String token, String name) {
        return require(token, name, MAX_CODE_POINTS);
    }

    /** Requires 1 to 512 code points under the same character rules as {@link #require(String, String)}. */
    public static String requireText(String text, String name) {
        return require(text, name, MAX_TEXT_CODE_POINTS);
    }

    private static String require(String value, String name, int max) {
        Objects.requireNonNull(value, name + " must not be null");
        // A code point is at most 2 UTF-16 units, so more than 2 * max units means more than max code points.
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

    /** Compares two strings by Unicode code point, not by UTF-16 code unit. */
    public static int compareCodePoints(String left, String right) {
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

    /** Invisible padding: whitespace, Unicode space separators and format characters such as U+200B or U+202E. */
    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
