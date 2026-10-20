package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.EvidenceTrace.Arm;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Row;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Snapshot;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The scorer of the evidence-trace experiment (protocol extension {@code l3-evidence-v1}, Issue #108): the only
 * component of this experiment that reads gold labels. It scores an {@link EvidenceTrace.Snapshot} recorded by the
 * labels-free {@link HtmlKnowledgeCycleExperiment}.
 *
 * <p>Per query and arm: {@code AdmittedFraction} = admitted / recorded results (undefined when nothing was recorded);
 * {@code UsedFraction} = used / admitted chunks (undefined when nothing was admitted or the trace is ambiguous);
 * {@code RelevantEvidenceUsed} = 1 when a used chunk is in {@code Rel(q)}, over {@code A} only (undefined when the
 * trace is ambiguous). {@code memorySupportedRelevant} additionally requires the host decision {@code SUPPORTED}; it is
 * what "a successful memory-supported answer" is allowed to mean here, and it is still not an answer.
 */
final class EvidenceTraceMetrics {

    private EvidenceTraceMetrics() {
    }

    /**
     * One query through one arm.
     *
     * @param query the judged query the slot is scored against
     * @param row the trace row
     * @param admittedFraction admitted / recorded results
     * @param usedFraction used / admitted chunks
     * @param relevantEvidenceUsed whether a used chunk is relevant ({@code A} only)
     * @param memorySupportedRelevant {@code SUPPORTED} and a used chunk is relevant
     */
    record QueryScore(JudgedQuery query, Row row, OptionalDouble admittedFraction, OptionalDouble usedFraction,
            OptionalDouble relevantEvidenceUsed, boolean memorySupportedRelevant) {
    }

    /** A count out of a population. */
    record Count(int count, int of) {

        OptionalDouble fraction() {
            return of == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) count / of);
        }
    }

    /**
     * The aggregates of one arm.
     *
     * @param admittedFraction mean over every query with a recorded result
     * @param usedFraction mean over every query with an admitted, unambiguous trace
     * @param relevantEvidenceUsed mean over {@code A}
     * @param decisions decision counts over every query
     * @param memorySupportedRelevant {@code SUPPORTED} with a relevant used chunk, out of {@code |A|}
     * @param unsupportedClaims {@code SUPPORTED} decisions on {@code U} (no relevant chunk exists), out of {@code |U|}
     * @param byCategory decision counts and {@code RelevantEvidenceUsed} per category
     */
    record ArmAggregate(Aggregate admittedFraction, Aggregate usedFraction, Aggregate relevantEvidenceUsed,
            Map<EvidenceDecision, Integer> decisions, Count memorySupportedRelevant, Count unsupportedClaims,
            Map<Category, CategoryAggregate> byCategory) {
    }

    /** Decision counts and {@code RelevantEvidenceUsed} of one category in one arm. */
    record CategoryAggregate(Map<EvidenceDecision, Integer> decisions, Aggregate relevantEvidenceUsed) {
    }

    /**
     * The scored experiment.
     *
     * @param byArm per-query scores per arm, in workload order
     * @param aggregates aggregates per arm
     * @param contrastAnswerable memory-empty contrast {@code differs} over {@code A}
     * @param contrastUnanswerable memory-empty contrast {@code differs} over {@code U}
     * @param removedTargets queries whose every relevant chunk belongs to the removed document
     * @param checks the scorer's semantic checks
     */
    record Scores(Map<Arm, List<QueryScore>> byArm, Map<Arm, ArmAggregate> aggregates, Count contrastAnswerable,
            Count contrastUnanswerable, List<String> removedTargets, List<Check> checks) {
    }

    /**
     * Scores {@code snapshot} against {@code querySet}.
     *
     * @throws IllegalArgumentException when the snapshot is not a complete trace of {@code querySet} (see
     *     {@link #requireComplete}); this is the only exception scoring throws for a malformed snapshot
     */
    static Scores score(HtmlKnowledgeQuerySet querySet, HtmlKnowledgeCorpus corpus, Snapshot snapshot) {
        Objects.requireNonNull(querySet, "querySet must not be null");
        requireComplete(querySet, snapshot);
        var removedChunks = corpus.chunks().stream()
                .filter(chunk -> chunk.documentId().equals(HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT))
                .map(CorpusChunk::chunkId).toList();
        var byArm = new EnumMap<Arm, List<QueryScore>>(Arm.class);
        var aggregates = new EnumMap<Arm, ArmAggregate>(Arm.class);
        for (var arm : Arm.values()) {
            var scores = new ArrayList<QueryScore>();
            for (var query : querySet.queries()) {
                var row = snapshot.row(arm, query.queryId()).orElseThrow(() -> new IllegalArgumentException(
                        "arm " + arm + " has no row for " + query.queryId()));
                scores.add(score(query, row));
            }
            byArm.put(arm, List.copyOf(scores));
            aggregates.put(arm, aggregate(scores));
        }
        var removedTargets = querySet.queries().stream().filter(JudgedQuery::answerable)
                .filter(query -> removedChunks.containsAll(query.relevant())).map(JudgedQuery::queryId).toList();
        var contrastA = contrast(byArm.get(Arm.MEMORY), true);
        var contrastU = contrast(byArm.get(Arm.MEMORY), false);

        var checks = new ArrayList<Check>();
        var memoryOff = byArm.get(Arm.MEMORY_EMPTY).stream().filter(QueryScore::memorySupportedRelevant)
                .map(score -> score.query().queryId()).toList();
        checks.add(new Check("control.memoryOffNotSupported", memoryOff.isEmpty(), memoryOff.isEmpty()
                ? "no memory-empty run is a memory-supported relevant result (" + querySet.queries().size() + " queries)"
                : "memory-empty runs reported as memory-supported: " + memoryOff));
        var removed = byArm.get(Arm.REMOVED).stream()
                .filter(score -> removedTargets.contains(score.query().queryId()) && score.memorySupportedRelevant())
                .map(score -> score.query().queryId()).toList();
        checks.add(new Check("control.removedRelevantNotSupported", !removedTargets.isEmpty() && removed.isEmpty(),
                removedTargets.isEmpty() ? "no query has all its relevant chunks in "
                        + HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT + "; the control is not exercised"
                        : removed.isEmpty() ? "none of the " + removedTargets.size() + " queries whose relevant chunks"
                                + " were all removed is a memory-supported relevant result: " + removedTargets
                                : "reported as memory-supported after removal: " + removed));
        return new Scores(byArm, aggregates, contrastA, contrastU, removedTargets, checks);
    }

    /**
     * Requires {@code snapshot} to be one complete trace of {@code querySet}: its {@code queryHash}, exactly one row
     * per arm and query and no other row, every {@code inputQueryId} a query of the set, a contrast exactly on
     * {@link Arm#MEMORY} rows, and B2 equality exactly on the arms that recall through the adapter. A phase file can
     * be syntactically complete and still violate this, so the check runs before anything is scored.
     *
     * @throws IllegalArgumentException naming the first violation
     */
    static void requireComplete(HtmlKnowledgeQuerySet querySet, Snapshot snapshot) {
        if (!snapshot.queryHash().equals(querySet.queryHash())) {
            throw new IllegalArgumentException("the snapshot answers queryHash " + snapshot.queryHash());
        }
        var ids = new HashSet<String>();
        querySet.queries().forEach(query -> ids.add(query.queryId()));
        var seen = new HashSet<String>();
        for (var row : snapshot.rows()) {
            var key = row.arm() + " " + row.queryId();
            if (!ids.contains(row.queryId()) || !ids.contains(row.inputQueryId())) {
                throw new IllegalArgumentException("row " + key + " names a query outside the set (input "
                        + row.inputQueryId() + ")");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("duplicate row " + key);
            }
            if (row.contrast().isPresent() != (row.arm() == Arm.MEMORY)) {
                throw new IllegalArgumentException("row " + key + (row.contrast().isPresent()
                        ? " carries a memory-empty contrast" : " has no memory-empty contrast"));
            }
            if (row.recalledEqualsB2().isPresent() != row.arm().recallsThroughAdapter()) {
                throw new IllegalArgumentException("row " + key + (row.recalledEqualsB2().isPresent()
                        ? " records B2 equality for an arm without an adapter" : " does not record B2 equality"));
            }
        }
        var expected = Arm.values().length * querySet.queries().size();
        if (seen.size() != expected) {
            throw new IllegalArgumentException("the snapshot holds " + seen.size() + " of " + expected + " rows ("
                    + Arm.values().length + " arms x " + querySet.queries().size() + " queries)");
        }
    }

    private static QueryScore score(JudgedQuery query, Row row) {
        var relevant = query.relevant();
        var used = row.used();
        var admittedFraction = ratio(row.admitted().size(), row.recorded().size());
        var usedFraction = row.traceAmbiguous() ? OptionalDouble.empty() : ratio(used.size(), row.admitted().size());
        var relevantUsed = used.stream().anyMatch(relevant::contains);
        var relevantEvidenceUsed = !query.answerable() || row.traceAmbiguous() ? OptionalDouble.empty()
                : OptionalDouble.of(relevantUsed ? 1.0 : 0.0);
        var supported = query.answerable() && !row.traceAmbiguous() && relevantUsed
                && row.decision() == EvidenceDecision.SUPPORTED;
        return new QueryScore(query, row, admittedFraction, usedFraction, relevantEvidenceUsed, supported);
    }

    private static ArmAggregate aggregate(List<QueryScore> scores) {
        var answerable = scores.stream().filter(score -> score.query().answerable()).toList();
        var unanswerable = scores.stream().filter(score -> !score.query().answerable()).toList();
        var byCategory = new EnumMap<Category, CategoryAggregate>(Category.class);
        for (var category : Category.values()) {
            var inCategory = scores.stream().filter(score -> score.query().category() == category).toList();
            if (!inCategory.isEmpty()) {
                byCategory.put(category, new CategoryAggregate(decisions(inCategory),
                        RecallMetrics.mean(inCategory.stream().map(QueryScore::relevantEvidenceUsed).toList())));
            }
        }
        return new ArmAggregate(
                RecallMetrics.mean(scores.stream().map(QueryScore::admittedFraction).toList()),
                RecallMetrics.mean(scores.stream().map(QueryScore::usedFraction).toList()),
                RecallMetrics.mean(answerable.stream().map(QueryScore::relevantEvidenceUsed).toList()),
                decisions(scores),
                new Count((int) answerable.stream().filter(QueryScore::memorySupportedRelevant).count(),
                        answerable.size()),
                new Count((int) unanswerable.stream().filter(score -> score.row().decision()
                        == EvidenceDecision.SUPPORTED).count(), unanswerable.size()),
                byCategory);
    }

    private static Map<EvidenceDecision, Integer> decisions(List<QueryScore> scores) {
        var counts = new LinkedHashMap<EvidenceDecision, Integer>();
        for (var decision : EvidenceDecision.values()) {
            counts.put(decision, (int) scores.stream().filter(score -> score.row().decision() == decision).count());
        }
        return counts;
    }

    private static Count contrast(List<QueryScore> memory, boolean answerable) {
        var population = memory.stream().filter(score -> score.query().answerable() == answerable).toList();
        return new Count((int) population.stream().filter(score -> score.row().contrast().orElseThrow().differs())
                .count(), population.size());
    }

    private static OptionalDouble ratio(int numerator, int denominator) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }

    /** The relevant chunk ids of {@code query}, sorted, for reports. */
    static List<String> relevant(JudgedQuery query) {
        return query.relevant().stream().sorted().toList();
    }
}
