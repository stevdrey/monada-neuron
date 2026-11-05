package monada.neuron.evaluation.bilingual;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The registered near-duplicate rule of {@code bilingual-sense/1}: two masked token sequences are near-duplicates when
 * they are equal, or when both have at least 3 tokens and the Jaccard similarity of their word 3-gram sets is at least
 * {@code 0.5}. Components are formed with union-find from two exact passes, so every pair meeting either branch is
 * found:
 *
 * <ul>
 *   <li>an equality pass, grouping sequences by content in a hash map;</li>
 *   <li>a Jaccard pass over an inverted index of 3-grams with prefix filtering (3-grams ordered rarest first by
 *       global frequency; a set {@code x} indexes its first {@code |x| - ceil(0.5 |x|) + 1} 3-grams), each candidate
 *       pair verified with the exact similarity.</li>
 * </ul>
 *
 * <p>The harness uses the rule for check {@code leakage.near-duplicate}; grouping the corpus itself is offline
 * tooling (protocol "Resource limits and ownership").
 */
final class NearDuplicateMerger {

    /** The registered Jaccard threshold. */
    static final double THRESHOLD = 0.5;

    /** Returns the component representative of every sequence (the smallest index of its component). */
    int[] components(List<List<String>> sequences) {
        var parent = new int[sequences.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        var byContent = new HashMap<List<String>, Integer>();
        for (int i = 0; i < sequences.size(); i++) {
            Integer first = byContent.putIfAbsent(sequences.get(i), i);
            if (first != null) {
                union(parent, first, i);
            }
        }
        jaccardPass(sequences, parent);
        var roots = new int[parent.length];
        for (int i = 0; i < parent.length; i++) {
            roots[i] = find(parent, i);
        }
        return roots;
    }

    /** Returns whether two sequences meet the near-duplicate rule, by direct computation (the reference). */
    boolean nearDuplicate(List<String> a, List<String> b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.size() < 3 || b.size() < 3) {
            return false;
        }
        var interner = new HashMap<List<String>, Integer>();
        int[] x = grams(a, interner);
        int[] y = grams(b, interner);
        Arrays.sort(x);
        Arrays.sort(y);
        return jaccard(x, y) >= THRESHOLD;
    }

    private void jaccardPass(List<List<String>> sequences, int[] parent) {
        var interner = new HashMap<List<String>, Integer>();
        var sets = new int[sequences.size()][];
        for (int i = 0; i < sequences.size(); i++) {
            sets[i] = sequences.get(i).size() >= 3 ? grams(sequences.get(i), interner) : null;
        }
        var frequency = new int[interner.size()];
        for (int[] set : sets) {
            if (set != null) {
                for (int g : set) {
                    frequency[g]++;
                }
            }
        }
        // Rank 3-grams rarest first, ties by intern id, and rewrite every set as sorted ranks.
        Integer[] byRarity = new Integer[frequency.length];
        for (int g = 0; g < byRarity.length; g++) {
            byRarity[g] = g;
        }
        Arrays.sort(byRarity, (p, q) -> frequency[p] != frequency[q]
                ? Integer.compare(frequency[p], frequency[q]) : Integer.compare(p, q));
        var rank = new int[frequency.length];
        for (int r = 0; r < byRarity.length; r++) {
            rank[byRarity[r]] = r;
        }
        Map<Integer, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < sets.length; i++) {
            if (sets[i] == null) {
                continue;
            }
            int[] ranked = sets[i];
            for (int k = 0; k < ranked.length; k++) {
                ranked[k] = rank[ranked[k]];
            }
            Arrays.sort(ranked);
            int prefix = ranked.length - (int) Math.ceil(THRESHOLD * ranked.length) + 1;
            for (int k = 0; k < prefix; k++) {
                List<Integer> postings = index.computeIfAbsent(ranked[k], _ -> new ArrayList<>());
                for (int other : postings) {
                    if (find(parent, other) != find(parent, i) && jaccard(sets[other], ranked) >= THRESHOLD) {
                        union(parent, other, i);
                    }
                }
                postings.add(i);
            }
        }
    }

    /** Returns the distinct word 3-grams of a sequence as intern ids (unsorted). */
    private int[] grams(List<String> sequence, Map<List<String>, Integer> interner) {
        var ids = new ArrayList<Integer>();
        var seen = new HashSet<Integer>();
        for (int i = 0; i + 3 <= sequence.size(); i++) {
            int id = interner.computeIfAbsent(List.copyOf(sequence.subList(i, i + 3)), _ -> interner.size());
            if (seen.add(id)) {
                ids.add(id);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Exact Jaccard similarity of two sorted, duplicate-free arrays. */
    private double jaccard(int[] x, int[] y) {
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) {
                common++;
                i++;
                j++;
            } else if (x[i] < y[j]) {
                i++;
            } else {
                j++;
            }
        }
        int union = x.length + y.length - common;
        return union == 0 ? 0.0 : (double) common / union;
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Unites two components, keeping the smaller index as the representative. */
    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
