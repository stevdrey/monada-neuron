package monada.neuron.context;

import monada.neuron.aeon.AeonInputResult;

import java.util.Objects;
import java.util.UUID;

/** One completed Aeon input result retained by a cognitive cycle. */
public record CognitiveAeonResult(UUID aeonId, AeonInputResult inputResult) {

    /** Validates the result association. */
    public CognitiveAeonResult {
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        Objects.requireNonNull(inputResult, "inputResult must not be null");
    }
}
