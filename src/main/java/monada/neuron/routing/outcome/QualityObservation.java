package monada.neuron.routing.outcome;

import monada.neuron.routing.features.Feature;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One host-judged observation of one quality dimension for one attempt, as recorded in a ledger event revision.
 *
 * <p>Forge judges; Neuron only carries the judgment. {@code NOT_APPLICABLE} needs a justification.
 *
 * @param eventRef opaque reference of the evidence event this observation belongs to
 * @param revision revision of that event, non-negative; a later revision replaces an earlier one
 * @param dimension judged dimension
 * @param state judgment
 * @param justification required for {@code NOT_APPLICABLE}, up to 512 code points, otherwise unknown
 * @param evaluatorId evaluator token
 * @param evaluatorVersion evaluator version token
 * @param policyId evaluation policy id the judgment was made under
 * @param policyVersion evaluation policy version
 * @param source who produced the judgment
 * @param attemptId attempt the judgment is about
 * @param ledgerSequence ledger sequence of the effective event, non-negative
 * @param evidenceRefs at most 8 evidence references, canonical order, no duplicates
 */
public record QualityObservation(
        String eventRef,
        long revision,
        QualityDimension dimension,
        QualityState state,
        Feature<String> justification,
        String evaluatorId,
        String evaluatorVersion,
        String policyId,
        String policyVersion,
        EvidenceSource source,
        String attemptId,
        long ledgerSequence,
        List<EvidenceRef> evidenceRefs) {

    /** Maximum evidence references per observation. */
    public static final int MAX_EVIDENCE_REFS = 8;

    /** Canonical order of observations: ledger sequence, event reference, revision, dimension. */
    static final Comparator<QualityObservation> ORDER = Comparator
            .comparingLong(QualityObservation::ledgerSequence)
            .thenComparing(QualityObservation::eventRef, OpaqueTokens::compareCodePoints)
            .thenComparingLong(QualityObservation::revision)
            .thenComparing(QualityObservation::dimension);

    /** Validates tokens, bounds and the justification rule, and copies the references. */
    public QualityObservation {
        OpaqueTokens.requireText(eventRef, "eventRef");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(justification, "justification must not be null");
        boolean justified = justification instanceof Feature.Known<String>;
        if (state == QualityState.NOT_APPLICABLE && !justified) {
            throw new IllegalArgumentException("NOT_APPLICABLE requires a justification");
        }
        if (state != QualityState.NOT_APPLICABLE && justified) {
            throw new IllegalArgumentException("only NOT_APPLICABLE carries a justification");
        }
        if (justification instanceof Feature.Known<String> known) {
            OpaqueTokens.requireText(known.value(), "justification");
        }
        OpaqueTokens.require(evaluatorId, "evaluatorId");
        OpaqueTokens.require(evaluatorVersion, "evaluatorVersion");
        OpaqueTokens.require(policyId, "policyId");
        OpaqueTokens.require(policyVersion, "policyVersion");
        Objects.requireNonNull(source, "source must not be null");
        OpaqueTokens.require(attemptId, "attemptId");
        if (ledgerSequence < 0) {
            throw new IllegalArgumentException("ledgerSequence must be non-negative, got: " + ledgerSequence);
        }
        evidenceRefs = refs(evidenceRefs);
    }

    private static List<EvidenceRef> refs(Collection<EvidenceRef> source) {
        return Ordering.canonical(source, Comparator.naturalOrder(), MAX_EVIDENCE_REFS, "evidenceRefs");
    }
}
