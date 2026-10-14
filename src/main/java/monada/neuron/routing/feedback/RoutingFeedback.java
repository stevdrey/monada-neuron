package monada.neuron.routing.feedback;

import monada.neuron.routing.token.CanonicalLists;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.evolution.FeedbackInput;
import monada.neuron.evolution.OutcomeFeedback;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.LearningAssessment;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, immutable feedback derived from one {@code RoutingObservation} (contract section 6). It is separate from
 * {@link OutcomeFeedback}: it carries no {@code ActionStatus}, credits one route's cohort Node only, and keeps the
 * evidence dimensions and the explicit {@link ScoreRule} that produced the scalar.
 *
 * <p>A {@code NEUTRAL} feedback has no entries: it records that the sample was examined and implies no reward and no
 * penalty. The artifact is plain caller-owned data; Neuron keeps no queue, history or applied-revision ledger of it.
 *
 * @param contractVersion must be {@value RoutingRequest#CONTRACT_VERSION}
 * @param identity exact sample revision, decision, route and definition this feedback answers
 * @param supersedesSequence sequence of the earliest revision the sample replaces, copied from the observation
 * @param cohortKey cohort of the executed route; a neutral feedback names it for audit only
 * @param routingPolicyId routing policy that decided (provenance)
 * @param routingPolicyVersion routing policy version
 * @param catalogVersion catalog snapshot behind the decision
 * @param cutoff ledger cutoff the outcome was evaluated as of
 * @param derivedAcceptance acceptance derived as the Store defines it
 * @param rule the explicit rule that produced the disposition and the score
 * @param disposition explicit direction; always equal to {@code rule.disposition()}
 * @param entries exactly one entry for {@code REINFORCE} or {@code PENALIZE}, none for {@code NEUTRAL}
 * @param reasons assessment reasons, declaration order
 * @param effectiveEvidence effective state per mandatory dimension
 * @param provenFailures mandatory dimensions whose effective state is {@code FAIL}
 * @param costPeer peer revision a reward was compared against (the derivation's view of the peer, so a superseded
 *         view is detected); always present for {@code REINFORCE_COST_REFINED}, whose peer must precede the reward in
 *         applied order, and absent unless the feedback is a reward
 */
public record RoutingFeedback(
        String contractVersion,
        FeedbackIdentity identity,
        Feature<Long> supersedesSequence,
        CohortKey cohortKey,
        String routingPolicyId,
        String routingPolicyVersion,
        String catalogVersion,
        long cutoff,
        DerivedAcceptance derivedAcceptance,
        ScoreRule rule,
        FeedbackDisposition disposition,
        List<FeedbackEntry> entries,
        List<IneligibilityReason> reasons,
        List<LearningAssessment.EffectiveEvidence> effectiveEvidence,
        List<QualityDimension> provenFailures,
        Optional<PeerRevision> costPeer) {

    /** Validates consistency between the rule, the disposition, the entries and the evidence. */
    public RoutingFeedback {
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        if (!RoutingRequest.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported contractVersion: " + contractVersion);
        }
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(supersedesSequence, "supersedesSequence must not be null");
        Objects.requireNonNull(cohortKey, "cohortKey must not be null");
        if (!cohortKey.route().equals(identity.route())
                || !cohortKey.scopeId().equals(identity.decisionRef().scopeId())) {
            throw new IllegalArgumentException("the cohort " + cohortKey + " does not belong to the executed route "
                    + identity.route() + " in scope " + identity.decisionRef().scopeId());
        }
        OpaqueTokens.require(routingPolicyId, "routingPolicyId");
        OpaqueTokens.require(routingPolicyVersion, "routingPolicyVersion");
        OpaqueTokens.require(catalogVersion, "catalogVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        if (identity.ledgerSequence() > cutoff) {
            throw new IllegalArgumentException("the sample at sequence " + identity.ledgerSequence()
                    + " lies beyond the cutoff " + cutoff);
        }
        Objects.requireNonNull(derivedAcceptance, "derivedAcceptance must not be null");
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(costPeer, "costPeer must not be null");
        if (rule == ScoreRule.REINFORCE_COST_REFINED && costPeer.isEmpty()) {
            throw new IllegalArgumentException("REINFORCE_COST_REFINED records the cost peer it compared against");
        }
        if (costPeer.isPresent() && rule.disposition() != FeedbackDisposition.REINFORCE) {
            throw new IllegalArgumentException("only a reward considers a cost peer");
        }
        if (rule == ScoreRule.REINFORCE_COST_REFINED
                && costPeer.get().experienceRef().equals(identity.experienceRef())) {
            throw new IllegalArgumentException("a cost peer is another sample, not a revision of the rewarded one");
        }
        if (rule == ScoreRule.REINFORCE_COST_REFINED
                && !costPeer.get().precedes(identity.ledgerSequence(), identity.experienceRef())) {
            throw new IllegalArgumentException("the cost peer " + costPeer.get().experienceRef() + " at sequence "
                    + costPeer.get().ledgerSequence() + " does not precede the reward at " + identity.ledgerSequence()
                    + " in applied order");
        }
        Objects.requireNonNull(disposition, "disposition must not be null");
        if (rule.disposition() != disposition) {
            throw new IllegalArgumentException("rule " + rule + " yields " + rule.disposition() + ", not " + disposition);
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (entries.size() > OutcomeFeedback.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "entries must not exceed " + OutcomeFeedback.MAX_ENTRIES + ", got: " + entries.size());
        }
        reasons = CanonicalLists.canonical(reasons, Comparator.naturalOrder(), IneligibilityReason.values().length,
                "reasons");
        effectiveEvidence = CanonicalLists.canonical(effectiveEvidence,
                Comparator.comparing(LearningAssessment.EffectiveEvidence::dimension),
                QualityDimension.values().length, "effectiveEvidence");
        provenFailures = CanonicalLists.canonical(provenFailures, Comparator.naturalOrder(),
                QualityDimension.values().length, "provenFailures");
        if (supersedesSequence instanceof Feature.Known<Long> known) {
            if (identity.revision() == 0) {
                throw new IllegalArgumentException("an initial revision replaces nothing, so it has no supersedesSequence");
            }
            if (known.value() < 0 || known.value() >= identity.ledgerSequence()) {
                throw new IllegalArgumentException(
                        "supersedesSequence must be in [0, ledgerSequence), got: " + known.value());
            }
        } else if (identity.revision() > 0) {
            throw new IllegalArgumentException("a corrected revision must carry the supersedesSequence it replaces");
        }
        for (FeedbackEntry entry : entries) {
            if (entry.targetSignal() != null) {
                throw new IllegalArgumentException(
                        "routing entries are score-only: a target Signal would select another adaptation rule");
            }
        }
        for (LearningAssessment.EffectiveEvidence evidence : effectiveEvidence) {
            if (evidence.ledgerSequence() > identity.ledgerSequence()) {
                throw new IllegalArgumentException("effective " + evidence.dimension() + " evidence at sequence "
                        + evidence.ledgerSequence() + " is later than the sample at " + identity.ledgerSequence()
                        + " (and the cutoff " + cutoff + ")");
            }
        }
        List<QualityDimension> failed = effectiveEvidence.stream()
                .filter(e -> e.state() == QualityState.FAIL).map(LearningAssessment.EffectiveEvidence::dimension)
                .toList();
        if (!failed.equals(provenFailures)) {
            throw new IllegalArgumentException(
                    "provenFailures " + provenFailures + " must equal the effective FAIL dimensions " + failed);
        }
        // the rule/assessment consistency has a single owner, shared with the ledger entry
        rule.requireConsistent(derivedAcceptance, reasons, provenFailures);
        if (disposition == FeedbackDisposition.NEUTRAL) {
            if (!entries.isEmpty()) {
                throw new IllegalArgumentException("NEUTRAL feedback must not carry entries");
            }
        } else {
            if (entries.size() != 1) {
                throw new IllegalArgumentException(disposition + " feedback credits exactly one Node, got entries: "
                        + entries.size());
            }
            double score = entries.getFirst().score();
            if (disposition == FeedbackDisposition.REINFORCE ? score <= 0.0 : score >= 0.0) {
                throw new IllegalArgumentException(disposition + " feedback cannot carry score " + score);
            }
        }
        if (disposition == FeedbackDisposition.REINFORCE && (effectiveEvidence.isEmpty()
                || effectiveEvidence.stream().anyMatch(e -> e.state() != QualityState.PASS
                        && e.state() != QualityState.NOT_APPLICABLE))) {
            throw new IllegalArgumentException(
                    "REINFORCE needs effective evidence with every state PASS or NOT_APPLICABLE");
        }
    }

    /**
     * Explicit adapter to the generic adaptation path: one {@link FeedbackInput} per entry, empty for a neutral
     * feedback. It manufactures no {@code ActionStatus} and no {@code OutcomeFeedback}; applying the inputs is the
     * caller's job, through an {@code AdaptationPolicy} on the Node it bound.
     */
    public List<FeedbackInput> toFeedbackInputs() {
        return entries.stream().map(FeedbackEntry::toFeedbackInput).toList();
    }
}
