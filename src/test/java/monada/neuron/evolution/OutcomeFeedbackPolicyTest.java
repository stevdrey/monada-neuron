package monada.neuron.evolution;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.reasoning.SignalEvidence;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeFeedbackPolicyTest {

    private static final UUID MONAD_ID = uuid(100);
    private static final List<UUID> TARGETS = List.of(uuid(1), uuid(2), uuid(3));

    private final DeterministicOutcomeFeedbackPolicy policy = new DeterministicOutcomeFeedbackPolicy();

    @Test
    void succeededOutcomeReinforcesEveryTargetInSuppliedOrder() {
        var cycle = runAction(ActionStatus.SUCCEEDED, 2);

        var feedback = policy.derive(cycle, TARGETS, 7L).orElseThrow();

        assertAll(
                () -> assertEquals(MONAD_ID, feedback.monadId()),
                () -> assertEquals(7L, feedback.originCycleOrdinal()),
                () -> assertEquals(ActionStatus.SUCCEEDED, feedback.sourceStatus()),
                () -> assertEquals(2, feedback.admittedObservationCount()),
                () -> assertEquals(FeedbackDisposition.REINFORCE, feedback.disposition()),
                () -> assertEquals(
                        List.of(FeedbackEntry.of(uuid(1), 1.0), FeedbackEntry.of(uuid(2), 1.0),
                                FeedbackEntry.of(uuid(3), 1.0)),
                        feedback.entries()),
                () -> assertTrue(feedback.attributions().isEmpty()));
    }

    @Test
    void partiallyCompletedOutcomeReinforcesWithTheReducedScore() {
        var feedback = policy.derive(runAction(ActionStatus.PARTIALLY_COMPLETED, 1), TARGETS, 0L).orElseThrow();

        assertAll(
                () -> assertEquals(FeedbackDisposition.REINFORCE, feedback.disposition()),
                () -> assertEquals(1, feedback.admittedObservationCount()),
                () -> assertTrue(feedback.entries().stream().allMatch(entry -> entry.score() == 0.5)));
    }

    @Test
    void theCycleBudgetNeverChangesTheFeedbackDerivedFromTheReportedStatus() {
        // capability produced 3 observations; maxSignals 4 admits the input and all 3, 2 admits input and 1
        var expected = Map.of(ActionStatus.SUCCEEDED, 1.0, ActionStatus.PARTIALLY_COMPLETED, 0.5);
        for (var reported : expected.keySet()) {
            var complete = policy.derive(runBudgetedAction(reported, 3, 4), TARGETS, 0L).orElseThrow();
            var truncated = policy.derive(runBudgetedAction(reported, 3, 2), TARGETS, 0L).orElseThrow();

            assertAll(
                    reported.name(),
                    () -> assertEquals(reported, complete.sourceStatus()),
                    () -> assertEquals(3, complete.producedObservationCount()),
                    () -> assertEquals(3, complete.admittedObservationCount()),
                    () -> assertTrue(complete.entries().stream().allMatch(entry -> entry.score() == expected.get(reported))),
                    // a budget-truncated outcome keeps the status and the reward; only provenance records the loss
                    () -> assertEquals(reported, truncated.sourceStatus()),
                    () -> assertEquals(3, truncated.producedObservationCount()),
                    () -> assertEquals(1, truncated.admittedObservationCount()),
                    () -> assertEquals(FeedbackDisposition.REINFORCE, truncated.disposition()),
                    () -> assertTrue(truncated.entries().stream().allMatch(entry -> entry.score() == expected.get(reported))));
        }
    }

    @Test
    void rejectedOutcomePenalizesLightly() {
        var feedback = policy.derive(runAction(ActionStatus.REJECTED, 0), TARGETS, 0L).orElseThrow();

        assertAll(
                () -> assertEquals(FeedbackDisposition.PENALIZE, feedback.disposition()),
                () -> assertEquals(ActionStatus.REJECTED, feedback.sourceStatus()),
                () -> assertTrue(feedback.entries().stream().allMatch(entry -> entry.score() == -0.25)));
    }

    @Test
    void failedOutcomePenalizesFully() {
        var feedback = policy.derive(runAction(ActionStatus.FAILED, 0), TARGETS, 0L).orElseThrow();

        assertAll(
                () -> assertEquals(FeedbackDisposition.PENALIZE, feedback.disposition()),
                () -> assertTrue(feedback.entries().stream().allMatch(entry -> entry.score() == -1.0)));
    }

    @Test
    void environmentalOutcomesDeriveExplicitNeutralFeedbackWithoutFabricatingReward() {
        for (var status : List.of(ActionStatus.UNAVAILABLE, ActionStatus.TIMED_OUT)) {
            var feedback = policy.derive(runAction(status, 0), TARGETS, 4L).orElseThrow();

            assertAll(
                    status.name(),
                    () -> assertEquals(FeedbackDisposition.NEUTRAL, feedback.disposition()),
                    () -> assertEquals(status, feedback.sourceStatus()),
                    () -> assertEquals(4L, feedback.originCycleOrdinal()),
                    () -> assertTrue(feedback.entries().isEmpty()));
        }
    }

    @Test
    void noTargetsDeriveNeutralFeedbackEvenForSuccess() {
        var feedback = policy.derive(runAction(ActionStatus.SUCCEEDED, 1), List.of(), 0L).orElseThrow();

        assertAll(
                () -> assertEquals(FeedbackDisposition.NEUTRAL, feedback.disposition()),
                () -> assertEquals(ActionStatus.SUCCEEDED, feedback.sourceStatus()),
                () -> assertTrue(feedback.entries().isEmpty()));
    }

    @Test
    void cycleWithoutActionResultDerivesNoFeedback() {
        var cycle = new DeterministicCognitiveCycle(List.of())
                .execute(new PrimaryMonad(MONAD_ID), List.of(signal(1.0)), new CognitiveBudget(5, 5, 5));

        assertTrue(policy.derive(cycle, TARGETS, 0L).isEmpty());
    }

    @Test
    void entriesAreTruncatedToTheConfiguredBoundKeepingSuppliedOrder() {
        var bounded = new DeterministicOutcomeFeedbackPolicy(
                new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 2, 4));

        var feedback = bounded.derive(runAction(ActionStatus.SUCCEEDED, 1), TARGETS, 0L).orElseThrow();

        assertEquals(List.of(uuid(1), uuid(2)),
                feedback.entries().stream().map(FeedbackEntry::targetNodeId).toList());
    }

    @Test
    void usesNoTargetSignalForTheScalarReferenceRule() {
        var feedback = policy.derive(runAction(ActionStatus.SUCCEEDED, 1), TARGETS, 0L).orElseThrow();

        assertTrue(feedback.entries().stream().allMatch(entry -> entry.targetSignal() == null));
        assertNull(feedback.entries().getFirst().targetSignal());
    }

    @Test
    void attributesSelectedHypothesesInRankOrderWithoutRetainingTheSet() {
        var cycle = runEvaluatedAction(ActionStatus.SUCCEEDED, 2);

        var feedback = policy.derive(cycle, TARGETS, 0L).orElseThrow();

        // candidate 1 (support 0.8) outranks candidate 0 (support 0.4); candidate 2 is only contradicted.
        assertAll(
                () -> assertEquals(
                        List.of(new Proposition(0, 1), new Proposition(0, 0)),
                        feedback.attributions().stream().map(HypothesisAttribution::proposition).toList()),
                () -> assertEquals(0.8 / 1.8, feedback.attributions().get(0).evaluationScore()),
                () -> assertEquals(0.4 / 1.4, feedback.attributions().get(1).evaluationScore()));
    }

    @Test
    void attributionIsBoundedByConfiguration() {
        var bounded = new DeterministicOutcomeFeedbackPolicy(
                new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 8, 1));
        var none = new DeterministicOutcomeFeedbackPolicy(
                new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 8, 0));
        var cycle = runEvaluatedAction(ActionStatus.FAILED, 0);

        assertAll(
                () -> assertEquals(
                        List.of(new Proposition(0, 1)),
                        bounded.derive(cycle, TARGETS, 0L).orElseThrow().attributions().stream()
                                .map(HypothesisAttribution::proposition).toList()),
                () -> assertTrue(none.derive(cycle, TARGETS, 0L).orElseThrow().attributions().isEmpty()));
    }

    @Test
    void neutralFeedbackStillCarriesAttribution() {
        var feedback = policy.derive(runEvaluatedAction(ActionStatus.TIMED_OUT, 0), TARGETS, 0L).orElseThrow();

        assertAll(
                () -> assertEquals(FeedbackDisposition.NEUTRAL, feedback.disposition()),
                () -> assertEquals(2, feedback.attributions().size()));
    }

    @Test
    void derivationIsDeterministicAndDoesNotChangeTheCycleResult() {
        var cycle = runEvaluatedAction(ActionStatus.SUCCEEDED, 2);
        var stagesBefore = List.copyOf(cycle.stageResults());

        var first = policy.derive(cycle, TARGETS, 3L);
        var second = policy.derive(cycle, TARGETS, 3L);

        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(stagesBefore, cycle.stageResults()));
    }

    @Test
    void rejectsDuplicateTargetsAndInvalidArguments() {
        var cycle = runAction(ActionStatus.SUCCEEDED, 1);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> policy.derive(cycle, List.of(uuid(1), uuid(1)), 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> policy.derive(cycle, TARGETS, -1L)),
                () -> assertThrows(NullPointerException.class, () -> policy.derive(null, TARGETS, 0L)),
                () -> assertThrows(NullPointerException.class, () -> policy.derive(cycle, null, 0L)),
                () -> assertThrows(NullPointerException.class,
                        () -> policy.derive(cycle, Arrays.asList(uuid(1), null), 0L)),
                () -> assertThrows(NullPointerException.class, () -> new DeterministicOutcomeFeedbackPolicy(null)));
    }

    @Test
    void noOpPolicyRejectsTheSameMalformedInputsAsTheReferencePolicy() {
        var cycle = runAction(ActionStatus.SUCCEEDED, 1);
        var noOp = NoOpOutcomeFeedbackPolicy.INSTANCE;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> noOp.derive(cycle, TARGETS, -1L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> noOp.derive(cycle, List.of(uuid(1), uuid(1)), 0L)),
                () -> assertThrows(NullPointerException.class,
                        () -> noOp.derive(cycle, Arrays.asList(uuid(1), null), 0L)),
                () -> assertThrows(NullPointerException.class, () -> noOp.derive(null, TARGETS, 0L)),
                () -> assertThrows(NullPointerException.class, () -> noOp.derive(cycle, null, 0L)),
                () -> assertTrue(noOp.derive(cycle, List.of(), 0L).isEmpty()));
    }

    @Test
    void noOpPolicyOnlyConsidersTheHardCapOfTargets() {
        var cycle = runAction(ActionStatus.SUCCEEDED, 1);
        var targets = new ArrayList<UUID>();
        for (var i = 0; i < OutcomeFeedback.MAX_ENTRIES; i++) {
            targets.add(uuid(1000 + i));
        }
        targets.add(uuid(1000)); // repeated, but beyond every policy's considered prefix

        assertTrue(NoOpOutcomeFeedbackPolicy.INSTANCE.derive(cycle, targets, 0L).isEmpty());
    }

    @Test
    void noOpPolicyValidatesTheSameConfiguredPrefixAsTheReferencePolicy() {
        var config = new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 2, 4);
        var reference = new DeterministicOutcomeFeedbackPolicy(config);
        var control = new NoOpOutcomeFeedbackPolicy(config);
        var cycle = runAction(ActionStatus.SUCCEEDED, 1);
        var duplicateInside = List.of(uuid(1), uuid(1), uuid(3));
        var duplicateBeyond = List.of(uuid(1), uuid(2), uuid(3), uuid(1));
        var nullInside = Arrays.asList(uuid(1), null, uuid(3));
        var nullBeyond = Arrays.asList(uuid(1), uuid(2), null);

        // Swapping the control for the reference policy must not change whether the workload runs.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> reference.derive(cycle, duplicateInside, 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> control.derive(cycle, duplicateInside, 0L)),
                () -> assertThrows(NullPointerException.class, () -> reference.derive(cycle, nullInside, 0L)),
                () -> assertThrows(NullPointerException.class, () -> control.derive(cycle, nullInside, 0L)),
                () -> assertTrue(reference.derive(cycle, duplicateBeyond, 0L).isPresent()),
                () -> assertTrue(control.derive(cycle, duplicateBeyond, 0L).isEmpty()),
                () -> assertTrue(reference.derive(cycle, nullBeyond, 0L).isPresent()),
                () -> assertTrue(control.derive(cycle, nullBeyond, 0L).isEmpty()));
    }

    @Test
    void noOpPolicyExposesItsConfigurationAndDefaultsToTheReferenceDefault() {
        var custom = new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 8, 0);

        assertAll(
                () -> assertSame(OutcomeFeedbackConfig.DEFAULT, NoOpOutcomeFeedbackPolicy.INSTANCE.config()),
                () -> assertSame(custom, new NoOpOutcomeFeedbackPolicy(custom).config()),
                () -> assertThrows(NullPointerException.class, () -> new NoOpOutcomeFeedbackPolicy(null)));
    }

    @Test
    void noOpPolicyIsTheControlPathAndDerivesNothing() {
        var cycle = runAction(ActionStatus.SUCCEEDED, 1);

        assertAll(
                () -> assertTrue(NoOpOutcomeFeedbackPolicy.INSTANCE.derive(cycle, TARGETS, 0L).isEmpty()),
                () -> assertFalse(policy.derive(cycle, TARGETS, 0L).isEmpty()));
    }

    // helpers ---------------------------------------------------------------------------------

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static ActionCapability capability(ActionStatus status, int observations) {
        return request -> new ActionResult(
                status,
                request.maxObservations(),
                IntStream.range(0, observations).mapToObj(i -> signal(i + 1.0)).toList());
    }

    private static CognitiveCycleResult runBudgetedAction(ActionStatus status, int observations, int maxSignals) {
        return new DeterministicCognitiveCycle(List.of(new ActionCognitiveStage(capability(status, observations), 4)))
                .execute(new PrimaryMonad(MONAD_ID), List.of(signal(1.0)), new CognitiveBudget(10, maxSignals, 30));
    }

    private static CognitiveCycleResult runAction(ActionStatus status, int observations) {
        return new DeterministicCognitiveCycle(List.of(new ActionCognitiveStage(capability(status, observations), 4)))
                .execute(new PrimaryMonad(MONAD_ID), List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));
    }

    private static CognitiveCycleResult runEvaluatedAction(ActionStatus status, int observations) {
        return new DeterministicCognitiveCycle(List.of(
                reasoningStub(hypotheses(), List.of(signal(1.0))),
                new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2),
                new ActionCognitiveStage(capability(status, observations), 4)))
                .execute(new PrimaryMonad(MONAD_ID), List.of(signal(1.0)), new CognitiveBudget(10, 10, 60));
    }

    /** Candidate 0 and 1 are supported (1 more strongly); candidate 2 is only contradicted. */
    private static HypothesisSet hypotheses() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(3, 4));
        var zero = builder.propose(new Proposition(0, 0)).getAsInt();
        var one = builder.propose(new Proposition(0, 1)).getAsInt();
        var two = builder.propose(new Proposition(0, 2)).getAsInt();
        builder.addEvidence(zero, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.4));
        builder.addEvidence(one, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.8));
        builder.addEvidence(two, new SignalEvidence(0, EvidenceRelation.CONTRADICTS, 0.9));
        return builder.build();
    }

    private static CognitiveStage reasoningStub(HypothesisSet hypotheses, List<Signal> outputs) {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                return new ReasoningCognitiveStageResult(CognitiveStageStatus.COMPLETED, outputs, hypotheses);
            }
        };
    }
}
