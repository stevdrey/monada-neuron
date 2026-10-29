package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.adaptation.PartitionField;
import monada.neuron.routing.adaptation.RestoreProblem;
import monada.neuron.routing.adaptation.codec.CheckpointRejectReason;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan table from assessments; plans from real replay failures live in {@code RecoveryReplayPlanTest}. */
class RecoveryPlanTest {

    private static final CohortKey KEY = new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("r", 1));
    private static final RecoveryStep FIX = RecoveryStep.of(RecoveryAction.FIX_HOST_CONFIGURATION);
    private static final RecoveryStep READABLE = RecoveryStep.of(RecoveryAction.OBTAIN_READABLE_CHECKPOINT);
    private static final RecoveryStep SCRATCH = RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH);
    private static final RecoveryStep EXPORT = RecoveryStep.of(RecoveryAction.OBTAIN_EXPORT);

    private static List<RecoveryStep> plan(RecoveryBlocker... blockers) {
        return RecoveryPlan.of(new RecoveryAssessment.Blocked(List.of(blockers))).steps();
    }

    private static RecoveryBlocker fromStore(RestoreProblem problem) {
        return new RecoveryBlocker.FromStore(problem);
    }

    // covers: AC-6
    @Test
    void compatiblePlansTheReplay() {
        var compatible = new RecoveryAssessment.Compatible(0, 0, new ReplayWindow(0, List.of()), List.of());
        assertEquals(List.of(RecoveryStep.of(RecoveryAction.REPLAY_FROM_CHECKPOINT)),
                RecoveryPlan.of(compatible).steps());
    }

    // covers: AC-6
    @Test
    void eachBlockerKindPlansItsNextActions() {
        assertEquals(List.of(READABLE, SCRATCH),
                plan(new RecoveryBlocker.Undecodable(CheckpointRejectReason.CORRUPT)));
        assertEquals(List.of(EXPORT), plan(new RecoveryBlocker.ExportUnavailable()));
        for (PartitionField field : List.of(PartitionField.SCOPE, PartitionField.EVALUATION_POLICY,
                PartitionField.MAPPING_VERSION, PartitionField.FEATURE_SCHEMA)) {
            assertEquals(List.of(FIX, READABLE, SCRATCH), plan(fromStore(new RestoreProblem.Mismatch(field))));
        }
        assertEquals(List.of(FIX, READABLE, SCRATCH),
                plan(fromStore(new RestoreProblem.BucketMappingMismatch("exp", "a/1", "b/1"))));
        assertEquals(List.of(FIX, READABLE, SCRATCH), plan(fromStore(new RestoreProblem.ChannelOutOfBounds(KEY))));
        assertEquals(List.of(EXPORT, RecoveryStep.olderCheckpoint(4)),
                plan(fromStore(new RestoreProblem.ExportBehindCheckpoint(3, 5))));
        assertEquals(List.of(EXPORT), plan(fromStore(new RestoreProblem.ExportBehindStore(3, 9))));
        assertEquals(List.of(RecoveryStep.olderCheckpoint(20), SCRATCH),
                plan(fromStore(new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 20, 20))));
        assertEquals(List.of(FIX, SCRATCH), plan(fromStore(new RestoreProblem.CohortCapacity(257, 1))));
    }

    // covers: AC-6
    @Test
    void anExclusiveBoundOfZeroLeavesOnlyTheRebuild() {
        assertEquals(List.of(SCRATCH),
                plan(fromStore(new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 0, 0))));
    }

    // covers: AC-6
    @Test
    void onlyTheFirstBlockerDrivesThePlanAndAnUnavailableExportComesFirst() {
        assertEquals(List.of(FIX, READABLE, SCRATCH), plan(fromStore(new RestoreProblem.Mismatch(PartitionField.SCOPE)),
                fromStore(new RestoreProblem.CohortCapacity(257, 0))));
        // every alternative after the export ends in a replay or a rebuild, so the export is obtained first
        assertEquals(List.of(EXPORT, FIX, READABLE, SCRATCH), plan(new RecoveryBlocker.ExportUnavailable(),
                fromStore(new RestoreProblem.Mismatch(PartitionField.SCOPE))));
        assertEquals(List.of(EXPORT, RecoveryStep.olderCheckpoint(7), SCRATCH), plan(
                new RecoveryBlocker.ExportUnavailable(),
                fromStore(new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 7, 9))));
    }

    // covers: AC-6
    @Test
    void thereIsNoActionThatDiscardsLearning() {
        assertEquals(List.of("REPLAY_FROM_CHECKPOINT", "USE_OLDER_CHECKPOINT", "REBUILD_FROM_SCRATCH",
                "OBTAIN_READABLE_CHECKPOINT", "OBTAIN_EXPORT", "FIX_HOST_CONFIGURATION", "INSPECT_FAILED_OBSERVATION"),
                List.of(RecoveryAction.values()).stream().map(Enum::name).toList());
        for (RecoveryAction action : RecoveryAction.values()) {
            String name = action.name();
            assertTrue(!name.contains("DISCARD") && !name.contains("RESET") && !name.contains("COLD"), name);
        }
    }

    // covers: AC-6
    @Test
    void aPlanIsImmutableAndRefusesAMissingInput() {
        var plan = RecoveryPlan.of(new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.ExportUnavailable())));
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
        var steps = new ArrayList<>(List.of(EXPORT));
        var copied = new RecoveryPlan(steps);
        steps.clear();
        assertEquals(List.of(EXPORT), copied.steps());
        assertThrows(NullPointerException.class, () -> RecoveryPlan.of(null));
        assertThrows(NullPointerException.class, () -> RecoveryPlan.after(null));
        assertThrows(NullPointerException.class, () -> new RecoveryPlan(null));
        assertThrows(NullPointerException.class, () -> RecoveryStep.inspect(null));
        assertThrows(NullPointerException.class, () -> RecoveryStep.of(null));
    }

    // covers: AC-6
    @Test
    void theOlderCheckpointBoundIsTheOneTheBlockerCarries() {
        assertEquals(OptionalLong.of(1), plan(fromStore(new RestoreProblem.ExportBehindCheckpoint(0, 5)))
                .get(1).cutoffBelow());
        assertEquals(OptionalLong.of(Long.MAX_VALUE), plan(fromStore(
                new RestoreProblem.ExportBehindCheckpoint(Long.MAX_VALUE - 1, Long.MAX_VALUE))).get(1).cutoffBelow());
        // no checkpoint lies at or below a negative export watermark, so only a newer export helps
        assertEquals(List.of(EXPORT), plan(fromStore(new RestoreProblem.ExportBehindCheckpoint(-1, 0))));
        assertEquals(List.of(EXPORT), plan(fromStore(new RestoreProblem.ExportBehindCheckpoint(-7, 3))));
        assertEquals(OptionalLong.of(1), plan(fromStore(new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 1, 1)))
                .getFirst().cutoffBelow());
        assertEquals(OptionalLong.of(Long.MAX_VALUE), plan(fromStore(
                new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", Long.MAX_VALUE, Long.MAX_VALUE)))
                .getFirst().cutoffBelow());
    }

    @Test
    void aStepCarriesExactlyWhatItsActionNeeds() {
        assertThrows(IllegalArgumentException.class, () -> RecoveryStep.olderCheckpoint(0));
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryStep(RecoveryAction.USE_OLDER_CHECKPOINT, OptionalLong.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryStep(RecoveryAction.OBTAIN_EXPORT, OptionalLong.of(3), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryStep(
                RecoveryAction.INSPECT_FAILED_OBSERVATION, OptionalLong.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryPlan(List.of()));
    }
}
