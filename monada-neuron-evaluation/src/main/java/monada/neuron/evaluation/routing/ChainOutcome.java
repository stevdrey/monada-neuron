package monada.neuron.evaluation.routing;

import java.util.Objects;

/**
 * Whole-chain facts of one execution, kept apart from the routed stage's own outcome and usage.
 *
 * @param attempts attempts in the chain up to acceptance (or all recorded attempts without acceptance), at least 1
 * @param cost hypothetical cost to the accepted outcome of the whole chain
 */
public record ChainOutcome(int attempts, ReplayCost cost) {

    /** Validates the fields. */
    public ChainOutcome {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, got: " + attempts);
        }
        Objects.requireNonNull(cost, "cost must not be null");
    }

    /** Attempts beyond the first: failed tries, repairs and retries. */
    public int rework() {
        return attempts - 1;
    }
}
