package monada.neuron.evaluation.knowledge;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Merged;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Rule;
import monada.neuron.evaluation.knowledge.SignalHitCapture.Hit;
import monada.neuron.resonance.adapter.OpaqueReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MergeCounterfactualsTest {

    private static final OpaqueReference REFERENCES = new OpaqueReference();

    /** Builds per-Signal hit lists from {@code atom:score} pairs; the chunk id is the atom id. */
    private static List<List<Hit>> lists(String[]... perSignal) {
        var lists = new ArrayList<List<Hit>>();
        for (var signal = 0; signal < perSignal.length; signal++) {
            var hits = new ArrayList<Hit>();
            for (var rank = 0; rank < perSignal[signal].length; rank++) {
                var cells = perSignal[signal][rank].split(":");
                hits.add(new Hit(signal, rank, cells[0], REFERENCES.of(cells[0]), Double.parseDouble(cells[1])));
            }
            lists.add(hits);
        }
        return lists;
    }

    private static List<String> ranked(List<Merged> merged) {
        return merged.stream().map(item -> item.chunkId() + " " + Double.doubleToLongBits(item.value()) + " "
                + item.best()).toList();
    }

    private static List<String> chunks(List<Merged> merged) {
        return merged.stream().map(Merged::chunkId).toList();
    }

    @Test
    void maxScoreReproducesTheAdapterMergeOfTheControl() {
        var lists = lists(new String[] {"a:0.9", "b:0.5", "c:0.5"}, new String[] {"c:0.7", "d:0.5", "a:0.2"},
                new String[] {"e:0.5"});
        var perSignal = new ArrayList<List<ResonanceResult>>();
        for (var hits : lists) {
            perSignal.add(hits.stream().map(hit -> new ResonanceResult(new KnowledgeAtom(hit.chunkId(),
                    AtomType.values()[0], hit.chunkId(), Map.of(), 1.0, Instant.EPOCH), hit.score())).toList());
        }
        for (var limit = 1; limit <= 6; limit++) {
            var control = HtmlKnowledgeRecall.mergeCodecResults(perSignal, limit);
            var replay = MergeCounterfactuals.merge(Rule.MAX_SCORE, lists, limit);
            assertEquals(control.stream().map(result -> result.atom().id()).toList(), chunks(replay), "limit " + limit);
            for (var i = 0; i < replay.size(); i++) {
                assertEquals(Double.doubleToLongBits(control.get(i).score()),
                        Double.doubleToLongBits(replay.get(i).value()), "bit-exact score at rank " + (i + 1));
            }
        }
    }

    @Test
    void deduplicationKeepsTheBestHitPerAtomAndCountsDistinctSignals() {
        var lists = lists(new String[] {"a:0.4", "b:0.3"}, new String[] {"a:0.8"}, new String[] {"a:0.8"});
        var merged = MergeCounterfactuals.merge(Rule.MAX_SCORE, lists, 5);

        assertEquals(List.of("a", "b"), chunks(merged));
        assertEquals(1, merged.getFirst().best().signalIndex(), "an equal score keeps the earlier Signal");
        assertEquals(3, merged.getFirst().signals());
        assertEquals(4, MergeCounterfactuals.hitCount(lists), "hits before deduplication");
        assertEquals(2, MergeCounterfactuals.candidates(Rule.MAX_SCORE, lists).size(), "atoms after deduplication");
    }

    @Test
    void tiesBreakBySignalIndexThenStoreRank() {
        var lists = lists(new String[] {"x:0.5", "y:0.5"}, new String[] {"z:0.5"});

        assertEquals(List.of("x", "y", "z"), chunks(MergeCounterfactuals.merge(Rule.MAX_SCORE, lists, 3)));
        assertEquals(List.of("x"), chunks(MergeCounterfactuals.merge(Rule.MAX_SCORE, lists, 1)));
    }

    @Test
    void everyAlternativeAggregatesTheSameCandidates() {
        // a: one strong hit; b: three moderate hits across Signals.
        var lists = lists(new String[] {"a:0.9", "b:0.4"}, new String[] {"b:0.4", "c:0.2"}, new String[] {"b:0.4"});

        assertEquals(List.of("a", "b", "c"), chunks(MergeCounterfactuals.merge(Rule.MAX_SCORE, lists, 3)));

        var sum = MergeCounterfactuals.merge(Rule.SUM_SCORE, lists, 3);
        assertEquals(List.of("b", "a", "c"), chunks(sum));
        assertEquals(1.2, sum.getFirst().value(), 1e-12);

        var count = MergeCounterfactuals.merge(Rule.TOKEN_COUNT, lists, 3);
        assertEquals(List.of("b", "a", "c"), chunks(count), "a count tie falls back to the best raw score");
        assertEquals(3.0, count.getFirst().value());

        var rrf = MergeCounterfactuals.merge(Rule.RRF, lists, 3);
        assertEquals(List.of("b", "a", "c"), chunks(rrf));
        assertEquals(1.0 / 62 + 1.0 / 61 + 1.0 / 61, rrf.getFirst().value(), 1e-12);

        var normalized = MergeCounterfactuals.merge(Rule.NORMALIZED_MAX, lists, 3);
        // b tops the second and third lists, so its normalized best is 1.0, equal to a's; a's raw score wins the tie.
        assertEquals(List.of("a", "b", "c"), chunks(normalized));
        assertEquals(1.0, normalized.get(1).value());
        assertEquals(0.5, normalized.get(2).value(), 1e-12);
    }

    @Test
    void aListWithoutAPositiveTopScoreContributesZeroToTheNormalizedRule() {
        var lists = lists(new String[] {"a:0.0", "b:0.0"});
        var merged = MergeCounterfactuals.merge(Rule.NORMALIZED_MAX, lists, 2);

        assertEquals(List.of("a", "b"), chunks(merged));
        assertEquals(0.0, merged.getFirst().value());
    }

    @Test
    void maxScoreIsInvariantToThePoolSizeEvenWithTies() {
        // Few distinct scores and shared atoms force ties at every cutoff and across Signals.
        var random = new Random(135);
        var scores = new double[] {0.5, 0.3, 0.3, 0.0};
        for (var trial = 0; trial < 200; trial++) {
            var full = new ArrayList<List<Hit>>();
            var signals = 1 + random.nextInt(4);
            for (var signal = 0; signal < signals; signal++) {
                var atoms = new ArrayList<>(List.of("a", "b", "c", "d", "e", "f", "g"));
                Collections.shuffle(atoms, random);
                var length = random.nextInt(atoms.size() + 1);
                var drawn = new ArrayList<Double>();
                for (var i = 0; i < length; i++) {
                    drawn.add(scores[random.nextInt(scores.length)]);
                }
                drawn.sort(Comparator.reverseOrder());
                var hits = new ArrayList<Hit>();
                for (var rank = 0; rank < length; rank++) {
                    hits.add(new Hit(signal, rank, atoms.get(rank), REFERENCES.of(atoms.get(rank)), drawn.get(rank)));
                }
                full.add(hits);
            }
            for (var k = 1; k <= 7; k++) {
                var pool = k;
                var prefixes = full.stream().map(hits -> hits.subList(0, Math.min(pool, hits.size()))).toList();
                // Atoms, order, values and best hits must agree; only the count of Signals seeing an atom may grow.
                assertEquals(ranked(MergeCounterfactuals.merge(Rule.MAX_SCORE, full, k)),
                        ranked(MergeCounterfactuals.merge(Rule.MAX_SCORE, prefixes, k)), "trial " + trial + " k " + k);
            }
        }
    }

    @Test
    void emptyListsMergeToNothingAndTheLimitMustBePositive() {
        assertEquals(List.of(), MergeCounterfactuals.merge(Rule.SUM_SCORE, List.of(List.of(), List.of()), 5));
        assertThrows(IllegalArgumentException.class, () -> MergeCounterfactuals.merge(Rule.MAX_SCORE, List.of(), 0));
    }
}
