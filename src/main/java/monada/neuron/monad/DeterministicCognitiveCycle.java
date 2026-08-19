package monada.neuron.monad;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveCycleSnapshot;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sequential reference lifecycle that executes optional stages in their canonical enum order.
 *
 * <p>The cycle owns one {@link CognitiveContext}. A stage sees signals from its direct predecessor
 * only, and receives them in the predecessor's declared output order. A truncated stage ends the
 * cycle before later stages execute; global context-budget exhaustion takes precedence when both
 * kinds of truncation are observed.
 */
public final class DeterministicCognitiveCycle implements CognitiveCycle {

    private final List<CognitiveStage> stages;

    /**
     * Creates an immutable stage plan normalized to {@link CognitiveStageKind}'s canonical order.
     *
     * @param stages optional unique stages; an empty plan is a successful pass-through cycle
     */
    public DeterministicCognitiveCycle(List<CognitiveStage> stages) {
        Objects.requireNonNull(stages, "stages must not be null");
        Map<CognitiveStageKind, CognitiveStage> stagesByKind = new EnumMap<>(CognitiveStageKind.class);
        for (var stage : stages) {
            Objects.requireNonNull(stage, "stages must not contain null");
            var previous = stagesByKind.putIfAbsent(
                    Objects.requireNonNull(stage.kind(), "stage kind must not be null"),
                    stage);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate cognitive stage: " + stage.kind());
            }
        }
        this.stages = List.copyOf(stagesByKind.values());
    }

    /** Executes all configured stages in canonical order over one fresh bounded context. */
    @Override
    public CognitiveCycleResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveBudget budget) {
        Objects.requireNonNull(monad, "monad must not be null");
        var stableInputs = List.copyOf(Objects.requireNonNull(
                inputSignals,
                "inputSignals must not be null"));
        Objects.requireNonNull(budget, "budget must not be null");
        validateBindings(monad);

        var context = new CognitiveContext(budget);
        var stageResults = new ArrayList<CognitiveStageResult>(stages.size());
        var currentSignals = stableInputs;
        CognitiveStage currentStage = null;
        try {
            if (stages.isEmpty()) {
                return complete(
                        monad,
                        CognitiveCycleTermination.COMPLETED,
                        stageResults,
                        currentSignals,
                        context);
            }

            for (var stage : stages) {
                currentStage = stage;
                if (currentSignals.isEmpty()) {
                    return complete(
                            monad,
                            CognitiveCycleTermination.NO_SIGNALS,
                            stageResults,
                            currentSignals,
                            context);
                }

                var stageInputs = stage instanceof AeonCognitiveStage
                        ? currentSignals
                        : admitStageInputs(stage.kind(), currentSignals, context);
                if (stageInputs.isEmpty()) {
                    return complete(
                            monad,
                            CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                            stageResults,
                            currentSignals,
                            context);
                }

                context.recordCognitiveStageStarted(stage.kind());
                var stageResult = Objects.requireNonNull(
                        stage.execute(monad, stageInputs, context),
                        "cognitive stage result must not be null");
                var candidateOutputs = validateStageResult(stage, stageResult);
                if (!context.isActive()) {
                    throw new IllegalStateException("cognitive stages must leave the context active");
                }
                var stableOutputs = stage instanceof AeonCognitiveStage
                        ? candidateOutputs
                        : admitStageOutputs(stage.kind(), candidateOutputs, context);
                var observedStageResult = stage instanceof AeonCognitiveStage
                        ? stageResult
                        : Objects.requireNonNull(
                                stageResult.withAdmittedOutputSignals(stableOutputs),
                                "admitted stage result must not be null");
                if (!stableOutputs.equals(validateStageResult(stage, observedStageResult))) {
                    throw new IllegalArgumentException(
                            "admitted stage result outputs must match the cycle-admitted prefix");
                }
                stageResults.add(observedStageResult);
                context.recordCognitiveStageCompleted(
                        stage.kind(),
                        stageInputs.size(),
                        stableOutputs.size(),
                        observedStageResult.status());
                currentSignals = stableOutputs;

                if (context.stepBudgetExhausted() || context.signalBudgetExhausted()) {
                    return complete(
                            monad,
                            CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                            stageResults,
                            currentSignals,
                            context);
                }
                if (observedStageResult.status() == CognitiveStageStatus.LIMIT_REACHED) {
                    return complete(
                            monad,
                            CognitiveCycleTermination.STAGE_LIMIT_REACHED,
                            stageResults,
                            currentSignals,
                            context);
                }
            }

            return complete(
                    monad,
                    CognitiveCycleTermination.COMPLETED,
                    stageResults,
                    currentSignals,
                    context);
        } catch (RuntimeException failure) {
            if (!context.isActive()) {
                throw failure;
            }
            if (currentStage != null) {
                context.recordCognitiveStageFailed(currentStage.kind());
            }
            CognitiveCycleSnapshot snapshot = context.complete(CognitiveCycleOutcome.FAILURE);
            if (currentStage == null) {
                throw failure;
            }
            throw new CognitiveCycleException(currentStage.kind(), stageResults, snapshot, failure);
        }
    }

    private void validateBindings(PrimaryMonad monad) {
        for (var stage : stages) {
            stage.validate(monad);
        }
    }

    private List<Signal> validateStageResult(CognitiveStage stage, CognitiveStageResult result) {
        if (result.kind() != stage.kind()) {
            throw new IllegalArgumentException(
                    "stage result kind must match executing stage: " + stage.kind());
        }
        Objects.requireNonNull(result.status(), "stage result status must not be null");
        return List.copyOf(Objects.requireNonNull(
                result.outputSignals(),
                "stage result outputSignals must not be null"));
    }

    private List<Signal> admitStageInputs(
            CognitiveStageKind stage,
            List<Signal> candidateInputs,
            CognitiveContext context) {
        var admittedInputs = new ArrayList<Signal>();
        for (var input : candidateInputs) {
            if (context.tryRecordCognitiveStageInputSignal(stage, input).isEmpty()) {
                break;
            }
            admittedInputs.add(input);
        }
        return List.copyOf(admittedInputs);
    }

    private List<Signal> admitStageOutputs(
            CognitiveStageKind stage,
            List<Signal> candidateOutputs,
            CognitiveContext context) {
        var admittedOutputs = new ArrayList<Signal>();
        for (var output : candidateOutputs) {
            if (context.tryRecordCognitiveStageOutputSignal(stage, output).isEmpty()) {
                break;
            }
            admittedOutputs.add(output);
        }
        return List.copyOf(admittedOutputs);
    }

    private CognitiveCycleResult complete(
            PrimaryMonad monad,
            CognitiveCycleTermination termination,
            List<CognitiveStageResult> stageResults,
            List<Signal> outputSignals,
            CognitiveContext context) {
        return new CognitiveCycleResult(
                monad.getId(),
                termination,
                stageResults,
                outputSignals,
                context.complete(CognitiveCycleOutcome.SUCCESS));
    }
}
