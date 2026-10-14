package monada.neuron.routing.adaptation;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.model.NodeType;
import monada.neuron.routing.ChangeSizeCohortMapping;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.RoutingFeedbackConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static monada.neuron.routing.adaptation.AdaptationFixtures.ADAPTATION;
import static monada.neuron.routing.adaptation.AdaptationFixtures.INITIAL;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SCORES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdaptiveStateDefinitionTest {

    private static final RoutingStateDefinition STATE =
            new RoutingStateDefinition("adaptive-test/1", ChangeSizeCohortMapping.DEFAULT);

    private static String rejection(InitialNodeState initial, AdaptationConfig adaptation) {
        return assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveStateDefinition(STATE, initial, adaptation, SCORES)).getMessage();
    }

    private static AdaptationConfig amplitudes(double min, double max) {
        return new AdaptationConfig(0.5, min, max, 0.0, 12.0, 0.0, 10_000.0, 2.0);
    }

    @Test
    void aZeroAmplitudeFloorIsAConfigurationError() {
        assertEquals("routing adaptation requires minAmplitude > 0, got: 0.0",
                rejection(INITIAL, AdaptationConfig.DEFAULT));
    }

    @Test
    void theBaselineMustLieStrictlyAboveTheFloorAndAtMostTheCeiling() {
        assertEquals("baselineAmplitude must lie in (minAmplitude, maxAmplitude] = (4.0, 6.0], got: 4.0",
                rejection(INITIAL, amplitudes(4.0, 6.0)));
        assertEquals("baselineAmplitude must lie in (minAmplitude, maxAmplitude] = (0.5, 3.0], got: 4.0",
                rejection(INITIAL, amplitudes(0.5, 3.0)));
        new AdaptiveStateDefinition(STATE, INITIAL, amplitudes(0.5, 4.0), SCORES);
    }

    @Test
    void theInitialEnergyAndFrequencyMustLieInsideTheAdaptationBounds() {
        assertEquals("initial energy must lie in [minEnergy, maxEnergy] = [0.0, 12.0], got: 13.0",
                rejection(new InitialNodeState(4.0, 50.0, 0.5, 13.0, NodeType.PROCESSOR), ADAPTATION));
        assertEquals("initial frequency must lie in [minFrequency, maxFrequency] = [60.0, 100.0], got: 50.0",
                rejection(INITIAL, new AdaptationConfig(0.5, 0.5, 6.0, 0.0, 12.0, 60.0, 100.0, 2.0)));
    }

    @Test
    void theInitialStateRejectsAZeroBaselineAndNonFiniteChannels() {
        assertEquals("baselineAmplitude must be finite and positive, got: 0.0", assertThrows(
                IllegalArgumentException.class,
                () -> new InitialNodeState(0.0, 0.0, 0.0, 0.0, NodeType.PROCESSOR)).getMessage());
        assertEquals("phase must be finite, got: NaN", assertThrows(IllegalArgumentException.class,
                () -> new InitialNodeState(1.0, 0.0, Double.NaN, 0.0, NodeType.PROCESSOR)).getMessage());
        assertEquals("nodeType must not be null", assertThrows(NullPointerException.class,
                () -> new InitialNodeState(1.0, 0.0, 0.0, 0.0, null)).getMessage());
    }

    @Test
    void theReferenceDefinitionIsValidAndVersioned() {
        var reference = AdaptiveStateDefinition.reference();
        assertEquals("adaptive-routing-state/1", reference.mappingVersion());
        assertEquals("task-features/1", reference.featureSchemaVersion());
        assertEquals(RoutingFeedbackConfig.DEFAULT, reference.feedback());
        assertEquals(1.0, reference.initial().baselineAmplitude());
        // a reward multiplies by 1.1, a penalty by 0.95: 22 rewards reach the ceiling, 41 penalties the floor
        assertEquals(22, (int) Math.ceil(Math.log(8.0) / Math.log(1.1)));
        assertEquals(41, (int) Math.ceil(Math.log(1.0 / 0.125) / -Math.log(0.95)));
    }

    @Test
    void theNodeIdentityIsTheNameBasedUuidOfTheLengthPrefixedEncoding() {
        var key = new CohortKey("scope-demo", "implement", "S", new RouteKey("sub-a", 7));
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes("forge-routing/1/node".getBytes(StandardCharsets.UTF_8));
        for (String part : List.of("scope-demo", "forge-gates", "1", "implement", "S", "sub-a", "adaptive-test/1")) {
            byte[] utf8 = part.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(ByteBuffer.allocate(4).putInt(utf8.length).array());
            bytes.writeBytes(utf8);
        }
        bytes.writeBytes(ByteBuffer.allocate(8).putLong(7L).array());
        assertEquals(UUID.nameUUIDFromBytes(bytes.toByteArray()),
                CohortNodeIdentity.of(key, "forge-gates", "1", "adaptive-test/1"));
        assertEquals(CohortNodeIdentity.of(key, "forge-gates", "1", "adaptive-test/1"),
                CohortNodeIdentity.of(key, "forge-gates", "1", "adaptive-test/1"));
    }

    @Test
    void distinctCohortsCannotCollideOnConcatenation() {
        var ab = new CohortKey("ab", "c", "S", new RouteKey("r", 1));
        var a = new CohortKey("a", "bc", "S", new RouteKey("r", 1));
        assertNotEquals(CohortNodeIdentity.of(ab, "p", "1", "m"), CohortNodeIdentity.of(a, "p", "1", "m"));
        var v1 = new CohortKey("s", "implement", "S", new RouteKey("r", 1));
        var v2 = new CohortKey("s", "implement", "S", new RouteKey("r", 2));
        assertNotEquals(CohortNodeIdentity.of(v1, "p", "1", "m"), CohortNodeIdentity.of(v2, "p", "1", "m"));
        assertNotEquals(CohortNodeIdentity.of(v1, "p", "1", "m"), CohortNodeIdentity.of(v1, "p", "2", "m"));
        assertNotEquals(CohortNodeIdentity.of(v1, "p", "1", "m"), CohortNodeIdentity.of(v1, "p", "1", "m2"));
    }
}
