package monada.neuron.evolution;

import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-cycle A/B and replay checks for the explicit cross-cycle feedback handoff: cycle N acts, the
 * caller derives feedback, and only an explicit hand-over lets cycle N+1 adapt on it.
 */
class FeedbackLoopCycleTest {

    private static final PrimaryMonad MONAD = new PrimaryMonad(uuid(100));
    private static final CognitiveBudget BUDGET = new CognitiveBudget(10, 10, 40);
    private static final int CYCLES = 4;

    private enum Arm {
        /** No feedback is derived or consumed: the existing no-op adaptation reference. */
        CONTROL,
        /** Feedback is derived every cycle but never handed to the next one. */
        DERIVED_NOT_CONSUMED,
        /** Feedback derived in cycle N is consumed by the baseline adaptation of cycle N+1. */
        CONSUMED
    }

    private record Outcome(
            List<FrequencyState> states,
            List<Double> energies,
            List<Integer> historySizes,
            List<OutcomeFeedback> derived,
            List<CognitiveCycleResult> cycles) {
    }

    @Test
    void stateDivergesOnlyWhenFeedbackIsCarriedForwardAndConsumed() {
        var control = run(Arm.CONTROL, ActionStatus.SUCCEEDED);
        var derivedOnly = run(Arm.DERIVED_NOT_CONSUMED, ActionStatus.SUCCEEDED);
        var consumed = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);

        assertAll(
                () -> assertTrue(control.derived().isEmpty()),
                () -> assertEquals(CYCLES, derivedOnly.derived().size()),
                () -> assertEquals(control.states(), derivedOnly.states()),
                () -> assertEquals(control.energies(), derivedOnly.energies()),
                () -> assertEquals(control.historySizes(), derivedOnly.historySizes()),
                () -> assertNotEquals(control.states(), consumed.states()),
                () -> assertNotEquals(control.energies(), consumed.energies()));
    }

    @Test
    void firstCycleHasNoPriorFeedbackSoItNeverAdapts() {
        var consumed = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);

        // Only cycles 2..N adapt; cycle 1 had nothing to consume. Each adapts the single target once.
        assertEquals(List.of(0, 1, 2, 3), consumed.historySizes());
    }

    @Test
    void sameInitialStateAndFeedbackSequenceReplayToTheSameStatesAndResults() {
        var first = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);
        var second = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);

        assertAll(
                () -> assertEquals(first.states(), second.states()),
                () -> assertEquals(first.energies(), second.energies()),
                () -> assertEquals(first.derived(), second.derived()),
                () -> assertEquals(first.cycles(), second.cycles()));
    }

    @Test
    void consumptionIsCorrelatedToTheOriginCycleInTheTrace() {
        var consumed = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);

        var consumptions = consumed.cycles().stream()
                .map(FeedbackLoopCycleTest::feedbackConsumedEvents)
                .toList();

        assertAll(
                () -> assertTrue(consumptions.get(0).isEmpty()),
                () -> assertEquals(
                        List.of(new CognitiveTraceEvent.FeedbackConsumed(
                                0L, ActionStatus.SUCCEEDED, FeedbackDisposition.REINFORCE, 1, 1, 0)),
                        consumptions.get(1)),
                () -> assertEquals(1L, consumptions.get(2).getFirst().originCycleOrdinal()),
                () -> assertEquals(2L, consumptions.get(3).getFirst().originCycleOrdinal()));
    }

    @Test
    void environmentalOutcomesNeverMoveTheStateEvenWhenConsumed() {
        var control = run(Arm.CONTROL, ActionStatus.TIMED_OUT);
        var consumed = run(Arm.CONSUMED, ActionStatus.TIMED_OUT);

        assertAll(
                () -> assertEquals(control.states(), consumed.states()),
                () -> assertEquals(control.energies(), consumed.energies()),
                () -> assertTrue(consumed.derived().stream()
                        .allMatch(feedback -> feedback.disposition() == FeedbackDisposition.NEUTRAL)));
    }

    @Test
    void penaltyAndRewardMoveTheStateInOppositeDirections() {
        var reward = run(Arm.CONSUMED, ActionStatus.SUCCEEDED);
        var penalty = run(Arm.CONSUMED, ActionStatus.FAILED);
        var initial = node().getFrequencyState().amplitude();

        assertAll(
                () -> assertTrue(reward.states().getLast().amplitude() > initial),
                () -> assertTrue(penalty.states().getLast().amplitude() < initial));
    }

    @Test
    void historyStaysWithinTheNodeLimitAcrossManyConsumedCycles() {
        var target = new Node.Builder().id(uuid(1)).type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(2.0, 10.0, 0.0)).energy(1.0).historyLimit(3).build();
        Optional<OutcomeFeedback> pending = Optional.empty();
        var derivation = new DeterministicOutcomeFeedbackPolicy();
        for (long ordinal = 0; ordinal < 20; ordinal++) {
            var result = cycle(target, pending, ActionStatus.SUCCEEDED).execute(
                    MONAD, List.of(signal(1.0)), BUDGET);
            pending = derivation.derive(result, List.of(target.getId()), ordinal);
        }

        assertEquals(3, target.getHistorySize());
    }

    // helpers ---------------------------------------------------------------------------------

    private static Outcome run(Arm arm, ActionStatus status) {
        var target = node();
        var derivation = arm == Arm.CONTROL ? NoOpOutcomeFeedbackPolicy.INSTANCE : new DeterministicOutcomeFeedbackPolicy();
        var states = new ArrayList<FrequencyState>();
        var energies = new ArrayList<Double>();
        var histories = new ArrayList<Integer>();
        var derived = new ArrayList<OutcomeFeedback>();
        var cycles = new ArrayList<CognitiveCycleResult>();
        Optional<OutcomeFeedback> pending = Optional.empty();
        for (long ordinal = 0; ordinal < CYCLES; ordinal++) {
            var result = cycle(target, pending, status).execute(MONAD, List.of(signal(1.0)), BUDGET);
            assertEquals(CognitiveCycleTermination.COMPLETED, result.termination());
            cycles.add(result);
            var next = derivation.derive(result, List.of(target.getId()), ordinal);
            next.ifPresent(derived::add);
            pending = arm == Arm.CONSUMED ? next : Optional.empty();
            states.add(target.getFrequencyState());
            energies.add(target.getEnergy());
            histories.add(target.getHistorySize());
        }
        return new Outcome(states, energies, histories, derived, cycles);
    }

    private static DeterministicCognitiveCycle cycle(
            Node target,
            Optional<OutcomeFeedback> priorFeedback,
            ActionStatus status) {
        var stages = new ArrayList<CognitiveStage>();
        stages.add(priorFeedback.isPresent()
                ? new FeedbackAdaptationCognitiveStage(
                        new DeterministicBaselineAdaptationPolicy(), List.of(target), priorFeedback.get())
                : new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, List.of(target)));
        stages.add(new ActionCognitiveStage(
                request -> new ActionResult(status, request.maxObservations(), List.of()), 1));
        return new DeterministicCognitiveCycle(stages);
    }

    private static List<CognitiveTraceEvent.FeedbackConsumed> feedbackConsumedEvents(CognitiveCycleResult result) {
        return result.snapshot().traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.FeedbackConsumed.class::isInstance)
                .map(CognitiveTraceEvent.FeedbackConsumed.class::cast)
                .toList();
    }

    private static Node node() {
        return new Node.Builder()
                .id(uuid(1))
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(2.0, 10.0, 0.0))
                .energy(1.0)
                .build();
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
