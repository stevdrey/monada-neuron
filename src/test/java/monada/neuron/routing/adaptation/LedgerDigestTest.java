package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerDigestTest {

    /** A reward on sub-a at 20, a penalty on sub-b at 30 and a neutral marker-free pending outcome at 40. */
    private static List<AppliedRevision> ledger() {
        var store = store();
        store.bind(List.of(new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A),
                new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B)));
        Object[][] samples = {{"exp-1", "sub-a", 20L, Kind.PASS}, {"exp-2", "sub-b", 30L, Kind.FAIL},
                {"exp-3", "sub-a", 40L, Kind.PENDING}};
        for (Object[] sample : samples) {
            var request = request("exec-" + sample[0]);
            var decision = decide(request, (String) sample[1]);
            deliver(store, observation(request, decision, (String) sample[0], (Long) sample[2], (Kind) sample[3]),
                    decision, request);
        }
        return store.ledger();
    }

    @Test
    void theDigestIsPinned() {
        // golden values: any change to the encoding or to the reference fixtures must bump the format version
        var entries = ledger();
        assertEquals("7dbe837bcba18afec8bfdab91747314dcf09da99aa2f0fc5b81511a7d8820e52", LedgerDigest.of(entries).sum());
        assertEquals(3, LedgerDigest.of(entries).entries());
    }

    @Test
    void theEncodingStartsWithTheTagAndFollowsTheComponentOrder() {
        byte[] encoded = LedgerDigest.encode(ledger().getFirst());
        byte[] tag = LedgerDigest.ENTRY_TAG.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(tag, Arrays.copyOf(encoded, tag.length));
        // experienceRef "exp-1": 4-byte big-endian length 5, then its UTF-8
        assertEquals("00000005657870" + "2d31",
                HexFormat.of().formatHex(Arrays.copyOfRange(encoded, tag.length, tag.length + 9)));
    }

    @Test
    void theDigestIgnoresOrderAndCutoffButNotTheClaim() {
        var entries = ledger();
        assertEquals(LedgerDigest.of(entries), LedgerDigest.of(entries.reversed()));
        AppliedRevision first = entries.getFirst();
        var reread = new AppliedRevision(first.identity(), first.routingPolicyId(), first.routingPolicyVersion(),
                first.supersedesSequence(), first.catalogVersion(), first.cutoff() + 1000, first.targetNodeId(),
                first.costPeer(), first.basis(), first.disposition(), first.score());
        assertEquals(first, reread);
        assertEquals(LedgerDigest.of(List.of(first)), LedgerDigest.of(List.of(reread)));
        var rescored = new AppliedRevision(first.identity(), first.routingPolicyId(), first.routingPolicyVersion(),
                first.supersedesSequence(), first.catalogVersion(), first.cutoff(), first.targetNodeId(),
                first.costPeer(), first.basis(), first.disposition(), Math.nextUp(first.score()));
        assertNotEquals(LedgerDigest.of(List.of(first)), LedgerDigest.of(List.of(rescored)));
        var recatalogued = new AppliedRevision(first.identity(), first.routingPolicyId(),
                first.routingPolicyVersion(), first.supersedesSequence(), "cat-other", first.cutoff(),
                first.targetNodeId(), first.costPeer(), first.basis(), first.disposition(), first.score());
        assertNotEquals(LedgerDigest.of(List.of(first)), LedgerDigest.of(List.of(recatalogued)));
    }

    @Test
    void digestsFoldAndUnfold() {
        var entries = ledger();
        var head = LedgerDigest.of(entries.subList(0, 1));
        var tail = LedgerDigest.of(entries.subList(1, 3));
        assertEquals(LedgerDigest.of(entries), head.plus(tail));
        assertEquals(tail, LedgerDigest.of(entries).minus(head));
        assertEquals(LedgerDigest.EMPTY, LedgerDigest.of(List.of()));
        assertEquals(head, head.plus(LedgerDigest.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> head.minus(tail));
        assertThrows(IllegalArgumentException.class, () -> new LedgerDigest(1, "ABC"));
        assertThrows(IllegalArgumentException.class, () -> new LedgerDigest(-1, LedgerDigest.EMPTY.sum()));
    }
}
