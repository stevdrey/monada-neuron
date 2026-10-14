package monada.neuron.routing.feedback;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable set of Node bindings of one state partition {@code (scopeId, evaluationPolicy, mappingVersion)}, kept in
 * canonical {@link CohortKey} order so a lookup is an allocation-free binary search (no map is needed for at most
 * {@value #MAX_BINDINGS} entries that are only point-queried).
 *
 * <p>Every binding must belong to the partition's scope, keys are unique, and one Node serves one cohort only, so a
 * single Node can never receive credit for two routes.
 *
 * @param scopeId scope of the partition
 * @param evaluationPolicyId evaluation policy id the partition is built under
 * @param evaluationPolicyVersion evaluation policy version
 * @param mappingVersion composite state-definition version
 * @param bindings bindings, at most {@value #MAX_BINDINGS}
 */
public record TargetBindings(
        String scopeId,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        String mappingVersion,
        List<TargetBinding> bindings) {

    /** Maximum bindings, equal to the maximum cohorts of a state (contract section 4). */
    public static final int MAX_BINDINGS = 256;

    private static final Comparator<TargetBinding> ORDER = Comparator.comparing(TargetBinding::key);

    /** Validates the partition and canonicalizes the bindings. */
    public TargetBindings {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        OpaqueTokens.require(mappingVersion, "mappingVersion");
        bindings = Canonical.sortedUnique(Objects.requireNonNull(bindings, "bindings must not be null"), ORDER,
                MAX_BINDINGS, "bindings");
        Set<UUID> nodes = new HashSet<>(bindings.size() * 2);
        for (TargetBinding binding : bindings) {
            if (!binding.key().scopeId().equals(scopeId)) {
                throw new IllegalArgumentException(
                        "binding " + binding.key() + " does not belong to scope " + scopeId);
            }
            if (!nodes.add(binding.targetNodeId())) {
                throw new IllegalArgumentException("one Node serves two cohorts: " + binding.targetNodeId());
            }
        }
    }

    /** Returns the Node bound to the exact cohort, or empty when the caller bound none. */
    public Optional<UUID> find(CohortKey key) {
        return Optional.ofNullable(lookup(key));
    }

    /** Binary search over the canonical order; {@code null} when absent. */
    UUID lookup(CohortKey key) {
        int low = 0;
        int high = bindings.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            TargetBinding candidate = bindings.get(middle);
            int order = candidate.key().compareTo(key);
            if (order < 0) {
                low = middle + 1;
            } else if (order > 0) {
                high = middle - 1;
            } else {
                return candidate.targetNodeId();
            }
        }
        return null;
    }
}
