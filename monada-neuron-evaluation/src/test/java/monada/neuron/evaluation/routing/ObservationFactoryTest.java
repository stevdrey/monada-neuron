package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void theReferenceStaysShortForMaximumLengthFields() {
        String longest = "x".repeat(128);
        assertTrue(observations.experienceRef(request(longest, longest)).length()
                <= ObservationFactory.REF_PREFIX.length() + 64);
    }

    private static RoutingRequest request(String execution, String attempt) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", execution, attempt, "stage",
                "implement", 1L, "src", "ctx", "con", "gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false, 10L);
    }
}
