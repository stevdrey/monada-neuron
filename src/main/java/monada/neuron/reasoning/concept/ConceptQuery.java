package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralPattern;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Input of one next-concept prediction (contract {@code ncm-wave-cognition/1} section 8.2).
 *
 * <p>{@code previousConceptCode} is the concept believed for the window before the current one. In an evaluation it
 * is the predictor's <b>own</b> earlier prediction for that window, or absent at the start of a sequence or after an
 * abstention; it is never a ground-truth label of a test window (contract section 12.3). It may be any concept of the
 * hierarchy, including a coarse one.
 *
 * @param evidence spectral pattern of the current window
 * @param previousConceptCode host label of the concept believed for the preceding window, or empty
 */
public record ConceptQuery(SpectralPattern evidence, OptionalLong previousConceptCode) {

    /** Validates the references. */
    public ConceptQuery {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(previousConceptCode, "previousConceptCode must not be null");
    }

    /** Returns a query with no previous concept. */
    public static ConceptQuery of(SpectralPattern evidence) {
        return new ConceptQuery(evidence, OptionalLong.empty());
    }
}
