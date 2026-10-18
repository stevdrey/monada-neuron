package monada.neuron.evaluation.knowledge;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.adapter.RecalledSignalDecoder;
import monada.neuron.resonance.adapter.SignalQueryEncoder;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluation-only Signal codec of the persistence experiment (Issue #106).
 *
 * <p>The <em>encoder</em> is a bounded lookup from a probe's query Signal (keyed by frequency only) to the probe's
 * query text. The <em>decoder</em> is identity-preserving: it hashes the recalled content exactly as the Store
 * returned it, with no re-normalization, and maps that SHA-256 to a Signal unique to the corpus chunk with that
 * {@code sha256}; content of no corpus chunk is rejected, which the adapter reports as {@code FAILED} instead of
 * inventing a match. {@link #chunkIdOf(Signal)} inverts the decoder so every recalled Signal can be traced to a chunk
 * independently of the adapter's opaque reference.
 *
 * <p>This is a lookup codec. The protocol forbids lookup codecs for L2 retrieval-quality metrics; here it only
 * attributes recalled Signals to persisted atoms and compares runs. It holds no query-to-answer table.
 */
final class ChunkTextProbeCodec implements SignalQueryEncoder, RecalledSignalDecoder {

    /** Codec name recorded in the report. */
    static final String NAME = "chunk-text-probe";

    /** Codec version recorded in the report; bump with any change to the mapping below. */
    static final String VERSION = "1";

    /** Query text for a Signal of no probe; it matches no corpus text. */
    static final String UNMAPPED_QUERY = "unmapped";

    private static final double QUERY_BASE_FREQUENCY = 1000.0;
    private static final double DECODED_BASE_FREQUENCY = 10.0;
    private static final double DECODED_FREQUENCY_STEP = 0.5;

    private final Map<Double, String> queryByFrequency = new HashMap<>();
    private final Map<String, Signal> querySignalByProbe = new HashMap<>();
    private final Map<String, Signal> signalBySha256 = new HashMap<>();
    private final Map<Signal, String> chunkIdBySignal = new HashMap<>();

    /** Builds the codec for {@code corpus} and its bounded {@code probes}. */
    ChunkTextProbeCodec(HtmlKnowledgeCorpus corpus, List<PersistenceProbes.Probe> probes) {
        Objects.requireNonNull(corpus, "corpus must not be null");
        if (probes.size() > PersistenceProbes.MAX_PROBES) {
            throw new IllegalArgumentException("too many probes: " + probes.size());
        }
        for (var index = 0; index < probes.size(); index++) {
            var probe = probes.get(index);
            var signal = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, QUERY_BASE_FREQUENCY + index, 0.0));
            queryByFrequency.put(signal.frequencyState().frequency(), probe.query());
            querySignalByProbe.put(probe.probeId(), signal);
        }
        var chunks = corpus.chunks();
        for (var index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            var signal = new Signal(SignalKind.INTERMEDIATE,
                    new FrequencyState(0.5, DECODED_BASE_FREQUENCY + index * DECODED_FREQUENCY_STEP, 0.0));
            if (signalBySha256.put(chunk.sha256(), signal) != null) {
                throw new IllegalArgumentException("two chunks share sha256 " + chunk.sha256());
            }
            chunkIdBySignal.put(signal, chunk.chunkId());
        }
    }

    /** Returns a description of the codec's parameters for the report. */
    static String describe() {
        return NAME + " v" + VERSION + ": query = probe text keyed by Signal frequency " + QUERY_BASE_FREQUENCY
                + "+i; decode = sha256(recalled content as returned) -> INTERMEDIATE(0.5, " + DECODED_BASE_FREQUENCY
                + "+" + DECODED_FREQUENCY_STEP + "*chunkIndex, 0.0); unknown content rejected";
    }

    /** Returns the query Signal of a probe. */
    Signal querySignal(String probeId) {
        var signal = querySignalByProbe.get(probeId);
        if (signal == null) {
            throw new IllegalArgumentException("unknown probe " + probeId);
        }
        return signal;
    }

    @Override
    public String encode(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        return queryByFrequency.getOrDefault(signal.frequencyState().frequency(), UNMAPPED_QUERY);
    }

    @Override
    public Signal decode(String content) {
        Objects.requireNonNull(content, "content must not be null");
        var signal = signalBySha256.get(CorpusDigests.sha256(content));
        if (signal == null) {
            throw new IllegalArgumentException("recalled content hashes to no chunk of the corpus");
        }
        return signal;
    }

    /** Returns the chunk id a decoded Signal stands for, if this codec produced it. */
    Optional<String> chunkIdOf(Signal signal) {
        return Optional.ofNullable(chunkIdBySignal.get(signal));
    }
}
