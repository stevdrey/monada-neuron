package monada.neuron.resonance.adapter;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded production {@link ResonanceMemoryPort} backed by the Monada Resonance Store {@code
 * monada-api}.
 *
 * <p>The adapter owns exactly one {@link MonadaMemory} opened once for its lifetime and never
 * reopens it per recall. Each query Signal issues one bounded {@code topK(maxResults)} store query;
 * candidates are merged deterministically by score descending, then query Signal order, then store
 * rank, deduplicated by recalled atom, and cut to {@code maxResults}. Ranking, encoding, and
 * persistence remain store responsibilities.
 *
 * <p>Failure translation: a closed adapter answers {@link ResonanceMemoryStatus#UNAVAILABLE}; store
 * I/O failures and malformed store output answer {@link ResonanceMemoryStatus#FAILED}. Recall is
 * all-or-nothing and synchronous, so {@code PARTIAL} and {@code TIMED_OUT} are never produced. Any
 * other runtime exception is an unexpected operational failure and propagates. Instances are safe
 * for concurrent recalls only if the underlying store supports concurrent reads.
 */
public final class ResonanceStoreMemoryAdapter implements ResonanceMemoryPort, AutoCloseable {

    private final ResonanceStoreRecall storeRecall;
    private final ResonanceStoreAdapterConfig config;
    private final OpaqueReference opaqueReference = new OpaqueReference();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ResonanceStoreMemoryAdapter(ResonanceStoreRecall storeRecall, ResonanceStoreAdapterConfig config) {
        this.storeRecall = storeRecall;
        this.config = config;
    }

    /** Opens the store at the path once and binds the adapter to it. */
    public static ResonanceStoreMemoryAdapter open(Path storePath, ResonanceStoreAdapterConfig config) {
        Objects.requireNonNull(storePath, "storePath must not be null");
        var stableConfig = Objects.requireNonNull(config, "config must not be null");
        try {
            return using(MonadaMemory.open(storePath, stableConfig.memoryOptions()), stableConfig);
        } catch (RuntimeException e) {
            throw new ResonanceStoreAdapterException("Cannot open Resonance Store at " + storePath, e);
        }
    }

    /** Binds the adapter to an already-open store whose lifetime remains with the caller. */
    public static ResonanceStoreMemoryAdapter using(
            MonadaMemory memory,
            ResonanceStoreAdapterConfig config) {
        Objects.requireNonNull(memory, "memory must not be null");
        return using(
                (query, limit, threshold) -> memory.resonate(query)
                        .topK(limit)
                        .threshold(threshold)
                        .execute()
                        .results(),
                config);
    }

    /**
     * Binds the adapter to an arbitrary store-side recall operation. This is the seam that lets the
     * Neuron-owned translation and merge work run without a real store.
     */
    public static ResonanceStoreMemoryAdapter using(
            ResonanceStoreRecall storeRecall,
            ResonanceStoreAdapterConfig config) {
        return new ResonanceStoreMemoryAdapter(
                Objects.requireNonNull(storeRecall, "storeRecall must not be null"),
                Objects.requireNonNull(config, "config must not be null"));
    }

    /** Recalls a bounded, deterministically ordered result set for the request. */
    @Override
    public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var limit = request.maxResults();
        if (closed.get()) {
            return emptyResponse(ResonanceMemoryStatus.UNAVAILABLE, limit);
        }
        try {
            return new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE, limit, select(request));
        } catch (UncheckedIOException | MalformedStoreOutputException e) {
            return emptyResponse(ResonanceMemoryStatus.FAILED, limit);
        }
    }

    /** Marks the adapter unavailable; the store API has no close operation to invoke. */
    @Override
    public void close() {
        closed.set(true);
    }

    private List<ResonanceMemoryResult> select(ResonanceMemoryRequest request) {
        var limit = request.maxResults();
        var bestByAtom = new HashMap<String, Candidate>();
        var querySignals = request.querySignals();
        for (var signalIndex = 0; signalIndex < querySignals.size(); signalIndex++) {
            var query = config.queryEncoder().encode(querySignals.get(signalIndex));
            var storeResults = storeRecall.recall(query, limit, config.threshold());
            for (var rank = 0; rank < storeResults.size(); rank++) {
                var candidate = candidate(storeResults.get(rank), signalIndex, rank);
                bestByAtom.merge(candidate.atomId(), candidate, this::better);
            }
        }
        return topByOrder(bestByAtom.values(), limit);
    }

    private Candidate candidate(ResonanceResult result, int signalIndex, int rank) {
        var score = result.score();
        if (!Double.isFinite(score)) {
            throw new MalformedStoreOutputException();
        }
        var atom = result.atom();
        return new Candidate(atom.id(), atom.content(), score, signalIndex, rank);
    }

    private Candidate better(Candidate left, Candidate right) {
        return ORDER.compare(left, right) <= 0 ? left : right;
    }

    private List<ResonanceMemoryResult> topByOrder(Iterable<Candidate> candidates, int limit) {
        // Head is the worst retained candidate so a full heap evicts in O(log limit).
        var retained = new PriorityQueue<Candidate>(Math.min(limit, 64) + 1, ORDER.reversed());
        for (var candidate : candidates) {
            retained.add(candidate);
            if (retained.size() > limit) {
                retained.poll();
            }
        }
        var ordered = new ArrayList<>(retained);
        ordered.sort(ORDER);
        var results = new ArrayList<ResonanceMemoryResult>(ordered.size());
        for (var candidate : ordered) {
            results.add(toResult(candidate));
        }
        return results;
    }

    private ResonanceMemoryResult toResult(Candidate candidate) {
        try {
            return new ResonanceMemoryResult(
                    opaqueReference.of(candidate.atomId()),
                    config.signalDecoder().decode(candidate.content()),
                    candidate.score());
        } catch (IllegalArgumentException e) {
            throw new MalformedStoreOutputException();
        }
    }

    private ResonanceMemoryResponse emptyResponse(ResonanceMemoryStatus status, int limit) {
        return new ResonanceMemoryResponse(status, limit, List.of());
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparingDouble(Candidate::score).reversed()
            .thenComparingInt(Candidate::signalIndex)
            .thenComparingInt(Candidate::rank);

    private record Candidate(String atomId, String content, double score, int signalIndex, int rank) {
    }

    private static final class MalformedStoreOutputException extends RuntimeException {
        MalformedStoreOutputException() {
            super("Resonance Store produced output that violates the Neuron memory contract", null, false, false);
        }
    }
}
