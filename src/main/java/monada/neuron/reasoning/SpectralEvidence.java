package monada.neuron.reasoning;

import monada.neuron.memory.ResonanceMemoryResult;

import java.util.Objects;

/**
 * Reference to one cycle-local sampled window by its host provenance, never its samples or spectral components
 * (ADR 0044, contract {@code ncm-wave-cognition/2} section 10.1).
 *
 * <p>The reference is the {@code sourceReference} and {@code windowOffset} of the window's {@code WaveProvenance}, so
 * the host can trace a hypothesis back to the window it supplied for the execution. Unlike {@link SignalEvidence},
 * the cycle cannot validate it against an occurrence: the window never entered the cognitive context as a
 * {@code Signal}. The stage that emits it only references windows its host resolved for the same execution.
 *
 * @param sourceReference non-blank host reference of the window's source, within the shared maximum length
 * @param windowOffset non-negative index of the window's first sample in the source
 * @param relation how the referenced window bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record SpectralEvidence(String sourceReference, long windowOffset, EvidenceRelation relation, double weight)
        implements Evidence {

    /** Validates the reference, offset and weight. */
    public SpectralEvidence {
        Objects.requireNonNull(sourceReference, "sourceReference must not be null");
        if (sourceReference.isBlank()) {
            throw new IllegalArgumentException("sourceReference must not be blank");
        }
        if (sourceReference.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "sourceReference must be at most " + ResonanceMemoryResult.MAX_REFERENCE_LENGTH
                            + " characters, got: " + sourceReference.length());
        }
        if (windowOffset < 0) {
            throw new IllegalArgumentException("windowOffset must be non-negative, got: " + windowOffset);
        }
        Evidence.validate(relation, weight);
    }
}
