package monada.neuron.routing.outcome;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The host's evidence policy: which dimensions are mandatory for acceptance. The set is host policy; Neuron has no
 * built-in default. An empty mandatory set is rejected because it would validate vacuously.
 *
 * @param id evaluation policy id, equal to the Store's {@code evaluationPolicy} id
 * @param version evaluation policy version
 * @param mandatoryDimensions non-empty set of mandatory dimensions
 */
public record EvidencePolicy(String id, String version, Set<QualityDimension> mandatoryDimensions) {

    /** Validates tokens and copies the set. */
    public EvidencePolicy {
        OutcomeTokens.token(id, "id");
        OutcomeTokens.token(version, "version");
        Objects.requireNonNull(mandatoryDimensions, "mandatoryDimensions must not be null");
        if (mandatoryDimensions.isEmpty()) {
            throw new IllegalArgumentException("mandatoryDimensions must not be empty");
        }
        mandatoryDimensions = Set.copyOf(EnumSet.copyOf((Collection<QualityDimension>) mandatoryDimensions));
    }
}
