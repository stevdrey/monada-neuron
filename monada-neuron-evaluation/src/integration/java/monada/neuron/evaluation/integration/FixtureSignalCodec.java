package monada.neuron.evaluation.integration;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.adapter.RecalledSignalDecoder;
import monada.neuron.resonance.adapter.SignalQueryEncoder;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fixture-specific Signal codec used by the integration evaluation.
 *
 * <p>Queries are keyed by Signal frequency only, so the same probe still resolves after cycle stages
 * rewrite kind, amplitude, or phase. Recalled documents decode to unique Signals whose labels can be
 * recovered for assertions; this reverse lookup is evaluation-only and never enters cognition.
 */
public final class FixtureSignalCodec implements SignalQueryEncoder, RecalledSignalDecoder {

    /** Query text used when a Signal has no fixture mapping (matches nothing in the corpus). */
    static final String UNMAPPED_QUERY = "unmapped";

    private static final double DECODED_BASE_FREQUENCY = 10.0;
    private static final double DECODED_FREQUENCY_STEP = 0.5;

    private final Map<Double, String> queryTextByFrequency = new HashMap<>();
    private final Map<String, Signal> signalByContent = new HashMap<>();
    private final Map<Signal, String> labelBySignal = new HashMap<>();

    /** Builds the codec for the versioned corpus and query set. */
    public FixtureSignalCodec() {
        ResonanceStoreFixtureCorpus.validate();
        for (var query : ResonanceStoreFixtureCorpus.queries()) {
            queryTextByFrequency.put(query.signal().frequencyState().frequency(), query.text());
        }
        var documents = ResonanceStoreFixtureCorpus.documents();
        for (var index = 0; index < documents.size(); index++) {
            var document = documents.get(index);
            var signal = new Signal(
                    SignalKind.INTERMEDIATE,
                    new FrequencyState(0.5, DECODED_BASE_FREQUENCY + index * DECODED_FREQUENCY_STEP, 0.0));
            signalByContent.put(document.text(), signal);
            labelBySignal.put(signal, document.label());
        }
    }

    @Override
    public String encode(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        return queryTextByFrequency.getOrDefault(signal.frequencyState().frequency(), UNMAPPED_QUERY);
    }

    @Override
    public Signal decode(String content) {
        var signal = signalByContent.get(Objects.requireNonNull(content, "content must not be null"));
        if (signal == null) {
            throw new IllegalArgumentException("content is not part of fixture corpus " + ResonanceStoreFixtureCorpus.VERSION);
        }
        return signal;
    }

    /** Returns the document label for a decoded Signal, if it came from this codec. */
    public Optional<String> labelOf(Signal signal) {
        return Optional.ofNullable(labelBySignal.get(signal));
    }
}
