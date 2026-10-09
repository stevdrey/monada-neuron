package monada.neuron.routing;

import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value snapshot of the caller's routing preference state, passed to {@code decide}.
 *
 * <p>{@code decide} sees only this snapshot, so it stays pure and later adaptation can never change a snapshot
 * already published. Cohorts are stored in canonical order; the list is bounded by {@value #MAX_COHORTS}.
 * {@code processedCutoff} is the export/rebuild watermark: the greatest ledger sequence the caller examined.
 *
 * @param scopeId scope the snapshot belongs to
 * @param featureSchemaVersion feature schema the cohort mapping was built for
 * @param evaluationPolicyId evaluation policy id the snapshot is partitioned by
 * @param evaluationPolicyVersion evaluation policy version
 * @param mappingVersion state-transition definition version
 * @param producedByPolicyId routing policy that produced the snapshot (provenance only)
 * @param producedByPolicyVersion version of that policy
 * @param processedCutoff non-negative watermark
 * @param cohorts cohort preferences, unique per (stage kind, bucket, route)
 */
public record RoutingPreference(
        String scopeId,
        String featureSchemaVersion,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        String mappingVersion,
        String producedByPolicyId,
        String producedByPolicyVersion,
        long processedCutoff,
        List<CohortPreference> cohorts) {

    /** Maximum cohorts per snapshot (contract section 4). */
    public static final int MAX_COHORTS = 256;

    /** Validates bounds and uniqueness and canonicalizes order. */
    public RoutingPreference {
        RoutingTokens.require(scopeId, "scopeId");
        RoutingTokens.require(featureSchemaVersion, "featureSchemaVersion");
        RoutingTokens.require(evaluationPolicyId, "evaluationPolicyId");
        RoutingTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        RoutingTokens.require(mappingVersion, "mappingVersion");
        RoutingTokens.require(producedByPolicyId, "producedByPolicyId");
        RoutingTokens.require(producedByPolicyVersion, "producedByPolicyVersion");
        if (processedCutoff < 0) {
            throw new IllegalArgumentException("processedCutoff must be non-negative, got: " + processedCutoff);
        }
        Objects.requireNonNull(cohorts, "cohorts must not be null");
        if (cohorts.size() > MAX_COHORTS) {
            throw new IllegalArgumentException(
                    "a snapshot allows at most " + MAX_COHORTS + " cohorts, got: " + cohorts.size());
        }
        var sorted = new ArrayList<>(cohorts);
        sorted.forEach(cohort -> Objects.requireNonNull(cohort, "cohort must not be null"));
        sorted.sort(CohortPreference.ORDER);
        for (int i = 1; i < sorted.size(); i++) {
            if (CohortPreference.ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
                var duplicate = sorted.get(i);
                throw new IllegalArgumentException("duplicate cohort: " + duplicate.stageKind() + "/"
                        + duplicate.cohortBucket() + "/" + duplicate.route());
            }
        }
        cohorts = List.copyOf(sorted);
    }

    /**
     * Valid cold-start snapshot: right bindings, no cohorts, watermark zero.
     *
     * @param request the request the snapshot will accompany (supplies scope, schema and evaluation policy)
     * @param definition the state definition the policy uses
     * @param policyId producing routing policy id
     * @param policyVersion producing routing policy version
     */
    public static RoutingPreference empty(
            RoutingRequest request, RoutingStateDefinition definition, String policyId, String policyVersion) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        return new RoutingPreference(request.scopeId(), request.features().schemaVersion(),
                request.evaluationPolicyId(), request.evaluationPolicyVersion(), definition.mappingVersion(),
                policyId, policyVersion, 0L, List.of());
    }

    /**
     * Checks the admission rules of contract section 2 against a request.
     *
     * @return every failed rule, unique and in {@link StateMismatch} order; empty when compatible
     */
    public List<StateMismatch> mismatches(RoutingRequest request, RoutingStateDefinition definition) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        var found = new ArrayList<StateMismatch>(StateMismatch.values().length);
        if (!scopeId.equals(request.scopeId())) {
            found.add(StateMismatch.SCOPE_MISMATCH);
        }
        String schema = request.features().schemaVersion();
        if (!featureSchemaVersion.equals(schema) || !definition.cohortMapping().featureSchemaVersion().equals(schema)) {
            found.add(StateMismatch.FEATURE_SCHEMA_MISMATCH);
        }
        if (!evaluationPolicyId.equals(request.evaluationPolicyId())
                || !evaluationPolicyVersion.equals(request.evaluationPolicyVersion())) {
            found.add(StateMismatch.EVALUATION_POLICY_MISMATCH);
        }
        if (!mappingVersion.equals(definition.mappingVersion())) {
            found.add(StateMismatch.MAPPING_VERSION_MISMATCH);
        }
        if (processedCutoff > request.cutoff()) {
            found.add(StateMismatch.PROCESSED_CUTOFF_NEWER);
        }
        return found;
    }

    /** The identity of this snapshot as recorded in decision provenance. */
    public StateBinding binding() {
        return new StateBinding(scopeId, featureSchemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                mappingVersion, processedCutoff, producedByPolicyId, producedByPolicyVersion);
    }
}
