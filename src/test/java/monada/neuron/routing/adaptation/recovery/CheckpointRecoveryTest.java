package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.ChangeSizeCohortMapping;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.CheckpointCohort;
import monada.neuron.routing.adaptation.LedgerDigest;
import monada.neuron.routing.adaptation.PartitionField;
import monada.neuron.routing.adaptation.RestoreProblem;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.adaptation.codec.CheckpointCodec;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.adaptation.codec.CheckpointRejectReason;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The assessment through the public API only: a host's view, with the reference definition. */
class CheckpointRecoveryTest {

    private static final AdaptiveStateDefinition DEFINITION = AdaptiveStateDefinition.reference();
    private static final StatePartition PARTITION = new StatePartition("scope", "eval", "1");
    private static final CohortKey KEY_A = new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-a", 1));
    private static final CohortKey KEY_B = new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-b", 1));
    private static final List<Caveat> FIXED =
            List.of(Caveat.REPLAY_REQUIRED, Caveat.UNVERIFIED_UNTIL_REPLAY, Caveat.EXPORT_COMPLETENESS_UNPROVEN);

    private static RoutingStateStore fresh() {
        return new RoutingStateStore(DEFINITION, PARTITION);
    }

    /** A checkpoint of a bound, confirmed store at cutoff 0, taken for policy {@code policy/1}. */
    private static RoutingStateCheckpoint checkpoint() {
        var store = fresh();
        store.bind(List.of(KEY_A));
        store.advanceCutoff(0);
        return store.checkpoint("policy", "1");
    }

    private static RoutingStateCheckpoint handBuilt(String scope, String schema, long cutoff,
            List<CheckpointCohort> cohorts) {
        return new RoutingStateCheckpoint(scope, schema, "eval", "1", DEFINITION.mappingVersion(), "policy", "1",
                cutoff, cohorts, LedgerDigest.EMPTY, List.of());
    }

    private static List<BlockerKind> kinds(RecoveryAssessment assessment) {
        return assertInstanceOf(RecoveryAssessment.Blocked.class, assessment).blockers().stream()
                .map(RecoveryBlocker::kind).toList();
    }

    // covers: AC-1
    @Test
    void compatibleCarriesWindowAndCaveats() {
        var assessment = CheckpointRecovery.assess(checkpoint(), fresh(), new ExportState.Observed(5));
        assertEquals(new RecoveryAssessment.Compatible(0, 5, new ReplayWindow(0, List.of()), FIXED), assessment);
        assertEquals(List.of("checkpointCutoff", "exportHighWatermark", "window", "caveats"),
                Arrays.stream(RecoveryAssessment.Compatible.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
    }

    // covers: AC-1
    @Test
    void aDecodedResultIsAssessedLikeItsCheckpoint() {
        var decoded = CheckpointCodec.decode(CheckpointCodec.encode(checkpoint()));
        assertEquals(CheckpointRecovery.assess(checkpoint(), fresh(), new ExportState.Observed(0)),
                CheckpointRecovery.assess(decoded, fresh(), new ExportState.Observed(0)));
    }

    // covers: AC-1
    @Test
    void aChangedRoutingPolicyIsANoticeNotABlocker() {
        var current = new LexicographicRoutingPolicy(DEFINITION.state(), 3, OptionalInt.empty(), Optional.empty());
        var store = fresh();
        store.bind(List.of(KEY_A));
        store.advanceCutoff(0);
        var sameCheckpoint = store.checkpoint(current);
        var changed = assertInstanceOf(RecoveryAssessment.Compatible.class,
                CheckpointRecovery.assess(checkpoint(), fresh(), new ExportState.Observed(0), current));
        assertEquals(List.of(Caveat.REPLAY_REQUIRED, Caveat.UNVERIFIED_UNTIL_REPLAY,
                Caveat.EXPORT_COMPLETENESS_UNPROVEN, Caveat.ROUTING_POLICY_CHANGED), changed.caveats());
        var same = assertInstanceOf(RecoveryAssessment.Compatible.class,
                CheckpointRecovery.assess(sameCheckpoint, fresh(), new ExportState.Observed(0), current));
        assertEquals(FIXED, same.caveats());
    }

    // covers: AC-2
    @Test
    void undecodableIsTheOnlyBlocker() {
        for (CheckpointRejectReason reason : CheckpointRejectReason.values()) {
            var rejected = new CheckpointDecodeResult.Rejected(reason);
            for (ExportState export : List.of(new ExportState.Observed(0), new ExportState.Unavailable())) {
                assertEquals(new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.Undecodable(reason))),
                        CheckpointRecovery.assess(rejected, fresh(), export));
            }
        }
    }

    // covers: AC-3
    @Test
    void unavailableExportIsBlockedAndStillListsTheStoresProblems() {
        assertEquals(new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.ExportUnavailable())),
                CheckpointRecovery.assess(checkpoint(), fresh(), new ExportState.Unavailable()));
        var foreign = new RoutingStateStore(DEFINITION, new StatePartition("other", "eval", "1"));
        assertEquals(new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.ExportUnavailable(),
                new RecoveryBlocker.FromStore(new RestoreProblem.Mismatch(PartitionField.SCOPE)))),
                CheckpointRecovery.assess(checkpoint(), foreign, new ExportState.Unavailable()));
        // the export rules never fire without an export, whatever the store's watermark
        var ahead = fresh();
        ahead.advanceCutoff(50);
        assertEquals(List.of(BlockerKind.EXPORT_UNAVAILABLE),
                kinds(CheckpointRecovery.assess(checkpoint(), ahead, new ExportState.Unavailable())));
    }

    // covers: AC-2
    @Test
    void everyBlockerIsReportedInCheckOrder() {
        var otherMapping = new AdaptiveStateDefinition(
                new RoutingStateDefinition("other/1", ChangeSizeCohortMapping.DEFAULT), DEFINITION.initial(),
                DEFINITION.adaptation(), DEFINITION.feedback());
        var target = new RoutingStateStore(otherMapping, new StatePartition("other", "eval", "2"));
        target.advanceCutoff(9);
        var checkpoint = handBuilt("scope", "features/9", 5,
                List.of(new CheckpointCohort(KEY_A, 100.0, 0.0, 0.0, 10.0, 0)));
        assertEquals(List.of(BlockerKind.FOREIGN_SCOPE, BlockerKind.FOREIGN_EVALUATION_POLICY,
                BlockerKind.MAPPING_VERSION_MISMATCH, BlockerKind.FEATURE_SCHEMA_MISMATCH,
                BlockerKind.CHANNEL_OUT_OF_BOUNDS, BlockerKind.EXPORT_BEHIND_CHECKPOINT,
                BlockerKind.EXPORT_BEHIND_STORE),
                kinds(CheckpointRecovery.assess(checkpoint, target, new ExportState.Observed(3))));
    }

    // covers: AC-2
    @Test
    void aRuleReportsItsFirstOffenderOnly() {
        var checkpoint = handBuilt("scope", DEFINITION.featureSchemaVersion(), 0, List.of(
                new CheckpointCohort(KEY_A, 100.0, 0.0, 0.0, 10.0, 0),
                new CheckpointCohort(KEY_B, 100.0, 0.0, 0.0, 10.0, 0)));
        var blocked = assertInstanceOf(RecoveryAssessment.Blocked.class,
                CheckpointRecovery.assess(checkpoint, fresh(), new ExportState.Observed(0)));
        assertEquals(List.of(new RecoveryBlocker.FromStore(new RestoreProblem.ChannelOutOfBounds(KEY_A))),
                blocked.blockers());
    }

    // covers: AC-1
    @Test
    void theDecodedOverloadCarriesThePolicyNoticeAndARejectionIgnoresIt() {
        var current = new LexicographicRoutingPolicy(DEFINITION.state(), 3, OptionalInt.empty(), Optional.empty());
        var decoded = CheckpointCodec.decode(CheckpointCodec.encode(checkpoint()));
        var changed = assertInstanceOf(RecoveryAssessment.Compatible.class,
                CheckpointRecovery.assess(decoded, fresh(), new ExportState.Observed(0), current));
        assertEquals(List.of(Caveat.REPLAY_REQUIRED, Caveat.UNVERIFIED_UNTIL_REPLAY,
                Caveat.EXPORT_COMPLETENESS_UNPROVEN, Caveat.ROUTING_POLICY_CHANGED), changed.caveats());
        var rejected = new CheckpointDecodeResult.Rejected(CheckpointRejectReason.MALFORMED);
        assertEquals(new RecoveryAssessment.Blocked(List.of(
                new RecoveryBlocker.Undecodable(CheckpointRejectReason.MALFORMED))),
                CheckpointRecovery.assess(rejected, fresh(), new ExportState.Observed(0), current));
    }

    // covers: AC-1
    @Test
    void aBlockedAssessmentCarriesNoPolicyNotice() {
        var current = new LexicographicRoutingPolicy(DEFINITION.state(), 3, OptionalInt.empty(), Optional.empty());
        var foreign = new RoutingStateStore(DEFINITION, new StatePartition("other", "eval", "1"));
        assertEquals(CheckpointRecovery.assess(checkpoint(), foreign, new ExportState.Observed(0)),
                CheckpointRecovery.assess(checkpoint(), foreign, new ExportState.Observed(0), current));
    }

    // covers: AC-1
    @Test
    void theValueTypesCopyTheirListsAndRefuseLaterChanges() {
        var caveats = new ArrayList<>(FIXED);
        var samples = new ArrayList<>(List.of("exp-b"));
        var compatible = new RecoveryAssessment.Compatible(0, 0, new ReplayWindow(0, samples), caveats);
        caveats.clear();
        samples.clear();
        assertEquals(FIXED, compatible.caveats());
        assertEquals(List.of("exp-b"), compatible.window().retainedSamples());
        assertThrows(UnsupportedOperationException.class, () -> compatible.caveats().clear());
        assertThrows(UnsupportedOperationException.class, () -> compatible.window().retainedSamples().clear());
        var blockers = new ArrayList<RecoveryBlocker>(List.of(new RecoveryBlocker.ExportUnavailable()));
        var blocked = new RecoveryAssessment.Blocked(blockers);
        blockers.clear();
        assertEquals(List.of(BlockerKind.EXPORT_UNAVAILABLE), kinds(blocked));
        assertThrows(UnsupportedOperationException.class, () -> blocked.blockers().clear());
    }

    @Test
    void everyEntryPointRefusesAMissingArgument() {
        var checkpoint = checkpoint();
        var decoded = CheckpointCodec.decode(CheckpointCodec.encode(checkpoint));
        var export = new ExportState.Observed(0);
        assertThrows(NullPointerException.class, () -> CheckpointRecovery.assess(checkpoint, null, export));
        assertThrows(NullPointerException.class, () -> CheckpointRecovery.assess(checkpoint, fresh(), null));
        assertThrows(NullPointerException.class,
                () -> CheckpointRecovery.assess(checkpoint, fresh(), export, (RoutingPolicy) null));
        assertThrows(NullPointerException.class,
                () -> CheckpointRecovery.assess((CheckpointDecodeResult) null, fresh(), export));
        assertThrows(NullPointerException.class, () -> CheckpointRecovery.assess(decoded, null, export));
        assertThrows(NullPointerException.class, () -> CheckpointRecovery.assess(decoded, fresh(), null));
        assertThrows(NullPointerException.class, () -> CheckpointRecovery.assess(decoded, fresh(), export, null));
        assertThrows(NullPointerException.class, () -> new RecoveryBlocker.Undecodable(null));
        assertThrows(NullPointerException.class, () -> new RecoveryBlocker.FromStore(null));
        assertThrows(NullPointerException.class, () -> new RecoveryAssessment.Blocked(null));
        assertThrows(NullPointerException.class,
                () -> new RecoveryAssessment.Blocked(Arrays.asList((RecoveryBlocker) null)));
        assertThrows(NullPointerException.class, () -> new ReplayWindow(0, null));
        assertThrows(IllegalArgumentException.class, () -> new ReplayWindow(-1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryAssessment.Compatible(-1, 0, new ReplayWindow(0, List.of()), FIXED));
    }

    @Test
    void theValueTypesRefuseInconsistentInput() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryAssessment.Blocked(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExportState.Observed(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryAssessment.Compatible(5, 4, new ReplayWindow(5, List.of()), FIXED));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreProblem.Mismatch(PartitionField.BUCKET_MAPPING));
        assertThrows(NullPointerException.class,
                () -> CheckpointRecovery.assess((RoutingStateCheckpoint) null, fresh(), new ExportState.Unavailable()));
        // blockers are ordered by kind whatever order they were given in
        var ordered = new RecoveryAssessment.Blocked(List.of(
                new RecoveryBlocker.FromStore(new RestoreProblem.CohortCapacity(257, 0)),
                new RecoveryBlocker.ExportUnavailable()));
        assertEquals(List.of(BlockerKind.EXPORT_UNAVAILABLE, BlockerKind.COHORT_CAPACITY), kinds(ordered));
    }
}
