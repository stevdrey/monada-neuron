package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The scorer of the judged recall experiment (protocol sections "Retrieval metrics (L1 and L2)"): the only component
 * that reads gold labels. It scores a {@link Snapshot} recorded by {@link HtmlKnowledgeRecall}.
 *
 * <p>{@code Ret_k(q)} is the prefix of length {@code k} of the limit-5 list in returned order. A per-query ratio with
 * a zero denominator is undefined ({@link OptionalDouble#empty()}, JSON {@code null}); an aggregate is the macro mean
 * of the defined values with {@code nDefined} and {@code nUndefined}, undefined when nothing is defined. A query whose
 * layer failed operationally gets no outcome for that layer.
 */
final class RecallMetrics {

    /** Evaluated prefix lengths. */
    static final List<Integer> KS = List.of(1, 3, 5);

    private RecallMetrics() {
    }

    /** Per-query outcome; the first applicable value in declaration order of its population wins. */
    enum Outcome {
        /** {@code A}: every required chunk retrieved. */
        HIT,
        /** {@code A}: some relevant chunk retrieved. */
        PARTIAL,
        /** {@code A}: results, none relevant. */
        MISS_WITH_RESULTS,
        /** {@code A}: no result at all. */
        NO_EVIDENCE,
        /** {@code U}: no result. */
        CORRECT_ABSTENTION,
        /** {@code U}: some result. */
        FALSE_EVIDENCE
    }

    /** A macro mean with its defined and undefined counts. */
    record Aggregate(OptionalDouble value, int nDefined, int nUndefined) {
    }

    /** One returned chunk of a limit-5 list. */
    record Ranked(String chunkId, double score) {
    }

    /**
     * Scores of one query at one {@code k}.
     *
     * @param outcome the per-query outcome
     * @param tieAtCutoff whether the score at rank {@code k} equals the score at rank {@code k + 1}
     * @param metrics per-query values by metric name, in report order; empty values are undefined
     */
    record KScore(Outcome outcome, boolean tieAtCutoff, Map<String, OptionalDouble> metrics) {
    }

    /**
     * One query's result through one layer.
     *
     * @param query the judged query
     * @param layer the system
     * @param returned the limit-5 list, or empty when the layer failed for this query
     * @param byK scores by {@code k}; empty when the layer failed
     */
    record QueryScore(JudgedQuery query, Layer layer, Optional<List<Ranked>> returned, Map<Integer, KScore> byK) {

        /** Returns whether the layer completed for this query. */
        boolean completed() {
            return returned.isPresent();
        }
    }

    /**
     * Aggregates of one layer at one {@code k}.
     *
     * @param answerable aggregates over {@code A} by metric name, in report order
     * @param fullEvidence {@code FullEvidence@k} over {@code multi-evidence} queries
     * @param noAnswerFalsePositiveRate fraction of {@code U} with a non-empty {@code Ret_k}
     * @param outcomes outcome counts by category
     * @param scoredA scored queries with a non-empty relevant set
     * @param scoredU scored queries with an empty relevant set
     */
    record LayerAggregate(Layer layer, int k, Map<String, Aggregate> answerable, Aggregate fullEvidence,
            OptionalDouble noAnswerFalsePositiveRate, Map<Category, Map<Outcome, Integer>> outcomes, int scoredA,
            int scoredU) {
    }

    /**
     * Set-level abstention metrics of one layer, over the full limit-5 list {@code Ret(q)}.
     *
     * @param abstentionPrecision {@code |{q ∈ U : Ret(q) = ∅}| / |{q : Ret(q) = ∅}|}
     * @param abstentionRecall {@code |{q ∈ U : Ret(q) = ∅}| / |U|}
     * @param unscored queries without an outcome because the layer failed
     */
    record LayerAbstention(Layer layer, OptionalDouble abstentionPrecision, OptionalDouble abstentionRecall,
            int unscored) {
    }

    /** Everything scored from one snapshot. */
    record Scores(List<QueryScore> queries, List<LayerAggregate> aggregates, List<LayerAbstention> abstentions) {

        Scores {
            queries = List.copyOf(queries);
            aggregates = List.copyOf(aggregates);
            abstentions = List.copyOf(abstentions);
        }
    }

    /** Scores every query of {@code querySet} through every layer recorded in {@code snapshot}. */
    static Scores score(HtmlKnowledgeQuerySet querySet, Snapshot snapshot) {
        Objects.requireNonNull(querySet, "querySet must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!snapshot.queryHash().equals(querySet.queryHash())) {
            throw new IllegalArgumentException("snapshot was recorded for queryHash " + snapshot.queryHash()
                    + ", not " + querySet.queryHash());
        }
        var queries = new ArrayList<QueryScore>();
        var aggregates = new ArrayList<LayerAggregate>();
        var abstentions = new ArrayList<LayerAbstention>();
        for (var layer : Layer.values()) {
            var layerScores = new ArrayList<QueryScore>();
            for (var query : querySet.queries()) {
                layerScores.add(layerScore(query, layer,
                        snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT)));
            }
            queries.addAll(layerScores);
            for (var k : KS) {
                aggregates.add(aggregate(layer, k, layerScores));
            }
            abstentions.add(abstention(layer, layerScores));
        }
        return new Scores(queries, aggregates, abstentions);
    }

    /** Returns the score of one query through one layer; a failure marker row leaves it without an outcome. */
    private static QueryScore layerScore(JudgedQuery query, Layer layer, List<Row> rows) {
        if (rows.stream().anyMatch(row -> row.chunkId().equals(HtmlKnowledgeRecall.FAILED))) {
            return new QueryScore(query, layer, Optional.empty(), Map.of());
        }
        var returned = rows.stream().map(row -> new Ranked(row.chunkId(), row.score())).toList();
        var chunkIds = returned.stream().map(Ranked::chunkId).toList();
        var scores = returned.stream().map(Ranked::score).toList();
        var byK = new LinkedHashMap<Integer, KScore>();
        for (var k : KS) {
            var prefix = chunkIds.subList(0, Math.min(k, chunkIds.size()));
            byK.put(k, new KScore(outcome(query.required(), query.relevant(), prefix), tieAtCutoff(scores, k),
                    perQuery(query, prefix, k)));
        }
        return new QueryScore(query, layer, Optional.of(returned), Map.copyOf(byK));
    }

    /** Returns the per-query metrics of an answerable query, or of nothing for {@code U}. */
    private static Map<String, OptionalDouble> perQuery(JudgedQuery query, List<String> retK, int k) {
        var metrics = new LinkedHashMap<String, OptionalDouble>();
        if (query.relevant().isEmpty()) {
            return metrics;
        }
        var relevant = query.relevant();
        metrics.put("Recall@" + k, recall(relevant, retK));
        metrics.put("Precision@" + k, precision(relevant, retK, k));
        metrics.put("HitPrecision@" + k, hitPrecision(relevant, retK));
        metrics.put("MRR@" + k, reciprocalRank(relevant, retK));
        metrics.put("Coverage@" + k, coverage(relevant, retK));
        if (query.category() == Category.MULTI_EVIDENCE) {
            metrics.put("FullEvidence@" + k, fullEvidence(query.required(), retK));
        }
        return metrics;
    }

    /** {@code |Rel ∩ Ret_k| / |Rel|}; undefined for an empty relevant set. */
    static OptionalDouble recall(Set<String> relevant, List<String> retK) {
        return ratio(relevantRetrieved(relevant, retK), relevant.size());
    }

    /** {@code |Rel ∩ Ret_k| / k}: the fixed denominator does not reward returning fewer results. */
    static OptionalDouble precision(Set<String> relevant, List<String> retK, int k) {
        return ratio(relevantRetrieved(relevant, retK), k);
    }

    /** {@code |Rel ∩ Ret_k| / |Ret_k|}; undefined when nothing was returned. */
    static OptionalDouble hitPrecision(Set<String> relevant, List<String> retK) {
        return ratio(relevantRetrieved(relevant, retK), new LinkedHashSet<>(retK).size());
    }

    /** {@code 1 / rank} of the first relevant result within {@code Ret_k}, {@code 0} if none. */
    static OptionalDouble reciprocalRank(Set<String> relevant, List<String> retK) {
        for (var i = 0; i < retK.size(); i++) {
            if (relevant.contains(retK.get(i))) {
                return OptionalDouble.of(1.0 / (i + 1));
            }
        }
        return OptionalDouble.of(0.0);
    }

    /** {@code 1} when some relevant chunk is in {@code Ret_k}, else {@code 0}. */
    static OptionalDouble coverage(Set<String> relevant, List<String> retK) {
        return OptionalDouble.of(relevantRetrieved(relevant, retK) > 0 ? 1.0 : 0.0);
    }

    /** {@code 1} when every required chunk is in {@code Ret_k}, else {@code 0}. */
    static OptionalDouble fullEvidence(Set<String> required, List<String> retK) {
        return OptionalDouble.of(retK.containsAll(required) ? 1.0 : 0.0);
    }

    /** Returns the outcome of one query at one {@code k}. */
    static Outcome outcome(Set<String> required, Set<String> relevant, List<String> retK) {
        if (relevant.isEmpty()) {
            return retK.isEmpty() ? Outcome.CORRECT_ABSTENTION : Outcome.FALSE_EVIDENCE;
        }
        if (retK.containsAll(required)) {
            return Outcome.HIT;
        }
        if (relevantRetrieved(relevant, retK) > 0) {
            return Outcome.PARTIAL;
        }
        return retK.isEmpty() ? Outcome.NO_EVIDENCE : Outcome.MISS_WITH_RESULTS;
    }

    /** Whether the tie group at rank {@code k} straddles the cutoff: the scores at ranks {@code k} and {@code k+1} are equal. */
    static boolean tieAtCutoff(List<Double> scores, int k) {
        return scores.size() > k && scores.get(k - 1).doubleValue() == scores.get(k).doubleValue();
    }

    /** Returns the macro mean of the defined {@code values}. */
    static Aggregate mean(List<OptionalDouble> values) {
        var sum = 0.0;
        var defined = 0;
        for (var value : values) {
            if (value.isPresent()) {
                sum += value.getAsDouble();
                defined++;
            }
        }
        return new Aggregate(defined == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / defined), defined,
                values.size() - defined);
    }

    /** Returns {@code numerator / denominator}, undefined when the denominator is {@code 0}. */
    static OptionalDouble ratio(int numerator, int denominator) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }

    private static int relevantRetrieved(Set<String> relevant, List<String> retK) {
        return (int) new LinkedHashSet<>(retK).stream().filter(relevant::contains).count();
    }

    private static LayerAggregate aggregate(Layer layer, int k, List<QueryScore> scores) {
        var scored = scores.stream().filter(QueryScore::completed).toList();
        var answerable = scored.stream().filter(score -> !score.query().relevant().isEmpty()).toList();
        var unanswerable = scored.stream().filter(score -> score.query().relevant().isEmpty()).toList();
        var metrics = new LinkedHashMap<String, Aggregate>();
        for (var name : List.of("Recall", "Precision", "HitPrecision", "MRR", "Coverage")) {
            var key = name + "@" + k;
            metrics.put(key, mean(answerable.stream().map(score -> score.byK().get(k).metrics().get(key)).toList()));
        }
        var fullEvidence = mean(answerable.stream()
                .filter(score -> score.query().category() == Category.MULTI_EVIDENCE)
                .map(score -> score.byK().get(k).metrics().get("FullEvidence@" + k)).toList());
        var falsePositives = (int) unanswerable.stream()
                .filter(score -> score.byK().get(k).outcome() == Outcome.FALSE_EVIDENCE).count();
        var outcomes = new EnumMap<Category, Map<Outcome, Integer>>(Category.class);
        for (var score : scored) {
            outcomes.computeIfAbsent(score.query().category(), ignored -> new EnumMap<>(Outcome.class))
                    .merge(score.byK().get(k).outcome(), 1, Integer::sum);
        }
        return new LayerAggregate(layer, k, metrics, fullEvidence, ratio(falsePositives, unanswerable.size()),
                outcomes, answerable.size(), unanswerable.size());
    }

    private static LayerAbstention abstention(Layer layer, List<QueryScore> scores) {
        var scored = scores.stream().filter(QueryScore::completed).toList();
        var empty = scored.stream().filter(score -> score.returned().orElseThrow().isEmpty()).toList();
        var unanswerable = (int) scored.stream().filter(score -> score.query().relevant().isEmpty()).count();
        var correct = (int) empty.stream().filter(score -> score.query().relevant().isEmpty()).count();
        return new LayerAbstention(layer, ratio(correct, empty.size()), ratio(correct, unanswerable),
                scores.size() - scored.size());
    }
}
