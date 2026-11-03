package monada.neuron.evaluation.routing;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.AlternativeStanding;
import monada.neuron.routing.disposition.DispositionRejection;
import monada.neuron.routing.disposition.DispositionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostScriptTest {

    private final HostScenarioFixtures fixtures = new HostScenarioFixtures();

    private List<HostDecisionRecord> run(ReplayArm arm) {
        ReplayFixture fixture = fixtures.disposition();
        var script = new HostScript(fixture, fixtures.dispositionScript(), HostScenarioFixtures.REQUIRED_FOR_PREVIEW);
        new TemporalReplay(fixture, script).run(arm);
        return script.records();
    }

    private static <T> T bound(HostDecisionRecord record, Class<T> type) {
        var result = assertInstanceOf(DispositionResult.Bound.class, record.disposition().orElseThrow(),
                record.toString());
        return assertInstanceOf(type, result.disposition());
    }

    private static DispositionRejection rejected(HostDecisionRecord record) {
        return assertInstanceOf(DispositionResult.Rejected.class, record.disposition().orElseThrow(),
                record.toString()).reason();
    }

    @Test
    void theScriptRecordsOneEntryPerDecisionInReplayOrder() {
        List<HostDecisionRecord> records = run(ReplayArm.STATIC);

        assertEquals(32, records.size());
        for (int i = 0; i < records.size(); i++) {
            HostDecisionRecord record = records.get(i);
            assertAll("task " + i,
                    () -> assertEquals(HostAction.values()[record.taskIndex() % 4], record.action()),
                    () -> assertTrue(record.receiptBytes() > 0),
                    () -> assertEquals(64, record.receiptSha256().length()),
                    () -> assertTrue(record.missingForHostPreview().isEmpty()));
        }
    }

    @Test
    void overASelectionEveryVariantBindsAndOnlyFollowAndAlternativeRun() {
        // block 0 is implement: tasks 0 to 3
        List<HostDecisionRecord> records = run(ReplayArm.STATIC);

        HostDecisionRecord follow = records.get(0);
        HostDecisionRecord alternative = records.get(1);
        var proposed = bound(alternative, AdviceDisposition.AlternativeProposed.class);
        assertAll(
                () -> assertEquals(ReplaySample.DecisionKind.SELECTED, follow.kind()),
                () -> bound(follow, AdviceDisposition.Followed.class),
                () -> assertEquals(new HostExecution.Advised(), follow.execution()),
                () -> assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, proposed.standing()),
                () -> assertNotEquals(proposed.advice().advisedRoute().orElseThrow(), proposed.route()),
                () -> assertEquals(new HostExecution.Alternative(proposed.route()), alternative.execution()),
                () -> bound(records.get(2), AdviceDisposition.Deferred.class),
                () -> assertEquals(new HostExecution.NotExecuted(HostAction.DEFER), records.get(2).execution()),
                () -> bound(records.get(3), AdviceDisposition.Declined.class),
                () -> assertEquals(new HostExecution.NotExecuted(HostAction.DECLINE), records.get(3).execution()));
    }

    @Test
    void anAlternativeOverAnAbstentionBindsButRunsNothing() {
        // block 1 is qa, which only tier 2 routes serve: the tier cap abstains on tasks 4 to 7
        List<HostDecisionRecord> records = run(ReplayArm.STATIC);
        HostDecisionRecord follow = records.get(4);
        HostDecisionRecord alternative = records.get(5);
        var proposed = bound(alternative, AdviceDisposition.AlternativeProposed.class);

        assertAll(
                () -> assertEquals(ReplaySample.DecisionKind.ABSTAINED, alternative.kind()),
                () -> assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, alternative.abstain().orElseThrow()),
                () -> assertEquals(DispositionRejection.ADVICE_NOT_SELECTED, rejected(follow)),
                () -> assertEquals(new HostExecution.NotExecuted(HostAction.FOLLOW), follow.execution()),
                () -> assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, proposed.standing()),
                () -> assertEquals(new HostExecution.NotExecuted(HostAction.ALTERNATIVE), alternative.execution()),
                () -> bound(records.get(6), AdviceDisposition.Deferred.class),
                () -> bound(records.get(7), AdviceDisposition.Declined.class));
    }

    @Test
    void anAlternativeWhenOnlyTheAdvisedRouteIsRankedIsRejectedAndRunsNothing() {
        // block 3 is docs, which only sub-a serves: task 13 picks an alternative
        HostDecisionRecord alternative = run(ReplayArm.STATIC).get(13);

        assertAll(
                () -> assertEquals(ReplaySample.DecisionKind.SELECTED, alternative.kind()),
                () -> assertEquals(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE, rejected(alternative)),
                () -> assertEquals(new HostExecution.NotExecuted(HostAction.ALTERNATIVE), alternative.execution()));
    }

    @Test
    void theAlternativeRunGeneratesTheOtherRoutesOutcomeAndNoRunGeneratesNone() {
        ReplayFixture fixture = fixtures.disposition();
        var script = new HostScript(fixture, fixtures.dispositionScript(), HostScenarioFixtures.REQUIRED_FOR_PREVIEW);
        ReplayRun run = new TemporalReplay(fixture, script).run(ReplayArm.ADAPTIVE);
        List<HostDecisionRecord> records = script.records();

        for (int i = 0; i < records.size(); i++) {
            HostDecisionRecord record = records.get(i);
            ReplaySample sample = run.samples().get(i);
            String experienceRef = new ObservationFactory().experienceRef(fixture.tasks().get(i).request());
            long observations = run.delivered().stream()
                    .filter(d -> d.observation().experienceRef().equals(experienceRef)).count();
            switch (record.execution()) {
                case HostExecution.Advised ignored -> assertEquals(1, observations, record.toString());
                case HostExecution.Alternative alternative -> {
                    assertEquals(1, observations, record.toString());
                    assertTrue(run.delivered().stream()
                            .filter(d -> d.observation().experienceRef().equals(experienceRef))
                            .allMatch(d -> d.observation().outcome().executedRoute().equals(alternative.route())));
                }
                case HostExecution.NotExecuted ignored -> {
                    assertEquals(0, observations, record.toString());
                    assertTrue(sample.chainCost().isEmpty() && sample.acceptance().isEmpty(), sample.toString());
                }
            }
        }
    }

    private static HostDecisionRecord record(ReplaySample.DecisionKind kind, Optional<AbstainReason> abstain,
            HostExecution execution) {
        return new HostDecisionRecord(0, "qa", kind, abstain, "sha", 600, List.of(), HostAction.FOLLOW,
                Optional.empty(), execution);
    }

    @Test
    void aRecordCarriesAnAbstentionReasonExactlyWhenTheDecisionAbstained() {
        var notRun = new HostExecution.NotExecuted(HostAction.FOLLOW);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> record(ReplaySample.DecisionKind.ABSTAINED, Optional.empty(), notRun)),
                () -> assertThrows(IllegalArgumentException.class, () -> record(ReplaySample.DecisionKind.SELECTED,
                        Optional.of(AbstainReason.POLICY_TRADEOFF_UNRESOLVED), notRun)),
                () -> assertEquals(ReplaySample.DecisionKind.ABSTAINED, record(ReplaySample.DecisionKind.ABSTAINED,
                        Optional.of(AbstainReason.POLICY_TRADEOFF_UNRESOLVED), notRun).kind()));
    }

    @Test
    void aRecordOfADecisionThatSelectedNothingCannotClaimAnExecution() {
        var abstain = Optional.of(AbstainReason.POLICY_TRADEOFF_UNRESOLVED);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> record(ReplaySample.DecisionKind.ABSTAINED, abstain, new HostExecution.Advised())),
                () -> assertThrows(IllegalArgumentException.class, () -> record(ReplaySample.DecisionKind.ABSTAINED,
                        abstain, new HostExecution.Alternative(SyntheticRoutingFixtures.SUB_B))),
                () -> assertInstanceOf(HostExecution.Alternative.class, record(ReplaySample.DecisionKind.SELECTED,
                        Optional.empty(), new HostExecution.Alternative(SyntheticRoutingFixtures.SUB_B)).execution()));
    }
}
