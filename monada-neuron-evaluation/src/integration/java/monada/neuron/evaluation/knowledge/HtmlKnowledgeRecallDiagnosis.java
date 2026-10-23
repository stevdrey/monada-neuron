package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Rule;
import monada.neuron.evaluation.knowledge.RecallDiagnosisLadder.LadderPoint;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.PointScores;
import monada.neuron.evaluation.knowledge.SignalHitCapture.QueryCapture;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * One ladder point of the B2 recall diagnosis (Issue #135): imports the point corpus into a fresh root, takes the
 * verified #107 observation (B0, B1, B2, B2c with fidelity, prefix stability and B2 = B2c) over the cohort, captures
 * every per-Signal Store hit labels-free ({@link SignalHitCapture}), replays the production merge over that capture,
 * and only then hands snapshot and capture to the scorer ({@link RecallLossAttribution}).
 *
 * <p>Checks, prefixed with the rung label: the ingestion checks of #105, the observation checks of #107,
 * {@code diagnosis.captureCompleted} (every captured Store call returned faithfully),
 * {@code diagnosis.maxScoreReplayEqualsB2} (the offline max-score replay over pool {@code k} equals B2 and B2c at
 * limit {@code k} for every {@code k}: chunks, order and bit-exact scores) and {@code diagnosis.attributionConsistent}
 * (every relevant chunk is attributed once per {@code k}, and the attributed {@code RETRIEVED} count equals the
 * relevant chunks B2 returned). Nothing is timed.
 */
final class HtmlKnowledgeRecallDiagnosis {

    /**
     * Everything one point produced.
     *
     * @param point the ladder point
     * @param checks semantic verdicts, prefixed with the rung label
     * @param captures the labels-free capture, one per cohort query
     * @param scores the scorer's output over the cohort
     */
    record PointDiagnosis(LadderPoint point, List<Check> checks, List<QueryCapture> captures, PointScores scores) {

        PointDiagnosis {
            checks = List.copyOf(checks);
            captures = List.copyOf(captures);
        }
    }

    private final UnaryOperator<ResonanceStoreRecall> storeSeam;

    HtmlKnowledgeRecallDiagnosis() {
        this(UnaryOperator.identity());
    }

    /** Creates the diagnosis with a test seam that wraps the Store-side recall of the capture only. */
    HtmlKnowledgeRecallDiagnosis(UnaryOperator<ResonanceStoreRecall> storeSeam) {
        this.storeSeam = Objects.requireNonNull(storeSeam, "storeSeam must not be null");
    }

    /** Diagnoses {@code point} over {@code cohort} with the root at {@code directory/root}. */
    PointDiagnosis measure(LadderPoint point, HtmlKnowledgeQuerySet querySet, List<JudgedQuery> cohort,
            Path directory) {
        var corpus = point.point().corpus();
        var prefix = point.rung().label() + ".";
        var checks = new ArrayList<Check>();
        var root = directory.resolve("root");
        var imported = new HtmlKnowledgeIngestion(corpus).ingest(root);
        imported.checks().forEach(check -> checks.add(prefixed(prefix, check)));
        var manifest = imported.manifest();

        // Labels-free from here to the scorer: the recall paths see query ids and texts only.
        var queries = HtmlKnowledgeScaling.texts(cohort);
        var observation = new HtmlKnowledgeRecall(corpus).observe(root, manifest, querySet.queryHash(), queries);
        observation.checks().forEach(check -> checks.add(prefixed(prefix, check)));
        var snapshot = observation.snapshot();
        var memory = MonadaMemory.open(root);
        ResonanceStoreRecall store = (query, limit, threshold) -> memory.resonate(query).topK(limit)
                .threshold(threshold).execute().results();
        var captures = new SignalHitCapture(corpus, manifest, storeSeam.apply(store)).capture(queries);

        var failed = captures.stream().filter(capture -> !capture.completed())
                .map(capture -> capture.queryId() + ": " + capture.failure().orElseThrow()).toList();
        checks.add(new Check(prefix + "diagnosis.captureCompleted", failed.isEmpty(), failed.isEmpty()
                ? "every Store call of all " + captures.size() + " queries at pools " + SignalHitCapture.POOLS
                        + " returned finite scores and atoms that map through the manifest with content hashing to"
                        + " the corpus sha256"
                : failed.size() + " queries failed and are not scored: " + head(failed)));
        var replay = replayDifferences(captures, snapshot);
        checks.add(new Check(prefix + "diagnosis.maxScoreReplayEqualsB2", replay.isEmpty() && failed.isEmpty(),
                replay.isEmpty() && failed.isEmpty()
                        ? "the offline max-score replay over each Signal's pool-k list equals B2 and B2c at limit k for"
                                + " k = 1, 3, 5 (chunks, order, bit-exact scores), so every counterfactual starts from"
                                + " the candidates B2 itself merged"
                        : "not compared or unequal: " + head(replay.isEmpty() ? failed : replay)));

        var scores = new RecallLossAttribution(snapshot, captures, SignalHitCapture.documentFrequency(corpus),
                corpus.chunks().stream().map(CorpusChunk::chunkId).collect(Collectors.toSet())).score(cohort);
        var inconsistent = attributionDifferences(cohort, snapshot, scores);
        checks.add(new Check(prefix + "diagnosis.attributionConsistent", inconsistent.isEmpty(), inconsistent.isEmpty()
                ? "every relevant chunk of every scored answerable query is attributed exactly once per k, and the"
                        + " RETRIEVED count equals the relevant chunks of B2 at limit k"
                : head(inconsistent)));
        return new PointDiagnosis(point, checks, captures, scores);
    }

    /** Returns every query and {@code k} where the max-score replay differs from B2 or B2c at limit {@code k}. */
    static List<String> replayDifferences(List<QueryCapture> captures, Snapshot snapshot) {
        var differences = new ArrayList<String>();
        for (var capture : captures) {
            if (!capture.completed()) {
                continue;
            }
            for (var k : RecallMetrics.KS) {
                var replay = MergeCounterfactuals.merge(Rule.MAX_SCORE, capture.at(k), k);
                var rows = new ArrayList<Row>(replay.size());
                for (var rank = 0; rank < replay.size(); rank++) {
                    var merged = replay.get(rank);
                    rows.add(new Row(capture.queryId(), Layer.B2, k, rank + 1, merged.chunkId(), merged.value()));
                }
                for (var layer : List.of(Layer.B2, Layer.B2C)) {
                    if (!HtmlKnowledgeRecall.sameRows(rows, snapshot.rows(capture.queryId(), layer, k))) {
                        differences.add(capture.queryId() + " " + layer.label() + " limit " + k);
                    }
                }
            }
        }
        return differences;
    }

    private static List<String> attributionDifferences(List<JudgedQuery> cohort, Snapshot snapshot,
            PointScores scores) {
        var differences = new ArrayList<String>();
        var failed = scores.failedQueries();
        for (var k : RecallMetrics.KS) {
            var expectedFates = 0;
            var expectedRetrieved = 0;
            for (var query : cohort) {
                if (failed.contains(query.queryId())) {
                    continue;
                }
                expectedFates += query.relevant().size();
                expectedRetrieved += (int) snapshot.rows(query.queryId(), Layer.B2, k).stream()
                        .map(Row::chunkId).filter(query.relevant()::contains).distinct().count();
            }
            var atK = k;
            var fates = scores.fates().stream().filter(fate -> fate.k() == atK).toList();
            var keys = fates.stream().map(fate -> fate.queryId() + " " + fate.chunkId()).distinct().count();
            if (fates.size() != expectedFates || keys != fates.size()) {
                differences.add("k=" + k + ": " + fates.size() + " fates (" + keys + " distinct), expected "
                        + expectedFates);
            }
            var retrieved = scores.reasons().get(k).getOrDefault(RecallLossAttribution.Reason.RETRIEVED, 0);
            if (retrieved != expectedRetrieved) {
                differences.add("k=" + k + ": " + retrieved + " RETRIEVED, B2 returned " + expectedRetrieved);
            }
        }
        return differences;
    }

    private static Check prefixed(String prefix, Check check) {
        return new Check(prefix + check.name(), check.passed(), check.detail());
    }

    private static String head(List<String> values) {
        return String.join("; ", values.subList(0, Math.min(5, values.size()))) + (values.size() > 5 ? "; ..." : "");
    }
}
