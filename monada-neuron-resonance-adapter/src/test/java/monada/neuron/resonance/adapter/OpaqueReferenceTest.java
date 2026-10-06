package monada.neuron.resonance.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OpaqueReferenceTest {

    private final OpaqueReference reference = new OpaqueReference();

    @Test
    void usesPrefixAndTruncatedSha256Hex() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("rs-ba7816bf8f01cfea414140de5dae2223", reference.of("abc"));
    }

    @Test
    void isStableAndDistinguishesAtomIds() {
        assertEquals(reference.of("atom-1"), reference.of("atom-1"));
        assertNotEquals(reference.of("atom-1"), reference.of("atom-2"));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> reference.of(null));
    }
}
