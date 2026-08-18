package monada.neuron.monad;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.context.CognitiveContext;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Coordinates one canonical Monad-owned Aeon at a fixed cycle position. */
public final class AeonCognitiveStage implements CognitiveStage {

    private final CognitiveStageKind kind;
    private final Aeon aeon;
    private final UUID startNodeId;
    private final CognitiveAeonCoordinator coordinator;
    private final NodeProcessor processor;
    private final PropagationConfig config;

    /** Creates a stage with explicit Aeon, entry Node, processor, and bounded propagation policy. */
    public AeonCognitiveStage(
            CognitiveStageKind kind,
            Aeon aeon,
            UUID startNodeId,
            CognitiveAeonCoordinator coordinator,
            NodeProcessor processor,
            PropagationConfig config) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.aeon = Objects.requireNonNull(aeon, "aeon must not be null");
        this.startNodeId = Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public CognitiveStageKind kind() {
        return kind;
    }

    @Override
    public void validate(PrimaryMonad monad) {
        Objects.requireNonNull(monad, "monad must not be null");
        var requiredPurpose = kind.aeonPurpose().orElseThrow(() -> new IllegalArgumentException(
                "stage does not accept an Aeon implementation: " + kind));
        if (!monad.isCanonicalAeon(aeon)) {
            throw new IllegalArgumentException(
                    "stage Aeon must be a canonical Monad registration: " + aeon.getId());
        }
        if (aeon.getPurpose() != requiredPurpose) {
            throw new IllegalArgumentException(
                    "stage " + kind + " requires Aeon purpose " + requiredPurpose
                            + ", got: " + aeon.getPurpose());
        }
        if (!aeon.containsMember(startNodeId)) {
            throw new IllegalArgumentException(
                    "startNodeId must identify a member of stage Aeon: " + startNodeId);
        }
    }

    @Override
    public CognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        validate(monad);
        var stableInputs = List.copyOf(Objects.requireNonNull(
                inputSignals,
                "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");

        var aeonInputs = new ArrayList<AeonInput>(stableInputs.size());
        for (var signal : stableInputs) {
            aeonInputs.add(new AeonInput(startNodeId, signal));
        }

        AeonCoordinationResult coordinationResult = coordinator.coordinate(
                aeon,
                aeonInputs,
                processor,
                config,
                context);
        var outputs = new ArrayList<Signal>();
        boolean limitReached = false;
        for (var inputResult : coordinationResult.inputResults()) {
            var propagationResult = inputResult.propagationResult();
            outputs.addAll(propagationResult.emittedSignals());
            limitReached |= propagationResult.stepLimitReached() || propagationResult.hopLimitReached();
        }
        return new AeonCognitiveStageResult(
                kind,
                limitReached ? CognitiveStageStatus.LIMIT_REACHED : CognitiveStageStatus.COMPLETED,
                aeon.getId(),
                coordinationResult,
                outputs);
    }
}
