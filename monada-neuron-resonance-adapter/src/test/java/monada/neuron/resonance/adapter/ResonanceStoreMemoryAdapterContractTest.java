package monada.neuron.resonance.adapter;

import monada.neuron.memory.ResonanceMemoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Runs the shared port contract against the production adapter over an isolated seeded store. */
final class ResonanceStoreMemoryAdapterContractTest extends ResonanceMemoryPortContractTest {

    @TempDir
    Path directory;

    private ResonanceStoreMemoryAdapter adapter;

    @BeforeEach
    void seedStore() {
        adapter = ResonanceStoreMemoryAdapter.using(StoreFixtures.seed(directory), StoreFixtures.config());
    }

    @Override
    ResonanceMemoryPort port() {
        return adapter;
    }
}
