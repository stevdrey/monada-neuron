package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Baseline B0: exact-token overlap over the same normalized chunks the Store holds. A trivial lexical reference, not a
 * search engine: no stemming, synonyms or weighting.
 *
 * <p>A chunk's score is the number of distinct query tokens it contains ({@link HtmlTokens}); chunks scoring
 * {@code 0} are dropped, the rest are ordered by score descending, then {@code chunkId} code point order, and the first
 * {@code limit} are returned. Chunk token sets are built once, so a query costs {@code O(N · |query tokens|)} set
 * lookups plus a sort of the scoring chunks; over {@code Q} queries this is the protocol's scalar {@code O(Q·N)}.
 */
final class LexicalOverlapBaseline {

    /** Baseline id recorded in reports. */
    static final String NAME = "B0";

    private final List<ChunkTokens> chunks;

    /** Builds the baseline over {@code corpus}'s chunks. */
    LexicalOverlapBaseline(HtmlKnowledgeCorpus corpus) {
        Objects.requireNonNull(corpus, "corpus must not be null");
        this.chunks = corpus.chunks().stream()
                .map(chunk -> new ChunkTokens(chunk.chunkId(), Set.copyOf(HtmlTokens.tokens(chunk.text()))))
                .toList();
    }

    /** One scored chunk. */
    record Hit(String chunkId, int score) {
    }

    /** Returns at most {@code limit} chunks with a positive score, best first. */
    List<Hit> search(String query, int limit) {
        Objects.requireNonNull(query, "query must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        var queryTokens = HtmlTokens.distinct(query);
        var hits = new ArrayList<Hit>();
        for (var chunk : chunks) {
            var score = 0;
            for (var token : queryTokens) {
                if (chunk.tokens().contains(token)) {
                    score++;
                }
            }
            if (score > 0) {
                hits.add(new Hit(chunk.chunkId(), score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::chunkId));
        return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
    }

    private record ChunkTokens(String chunkId, Set<String> tokens) {
    }
}
