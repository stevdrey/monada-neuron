package monada.neuron.context;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveContextTest {

    @Test
    void budgetValidatesEveryCapacity() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CognitiveBudget(0, 1, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CognitiveBudget(1, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CognitiveBudget(1, 1, -1)),
                () -> assertThrows(NullPointerException.class,
                        () -> new CognitiveContext(null)));
    }

    @Test
    void recordingRejectsNullRequiredValues() {
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 0));
        var nodeId = uuid(1);
        var inputSequence = context.tryRecordInputSignal(nodeId, signal(1.0)).orElseThrow();

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> context.tryRecordInputSignal(null, signal(1.0))),
                () -> assertThrows(NullPointerException.class,
                        () -> context.tryRecordInputSignal(nodeId, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> context.recordAeonInputStarted(null, nodeId)),
                () -> assertThrows(NullPointerException.class,
                        () -> context.recordAeonInputStarted(uuid(2), null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> context.recordCompletedStep(nodeId, inputSequence + 1, 0)));
    }

    @Test
    void snapshotsSequencedOccurrencesAndReleasesTheContextForOneTimeCompletion() {
        var context = new CognitiveContext(new CognitiveBudget(1, 3, 4));
        var source = uuid(1);
        var target = uuid(2);
        var input = signal(1.0);
        var emitted = signal(2.0);

        OptionalLong inputSequence = context.tryRecordInputSignal(source, input);
        OptionalLong emittedSequence = context.tryRecordEmittedSignal(source, emitted);
        OptionalLong deliverySequence = context.tryRecordDeliveredSignal(source, target, emitted);
        context.recordAeonInputStarted(uuid(10), source);
        context.recordCompletedStep(source, inputSequence.orElseThrow(), 1);
        context.recordAcceptedRoute(
                source,
                target,
                emittedSequence.orElseThrow(),
                deliverySequence.orElseThrow());

        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(CognitiveLifecycle.COMPLETED, context.lifecycle()),
                () -> assertEquals(CognitiveCycleOutcome.SUCCESS, snapshot.outcome()),
                () -> assertEquals(1, snapshot.processedSteps()),
                () -> assertEquals(3, snapshot.acceptedSignals()),
                () -> assertEquals(0L, snapshot.signalOccurrences().get(0).sequence()),
                () -> assertEquals(1L, snapshot.signalOccurrences().get(1).sequence()),
                () -> assertEquals(2L, snapshot.signalOccurrences().get(2).sequence()),
                () -> assertInstanceOf(CognitiveSignalOccurrence.Input.class,
                        snapshot.signalOccurrences().get(0)),
                () -> assertInstanceOf(CognitiveSignalOccurrence.Emitted.class,
                        snapshot.signalOccurrences().get(1)),
                () -> assertInstanceOf(CognitiveSignalOccurrence.Delivered.class,
                        snapshot.signalOccurrences().get(2)),
                () -> assertEquals(3, snapshot.traceEntries().size()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.signalOccurrences().clear()),
                () -> assertThrows(IllegalStateException.class,
                        () -> context.tryRecordInputSignal(source, input)),
                () -> assertThrows(IllegalStateException.class,
                        () -> context.complete(CognitiveCycleOutcome.SUCCESS)));
    }

    @Test
    void traceRetainsOnlyItsPrefixWithoutChangingRecordedCycleState() {
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 1));
        var nodeId = uuid(1);
        var input = signal(1.0);

        var inputSequence = context.tryRecordInputSignal(nodeId, input).orElseThrow();
        context.recordAeonInputStarted(uuid(10), nodeId);
        context.recordCompletedStep(nodeId, inputSequence, 0);
        var snapshot = context.complete(CognitiveCycleOutcome.FAILURE);

        assertAll(
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, snapshot.outcome()),
                () -> assertEquals(1, snapshot.processedSteps()),
                () -> assertEquals(1, snapshot.acceptedSignals()),
                () -> assertEquals(1, snapshot.traceEntries().size()),
                () -> assertInstanceOf(CognitiveTraceEvent.AeonInputStarted.class,
                        snapshot.traceEntries().getFirst().event()),
                () -> assertTrue(snapshot.traceBudgetExhausted()),
                () -> assertEquals(1L, snapshot.omittedTraceEntries()));
    }

    @Test
    void disabledTraceOmitsEveryEventWithoutChangingCycleState() {
        var context = new CognitiveContext(new CognitiveBudget(1, 3, 0));
        var source = uuid(1);
        var target = uuid(2);
        var input = signal(1.0);

        var inputSequence = context.tryRecordInputSignal(source, input).orElseThrow();
        var emittedSequence = context.tryRecordEmittedSignal(source, input).orElseThrow();
        var deliveredSequence = context.tryRecordDeliveredSignal(source, target, input).orElseThrow();
        context.recordAeonInputStarted(uuid(10), source);
        context.recordCompletedStep(source, inputSequence, 1);
        context.recordAcceptedRoute(source, target, emittedSequence, deliveredSequence);
        context.recordAeonInputCompleted(uuid(10), source, 1, false, false, false);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(1, snapshot.processedSteps()),
                () -> assertEquals(3, snapshot.acceptedSignals()),
                () -> assertTrue(snapshot.traceEntries().isEmpty()),
                () -> assertTrue(snapshot.traceBudgetExhausted()),
                () -> assertEquals(4L, snapshot.omittedTraceEntries()));
    }

    @Test
    void traceSaturationDoesNotBypassPublicArgumentValidation() {
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 1));
        var nodeId = uuid(1);
        var inputSequence = context.tryRecordInputSignal(nodeId, signal(1.0)).orElseThrow();
        context.recordAeonInputStarted(uuid(10), nodeId);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> context.recordCompletedStep(null, inputSequence, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> context.recordCompletedStep(nodeId, -1, 0)));
    }

    @Test
    void recordsNodeAdaptedEventsInTrace() {
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 5));
        var nodeId = uuid(1);
        var prev = new FrequencyState(1.0, 10.0, 0.0);
        var next = new FrequencyState(2.0, 10.0, 0.0);
        context.recordNodeAdapted(nodeId, true, prev, next, 0.5, 1.5);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(1, snapshot.traceEntries().size()),
                () -> assertInstanceOf(CognitiveTraceEvent.NodeAdapted.class,
                        snapshot.traceEntries().getFirst().event()),
                () -> {
                    var event = (CognitiveTraceEvent.NodeAdapted) snapshot.traceEntries().getFirst().event();
                    assertAll(
                            () -> assertEquals(nodeId, event.nodeId()),
                            () -> assertTrue(event.adapted()),
                            () -> assertEquals(prev, event.previousState()),
                            () -> assertEquals(next, event.newState()),
                            () -> assertEquals(0.5, event.previousEnergy()),
                            () -> assertEquals(1.5, event.newEnergy()));
                });
    }

    @Test
    void discardIsIdempotentAndPreventsReuse() {
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 0));
        context.close();
        context.close();

        assertAll(
                () -> assertEquals(CognitiveLifecycle.DISCARDED, context.lifecycle()),
                () -> assertFalse(context.isActive()),
                () -> assertThrows(IllegalStateException.class, context::hasRemainingStepCapacity),
                () -> assertThrows(IllegalStateException.class,
                        () -> context.complete(CognitiveCycleOutcome.FAILURE)));
    }

    private Signal signal(double amplitude) {
        return new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(amplitude, 10.0, 0.0));
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
