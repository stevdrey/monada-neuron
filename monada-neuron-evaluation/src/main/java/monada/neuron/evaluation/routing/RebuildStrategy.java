package monada.neuron.evaluation.routing;

/**
 * Where the adaptive arm starts a correction-triggered rebuild. Both strategies must give the same decisions,
 * feedback and learned state; only the replayed work differs.
 *
 * <ul>
 *   <li>{@link #SCRATCH}: a reset copy replays every observation delivered so far (the #67 baseline);</li>
 *   <li>{@link #CHECKPOINT}: the state takes a {@code RoutingStateCheckpoint} every {@link #CHECKPOINT_INTERVAL}
 *       deliveries, keeps the newest {@link #RETAINED_CHECKPOINTS}, compacts its ledger to the oldest of them, and a
 *       rebuild starts from the newest checkpoint whose cutoff lies strictly below the earliest affected sequence,
 *       replaying only the observations after it; with none left it falls back to {@link #SCRATCH}.</li>
 * </ul>
 */
public enum RebuildStrategy {
    SCRATCH,
    CHECKPOINT;

    /** Deliveries between two checkpoints. */
    public static final int CHECKPOINT_INTERVAL = 32;

    /** Checkpoints kept; the oldest is the one the ledger is compacted to. */
    public static final int RETAINED_CHECKPOINTS = 4;
}
