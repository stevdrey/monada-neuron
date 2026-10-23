package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.SignalHitCapture.Hit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Offline merge rules of the B2 recall diagnosis (Issue #135), replayed over the captured per-Signal Store lists of
 * {@link SignalHitCapture}, so every rule sees exactly the same candidates. {@link Rule#MAX_SCORE} is the production
 * adapter's rule and must reproduce B2 bit for bit; every other rule is a research-only, in-sample exploratory
 * alternative that is never substituted for B2.
 *
 * <p>Every rule keeps one candidate per atom (deduplication by opaque reference), orders by its aggregate value
 * descending, then by the atom's best hit under the production order (raw score descending, Signal index, Store rank),
 * and cuts to the limit. Cost: {@code O(H log H)} for {@code H} captured hits.
 */
final class MergeCounterfactuals {

    /** The constant of reciprocal rank fusion. */
    static final int RRF_CONSTANT = 60;

    /** A merge rule. */
    enum Rule {
        /** Production: the best raw score of the atom over all Signals. */
        MAX_SCORE("max-score", "production: best raw Store score over the Signals (B2)"),
        /** Consensus: the sum of the atom's raw scores over the Signals that retrieved it. */
        SUM_SCORE("sum-score", "research-only: sum of raw scores over the Signals that retrieved the atom"),
        /** Coverage: the number of Signals that retrieved the atom, then its best raw score. */
        TOKEN_COUNT("token-count", "research-only: number of Signals that retrieved the atom, then best raw score"),
        /** Rank fusion: {@code Σ 1 / (60 + rank)} over the Signals that retrieved the atom (1-based rank). */
        RRF("rrf", "research-only: reciprocal rank fusion, sum of 1/(" + RRF_CONSTANT + " + rank) over Signals"),
        /** Per-token normalization: the best raw score divided by the top score of the same Signal's list. */
        NORMALIZED_MAX("normalized-max", "research-only: best of score / top score of the same Signal's list");

        private final String label;
        private final String description;

        Rule(String label, String description) {
            this.label = label;
            this.description = description;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }

        /** Returns the report description. */
        String description() {
            return description;
        }
    }

    /**
     * One merged candidate.
     *
     * @param chunkId the mapped chunk
     * @param reference the opaque reference, the deduplication key
     * @param value the rule's aggregate value
     * @param best the atom's best hit under the production order
     * @param signals the number of distinct Signals that retrieved the atom
     */
    record Merged(String chunkId, String reference, double value, Hit best, int signals) {
    }

    /** The production order of hits: raw score descending, then Signal index, then Store rank. */
    static final Comparator<Hit> HIT_ORDER = Comparator.comparingDouble(Hit::score).reversed()
            .thenComparingInt(Hit::signalIndex).thenComparingInt(Hit::rank);

    private static final Comparator<Merged> MERGED_ORDER = Comparator.comparingDouble(Merged::value).reversed()
            .thenComparing(Merged::best, HIT_ORDER);

    private MergeCounterfactuals() {
    }

    /** Merges {@code perSignal} under {@code rule} and cuts to {@code limit}. */
    static List<Merged> merge(Rule rule, List<List<Hit>> perSignal, int limit) {
        Objects.requireNonNull(rule, "rule must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return candidates(rule, perSignal).stream().sorted(MERGED_ORDER).limit(limit).toList();
    }

    /** Returns every deduplicated candidate of {@code perSignal} under {@code rule}, unordered (first-seen order). */
    static List<Merged> candidates(Rule rule, List<List<Hit>> perSignal) {
        var accumulators = new LinkedHashMap<String, Accumulator>();
        for (var hits : perSignal) {
            var top = hits.isEmpty() ? 0.0 : hits.getFirst().score();
            for (var hit : hits) {
                accumulators.computeIfAbsent(hit.reference(), reference -> new Accumulator(hit)).add(hit, top);
            }
        }
        var merged = new ArrayList<Merged>(accumulators.size());
        accumulators.forEach((reference, accumulator) -> merged.add(new Merged(accumulator.best.chunkId(),
                reference, accumulator.value(rule), accumulator.best, accumulator.signals.size())));
        return merged;
    }

    /** Returns the number of captured hits before deduplication. */
    static int hitCount(List<List<Hit>> perSignal) {
        return perSignal.stream().mapToInt(List::size).sum();
    }

    /** Running aggregates of one atom. */
    private static final class Accumulator {

        private Hit best;
        private double sum;
        private double rrf;
        private double normalizedMax = Double.NEGATIVE_INFINITY;
        private final HashSet<Integer> signals = new HashSet<>();

        Accumulator(Hit first) {
            this.best = first;
        }

        void add(Hit hit, double top) {
            if (HIT_ORDER.compare(hit, best) < 0) {
                best = hit;
            }
            sum += hit.score();
            rrf += 1.0 / (RRF_CONSTANT + hit.rank() + 1);
            // A list whose top score is not positive carries no relative strength; it contributes 0.
            normalizedMax = Math.max(normalizedMax, top > 0.0 ? hit.score() / top : 0.0);
            signals.add(hit.signalIndex());
        }

        double value(Rule rule) {
            return switch (rule) {
                case MAX_SCORE -> best.score();
                case SUM_SCORE -> sum;
                case TOKEN_COUNT -> signals.size();
                case RRF -> rrf;
                case NORMALIZED_MAX -> normalizedMax;
            };
        }
    }
}
