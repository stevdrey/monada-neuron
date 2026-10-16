package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalEncoderTest {

    private final CanonicalEncoder encoder = new CanonicalEncoder();

    @Test
    void separatorsInsideOpaqueFieldsCannotMakeTwoRecordsEncodeAlike() {
        // both render "taskId=t, executionId=e, executionId=f" through record toString
        var first = request("t, executionId=e", "f");
        var second = request("t", "e, executionId=f");

        assertAll(
                () -> assertEquals(first.toString(), second.toString()),
                () -> assertFalse(Arrays.equals(encoder.encode(first), encoder.encode(second))),
                () -> assertArrayEquals(encoder.encode(first), encoder.encode(request("t, executionId=e", "f"))));
    }

    @Test
    void setsAndMapsEncodeIndependentlyOfIterationOrder() {
        var forward = new LinkedHashSet<>(List.of("a", "b", "c"));
        var backward = new LinkedHashSet<>(List.of("c", "b", "a"));
        var forwardMap = new LinkedHashMap<String, Integer>();
        forwardMap.put("x", 1);
        forwardMap.put("y", 2);
        var backwardMap = new LinkedHashMap<String, Integer>();
        backwardMap.put("y", 2);
        backwardMap.put("x", 1);

        assertAll(
                () -> assertArrayEquals(encoder.encode(forward), encoder.encode(backward)),
                () -> assertArrayEquals(encoder.encode(forward), encoder.encode(new HashSet<>(forward))),
                () -> assertArrayEquals(encoder.encode(forwardMap), encoder.encode(backwardMap)),
                // a list keeps its order
                () -> assertFalse(Arrays.equals(encoder.encode(List.of("a", "b")), encoder.encode(List.of("b", "a")))));
    }

    @Test
    void typesAndExactBitsAreDistinguished() {
        assertAll(
                () -> assertFalse(Arrays.equals(encoder.encode(0.0), encoder.encode(-0.0))),
                () -> assertFalse(Arrays.equals(encoder.encode(1L), encoder.encode(1))),
                () -> assertFalse(Arrays.equals(encoder.encode("1"), encoder.encode(1L))),
                () -> assertFalse(Arrays.equals(encoder.encode(Optional.empty()), encoder.encode(Optional.of("")))),
                () -> assertFalse(Arrays.equals(encoder.encode(List.of("ab", "c")), encoder.encode(List.of("a", "bc")))),
                () -> assertFalse(Arrays.equals(encoder.encode(Map.of("a", "bc")), encoder.encode(Map.of("ab", "c")))));
    }

    @Test
    void textIsEncodedLosslesslyEvenWithUnpairedSurrogates() {
        // a UTF-8 encoder would replace the unpaired surrogate with '?'
        assertAll(
                () -> assertFalse(Arrays.equals(encoder.encode("\uD800"), encoder.encode("?"))),
                () -> assertFalse(Arrays.equals(encoder.encode("\uD800"), encoder.encode("\uDC00"))),
                // a valid pair differs from either half and from the halves reversed
                () -> assertFalse(Arrays.equals(encoder.encode("\uD83D\uDE00"), encoder.encode("\uDE00\uD83D"))),
                // routing tokens must be well-formed UTF-16 (OpaqueTokens), so such a request cannot even be built
                () -> assertThrows(IllegalArgumentException.class, () -> request("\uD800", "f")));
    }

    @Test
    void anUnsupportedTypeIsRejectedRatherThanRenderedAsText() {
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new StringBuilder("x")));
    }

    private static RoutingRequest request(String taskId, String executionId) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", taskId, executionId, "att", "stage",
                "implement", 1L, "src", "ctx", "con", "gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false, 10L);
    }
}
