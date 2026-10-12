package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.EnumSet;

/**
 * The {@code route-lex/1} part of decision verification (Forge Routing Contract v1, sections 3.3 and 17.5): state
 * admission against the decision's recorded {@link PolicyParameters}, the exact lexicographic ranking and its bounded
 * explanation, truncation and the tier cap.
 *
 * <p>It runs after {@link RoutingDecisionValidator} and relies on it for provenance, request-derived admission and hard
 * eligibility. It recomputes the ranking with {@link LexicographicRanking}, the same definition the reference policy
 * decides with, so a match proves agreement with that definition and not its independent correctness. The ranking
 * inputs (parameters and cohort bucket) are those the decision records; the bucket is not re-derived, because the
 * {@link RoutingPolicy} port does not expose the policy's {@link CohortMapping}, and the feature-schema check covers
 * only the schema the parameters record. Instances are immutable and stateless.
 */
final class LexicographicDecisionVerifier {

    /**
     * Verifies a decision that already passed {@link RoutingDecisionValidator}.
     *
     * @param decision the policy's decision
     * @param input the resolved inputs the policy decided on
     * @param report the eligibility filter's report for {@code input}, the same one the validator used
     * @throws IllegalStateException if the decision disagrees with the contract ordering recomputed from its recorded
     *         parameters
     */
    void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
        verifyState(decision.provenance().validation(), decision.provenance().parameters(), input);
        switch (decision) {
            case RoutingDecision.Selected selected -> verifySelectedRanking(selected, report, input);
            case RoutingDecision.Abstain abstain when abstain.reason() == AbstainReason.POLICY_TRADEOFF_UNRESOLVED ->
                    verifyTradeoff(abstain, report, input);
            case RoutingDecision.Abstain ignored -> { }
            case RoutingDecision.NoEligibleRoute ignored -> { }
        }
    }

    /**
     * The admission keys the recorded parameters decide (the policy mapping's feature schema and mapping version) must
     * be reported exactly, and an incompatible decision must report no reason that neither the request nor the
     * parameters imply.
     */
    private void verifyState(StateValidation validation, PolicyParameters parameters, RoutingInput input) {
        RoutingPreference preference = input.preference();
        var implied = EnumSet.noneOf(StateMismatch.class);
        if (!parameters.stateFeatureSchemaVersion().equals(input.request().features().schemaVersion())) {
            implied.add(StateMismatch.FEATURE_SCHEMA_MISMATCH);
        }
        if (!preference.mappingVersion().equals(parameters.stateMappingVersion())) {
            implied.add(StateMismatch.MAPPING_VERSION_MISMATCH);
        }
        switch (validation) {
            case StateValidation.Compatible compatible -> {
                if (!implied.isEmpty()) {
                    throw new IllegalStateException("policy decision accepted a preference snapshot that conflicts "
                            + "with the policy mapping: " + implied);
                }
            }
            case StateValidation.Incompatible incompatible -> {
                if (!incompatible.reasons().containsAll(implied)) {
                    throw new IllegalStateException("policy decision omits state mismatches the recorded policy "
                            + "parameters imply: " + implied);
                }
                var existing = EnumSet.copyOf(implied);
                existing.addAll(preference.mismatches(input.request()));
                for (StateMismatch reason : incompatible.reasons()) {
                    if (!existing.contains(reason)) {
                        throw new IllegalStateException("policy decision reports a state mismatch that does not "
                                + "exist: " + reason);
                    }
                }
            }
            case StateValidation.NotEvaluated notEvaluated -> { }
        }
    }

    /**
     * A {@code Selected} decision must equal the contract ordering recomputed from its recorded parameters and cohort
     * bucket (the bucket itself cannot be re-derived), and its winner must not exceed the recorded tier cap.
     */
    private void verifySelectedRanking(RoutingDecision.Selected selected, EligibilityReport report, RoutingInput input) {
        PolicyParameters parameters = selected.provenance().parameters();
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(),
                        selected.cohortBinding().cohortBucket());
        boolean sameRanking = expected.order().getFirst().route().key().equals(selected.route())
                && expected.candidates().equals(selected.candidates())
                && expected.truncated() == selected.candidatesTruncated()
                && expected.applied().equals(selected.rulesApplied())
                && expected.skipped().equals(selected.rulesSkipped())
                && expected.candidates().getFirst().placement().basis() == selected.basis();
        if (!sameRanking) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + " (applied " + expected.applied() + ", skipped " + expected.skipped()
                    + "), got " + selected.candidates() + " (applied " + selected.rulesApplied() + ", skipped "
                    + selected.rulesSkipped() + ", basis " + selected.basis() + ")");
        }
        if (exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy selected " + selected.route() + " (tier "
                    + expected.order().getFirst().tier() + ") above the recorded tier cap "
                    + parameters.maxAutoSelectTier());
        }
    }

    /**
     * A tier-cap abstention is the only tradeoff trigger. The ranking is recomputed with the cohort the abstention
     * recorded and must be reproduced, and the recomputed winner must exceed the recorded cap.
     */
    private void verifyTradeoff(RoutingDecision.Abstain abstain, EligibilityReport report, RoutingInput input) {
        PolicyParameters parameters = abstain.provenance().parameters();
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(),
                        abstain.cohortBinding().orElseThrow().cohortBucket());
        if (!expected.candidates().equals(abstain.candidates())
                || expected.truncated() != abstain.candidatesTruncated()) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + ", got " + abstain.candidates());
        }
        if (!exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy abstained on the tier cap but the winner does not exceed the "
                    + "recorded cap " + parameters.maxAutoSelectTier());
        }
    }

    private boolean exceedsCap(LexicographicRanking.Result ranking, PolicyParameters parameters) {
        return parameters.maxAutoSelectTier().isPresent()
                && ranking.order().getFirst().tier() > parameters.maxAutoSelectTier().getAsInt();
    }
}
