package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.List;

/**
 * Mandatory validation of any routing decision a {@link RoutingReasoningStage} hosts, independent of the policy's
 * ordering rules (Forge Routing Contract v1, section 17.5).
 *
 * <p>It checks that the decision describes exactly the resolved request, catalog, preference snapshot and hosted policy
 * identity, that its state admission agrees with the mismatches the request alone decides, and that it agrees with the
 * hard eligibility filter. It does not check ordering, explanation, truncation, the tier cap or the admission keys that
 * depend on the policy's parameters and state definition; the {@link RoutingDecisionVerifier} the stage composes does
 * (the host-selected one, or the legacy {@code route-lex/1} verifier built on the decision's own record). Instances are
 * immutable and stateless.
 */
final class RoutingDecisionValidator {

    /**
     * Validates a decision.
     *
     * @param decision the policy's decision
     * @param input the resolved inputs the policy decided on
     * @param policy the hosted policy; only its identity is read
     * @param report the eligibility filter's report for {@code input}, computed once by the caller; only its catalog
     *         version can be checked against {@code input}, because a report carries no request identity
     * @throws IllegalArgumentException if {@code report} was computed for another catalog than {@code input}'s
     * @throws IllegalStateException if the decision disagrees with the inputs, the policy identity or the report
     */
    void validate(RoutingDecision decision, RoutingInput input, RoutingPolicy policy, EligibilityReport report) {
        validate(decision, input, policy.policyId(), policy.policyVersion(), report);
    }

    /**
     * Validates a decision against an explicit hosted policy identity, so a trusted host's identity does not depend on
     * the policy object answering consistently.
     *
     * @param policyId the identity the decision must record
     * @param policyVersion the version the decision must record
     * @see #validate(RoutingDecision, RoutingInput, RoutingPolicy, EligibilityReport)
     */
    void validate(RoutingDecision decision, RoutingInput input, String policyId, String policyVersion,
            EligibilityReport report) {
        requireReportFor(report, input);
        Provenance provenance = decision.provenance();
        if (!provenance.catalogVersion().equals(input.catalog().catalogVersion())) {
            throw new IllegalStateException("policy decision refers to catalog " + provenance.catalogVersion()
                    + " but the resolved catalog is " + input.catalog().catalogVersion());
        }
        if (!provenance.decisionRef().equals(DecisionRef.of(input.request()))) {
            throw new IllegalStateException("policy decision refers to another request: " + provenance.decisionRef());
        }
        if (provenance.cutoff() != input.request().cutoff()) {
            throw new IllegalStateException("policy decision records cutoff " + provenance.cutoff()
                    + " but the request cutoff is " + input.request().cutoff());
        }
        if (!provenance.state().equals(input.preference().binding())) {
            throw new IllegalStateException("policy decision records another preference snapshot: "
                    + provenance.state());
        }
        if (!provenance.policyId().equals(policyId) || !provenance.policyVersion().equals(policyVersion)) {
            throw new IllegalStateException("policy decision records policy " + provenance.policyId() + "/"
                    + provenance.policyVersion() + " instead of " + policyId + "/" + policyVersion);
        }
        validateRequestState(provenance.validation(), input);
        switch (decision) {
            case RoutingDecision.NoEligibleRoute ignored -> {
                if (!report.noneEligible()) {
                    throw new IllegalStateException("policy reported no eligible route but the eligibility filter "
                            + "accepts " + report.eligible().size());
                }
            }
            case RoutingDecision.Abstain abstain -> {
                if (!abstain.eligible().equals(report.eligible().stream().map(route -> route.key()).toList())) {
                    throw new IllegalStateException("policy abstention lists other eligible routes than the filter");
                }
            }
            case RoutingDecision.Selected selected -> validateSelected(selected, report);
        }
        if (!decision.exclusions().equals(report.excluded())) {
            throw new IllegalStateException("policy decision exclusions disagree with the eligibility filter");
        }
    }

    /**
     * The admission keys the request alone decides (scope, feature schema, evaluation policy, watermark): a compatible
     * decision must have none of those mismatches and an incompatible one must report every one. Mismatches that depend
     * on the policy's state definition, and spurious reasons, are left to the policy-specific verifier.
     */
    private void validateRequestState(StateValidation validation, RoutingInput input) {
        List<StateMismatch> implied = input.preference().mismatches(input.request());
        switch (validation) {
            case StateValidation.Compatible compatible -> {
                if (!implied.isEmpty()) {
                    throw new IllegalStateException("policy decision accepted a preference snapshot that conflicts "
                            + "with the request: " + implied);
                }
            }
            case StateValidation.Incompatible incompatible -> {
                if (!incompatible.reasons().containsAll(implied)) {
                    throw new IllegalStateException("policy decision omits state mismatches the request implies: "
                            + implied);
                }
            }
            case StateValidation.NotEvaluated notEvaluated -> { }
        }
    }

    /** A report for another catalog would make every eligibility check compare against the wrong routes. */
    private void requireReportFor(EligibilityReport report, RoutingInput input) {
        if (!report.catalogVersion().equals(input.catalog().catalogVersion())) {
            throw new IllegalArgumentException("eligibility report was computed for catalog " + report.catalogVersion()
                    + ", not " + input.catalog().catalogVersion());
        }
    }

    private void validateSelected(RoutingDecision.Selected selected, EligibilityReport report) {
        EligibilityReport.EligibleRoute eligible = report.eligible().stream()
                .filter(route -> route.key().equals(selected.route()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "policy selected a route that is not eligible for the request: " + selected.route()));
        if (!eligible.executionMode().equals(selected.executionMode())
                || eligible.overflow() != selected.overflowUsed()) {
            throw new IllegalStateException("policy decision disagrees with the eligibility filter for "
                    + selected.route() + ": mode " + selected.executionMode() + ", overflow "
                    + selected.overflowUsed() + " instead of " + eligible.executionMode() + ", "
                    + eligible.overflow());
        }
    }
}
