package monada.neuron.evaluation.knowledge;

import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.resonance.adapter.OpaqueReference;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The labels-free capture of the B2 recall diagnosis (Issue #135): for every query, the {@link TokenPackingSignalCodec}
 * Signals the production path sends, the single-token Store query each one decodes to, and every Store hit of that
 * query at each candidate pool size. Pool {@code k} (1, 3 or 5) is what the adapter asks for at limit {@code k}; pool
 * {@value #EXPANDED_POOL} is a research-only expansion, reported separately as a candidate-recall change.
 *
 * <p>Like {@link HtmlKnowledgeRecall}, its input is {@link QueryText} only, never gold labels. Every Store list goes
 * through {@link HtmlKnowledgeRecall#rejectStoreResults} and the manifest/{@code sha256} source fidelity rule; a throw,
 * a non-finite score, an unmapped atom or changed content marks the whole query failed, so it is never scored as an
 * empty result. Raw atom ids stay inside: a hit carries its mapped chunk id and its opaque reference.
 */
final class SignalHitCapture {

    /** Pool sizes the adapter itself uses: one per evaluated {@code k}. */
    static final List<Integer> PRODUCTION_POOLS = RecallMetrics.KS;

    /** The research-only expanded per-token pool. */
    static final int EXPANDED_POOL = 20;

    /** Every captured pool, ascending. */
    static final List<Integer> POOLS = List.of(1, 3, 5, EXPANDED_POOL);

    /**
     * One Store hit of one query Signal.
     *
     * @param signalIndex the Signal's position in the codec output
     * @param rank 0-based Store rank within that Signal's list
     * @param chunkId the chunk mapped through the manifest
     * @param reference the atom's opaque reference
     * @param score the raw Store score, bit-exact
     */
    record Hit(int signalIndex, int rank, String chunkId, String reference, double score) {
    }

    /**
     * Everything captured for one query.
     *
     * @param queryId the query
     * @param tokens the decoded single-token Store query of each Signal, in Signal order (the empty query for a text
     *     without tokens)
     * @param lists by pool, each Signal's hit list in Signal order; empty when the query failed
     * @param failure why the capture failed, or empty
     */
    record QueryCapture(String queryId, List<String> tokens, Map<Integer, List<List<Hit>>> lists,
            Optional<String> failure) {

        QueryCapture {
            Objects.requireNonNull(queryId, "queryId must not be null");
            tokens = List.copyOf(tokens);
            var copy = new LinkedHashMap<Integer, List<List<Hit>>>();
            lists.forEach((pool, perSignal) -> copy.put(pool, perSignal.stream().map(List::copyOf).toList()));
            lists = Collections.unmodifiableMap(copy);
            Objects.requireNonNull(failure, "failure must not be null");
        }

        /** Returns each Signal's hit list at {@code pool}. */
        List<List<Hit>> at(int pool) {
            var perSignal = lists.get(pool);
            if (perSignal == null) {
                throw new IllegalArgumentException("pool " + pool + " was not captured");
            }
            return perSignal;
        }

        /** Returns whether every Store call of the query completed faithfully. */
        boolean completed() {
            return failure.isEmpty();
        }

        /** Returns the number of Store calls one production B2 recall of this query makes. */
        int storeCalls() {
            return tokens.size();
        }
    }

    private final HtmlKnowledgeCorpus corpus;
    private final IngestionManifest manifest;
    private final ResonanceStoreRecall store;
    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();
    private final OpaqueReference opaqueReference = new OpaqueReference();

    /** Creates the capture over {@code store}, the Store-side recall of the root {@code manifest} describes. */
    SignalHitCapture(HtmlKnowledgeCorpus corpus, IngestionManifest manifest, ResonanceStoreRecall store) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.manifest = Objects.requireNonNull(manifest, "manifest must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** Captures every query, in order. */
    List<QueryCapture> capture(List<QueryText> queries) {
        return queries.stream().map(this::capture).toList();
    }

    /** Captures one query at every pool in {@link #POOLS}. */
    QueryCapture capture(QueryText query) {
        var tokens = new ArrayList<String>();
        try {
            var signals = codec.querySignals(query.text());
            for (var signal : signals) {
                tokens.add(codec.encode(signal));
            }
        } catch (RuntimeException e) {
            return new QueryCapture(query.queryId(), tokens, Map.of(), Optional.of("the codec threw " + e));
        }
        var lists = new LinkedHashMap<Integer, List<List<Hit>>>();
        for (var pool : POOLS) {
            var perSignal = new ArrayList<List<Hit>>(tokens.size());
            for (var signalIndex = 0; signalIndex < tokens.size(); signalIndex++) {
                var where = "token " + (signalIndex + 1) + " pool " + pool + ": ";
                List<ResonanceResult> results;
                try {
                    results = store.recall(tokens.get(signalIndex), pool, HtmlKnowledgeRecall.THRESHOLD);
                } catch (RuntimeException e) {
                    return failed(query, tokens, where + "the Store threw " + e);
                }
                var rejected = HtmlKnowledgeRecall.rejectStoreResults(results);
                if (rejected.isPresent()) {
                    return failed(query, tokens, where + "the Store " + rejected.get());
                }
                if (results.size() > pool) {
                    return failed(query, tokens, where + "the Store returned " + results.size() + " results");
                }
                var hits = new ArrayList<Hit>(results.size());
                for (var rank = 0; rank < results.size(); rank++) {
                    var atom = results.get(rank).atom();
                    var chunkId = manifest.chunkIdForAtom(atom.id());
                    if (chunkId.isEmpty()) {
                        return failed(query, tokens, where + "an atom is not in the manifest");
                    }
                    var chunk = corpus.chunk(chunkId.get());
                    if (chunk.isEmpty() || !CorpusDigests.sha256(atom.content()).equals(chunk.get().sha256())) {
                        return failed(query, tokens, where + "the content of " + chunkId.get()
                                + " does not hash to its corpus sha256");
                    }
                    hits.add(new Hit(signalIndex, rank, chunkId.get(), opaqueReference.of(atom.id()),
                            results.get(rank).score()));
                }
                perSignal.add(hits);
            }
            lists.put(pool, perSignal);
        }
        return new QueryCapture(query.queryId(), tokens, lists, Optional.empty());
    }

    private static QueryCapture failed(QueryText query, List<String> tokens, String reason) {
        return new QueryCapture(query.queryId(), tokens, Map.of(), Optional.of(reason));
    }

    /**
     * Returns the corpus document frequency of every token of the chunk texts: the number of chunks whose distinct
     * tokens contain it. Labels-free (chunk text only); {@code O(total tokens)}.
     */
    static Map<String, Integer> documentFrequency(HtmlKnowledgeCorpus corpus) {
        var frequency = new HashMap<String, Integer>();
        for (var chunk : corpus.chunks()) {
            for (var token : HtmlTokens.distinct(chunk.text())) {
                frequency.merge(token, 1, Integer::sum);
            }
        }
        return Map.copyOf(frequency);
    }
}
