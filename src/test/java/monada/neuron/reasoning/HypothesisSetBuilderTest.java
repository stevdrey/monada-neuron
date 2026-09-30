package monada.neuron.reasoning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisSetBuilderTest {

    private final HypothesisLimits limits = new HypothesisLimits(3, 2);

    @Test
    void emptyBuilderProducesEmptySet() {
        var set = new HypothesisSetBuilder(limits).build();
        assertAll(() -> assertTrue(set.isEmpty()), () -> assertEquals(0, set.size()));
    }

    @Test
    void assignsContiguousSequencesInProposalOrder() {
        var builder = new HypothesisSetBuilder(limits);
        var a = builder.propose(new Proposition(0, 10)).getAsInt();
        var b = builder.propose(new Proposition(0, 5)).getAsInt();
        var set = builder.build();
        assertAll(
                () -> assertEquals(0, a),
                () -> assertEquals(1, b),
                () -> assertEquals(new Proposition(0, 10), set.get(0).proposition()),
                () -> assertEquals(new Proposition(0, 5), set.get(1).proposition()));
    }

    @Test
    void equivalentPropositionsMergeEvidenceIntoOneCandidate() {
        var builder = new HypothesisSetBuilder(limits);
        var first = builder.propose(new Proposition(1, 1)).getAsInt();
        var second = builder.propose(new Proposition(1, 1)).getAsInt();
        builder.addEvidence(first, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.5));
        builder.addEvidence(second, new SignalEvidence(1, EvidenceRelation.CONTRADICTS, 0.25));
        var set = builder.build();
        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(1, set.size()),
                () -> assertEquals(2, set.get(0).evidence().size()));
    }

    @Test
    void preservesSupportContradictionAndNeutralEvidenceOrder() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(1, 3));
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        var support = new SignalEvidence(4, EvidenceRelation.SUPPORTS, 0.9);
        var contradiction = new MemoryReferenceEvidence(2, EvidenceRelation.CONTRADICTS, 0.4);
        var neutral = new ActionReferenceEvidence(9, EvidenceRelation.NEUTRAL, 0.1);
        builder.addEvidence(seq, support);
        builder.addEvidence(seq, contradiction);
        builder.addEvidence(seq, neutral);
        assertEquals(List.of(support, contradiction, neutral), builder.build().get(0).evidence());
    }

    @Test
    void reportsCapacityExhaustionWithoutThrowing() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(1, 1));
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        var accepted = builder.addEvidence(seq, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 1.0));
        var rejected = builder.addEvidence(seq, new SignalEvidence(1, EvidenceRelation.SUPPORTS, 1.0));
        assertAll(
                () -> assertTrue(accepted),
                () -> assertFalse(rejected),
                () -> assertTrue(builder.propose(new Proposition(0, 1)).isPresent()),
                () -> assertTrue(builder.propose(new Proposition(0, 2)).isEmpty()),
                () -> assertEquals(1, builder.build().get(0).evidence().size()));
    }

    @Test
    void rejectsEvidenceForUnknownSequence() {
        var builder = new HypothesisSetBuilder(limits);
        assertThrows(IllegalArgumentException.class,
                () -> builder.addEvidence(0, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 1.0)));
    }

    @Test
    void snapshotIsIndependentOfLaterBuilderMutation() {
        var builder = new HypothesisSetBuilder(limits);
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        var before = builder.build();
        builder.addEvidence(seq, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 1.0));
        builder.propose(new Proposition(0, 2));
        assertAll(
                () -> assertEquals(1, before.size()),
                () -> assertTrue(before.get(0).evidence().isEmpty()));
    }

    @Test
    void identicalInputsReplayToEqualSets() {
        assertEquals(randomSet(42), randomSet(42));
    }

    private HypothesisSet randomSet(long seed) {
        var random = new Random(seed);
        var builder = new HypothesisSetBuilder(new HypothesisLimits(8, 4));
        for (var i = 0; i < 40; i++) {
            var seq = builder.propose(new Proposition(0, random.nextInt(12)));
            if (seq.isPresent()) {
                var relation = EvidenceRelation.values()[random.nextInt(3)];
                builder.addEvidence(seq.getAsInt(), new SignalEvidence(random.nextInt(100), relation, 0.5));
            }
        }
        return builder.build();
    }
}
