package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.List;
import java.util.Objects;

/**
 * The trusted {@code route-lex/1} ordering verifier (Forge Routing Contract v1, sections 3.3, 17.5 and 18): it checks a
 * decision against host-supplied {@link PolicyParameters} and {@link RoutingStateDefinition} instead of against what the
 * decision records about itself.
 *
 * <p>The decision's recorded identity and parameters must equal the host's, its admission result must equal the mismatches
 * the trusted definition implies, and the cohort bucket is derived with the trusted {@link CohortMapping} and compared with
 * the recorded {@link CohortBinding} before the ranking is recomputed from trusted values only.
 *
 * <p>It runs after the stage's mandatory validation and relies on it for provenance binding, request-derived admission and
 * hard eligibility. It recomputes the ranking with {@link LexicographicRanking}, the same definition the reference policy
 * decides with, so a match proves agreement with that definition and not its independent correctness. Instances are
 * immutable and stateless. The verifier targets the ordering of {@link RoutingDecision#CONTRACT_VERSION}; a policy under
 * another identity may reuse it only if its decisions follow that same ordering.
 */
public final class LexicographicDecisionVerifier implements RoutingDecisionVerifier {

    private final String policyId;
    private final String policyVersion;
    private final PolicyParameters parameters;
    private final RoutingStateDefinition definition;
    private final LexicographicOrdering ordering = new LexicographicOrdering("trusted");

    private LexicographicDecisionVerifier(
            String policyId, String policyVersion, PolicyParameters parameters, RoutingStateDefinition definition) {
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.parameters = parameters;
        this.definition = definition;
    }

    /**
     * Verifier for the reference policy identity {@code route-lex}/{@code 1} that checks decisions against host-supplied
     * configuration.
     *
     * @param parameters the effective parameters the host configured the policy with
     * @param definition the state definition (cohort mapping and mapping version) the host configured
     * @throws IllegalArgumentException if the parameters disagree with the definition
     */
    public static LexicographicDecisionVerifier trusted(PolicyParameters parameters, RoutingStateDefinition definition) {
        return trusted(LexicographicRoutingPolicy.POLICY_ID, LexicographicRoutingPolicy.POLICY_VERSION, parameters,
                definition);
    }

    /**
     * Verifier for a policy with its own identity whose decisions follow the {@code route-lex/1} ordering.
     *
     * @param policyId id the policy records in its decisions
     * @param policyVersion version the policy records in its decisions
     * @param parameters the effective parameters the host configured the policy with
     * @param definition the state definition (cohort mapping and mapping version) the host configured
     * @throws IllegalArgumentException if the parameters disagree with the definition
     */
    public static LexicographicDecisionVerifier trusted(
            String policyId, String policyVersion, PolicyParameters parameters, RoutingStateDefinition definition) {
        RoutingTokens.require(policyId, "policyId");
        RoutingTokens.require(policyVersion, "policyVersion");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        if (!parameters.stateMappingVersion().equals(definition.mappingVersion())
                || !parameters.stateFeatureSchemaVersion().equals(definition.cohortMapping().featureSchemaVersion())) {
            throw new IllegalArgumentException("trusted parameters record mapping " + parameters.stateMappingVersion()
                    + " and schema " + parameters.stateFeatureSchemaVersion() + " but the state definition is "
                    + definition.mappingVersion() + " over schema " + definition.cohortMapping().featureSchemaVersion());
        }
        return new LexicographicDecisionVerifier(policyId, policyVersion, parameters, definition);
    }

    @Override
    public String policyId() {
        return policyId;
    }

    @Override
    public String policyVersion() {
        return policyVersion;
    }

    @Override
    public String decisionContractVersion() {
        return RoutingDecision.CONTRACT_VERSION;
    }

    /**
     * Verifies a decision that already passed the stage's mandatory validation.
     *
     * @param decision the policy's decision
     * @param input the resolved inputs the policy decided on
     * @param report the eligibility filter's report for {@code input}, the same one the validation used
     * @throws IllegalArgumentException if {@code report} was computed for another catalog than {@code input}'s
     * @throws IllegalStateException if the decision disagrees with the host's configuration or with the contract
     *         ordering recomputed from it
     */
    @Override
    public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
        ordering.requireReportFor(report, input);
        verifyProvenance(decision.provenance());
        verifyState(decision, input);
        ordering.verify(decision, report, input, parameters, recorded -> bucket(recorded, input));
    }

    /** The decision's identity and recorded configuration must be exactly what the host configured. */
    private void verifyProvenance(Provenance provenance) {
        if (!policyId.equals(provenance.policyId()) || !policyVersion.equals(provenance.policyVersion())) {
            throw new IllegalStateException("policy decision records policy " + provenance.policyId() + "/"
                    + provenance.policyVersion() + " but this verifier checks " + policyId + "/" + policyVersion);
        }
        if (!parameters.equals(provenance.parameters())) {
            throw new IllegalStateException("policy decision records parameters " + provenance.parameters()
                    + " that differ from the trusted configuration " + parameters);
        }
    }

    /**
     * The admission result must equal the mismatches the request, the preference and the trusted state definition imply,
     * in their normative order: none omitted, none spurious. The state is consulted exactly when a route is eligible.
     */
    private void verifyState(RoutingDecision decision, RoutingInput input) {
        StateValidation validation = decision.provenance().validation();
        switch (decision) {
            case RoutingDecision.NoEligibleRoute ignored -> { }
            case RoutingDecision.Abstain abstain when abstain.reason() == AbstainReason.STATE_INCOMPATIBLE -> {
                List<StateMismatch> expected = input.preference().mismatches(input.request(), definition);
                if (!(validation instanceof StateValidation.Incompatible incompatible)) {
                    throw new IllegalStateException("a state-incompatible abstention must record the mismatches "
                            + expected + ", got " + validation);
                }
                if (!incompatible.reasons().equals(expected)) {
                    throw new IllegalStateException("policy decision reports state mismatches "
                            + incompatible.reasons() + " but the trusted state definition implies " + expected);
                }
            }
            case RoutingDecision.Selected ignored -> requireAdmitted(validation, input);
            case RoutingDecision.Abstain ignored -> requireAdmitted(validation, input);
        }
    }

    private void requireAdmitted(StateValidation validation, RoutingInput input) {
        List<StateMismatch> expected = input.preference().mismatches(input.request(), definition);
        if (!expected.isEmpty()) {
            throw new IllegalStateException("policy decision ranked routes although the trusted state definition "
                    + "implies the mismatches " + expected);
        }
        // Defensive: the decision records already reject a ranked decision without a compatible state.
        if (!(validation instanceof StateValidation.Compatible)) {
            throw new IllegalStateException("a ranked decision must record a compatible state, got " + validation);
        }
    }

    /**
     * The bucket the ranking runs with: derived with the host's {@link CohortMapping}, and the recorded binding must
     * equal it, including both mapping versions and the request's feature schema.
     */
    private String bucket(CohortBinding recorded, RoutingInput input) {
        String bucket;
        CohortBinding expected;
        try {
            bucket = definition.cohortMapping().bucketOf(input.request().features());
            expected = new CohortBinding(bucket, definition.cohortMapping().bucketMappingVersion(),
                    definition.mappingVersion(), input.request().features().schemaVersion());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("the trusted cohort mapping " + definition.cohortMapping()
                    .bucketMappingVersion() + " produced an invalid cohort binding: " + invalid.getMessage(), invalid);
        }
        if (!expected.equals(recorded)) {
            throw new IllegalStateException("policy decision records cohort binding " + recorded
                    + " but the trusted mapping derives " + expected);
        }
        return bucket;
    }
}
