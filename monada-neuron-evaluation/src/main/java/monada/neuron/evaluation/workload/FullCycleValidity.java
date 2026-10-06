package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.action.ObservationAdmission;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Semantic validity gate for the evaluation full-cycle workload.
 *
 * <p>A measurement may only be accepted when the cycle really executed all five canonical stages in
 * order, terminated with {@code COMPLETED}, exhausted none of its budgets, and the deterministic memory and
 * action fixtures behaved as specified.
 */
public final class FullCycleValidity {

    private final ResonanceMemoryPort memoryReference;
    private final ActionCapability actionReference;

    /** Creates a gate whose reference behavior is the deterministic memory and action fixtures. */
    public FullCycleValidity() {
        this(new DeterministicMemoryFixture(), new DeterministicActionFixture());
    }

    /**
     * Creates a gate that replays the memory and action stages against the given reference behavior.
     *
     * @param memoryReference reference memory port the recorded memory response must equal
     * @param actionReference reference action capability the recorded action result must equal
     */
    public FullCycleValidity(ResonanceMemoryPort memoryReference, ActionCapability actionReference) {
        this.memoryReference = Objects.requireNonNull(memoryReference, "memoryReference must not be null");
        this.actionReference = Objects.requireNonNull(actionReference, "actionReference must not be null");
    }

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

        var stages = result.stageResults();
        for (int index = 0; index < stages.size(); index++) {
            var previousOutputs = index == 0 ? List.<Signal>of() : stages.get(index - 1).outputSignals();
            switch (stages.get(index)) {
                case ResonanceMemoryStageResult memory -> requireMemory(memory, previousOutputs);
                case ActionCognitiveStageResult action -> requireAction(action, previousOutputs);
                default -> {
                    // Aeon and adaptation stages are covered by order, termination, and budgets.
                }
            }
        }
    }

    private void requireMemory(ResonanceMemoryStageResult memory, List<Signal> previousOutputs) {
        var response = memory.response();
        check(response.status() == ResonanceMemoryStatus.COMPLETE,
                "memory status must be COMPLETE, got " + response.status());
        check(!response.results().isEmpty(), "memory fixture must recall at least one result");
        var expected = memoryReference.recall(new ResonanceMemoryRequest(previousOutputs, response.resultLimit()));
        check(response.equals(expected),
                "memory response must equal the reference memory behavior over the perception output");
    }

    private void requireAction(ActionCognitiveStageResult action, List<Signal> previousOutputs) {
        var outcome = action.outcome();
        check(outcome.result().status() == ActionStatus.SUCCEEDED,
                "action status must be SUCCEEDED, got " + outcome.result().status());
        check(action.admittedObservationCount() > 0, "action fixture must emit observations");
        check(action.observationAdmission() == ObservationAdmission.COMPLETE,
                "action observations must be fully admitted");
        check(outcome.request().inputSignals().equals(previousOutputs),
                "action request must carry the adaptation output");
        check(outcome.result().equals(actionReference.execute(outcome.request())),
                "action result must equal the reference action behavior");
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("invalid full-cycle workload: " + message);
        }
    }
}
