package monada.neuron.resonance.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

// Temporary: proves the CI adapter job fails on an adapter test failure (Issue #84). Reverted next commit.
final class DeliberateCiFailureTest {

    @Test
    void failsOnPurpose() {
        fail("Deliberate failure to verify the CI adapter job (Issue #84)");
    }
}
