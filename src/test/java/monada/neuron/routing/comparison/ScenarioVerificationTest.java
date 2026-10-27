package monada.neuron.routing.comparison;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.TrustedRoutingAdvice;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import static monada.neuron.routing.comparison.ComparisonFixtures.A;
import static monada.neuron.routing.comparison.ComparisonFixtures.B;
import static monada.neuron.routing.comparison.ComparisonFixtures.REFERENCE;
import static monada.neuron.routing.comparison.ComparisonFixtures.TIER_ONE;
import static monada.neuron.routing.comparison.ComparisonFixtures.catalog;
import static monada.neuron.routing.comparison.ComparisonFixtures.cold;
import static monada.neuron.routing.comparison.ComparisonFixtures.entry;
import static monada.neuron.routing.comparison.ComparisonFixtures.request;
import static monada.neuron.routing.comparison.ComparisonFixtures.scenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each side of a preview is decided once and checked like a trusted stage before anything is compared. */
class ScenarioVerificationTest {

    private final RoutingRequest request = request().build();
    private final RoutingInput input = new RoutingInput(request, catalog(), cold(request));

    /** Counts calls and can answer for another catalog than the one it was given. */
    private static final class Probe implements RoutingPolicy {
        private final RouteCatalog answerFor;
        private int calls;

        Probe(RouteCatalog answerFor) {
            this.answerFor = answerFor;
        }

        @Override
        public String policyId() {
            return REFERENCE.policyId();
        }

        @Override
        public String policyVersion() {
            return REFERENCE.policyVersion();
        }

        @Override
        public RoutingDecision decide(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {
            calls++;
            return REFERENCE.decide(request, answerFor == null ? catalog : answerFor, preference);
        }
    }

    // covers: AC-4
    @Test
    void trustedAdviceReturnsThePolicysDecisionUnchanged() {
        assertEquals(REFERENCE.decide(request, catalog(), cold(request)),
                new TrustedRoutingAdvice().decide(REFERENCE, TrustedRoutingProfile.lexicographic(REFERENCE), input));
    }

    @Test
    void eachSideDecidesExactlyOnce() {
        var probe = new Probe(null);
        var side = new RoutingScenario(probe, TrustedRoutingProfile.lexicographic(REFERENCE), input);

        new ScenarioComparator().compare(side, side);

        assertEquals(2, probe.calls);
    }

    @Test
    void aDecisionForAnotherCatalogFailsTheWholeComparison() {
        var probe = new Probe(catalog("cat-other", entry(B, 1, 0)));
        var lying = new RoutingScenario(probe, TrustedRoutingProfile.lexicographic(REFERENCE), input);

        assertThrows(IllegalStateException.class,
                () -> new ScenarioComparator().compare(scenario(REFERENCE, input), lying));
    }

    @Test
    void aPolicyConfiguredOtherwiseThanItsProfileFailsVerification() {
        var profile = TrustedRoutingProfile.lexicographic(TIER_ONE.parameters(), RoutingStateDefinition.reference());
        var misconfigured = new RoutingScenario(REFERENCE, profile, input);

        assertThrows(IllegalStateException.class,
                () -> new ScenarioComparator().compare(misconfigured, scenario(REFERENCE, input)));
    }

    @Test
    void aProfileForAnotherPolicyIdentityIsRejected() {
        RoutingPolicy renamed = new RoutingPolicy() {
            @Override
            public String policyId() {
                return "route-other";
            }

            @Override
            public String policyVersion() {
                return "1";
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                return REFERENCE.decide(r, c, p);
            }
        };
        var profile = TrustedRoutingProfile.lexicographic(REFERENCE);

        assertThrows(IllegalArgumentException.class, () -> new RoutingScenario(renamed, profile, input));
        assertThrows(IllegalArgumentException.class, () -> new TrustedRoutingAdvice().decide(renamed, profile, input));
        assertEquals(A, ((RoutingDecision.Selected) new TrustedRoutingAdvice().decide(REFERENCE, profile, input))
                .route());
    }

    // covers: AC-4
    @Test
    void anIncomparablePairStillVerifiesBothSidesFirst() {
        RoutingRequest otherScope = request().scope("scope-other").build();
        var otherInput = new RoutingInput(otherScope, catalog(), cold(otherScope));
        var lying = new RoutingScenario(new Probe(catalog("cat-other", entry(B, 1, 0))),
                TrustedRoutingProfile.lexicographic(REFERENCE), otherInput);

        assertThrows(IllegalStateException.class,
                () -> new ScenarioComparator().compare(scenario(REFERENCE, input), lying));
    }

    @Test
    void trustedAdviceRequiresEveryPart() {
        var advice = new TrustedRoutingAdvice();
        var profile = TrustedRoutingProfile.lexicographic(REFERENCE);

        assertThrows(NullPointerException.class, () -> advice.decide(null, profile, input));
        assertThrows(NullPointerException.class, () -> advice.decide(REFERENCE, null, input));
        assertThrows(NullPointerException.class, () -> advice.decide(REFERENCE, profile, null));
    }
}
