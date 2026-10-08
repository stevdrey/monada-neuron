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
     * Requires 1 to 128 code points, no control characters and no leading or trailing whitespace.
     *
     * @return the same token
     */
    static String require(String token, String name) {
        Objects.requireNonNull(token, name + " must not be null");
        // 128 code points never need more than 512 UTF-16 units; reject before counting.
        if (token.length() > MAX_CODE_POINTS * 4) {
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
        if (!token.equals(token.strip())) {
            throw new IllegalArgumentException(name + " must not have leading or trailing whitespace");
        }
        return token;
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
