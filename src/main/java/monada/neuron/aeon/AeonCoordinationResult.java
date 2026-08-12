package monada.neuron.aeon;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered result of coordinating all requested Aeon inputs.
 *
 * @param inputResults one result per input, in the original input order
 */
public record AeonCoordinationResult(List<AeonInputResult> inputResults) {

    private static final AeonCoordinationResult EMPTY = new AeonCoordinationResult(List.of());

    /** Validates and snapshots the per-input results. */
    public AeonCoordinationResult {
        Objects.requireNonNull(inputResults, "inputResults must not be null");
        inputResults = List.copyOf(inputResults);
    }

    /** Returns the shared successful result for a coordination with no inputs. */
    public static AeonCoordinationResult empty() {
        return EMPTY;
    }
}
