package monada.neuron.evaluation.integration;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.integration.ResonanceStoreFixtureCorpus.Document;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterConfig;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Isolated, seeded Resonance Store in a fresh temporary directory that is always deleted on close.
 *
 * <p>Seeding is timed once at construction so setup cost is reported separately from recall and cycle
 * measurements. Adapters opened through {@link #openAdapter()} are closed with the store.
 */
public final class TemporaryResonanceStore implements AutoCloseable {

    /** Minimum store score; the store returns zero-scored fillers below it, which would hide "no match". */
    public static final double FIXTURE_THRESHOLD = 0.1;

    private final Path directory;
    private final ResonanceStoreAdapterConfig adapterConfig;
    private final List<ResonanceStoreMemoryAdapter> openedAdapters = new ArrayList<>();
    private final long seedNanos;
    private boolean closed;

    private TemporaryResonanceStore(Path directory, ResonanceStoreAdapterConfig adapterConfig, long seedNanos) {
        this.directory = directory;
        this.adapterConfig = adapterConfig;
        this.seedNanos = seedNanos;
    }

    /** Creates an empty temporary directory, opens the store once, and seeds the fixture corpus. */
    public static TemporaryResonanceStore seeded(FixtureSignalCodec codec) {
        Objects.requireNonNull(codec, "codec must not be null");
        return create(codec, ResonanceStoreFixtureCorpus.documents());
    }

    /** Creates a temporary store seeded with the given documents. */
    public static TemporaryResonanceStore create(FixtureSignalCodec codec, List<Document> documents) {
        var directory = createDirectory();
        try {
            var start = System.nanoTime();
            var memory = MonadaMemory.open(directory);
            for (var document : documents) {
                memory.remember(document.text());
            }
            var seedNanos = System.nanoTime() - start;
            return new TemporaryResonanceStore(directory, adapterConfig(codec), seedNanos);
        } catch (RuntimeException failure) {
            deleteTree(directory);
            throw failure;
        }
    }

    /** Creates an empty temporary directory that does not yet contain a store. */
    public static TemporaryResonanceStore empty(FixtureSignalCodec codec) {
        return new TemporaryResonanceStore(createDirectory(), adapterConfig(codec), 0L);
    }

    /** Returns the adapter configuration bound to the fixture codec and the store default options. */
    public static ResonanceStoreAdapterConfig adapterConfig(FixtureSignalCodec codec) {
        var defaults = ResonanceStoreAdapterConfig.defaults();
        return new ResonanceStoreAdapterConfig(codec, codec, defaults.memoryOptions(), FIXTURE_THRESHOLD);
    }

    /** Returns the store directory; it is deleted when this object closes. */
    public Path path() {
        return directory;
    }

    /** Returns the wall time spent opening the store and remembering every fixture document. */
    public long seedNanos() {
        return seedNanos;
    }

    /** Opens a new adapter on the persisted store, as a restarted process would. */
    public ResonanceStoreMemoryAdapter openAdapter() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
        var adapter = ResonanceStoreMemoryAdapter.open(directory, adapterConfig);
        openedAdapters.add(adapter);
        return adapter;
    }

    /** Closes an adapter opened by this store and stops retaining it. */
    public void release(ResonanceStoreMemoryAdapter adapter) {
        adapter.close();
        openedAdapters.remove(adapter);
    }

    /** Returns the number of adapters this store still retains. */
    public int retainedAdapterCount() {
        return openedAdapters.size();
    }

    /** Returns the raw persisted manifest text for reporting, or an empty string when absent. */
    public String manifestJson() {
        try {
            var manifest = directory.resolve("manifest.json");
            return Files.exists(manifest) ? Files.readString(manifest, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes every regular file under the store's vector directory to simulate storage loss. */
    public void deleteVectorSegments() {
        try (Stream<Path> files = Files.walk(directory.resolve("vectors"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns whether the temporary directory still exists. */
    public boolean exists() {
        return Files.exists(directory);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        openedAdapters.forEach(ResonanceStoreMemoryAdapter::close);
        // Only a completed deletion marks the store closed, so a failed cleanup can be retried.
        deleteTree(directory);
        closed = true;
    }

    private static Path createDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-rs-eval-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
