package consumer.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The one gate at which a host would act: at Phase 2 it refuses and counts. */
class SideEffectBoundaryTest {

    // covers: AC-3
    @Test
    void startsAtZeroAttempts() {
        assertEquals(0, new SideEffectBoundary().reachedCalls());
    }

    // covers: AC-3
    @Test
    void executeRefusesAndCountsTheAttempt() {
        var boundary = new SideEffectBoundary();

        var refused = assertThrows(UnsupportedOperationException.class, () -> boundary.execute("local-small"));

        assertEquals("advice is not authorization: this host has no execution path", refused.getMessage());
        assertEquals(1, boundary.reachedCalls());
    }

    // covers: AC-4
    @Test
    void accessRefusesAndCountsTheAttempt() {
        var boundary = new SideEffectBoundary();

        var refused = assertThrows(UnsupportedOperationException.class, () -> boundary.access("src/main/java"));

        assertEquals("routing never accesses paths: this host has no file access path", refused.getMessage());
        assertEquals(1, boundary.reachedCalls());
    }

    // covers: AC-3
    @Test
    void everyRefusedCallIsCounted() {
        var boundary = new SideEffectBoundary();

        assertThrows(UnsupportedOperationException.class, () -> boundary.execute("a"));
        assertThrows(UnsupportedOperationException.class, () -> boundary.access("b"));
        assertThrows(UnsupportedOperationException.class, () -> boundary.execute("c"));

        assertEquals(3, boundary.reachedCalls());
    }

    // covers: AC-3
    @Test
    void nullArgumentsAreRejectedBeforeCounting() {
        var boundary = new SideEffectBoundary();

        assertThrows(NullPointerException.class, () -> boundary.execute(null));
        assertThrows(NullPointerException.class, () -> boundary.access(null));

        assertEquals(0, boundary.reachedCalls());
    }
}
