package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.EnumSet;

/**
 * The legacy {@code route-lex/1} verification of the three-argument {@link RoutingReasoningStage} constructor (Forge
 * Routing Contract v1, section 17.5): the ordering is recomputed from the {@link PolicyParameters} and the cohort bucket
 * the decision itself records, because nothing independent is available.
 *
 * <p>A policy can therefore choose the configuration it is checked against, and the feature-schema check covers only the
 * schema those parameters record. It is not independent verification; {@link LexicographicDecisionVerifier} is. It runs
 * after the stage's mandatory validation. Instances are immutable and stateless.
 */
final class SelfReportedLexicographicVerifier implements RoutingDecisionVerifier {

    private final LexicographicOrdering ordering = new LexicographicOrdering("recorded");

    @Override
    public String policyId() {
        return LexicographicRoutingPolicy.POLICY_ID;
    }

    @Override
    public String policyVersion() {
        return LexicographicRoutingPolicy.POLICY_VERSION;
    }

    @Override
    public String decisionContractVersion() {
        return RoutingDecision.CONTRACT_VERSION;
    }

    @Override
    public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
        ordering.requireReportFor(report, input);
        PolicyParameters parameters = decision.provenance().parameters();
        verifyState(decision.provenance().validation(), parameters, input);
        ordering.verify(decision, report, input, parameters, CohortBinding::cohortBucket);
    }

    /**
     * The admission keys the recorded parameters decide (the policy mapping's feature schema and mapping version) must
     * be reported exactly, and an incompatible decision must report no reason that neither the request nor the
     * parameters imply.
     */
    private void verifyState(StateValidation validation, PolicyParameters parameters, RoutingInput input) {
        if (validation instanceof StateValidation.NotEvaluated) {
            return;
        }
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
                // Implied when RoutingDecisionValidator ran first (Provenance ties a compatible state to the
                // parameters and the validator ties the state to the preference and the request); kept so that this
                // verifier fails closed on its own.
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
}
