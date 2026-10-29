package monada.neuron.routing.adaptation.recovery;

/**
 * What a {@link RecoveryAssessment.Compatible} assessment cannot promise. The first three are present on every
 * compatible assessment; the fourth only when the host passed its current routing policy and it differs from the
 * checkpoint's. Declaration order is the report order.
 */
public enum Caveat {

    /** Nothing is restored until the host calls {@code RoutingStateStore.rebuildFrom}; the assessment is a value. */
    REPLAY_REQUIRED,

    /**
     * A later revision that supersedes something at or below the checkpoint's cutoff is seen only during replay, as
     * {@code RebuildResult.Failed} with {@code BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED}; the preflight cannot see it.
     */
    UNVERIFIED_UNTIL_REPLAY,

    /**
     * The preflight sees the export's watermark, not its observations: an export cut off below a watermark that still
     * reaches past the cutoff passes.
     */
    EXPORT_COMPLETENESS_UNPROVEN,

    /**
     * The host's current routing policy id or version differs from the one the checkpoint was taken for. The learned
     * state is provenance of that policy; {@code rebuildFrom} ignores it, so this is a notice, not a blocker.
     */
    ROUTING_POLICY_CHANGED
}
