package monada.neuron.reasoning.concept;

/**
 * Why {@link ConceptFeedbackPolicy} did not apply an outcome (ADR 0049). Every rejection changes nothing; the checks
 * run in the declaration order below and the first failing one is reported.
 */
public enum ConceptFeedbackRejection {
    /** The batch exceeds {@link ConceptFeedbackLimits#maxOutcomesPerUpdate()}; the whole batch is rejected. */
    BATCH_LIMIT,
    /**
     * The receipt names another snapshot than the one being updated: an outcome of another model, task or an older
     * revision, including a batch replayed onto the snapshot it already produced.
     */
    MODEL_MISMATCH,
    /** The verifier is not one of {@link ConceptFeedbackLimits#trustedVerifiers()}. */
    UNTRUSTED_VERIFIER,
    /** The outcome is {@link ConceptOutcome.Verification#UNVERIFIED}: unknown outcomes abstain. */
    UNVERIFIED,
    /**
     * The outcome is {@link ConceptOutcome.Verification#CONTRADICTED}, or another outcome of the batch from a trusted
     * verifier on the same snapshot contradicts or labels differently the same predicted window (even when that
     * outcome fails a later check), or two accepted outcomes label the same evidence window differently; every
     * accepted outcome of that window is rejected.
     */
    AMBIGUOUS,
    /** The predicting cycle is after the current one or older than {@link ConceptFeedbackLimits#maxEvidenceAge()}. */
    AGE_OUT_OF_RANGE,
    /**
     * The predicted, verified or verified-previous code is not a concept of the hierarchy, a label is not a leaf, or
     * the receipt's depth is not its predicted concept's depth.
     */
    UNKNOWN_CONCEPT,
    /** The observed pattern has another version or frame than the snapshot. */
    INCOMPATIBLE_EVIDENCE,
    /**
     * The batch already holds an accepted outcome with the same labels for the same predicted window or the same
     * evidence window; the first one is kept.
     */
    DUPLICATE,
    /**
     * The leaf has absorbed {@link ConceptFeedbackLimits#maxFeedbackShare()} times its training windows of feedback:
     * feedback can never outweigh the labeled training data by more than that factor.
     */
    CONCEPT_CAP
}
