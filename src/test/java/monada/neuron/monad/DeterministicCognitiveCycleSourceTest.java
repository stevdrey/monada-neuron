package monada.neuron.monad;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicCognitiveCycleSourceTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(10, 10, 20);

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    /** Test stage that emits fixed signals and counts executions; optionally declares itself a source. */
    private static final class FixedStage implements CognitiveStage {
        final CognitiveStageKind kind;
        final boolean source;
        final List<Signal> outputs;
        final AtomicInteger executions = new AtomicInteger();
        List<Signal> lastInputs = List.of();

        FixedStage(CognitiveStageKind kind, boolean source, List<Signal> outputs) {
            this.kind = kind;
            this.source = source;
            this.outputs = outputs;
        }

        @Override
        public CognitiveStageKind kind() {
            return kind;
        }

        @Override
        public boolean isSource() {
            return source;
        }

        @Override
        public CognitiveStageResult execute(
                PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
            executions.incrementAndGet();
            lastInputs = inputSignals;
            return new CognitiveStageResultSnapshot(kind, CognitiveStageStatus.COMPLETED, outputs);
        }
    }

    @Test
    void stagesAreNotSourcesByDefault() {
        CognitiveStage stage = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                return new CognitiveStageResultSnapshot(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
            }
        };

        assertEquals(false, stage.isSource());
    }

    @Test
    void sourceRunsWithEmptyInitialSignalsAndHandsItsOutputToTheNextStage() {
        var first = observation(1.0);
        var second = observation(2.0);
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of(first, second));
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of(observation(3.0)));

        var result = new DeterministicCognitiveCycle(List.of(next, source))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(), source.lastInputs),
                () -> assertEquals(List.of(first, second), next.lastInputs),
                () -> assertEquals(1, source.executions.get()),
                () -> assertEquals(1, next.executions.get()),
                () -> assertEquals(
                        List.of(CognitiveStageKind.PERCEPTION, CognitiveStageKind.REASONING),
                        result.stageResults().stream().map(CognitiveStageResult::kind).toList()));
    }

    @Test
    void sourceThatEmitsNothingEndsAMultiStageCycleWithNoSignals() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of());

        var result = new DeterministicCognitiveCycle(List.of(source, next))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertEquals(0, next.executions.get()),
                () -> assertEquals(1, result.stageResults().size()));
    }

    @Test
    void sourceOnlyCycleCompletesEvenWhenItEmitsNothing() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());

        var result = new DeterministicCognitiveCycle(List.of(source)).execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(), result.outputSignals()));
    }

    @Test
    void initialSignalsWithASourcePlanFailBeforeAnyStageRuns() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of(observation(1.0)));
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DeterministicCognitiveCycle(List.of(source, next))
                                .execute(monad(), List.of(observation(9.0)), BUDGET)),
                () -> assertEquals(0, source.executions.get()),
                () -> assertEquals(0, next.executions.get()));
    }

    @Test
    void aSourceThatIsNotTheFirstStageIsRejectedAtConstruction() {
        var earlier = new FixedStage(CognitiveStageKind.PERCEPTION, false, List.of());
        var lateSource = new FixedStage(CognitiveStageKind.MEMORY_RECALL, true, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicCognitiveCycle(List.of(earlier, lateSource)));
    }

    @Test
    void aSourceAndAnotherStageAtThePerceptionPositionAreRejectedAsDuplicates() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());
        var other = new FixedStage(CognitiveStageKind.PERCEPTION, false, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicCognitiveCycle(List.of(source, other)));
    }

    @Test
    void planWithoutASourceStillEndsWithNoSignalsForEmptyInitialSignals() {
        var stage = new FixedStage(CognitiveStageKind.REASONING, false, List.of(observation(1.0)));

        var result = new DeterministicCognitiveCycle(List.of(stage)).execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertEquals(0, stage.executions.get()));
    }
}
