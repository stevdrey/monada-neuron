package monada.neuron.evaluation.routing;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayFixtureBoundsTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void anOversizedFixtureIsRejectedNotTruncated() {
        assertThrows(IllegalArgumentException.class, () -> fixtures.workload(ReplayFixture.MAX_TASKS + 1));
    }

    @Test
    void duplicateDecisionsAndForeignPartitionsAreRejected() {
        var fixture = fixtures.improvement(4);
        var duplicated = new ArrayList<>(fixture.tasks());
        duplicated.add(fixture.tasks().getFirst());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1",
                        fixture.definition(), fixture.evidencePolicy(), fixture.policy(), fixture.partition(),
                        duplicated)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1",
                        fixture.definition(), fixture.evidencePolicy(), fixture.policy(),
                        new StatePartition("other-scope", "forge-gates", "1"), fixture.tasks())));
    }

    @Test
    void theEvidencePolicyMustBeThePartitionsEvaluationPolicy() {
        var fixture = fixtures.improvement(4);
        var otherVersion = new EvidencePolicy(fixture.evidencePolicy().id(), "2",
                fixture.evidencePolicy().mandatoryDimensions());

        assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1", fixture.definition(),
                otherVersion, fixture.policy(), fixture.partition(), fixture.tasks()));
    }

    @Test
    void thePolicyMustRouteOverTheAdaptiveDefinitionsState() {
        var fixture = fixtures.improvement(4);
        // the reference policy routes over routing-state/1, the reference definition is adaptive-routing-state/1
        assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1", fixture.definition(),
                fixture.evidencePolicy(), LexicographicRoutingPolicy.reference(), fixture.partition(),
                fixture.tasks()));
    }

    @Test
    void anOutcomeMustBecomeVisibleStrictlyAfterItsDecision() {
        var task = fixtures.improvement(1).tasks().getFirst();
        var known = (OutcomeOracle.KnownForEveryRoute) task.oracle();
        ScriptedOutcome atCutoff = known.outcomes().get(SyntheticRoutingFixtures.SUB_B)
                .observedAt(task.request().cutoff());

        assertThrows(IllegalArgumentException.class, () -> new ReplayTask(task.request(), task.catalog(),
                OutcomeOracle.KnownForEveryRoute.of(Map.of(SyntheticRoutingFixtures.SUB_B, atCutoff))));
    }

    @Test
    void aHorizonBeforeAnyCutoffOrObservationIsRejected() {
        var fixture = fixtures.improvement(4);
        long latest = fixture.horizon();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1",
                        fixture.definition(), fixture.evidencePolicy(), fixture.policy(), fixture.partition(),
                        fixture.tasks(), latest - 1)),
                () -> assertEquals(latest + 10, new ReplayFixture("f", "1", fixture.definition(),
                        fixture.evidencePolicy(), fixture.policy(), fixture.partition(), fixture.tasks(), latest + 10)
                        .horizon()));
    }

    @Test
    void requestOrdinalsMustStrictlyIncreaseInReplayOrder() {
        var fixture = fixtures.improvement(2);
        ReplayTask first = fixture.tasks().get(0);
        ReplayTask second = fixture.tasks().get(1);
        // another task and execution, but the first task's ordinal
        var sameOrdinal = new ReplayTask(withIdentity(second.request(), "task-other", "exe-other", 0L),
                second.catalog(), second.oracle());
        // decided later but numbered earlier
        var reordered = new ReplayTask(withIdentity(first.request(), first.request().taskId(),
                first.request().executionId(), 5L), first.catalog(), first.oracle());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1",
                        fixture.definition(), fixture.evidencePolicy(), fixture.policy(), fixture.partition(),
                        List.of(first, sameOrdinal))),
                () -> assertThrows(IllegalArgumentException.class, () -> new ReplayFixture("f", "1",
                        fixture.definition(), fixture.evidencePolicy(), fixture.policy(), fixture.partition(),
                        List.of(reordered, second))));
    }

    @Test
    void aBatchSharingOneCutoffReplaysInIssuanceOrderWhateverItsIds() {
        var fixture = fixtures.improvement(2);
        ReplayTask first = fixture.tasks().get(0);
        ReplayTask second = fixture.tasks().get(1);
        long cutoff = first.request().cutoff();
        // issued first but with the later-sorting id, then issued second with the earlier-sorting id
        var z = new ReplayTask(withIdentity(first.request(), "z", "exe-z", 1L, cutoff), first.catalog(),
                first.oracle());
        var a = new ReplayTask(withIdentity(second.request(), "a", "exe-a", 2L, cutoff), second.catalog(),
                second.oracle());
        var batch = new ReplayFixture("f", "1", fixture.definition(), fixture.evidencePolicy(), fixture.policy(),
                fixture.partition(), List.of(a, z));

        assertEquals(List.of("z", "a"), batch.tasks().stream().map(t -> t.request().taskId()).toList());
    }

    private static RoutingRequest withIdentity(RoutingRequest r, String taskId, String executionId, long ordinal) {
        return withIdentity(r, taskId, executionId, ordinal, r.cutoff());
    }

    private static RoutingRequest withIdentity(RoutingRequest r, String taskId, String executionId, long ordinal,
            long cutoff) {
        return new RoutingRequest(r.contractVersion(), r.scopeId(), taskId, executionId, r.attemptId(), r.stageId(),
                r.stageKind(), ordinal, r.sourceFingerprint(), r.contextFingerprint(), r.constraintsFingerprint(),
                r.evaluationPolicyId(), r.evaluationPolicyVersion(), r.features(), r.requirements(),
                r.overflowPermitted(), cutoff);
    }

    @Test
    void tasksAreStoredInTotalReplayOrder() {
        var fixture = fixtures.improvement(6);
        var reversed = new ArrayList<>(fixture.tasks()).reversed();
        var resorted = new ReplayFixture(fixture.fixtureId(), fixture.version(), fixture.definition(),
                fixture.evidencePolicy(), fixture.policy(), fixture.partition(), reversed);

        assertEquals(fixture.tasks(), resorted.tasks());
    }

    @Test
    void costInvariantsMirrorTheStore() {
        var usd = new Money(new BigDecimal("0.01"), "USD");
        assertAll(
                // an unavailable cost carries no amount
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ReplayCost(ReplayCost.Status.UNAVAILABLE, List.of(usd), List.of("X"))),
                // one amount per currency
                () -> assertThrows(IllegalArgumentException.class, () -> ReplayCost.available(usd, usd)),
                // never stricter than the Store: a partial cost needs no reason, and currencies are not capped
                () -> assertEquals(ReplayCost.Status.PARTIAL,
                        new ReplayCost(ReplayCost.Status.PARTIAL, List.of(usd), List.of()).status()),
                () -> assertEquals(5, ReplayCost.available(usd, new Money(BigDecimal.ONE, "EUR"),
                        new Money(BigDecimal.ONE, "GBP"), new Money(BigDecimal.ONE, "JPY"),
                        new Money(BigDecimal.ONE, "CHF")).amounts().size()),
                () -> assertThrows(IllegalArgumentException.class, () -> new ChainOutcome(0,
                        ReplayCost.available(usd))),
                () -> assertEquals(List.of("EUR", "USD"), ReplayCost.available(usd,
                        new Money(BigDecimal.ONE, "EUR")).amounts().stream().map(Money::currency).toList()));
    }

    @Test
    void aLoggedObservationMustAnswerTheLoggedDecision() {
        var fixture = fixtures.chosenRouteOnly(6);
        var first = (OutcomeOracle.ChosenRouteOnly) fixture.tasks().get(0).oracle();
        var second = (OutcomeOracle.ChosenRouteOnly) fixture.tasks().get(1).oracle();

        assertThrows(IllegalArgumentException.class, () -> new OutcomeOracle.ChosenRouteOnly(
                first.loggedDecision(), second.observed(), first.chain()));
    }

    @Test
    void loggedObservationsMustBeRevisionsOfOneSample() {
        var fixture = fixtures.chosenRouteOnly(6);
        ReplayTask task = fixture.tasks().getFirst();
        var logged = (OutcomeOracle.ChosenRouteOnly) task.oracle();
        RoutingObservation original = logged.observed().getFirst();
        var scripted = ScriptedOutcome.passing(original.ledgerSequence(), SyntheticRoutingFixtures.GATES,
                original.outcome().resources(), logged.chain()).corrected(new ScriptedOutcome.Correction(
                        original.ledgerSequence() + 4096, OutcomeStatus.REJECTED,
                        Map.of(QualityDimension.TESTS, QualityState.FAIL, QualityDimension.SECURITY, QualityState.PASS),
                        new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE"))));
        var factory = new ObservationFactory();
        RoutingObservation revision0 = factory.observe(task.request(), logged.loggedDecision(), scripted, 0);
        RoutingObservation revision1 = factory.observe(task.request(), logged.loggedDecision(), scripted, 1);
        // another sample reference, at the same position
        var otherSample = new RoutingObservation(revision0.outcome(), "exp-other", 0, revision0.ledgerSequence(),
                Feature.unknown());
        // revision 1 placed at the original's own position
        var notLater = new RoutingObservation(revision0.outcome(), revision0.experienceRef(), 1,
                revision0.ledgerSequence(), Feature.known(revision0.ledgerSequence() - 1));

        assertAll(
                () -> assertEquals(2, new OutcomeOracle.ChosenRouteOnly(logged.loggedDecision(),
                        List.of(revision1, revision0), logged.chain()).observed().size()),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeOracle.ChosenRouteOnly(
                        logged.loggedDecision(), List.of(revision0, otherSample), logged.chain())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeOracle.ChosenRouteOnly(
                        logged.loggedDecision(), List.of(revision0, revision0), logged.chain())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeOracle.ChosenRouteOnly(
                        logged.loggedDecision(), List.of(revision0, notLater), logged.chain())));
    }

    @Test
    void everyCorrectionSupersedesTheOriginalPositionAsTheStoreTranslatorWritesIt() {
        var fixture = fixtures.chosenRouteOnly(6);
        ReplayTask task = fixture.tasks().getFirst();
        var logged = (OutcomeOracle.ChosenRouteOnly) task.oracle();
        RoutingObservation revision0 = logged.observed().getFirst();
        long l0 = revision0.ledgerSequence();
        // the translator gives every corrected revision the sample's first position, and revisions may skip
        RoutingObservation revision1 = revision(revision0, 1, l0 + 10, l0);
        RoutingObservation revision2 = revision(revision0, 2, l0 + 20, l0);
        RoutingObservation revision3 = revision(revision0, 3, l0 + 30, l0);
        RoutingObservation revision2OnRevision1 = revision(revision0, 2, l0 + 20, l0 + 10);

        assertAll(
                () -> assertEquals(3, chosen(logged, revision0, revision1, revision2).observed().size()),
                () -> assertEquals(2, chosen(logged, revision0, revision3).observed().size()),
                // an export that no longer lists revision 0
                () -> assertEquals(2, chosen(logged, revision1, revision2).observed().size()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> chosen(logged, revision0, revision1, revision2OnRevision1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> chosen(logged, revision1, revision2OnRevision1)));
    }

    private static OutcomeOracle.ChosenRouteOnly chosen(OutcomeOracle.ChosenRouteOnly logged,
            RoutingObservation... observed) {
        return new OutcomeOracle.ChosenRouteOnly(logged.loggedDecision(), List.of(observed), logged.chain());
    }

    /** A later revision of {@code original} at {@code position}, exported as of that position. */
    private static RoutingObservation revision(RoutingObservation original, long revision, long position,
            long supersedes) {
        RoutingOutcome o = original.outcome();
        var outcome = new RoutingOutcome(o.contractVersion(), o.decisionRef(), o.executedRoute(), o.cohortBinding(),
                o.catalogVersion(), o.routingPolicyId(), o.routingPolicyVersion(), o.sourceFingerprint(),
                o.contextFingerprint(), o.constraintsFingerprint(), o.evaluationPolicyId(), o.evaluationPolicyVersion(),
                position, o.invocation(), o.outcomeStatus(), o.outcomeOrigin(), o.acceptedAttemptId(),
                o.acceptedAttemptStatus(), o.outcomeSequence(), o.observations(), o.judgedRevisions(), o.resources(),
                o.attribution());
        return new RoutingObservation(outcome, original.experienceRef(), revision, position, Feature.known(supersedes));
    }

    @Test
    void cohortsCoverEveryServedRouteOfEveryBucket() {
        var cohorts = fixtures.workload(240).cohortKeys();
        // two learned stages x four buckets x two subscription routes, plus qa x four buckets x api-x
        assertAll(
                () -> assertEquals(20, cohorts.size()),
                () -> assertEquals(4, cohorts.stream().filter(k -> k.route().equals(new RouteKey("api-x", 1)))
                        .count()));
    }
}
