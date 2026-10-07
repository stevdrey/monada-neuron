package monada.neuron.evaluation.integration.jmh;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.adapter.RecalledSignalDecoder;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterConfig;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;
import monada.neuron.resonance.adapter.SignalQueryEncoder;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic, store-free workload for the adapter-boundary benchmarks (Issue #49).
 *
 * <p>Each query Signal owns a prebuilt, best-first list of exactly {@code maxResults} store results,
 * so the candidate count is {@code querySignals x maxResults}, the bound the adapter contract allows.
 * Scores are shaped so that consecutive ranks of one Signal tie (store-rank tie-breaker) and distinct
 * query Signals tie on equal ranks (signal-order tie-breaker). {@link DuplicateProfile} controls how many ranks resolve
 * to the same atom across query Signals.
 *
 * <p>Everything is built eagerly in the constructor so benchmarks keep construction out of the
 * measured path. No disk I/O and no {@code MonadaMemory} is involved.
 */
public final class AdapterBoundaryScenario {

    /** Share of store ranks whose atom is common to every query Signal. */
    public enum DuplicateProfile {
        /** Every candidate is a distinct atom. */
        NONE,
        /** Even store ranks are shared across query Signals. */
        MODERATE,
        /** Every store rank is shared, so the merge collapses to {@code maxResults} atoms. */
        HIGH;

        boolean shared(int rank) {
            return switch (this) {
                case NONE -> false;
                case MODERATE -> rank % 2 == 0;
                case HIGH -> true;
            };
        }
    }

    /** Recalled content length used when a benchmark does not parameterize it. */
    public static final int DEFAULT_CONTENT_LENGTH = 64;

    private static final String CONSTANT_QUERY = "query";
    static final Signal CONSTANT_SIGNAL =
            new Signal(SignalKind.INTERMEDIATE, new FrequencyState(0.5, 10.0, 0.0));

    private final int querySignals;
    private final int maxResults;
    private final DuplicateProfile duplicates;
    private final List<Signal> signals;
    private final List<List<ResonanceResult>> storeResults;
    private final ResonanceMemoryRequest request;

    /** Builds the scenario for the query count, result limit, and duplicate distribution. */
    public AdapterBoundaryScenario(int querySignals, int maxResults, DuplicateProfile duplicates) {
        this.querySignals = querySignals;
        this.maxResults = maxResults;
        this.duplicates = duplicates;
        this.signals = buildSignals(querySignals);
        this.storeResults = buildStoreResults(querySignals, maxResults, duplicates);
        this.request = new ResonanceMemoryRequest(signals, maxResults);
    }

    public int querySignals() {
        return querySignals;
    }

    public int maxResults() {
        return maxResults;
    }

    public DuplicateProfile duplicates() {
        return duplicates;
    }

    public List<Signal> signals() {
        return signals;
    }

    public ResonanceMemoryRequest request() {
        return request;
    }

    /** Store results the substitute store returns for the query Signal at the index. */
    public List<ResonanceResult> storeResults(int signalIndex) {
        return storeResults.get(signalIndex);
    }

    /** Number of distinct atoms across all query Signals. */
    public int distinctAtoms() {
        return (int) storeResults.stream()
                .flatMap(List::stream)
                .map(result -> result.atom().id())
                .distinct()
                .count();
    }

    /**
     * Returns a substitute store that answers the query Signals in order, cycling every
     * {@code querySignals} calls. It is stateful and for single-threaded benchmark use only; the
     * adapter issues exactly one store recall per query Signal, in Signal order.
     */
    public CursorStore cursorStore() {
        return new CursorStore(storeResults);
    }

    /** Adapter with trivial codecs, isolating bounded merge, references, and result construction. */
    public ResonanceStoreMemoryAdapter mergeOnlyAdapter(CursorStore store) {
        SignalQueryEncoder encoder = signal -> CONSTANT_QUERY;
        RecalledSignalDecoder decoder = content -> CONSTANT_SIGNAL;
        return ResonanceStoreMemoryAdapter.using(store, config(encoder, decoder));
    }

    /** Adapter with the production default codecs: all Neuron-owned work, no store. */
    public ResonanceStoreMemoryAdapter boundaryAdapter(CursorStore store) {
        var defaults = ResonanceStoreAdapterConfig.defaults();
        return ResonanceStoreMemoryAdapter.using(store, config(defaults.queryEncoder(), defaults.signalDecoder()));
    }

    /** {@link #mergeOnlyAdapter(CursorStore)} over a fresh substitute store. */
    public ResonanceStoreMemoryAdapter mergeOnlyAdapter() {
        return mergeOnlyAdapter(cursorStore());
    }

    /** {@link #boundaryAdapter(CursorStore)} over a fresh substitute store. */
    public ResonanceStoreMemoryAdapter boundaryAdapter() {
        return boundaryAdapter(cursorStore());
    }

    /** Query the merge-only adapter's constant encoder produces for every Signal. */
    public static String constantQuery() {
        return CONSTANT_QUERY;
    }

    /**
     * Substitute store answering from prebuilt lists. It retains the last query it received so the
     * encoder's output has an observable effect (the string escapes to the heap and cannot be
     * optimized away) and so setup can verify the encoder actually fed the store.
     */
    public static final class CursorStore implements ResonanceStoreRecall {

        private final List<List<ResonanceResult>> results;
        private int cursor;
        private String lastQuery;

        private CursorStore(List<List<ResonanceResult>> results) {
            this.results = results;
        }

        @Override
        public List<ResonanceResult> recall(String query, int limit, double threshold) {
            lastQuery = query;
            var current = results.get(cursor);
            cursor = cursor + 1 == results.size() ? 0 : cursor + 1;
            return current;
        }

        /** Query received by the most recent store call, or {@code null} before the first call. */
        public String lastQuery() {
            return lastQuery;
        }
    }

    /** Production default codecs, for oracles that need the same translation. */
    public ResonanceStoreAdapterConfig defaultConfig() {
        return ResonanceStoreAdapterConfig.defaults();
    }

    private ResonanceStoreAdapterConfig config(SignalQueryEncoder encoder, RecalledSignalDecoder decoder) {
        return new ResonanceStoreAdapterConfig(encoder, decoder, MonadaMemoryOptions.defaults(), 0.0);
    }

    private static List<Signal> buildSignals(int count) {
        var signals = new ArrayList<Signal>(count);
        for (var index = 0; index < count; index++) {
            signals.add(new Signal(
                    SignalKind.OBSERVATION,
                    new FrequencyState(0.5 + 0.01 * index, 100.0 + index, 0.1 * index)));
        }
        return List.copyOf(signals);
    }

    private static List<List<ResonanceResult>> buildStoreResults(
            int querySignals, int maxResults, DuplicateProfile duplicates) {
        var perSignal = new ArrayList<List<ResonanceResult>>(querySignals);
        for (var signalIndex = 0; signalIndex < querySignals; signalIndex++) {
            var results = new ArrayList<ResonanceResult>(maxResults);
            for (var rank = 0; rank < maxResults; rank++) {
                var atomKey = duplicates.shared(rank) ? "shared-" + rank : "atom-" + signalIndex + "-" + rank;
                // Consecutive ranks share a score (store-rank ties within a Signal), and Signals with
                // the same index modulo 3 tie exactly on equal ranks (signal-order ties).
                var score = 1.0 - (rank / 2) * 0.01 - (signalIndex % 3) * 0.002;
                results.add(new ResonanceResult(atom(atomKey), score));
            }
            perSignal.add(List.copyOf(results));
        }
        return List.copyOf(perSignal);
    }

    /** Builds an atom whose id mirrors the store's name-based UUID ids. */
    static KnowledgeAtom atom(String key) {
        var id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new KnowledgeAtom(id, AtomType.TEXT, content(key, DEFAULT_CONTENT_LENGTH), Map.of(), 1.0, Instant.EPOCH);
    }

    /** Deterministic ASCII content of exactly {@code length} characters derived from the key. */
    static String content(String key, int length) {
        var builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append(key).append(' ');
        }
        builder.setLength(length);
        return builder.toString();
    }
}
