package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.RestoreProblem;
import monada.neuron.routing.adaptation.StateUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The next actions a host should try, in order, after an assessment or a failed replay. A plan is advice: it changes
 * nothing, and it never proposes discarding the learning. {@code REBUILD_FROM_SCRATCH} means a full
 * {@code RoutingStateStore.rebuild} over the export, never a new empty store without one.
 *
 * <p>How to read the order: {@code OBTAIN_EXPORT}, when present, comes first and is a prerequisite of every later step,
 * because every path that ends in a replay or a rebuild consumes an export. The steps after it are alternatives in
 * order of preference: a host takes the first one it can and, when that one does not lead to a restore, moves to the
 * next; {@code REBUILD_FROM_SCRATCH} is always the last resort. An {@code UNDECODABLE} plan never mentions the export,
 * because a rejected decode inspects nothing else; its rebuild still needs one.
 *
 * @param steps at least one step
 */
public record RecoveryPlan(List<RecoveryStep> steps) {

    /** Requires at least one step and copies the list. */
    public RecoveryPlan {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a plan holds at least one step");
        }
    }

    /**
     * Plans from an assessment. {@code Compatible} is one {@code REPLAY_FROM_CHECKPOINT}; {@code Blocked} plans from
     * its first blocker, because the later ones are usually its consequences. When the first blocker is
     * {@code EXPORT_UNAVAILABLE} and store blockers follow, {@code OBTAIN_EXPORT} comes first and the plan of the
     * first store blocker after it: whichever of its alternatives the host picks ends in a replay or a rebuild, and
     * both need the export.
     *
     * @throws NullPointerException when the assessment is null; every value a {@code RecoveryAssessment} can carry
     *         plans without an exception
     */
    public static RecoveryPlan of(RecoveryAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment must not be null");
        return switch (assessment) {
            case RecoveryAssessment.Compatible ignored ->
                    new RecoveryPlan(List.of(RecoveryStep.of(RecoveryAction.REPLAY_FROM_CHECKPOINT)));
            case RecoveryAssessment.Blocked blocked -> {
                List<RecoveryBlocker> blockers = blocked.blockers();
                if (blockers.getFirst() instanceof RecoveryBlocker.ExportUnavailable && blockers.size() > 1) {
                    var steps = new ArrayList<RecoveryStep>();
                    steps.add(RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT));
                    steps.addAll(stepsFor(blockers.get(1)));
                    yield new RecoveryPlan(steps);
                }
                yield new RecoveryPlan(stepsFor(blockers.getFirst()));
            }
        };
    }

    /**
     * Plans from where a replay stopped. A {@code BeyondCheckpoint} of kind {@code SUPERSEDES_COMPACTED} or
     * {@code BEFORE_HORIZON} asks for a checkpoint whose cutoff is strictly below {@code affectedFromSequence} (the
     * superseded position, or the observation's own sequence), or a rebuild from scratch; a {@code RebuildRequired}
     * asks for the rebuild from scratch the staged store itself asked for; anything else is handed back for the host
     * to inspect, because Neuron cannot see its cause.
     */
    public static RecoveryPlan after(RebuildResult.Failed failed) {
        Objects.requireNonNull(failed, "failed must not be null");
        StateUpdate update = failed.update();
        return switch (update) {
            case StateUpdate.BeyondCheckpoint beyond when beyond.kind() != StateUpdate.BeyondCheckpoint.Kind.COVERED ->
                    new RecoveryPlan(olderOrScratch(beyond.affectedFromSequence()));
            case StateUpdate.RebuildRequired ignored ->
                    new RecoveryPlan(List.of(RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH)));
            default -> new RecoveryPlan(List.of(RecoveryStep.inspect(update)));
        };
    }

    private static List<RecoveryStep> stepsFor(RecoveryBlocker blocker) {
        return switch (blocker) {
            case RecoveryBlocker.Undecodable ignored -> List.of(RecoveryStep.of(RecoveryAction.OBTAIN_READABLE_CHECKPOINT),
                    RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH));
            case RecoveryBlocker.ExportUnavailable ignored -> List.of(RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT));
            case RecoveryBlocker.FromStore(RestoreProblem problem) -> switch (problem) {
                case RestoreProblem.Mismatch ignored -> wrongSetupOrCheckpoint();
                case RestoreProblem.BucketMappingMismatch ignored -> wrongSetupOrCheckpoint();
                case RestoreProblem.ChannelOutOfBounds ignored -> wrongSetupOrCheckpoint();
                // a checkpoint at or below the export's watermark is accepted, so the bound is the watermark plus one
                case RestoreProblem.ExportBehindCheckpoint behind -> exportOrOlder(behind.exportCutoff() + 1);
                case RestoreProblem.ExportBehindStore ignored -> List.of(RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT));
                // a checkpoint with a cutoff below the naming marker's sequence does not retain it
                case RestoreProblem.CohortBoundAfterCheckpoint bound -> olderOrScratch(bound.markerSequence());
                case RestoreProblem.CohortCapacity ignored -> List.of(RecoveryStep.of(RecoveryAction.FIX_HOST_CONFIGURATION),
                        RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH));
            };
        };
    }

    /** The preflight cannot tell a wrong definition or partition from a checkpoint of another one. */
    private static List<RecoveryStep> wrongSetupOrCheckpoint() {
        return List.of(RecoveryStep.of(RecoveryAction.FIX_HOST_CONFIGURATION),
                RecoveryStep.of(RecoveryAction.OBTAIN_READABLE_CHECKPOINT),
                RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH));
    }

    /** Sequence 0 is a valid position, so no checkpoint lies strictly below it: then only the rebuild remains. */
    private static List<RecoveryStep> olderOrScratch(long cutoffBelow) {
        if (cutoffBelow <= 0) {
            return List.of(RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH));
        }
        return List.of(RecoveryStep.olderCheckpoint(cutoffBelow), RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH));
    }

    /** A negative export watermark (host input) leaves no checkpoint to pick: then only a newer export helps. */
    private static List<RecoveryStep> exportOrOlder(long cutoffBelow) {
        if (cutoffBelow <= 0) {
            return List.of(RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT));
        }
        return List.of(RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT), RecoveryStep.olderCheckpoint(cutoffBelow));
    }
}
