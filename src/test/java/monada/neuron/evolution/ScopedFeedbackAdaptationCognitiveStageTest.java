package monada.neuron.evolution;

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
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedFeedbackAdaptationCognitiveStageTest {

    private static final PrimaryMonad MONAD = new PrimaryMonad(uuid(100));

    private final AdaptationPolicy baseline = new DeterministicBaselineAdaptationPolicy();

    @Test
    void occupiesTheAdaptationPosition() {
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of());

        assertEquals(CognitiveStageKind.ADAPTATION, stage.kind());
    }

    @Test
    void withoutScopedFeedbackItPassesSignalsThroughAndConsumesNothing() {
        var target = node(1);
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of(target));
        var inputs = List.of(signal(1.0));
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 20));

        var result = assertInstanceOf(
                AdaptationCognitiveStageResult.class, stage.execute(MONAD, inputs, context));
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertTrue(result.decisions().isEmpty()),
                () -> assertEquals(inputs, result.outputSignals()),
                () -> assertFalse(stage.acceptsTypedOnlyHandOff()),
                () -> assertTrue(snapshot.traceEntries().isEmpty()),
                () -> assertEquals(new FrequencyState(2.0, 10.0, 0.0), target.getFrequencyState()),
                () -> assertEquals(0, target.getHistorySize()));
    }

    @Test
    void withScopedFeedbackItAdaptsExactlyLikeTheCapturingStage() {
        var scopedTarget = node(1);
        var capturingTarget = node(1);
        var feedback = feedback(100, FeedbackEntry.of(uuid(1), 1.0));
        var inputs = List.of(signal(1.0));

        var scopedContext = new CognitiveContext(new CognitiveBudget(10, 10, 20));
        var scoped = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of(scopedTarget));
        var scopedResult = ScopedFeedbackAdaptationCognitiveStage.callWith(
                feedback, () -> scoped.execute(MONAD, inputs, scopedContext));
        var capturingContext = new CognitiveContext(new CognitiveBudget(10, 10, 20));
        var capturingResult = new FeedbackAdaptationCognitiveStage(baseline, List.of(capturingTarget), feedback)
                .execute(MONAD, inputs, capturingContext);

        assertAll(
                () -> assertEquals(capturingResult, scopedResult),
                () -> assertEquals(capturingTarget.getFrequencyState(), scopedTarget.getFrequencyState()),
                () -> assertEquals(capturingTarget.getEnergy(), scopedTarget.getEnergy()),
                () -> assertEquals(1, scopedTarget.getHistorySize()),
                () -> assertEquals(
                        capturingContext.complete(CognitiveCycleOutcome.SUCCESS).traceEntries(),
                        scopedContext.complete(CognitiveCycleOutcome.SUCCESS).traceEntries()));
    }

    @Test
    void acceptsTypedOnlyHandOffOnlyWhileFeedbackIsScoped() {
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of());

        assertAll(
                () -> assertFalse(stage.acceptsTypedOnlyHandOff()),
                () -> assertTrue(ScopedFeedbackAdaptationCognitiveStage.callWith(
                        feedback(100), stage::acceptsTypedOnlyHandOff)));
    }

    @Test
    void feedbackIsVisibleOnlyInsideItsScope() {
        var target = node(1);
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of(target));
        var feedback = feedback(100, FeedbackEntry.of(uuid(1), 1.0));
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 20));

        var inside = ScopedFeedbackAdaptationCognitiveStage.callWith(feedback, () -> "value");
        var after = assertInstanceOf(
                AdaptationCognitiveStageResult.class,
                stage.execute(MONAD, List.of(signal(1.0)), context));

        assertAll(
                () -> assertEquals("value", inside),
                () -> assertTrue(after.decisions().isEmpty()),
                () -> assertEquals(0, target.getHistorySize()));
    }

    @Test
    void propagatesFailuresFromInsideTheScope() {
        var cause = new IllegalStateException("boom");

        var failure = assertThrows(
                IllegalStateException.class,
                () -> ScopedFeedbackAdaptationCognitiveStage.callWith(feedback(100), () -> {
                    throw cause;
                }));

        assertEquals(cause, failure);
    }

    @Test
    void rejectsScopedFeedbackProducedByAnotherMonadBeforeAnyNodeChanges() {
        var target = node(1);
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of(target));
        var foreign = feedback(999, FeedbackEntry.of(uuid(1), 1.0));

        assertThrows(
                IllegalArgumentException.class,
                () -> ScopedFeedbackAdaptationCognitiveStage.callWith(foreign, () -> {
                    stage.validate(MONAD);
                    return null;
                }));
        assertEquals(0, target.getHistorySize());
    }

    @Test
    void validatesAnyMonadWhenNoFeedbackIsScoped() {
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, List.of());

        stage.validate(MONAD);
        assertThrows(NullPointerException.class, () -> stage.validate(null));
    }

    @Test
    void resolvesTargetsFromAnAeonWhenExecuting() {
        var member = node(1);
        var aeon = new Aeon(uuid(50), AeonPurpose.EVOLUTION);
        aeon.addMember(member);
        var stage = new ScopedFeedbackAdaptationCognitiveStage(baseline, aeon);
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 20));

        ScopedFeedbackAdaptationCognitiveStage.callWith(
                feedback(100, FeedbackEntry.of(uuid(1), 1.0)),
                () -> stage.execute(MONAD, List.of(signal(1.0)), context));
        var consumed = context.complete(CognitiveCycleOutcome.SUCCESS).traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.FeedbackConsumed.class::isInstance)
                .count();

        assertAll(
                () -> assertEquals(1, member.getHistorySize()),
                () -> assertEquals(1L, consumed));
    }

    @Test
    void rejectsNullConfiguration() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedFeedbackAdaptationCognitiveStage(null, List.<Node>of())),
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedFeedbackAdaptationCognitiveStage(baseline, (List<Node>) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedFeedbackAdaptationCognitiveStage(baseline, (Aeon) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> ScopedFeedbackAdaptationCognitiveStage.callWith(null, () -> "x")),
                () -> assertThrows(NullPointerException.class,
                        () -> ScopedFeedbackAdaptationCognitiveStage.callWith(feedback(100), null)));
    }

    private static OutcomeFeedback feedback(long monadId, FeedbackEntry... entries) {
        var disposition = entries.length == 0 ? FeedbackDisposition.NEUTRAL : FeedbackDisposition.REINFORCE;
        var status = entries.length == 0 ? ActionStatus.TIMED_OUT : ActionStatus.SUCCEEDED;
        return new OutcomeFeedback(
                uuid(monadId), 1L, status, 0, disposition, List.of(entries), List.of());
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static Node node(long id) {
        return new Node.Builder()
                .id(uuid(id))
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(2.0, 10.0, 0.0))
                .energy(1.0)
                .build();
    }
}
