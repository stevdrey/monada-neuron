package monada.neuron.evaluation.routing;

import java.util.Objects;

/**
 * Whole-chain facts of one execution, kept apart from the routed stage's own outcome and usage.
 *
 * <p>Attempts and stages are independent: a repair adds an attempt, and an execution may route several stages with
 * one attempt each. The stage count is what a synthetic observation reports as its attribution's
 * {@code chainStageCount} (contract "StageAttribution": the real number of stages in the execution); for a logged
 * chain it mirrors the exported observation's.
 *
 * @param attempts attempts in the chain up to acceptance (or all recorded attempts without acceptance), at least 1
 * @param stages stages in the execution, at least 1
 * @param cost hypothetical cost to the accepted outcome of the whole chain
 */
public record ChainOutcome(int attempts, int stages, ReplayCost cost) {

    /** Validates the fields. */
    public ChainOutcome {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, got: " + attempts);
        }
        if (stages < 1) {
            throw new IllegalArgumentException("stages must be at least 1, got: " + stages);
        }
        Objects.requireNonNull(cost, "cost must not be null");
    }

    /** A single-stage chain. */
    public ChainOutcome(int attempts, ReplayCost cost) {
        this(attempts, 1, cost);
    }
}
