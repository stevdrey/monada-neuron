package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.action.ObservationAdmission;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.signal.SignalKind;

import java.util.Objects;

/**
 * Semantic validity gate for the evaluation full-cycle workload.
 *
 * <p>A measurement may only be accepted when the cycle really executed all five canonical stages in
 * order, terminated with {@code COMPLETED}, exhausted none of its budgets, and the deterministic memory and
 * action fixtures behaved as specified.
 */
public final class FullCycleValidity {

    /**
     * Throws when the result is not a valid complete five-stage cycle.
     *
     * @param result cycle result to validate
     * @throws IllegalStateException with the first violated condition
     */
    public void require(CognitiveCycleResult result) {
        Objects.requireNonNull(result, "result must not be null");
        var kinds = result.stageResults().stream().map(CognitiveStageResult::kind).toList();
        check(kinds.equals(DeterministicWorkloadGenerator.FULL_CYCLE_ORDER),
                "stage order must be " + DeterministicWorkloadGenerator.FULL_CYCLE_ORDER + ", got " + kinds);
        check(result.termination() == CognitiveCycleTermination.COMPLETED,
                "termination must be COMPLETED, got " + result.termination());
        var snapshot = result.snapshot();
        check(!snapshot.stepBudgetExhausted(), "step budget must not be exhausted");
        check(!snapshot.signalBudgetExhausted(), "signal budget must not be exhausted");
        check(!snapshot.traceBudgetExhausted(), "trace budget must not be exhausted");

        for (var stage : result.stageResults()) {
            switch (stage) {
                case ResonanceMemoryStageResult memory -> {
                    check(memory.response().status() == ResonanceMemoryStatus.COMPLETE,
                            "memory status must be COMPLETE, got " + memory.response().status());
                    check(!memory.response().results().isEmpty(), "memory fixture must recall at least one result");
                }
                case ActionCognitiveStageResult action -> {
                    check(action.outcome().result().status() == ActionStatus.SUCCEEDED,
                            "action status must be SUCCEEDED, got " + action.outcome().result().status());
                    check(action.admittedObservationCount() > 0, "action fixture must emit observations");
                    check(action.observationAdmission() == ObservationAdmission.COMPLETE,
                            "action observations must be fully admitted");
                    for (var observation : action.outputSignals()) {
                        check(observation.kind() == SignalKind.OBSERVATION
                                        && observation.frequencyState().phase() == 0.0,
                                "action observations must follow the deterministic fixture semantics");
                    }
                }
                default -> {
                    // Aeon and adaptation stages are covered by order, termination, and budgets.
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("invalid full-cycle workload: " + message);
        }
    }
}
