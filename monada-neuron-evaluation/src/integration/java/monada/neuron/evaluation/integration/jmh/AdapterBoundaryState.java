package monada.neuron.evaluation.integration.jmh;

import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import monada.neuron.evaluation.integration.jmh.AdapterBoundaryScenario.DuplicateProfile;

/**
 * Per-thread JMH state shared by the merge and boundary benchmarks. Fixture construction and the
 * semantic validation run once per trial, outside any measured method.
 */
@State(Scope.Thread)
public class AdapterBoundaryState {

    @Param({"1", "3", "8"})
    public int querySignals;

    @Param({"1", "5", "10", "32"})
    public int maxResults;

    @Param({"NONE", "MODERATE", "HIGH"})
    public String duplicates;

    ResonanceMemoryRequest request;
    ResonanceStoreMemoryAdapter mergeOnlyAdapter;
    ResonanceStoreMemoryAdapter boundaryAdapter;

    @Setup(Level.Trial)
    public void setUp() {
        var scenario = new AdapterBoundaryScenario(querySignals, maxResults, DuplicateProfile.valueOf(duplicates));
        request = scenario.request();
        mergeOnlyAdapter = scenario.mergeOnlyAdapter();
        boundaryAdapter = scenario.boundaryAdapter();

        var oracle = new AdapterBoundaryOracle();
        var firstMerge = mergeOnlyAdapter.recall(request);
        oracle.validate(scenario, firstMerge);
        // The same request must yield identical, stable output on every call.
        if (!firstMerge.equals(mergeOnlyAdapter.recall(request))) {
            throw new IllegalStateException("Merge-only adapter output is not stable across recalls");
        }
        var firstBoundary = boundaryAdapter.recall(request);
        oracle.validate(scenario, firstBoundary);
        if (!firstBoundary.equals(boundaryAdapter.recall(request))) {
            throw new IllegalStateException("Boundary adapter output is not stable across recalls");
        }
    }
}
