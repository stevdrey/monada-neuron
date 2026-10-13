package monada.neuron.routing.token;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpaqueTokensTest {

    private static String message(String token) {
        return assertThrows(IllegalArgumentException.class, () -> OpaqueTokens.require(token, "t")).getMessage();
    }

    @Test
    void acceptsBoundaryLengthsAndReturnsTheSameInstance() {
        String one = "a";
        String max = "x".repeat(128);
        assertSame(one, OpaqueTokens.require(one, "t"));
        assertSame(max, OpaqueTokens.require(max, "t"));
        assertEquals("t must have 1 to 128 code points, got: 129", message("x".repeat(129)));
        assertEquals("t must have 1 to 128 code points, got: 0", message(""));
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        String supplementary = "😀".repeat(128);
        assertSame(supplementary, OpaqueTokens.require(supplementary, "t"));
        assertEquals("t exceeds 128 code points", message("😀".repeat(129)));
    }

    @Test
    void rejectsNullControlAndInvisiblePadding() {
        assertEquals("t must not be null", assertThrows(NullPointerException.class,
                () -> OpaqueTokens.require(null, "t")).getMessage());
        assertEquals("t must not contain control characters", message("a\u0000b"));
        for (String padded : new String[] {" a", "a ", " a", "a ", "​a", "a‮"}) {
            assertEquals("t must not have leading or trailing whitespace", message(padded));
        }
    }

    @Test
    void allowsInteriorSpacesAndFormatCharacters() {
        String zwj = "👨‍👩";
        assertSame(zwj, OpaqueTokens.require(zwj, "t"));
        assertEquals("a b", OpaqueTokens.require("a b", "t"));
    }

    @Test
    void textVariantAllowsUpTo512CodePoints() {
        String max = "y".repeat(512);
        assertSame(max, OpaqueTokens.requireText(max, "t"));
        assertEquals("t must have 1 to 512 code points, got: 513",
                assertThrows(IllegalArgumentException.class,
                        () -> OpaqueTokens.requireText("y".repeat(513), "t")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> OpaqueTokens.requireText(" y", "t"));
    }

    @Test
    void ordersByCodePointNotUtf16Unit() {
        String supplementary = "😀";
        String highBmp = "～";
        // UTF-16 order puts the surrogate pair first; code point order puts the supplementary character last.
        assertTrue(supplementary.compareTo(highBmp) < 0);
        assertTrue(OpaqueTokens.compareCodePoints(supplementary, highBmp) > 0);
        assertTrue(OpaqueTokens.CODE_POINT_ORDER.compare(highBmp, supplementary) < 0);
        assertEquals(0, OpaqueTokens.compareCodePoints("ab", "ab"));
        assertTrue(OpaqueTokens.compareCodePoints("ab", "abc") < 0);
    }
}
