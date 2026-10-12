package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code route-lex/1} part of decision verification (Forge Routing Contract v1, sections 3.3, 17.5 and 18): state
 * admission, the exact lexicographic ranking and its bounded explanation, truncation and the tier cap.
 *
 * <p>It runs after {@link RoutingDecisionValidator} and relies on it for provenance, request-derived admission and hard
 * eligibility. It recomputes the ranking with {@link LexicographicRanking}, the same definition the reference policy
 * decides with, so a match proves agreement with that definition and not its independent correctness. Instances are
 * immutable and stateless, and come in two modes:
 *
 * <ul>
 *   <li><b>trusted</b> ({@link #trusted}): the host supplies the effective {@link PolicyParameters} and the
 *       {@link RoutingStateDefinition}. The decision's recorded identity and parameters must equal them, its admission
 *       result must equal the mismatches the trusted definition implies, and the cohort bucket is derived with the
 *       trusted {@link CohortMapping} and compared with the recorded {@link CohortBinding} before the ranking is
 *       recomputed from trusted values only.
 *   <li><b>self-reported</b> (package-private; used by the legacy stage constructor): the parameters and the cohort
 *       bucket are those the decision records, because nothing independent is available. A policy can therefore
 *       choose the configuration it is checked against, and the feature-schema check covers only the schema those
 *       parameters record.
 * </ul>
 */
public final class LexicographicDecisionVerifier implements RoutingDecisionVerifier {

    private final Optional<Trusted> trusted;

    private LexicographicDecisionVerifier(Optional<Trusted> trusted) {
        this.trusted = trusted;
    }

    /** What the host supplies independently of every decision. */
    private record Trusted(PolicyParameters parameters, RoutingStateDefinition definition) {
    }

    /**
     * Verifier that checks decisions against host-supplied configuration.
     *
     * @param parameters the effective parameters the host configured the policy with
     * @param definition the state definition (cohort mapping and mapping version) the host configured
     * @throws IllegalArgumentException if the parameters disagree with the definition
     */
    public static LexicographicDecisionVerifier trusted(PolicyParameters parameters, RoutingStateDefinition definition) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        if (!parameters.stateMappingVersion().equals(definition.mappingVersion())
                || !parameters.stateFeatureSchemaVersion().equals(definition.cohortMapping().featureSchemaVersion())) {
            throw new IllegalArgumentException("trusted parameters record mapping " + parameters.stateMappingVersion()
                    + " and schema " + parameters.stateFeatureSchemaVersion() + " but the state definition is "
                    + definition.mappingVersion() + " over schema " + definition.cohortMapping().featureSchemaVersion());
        }
        return new LexicographicDecisionVerifier(Optional.of(new Trusted(parameters, definition)));
    }

    /** The legacy verifier: parameters and cohort bucket are taken from the decision itself. */
    static LexicographicDecisionVerifier selfReported() {
        return new LexicographicDecisionVerifier(Optional.empty());
    }

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

    /**
     * Verifies a decision that already passed {@link RoutingDecisionValidator}.
     *
     * @param decision the policy's decision
     * @param input the resolved inputs the policy decided on
     * @param report the eligibility filter's report for {@code input}, the same one the validator used
     * @throws IllegalArgumentException if {@code report} was computed for another catalog than {@code input}'s
     * @throws IllegalStateException if the decision disagrees with the contract ordering recomputed from the trusted
     *         (or, in self-reported mode, the recorded) parameters
     */
    @Override
    public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
        // Checked here too, because the ranking reads report.eligible() and this verifier must stay usable on its own.
        if (!report.catalogVersion().equals(input.catalog().catalogVersion())) {
            throw new IllegalArgumentException("eligibility report was computed for catalog " + report.catalogVersion()
                    + ", not " + input.catalog().catalogVersion());
        }
        Provenance provenance = decision.provenance();
        PolicyParameters parameters = trusted.map(Trusted::parameters).orElse(provenance.parameters());
        if (trusted.isPresent()) {
            verifyTrustedProvenance(provenance, trusted.get());
            verifyTrustedState(decision, input, trusted.get().definition());
        } else {
            verifyState(provenance.validation(), parameters, input);
        }
        switch (decision) {
            case RoutingDecision.Selected selected -> verifySelectedRanking(
                    selected, report, input, parameters, bucket(selected.cohortBinding(), input));
            case RoutingDecision.Abstain abstain when abstain.reason() == AbstainReason.POLICY_TRADEOFF_UNRESOLVED ->
                    verifyTradeoff(abstain, report, input, parameters,
                            bucket(abstain.cohortBinding().orElseThrow(), input));
            case RoutingDecision.Abstain ignored -> { }
            case RoutingDecision.NoEligibleRoute ignored -> { }
        }
    }

    /** The decision's identity and recorded configuration must be exactly what the host configured. */
    private void verifyTrustedProvenance(Provenance provenance, Trusted host) {
        if (!policyId().equals(provenance.policyId()) || !policyVersion().equals(provenance.policyVersion())) {
            throw new IllegalStateException("policy decision records policy " + provenance.policyId() + "/"
                    + provenance.policyVersion() + " but this verifier checks " + policyId() + "/" + policyVersion());
        }
        if (!host.parameters().equals(provenance.parameters())) {
            throw new IllegalStateException("policy decision records parameters " + provenance.parameters()
                    + " that differ from the trusted configuration " + host.parameters());
        }
    }

    /**
     * The admission result must equal the mismatches the request, the preference and the trusted state definition imply,
     * in their normative order: none omitted, none spurious. The state is consulted exactly when a route is eligible.
     */
    private void verifyTrustedState(RoutingDecision decision, RoutingInput input, RoutingStateDefinition definition) {
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
            case RoutingDecision.Selected ignored -> requireAdmitted(validation, input, definition);
            case RoutingDecision.Abstain ignored -> requireAdmitted(validation, input, definition);
        }
    }

    private void requireAdmitted(StateValidation validation, RoutingInput input, RoutingStateDefinition definition) {
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
     * The bucket the ranking runs with. Trusted mode derives it with the host's {@link CohortMapping} and requires the
     * recorded binding to equal it, including both mapping versions and the request's feature schema; self-reported
     * mode can only use the recorded bucket.
     */
    private String bucket(CohortBinding recorded, RoutingInput input) {
        if (trusted.isEmpty()) {
            return recorded.cohortBucket();
        }
        RoutingStateDefinition definition = trusted.get().definition();
        String bucket = definition.cohortMapping().bucketOf(input.request().features());
        var expected = new CohortBinding(bucket, definition.cohortMapping().bucketMappingVersion(),
                definition.mappingVersion(), input.request().features().schemaVersion());
        if (!expected.equals(recorded)) {
            throw new IllegalStateException("policy decision records cohort binding " + recorded
                    + " but the trusted mapping derives " + expected);
        }
        return bucket;
    }

    /**
     * The admission keys the recorded parameters decide (the policy mapping's feature schema and mapping version) must
     * be reported exactly, and an incompatible decision must report no reason that neither the request nor the
     * parameters imply. Self-reported mode only.
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

    /**
     * A {@code Selected} decision must equal the contract ordering recomputed from the parameters and the cohort bucket
     * (derived in trusted mode, recorded otherwise), and its winner must not exceed the tier cap of those parameters.
     */
    private void verifySelectedRanking(RoutingDecision.Selected selected, EligibilityReport report, RoutingInput input,
            PolicyParameters parameters, String bucket) {
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(), bucket);
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
                    + expected.order().getFirst().tier() + ") above the " + source() + " tier cap "
                    + parameters.maxAutoSelectTier());
        }
    }

    /**
     * A tier-cap abstention is the only tradeoff trigger. The ranking is recomputed with the cohort bucket and must be
     * reproduced, and the recomputed winner must exceed the cap.
     */
    private void verifyTradeoff(RoutingDecision.Abstain abstain, EligibilityReport report, RoutingInput input,
            PolicyParameters parameters, String bucket) {
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(), bucket);
        if (!expected.candidates().equals(abstain.candidates())
                || expected.truncated() != abstain.candidatesTruncated()) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + ", got " + abstain.candidates());
        }
        if (!exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy abstained on the tier cap but the winner does not exceed the "
                    + source() + " cap "
                    + parameters.maxAutoSelectTier());
        }
    }

    /** Whose parameters bound the check: the host's, or those the decision records. */
    private String source() {
        return trusted.isPresent() ? "trusted" : "recorded";
    }

    private boolean exceedsCap(LexicographicRanking.Result ranking, PolicyParameters parameters) {
        return parameters.maxAutoSelectTier().isPresent()
                && ranking.order().getFirst().tier() > parameters.maxAutoSelectTier().getAsInt();
    }
}
