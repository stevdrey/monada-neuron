package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Merged;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Rule;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import monada.neuron.evaluation.knowledge.SignalHitCapture.Hit;
import monada.neuron.evaluation.knowledge.SignalHitCapture.QueryCapture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The scorer of the B2 recall diagnosis (Issue #135), and its only component that reads gold labels. It receives the
 * labels-free {@link SignalHitCapture} lists and the verified #107 {@link Snapshot} of one ladder point and explains,
 * for every relevant chunk of every answerable query at every {@code k}, why it did or did not reach B2's top
 * {@code k} ({@link Reason}). It also scores B1, B2 and every {@link MergeCounterfactuals} rule over identical
 * candidates, the candidate ceiling any merge could reach, the non-relevant slots of B2 and the tokens that won them.
 *
 * <p>Nothing here feeds back into encoding, retrieval or merging: every alternative is research-only and in-sample
 * exploratory over the fixed #111 curve cohort, which is a descriptive population, never a tuning or validation set.
 */
final class RecallLossAttribution {

    /** Number of worst regressions reported per point. */
    static final int WORST = 5;

    /** Chunk id prefix of generated distractors ({@link ScalingDistractorGenerator}). */
    static final String DISTRACTOR_PREFIX = "chk:synthetic-";

    /** Why a relevant chunk did or did not reach B2's top {@code k}; exactly one per chunk, query and {@code k}. */
    enum Reason {
        /** In B2's top {@code k}. */
        RETRIEVED,
        /** In some Signal's pool-{@code k} list with the cutoff score, lost on the Signal-order or Store-rank tie-break. */
        TIE_BROKEN_OUT,
        /** In some Signal's pool-{@code k} list, but its best raw score is below the merged cutoff. */
        OUTRANKED_IN_MERGE,
        /** In no Signal's pool-{@code k} list, but in some Signal's expanded pool list. */
        RECOVERABLE_WITH_LARGER_POOL,
        /** In no Signal's list, even at the expanded pool. */
        ABSENT_FROM_ALL_TOKEN_LISTS,
        /** Not in the point corpus at all (missing gold evidence); never expected for the curve cohort. */
        MISSING_FROM_CORPUS
    }

    /**
     * The fate of one relevant chunk.
     *
     * @param queryId the query
     * @param k the cutoff
     * @param chunkId the relevant chunk
     * @param required whether it is required (otherwise supporting)
     * @param reason the attributed reason
     * @param inB1 whether B1's top {@code k} holds it (the full-text contrast)
     * @param signalsAtPoolK the number of Signals whose pool-{@code k} list holds it
     * @param bestRankExpanded its best 1-based Store rank over the Signals' expanded lists, if any
     * @param bestToken the token of its best expanded-pool hit, if any
     * @param bestScore its best raw score over the expanded lists, if any
     * @param cutoffScore the raw score at B2's rank {@code k}, if B2 returned {@code k} results
     */
    record ChunkFate(String queryId, int k, String chunkId, boolean required, Reason reason, boolean inB1,
            int signalsAtPoolK, OptionalInt bestRankExpanded, Optional<String> bestToken, OptionalDouble bestScore,
            OptionalDouble cutoffScore) {
    }

    /**
     * One non-relevant slot of B2's top {@value HtmlKnowledgeRecall#MAX_LIMIT} for an answerable query.
     *
     * @param queryId the query
     * @param rank 1-based B2 rank
     * @param chunkId the chunk occupying it
     * @param distractor whether it is a generated distractor
     * @param token the token whose hit placed it there
     * @param tokenDf that token's corpus document frequency
     * @param score its raw score
     */
    record Displacer(String queryId, int rank, String chunkId, boolean distractor, String token, int tokenDf,
            double score) {
    }

    /**
     * One query Signal's pool-{@value HtmlKnowledgeRecall#MAX_LIMIT} list, summarized.
     *
     * @param queryId the query
     * @param signalIndex the Signal's position
     * @param token its single-token Store query
     * @param df the token's corpus document frequency
     * @param hits results in its list
     * @param top1 its top raw score, if any
     * @param relevantHits relevant chunks in its list (scorer-side)
     */
    record TokenRow(String queryId, int signalIndex, String token, int df, int hits, OptionalDouble top1,
            int relevantHits) {
    }

    /**
     * One system's aggregate at one {@code k} over a population.
     *
     * @param system {@code B1}, {@code B2} or a merge rule label
     * @param pool {@code full-text} for B1, {@code k} for B2 and pool-{@code k} replays, or the expanded pool
     * @param k the cutoff
     * @param means Recall, Precision and MRR over answerable queries
     * @param hits answerable queries with every required chunk retrieved
     * @param answerable scored answerable queries
     * @param falseEvidence non-answerable queries with a non-empty top {@code k}
     * @param unanswerable scored non-answerable queries
     * @param failed queries without an outcome (operational failure), never scored as zeros
     */
    record SystemScore(String system, String pool, int k, Map<String, Aggregate> means, int hits, int answerable,
            int falseEvidence, int unanswerable, int failed) {

        SystemScore {
            means = Collections.unmodifiableMap(new LinkedHashMap<>(means));
        }
    }

    /**
     * The candidate ceiling at one pool: the mean fraction of relevant chunks in the union of a query's Signal lists,
     * an upper bound on the Recall@k any merge of those candidates can reach.
     *
     * @param k the cutoff
     * @param pool the per-Signal pool
     * @param unionRecall the mean over answerable queries
     */
    record CandidateCeiling(int k, int pool, Aggregate unionRecall) {
    }

    /**
     * Cross-token score comparability at {@code k = 5}.
     *
     * @param pairs (query, token) pairs with a non-empty pool-5 list
     * @param spearmanDfTop1 Spearman rank correlation between the token's document frequency and its top score
     * @param medianDf the median document frequency over all (query, token) pairs
     * @param slots B2 top-5 slots of answerable queries
     * @param nonRelevantSlots of which non-relevant
     * @param distractorSlots of which generated distractors
     * @param aboveMedianDfSlots non-relevant slots won through a token whose document frequency exceeds the median
     */
    record Comparability(int pairs, OptionalDouble spearmanDfTop1, double medianDf, int slots, int nonRelevantSlots,
            int distractorSlots, int aboveMedianDfSlots) {
    }

    /**
     * One worst regression: an answerable query whose B2 Recall@5 is furthest below its B1 Recall@5.
     *
     * @param queryId the query
     * @param category its category
     * @param tokens its Store queries
     * @param b1Recall B1 Recall@5
     * @param b2Recall B2 Recall@5
     * @param fates the fate of each relevant chunk at {@code k = 5}
     * @param displacers the non-relevant slots of B2's top 5
     */
    record Regression(String queryId, String category, List<String> tokens, double b1Recall, double b2Recall,
            List<ChunkFate> fates, List<Displacer> displacers) {
    }

    /**
     * Store-side cost of B2 and of the expanded pool, per query.
     *
     * @param storeCallsPerQuery mean Store calls of one B2 recall (one per Signal); B1 makes one
     * @param hitsPerQuery by pool, mean captured hits before deduplication
     * @param distinctPerQuery by pool, mean distinct atoms after deduplication
     */
    record Cost(double storeCallsPerQuery, Map<Integer, Double> hitsPerQuery, Map<Integer, Double> distinctPerQuery) {
    }

    /**
     * Everything scored at one point.
     *
     * @param systems every system at every {@code k} over the whole cohort
     * @param groups the same by group ({@code arity:...} and {@code category:...}), in a fixed order
     * @param ceilings the candidate ceiling at every {@code k}, for pool {@code k} and the expanded pool
     * @param fates every relevant chunk's fate, by query then {@code k}
     * @param reasons reason counts by {@code k}
     * @param displacers every non-relevant B2 top-5 slot of answerable queries
     * @param tokens every Signal's pool-5 summary
     * @param comparability the score-comparability summary
     * @param regressions the worst regressions, worst first
     * @param cost the Store-side cost
     * @param failedQueries queries whose capture or a snapshot layer failed
     * @param negativeQueries queries with an empty relevant set
     */
    record PointScores(List<SystemScore> systems, Map<String, List<SystemScore>> groups,
            List<CandidateCeiling> ceilings, List<ChunkFate> fates, Map<Integer, Map<Reason, Integer>> reasons,
            List<Displacer> displacers, List<TokenRow> tokens, Comparability comparability,
            List<Regression> regressions, Cost cost, List<String> failedQueries, List<String> negativeQueries) {
    }

    /** A ranked system: how it ranks one query at one {@code k}, or empty when it has no outcome for that query. */
    private record Ranker(String name, String pool, SystemRanking ranking) {
    }

    @FunctionalInterface
    private interface SystemRanking {

        Optional<List<String>> topK(JudgedQuery query, int k);
    }

    private final Snapshot snapshot;
    private final Map<String, QueryCapture> captures;
    private final Map<String, Integer> documentFrequency;
    private final Set<String> corpusChunks;

    /**
     * Creates the scorer of one point.
     *
     * @param snapshot the verified #107 observation of the point's root
     * @param captures the labels-free capture of the same root, one per query
     * @param documentFrequency the point corpus' token document frequencies
     * @param corpusChunks the chunk ids of the point corpus
     */
    RecallLossAttribution(Snapshot snapshot, List<QueryCapture> captures, Map<String, Integer> documentFrequency,
            Set<String> corpusChunks) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        var byId = new LinkedHashMap<String, QueryCapture>();
        captures.forEach(capture -> byId.put(capture.queryId(), capture));
        this.captures = Collections.unmodifiableMap(byId);
        this.documentFrequency = Map.copyOf(documentFrequency);
        this.corpusChunks = Set.copyOf(corpusChunks);
    }

    /** Scores {@code cohort}, in order. */
    PointScores score(List<JudgedQuery> cohort) {
        var systems = systems();
        var whole = new ArrayList<SystemScore>();
        for (var system : systems) {
            for (var k : RecallMetrics.KS) {
                whole.add(aggregate(system, k, cohort));
            }
        }
        var groups = new LinkedHashMap<String, List<SystemScore>>();
        for (var arity : List.of("no-token", "single-token", "multi-token")) {
            group(groups, "arity:" + arity, systems, cohort, query -> arity(query).equals(arity));
        }
        for (var category : JudgedQuery.Category.values()) {
            group(groups, "category:" + category.label(), systems, cohort, query -> query.category() == category);
        }
        var fates = new ArrayList<ChunkFate>();
        var reasons = new LinkedHashMap<Integer, Map<Reason, Integer>>();
        RecallMetrics.KS.forEach(k -> reasons.put(k, new EnumMap<>(Reason.class)));
        var failed = new ArrayList<String>();
        var negative = new ArrayList<String>();
        for (var query : cohort) {
            if (query.relevant().isEmpty()) {
                negative.add(query.queryId());
            }
            if (!scorable(query)) {
                failed.add(query.queryId());
                continue;
            }
            for (var k : RecallMetrics.KS) {
                for (var fate : fates(query, k)) {
                    fates.add(fate);
                    reasons.get(k).merge(fate.reason(), 1, Integer::sum);
                }
            }
        }
        var displacers = new ArrayList<Displacer>();
        var slots = 0;
        for (var query : cohort) {
            if (query.answerable() && scorable(query)) {
                var top = merged(query, HtmlKnowledgeRecall.MAX_LIMIT, HtmlKnowledgeRecall.MAX_LIMIT);
                slots += top.size();
                displacers.addAll(displacers(query, top));
            }
        }
        var tokens = tokens(cohort);
        return new PointScores(whole, groups, ceilings(cohort), fates, reasons, displacers, tokens,
                comparability(tokens, slots, displacers), regressions(cohort, fates, displacers), cost(cohort), failed,
                negative);
    }

    /** B1, B2, then every rule at pool {@code k} and at the expanded pool. */
    private List<Ranker> systems() {
        var systems = new ArrayList<Ranker>();
        systems.add(new Ranker("B1", "full-text", (query, k) -> snapshotTopK(query, Layer.B1, k)));
        systems.add(new Ranker("B2", "k", (query, k) -> snapshotTopK(query, Layer.B2, k)));
        for (var rule : Rule.values()) {
            systems.add(new Ranker(rule.label(), "k", (query, k) -> replay(query, rule, k, k)));
        }
        for (var rule : Rule.values()) {
            systems.add(new Ranker(rule.label(), Integer.toString(SignalHitCapture.EXPANDED_POOL),
                    (query, k) -> replay(query, rule, SignalHitCapture.EXPANDED_POOL, k)));
        }
        return systems;
    }

    private Optional<List<String>> snapshotTopK(JudgedQuery query, Layer layer, int k) {
        var rows = snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT);
        if (rows.stream().anyMatch(row -> row.chunkId().equals(HtmlKnowledgeRecall.FAILED))) {
            return Optional.empty();
        }
        var ids = rows.stream().map(Row::chunkId).toList();
        return Optional.of(ids.subList(0, Math.min(k, ids.size())));
    }

    private Optional<List<String>> replay(JudgedQuery query, Rule rule, int pool, int k) {
        var capture = captures.get(query.queryId());
        if (capture == null || !capture.completed()) {
            return Optional.empty();
        }
        return Optional.of(MergeCounterfactuals.merge(rule, capture.at(pool), k).stream().map(Merged::chunkId)
                .toList());
    }

    private boolean scorable(JudgedQuery query) {
        var capture = captures.get(query.queryId());
        return capture != null && capture.completed() && snapshotTopK(query, Layer.B1, 1).isPresent()
                && snapshotTopK(query, Layer.B2, 1).isPresent();
    }

    private void group(Map<String, List<SystemScore>> groups, String name, List<Ranker> systems,
            List<JudgedQuery> cohort, Predicate<JudgedQuery> member) {
        var population = cohort.stream().filter(member).toList();
        if (population.isEmpty()) {
            return;
        }
        var scores = new ArrayList<SystemScore>();
        for (var system : systems) {
            for (var k : RecallMetrics.KS) {
                scores.add(aggregate(system, k, population));
            }
        }
        groups.put(name, List.copyOf(scores));
    }

    /** {@code no-token} (blank), {@code single-token} or {@code multi-token}, from the captured Store queries. */
    private static String arity(JudgedQuery query) {
        var tokens = HtmlTokens.distinct(query.text()).size();
        return tokens == 0 ? "no-token" : tokens == 1 ? "single-token" : "multi-token";
    }

    private static SystemScore aggregate(Ranker system, int k, List<JudgedQuery> population) {
        var recall = new ArrayList<OptionalDouble>();
        var precision = new ArrayList<OptionalDouble>();
        var mrr = new ArrayList<OptionalDouble>();
        int hits = 0, answerable = 0, falseEvidence = 0, unanswerable = 0, failed = 0;
        for (var query : population) {
            var topK = system.ranking().topK(query, k);
            if (topK.isEmpty()) {
                failed++;
                continue;
            }
            var retK = topK.get();
            var outcome = RecallMetrics.outcome(query.required(), query.relevant(), retK);
            if (query.relevant().isEmpty()) {
                unanswerable++;
                falseEvidence += outcome == Outcome.FALSE_EVIDENCE ? 1 : 0;
                continue;
            }
            answerable++;
            hits += outcome == Outcome.HIT ? 1 : 0;
            recall.add(RecallMetrics.recall(query.relevant(), retK));
            precision.add(RecallMetrics.precision(query.relevant(), retK, k));
            mrr.add(RecallMetrics.reciprocalRank(query.relevant(), retK));
        }
        var means = new LinkedHashMap<String, Aggregate>();
        means.put("Recall@" + k, RecallMetrics.mean(recall));
        means.put("Precision@" + k, RecallMetrics.mean(precision));
        means.put("MRR@" + k, RecallMetrics.mean(mrr));
        return new SystemScore(system.name(), system.pool(), k, means, hits, answerable, falseEvidence, unanswerable,
                failed);
    }

    private List<Merged> merged(JudgedQuery query, int pool, int k) {
        return MergeCounterfactuals.merge(Rule.MAX_SCORE, captures.get(query.queryId()).at(pool), k);
    }

    /** Attributes every relevant chunk of {@code query} at {@code k}, in sorted chunk order. */
    List<ChunkFate> fates(JudgedQuery query, int k) {
        var capture = captures.get(query.queryId());
        var b2 = merged(query, k, k);
        var b2Chunks = b2.stream().map(Merged::chunkId).toList();
        var cutoff = b2.size() == k ? OptionalDouble.of(b2.getLast().value()) : OptionalDouble.empty();
        var b1 = snapshotTopK(query, Layer.B1, k).orElse(List.of());
        var fates = new ArrayList<ChunkFate>();
        for (var chunkId : query.relevant().stream().sorted().toList()) {
            var atK = hitsOf(capture.at(k), chunkId);
            var expanded = hitsOf(capture.at(SignalHitCapture.EXPANDED_POOL), chunkId);
            var best = expanded.stream().min(MergeCounterfactuals.HIT_ORDER);
            Reason reason;
            if (!corpusChunks.contains(chunkId)) {
                reason = Reason.MISSING_FROM_CORPUS;
            } else if (b2Chunks.contains(chunkId)) {
                reason = Reason.RETRIEVED;
            } else if (!atK.isEmpty()) {
                var score = atK.stream().min(MergeCounterfactuals.HIT_ORDER).orElseThrow().score();
                reason = cutoff.isPresent() && HtmlKnowledgeRecall.sameScore(score, cutoff.getAsDouble())
                        ? Reason.TIE_BROKEN_OUT : Reason.OUTRANKED_IN_MERGE;
            } else if (!expanded.isEmpty()) {
                reason = Reason.RECOVERABLE_WITH_LARGER_POOL;
            } else {
                reason = Reason.ABSENT_FROM_ALL_TOKEN_LISTS;
            }
            fates.add(new ChunkFate(query.queryId(), k, chunkId, query.required().contains(chunkId), reason,
                    b1.contains(chunkId), (int) atK.stream().map(Hit::signalIndex).distinct().count(),
                    expanded.stream().mapToInt(hit -> hit.rank() + 1).min(),
                    best.map(hit -> capture.tokens().get(hit.signalIndex())),
                    best.map(hit -> OptionalDouble.of(hit.score())).orElse(OptionalDouble.empty()), cutoff));
        }
        return fates;
    }

    private static List<Hit> hitsOf(List<List<Hit>> perSignal, String chunkId) {
        return perSignal.stream().flatMap(List::stream).filter(hit -> hit.chunkId().equals(chunkId)).toList();
    }

    private List<Displacer> displacers(JudgedQuery query, List<Merged> top) {
        var tokens = captures.get(query.queryId()).tokens();
        var displacers = new ArrayList<Displacer>();
        for (var rank = 0; rank < top.size(); rank++) {
            var merged = top.get(rank);
            if (query.relevant().contains(merged.chunkId())) {
                continue;
            }
            var token = tokens.get(merged.best().signalIndex());
            displacers.add(new Displacer(query.queryId(), rank + 1, merged.chunkId(),
                    merged.chunkId().startsWith(DISTRACTOR_PREFIX), token, documentFrequency.getOrDefault(token, 0),
                    merged.best().score()));
        }
        return displacers;
    }

    private List<TokenRow> tokens(List<JudgedQuery> cohort) {
        var rows = new ArrayList<TokenRow>();
        for (var query : cohort) {
            var capture = captures.get(query.queryId());
            if (capture == null || !capture.completed()) {
                continue;
            }
            var lists = capture.at(HtmlKnowledgeRecall.MAX_LIMIT);
            for (var signal = 0; signal < lists.size(); signal++) {
                var hits = lists.get(signal);
                var token = capture.tokens().get(signal);
                rows.add(new TokenRow(query.queryId(), signal, token, documentFrequency.getOrDefault(token, 0),
                        hits.size(), hits.isEmpty() ? OptionalDouble.empty()
                                : OptionalDouble.of(hits.getFirst().score()),
                        (int) hits.stream().filter(hit -> query.relevant().contains(hit.chunkId())).count()));
            }
        }
        return rows;
    }

    private List<CandidateCeiling> ceilings(List<JudgedQuery> cohort) {
        var ceilings = new ArrayList<CandidateCeiling>();
        for (var k : RecallMetrics.KS) {
            for (var pool : List.of(k, SignalHitCapture.EXPANDED_POOL)) {
                var values = new ArrayList<OptionalDouble>();
                for (var query : cohort) {
                    if (query.answerable() && scorable(query)) {
                        var union = captures.get(query.queryId()).at(pool).stream().flatMap(List::stream)
                                .map(Hit::chunkId).distinct().toList();
                        values.add(RecallMetrics.recall(query.relevant(), union));
                    }
                }
                ceilings.add(new CandidateCeiling(k, pool, RecallMetrics.mean(values)));
            }
        }
        return ceilings;
    }

    private static Comparability comparability(List<TokenRow> tokens, int slots, List<Displacer> displacers) {
        var withHits = tokens.stream().filter(row -> row.top1().isPresent()).toList();
        var spearman = spearman(withHits.stream().mapToDouble(TokenRow::df).toArray(),
                withHits.stream().mapToDouble(row -> row.top1().getAsDouble()).toArray());
        var dfs = tokens.stream().mapToDouble(TokenRow::df).sorted().toArray();
        var median = dfs.length == 0 ? 0.0 : dfs.length % 2 == 1 ? dfs[dfs.length / 2]
                : (dfs[dfs.length / 2 - 1] + dfs[dfs.length / 2]) / 2.0;
        return new Comparability(withHits.size(), spearman, median, slots, displacers.size(),
                (int) displacers.stream().filter(Displacer::distractor).count(),
                (int) displacers.stream().filter(displacer -> displacer.tokenDf() > median).count());
    }

    /** Spearman's rank correlation with average ranks for ties; undefined below two pairs or for a constant side. */
    static OptionalDouble spearman(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("unequal lengths");
        }
        if (x.length < 2) {
            return OptionalDouble.empty();
        }
        var rx = ranks(x);
        var ry = ranks(y);
        var mx = 0.0;
        var my = 0.0;
        for (var i = 0; i < x.length; i++) {
            mx += rx[i];
            my += ry[i];
        }
        mx /= x.length;
        my /= x.length;
        double covariance = 0, vx = 0, vy = 0;
        for (var i = 0; i < x.length; i++) {
            covariance += (rx[i] - mx) * (ry[i] - my);
            vx += (rx[i] - mx) * (rx[i] - mx);
            vy += (ry[i] - my) * (ry[i] - my);
        }
        return vx == 0 || vy == 0 ? OptionalDouble.empty() : OptionalDouble.of(covariance / Math.sqrt(vx * vy));
    }

    private static double[] ranks(double[] values) {
        var order = new Integer[values.length];
        for (var i = 0; i < values.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
        var ranks = new double[values.length];
        var i = 0;
        while (i < order.length) {
            var j = i;
            while (j + 1 < order.length && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            var average = (i + j) / 2.0 + 1;
            for (var m = i; m <= j; m++) {
                ranks[order[m]] = average;
            }
            i = j + 1;
        }
        return ranks;
    }

    private List<Regression> regressions(List<JudgedQuery> cohort, List<ChunkFate> fates, List<Displacer> displacers) {
        var k = HtmlKnowledgeRecall.MAX_LIMIT;
        var candidates = new ArrayList<Regression>();
        for (var query : cohort) {
            if (!query.answerable() || !scorable(query)) {
                continue;
            }
            var b1 = RecallMetrics.recall(query.relevant(), snapshotTopK(query, Layer.B1, k).orElseThrow())
                    .getAsDouble();
            var b2 = RecallMetrics.recall(query.relevant(), snapshotTopK(query, Layer.B2, k).orElseThrow())
                    .getAsDouble();
            if (b1 > b2) {
                candidates.add(new Regression(query.queryId(), query.category().label(),
                        captures.get(query.queryId()).tokens(), b1, b2,
                        fates.stream().filter(fate -> fate.queryId().equals(query.queryId()) && fate.k() == k)
                                .toList(),
                        displacers.stream().filter(displacer -> displacer.queryId().equals(query.queryId()))
                                .toList()));
            }
        }
        // Worst first; equal drops keep cohort order (the sort is stable).
        candidates.sort(Comparator.comparingDouble((Regression regression) -> regression.b1Recall()
                - regression.b2Recall()).reversed());
        return List.copyOf(candidates.subList(0, Math.min(WORST, candidates.size())));
    }

    private Cost cost(List<JudgedQuery> cohort) {
        var completed = cohort.stream().map(query -> captures.get(query.queryId()))
                .filter(capture -> capture != null && capture.completed()).toList();
        var calls = completed.stream().mapToInt(QueryCapture::storeCalls).average().orElse(0.0);
        var hits = new LinkedHashMap<Integer, Double>();
        var distinct = new LinkedHashMap<Integer, Double>();
        for (var pool : SignalHitCapture.POOLS) {
            hits.put(pool, completed.stream().mapToInt(capture -> MergeCounterfactuals.hitCount(capture.at(pool)))
                    .average().orElse(0.0));
            distinct.put(pool, completed.stream().mapToInt(capture -> new LinkedHashSet<>(capture.at(pool).stream()
                    .flatMap(List::stream).map(Hit::reference).toList()).size()).average().orElse(0.0));
        }
        return new Cost(calls, hits, distinct);
    }
}
