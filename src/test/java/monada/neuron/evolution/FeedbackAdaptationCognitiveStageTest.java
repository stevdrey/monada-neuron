package monada.neuron.evolution;

import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackAdaptationCognitiveStageTest {

    private static final PrimaryMonad MONAD = new PrimaryMonad(uuid(100));

    private final AdaptationPolicy baseline = new DeterministicBaselineAdaptationPolicy();

    @Test
    void occupiesTheAdaptationPosition() {
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(), neutral());

        assertEquals(CognitiveStageKind.ADAPTATION, stage.kind());
    }

    @Test
    void adaptsEligibleTargetsInFeedbackEntryOrderAndRecordsConsumption() {
        var a = node(1, 2.0, 5.0);
        var b = node(2, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 9L,
                FeedbackEntry.of(uuid(2), 1.0), FeedbackEntry.of(uuid(1), 0.5));
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(a, b), feedback);
        var inputs = List.of(signal(1.0), signal(2.0));

        var run = execute(stage, inputs);

        assertAll(
                () -> assertEquals(List.of(uuid(2), uuid(1)),
                        run.result().decisions().stream().map(AdaptationDecision::nodeId).toList()),
                () -> assertTrue(run.result().decisions().stream().allMatch(AdaptationDecision::adapted)),
                // scalar rule: amplitude and energy scale by 1 + learningRate * score (default rate 0.1)
                () -> assertEquals(2.0 * 1.1, b.getFrequencyState().amplitude(), 1e-12),
                () -> assertEquals(2.0 * 1.05, a.getFrequencyState().amplitude(), 1e-12),
                () -> assertEquals(inputs, run.result().outputSignals()),
                () -> assertEquals(
                        List.of(uuid(2), uuid(1)),
                        run.adapted().stream().map(CognitiveTraceEvent.NodeAdapted::nodeId).toList()),
                () -> assertEquals(
                        List.of(new CognitiveTraceEvent.FeedbackConsumed(
                                9L, ActionStatus.SUCCEEDED, FeedbackDisposition.REINFORCE, 2, 2, 0)),
                        run.consumed()));
    }

    @Test
    void skipsIneligibleTargetsWithoutFailingAndCountsThem() {
        var a = node(1, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.PENALIZE, ActionStatus.FAILED, 1L,
                FeedbackEntry.of(uuid(1), -1.0), FeedbackEntry.of(uuid(99), -1.0));
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(a), feedback);

        var run = execute(stage, List.of(signal(1.0)));

        assertAll(
                () -> assertEquals(1, run.result().decisions().size()),
                () -> assertEquals(2.0 * 0.9, a.getFrequencyState().amplitude(), 1e-12),
                () -> assertEquals(
                        List.of(new CognitiveTraceEvent.FeedbackConsumed(
                                1L, ActionStatus.FAILED, FeedbackDisposition.PENALIZE, 2, 1, 1)),
                        run.consumed()));
    }

    @Test
    void neutralFeedbackAdaptsNothingButIsRecordedAsConsumed() {
        var a = node(1, 2.0, 5.0);
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(a), neutral());

        var run = execute(stage, List.of(signal(1.0)));

        assertAll(
                () -> assertTrue(run.result().decisions().isEmpty()),
                () -> assertTrue(run.adapted().isEmpty()),
                () -> assertEquals(new FrequencyState(2.0, 10.0, 0.0), a.getFrequencyState()),
                () -> assertEquals(5.0, a.getEnergy()),
                () -> assertTrue(a.getHistory().isEmpty()),
                () -> assertEquals(
                        List.of(new CognitiveTraceEvent.FeedbackConsumed(
                                3L, ActionStatus.TIMED_OUT, FeedbackDisposition.NEUTRAL, 0, 0, 0)),
                        run.consumed()));
    }

    @Test
    void noOpAdaptationPolicyKeepsTheControlPathUnchanged() {
        var a = node(1, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                FeedbackEntry.of(uuid(1), 1.0));
        var stage = new FeedbackAdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, List.of(a), feedback);

        var run = execute(stage, List.of(signal(1.0)));

        assertAll(
                () -> assertEquals(1, run.result().decisions().size()),
                () -> assertFalse(run.result().decisions().getFirst().adapted()),
                () -> assertEquals(new FrequencyState(2.0, 10.0, 0.0), a.getFrequencyState()),
                () -> assertEquals(5.0, a.getEnergy()),
                () -> assertTrue(a.getHistory().isEmpty()));
    }

    @Test
    void forwardsTheEntryTargetSignalToThePolicy() {
        var a = node(1, 2.0, 5.0);
        var target = signal(8.0);
        var seen = new ArrayList<FeedbackInput>();
        AdaptationPolicy recording = (node, input) -> {
            seen.add(input);
            return NoOpAdaptationPolicy.INSTANCE.adapt(node, input);
        };
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                new FeedbackEntry(uuid(1), target, 0.75));

        execute(new FeedbackAdaptationCognitiveStage(recording, List.of(a), feedback), List.of(signal(1.0)));

        assertEquals(List.of(FeedbackInput.ofTarget(uuid(1), target, 0.75)), seen);
    }

    @Test
    void targetsCanBeTheMembersOfAnAeon() {
        var a = node(1, 2.0, 5.0);
        var aeon = new Aeon(uuid(10), AeonPurpose.EVOLUTION);
        aeon.addMember(a);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                FeedbackEntry.of(uuid(1), 1.0));

        var run = execute(new FeedbackAdaptationCognitiveStage(baseline, aeon, feedback), List.of(signal(1.0)));

        assertEquals(1, run.result().decisions().size());
    }

    @Test
    void preservesCanonicalOrderSoActionRunsAfterConsumingFeedback() {
        var a = node(1, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                FeedbackEntry.of(uuid(1), 1.0));
        var seenByAction = new ArrayList<List<Signal>>();
        var action = new ActionCognitiveStage(request -> {
            seenByAction.add(request.inputSignals());
            // adaptation already ran in this cycle: the action observes the adapted node state
            assertTrue(a.getFrequencyState().amplitude() > 2.0);
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        }, 1);
        var cycle = new DeterministicCognitiveCycle(List.of(
                action, new FeedbackAdaptationCognitiveStage(baseline, List.of(a), feedback)));

        var result = cycle.execute(MONAD, List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));

        assertAll(
                () -> assertEquals(
                        List.of(CognitiveStageKind.ADAPTATION, CognitiveStageKind.ACTION),
                        result.stageResults().stream().map(stage -> stage.kind()).toList()),
                () -> assertEquals(List.of(List.of(signal(1.0))), seenByAction));
    }

    @Test
    void reusingTheSameFeedbackInTwoCyclesIsTheCallersExplicitChoice() {
        var a = node(1, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                FeedbackEntry.of(uuid(1), 1.0));
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(a), feedback);

        execute(stage, List.of(signal(1.0)));
        execute(stage, List.of(signal(1.0)));

        assertEquals(2.0 * 1.1 * 1.1, a.getFrequencyState().amplitude(), 1e-12);
    }

    @Test
    void rejectsFeedbackProducedByADifferentMonadBeforeAnyNodeChanges() {
        var a = node(1, 2.0, 5.0);
        var foreign = new OutcomeFeedback(
                uuid(999), 0L, ActionStatus.SUCCEEDED, 0, FeedbackDisposition.REINFORCE,
                List.of(FeedbackEntry.of(uuid(1), 1.0)), List.of());
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(a), foreign);
        var cycle = new DeterministicCognitiveCycle(List.of(stage));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> stage.validate(MONAD)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> cycle.execute(MONAD, List.of(signal(1.0)), new CognitiveBudget(10, 10, 20))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> execute(stage, List.of(signal(1.0)))),
                () -> assertEquals(new FrequencyState(2.0, 10.0, 0.0), a.getFrequencyState()),
                () -> assertEquals(5.0, a.getEnergy()),
                () -> assertTrue(a.getHistory().isEmpty()));
    }

    @Test
    void acceptsFeedbackProducedByTheExecutingMonad() {
        var stage = new FeedbackAdaptationCognitiveStage(baseline, List.of(), neutral());

        stage.validate(MONAD);
        stage.validate(new PrimaryMonad(uuid(100)));
    }

    @Test
    void rejectsInvalidConstruction() {
        var node = node(1, 1.0, 1.0);
        var nodes = new ArrayList<Node>();
        nodes.add(null);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackAdaptationCognitiveStage(null, List.of(node), neutral())),
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackAdaptationCognitiveStage(baseline, (List<Node>) null, neutral())),
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackAdaptationCognitiveStage(baseline, nodes, neutral())),
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackAdaptationCognitiveStage(baseline, List.of(node), null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackAdaptationCognitiveStage(baseline, (Aeon) null, neutral())));
    }

    @Test
    void rejectsAPolicyThatReturnsNull() {
        var a = node(1, 2.0, 5.0);
        var feedback = feedback(FeedbackDisposition.REINFORCE, ActionStatus.SUCCEEDED, 0L,
                FeedbackEntry.of(uuid(1), 1.0));
        var stage = new FeedbackAdaptationCognitiveStage((n, input) -> null, List.of(a), feedback);

        assertThrows(NullPointerException.class, () -> execute(stage, List.of(signal(1.0))));
    }

    // helpers ---------------------------------------------------------------------------------

    private record Run(
            AdaptationCognitiveStageResult result,
            List<CognitiveTraceEvent.NodeAdapted> adapted,
            List<CognitiveTraceEvent.FeedbackConsumed> consumed) {
    }

    private static Run execute(FeedbackAdaptationCognitiveStage stage, List<Signal> inputs) {
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 20));
        var result = assertInstanceOf(
                AdaptationCognitiveStageResult.class, stage.execute(MONAD, inputs, context));
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);
        var adapted = new ArrayList<CognitiveTraceEvent.NodeAdapted>();
        var consumed = new ArrayList<CognitiveTraceEvent.FeedbackConsumed>();
        for (var entry : snapshot.traceEntries()) {
            if (entry.event() instanceof CognitiveTraceEvent.NodeAdapted event) {
                adapted.add(event);
            } else if (entry.event() instanceof CognitiveTraceEvent.FeedbackConsumed event) {
                consumed.add(event);
            }
        }
        return new Run(result, adapted, consumed);
    }

    private static OutcomeFeedback neutral() {
        return OutcomeFeedback.neutral(uuid(100), 3L, ActionStatus.TIMED_OUT, List.of());
    }

    private static OutcomeFeedback feedback(
            FeedbackDisposition disposition,
            ActionStatus status,
            long ordinal,
            FeedbackEntry... entries) {
        return new OutcomeFeedback(uuid(100), ordinal, status, 0, disposition, List.of(entries), List.of());
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static Node node(long id, double amplitude, double energy) {
        return new Node.Builder()
                .id(uuid(id))
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(amplitude, 10.0, 0.0))
                .energy(energy)
                .build();
    }
}
