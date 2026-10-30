package monada.neuron.routing.adaptation;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.codec.CheckpointCodec;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.adaptation.codec.ReferenceCheckpointWriter;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.demo;
import static monada.neuron.routing.adaptation.AdaptationFixtures.feedbackPolicy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.only;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The codec against real stores: a dormant cohort's golden vector, and a rebuild from decoded bytes. */
class CheckpointCodecRestoreTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    /** {@code routing-checkpoint/1} of {@link #dormant()}, pinned from {@link ReferenceCheckpointWriter}. */
    static final String DORMANT_HEX =
            "00000014726f7574696e672d636865636b706f696e742f310000000a73636f70652d64656d6f0000000f7461736b2d66"
            + "656174757265732f310000000b666f7267652d676174657300000001310000000f61646170746976652d746573742f31"
            + "00000009726f7574652d6c65780000000131000000000000001e000000020000000a73636f70652d64656d6f00000009"
            + "696d706c656d656e7400000007554e4b4e4f574e000000057375622d6100000000000000014016666666666666404900"
            + "00000000003fe0000000000000402599999999999a000000010000000a73636f70652d64656d6f00000009696d706c65"
            + "6d656e7400000007554e4b4e4f574e000000057375622d620000000000000001400999999999999a4049000000000000"
            + "3fe00000000000004023333333333333000000010000000000000002b24100dbdfb5cd2d1d0ac72077fba9d5a8848eb7"
            + "498d4ef0c34143cb4c6a717600000000f30b9138c7bff2f38af18483110ea94860ff48048819835cc8f32f0901641c11";

    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    private RoutingObservation sample(String ref, String routeId, long sequence, Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, sequence, kind);
    }

    private void deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private RoutingStateStore rebuildFrom(RoutingStateCheckpoint checkpoint, List<RoutingObservation> observations,
            long cutoff) {
        var result = store().rebuildFrom(checkpoint, observations, cutoff, (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
        return assertInstanceOf(RebuildResult.Rebuilt.class, result).store();
    }

    private static RoutingStateCheckpoint confirmedCheckpoint(RoutingStateStore store) {
        store.advanceCutoff(store.processedCutoff());
        return store.checkpoint(policy());
    }

    private static RoutingStateCheckpoint decoded(RoutingStateCheckpoint checkpoint) {
        return assertInstanceOf(CheckpointDecodeResult.Decoded.class,
                CheckpointCodec.decode(CheckpointCodec.encode(checkpoint))).checkpoint();
    }

    /** Both routes learned, then sub-b left the catalog: its cohort is dormant and still in the checkpoint. */
    private RoutingStateCheckpoint dormant() {
        var live = store();
        live.bind(List.of(KEY_A, KEY_B));
        deliverTo(live, sample("exp-a", "sub-a", 20, Kind.PASS));
        deliverTo(live, sample("exp-b", "sub-b", 30, Kind.FAIL));
        // dormant against a catalog without sub-b; unbindDormant would remove the cohort, so it is not called
        assertEquals(CohortStatus.DORMANT, live.cohorts(only("sub-a")).stream()
                .filter(cohort -> cohort.key().equals(KEY_B)).findFirst().orElseThrow().status());
        return confirmedCheckpoint(live);
    }

    @Test
    void aDormantCohortIsWrittenAndRestoredLikeAnyOther() {
        var checkpoint = dormant();
        assertEquals(List.of(KEY_A, KEY_B),
                checkpoint.preferenceSnapshot().stream().map(CheckpointCohort::key).toList());
        byte[] encoded = CheckpointCodec.encode(checkpoint);
        assertArrayEquals(ReferenceCheckpointWriter.write(checkpoint), encoded);
        assertEquals(DORMANT_HEX, HexFormat.of().formatHex(encoded));
        assertEquals(checkpoint, decoded(checkpoint));
    }

    @Test
    void aRebuildFromDecodedBytesMatchesARebuildFromTheOriginal() {
        var live = store();
        live.bind(List.of(KEY_A));
        // sub-b has no bound Node, so exp-b becomes a retained retryable marker
        deliverTo(live, sample("exp-b", "sub-b", 20, Kind.PASS));
        deliverTo(live, sample("exp-a", "sub-a", 40, Kind.PASS));
        var original = confirmedCheckpoint(live);
        assertEquals(1, original.retainedMarkers().size());
        var copy = decoded(original);
        assertEquals(original.processedCutoff(), copy.processedCutoff());
        assertEquals(original.ledgerDigest(), copy.ledgerDigest());

        var later = List.of(sample("exp-c", "sub-a", 50, Kind.FAIL), sample("exp-d", "sub-a", 55, Kind.PASS));
        var expected = rebuildFrom(original, later, 60);
        var actual = rebuildFrom(copy, later, 60);
        assertEquals(expected.snapshot(policy(), demo()), actual.snapshot(policy(), demo()));
        assertEquals(expected.ledgerDigest(), actual.ledgerDigest());
        assertEquals(expected.processedCutoff(), actual.processedCutoff());
        assertEquals(expected.ledger(), actual.ledger());
        assertEquals(expected.ledger().stream().map(AppliedRevision::cutoff).toList(),
                actual.ledger().stream().map(AppliedRevision::cutoff).toList());
        assertArrayEquals(CheckpointCodec.encode(confirmedCheckpoint(expected)),
                CheckpointCodec.encode(confirmedCheckpoint(actual)));
    }
}
