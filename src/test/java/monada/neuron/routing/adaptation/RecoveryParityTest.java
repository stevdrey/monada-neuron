package monada.neuron.routing.adaptation;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.recovery.BlockerKind;
import monada.neuron.routing.adaptation.recovery.CheckpointRecovery;
import monada.neuron.routing.adaptation.recovery.ExportState;
import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.ReplayWindow;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static monada.neuron.routing.adaptation.AdaptationFixtures.DEFINITION;
import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.PARTITION;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.demo;
import static monada.neuron.routing.adaptation.AdaptationFixtures.feedbackPolicy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The preflight and {@code rebuildFrom} share one rulebook: on the same fixture, the first restore problem's message is
 * the exception {@code rebuildFrom} throws, and a compatible assessment means the rebuild succeeds. Lives beside
 * {@link AdaptationFixtures}, which is package private.
 */
class RecoveryParityTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    /** One fixture per store backed blocker kind. */
    record Row(BlockerKind kind, RoutingStateCheckpoint checkpoint, RoutingStateStore target, long exportCutoff) {
    }

    private RoutingStateStore bound() {
        var store = store();
        store.bind(List.of(KEY_A, KEY_B));
        return store;
    }

    private RoutingObservation sample(String ref, String routeId, long sequence, Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, sequence, kind);
    }

    private void deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private static RoutingStateCheckpoint confirmedCheckpoint(RoutingStateStore store) {
        store.advanceCutoff(store.processedCutoff());
        return store.checkpoint(policy());
    }

    private RebuildResult rebuildFrom(RoutingStateStore target, RoutingStateCheckpoint checkpoint, long cutoff) {
        return target.rebuildFrom(checkpoint, List.of(), cutoff, (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
    }

    /** Both routes learned, cutoff 30. */
    private RoutingStateCheckpoint learned() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-2", "sub-b", 30, Kind.PASS));
        return confirmedCheckpoint(live);
    }

    /** Only sub-a bound, so exp-b is retained as a {@code NO_TARGET_BINDING} marker at 20; cutoff 20. */
    private RoutingStateCheckpoint withUnboundMarker() {
        var live = store();
        live.bind(List.of(KEY_A));
        deliverTo(live, sample("exp-b", "sub-b", 20, Kind.PASS));
        return confirmedCheckpoint(live);
    }

    private static RoutingStateCheckpoint handBuilt(String schema, List<CheckpointCohort> cohorts) {
        return new RoutingStateCheckpoint("scope-demo", schema, "forge-gates", "1", DEFINITION.mappingVersion(),
                "policy-x", "1", 0, cohorts, LedgerDigest.EMPTY, List.of());
    }

    private static List<CohortKey> routes(String prefix, int count) {
        List<CohortKey> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(new CohortKey("scope-demo", "implement", "UNKNOWN", new RouteKey(prefix + "-" + i, 1)));
        }
        return keys;
    }

    private List<Row> rows() {
        var learned = learned();
        var rows = new ArrayList<Row>();
        rows.add(new Row(BlockerKind.FOREIGN_SCOPE, learned,
                new RoutingStateStore(DEFINITION, new StatePartition("scope-other", "forge-gates", "1")), 30));
        rows.add(new Row(BlockerKind.FOREIGN_EVALUATION_POLICY, learned,
                new RoutingStateStore(DEFINITION, new StatePartition("scope-demo", "forge-gates", "2")), 30));
        rows.add(new Row(BlockerKind.MAPPING_VERSION_MISMATCH, learned,
                new RoutingStateStore(AdaptationFixtures.definition("adaptive-test/2"), PARTITION), 30));
        rows.add(new Row(BlockerKind.FEATURE_SCHEMA_MISMATCH, handBuilt("features/9", List.of()), store(), 0));

        var marked = withUnboundMarker();
        var rebucketedMarker = rebucketed(marked.retainedMarkers().getFirst());
        rows.add(new Row(BlockerKind.BUCKET_MAPPING_MISMATCH, new RoutingStateCheckpoint(marked.scopeId(),
                marked.featureSchemaVersion(), marked.evaluationPolicyId(), marked.evaluationPolicyVersion(),
                marked.mappingVersion(), marked.policyId(), marked.policyVersion(), marked.processedCutoff(),
                marked.preferenceSnapshot(), marked.ledgerDigest(), List.of(rebucketedMarker)), store(), 20));

        rows.add(new Row(BlockerKind.CHANNEL_OUT_OF_BOUNDS, handBuilt(DEFINITION.featureSchemaVersion(),
                List.of(new CheckpointCohort(KEY_A, 100.0, 50.0, 0.5, 10.0, 0))), store(), 0));
        rows.add(new Row(BlockerKind.EXPORT_BEHIND_CHECKPOINT, learned, store(), 29));
        var ahead = store();
        ahead.advanceCutoff(40);
        rows.add(new Row(BlockerKind.EXPORT_BEHIND_STORE, learned, ahead, 30));
        rows.add(new Row(BlockerKind.COHORT_BOUND_AFTER_CHECKPOINT, marked, bound(), 20));
        var crowded = store();
        crowded.bind(routes("route", RoutingStateStore.MAX_COHORTS - 1));
        rows.add(new Row(BlockerKind.COHORT_CAPACITY, learned, crowded, 30));
        return rows;
    }

    @Test
    void everyStoreBackedBlockerMatchesTheExceptionRebuildFromThrows() {
        for (Row row : rows()) {
            List<RestoreProblem> problems = row.target().restoreProblems(row.checkpoint(), row.exportCutoff());
            assertFalse(problems.isEmpty(), row.kind().name());
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> rebuildFrom(row.target(), row.checkpoint(), row.exportCutoff()), row.kind().name());
            assertEquals(problems.getFirst().message(), thrown.getMessage(), row.kind().name());
            var blocked = assertInstanceOf(RecoveryAssessment.Blocked.class, CheckpointRecovery.assess(
                    row.checkpoint(), row.target(), new ExportState.Observed(row.exportCutoff())), row.kind().name());
            assertEquals(row.kind(), blocked.blockers().getFirst().kind());
            assertEquals(problems.size(), blocked.blockers().size(), row.kind().name());
        }
    }

    @Test
    void everyKindHasARowOrIsNotStoreBacked() {
        var covered = EnumSet.of(BlockerKind.UNDECODABLE, BlockerKind.EXPORT_UNAVAILABLE);
        rows().forEach(row -> covered.add(row.kind()));
        assertEquals(EnumSet.allOf(BlockerKind.class), covered);
    }

    @Test
    void aCompatibleAssessmentMeansRebuildFromSucceeds() {
        var learned = learned();
        var target = store();
        assertEquals(List.of(), target.restoreProblems(learned, 30));
        var compatible = assertInstanceOf(RecoveryAssessment.Compatible.class,
                CheckpointRecovery.assess(learned, target, new ExportState.Observed(30)));
        assertEquals(30L, compatible.checkpointCutoff());
        assertEquals(new ReplayWindow(30, List.of()), compatible.window());
        var rebuilt = assertInstanceOf(RebuildResult.Rebuilt.class, rebuildFrom(target, learned, 30)).store();
        assertEquals(learned.preferenceSnapshot(), confirmedCheckpoint(rebuilt).preferenceSnapshot());
        assertEquals(List.of(KEY_A, KEY_B), rebuilt.cohorts(demo()).stream().map(CohortState::key).toList());
    }

    @Test
    void theReplayWindowNamesTheRetainedMarkersSamples() {
        var marked = withUnboundMarker();
        var target = store();
        target.bind(List.of(KEY_A));
        var compatible = assertInstanceOf(RecoveryAssessment.Compatible.class,
                CheckpointRecovery.assess(marked, target, new ExportState.Observed(25)));
        assertEquals(new ReplayWindow(20, List.of("exp-b")), compatible.window());
        assertEquals(25L, compatible.exportHighWatermark());
        assertInstanceOf(RebuildResult.Rebuilt.class, rebuildFrom(target, marked, 25));
    }

    // covers: AC-2
    @Test
    void anEvaluationPolicyDifferingByIdOrByVersionIsOneMismatch() {
        var learned = learned();
        var otherId = new RoutingStateStore(DEFINITION, new StatePartition("scope-demo", "other-gates", "1"));
        var otherVersion = new RoutingStateStore(DEFINITION, new StatePartition("scope-demo", "forge-gates", "2"));
        var both = new RoutingStateStore(DEFINITION, new StatePartition("scope-demo", "other-gates", "2"));
        var expected = List.<RestoreProblem>of(new RestoreProblem.Mismatch(PartitionField.EVALUATION_POLICY));
        assertEquals(expected, otherId.restoreProblems(learned, 30));
        assertEquals(expected, otherVersion.restoreProblems(learned, 30));
        assertEquals(expected, both.restoreProblems(learned, 30));
    }

    /** Only sub-a bound, so exp-b (at 20) and exp-c (at 21) are both retained as unbound markers; cutoff 21. */
    private RoutingStateCheckpoint withTwoUnboundMarkers() {
        var live = store();
        live.bind(List.of(KEY_A));
        deliverTo(live, sample("exp-b", "sub-b", 20, Kind.PASS));
        deliverTo(live, sample("exp-c", "sub-b", 21, Kind.PASS));
        return confirmedCheckpoint(live);
    }

    private static AppliedRevision rebucketed(AppliedRevision marker) {
        CohortBinding binding = ((EntryBasis.NoUpdateMarker) marker.basis()).cohortBinding();
        return new AppliedRevision(marker.identity(), marker.routingPolicyId(), marker.routingPolicyVersion(),
                marker.supersedesSequence(), marker.catalogVersion(), marker.cutoff(), marker.targetNodeId(),
                marker.costPeer(), new EntryBasis.NoUpdateMarker(NoUpdateReason.NO_TARGET_BINDING,
                        new CohortBinding(binding.cohortBucket(), "other-buckets/9", binding.mappingVersion(),
                                binding.featureSchemaVersion())),
                marker.disposition(), marker.score());
    }

    // covers: AC-2
    @Test
    void twoMarkersOfAnotherBucketMappingAreOneProblemNamingTheFirst() {
        var marked = withTwoUnboundMarkers();
        assertEquals(2, marked.retainedMarkers().size());
        var bothRebucketed = new RoutingStateCheckpoint(marked.scopeId(), marked.featureSchemaVersion(),
                marked.evaluationPolicyId(), marked.evaluationPolicyVersion(), marked.mappingVersion(),
                marked.policyId(), marked.policyVersion(), marked.processedCutoff(), marked.preferenceSnapshot(),
                marked.ledgerDigest(), marked.retainedMarkers().stream().map(RecoveryParityTest::rebucketed).toList());
        String expectedMapping = ((EntryBasis.NoUpdateMarker) marked.retainedMarkers().getFirst().basis())
                .cohortBinding().bucketMappingVersion();
        assertEquals(List.of(new RestoreProblem.BucketMappingMismatch("exp-b", "other-buckets/9", expectedMapping)),
                store().restoreProblems(bothRebucketed, 21));
    }

    // covers: AC-2
    @Test
    void twoMarkersNamingACohortBoundAfterTheCheckpointAreOneProblemNamingTheFirst() {
        var marked = withTwoUnboundMarkers();
        var target = bound();
        assertEquals(List.of(new RestoreProblem.CohortBoundAfterCheckpoint(KEY_B, "exp-b", 20, 21)),
                target.restoreProblems(marked, 21));
        assertEquals(new RestoreProblem.CohortBoundAfterCheckpoint(KEY_B, "exp-b", 20, 21).message(),
                assertThrows(IllegalArgumentException.class, () -> rebuildFrom(target, marked, 21)).getMessage());
    }

    // covers: AC-4
    @Test
    void aNegativeExportCutoffIsReportedWithTheMessageMainAlwaysThrew() {
        var learned = learned();
        var target = store();
        // a fresh store's watermark is 0, so a negative cutoff is behind the checkpoint and the store alike
        assertEquals(List.of(new RestoreProblem.ExportBehindCheckpoint(-1, 30),
                new RestoreProblem.ExportBehindStore(-1, 0)), target.restoreProblems(learned, -1));
        assertEquals("exportCutoff -1 is below the checkpoint's cutoff 30",
                assertThrows(IllegalArgumentException.class, () -> rebuildFrom(target, learned, -1)).getMessage());
    }

    // covers: AC-5
    @Test
    void theProblemListIsImmutable() {
        var learned = learned();
        var foreign = new RoutingStateStore(DEFINITION, new StatePartition("scope-other", "forge-gates", "1"));
        List<RestoreProblem> problems = foreign.restoreProblems(learned, 30);
        assertThrows(UnsupportedOperationException.class, problems::clear);
        assertThrows(UnsupportedOperationException.class, () -> store().restoreProblems(learned, 30).add(null));
    }

    @Test
    void compactAppliesThePartitionRulesWithTheSameMessage() {
        var learned = learned();
        var other = new RoutingStateStore(AdaptationFixtures.definition("adaptive-test/2"), PARTITION);
        assertEquals(other.restoreProblems(learned, 30).getFirst().message(),
                assertThrows(IllegalArgumentException.class, () -> other.compact(learned)).getMessage());
    }

    @Test
    void bothRestorabilityProblemsCanAppearTogether() {
        var marked = withUnboundMarker();
        var target = store();
        var keys = new ArrayList<>(routes("route", RoutingStateStore.MAX_COHORTS - 1));
        keys.add(KEY_B);
        target.bind(keys);
        List<RestoreProblem> problems = target.restoreProblems(marked, 20);
        assertEquals(2, problems.size());
        var named = assertInstanceOf(RestoreProblem.CohortBoundAfterCheckpoint.class, problems.getFirst());
        assertEquals(new RestoreProblem.CohortBoundAfterCheckpoint(KEY_B, "exp-b", 20, 20), named);
        assertEquals(new RestoreProblem.CohortCapacity(RoutingStateStore.MAX_COHORTS + 1, 0), problems.getLast());
        assertEquals(named.message(),
                assertThrows(IllegalArgumentException.class, () -> rebuildFrom(target, marked, 20)).getMessage());
    }
}
