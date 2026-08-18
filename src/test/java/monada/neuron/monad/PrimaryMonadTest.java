package monada.neuron.monad;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimaryMonadTest {

    @Test
    void registrationKeepsTheFirstAeonCanonicalAndMaintainsInsertionOrder() {
        var monad = new PrimaryMonad(uuid(1));
        var first = aeon(10, AeonPurpose.PERCEPTION);
        var duplicateId = aeon(10, AeonPurpose.REASONING);
        var second = aeon(20, AeonPurpose.ACTION);

        assertAll(
                () -> assertTrue(monad.registerAeon(first)),
                () -> assertFalse(monad.registerAeon(duplicateId)),
                () -> assertTrue(monad.registerAeon(second)),
                () -> assertEquals(java.util.List.of(first, second), java.util.List.copyOf(monad.getAeons())),
                () -> assertSame(first, monad.findAeon(first.getId()).orElseThrow()),
                () -> assertTrue(monad.containsAeon(first.getId())));
    }

    @Test
    void removalAndReregistrationAppendTheAeonAndTheLiveViewIsUnmodifiable() {
        var monad = new PrimaryMonad(uuid(1));
        var first = aeon(10, AeonPurpose.PERCEPTION);
        var second = aeon(20, AeonPurpose.ACTION);
        var view = monad.getAeons();
        monad.registerAeon(first);
        monad.registerAeon(second);

        assertAll(
                () -> assertEquals(java.util.List.of(first, second), java.util.List.copyOf(view)),
                () -> assertThrows(UnsupportedOperationException.class, view::clear),
                () -> assertTrue(monad.unregisterAeon(first.getId())),
                () -> assertFalse(monad.containsAeon(first.getId())),
                () -> assertTrue(monad.registerAeon(first)),
                () -> assertEquals(java.util.List.of(second, first), java.util.List.copyOf(view)),
                () -> assertFalse(monad.unregisterAeon(uuid(99))));
    }

    @Test
    void equalityUsesOnlyTheStableMonadIdentity() {
        assertAll(
                () -> assertEquals(new PrimaryMonad(uuid(1)), new PrimaryMonad(uuid(1))),
                () -> assertFalse(new PrimaryMonad(uuid(1)).equals(new PrimaryMonad(uuid(2)))),
                () -> assertThrows(NullPointerException.class, () -> new PrimaryMonad(null)));
    }

    private Aeon aeon(long id, AeonPurpose purpose) {
        return new Aeon(uuid(id), purpose);
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
