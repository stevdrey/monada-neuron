package monada.neuron.evaluation.bilingual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearDuplicateMergerTest {

    private final NearDuplicateMerger merger = new NearDuplicateMerger();
    private final SenseTokenizer tokenizer = new SenseTokenizer();

    @Test
    void equalityPassMergesShortSequencesWithoutAnyThreeGram() {
        List<String> bank = tokenizer.masked(tokenizer.tokenize("Bank!"), 0);
        List<String> banks = tokenizer.masked(tokenizer.tokenize("Banks?"), 0);
        int[] components = merger.components(List.of(bank, List.of("x", "y"), banks));
        assertEquals(components[0], components[2]);
        assertNotEquals(components[0], components[1]);
    }

    @Test
    void jaccardThresholdIsInclusiveAtOneHalf() {
        // 3-grams {abc, bcd, cde} vs {abc, bcd, cdf}: 2 shared of 4 = 0.5 -> near-duplicates.
        assertTrue(merger.nearDuplicate(List.of("a", "b", "c", "d", "e"), List.of("a", "b", "c", "d", "f")));
        // {abc, bcd, cde, def} vs {abc, bcd, cdx, dxy}: 2 shared of 6 = 0.33.
        assertFalse(merger.nearDuplicate(List.of("a", "b", "c", "d", "e", "f"),
                List.of("a", "b", "c", "d", "x", "y")));
        assertFalse(merger.nearDuplicate(List.of("a", "b"), List.of("a", "c")));
    }

    @Test
    void prefixFilteredPassFindsEveryPairThatBruteForceFinds() {
        var random = new SplittableRandom(185);
        var sequences = new ArrayList<List<String>>();
        for (int i = 0; i < 300; i++) {
            var sequence = new ArrayList<String>();
            int length = 1 + random.nextInt(7);
            for (int t = 0; t < length; t++) {
                sequence.add("w" + random.nextInt(4));
            }
            sequences.add(sequence);
        }
        int[] components = merger.components(sequences);
        // Brute force over every pair with union-find gives the reference components.
        int[] parent = new int[sequences.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < sequences.size(); i++) {
            for (int j = i + 1; j < sequences.size(); j++) {
                if (merger.nearDuplicate(sequences.get(i), sequences.get(j))) {
                    int ri = find(parent, i);
                    int rj = find(parent, j);
                    parent[Math.max(ri, rj)] = Math.min(ri, rj);
                }
            }
        }
        for (int i = 0; i < sequences.size(); i++) {
            assertEquals(find(parent, i), components[i], "component of sequence " + i);
        }
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            i = parent[i];
        }
        return i;
    }
}
