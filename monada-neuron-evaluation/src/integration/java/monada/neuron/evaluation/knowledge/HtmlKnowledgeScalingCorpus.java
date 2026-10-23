package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The scaling family of {@code html-kc-v1} (Issue #111, extension {@code scaling-v1}): one frozen corpus version
 * {@code html-kc-v1-scale-<N>} per size point, holding the first {@code min(N, B)} base chunks in {@code chunkId} order
 * and {@code N - min(N, B)} distractors of {@link ScalingDistractorGenerator}, with its own {@code corpusHash}.
 *
 * <p>The query workload is {@code html-kq-v1}, unchanged, whose labels were validated once against the full base corpus.
 * A query whose relevant set names a chunk missing at a point is removed from that point before any point-local check
 * or scoring and listed by id. The <em>curve population</em> is the queries eligible at every protocol size point,
 * whichever subset a run measures, so only the corpus size changes along the curve and a point scores the same curve
 * queries in every run.
 */
final class HtmlKnowledgeScalingCorpus {

    /** The protocol's size points. */
    static final List<Integer> SIZES = List.of(25, 100, 500);

    private HtmlKnowledgeScalingCorpus() {
    }

    /** Returns the corpus version of the point of size {@code n}. */
    static String version(int n) {
        return HtmlKnowledgeCorpus.VERSION + "-scale-" + n;
    }

    /**
     * One size point.
     *
     * @param size the number of chunks {@code N}
     * @param corpus the validated point corpus
     * @param baseChunks how many base chunks it holds
     * @param generatedChunks how many generated distractors it holds
     * @param eligible the queries whose whole relevant set is present, in query-set order
     * @param removed the ids of the queries removed at this point, in query-set order
     */
    record Point(int size, HtmlKnowledgeCorpus corpus, int baseChunks, int generatedChunks, List<JudgedQuery> eligible,
            List<String> removed) {

        Point {
            Objects.requireNonNull(corpus, "corpus must not be null");
            eligible = List.copyOf(eligible);
            removed = List.copyOf(removed);
        }
    }

    /**
     * A scaling family.
     *
     * @param base the base corpus {@code html-kc-v1}
     * @param querySet the unchanged query set, validated against {@code base}
     * @param seed the generator seed
     * @param points the points, in ascending size
     * @param curve the queries eligible at every protocol size point, in query-set order
     * @param excludedFromCurve the ids of the queries removed at any protocol size point, in query-set order
     */
    record Family(HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet, long seed, List<Point> points,
            List<JudgedQuery> curve, List<String> excludedFromCurve) {

        Family {
            points = List.copyOf(points);
            curve = List.copyOf(curve);
            excludedFromCurve = List.copyOf(excludedFromCurve);
        }
    }

    /**
     * Builds the family over {@code sizes} (each one of {@link #SIZES}, ascending and distinct).
     *
     * @throws IllegalArgumentException when a size is not a protocol size point, or sizes repeat or are unordered
     * @throws CorpusValidationException when a point corpus violates its bounds or invariants
     */
    static Family of(HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet, List<Integer> sizes, long seed) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(querySet, "querySet must not be null");
        requireSizes(sizes);
        var points = new ArrayList<Point>(sizes.size());
        for (var size : sizes) {
            points.add(point(base, querySet, size, seed));
        }
        // The curve population is fixed by every protocol size point, not by the requested subset, so a subset run
        // scores each point over the same queries as a full run.
        var excluded = new LinkedHashSet<String>();
        for (var size : SIZES) {
            excluded.addAll(removed(base, querySet, size));
        }
        var curve = querySet.queries().stream().filter(query -> !excluded.contains(query.queryId())).toList();
        return new Family(base, querySet, seed, points, curve, List.copyOf(excluded));
    }

    /** Builds one point. */
    static Point point(HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet, int size, long seed) {
        var corpus = corpus(base, size, seed);
        var removed = removed(base, querySet, size);
        var eligible = querySet.queries().stream().filter(query -> !removed.contains(query.queryId())).toList();
        var baseChunks = Math.min(size, base.chunks().size());
        return new Point(size, corpus, baseChunks, size - baseChunks, eligible, removed);
    }

    /**
     * Returns the ids of the queries removed at size {@code size}, in query-set order: those whose relevant set names a
     * base chunk outside the point's base prefix. Relevant chunks are always base chunks (generated distractors are
     * irrelevant by construction), so this needs no generated corpus.
     */
    static List<String> removed(HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet, int size) {
        var present = base.chunks().subList(0, Math.min(size, base.chunks().size())).stream()
                .map(CorpusChunk::chunkId).collect(Collectors.toSet());
        return querySet.queries().stream().filter(query -> !present.containsAll(query.relevant()))
                .map(JudgedQuery::queryId).toList();
    }

    /**
     * Builds and validates the corpus {@code html-kc-v1-scale-<size>}: the first {@code min(size, B)} base chunks in
     * {@code chunkId} order with their documents, then the generated distractors.
     */
    static HtmlKnowledgeCorpus corpus(HtmlKnowledgeCorpus base, int size, long seed) {
        var baseChunks = base.chunks().subList(0, Math.min(size, base.chunks().size()));
        Set<String> kept = baseChunks.stream().map(CorpusChunk::documentId).collect(Collectors.toSet());
        var documents = new ArrayList<CorpusDocument>(base.documents().stream()
                .filter(document -> kept.contains(document.documentId())).toList());
        var chunks = new ArrayList<CorpusChunk>(baseChunks);
        for (var distractor : new ScalingDistractorGenerator().generate(size - baseChunks.size(), seed)) {
            documents.add(distractor.document());
            chunks.add(distractor.chunk());
        }
        return HtmlKnowledgeCorpus.of(version(size), CorpusBounds.SCALING, documents, chunks);
    }

    private static void requireSizes(List<Integer> sizes) {
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("at least one size point is required");
        }
        for (var i = 0; i < sizes.size(); i++) {
            if (!SIZES.contains(sizes.get(i))) {
                throw new IllegalArgumentException("size " + sizes.get(i) + " is not one of " + SIZES);
            }
            if (i > 0 && sizes.get(i) <= sizes.get(i - 1)) {
                throw new IllegalArgumentException("sizes must be distinct and ascending: " + sizes);
            }
        }
    }
}
