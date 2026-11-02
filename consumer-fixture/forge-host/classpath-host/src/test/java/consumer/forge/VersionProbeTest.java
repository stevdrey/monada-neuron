package consumer.forge;

import consumer.forge.VersionProbe.Expectation;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The reflective version probe of readiness contract Section 5.2 against the published core. */
class VersionProbeTest {

    // covers: AC-1
    @Test
    void publishedCoreMatchesForgeRoutingV1() {
        assertEquals(Optional.empty(), VersionProbe.mismatch(VersionProbe.forgeRoutingV1()));
    }

    // covers: AC-1
    @Test
    void forgeRoutingV1NamesTheFiveContractConstants() {
        var names = VersionProbe.forgeRoutingV1().stream()
                .map(e -> e.type().getSimpleName() + "." + e.field())
                .toList();

        assertEquals(List.of("RoutingRequest.CONTRACT_VERSION", "TaskFeatures.SCHEMA_VERSION",
                "RoutingDecision.CONTRACT_VERSION", "LexicographicRoutingPolicy.POLICY_ID",
                "LexicographicRoutingPolicy.POLICY_VERSION"), names);
    }

    // covers: AC-2
    @Test
    void aDifferentValueNamesTheFieldExpectedAndFound() {
        var mismatch = VersionProbe.mismatch(List.of(
                new Expectation(TaskFeatures.class, "SCHEMA_VERSION", "task-features/2")));

        assertEquals(Optional.of("TaskFeatures.SCHEMA_VERSION expected task-features/2, found task-features/1"),
                mismatch);
    }

    // covers: AC-2
    @Test
    void aMissingFieldIsReportedAsUnreadable() {
        var mismatch = VersionProbe.mismatch(List.of(
                new Expectation(RoutingDecision.class, "NO_SUCH_FIELD", "x")));

        assertEquals(Optional.of("RoutingDecision.NO_SUCH_FIELD could not be read: NoSuchFieldException"), mismatch);
    }

    // covers: AC-2
    @Test
    void aFieldThatIsNotAStringIsAMismatch() {
        var mismatch = VersionProbe.mismatch(List.of(
                new Expectation(RouteCatalog.class, "MAX_ROUTES", "32")));

        assertEquals(Optional.of("RouteCatalog.MAX_ROUTES is not a String"), mismatch);
    }

    // covers: AC-2
    @Test
    void theFirstMismatchWinsAndLaterExpectationsAreNotRead() {
        var mismatch = VersionProbe.mismatch(List.of(
                new Expectation(RoutingRequest.class, "CONTRACT_VERSION", "forge-routing/1"),
                new Expectation(LexicographicRoutingPolicy.class, "POLICY_ID", "other-policy"),
                new Expectation(RoutingDecision.class, "NO_SUCH_FIELD", "x")));

        assertEquals(Optional.of("LexicographicRoutingPolicy.POLICY_ID expected other-policy, found route-lex"),
                mismatch);
    }

    // covers: AC-2
    @Test
    void noExpectationsMeansNoMismatch() {
        assertEquals(Optional.empty(), VersionProbe.mismatch(List.of()));
    }

    // covers: AC-2
    @Test
    void expectationRejectsNullParts() {
        assertThrows(NullPointerException.class, () -> new Expectation(null, "f", "v"));
        assertThrows(NullPointerException.class, () -> new Expectation(TaskFeatures.class, null, "v"));
        assertThrows(NullPointerException.class, () -> new Expectation(TaskFeatures.class, "f", null));
    }
}
