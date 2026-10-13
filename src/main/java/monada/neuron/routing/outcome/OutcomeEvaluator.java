package monada.neuron.routing.outcome;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives acceptance from the host's judgments and decides whether an observation can support routing feedback.
 *
 * <p>Rules follow the Store's execution-memory contract (section 8) and the routing contract (sections 5 and 6):
 * <ul>
 * <li>an observation is <em>applicable</em> when it is about the evidence attempt, was judged under the evaluation
 *     policy in force, is not after the cutoff and is the latest listed revision of its event;</li>
 * <li>the <em>effective</em> observation of a dimension is the applicable one with the highest ledger sequence, so a
 *     later {@code FAIL} or {@code UNKNOWN} overrides an earlier {@code PASS} and the reverse;</li>
 * <li>the evidence attempt is the accepted attempt, or the decision's attempt when none was accepted;</li>
 * <li>an accepted flag alone never validates, a completed invocation is not acceptance, and neither cost nor any
 *     resource field is read, so cost cannot overcome a failed gate.</li>
 * </ul>
 *
 * <p>Stateless and deterministic. Time {@code O(O + D)} for {@code O <= 32} observations and {@code D <= 6}
 * dimensions; extra space {@code O(O + D)}. The hash map is only point-queried, never iterated.
 */
public final class OutcomeEvaluator {

    /**
     * Assesses one observation against the decision it answers.
     *
     * @param observation the sample to assess
     * @param decision the {@code Selected} decision the outcome answers
     * @param request the request that produced the decision
     * @param policy the host evidence policy in force
     * @return the assessment; never throws for well-formed arguments, whatever the evidence says
     */
    public LearningAssessment assess(
            RoutingObservation observation, RoutingDecision.Selected decision, RoutingRequest request,
            EvidencePolicy policy) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        RoutingOutcome outcome = observation.outcome();

        List<ProvenanceField> mismatches = mismatches(outcome, decision, request);
        Set<IneligibilityReason> reasons = EnumSet.noneOf(IneligibilityReason.class);
        if (!mismatches.isEmpty()) {
            reasons.add(IneligibilityReason.PROVENANCE_MISMATCH);
        }
        boolean policyInForce = policy.id().equals(outcome.evaluationPolicyId())
                && policy.version().equals(outcome.evaluationPolicyVersion());
        if (!policyInForce) {
            reasons.add(IneligibilityReason.STALE_EVALUATION_POLICY);
        }

        String evidenceAttempt = outcome.acceptedAttemptId() instanceof Feature.Known<String> known
                ? known.value() : outcome.decisionRef().attemptId();
        long cutoff = outcome.cutoff();

        Map<String, Long> latestRevision = new HashMap<>();
        for (QualityObservation o : outcome.observations()) {
            if (o.ledgerSequence() <= cutoff) {
                latestRevision.merge(o.eventRef(), o.revision(), Math::max);
            }
        }

        Map<QualityDimension, QualityObservation> effective = new EnumMap<>(QualityDimension.class);
        Set<QualityDimension> tied = EnumSet.noneOf(QualityDimension.class);
        Set<QualityDimension> otherPolicy = EnumSet.noneOf(QualityDimension.class);
        int excluded = 0;
        for (QualityObservation o : outcome.observations()) {
            if (o.ledgerSequence() > cutoff || o.revision() < latestRevision.get(o.eventRef())
                    || !o.attemptId().equals(evidenceAttempt)) {
                excluded++;
                continue;
            }
            if (!o.policyId().equals(outcome.evaluationPolicyId())
                    || !o.policyVersion().equals(outcome.evaluationPolicyVersion())
                    || !policyInForce) {
                excluded++;
                otherPolicy.add(o.dimension());
                continue;
            }
            QualityObservation best = effective.get(o.dimension());
            if (best == null || o.ledgerSequence() > best.ledgerSequence()) {
                effective.put(o.dimension(), o);
                tied.remove(o.dimension());
            } else if (o.ledgerSequence() == best.ledgerSequence() && o.state() != best.state()) {
                tied.add(o.dimension());
            }
        }

        List<LearningAssessment.EffectiveEvidence> evidence = new ArrayList<>();
        List<QualityDimension> failures = new ArrayList<>();
        boolean allSatisfied = true;
        for (QualityDimension d : EnumSet.copyOf(policy.mandatoryDimensions())) {
            QualityObservation o = effective.get(d);
            if (o == null || tied.contains(d)) {
                allSatisfied = false;
                if (tied.contains(d)) {
                    reasons.add(IneligibilityReason.CONTRADICTORY_EVIDENCE);
                } else {
                    reasons.add(IneligibilityReason.MISSING_EVIDENCE);
                    if (otherPolicy.contains(d)) {
                        reasons.add(IneligibilityReason.STALE_EVALUATION_POLICY);
                    }
                }
                continue;
            }
            evidence.add(new LearningAssessment.EffectiveEvidence(d, o.state(), o.ledgerSequence()));
            switch (o.state()) {
                case PASS, NOT_APPLICABLE -> { }
                case FAIL -> {
                    allSatisfied = false;
                    failures.add(d);
                }
                case UNKNOWN -> {
                    allSatisfied = false;
                    reasons.add(IneligibilityReason.UNKNOWN_EVIDENCE);
                }
            }
        }

        if (!tied.isEmpty()) {
            reasons.add(IneligibilityReason.CONTRADICTORY_EVIDENCE);
        }
        for (EvidenceRevision pin : outcome.judgedRevisions()) {
            Long latest = latestRevision.get(pin.eventRef());
            if (latest == null || latest < pin.revision()) {
                if (!mismatches.contains(ProvenanceField.EVIDENCE_REFERENCE)) {
                    mismatches = withMismatch(mismatches, ProvenanceField.EVIDENCE_REFERENCE);
                    reasons.add(IneligibilityReason.PROVENANCE_MISMATCH);
                }
            } else if (latest > pin.revision()) {
                reasons.add(IneligibilityReason.STALE_ARTIFACT_REVISION);
            }
        }

        DerivedAcceptance derived = derive(outcome, allSatisfied);
        switch (outcome.outcomeStatus()) {
            case PENDING -> reasons.add(IneligibilityReason.PENDING);
            case CANCELLED -> reasons.add(IneligibilityReason.CANCELLED);
            case ACCEPTED -> {
                if (outcome.invocation() != InvocationStatus.COMPLETED) {
                    reasons.add(IneligibilityReason.CONTRADICTORY_EVIDENCE);
                }
            }
            case REJECTED, FAILED -> { }
        }
        if (outcome.outcomeOrigin() == OutcomeOrigin.IMPORTED_CLAIM) {
            reasons.add(IneligibilityReason.IMPORTED_CLAIM);
        }
        if (outcome.invocation() == InvocationStatus.CANCELLED) {
            reasons.add(IneligibilityReason.CANCELLED);
        }
        if (outcome.invocation().environmental()) {
            reasons.add(IneligibilityReason.ENVIRONMENTAL_FAILURE);
        }

        boolean stageLocal = outcome.attribution().basis() == StageAttribution.Basis.STAGE_LOCAL;
        boolean blocked = reasons.stream().anyMatch(IneligibilityReason::blocking);

        if ((derived == DerivedAcceptance.VALIDATED_ACCEPTED || !failures.isEmpty()) && !stageLocal) {
            reasons.add(IneligibilityReason.NO_STAGE_ATTRIBUTION);
        }
        if (derived == DerivedAcceptance.NOT_ACCEPTED && failures.isEmpty()) {
            reasons.add(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE);
        }

        EligibilityVerdict verdict = EligibilityVerdict.NOT_ELIGIBLE;
        if (!blocked && stageLocal) {
            if (derived == DerivedAcceptance.VALIDATED_ACCEPTED) {
                verdict = EligibilityVerdict.POSITIVE_CANDIDATE;
            } else if (!failures.isEmpty()) {
                verdict = EligibilityVerdict.NEGATIVE_CANDIDATE;
            }
        }
        return new LearningAssessment(derived, verdict, List.copyOf(reasons), mismatches, evidence, failures,
                excluded);
    }

    private DerivedAcceptance derive(RoutingOutcome outcome, boolean allMandatorySatisfied) {
        return switch (outcome.outcomeStatus()) {
            case PENDING -> DerivedAcceptance.PENDING;
            case REJECTED, FAILED, CANCELLED -> DerivedAcceptance.NOT_ACCEPTED;
            case ACCEPTED -> outcome.outcomeOrigin() == OutcomeOrigin.VALIDATED && allMandatorySatisfied
                    ? DerivedAcceptance.VALIDATED_ACCEPTED : DerivedAcceptance.ACCEPTED_UNVALIDATED;
        };
    }

    private List<ProvenanceField> mismatches(
            RoutingOutcome outcome, RoutingDecision.Selected decision, RoutingRequest request) {
        Set<ProvenanceField> found = EnumSet.noneOf(ProvenanceField.class);
        var provenance = decision.provenance();
        if (!RoutingRequest.CONTRACT_VERSION.equals(outcome.contractVersion())) {
            found.add(ProvenanceField.CONTRACT_VERSION);
        }
        if (!outcome.decisionRef().equals(provenance.decisionRef())
                || !provenance.decisionRef().equals(DecisionRef.of(request))) {
            found.add(ProvenanceField.DECISION_REF);
        }
        if (provenance.cutoff() != request.cutoff()) {
            found.add(ProvenanceField.CUTOFF);
        }
        if (!outcome.executedRoute().equals(decision.route())) {
            found.add(ProvenanceField.EXECUTED_ROUTE);
        }
        if (!outcome.cohortBinding().equals(decision.cohortBinding())) {
            found.add(ProvenanceField.COHORT_BINDING);
        }
        if (!request.features().schemaVersion().equals(decision.cohortBinding().featureSchemaVersion())) {
            found.add(ProvenanceField.FEATURE_SCHEMA_VERSION);
        }
        if (!outcome.catalogVersion().equals(provenance.catalogVersion())) {
            found.add(ProvenanceField.CATALOG_VERSION);
        }
        if (!outcome.routingPolicyId().equals(provenance.policyId())
                || !outcome.routingPolicyVersion().equals(provenance.policyVersion())) {
            found.add(ProvenanceField.ROUTING_POLICY);
        }
        if (!outcome.sourceFingerprint().equals(request.sourceFingerprint())) {
            found.add(ProvenanceField.SOURCE_FINGERPRINT);
        }
        if (!outcome.contextFingerprint().equals(request.contextFingerprint())) {
            found.add(ProvenanceField.CONTEXT_FINGERPRINT);
        }
        if (!outcome.constraintsFingerprint().equals(request.constraintsFingerprint())) {
            found.add(ProvenanceField.CONSTRAINTS_FINGERPRINT);
        }
        var state = provenance.state();
        if (!outcome.evaluationPolicyId().equals(request.evaluationPolicyId())
                || !outcome.evaluationPolicyVersion().equals(request.evaluationPolicyVersion())
                || !state.evaluationPolicyId().equals(request.evaluationPolicyId())
                || !state.evaluationPolicyVersion().equals(request.evaluationPolicyVersion())) {
            found.add(ProvenanceField.EVALUATION_POLICY);
        }
        return List.copyOf(found);
    }

    private List<ProvenanceField> withMismatch(List<ProvenanceField> existing, ProvenanceField added) {
        Set<ProvenanceField> all = EnumSet.of(added);
        all.addAll(existing);
        return List.copyOf(all);
    }
}
