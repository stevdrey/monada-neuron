package monada.neuron.evaluation.routing;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.ResourceSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationFactoryTest {

    private final ObservationFactory observations = new ObservationFactory();

    @Test
    void separatorsInsideOpaqueTokensCannotMergeTwoDecisions() {
        // plain "/"-joined execution and attempt would both read "a/b/c"
        var first = request("a/b", "c");
        var second = request("a", "b/c");

        assertAll(
                () -> assertNotEquals(observations.experienceRef(first), observations.experienceRef(second)),
                () -> assertEquals(observations.experienceRef(first), observations.experienceRef(request("a/b", "c"))),
                () -> assertTrue(observations.experienceRef(first).startsWith(ObservationFactory.REF_PREFIX)));
    }

    @Test
    void anUnpairedSurrogateNeverSharesAReferenceWithItsReplacement() {
        assertAll(
                // routing tokens must be well-formed UTF-16 (OpaqueTokens), so such a request cannot even be built
                () -> assertThrows(IllegalArgumentException.class, () -> request("exe-\uD800", "att")),
                () -> assertNotEquals(observations.repairAttemptId("att-\uD800"),
                        observations.repairAttemptId("att-?")));
    }

    @Test
    void theReferenceStaysShortForMaximumLengthFields() {
        String longest = "x".repeat(128);
        assertTrue(observations.experienceRef(request(longest, longest)).length()
                <= ObservationFactory.REF_PREFIX.length() + 64);
    }

    @Test
    void aRepairOfAMaximumLengthAttemptStaysWithinTheTokenBound() {
        var request = request("exe", "a".repeat(128));
        var decision = (RoutingDecision.Selected) SyntheticRoutingFixtures.POLICY.decide(request,
                SyntheticRoutingFixtures.FULL, RoutingPreference.empty(request,
                        SyntheticRoutingFixtures.DEFINITION.state(), SyntheticRoutingFixtures.POLICY.policyId(),
                        SyntheticRoutingFixtures.POLICY.policyVersion()));
        var repaired = ScriptedOutcome.passing(20L, SyntheticRoutingFixtures.GATES, ResourceSummary.unrecorded(),
                new ChainOutcome(2, ReplayCost.unavailable("NOT_PRICED"))).repaired();
        var accepted = observations.observe(request, decision, repaired, 0).outcome().acceptedAttemptId();

        String repair = assertInstanceOf(Feature.Known.class, accepted).value().toString();
        assertAll(
                () -> assertEquals(39, repair.length()),
                () -> assertNotEquals(request.attemptId(), repair),
                () -> assertEquals(repair, observations.repairAttemptId(request.attemptId())));
    }

    @Test
    void theDefaultObservationIsTheExecutionOfTheAdvisedRouteUnderItsOwnBinding() {
        var request = request("exe", "att");
        var decision = decide(request);

        assertEquals(observations.observe(request, decision, passing(), 0, decision.route(), decision.cohortBinding()),
                observations.observe(request, decision, passing(), 0));
    }

    @Test
    void anOverrideNamesTheRouteThatRanWhileStillAnsweringTheDecision() {
        var request = request("exe", "att");
        var decision = decide(request);
        RouteKey other = decision.route().equals(SyntheticRoutingFixtures.SUB_A)
                ? SyntheticRoutingFixtures.SUB_B : SyntheticRoutingFixtures.SUB_A;

        var outcome = observations.observe(request, decision, passing(), 0, other).outcome();

        assertAll(
                () -> assertEquals(other, outcome.executedRoute()),
                () -> assertEquals(DecisionRef.of(request), outcome.decisionRef()),
                () -> assertEquals(decision.cohortBinding(), outcome.cohortBinding()),
                () -> assertEquals(decision.provenance().catalogVersion(), outcome.catalogVersion()),
                () -> assertEquals(decision.provenance().policyId(), outcome.routingPolicyId()));
    }

    @Test
    void aCallerChosenBindingIsReportedAsGivenForTheForeignEvidenceProbe() {
        var request = request("exe", "att");
        var decision = decide(request);
        var own = decision.cohortBinding();
        var foreign = new CohortBinding(own.cohortBucket(), own.bucketMappingVersion(), own.mappingVersion(),
                own.featureSchemaVersion() + "-other");

        var outcome = observations.observe(request, decision, passing(), 0, decision.route(), foreign).outcome();

        assertEquals(foreign, outcome.cohortBinding());
    }

    @Test
    void anObservationWithoutAnExecutedRouteOrABindingIsRejected() {
        var request = request("exe", "att");
        var decision = decide(request);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> observations.observe(request, decision, passing(), 0, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> observations.observe(request, decision, passing(), 0, decision.route(), null)));
    }

    private static RoutingDecision.Selected decide(RoutingRequest request) {
        return (RoutingDecision.Selected) SyntheticRoutingFixtures.POLICY.decide(request,
                SyntheticRoutingFixtures.FULL, RoutingPreference.empty(request,
                        SyntheticRoutingFixtures.DEFINITION.state(), SyntheticRoutingFixtures.POLICY.policyId(),
                        SyntheticRoutingFixtures.POLICY.policyVersion()));
    }

    private static ScriptedOutcome passing() {
        return ScriptedOutcome.passing(20L, SyntheticRoutingFixtures.GATES, ResourceSummary.unrecorded(),
                new ChainOutcome(1, ReplayCost.unavailable("NOT_PRICED")));
    }

    private static RoutingRequest request(String execution, String attempt) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", execution, attempt, "stage",
                "implement", 1L, "src", "ctx", "con", "gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false, 10L);
    }
}
