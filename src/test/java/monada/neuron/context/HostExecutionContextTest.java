package monada.neuron.context;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostExecutionContextTest {

    @Test
    void referenceRejectsNullBlankAndOverlongValues() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new HostReference(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HostReference("")),
                () -> assertThrows(IllegalArgumentException.class, () -> new HostReference("  \t")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HostReference("x".repeat(HostReference.MAX_LENGTH + 1))),
                () -> assertEquals(
                        HostReference.MAX_LENGTH,
                        new HostReference("x".repeat(HostReference.MAX_LENGTH)).value().length()));
    }

    @Test
    void contextCarriesReferencesByValueWithoutInterpretingThem() {
        var execution = new HostReference("forge-run-42");
        var lookup = new HostReference("task:opaque");

        var context = HostExecutionContext.of(execution).withLookupRef(lookup);

        assertAll(
                () -> assertEquals(execution, context.executionRef()),
                () -> assertEquals(Optional.of(lookup), context.lookupRef()),
                () -> assertEquals(Optional.empty(), HostExecutionContext.of(execution).lookupRef()),
                () -> assertEquals(context, new HostExecutionContext(execution, Optional.of(lookup))));
    }

    @Test
    void contextRejectsNulls() {
        var reference = new HostReference("a");
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> HostExecutionContext.of(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new HostExecutionContext(reference, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> HostExecutionContext.of(reference).withLookupRef(null)));
    }

    @Test
    void cognitiveContextExposesTheHostContextOnlyWhenSupplied() {
        var budget = new CognitiveBudget(1, 1, 1);
        var host = HostExecutionContext.of(new HostReference("a"));

        try (var with = new CognitiveContext(budget, Optional.of(host));
                var without = new CognitiveContext(budget)) {
            assertAll(
                    () -> assertEquals(Optional.of(host), with.hostContext()),
                    () -> assertEquals(Optional.empty(), without.hostContext()),
                    () -> assertThrows(NullPointerException.class, () -> new CognitiveContext(budget, null)));
        }
    }
}
