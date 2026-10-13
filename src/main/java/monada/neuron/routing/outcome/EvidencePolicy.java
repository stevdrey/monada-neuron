package monada.neuron.routing.outcome;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The host's evidence policy: which dimensions are mandatory for acceptance. The set is host policy; Neuron has no
 * built-in default. An empty mandatory set is rejected because it would validate vacuously.
 *
 * @param id evaluation policy id, equal to the Store's {@code evaluationPolicy} id
 * @param version evaluation policy version
 * @param mandatoryDimensions non-empty set of mandatory dimensions; iterates in {@link QualityDimension} order
 */
public record EvidencePolicy(String id, String version, Set<QualityDimension> mandatoryDimensions) {

    /** Validates tokens and copies the set. */
    public EvidencePolicy {
        OpaqueTokens.require(id, "id");
        OpaqueTokens.require(version, "version");
        Objects.requireNonNull(mandatoryDimensions, "mandatoryDimensions must not be null");
        if (mandatoryDimensions.isEmpty()) {
            throw new IllegalArgumentException("mandatoryDimensions must not be empty");
        }
        mandatoryDimensions = Collections.unmodifiableSet(EnumSet.copyOf((Collection<QualityDimension>) mandatoryDimensions));
    }
}
