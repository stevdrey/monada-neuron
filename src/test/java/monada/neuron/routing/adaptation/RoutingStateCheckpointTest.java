package monada.neuron.routing.adaptation;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingStateCheckpointTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    private RoutingStateStore bound() {
        var store = store();
        store.bind(List.of(KEY_A, KEY_B));
        return store;
    }

    private RoutingObservation sample(String ref, String routeId, long revision, long sequence, Feature<Long> previous,
            Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, revision, sequence, previous, kind);
    }

    private RoutingObservation sample(String ref, String routeId, long sequence, Kind kind) {
        return sample(ref, routeId, 0, sequence, Feature.unknown(), kind);
    }

    private StateUpdate deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        return deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private RoutingObservation corrected(RoutingObservation original, long sequence, Kind kind) {
        String ref = original.experienceRef();
        return observation(requests.get(ref), decisions.get(ref), ref, original.revision() + 1, sequence,
                Feature.known(original.ledgerSequence()), kind);
    }

    private RebuildResult rebuildFrom(RoutingStateStore live, RoutingStateCheckpoint checkpoint,
            List<RoutingObservation> observations, long cutoff) {
        return live.rebuildFrom(checkpoint, observations, cutoff, (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
    }

    private RoutingStateStore scratch(List<RoutingObservation> observations, long cutoff) {
        var result = bound().rebuild(observations, cutoff, List.of(KEY_A, KEY_B), (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
        return assertInstanceOf(RebuildResult.Rebuilt.class, result).store();
    }

    /** Equal Node channels, support, digest, watermark and snapshot; the ledger is equal above the horizon. */
    private void assertSameState(RoutingStateStore expected, RoutingStateStore actual) {
        assertEquals(expected.cohorts(demo()), actual.cohorts(demo()));
        assertEquals(expected.ledgerDigest(), actual.ledgerDigest());
        assertEquals(expected.processedCutoff(), actual.processedCutoff());
        assertEquals(expected.snapshot(policy(), demo()), actual.snapshot(policy(), demo()));
        long horizon = actual.compactionHorizon().orElse(-1);
        assertEquals(expected.ledger().stream().filter(e -> e.ledgerSequence() > horizon || e.retryable()).toList(),
                actual.ledger());
    }

    @Test
    void aCheckpointHoldsEveryCohortAndTheLedgerDigest() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-2", "sub-b", 30, Kind.FAIL));
        var checkpoint = live.checkpoint(policy());
        assertEquals("scope-demo", checkpoint.scopeId());
        assertEquals(30L, checkpoint.processedCutoff());
        assertEquals(LedgerDigest.of(live.ledger()), checkpoint.ledgerDigest());
        assertEquals(live.ledgerDigest(), checkpoint.ledgerDigest());
        assertEquals(List.of(), checkpoint.retainedMarkers());
        // dormant cohorts are held too: a RoutingPreference would omit sub-b for a catalog without it
        var only = AdaptationFixtures.only("sub-a");
        assertEquals(1, live.snapshot(policy(), only).cohorts().size());
        assertEquals(2, checkpoint.preferenceSnapshot().size());
        var a = checkpoint.preferenceSnapshot().getFirst();
        assertEquals(new CheckpointCohort(KEY_A, 5.6, 50.0, 0.5, 10.8, 1), a);
        var b = checkpoint.preferenceSnapshot().getLast();
        assertEquals(new CheckpointCohort(KEY_B, 3.2, 50.0, 0.5, 9.6, 1), b);
        assertEquals(OptionalLong.empty(), live.compactionHorizon());
    }

    @Test
    void compactionDropsCoveredEntriesWithoutChangingTheState() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-2", "sub-b", 30, Kind.FAIL));
        var checkpoint = live.checkpoint(policy());
        deliverTo(live, sample("exp-3", "sub-a", 40, Kind.PASS));
        var cohorts = live.cohorts(demo());
        var digest = live.ledgerDigest();

        assertEquals(new CompactionResult.Compacted(2, 30), live.compact(checkpoint));
        assertEquals(List.of("exp-3"), live.ledger().stream().map(AppliedRevision::experienceRef).toList());
        assertEquals(cohorts, live.cohorts(demo()));
        assertEquals(digest, live.ledgerDigest());
        assertEquals(40L, live.processedCutoff());
        assertEquals(OptionalLong.of(30), live.compactionHorizon());
        assertEquals(checkpoint, live.latestCheckpoint().orElseThrow());
        // compacting again with the same checkpoint is a no-op
        assertEquals(new CompactionResult.Compacted(0, 30), live.compact(checkpoint));
    }

    @Test
    void aFullLedgerLearnsAgainAfterCompaction() {
        var live = bound();
        for (int i = 0; i < RoutingStateStore.MAX_LEDGER; i++) {
            assertInstanceOf(StateUpdate.Applied.class,
                    deliverTo(live, sample(String.format("r-%05d", i), "sub-a", 100 + i, Kind.PENDING)));
        }
        var overflow = sample("r-overflow", "sub-b", 90_000, Kind.PASS);
        var blocked = assertInstanceOf(StateUpdate.Blocked.class, deliverTo(live, overflow));
        assertInstanceOf(Reconciliation.CapacityExhausted.class, blocked.advice());

        var checkpoint = live.checkpoint(policy());
        assertEquals(new CompactionResult.Compacted(RoutingStateStore.MAX_LEDGER, 100 + 4095), live.compact(checkpoint));
        assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, overflow));
        assertEquals(1, live.ledger().size());
        assertEquals(RoutingStateStore.MAX_LEDGER + 1, live.ledgerDigest().entries());
        // the retry was applied exactly once: a redelivery is a duplicate, a compacted sample is covered
        assertInstanceOf(StateUpdate.Unchanged.class, deliverTo(live, overflow));
        assertEquals(new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.COVERED, 4195, 150),
                deliverTo(live, sample("r-00050", "sub-a", 150, Kind.PENDING)));
        assertEquals(1, live.cohorts(demo()).getLast().supportingObservations());
    }

    @Test
    void aCorrectionOfACompactedRevisionIsBeyondTheCheckpoint() {
        var live = bound();
        var first = sample("exp-1", "sub-a", 20, Kind.PASS);
        var second = sample("exp-2", "sub-b", 30, Kind.PASS);
        deliverTo(live, first);
        var early = live.checkpoint(policy());
        deliverTo(live, second);
        var late = live.checkpoint(policy());
        live.compact(late);
        var cohorts = live.cohorts(demo());
        var ledger = live.ledger();

        var correction = corrected(first, 50, Kind.FAIL);
        assertEquals(new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED, 30, 20),
                deliverTo(live, correction));
        assertEquals(cohorts, live.cohorts(demo()));
        assertEquals(ledger, live.ledger());
        assertEquals(30L, live.processedCutoff());

        // the late checkpoint contains the superseded effect; the early one does not (cutoff 20 is not < 20 either)
        var all = List.of(first, second, correction);
        var failed = assertInstanceOf(RebuildResult.Failed.class, rebuildFrom(live, late, all, 50));
        assertEquals(correction, failed.observation());
        assertInstanceOf(StateUpdate.BeyondCheckpoint.class, failed.update());
        assertInstanceOf(RebuildResult.Failed.class, rebuildFrom(live, early, all, 50));
        var expected = scratch(all, 50);
        var fromScratch = assertInstanceOf(RebuildResult.Rebuilt.class, live.rebuild(all, 50,
                List.of(KEY_A, KEY_B), (o, bindings) -> feedbackPolicy().derive(o, decisions.get(o.experienceRef()),
                        requests.get(o.experienceRef()), bindings))).store();
        assertSameState(expected, fromScratch);
        assertEquals(3.2, fromScratch.cohorts(demo()).getFirst().amplitude(), 1e-12);
    }

    @Test
    void aRebuildFromAValidCheckpointEqualsARebuildFromScratchAfterACorrection() {
        var live = bound();
        var first = sample("exp-1", "sub-a", 20, Kind.PASS);
        var second = sample("exp-2", "sub-b", 30, Kind.PASS);
        var third = sample("exp-3", "sub-a", 40, Kind.FAIL);
        deliverTo(live, first);
        var checkpoint = live.checkpoint(policy());
        deliverTo(live, second);
        deliverTo(live, third);
        live.compact(checkpoint);
        var correction = corrected(second, 60, Kind.FAIL);
        var rebuild = assertInstanceOf(StateUpdate.RebuildRequired.class, deliverTo(live, correction));
        assertTrue(checkpoint.processedCutoff() < rebuild.advice().affectedFromSequence());

        var all = List.of(first, second, third, correction);
        var restored = assertInstanceOf(RebuildResult.Rebuilt.class, rebuildFrom(live, checkpoint, all, 60)).store();
        assertSameState(scratch(all, 60), restored);
        assertEquals(checkpoint, restored.latestCheckpoint().orElseThrow());
        // only the observations after the cutoff are needed
        var later = assertInstanceOf(RebuildResult.Rebuilt.class,
                rebuildFrom(live, checkpoint, List.of(second, third, correction), 60)).store();
        assertSameState(restored, later);
    }

    @Test
    void aStaleCheckpointIsRefused() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-3", "sub-a", 40, Kind.PENDING));
        var checkpoint = live.checkpoint(policy());
        // a late sample below the cutoff that moves no Node after a later Node change is recorded in place
        assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, sample("exp-2", "sub-b", 30, Kind.PENDING)));
        var ledger = live.ledger();
        assertEquals(new CompactionResult.Refused(CompactionResult.Reason.STALE), live.compact(checkpoint));
        assertEquals(ledger, live.ledger());
        assertEquals(OptionalLong.empty(), live.compactionHorizon());
    }

    @Test
    void compactionNeverMovesBackwards() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        var early = live.checkpoint(policy());
        deliverTo(live, sample("exp-2", "sub-a", 30, Kind.PASS));
        live.compact(live.checkpoint(policy()));
        assertEquals(new CompactionResult.Refused(CompactionResult.Reason.BEHIND_HORIZON), live.compact(early));
    }

    @Test
    void anEffectOnACohortTheCheckpointDoesNotHoldIsNeverCompacted() {
        var live = bound();
        deliverTo(live, sample("exp-b", "sub-b", 20, Kind.PASS));
        assertTrue(live.unbindDormant(KEY_B, AdaptationFixtures.only("sub-a")));
        var checkpoint = live.checkpoint(policy());
        assertEquals(new CompactionResult.Refused(CompactionResult.Reason.CREDITS_UNBOUND_COHORT),
                live.compact(checkpoint));
        assertEquals(1, live.ledger().size());
    }

    @Test
    void aCompactedEffectBlocksARebindUntilARebuildFromTheCheckpoint() {
        var live = bound();
        var b = sample("exp-b", "sub-b", 20, Kind.PASS);
        var a = sample("exp-a", "sub-a", 30, Kind.PASS);
        deliverTo(live, b);
        deliverTo(live, a);
        var checkpoint = live.checkpoint(policy());
        live.compact(checkpoint);
        assertTrue(live.unbindDormant(KEY_B, AdaptationFixtures.only("sub-a")));
        assertEquals(new BindResult.RebuildRequired(List.of(KEY_B)), live.bind(List.of(KEY_B)));

        var restored = assertInstanceOf(RebuildResult.Rebuilt.class, rebuildFrom(live, checkpoint, List.of(), 30))
                .store();
        assertSameState(scratch(List.of(b, a), 30), restored);
    }

    @Test
    void retryableMarkersSurviveCompactionAndARetryBelowTheHorizonNeedsAnEarlierStart() {
        var live = store();
        live.bind(List.of(KEY_A));
        var unbound = sample("exp-b", "sub-b", 20, Kind.PASS);
        var recorded = assertInstanceOf(StateUpdate.Recorded.class, deliverTo(live, unbound));
        assertEquals(NoUpdateReason.NO_TARGET_BINDING,
                ((EntryBasis.NoUpdateMarker) recorded.marker().basis()).reason());
        // a wrong pairing: the request claims another stage than the decision recorded
        var mismatch = sample("exp-m", "sub-a", 30, Kind.PASS);
        var review = request("scope-demo", "review", Feature.unknown(), "exec-exp-m", 101L);
        var wrong = live.consume(mismatch,
                feedbackPolicy().derive(mismatch, decisions.get("exp-m"), review, live.bindings()));
        assertInstanceOf(StateUpdate.Recorded.class, wrong);
        deliverTo(live, sample("exp-a", "sub-a", 40, Kind.PASS));
        var checkpoint = live.checkpoint(policy());
        assertEquals(2, checkpoint.retainedMarkers().size());

        assertEquals(new CompactionResult.Compacted(1, 40), live.compact(checkpoint));
        assertEquals(List.of("exp-b", "exp-m"), live.ledger().stream().map(AppliedRevision::experienceRef).toList());
        // binding sub-b in place would leave exp-b unapplied where a replay from before 20 would apply it
        assertEquals(new BindResult.RebuildRequired(List.of(KEY_B)), live.bind(List.of(KEY_B)));
        // the corrected pairing is a first application at 30, below the horizon
        assertEquals(new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.BEFORE_HORIZON, 40, 30),
                deliverTo(live, mismatch));
        // a repeat of the marker itself is still recognized
        assertInstanceOf(StateUpdate.Unchanged.class, deliverTo(live, unbound));
    }

    @Test
    void aCheckpointOfAnotherPartitionOrBeyondTheWatermarkIsRejected() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        var other = new RoutingStateStore(AdaptationFixtures.definition("adaptive-test/2"),
                AdaptationFixtures.PARTITION);
        assertEquals("the checkpoint belongs to another partition or definition", assertThrows(
                IllegalArgumentException.class, () -> other.compact(live.checkpoint(policy()))).getMessage());
        assertEquals("the checkpoint at 20 lies beyond processedCutoff 0", assertThrows(
                IllegalArgumentException.class, () -> bound().compact(live.checkpoint(policy()))).getMessage());
    }

    @Test
    void aStoreThatIsNeverCompactedDigestsItsLedger() {
        var live = bound();
        assertEquals(LedgerDigest.EMPTY, live.ledgerDigest());
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-2", "sub-b", 30, Kind.PENDING));
        assertEquals(LedgerDigest.of(live.ledger()), live.ledgerDigest());
        assertEquals(LedgerDigest.EMPTY, live.resetCopy().ledgerDigest());
    }

    /**
     * Long seeded fixture: corrections with short and long delays, checkpoints every four deliveries, compaction to the
     * checkpoint two before the newest, and every rebuild started from the latest valid checkpoint (or from scratch).
     * The final state, and a rebuild from every remaining checkpoint, equal a rebuild from scratch.
     */
    @Test
    void checkpointThenReplayEqualsARebuildFromScratchOverALongFixture() {
        var random = new Random(89);
        Kind[] kinds = Kind.values();
        List<RoutingObservation> schedule = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            var original = sample(String.format("s-%03d", i), random.nextBoolean() ? "sub-a" : "sub-b", 100 + 10L * i,
                    kinds[random.nextInt(kinds.length)]);
            schedule.add(original);
            if (i % 15 == 4) {
                schedule.add(corrected(original, original.ledgerSequence() + 35, kinds[random.nextInt(kinds.length)]));
            } else if (i % 23 == 11) {
                schedule.add(corrected(original, original.ledgerSequence() + 405, Kind.FAIL));
            }
        }
        schedule.sort(Comparator.comparingLong(RoutingObservation::ledgerSequence));

        var live = bound();
        List<RoutingObservation> delivered = new ArrayList<>();
        List<RoutingStateCheckpoint> checkpoints = new ArrayList<>();
        int fromCheckpoint = 0;
        int beyond = 0;
        int compactions = 0;
        for (RoutingObservation next : schedule) {
            delivered.add(next);
            StateUpdate update = deliverTo(live, next);
            OptionalLong affected = switch (update) {
                case StateUpdate.RebuildRequired rebuild -> OptionalLong.of(rebuild.advice().affectedFromSequence());
                case StateUpdate.BeyondCheckpoint out when out.kind() != StateUpdate.BeyondCheckpoint.Kind.COVERED -> {
                    beyond++;
                    yield OptionalLong.of(out.affectedFromSequence());
                }
                default -> OptionalLong.empty();
            };
            // contract section 7: a checkpoint is valid only below the earliest superseded revision
            if (next.supersedesSequence() instanceof Feature.Known<Long> known) {
                checkpoints.removeIf(c -> c.processedCutoff() >= known.value());
            }
            if (affected.isPresent()) {
                long from = affected.getAsLong();
                checkpoints.removeIf(c -> c.processedCutoff() >= from);
                long cutoff = Math.max(live.processedCutoff(), next.ledgerSequence());
                if (checkpoints.isEmpty()) {
                    live = scratch(delivered, cutoff);
                } else {
                    live = assertInstanceOf(RebuildResult.Rebuilt.class,
                            rebuildFrom(live, checkpoints.getLast(), delivered, cutoff)).store();
                    fromCheckpoint++;
                }
            }
            if (delivered.size() % 4 == 0) {
                checkpoints.add(live.checkpoint(policy()));
                if (checkpoints.size() >= 3) {
                    // a store restored from a newer checkpoint than this one is already compacted past it
                    var compaction = live.compact(checkpoints.get(checkpoints.size() - 3));
                    assertTrue(compaction instanceof CompactionResult.Compacted || compaction.equals(
                            new CompactionResult.Refused(CompactionResult.Reason.BEHIND_HORIZON)), compaction::toString);
                    compactions += compaction instanceof CompactionResult.Compacted done && done.removed() > 0 ? 1 : 0;
                }
            }
        }
        assertTrue(fromCheckpoint > 0, "no rebuild started from a checkpoint");
        assertTrue(beyond > 0, "no correction reached below the horizon");
        assertTrue(compactions > 0, "nothing was compacted");
        assertTrue(live.compactionHorizon().isPresent());
        assertTrue(live.ledger().size() < live.ledgerDigest().entries());

        var expected = scratch(delivered, live.processedCutoff());
        assertSameState(expected, live);
        for (RoutingStateCheckpoint checkpoint : checkpoints) {
            var restored = assertInstanceOf(RebuildResult.Rebuilt.class,
                    rebuildFrom(live, checkpoint, delivered, live.processedCutoff())).store();
            assertSameState(expected, restored);
        }
        assertNotEquals(bound().cohorts(demo()), live.cohorts(demo()));
    }
}
