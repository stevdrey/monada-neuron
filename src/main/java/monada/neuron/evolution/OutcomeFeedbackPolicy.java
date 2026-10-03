package monada.neuron.evolution;

import monada.neuron.monad.CognitiveCycleResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives a bounded {@link OutcomeFeedback} from a completed cognitive cycle.
 *
 * <p>Derivation happens after the cycle returned, so it never adds a backward {@code ACTION ->
 * ADAPTATION} edge: the produced artifact is carried by the caller into a later cycle. Implementations
 * are pure and deterministic. They must not mutate Nodes, adaptation state, the cycle result, or shared
 * state, and must not retain the cycle result, provider payloads, or exceptions.
 */
@FunctionalInterface
public interface OutcomeFeedbackPolicy {

    /**
     * Derives feedback for the cycle's action outcome.
     *
     * @param cycle completed cycle result that may contain an action outcome
     * @param targetNodeIds ordered Node identifiers eligible to receive credit; order defines entry order
     * @param originCycleOrdinal non-negative caller-assigned ordinal that identifies the producing cycle
     * @return the derived feedback, or empty when the cycle produced no action outcome
     * @throws IllegalArgumentException if the ordinal is negative or considered targets repeat
     */
    Optional<OutcomeFeedback> derive(CognitiveCycleResult cycle, List<UUID> targetNodeIds, long originCycleOrdinal);
}
