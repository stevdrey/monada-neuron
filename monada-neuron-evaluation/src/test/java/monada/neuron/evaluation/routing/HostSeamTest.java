package monada.neuron.evaluation.routing;

import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.outcome.ProvenanceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSeamTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();
    private final ReplayDigests digests = new ReplayDigests();

    @Test
    void followingTheAdviceReplaysEveryExistingFixtureExactlyAsWithoutAHost() {
        List<ReplayFixture> all = List.of(fixtures.improvement(24), fixtures.degradation(24), fixtures.neutral(24),
                fixtures.cases(), fixtures.chosenRouteOnly(12), fixtures.workload(96));
        for (ReplayFixture fixture : all) {
            for (ReplayArm arm : ReplayArm.values()) {
                for (RebuildStrategy strategy : RebuildStrategy.values()) {
                    ReplayRun plain = new TemporalReplay(fixture).run(arm, strategy);
                    ReplayRun hosted = new TemporalReplay(fixture, HostStep.FOLLOW).run(arm, strategy);
                    String where = fixture.fixtureId() + " " + arm + " " + strategy;
                    assertAll(where,
                            () -> assertEquals(digests.decisions(plain), digests.decisions(hosted)),
                            () -> assertEquals(digests.feedback(plain), digests.feedback(hosted)),
                            () -> assertEquals(plain.state().map(digests::state), hosted.state().map(digests::state)),
                            () -> assertEquals(plain.samples(), hosted.samples()),
                            () -> assertTrue(hosted.samples().stream()
                                    .noneMatch(s -> s.coverage() == ReplaySample.Coverage.HOST_NOT_EXECUTED)));
                }
            }
        }
    }

    @Test
    void aHostThatRunsNothingLeavesASelectionWithoutOutcomeOrObservation() {
        ReplayFixture fixture = fixtures.improvement(8);
        ReplayRun run = new TemporalReplay(fixture, (task, decision) -> new HostExecution.NotExecuted(HostAction.DEFER))
                .run(ReplayArm.ADAPTIVE);

        assertAll(
                () -> assertTrue(run.samples().stream().allMatch(s -> s.kind() == ReplaySample.DecisionKind.SELECTED
                        && s.coverage() == ReplaySample.Coverage.HOST_NOT_EXECUTED && s.chainCost().isEmpty()
                        && s.acceptance().isEmpty())),
                () -> assertTrue(run.delivered().isEmpty()),
                () -> assertTrue(run.feedback().isEmpty()));
    }

    @Test
    void anAlternativeRunsTheOtherRouteAndItsOutcomeNamesThatRoute() {
        ReplayFixture fixture = fixtures.improvement(8);
        // during the improvement fixture's outage only one route is ranked; the host runs nothing there
        HostStep alternative = (task, decision) -> ((RoutingDecision.Selected) decision).candidates().stream()
                .map(RankedCandidate::key)
                .filter(k -> !k.equals(((RoutingDecision.Selected) decision).route())).findFirst()
                .<HostExecution>map(HostExecution.Alternative::new)
                .orElse(new HostExecution.NotExecuted(HostAction.ALTERNATIVE));
        ReplayRun run = new TemporalReplay(fixture, alternative).run(ReplayArm.DERIVED_NOT_CONSUMED);

        assertFalse(run.delivered().isEmpty());
        assertTrue(run.samples().stream().anyMatch(s -> s.coverage() == ReplaySample.Coverage.HOST_NOT_EXECUTED));
        for (ReplayRun.Delivered delivered : run.delivered()) {
            RouteKey executed = delivered.observation().outcome().executedRoute();
            assertFalse(executed.equals(delivered.decision().route()), "the executed route is the alternative");
        }
        for (FeedbackEvent event : run.feedback()) {
            assertEquals(NoUpdateReason.PROVENANCE_MISMATCH, event.noUpdate().orElseThrow());
        }
        var policy = fixture.definition().feedbackPolicy(fixture.evidencePolicy());
        var store = new RoutingStateStore(fixture.definition(), fixture.partition());
        store.bind(fixture.cohortKeys());
        ReplayRun.Delivered first = run.delivered().getFirst();
        var derivation = assertInstanceOf(FeedbackDerivation.NoUpdate.class,
                policy.derive(first.observation(), first.decision(), first.request(), store.bindings()));
        assertTrue(derivation.mismatches().contains(ProvenanceField.EXECUTED_ROUTE), derivation.toString());
    }

    @Test
    void aHostThatRunsSomethingOverAnAbstentionIsAHarnessBug() {
        // task 8 of the cases fixture abstains under the tier cap; the host claims to run the advice anyway
        ReplayFixture fixture = fixtures.cases();
        var replay = new TemporalReplay(fixture, (task, decision) -> new HostExecution.Advised());

        var thrown = assertThrows(IllegalStateException.class, () -> replay.run(ReplayArm.STATIC));
        assertTrue(thrown.getMessage().contains("selected nothing"), thrown.getMessage());
    }
}
