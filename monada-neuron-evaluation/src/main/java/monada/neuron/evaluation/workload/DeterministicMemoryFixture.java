package monada.neuron.evaluation.workload;

import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic memory port fixture for evaluation and benchmarks.
 *
 * <p>Returns synthetic resonant matches based on the input signals without performing network I/O
 * or requiring Monada Resonance Store.
 */
public final class DeterministicMemoryFixture implements ResonanceMemoryPort {

    private final double defaultScore;

    /** Creates a memory fixture with default similarity score of 0.85. */
    public DeterministicMemoryFixture() {
        this(0.85);
    }

    /** Creates a memory fixture with a fixed resonance score. */
    public DeterministicMemoryFixture(double defaultScore) {
        if (defaultScore < 0.0 || defaultScore > 1.0) {
            throw new IllegalArgumentException("defaultScore must be in [0, 1], got: " + defaultScore);
        }
        this.defaultScore = defaultScore;
    }

    @Override
    public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var inputs = request.querySignals();
        int maxResults = request.maxResults();

        if (inputs.isEmpty()) {
            return new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE, maxResults, List.of());
        }

        var matches = new ArrayList<ResonanceMemoryResult>();
        for (int i = 0; i < Math.min(inputs.size(), maxResults); i++) {
            var probe = inputs.get(i);
            var recalledSignal = new Signal(
                    SignalKind.INTERMEDIATE,
                    new FrequencyState(
                            probe.frequencyState().amplitude() * 0.95,
                            probe.frequencyState().frequency() * 1.02,
                            probe.frequencyState().phase() + 0.05));
            matches.add(new ResonanceMemoryResult("mem-" + i, recalledSignal, defaultScore));
        }

        return new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE, maxResults, List.copyOf(matches));
    }
}
