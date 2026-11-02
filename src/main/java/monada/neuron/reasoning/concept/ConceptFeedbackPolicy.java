package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralPattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Quality-gated, bounded update of a caller-owned {@link ConceptModel} from verified, attributable {@link
 * ConceptOutcome}s (contract {@code ncm-wave-cognition/2} section 10.2, ADR 0049). Stateless, pure and deterministic:
 * the same snapshot, batch and cycle ordinal give a bit-identical result, and nothing is kept between calls. The
 * caller carries outcomes across cycles, approves the returned snapshot for the next cycle and keeps the previous
 * one for rollback; there is no hidden online training.
 *
 * <p><b>Gate.</b> Every outcome passes the checks of {@link ConceptFeedbackRejection} in order: the receipt must name
 * this exact snapshot, the verifier must be trusted, the outcome must be verified and unambiguous, the predicting
 * cycle must be within {@link ConceptFeedbackLimits#maxEvidenceAge()}, every code must be a concept of the hierarchy
 * (labels leaves, the receipt's depth that of its concept), and the evidence must match the frame. Within a batch, a
 * predicted window that any trusted verifier contradicts or labels differently is ambiguous, and both a predicted
 * window and an evidence window are absorbed at most once. Because the new snapshot has a new fingerprint, a batch
 * replayed after it was applied is {@code MODEL_MISMATCH}. Neuron keeps no history across batches, so a host that
 * re-predicts a window and sends the same evidence again under the new receipt is bounded only by
 * {@link ConceptFeedbackRejection#CONCEPT_CAP}; cross-batch idempotency is the host's.
 *
 * <p><b>Update.</b> An accepted outcome is one more labeled window of its verified leaf, exactly as the trainer would
 * count it: one leaf marginal count, one transition from the verified previous leaf when given, and, for a non-silent
 * pattern, its unit feature added to the exemplar sum of the leaf and every ancestor, so an inner concept keeps
 * pooling all of its leaves. The prediction itself never contributes: positive and negative outcomes add the same
 * evidence of the verified label, and nothing is subtracted. The per-leaf {@link ConceptFeedbackRejection#CONCEPT_CAP}
 * bounds how far feedback can move a leaf relative to its training windows.
 *
 * <p>Centroid sums are restored from the unit centroid and its stored norm, so an update equals a retrain on the
 * training windows plus the accepted outcomes up to rounding of the sums (one restoration per touched concept per
 * batch). Cost: O(B · log B) for the gate, O(B · depth · bins) for the sums and one copy of the learned arrays,
 * O(model) bytes, per batch of B outcomes. The receipt check needs the {@link ConceptModel#fingerprint()} of the
 * snapshot, a SHA-256 over all of it computed once per snapshot; at the largest bounds it dominates the update, so
 * batching amortizes both the copy and the digest.
 */
public final class ConceptFeedbackPolicy {

    private static final Comparator<ConceptOutcome> WINDOW_ORDER = Comparator
            .comparing((ConceptOutcome outcome) -> outcome.receipt().sourceReference())
            .thenComparingLong(outcome -> outcome.receipt().windowOffset());

    private static final Comparator<ConceptOutcome> EVIDENCE_ORDER = Comparator
            .comparing((ConceptOutcome outcome) -> outcome.observedEvidence().provenance().sourceReference())
            .thenComparingLong(outcome -> outcome.observedEvidence().provenance().windowOffset());

    private final ConceptFeedbackLimits limits;
    private final ConceptFeatures features = new ConceptFeatures();

    /**
     * Creates a policy with the given bounds.
     *
     * @param limits the bounds
     */
    public ConceptFeedbackPolicy(ConceptFeedbackLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /** Returns the bounds. */
    public ConceptFeedbackLimits limits() {
        return limits;
    }

    /**
     * Applies a batch of outcomes to a snapshot, leaving it unchanged.
     *
     * @param current the snapshot the receipts must name
     * @param batch the outcomes in the caller's order, possibly empty
     * @param currentCycleOrdinal the caller's ordinal of the cycle applying the batch, at least {@code 0}
     * @return the new snapshot, or the unchanged one, with every rejection
     */
    public ConceptModelUpdate apply(ConceptModel current, List<ConceptOutcome> batch, long currentCycleOrdinal) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(batch, "batch must not be null");
        if (currentCycleOrdinal < 0) {
            throw new IllegalArgumentException("currentCycleOrdinal must be non-negative, got: " + currentCycleOrdinal);
        }
        for (ConceptOutcome outcome : batch) {
            Objects.requireNonNull(outcome, "batch must not contain null");
        }
        int size = batch.size();
        ConceptFeedbackRejection[] reasons = new ConceptFeedbackRejection[size];
        if (size > limits.maxOutcomesPerUpdate()) {
            Arrays.fill(reasons, ConceptFeedbackRejection.BATCH_LIMIT);
            return unchanged(current, batch, reasons);
        }
        String fingerprint = current.fingerprint();
        for (int i = 0; i < size; i++) {
            reasons[i] = check(current, fingerprint, batch.get(i), currentCycleOrdinal);
        }
        rejectRepeatedWindows(batch, reasons);
        rejectRepeatedEvidence(batch, reasons);
        return update(current, batch, reasons);
    }

    private ConceptFeedbackRejection check(ConceptModel model, String fingerprint, ConceptOutcome outcome,
                                           long currentCycleOrdinal) {
        ConceptHierarchy hierarchy = model.hierarchy();
        if (!outcome.receipt().modelFingerprint().equals(fingerprint)) {
            return ConceptFeedbackRejection.MODEL_MISMATCH;
        }
        if (!limits.trusts(outcome.verifierId())) {
            return ConceptFeedbackRejection.UNTRUSTED_VERIFIER;
        }
        switch (outcome.verification()) {
            case UNVERIFIED -> {
                return ConceptFeedbackRejection.UNVERIFIED;
            }
            case CONTRADICTED -> {
                return ConceptFeedbackRejection.AMBIGUOUS;
            }
            case VERIFIED -> {
            }
        }
        long origin = outcome.originCycleOrdinal();
        if (origin > currentCycleOrdinal || currentCycleOrdinal - origin > limits.maxEvidenceAge()) {
            return ConceptFeedbackRejection.AGE_OUT_OF_RANGE;
        }
        OptionalInt predicted = hierarchy.ordinalOf(outcome.receipt().predictedCode());
        if (predicted.isEmpty() || hierarchy.node(predicted.getAsInt()).depth() != outcome.receipt().depth()
                || leafOrdinal(hierarchy, outcome.verifiedCode()).isEmpty()
                || (outcome.verifiedPreviousCode().isPresent()
                        && leafOrdinal(hierarchy, outcome.verifiedPreviousCode()).isEmpty())) {
            return ConceptFeedbackRejection.UNKNOWN_CONCEPT;
        }
        SpectralPattern evidence = outcome.observedEvidence();
        if (evidence.version() != SpectralPattern.CURRENT_VERSION || !evidence.frame().equals(model.frame())) {
            return ConceptFeedbackRejection.INCOMPATIBLE_EVIDENCE;
        }
        return null;
    }

    private OptionalInt leafOrdinal(ConceptHierarchy hierarchy, OptionalLong code) {
        OptionalInt ordinal = hierarchy.ordinalOf(code.getAsLong());
        return ordinal.isPresent() && hierarchy.isLeaf(ordinal.getAsInt()) ? ordinal : OptionalInt.empty();
    }

    /**
     * Groups the outcomes that vote on a window in canonical order: every outcome from a trusted verifier on this
     * snapshot that is not {@code UNVERIFIED}, including a {@code CONTRADICTED} one and a verified one rejected by a
     * later check. A contradiction or two different labels make every accepted outcome of the window ambiguous;
     * otherwise the accepted repeats after the first are duplicates.
     */
    private void rejectRepeatedWindows(List<ConceptOutcome> batch, ConceptFeedbackRejection[] reasons) {
        TreeMap<ConceptOutcome, List<Integer>> windows = new TreeMap<>(WINDOW_ORDER);
        for (int i = 0; i < batch.size(); i++) {
            if (votes(reasons[i])) {
                windows.computeIfAbsent(batch.get(i), key -> new ArrayList<>(1)).add(i);
            }
        }
        for (List<Integer> indices : windows.values()) {
            rejectRepeats(batch, reasons, indices);
        }
    }

    /**
     * Groups the accepted outcomes by the window of their evidence, so one labeled window is absorbed at most once
     * per batch even under different receipts: repeats with the same labels are duplicates, different labels
     * ambiguous.
     */
    private void rejectRepeatedEvidence(List<ConceptOutcome> batch, ConceptFeedbackRejection[] reasons) {
        TreeMap<ConceptOutcome, List<Integer>> windows = new TreeMap<>(EVIDENCE_ORDER);
        for (int i = 0; i < batch.size(); i++) {
            if (reasons[i] == null) {
                windows.computeIfAbsent(batch.get(i), key -> new ArrayList<>(1)).add(i);
            }
        }
        for (List<Integer> indices : windows.values()) {
            rejectRepeats(batch, reasons, indices);
        }
    }

    private void rejectRepeats(List<ConceptOutcome> batch, ConceptFeedbackRejection[] reasons, List<Integer> indices) {
        if (indices.size() == 1) {
            return;
        }
        ConceptOutcome label = null;
        boolean agree = true;
        for (int index : indices) {
            ConceptOutcome other = batch.get(index);
            if (other.verification() != ConceptOutcome.Verification.VERIFIED) {
                agree = false;
            } else if (label == null) {
                label = other;
            } else {
                agree &= other.verifiedCode().equals(label.verifiedCode())
                        && other.verifiedPreviousCode().equals(label.verifiedPreviousCode());
            }
        }
        boolean kept = false;
        for (int index : indices) {
            if (reasons[index] != null) {
                continue;
            }
            if (!agree) {
                reasons[index] = ConceptFeedbackRejection.AMBIGUOUS;
            } else if (kept) {
                reasons[index] = ConceptFeedbackRejection.DUPLICATE;
            } else {
                kept = true;
            }
        }
    }

    /** Whether an outcome speaks for its window: trusted, about this snapshot, and not merely unverified. */
    private boolean votes(ConceptFeedbackRejection reason) {
        return reason == null || reason.compareTo(ConceptFeedbackRejection.AMBIGUOUS) >= 0;
    }

    private ConceptModelUpdate update(ConceptModel model, List<ConceptOutcome> batch,
                                      ConceptFeedbackRejection[] reasons) {
        ConceptHierarchy hierarchy = model.hierarchy();
        int leaves = hierarchy.leafCount();
        int bins = model.bins();
        double[] centroids = null;
        double[] norms = null;
        long[] exemplarCounts = null;
        long[] transitions = null;
        long[] leafCounts = null;
        long[] feedbackCounts = null;
        boolean[] touched = null;
        double[] feature = null;
        List<ConceptOutcome> applied = new ArrayList<>();
        int positive = 0;
        int exemplarsAdded = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (reasons[i] != null) {
                continue;
            }
            ConceptOutcome outcome = batch.get(i);
            int leaf = hierarchy.ordinalOf(outcome.verifiedCode().getAsLong()).getAsInt();
            int position = hierarchy.leafPosition(leaf);
            long[] feedbackSoFar = feedbackCounts == null ? model.feedbackCountsView() : feedbackCounts;
            long[] windowsSoFar = leafCounts == null ? model.leafCountsView() : leafCounts;
            long trainingWindows = windowsSoFar[position] - feedbackSoFar[position];
            if (feedbackSoFar[position] + 1 > limits.maxFeedbackShare() * trainingWindows) {
                reasons[i] = ConceptFeedbackRejection.CONCEPT_CAP;
                continue;
            }
            if (centroids == null) {
                centroids = model.centroidsView().clone();
                norms = model.centroidNormsView().clone();
                exemplarCounts = model.exemplarCountsView().clone();
                transitions = model.transitionsView().clone();
                leafCounts = model.leafCountsView().clone();
                feedbackCounts = model.feedbackCountsView().clone();
                touched = new boolean[hierarchy.size()];
                feature = new double[bins];
            }
            leafCounts[position]++;
            feedbackCounts[position]++;
            if (outcome.verifiedPreviousCode().isPresent()) {
                int previous = hierarchy.leafPosition(
                        hierarchy.ordinalOf(outcome.verifiedPreviousCode().getAsLong()).getAsInt());
                transitions[previous * leaves + position]++;
            }
            if (features.extract(outcome.observedEvidence(), feature)) {
                for (int concept = leaf; concept >= 0; concept = hierarchy.node(concept).parentOrdinal()) {
                    int offset = concept * bins;
                    if (!touched[concept]) {
                        touched[concept] = true;
                        for (int k = 0; k < bins; k++) {
                            centroids[offset + k] *= norms[concept];
                        }
                    }
                    for (int k = 0; k < bins; k++) {
                        centroids[offset + k] += feature[k];
                    }
                    exemplarCounts[concept]++;
                    exemplarsAdded++;
                }
            }
            if (outcome.polarity(hierarchy) == ConceptOutcome.Polarity.POSITIVE) {
                positive++;
            }
            applied.add(outcome);
        }
        if (applied.isEmpty()) {
            return unchanged(model, batch, reasons);
        }
        for (int concept = 0; concept < touched.length; concept++) {
            if (touched[concept]) {
                norms[concept] = features.normalize(centroids, concept * bins, bins);
            }
        }
        ConceptModel next = new ConceptModel(hierarchy, model.frame(), centroids, norms, exemplarCounts, transitions,
                leafCounts, feedbackCounts, model.temperature(), model.thresholds(), model.revision() + 1);
        var work = new ConceptModelUpdate.Work(batch.size(), applied.size(), positive, applied.size() - positive,
                exemplarsAdded, next.retainedBytes());
        return new ConceptModelUpdate.Updated(model, next, applied, rejected(batch, reasons), work);
    }

    private ConceptModelUpdate unchanged(ConceptModel model, List<ConceptOutcome> batch,
                                         ConceptFeedbackRejection[] reasons) {
        return new ConceptModelUpdate.Unchanged(model, rejected(batch, reasons),
                new ConceptModelUpdate.Work(batch.size(), 0, 0, 0, 0, 0L));
    }

    private List<ConceptModelUpdate.Rejected> rejected(List<ConceptOutcome> batch,
                                                       ConceptFeedbackRejection[] reasons) {
        List<ConceptModelUpdate.Rejected> rejected = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            if (reasons[i] != null) {
                rejected.add(new ConceptModelUpdate.Rejected(i, batch.get(i), reasons[i]));
            }
        }
        return rejected;
    }
}
