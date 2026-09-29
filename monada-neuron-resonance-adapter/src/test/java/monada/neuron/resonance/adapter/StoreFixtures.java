package monada.neuron.resonance.adapter;

import com.monada.api.MonadaMemory;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Deterministic seeded-store fixtures shared by adapter tests. */
final class StoreFixtures {

    static final Signal SOLAR = signal(1.0);
    static final Signal OCEAN = signal(2.0);
    static final Signal BLANK = signal(3.0);

    private static final Map<Signal, String> QUERIES = Map.of(
            SOLAR, "solar energy",
            OCEAN, "ocean tide",
            BLANK, "   ");

    private static final List<String> CORPUS = List.of(
            "solar panels convert sunlight into energy",
            "solar wind storms disturb satellites",
            "solar energy storage uses batteries",
            "ocean tide follows the moon",
            "ocean currents move warm water");

    private StoreFixtures() {
    }

    static Signal signal(double frequency) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, frequency, 0.0));
    }

    /** Test encoder mapping the fixture signals to fixed store queries. */
    static SignalQueryEncoder encoder() {
        return signal -> QUERIES.getOrDefault(signal, "unmapped");
    }

    static ResonanceStoreAdapterConfig config() {
        var defaults = ResonanceStoreAdapterConfig.defaults();
        return new ResonanceStoreAdapterConfig(
                encoder(), defaults.signalDecoder(), defaults.memoryOptions(), defaults.threshold());
    }

    /** Opens a store at the path, seeds the corpus directly through the store API, and returns it. */
    static MonadaMemory seed(Path directory) {
        var memory = MonadaMemory.open(directory);
        CORPUS.forEach(memory::remember);
        return memory;
    }
}
