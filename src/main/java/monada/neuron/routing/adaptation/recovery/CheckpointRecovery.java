package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.adaptation.RestoreProblem;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.feedback.AppliedRevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read only preflight of a checkpoint restore (issue #101): would {@code RoutingStateStore.rebuildFrom} with this
 * checkpoint, into this store, over this export, pass every check before replay? It asks the store the same question
 * through {@link RoutingStateStore#restoreProblems}, so there is one rulebook, and it returns a value: nothing is
 * bound, staged, compacted or advanced, and no store is built. Restoring remains the host's explicit
 * {@code rebuildFrom} call.
 *
 * <p>Every function here is pure in its inputs, so they are class level, like {@code CheckpointCodec}.
 */
public final class CheckpointRecovery {

    private CheckpointRecovery() {
    }

    /**
     * Assesses a decoded checkpoint against the store it would be restored into and the export the host holds.
     *
     * @param checkpoint the checkpoint
     * @param target the store {@code rebuildFrom} would be called on (a new, empty store after a restart)
     * @param export what the host knows about the export
     * @return {@code Compatible}, or {@code Blocked} with every reason in check order
     */
    public static RecoveryAssessment assess(RoutingStateCheckpoint checkpoint, RoutingStateStore target,
            ExportState export) {
        return assess(checkpoint, target, export, Optional.empty());
    }

    /**
     * As {@link #assess(RoutingStateCheckpoint, RoutingStateStore, ExportState)}, adding
     * {@link Caveat#ROUTING_POLICY_CHANGED} when the current policy's id or version differs from the checkpoint's.
     *
     * @param currentPolicy the routing policy the host decides with now
     */
    public static RecoveryAssessment assess(RoutingStateCheckpoint checkpoint, RoutingStateStore target,
            ExportState export, RoutingPolicy currentPolicy) {
        return assess(checkpoint, target, export,
                Optional.of(Objects.requireNonNull(currentPolicy, "currentPolicy must not be null")));
    }

    /**
     * Assesses a codec result: a {@code Rejected} is {@code Blocked} with one {@link RecoveryBlocker.Undecodable}
     * blocker and nothing else is inspected; a {@code Decoded} checkpoint is assessed as above.
     */
    public static RecoveryAssessment assess(CheckpointDecodeResult decoded, RoutingStateStore target,
            ExportState export) {
        return assess(decoded, target, export, Optional.empty());
    }

    /** As {@link #assess(CheckpointDecodeResult, RoutingStateStore, ExportState)}, with the policy notice. */
    public static RecoveryAssessment assess(CheckpointDecodeResult decoded, RoutingStateStore target,
            ExportState export, RoutingPolicy currentPolicy) {
        return assess(decoded, target, export,
                Optional.of(Objects.requireNonNull(currentPolicy, "currentPolicy must not be null")));
    }

    private static RecoveryAssessment assess(CheckpointDecodeResult decoded, RoutingStateStore target,
            ExportState export, Optional<RoutingPolicy> currentPolicy) {
        Objects.requireNonNull(decoded, "decoded must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(export, "export must not be null");
        return switch (decoded) {
            case CheckpointDecodeResult.Rejected rejected ->
                    new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.Undecodable(rejected.reason())));
            case CheckpointDecodeResult.Decoded ok -> assess(ok.checkpoint(), target, export, currentPolicy);
        };
    }

    private static RecoveryAssessment assess(RoutingStateCheckpoint checkpoint, RoutingStateStore target,
            ExportState export, Optional<RoutingPolicy> currentPolicy) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(export, "export must not be null");
        return switch (export) {
            case ExportState.Unavailable ignored -> {
                // no export rule can trip at this cutoff, so the host still sees every partition and restorability
                // problem and does not fetch an export for a checkpoint that could never restore
                var blockers = new ArrayList<RecoveryBlocker>();
                blockers.add(new RecoveryBlocker.ExportUnavailable());
                blockers.addAll(fromStore(target.restoreProblems(checkpoint, Long.MAX_VALUE)));
                yield new RecoveryAssessment.Blocked(blockers);
            }
            case ExportState.Observed observed -> {
                List<RestoreProblem> problems = target.restoreProblems(checkpoint, observed.highWatermark());
                if (!problems.isEmpty()) {
                    yield new RecoveryAssessment.Blocked(fromStore(problems));
                }
                var caveats = new ArrayList<Caveat>(List.of(Caveat.REPLAY_REQUIRED, Caveat.UNVERIFIED_UNTIL_REPLAY,
                        Caveat.EXPORT_COMPLETENESS_UNPROVEN));
                if (currentPolicy.isPresent() && !(currentPolicy.get().policyId().equals(checkpoint.policyId())
                        && currentPolicy.get().policyVersion().equals(checkpoint.policyVersion()))) {
                    caveats.add(Caveat.ROUTING_POLICY_CHANGED);
                }
                var retained = new ArrayList<String>(checkpoint.retainedMarkers().size());
                for (AppliedRevision marker : checkpoint.retainedMarkers()) {
                    retained.add(marker.experienceRef());
                }
                yield new RecoveryAssessment.Compatible(checkpoint.processedCutoff(), observed.highWatermark(),
                        new ReplayWindow(checkpoint.processedCutoff(), retained), caveats);
            }
        };
    }

    private static List<RecoveryBlocker> fromStore(List<RestoreProblem> problems) {
        var blockers = new ArrayList<RecoveryBlocker>(problems.size());
        for (RestoreProblem problem : problems) {
            blockers.add(new RecoveryBlocker.FromStore(problem));
        }
        return blockers;
    }
}
