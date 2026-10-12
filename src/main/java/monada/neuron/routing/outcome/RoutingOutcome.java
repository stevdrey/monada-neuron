package monada.neuron.routing.outcome;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The host-evaluated result of executing a routing decision, with the provenance needed to match it to that exact
 * decision. It is data the host supplies: Neuron does not inspect code, call a judge, issue identifiers or read a clock.
 *
 * <p>Three things stay separate: the worker {@link InvocationStatus invocation} (finished, not accepted), the recorded
 * outcome claim, and the quality {@link QualityObservation observations}. Acceptance is derived from the last two by
 * {@link OutcomeEvaluator}; a flag alone never decides it.
 *
 * @param contractVersion must be {@value RoutingRequest#CONTRACT_VERSION}
 * @param decisionRef identity of the decision this outcome answers
 * @param executedRoute route that actually ran; a different route than the advised one earns no credit
 * @param cohortBinding binding copied from the decision
 * @param catalogVersion catalog snapshot behind the decision
 * @param routingPolicyId routing policy that decided
 * @param routingPolicyVersion routing policy version
 * @param sourceFingerprint source revision of the work
 * @param contextFingerprint context fingerprint
 * @param constraintsFingerprint constraints fingerprint
 * @param evaluationPolicyId evaluation policy id in force
 * @param evaluationPolicyVersion evaluation policy version in force
 * @param cutoff inclusive ledger cutoff this outcome is evaluated as of
 * @param invocation worker invocation result
 * @param outcomeStatus current outcome status
 * @param outcomeOrigin whether the outcome was validated or is an imported claim
 * @param acceptedAttemptId attempt the outcome accepted; required for, and only allowed on, {@code ACCEPTED}
 * @param outcomeSequence ledger sequence of the current outcome event, at most {@code cutoff}
 * @param observations at most 32 observations including superseded revisions, canonical order
 * @param judgedRevisions at most 32 evidence revisions the host judged on, one per event, canonical order
 * @param resources usage, cost and billing data
 * @param attribution stage attribution evidence
 */
public record RoutingOutcome(
        String contractVersion,
        DecisionRef decisionRef,
        RouteKey executedRoute,
        CohortBinding cohortBinding,
        String catalogVersion,
        String routingPolicyId,
        String routingPolicyVersion,
        String sourceFingerprint,
        String contextFingerprint,
        String constraintsFingerprint,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        long cutoff,
        InvocationStatus invocation,
        OutcomeStatus outcomeStatus,
        OutcomeOrigin outcomeOrigin,
        Feature<String> acceptedAttemptId,
        long outcomeSequence,
        List<QualityObservation> observations,
        List<EvidenceRevision> judgedRevisions,
        ResourceSummary resources,
        StageAttribution attribution) {

    /** Maximum observations (including superseded revisions) and judged revisions. */
    public static final int MAX_EVIDENCE = 32;

    /** Validates tokens, bounds and cross-field rules and copies the lists in canonical order. */
    public RoutingOutcome {
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        if (!RoutingRequest.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported contractVersion: " + contractVersion);
        }
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(executedRoute, "executedRoute must not be null");
        Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
        OutcomeTokens.token(catalogVersion, "catalogVersion");
        OutcomeTokens.token(routingPolicyId, "routingPolicyId");
        OutcomeTokens.token(routingPolicyVersion, "routingPolicyVersion");
        OutcomeTokens.token(sourceFingerprint, "sourceFingerprint");
        OutcomeTokens.token(contextFingerprint, "contextFingerprint");
        OutcomeTokens.token(constraintsFingerprint, "constraintsFingerprint");
        OutcomeTokens.token(evaluationPolicyId, "evaluationPolicyId");
        OutcomeTokens.token(evaluationPolicyVersion, "evaluationPolicyVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(outcomeStatus, "outcomeStatus must not be null");
        Objects.requireNonNull(outcomeOrigin, "outcomeOrigin must not be null");
        Objects.requireNonNull(acceptedAttemptId, "acceptedAttemptId must not be null");
        if (acceptedAttemptId instanceof Feature.Known<String> known) {
            OutcomeTokens.token(known.value(), "acceptedAttemptId");
            if (outcomeStatus != OutcomeStatus.ACCEPTED) {
                throw new IllegalArgumentException("only an ACCEPTED outcome names an accepted attempt");
            }
        } else if (outcomeStatus == OutcomeStatus.ACCEPTED) {
            throw new IllegalArgumentException("an ACCEPTED outcome must name the accepted attempt");
        }
        if (outcomeSequence < 0 || outcomeSequence > cutoff) {
            throw new IllegalArgumentException(
                    "outcomeSequence must be within [0, cutoff=" + cutoff + "], got: " + outcomeSequence);
        }
        observations = Ordering.canonical((Collection<QualityObservation>) observations, QualityObservation.ORDER,
                MAX_EVIDENCE, "observations");
        requireConsistentPositions(observations);
        judgedRevisions = Ordering.canonical((Collection<EvidenceRevision>) judgedRevisions,
                Comparator.comparing(EvidenceRevision::eventRef, OutcomeTokens::compare), MAX_EVIDENCE,
                "judgedRevisions");
        Objects.requireNonNull(resources, "resources must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");
    }

    /** One exact event revision has one ledger position and one judgment per dimension, and a higher revision of an event sits at a strictly higher ledger sequence. */
    private static void requireConsistentPositions(List<QualityObservation> observations) {
        record Revision(String eventRef, long revision) {
        }
        record Judgment(Revision revision, QualityDimension dimension) {
        }
        Map<Revision, Long> positions = new HashMap<>();
        Set<Judgment> judgments = new HashSet<>();
        for (QualityObservation o : observations) {
            var revision = new Revision(o.eventRef(), o.revision());
            Long known = positions.putIfAbsent(revision, o.ledgerSequence());
            if (known != null && known != o.ledgerSequence()) {
                throw new IllegalArgumentException("event revision " + o.eventRef() + "@" + o.revision()
                        + " appears at ledger sequences " + known + " and " + o.ledgerSequence());
            }
            if (!judgments.add(new Judgment(revision, o.dimension()))) {
                throw new IllegalArgumentException("event revision " + o.eventRef() + "@" + o.revision()
                        + " judges " + o.dimension() + " more than once");
            }
        }
        List<QualityObservation> byRevision = new ArrayList<>(observations);
        byRevision.sort(Comparator.comparing(QualityObservation::eventRef, OutcomeTokens::compare)
                .thenComparingLong(QualityObservation::revision));
        for (int i = 1; i < byRevision.size(); i++) {
            QualityObservation earlier = byRevision.get(i - 1);
            QualityObservation later = byRevision.get(i);
            if (earlier.eventRef().equals(later.eventRef()) && earlier.revision() != later.revision()
                    && later.ledgerSequence() <= earlier.ledgerSequence()) {
                throw new IllegalArgumentException("event " + later.eventRef() + " revision " + later.revision()
                        + " at sequence " + later.ledgerSequence() + " must come after revision " + earlier.revision()
                        + " at sequence " + earlier.ledgerSequence());
            }
        }
    }
}
