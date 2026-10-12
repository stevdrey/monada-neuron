package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host's binding of policy identity, decision contract and verifier fails closed at construction. */
class TrustedRoutingProfileTest {

    private static RoutingDecisionVerifier verifier(String id, String version, String contract) {
        return new RoutingDecisionVerifier() {
            @Override
            public String policyId() {
                return id;
            }

            @Override
            public String policyVersion() {
                return version;
            }

            @Override
            public String decisionContractVersion() {
                return contract;
            }

            @Override
            public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
            }
        };
    }

    @Test
    void theLexicographicFactoryBindsTheReferenceIdentityAndTheHostsOwnConfiguration() {
        var profile = TrustedRoutingProfile.lexicographic(LexicographicRoutingPolicy.reference());

        assertEquals("route-lex", profile.policyId());
        assertEquals("1", profile.policyVersion());
        assertEquals(RoutingDecision.CONTRACT_VERSION, profile.decisionContractVersion());
        assertEquals(profile.verifier().policyId(), profile.policyId());
    }

    @Test
    void anAbsentVerifierOrPolicyIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new TrustedRoutingProfile("p", "1", RoutingDecision.CONTRACT_VERSION, null));
        assertThrows(NullPointerException.class, () -> TrustedRoutingProfile.lexicographic(null));
    }

    @Test
    void aVerifierForAnotherPolicyIdentityIsRejected() {
        var other = verifier("other", "1", RoutingDecision.CONTRACT_VERSION);

        var wrongId = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile("p", "1", RoutingDecision.CONTRACT_VERSION, other));
        var wrongVersion = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile("other", "2", RoutingDecision.CONTRACT_VERSION, other));

        assertTrue(wrongId.getMessage().contains("other/1"), wrongId.getMessage());
        assertTrue(wrongVersion.getMessage().contains("other/1"), wrongVersion.getMessage());
    }

    @Test
    void anUnimplementedDecisionContractIsRejectedWithAClearDiagnostic() {
        var successor = verifier("p", "1", "routing-decision/2");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile("p", "1", "routing-decision/2", successor));

        assertTrue(failure.getMessage().contains("unsupported routing decision contract routing-decision/2"),
                failure.getMessage());
    }

    @Test
    void aVerifierCheckingAnotherContractThanTheProfileTargetsIsRejected() {
        var successor = verifier("p", "1", "routing-decision/2");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile("p", "1", RoutingDecision.CONTRACT_VERSION, successor));

        assertTrue(failure.getMessage().contains("routing-decision/2"), failure.getMessage());
    }

    @Test
    void trustedVerifierRejectsConfigurationThatContradictsItsStateDefinition() {
        var definition = RoutingStateDefinition.reference();
        var wrongMapping = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(),
                "routing-state/2", definition.cohortMapping().featureSchemaVersion());
        var wrongSchema = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(),
                definition.mappingVersion(), "task-features/0");

        assertThrows(IllegalArgumentException.class, () -> LexicographicDecisionVerifier.trusted(wrongMapping, definition));
        assertThrows(IllegalArgumentException.class, () -> LexicographicDecisionVerifier.trusted(wrongSchema, definition));
        assertThrows(NullPointerException.class, () -> LexicographicDecisionVerifier.trusted(null, definition));
        assertThrows(NullPointerException.class,
                () -> LexicographicDecisionVerifier.trusted(RoutingFixtures.PARAMETERS, null));
    }
}
