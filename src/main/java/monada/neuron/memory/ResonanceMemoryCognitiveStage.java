package monada.neuron.memory;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Optional cognitive stage that enriches ordered input Signals with bounded recalled matches. */
public final class ResonanceMemoryCognitiveStage implements CognitiveStage {

    private final ResonanceMemoryPort memoryPort;
    private final int maxResults;

    /** Creates a memory-recall stage with one explicit result limit for each batch request. */
    public ResonanceMemoryCognitiveStage(ResonanceMemoryPort memoryPort, int maxResults) {
        this.memoryPort = Objects.requireNonNull(memoryPort, "memoryPort must not be null");
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive, got: " + maxResults);
        }
        this.maxResults = maxResults;
    }

    /** Returns the fixed memory-recall position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.MEMORY_RECALL;
    }

    /** Recalls one batch and appends adapter-ordered matches after the original Signals. */
    @Override
    public ResonanceMemoryStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        var stableInputs = List.copyOf(Objects.requireNonNull(
                inputSignals,
                "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        var request = new ResonanceMemoryRequest(stableInputs, maxResults);
        var response = Objects.requireNonNull(memoryPort.recall(request), "memory response must not be null");
        if (response.resultLimit() != request.maxResults()) {
            throw new IllegalArgumentException(
                    "memory response resultLimit must match request maxResults");
        }

        var outputs = new ArrayList<Signal>(stableInputs.size() + response.results().size());
        outputs.addAll(stableInputs);
        for (var result : response.results()) {
            outputs.add(result.signal());
        }
        return new ResonanceMemoryStageResult(response, outputs);
    }
}
