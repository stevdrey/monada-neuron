package monada.neuron.routing.features;

import monada.neuron.model.FrequencyState;
import monada.neuron.routing.features.EncodingResult.Encoded;
import monada.neuron.routing.features.EncodingResult.SchemaMismatch;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFeatureEncoderTest {

    private static final double EPS = 1e-12;
    private final TaskFeatureEncoder encoder = new TaskFeatureEncoder(EncodingPolicy.defaultV1());

    private Encoded encode(TaskFeatures features) {
        return assertInstanceOf(Encoded.class, encoder.encode(features));
    }

    private static void assertSignal(Signal signal, double amplitude, double frequency, double phase) {
        assertEquals(SignalKind.OBSERVATION, signal.kind());
        assertEquals(amplitude, signal.frequencyState().amplitude(), EPS);
        assertEquals(frequency, signal.frequencyState().frequency(), EPS);
        assertEquals(phase, signal.frequencyState().phase(), EPS);
    }

    @Test
    void goldenFixtureMatchesHandComputedValues() {
        TaskFeatures features = TaskFeatures.builder()
                .stageKind("implement")                     // k=1 of n=4  -> 0  + 8*2/5  = 3.2
                .category("bugfix")                         // k=0 of n=6  -> 10 + 8*1/7
                .languages(List.of("rust", "java"))         // java k=0 -> 20 + 8/9 ; rust k=6 -> 20 + 8*7/9
                .domains(List.of("backend"))                // k=0 of n=6  -> 30 + 8/7
                .changeSize(100)                            // bitLength 7, L=16 -> 40 + 8*7/16 = 43.5
                .contextSize(1_048_575)                     // bitLength 20, L=20 -> 50 + 8 = 58
                .tests(Requirement.REQUIRED)                // ordinal 1 -> 2/3 -> 60 + 16/3
                .security(Requirement.NOT_REQUIRED)         // ordinal 0 -> 1/3 -> 70 + 8/3
                .build();

        Encoded encoded = encode(features);
        List<Signal> s = encoded.signals();

        assertEquals(9, s.size());
        assertSignal(s.get(0), 1.0, 3.2, 0.0);
        assertSignal(s.get(1), 1.0, 10 + 8.0 / 7, 0.0);
        assertSignal(s.get(2), 1.0, 20 + 8.0 / 9, 0.0);
        assertSignal(s.get(3), 1.0, 20 + 56.0 / 9, 0.0);
        assertSignal(s.get(4), 1.0, 30 + 8.0 / 7, 0.0);
        assertSignal(s.get(5), 1.0, 43.5, 0.0);
        assertSignal(s.get(6), 1.0, 58.0, 0.0);
        assertSignal(s.get(7), 1.0, 60 + 16.0 / 3, 0.0);
        assertSignal(s.get(8), 1.0, 70 + 8.0 / 3, 0.0);
        assertEquals(features, encoded.features());
        assertEquals("task-encoding-default", encoded.policyId());
        assertEquals(1L, encoded.policyVersion());
        assertTrue(encoded.saturatedDimensions().isEmpty());
        assertTrue(encoded.otherBucketDimensions().isEmpty());
    }

    @Test
    void permutedInputYieldsEqualSignals() {
        TaskFeatures a = TaskFeatures.builder().languages(List.of("go", "java", "rust"))
                .domains(List.of("data", "backend")).build();
        TaskFeatures b = TaskFeatures.builder().languages(List.of("rust", "go", "java"))
                .domains(List.of("backend", "data")).build();

        assertEquals(a, b);
        assertEquals(encode(a).signals(), encode(b).signals());
    }

    @Test
    void unknownIsVisibleAndNeverAMeasuredZero() {
        Encoded unknown = encode(TaskFeatures.builder().build());
        Encoded zero = encode(TaskFeatures.builder().changeSize(0).contextSize(0).languages(List.of()).build());

        assertEquals(8, unknown.signals().size());
        for (Signal signal : unknown.signals()) {
            assertSignal(signal, 0.0, 0.0, Math.PI);
            assertNotEquals(new Signal(SignalKind.OBSERVATION, FrequencyState.ZERO), signal);
        }
        // Measured zero: full amplitude at the band base (change size band starts at 40).
        Signal measuredZero = zero.signals().stream()
                .filter(sig -> sig.frequencyState().frequency() == 40.0).findFirst().orElseThrow();
        assertSignal(measuredZero, 1.0, 40.0, 0.0);
        assertNotEquals(unknown.signals().get(4), measuredZero);
        // Known empty tag set emits nothing; unknown emits a marker.
        // 8 dimensions minus the known empty language set, which emits no signal.
        assertEquals(7, zero.signals().size());
    }

    @Test
    void numericRangeSaturatesAtTheCeilingAndIsReported() {
        Encoded atCeiling = encode(TaskFeatures.builder().changeSize(65_535).build());
        Encoded above = encode(TaskFeatures.builder().changeSize(65_536).contextSize(Long.MAX_VALUE).build());
        Encoded huge = encode(TaskFeatures.builder().changeSize(Long.MAX_VALUE).build());

        assertTrue(atCeiling.saturatedDimensions().isEmpty());
        assertSignal(atCeiling.signals().get(4), 1.0, 48.0, 0.0);

        assertEquals(List.of(FeatureDimension.CHANGE_SIZE, FeatureDimension.CONTEXT_SIZE), above.saturatedDimensions());
        assertSignal(above.signals().get(4), 1.0, 48.0, 0.0);
        assertSignal(above.signals().get(5), 1.0, 58.0, 0.0);
        assertSignal(huge.signals().get(4), 1.0, 48.0, 0.0);
        // Saturation loses information in Signals but never in the typed features.
        assertNotEquals(atCeiling.features(), above.features());
    }

    @Test
    void outOfVocabularyTokensShareTheOtherPositionButTypedFieldsDiffer() {
        Encoded a = encode(TaskFeatures.builder().category("zebra-migration").build());
        Encoded b = encode(TaskFeatures.builder().category("quantum-tuning").build());

        assertEquals(a.signals().get(1), b.signals().get(1));
        assertSignal(a.signals().get(1), 1.0, 10 + 8.0, 0.0);
        assertEquals(List.of(FeatureDimension.CATEGORY), a.otherBucketDimensions());
        assertNotEquals(a.features(), b.features());
        assertNotEquals(a.features().category(), b.features().category());
    }

    @Test
    void schemaMismatchIsReportedNotThrown() {
        TaskFeatures foreign = TaskFeatures.builder().schemaVersion("task-features/2").stageKind("plan").build();

        SchemaMismatch mismatch = assertInstanceOf(SchemaMismatch.class, encoder.encode(foreign));
        assertEquals("task-features/1", mismatch.expected());
        assertEquals("task-features/2", mismatch.actual());
    }

    @Test
    void encodingIsDeterministicAndBandsAreDisjoint() {
        TaskFeatures features = TaskFeatures.builder().stageKind("qa").category("docs")
                .languages(List.of("java", "csharp")).domains(List.of("security")).changeSize(5).contextSize(9)
                .tests(Requirement.REQUIRED).security(Requirement.REQUIRED).build();

        Encoded first = encode(features);
        Encoded second = encode(features);

        assertEquals(first, second);
        int[] dimensionOfSignal = {0, 1, 2, 2, 3, 4, 5, 6, 7};
        assertEquals(dimensionOfSignal.length, first.signals().size());
        for (int i = 0; i < dimensionOfSignal.length; i++) {
            double f = first.signals().get(i).frequencyState().frequency();
            assertTrue(f > 10.0 * dimensionOfSignal[i] && f <= 10.0 * dimensionOfSignal[i] + 8.0,
                    "signal " + i + " outside its band: " + f);
        }
    }

    @Test
    void signalsNeverDependOnAnythingButTheTypedFeatures() {
        // The encoder API accepts only TaskFeatures; identical features always give identical signals.
        TaskFeatures a = TaskFeatures.builder().stageKind("review").tests(Requirement.REQUIRED).build();
        TaskFeatures b = TaskFeatures.builder().stageKind("review").tests(Requirement.REQUIRED).build();
        assertEquals(encode(a).signals(), encode(b).signals());
        var encodeMethods = java.util.Arrays.stream(TaskFeatureEncoder.class.getMethods())
                .filter(m -> m.getName().equals("encode")).toList();
        assertEquals(1, encodeMethods.size());
        assertEquals(List.of(TaskFeatures.class), List.of(encodeMethods.get(0).getParameterTypes()));
    }

    @Test
    void maximumCapacityStaysWithinTheDocumentedSignalBound() {
        List<String> eightLanguages = List.of("java", "kotlin", "python", "typescript", "javascript", "go", "rust",
                "csharp");
        List<String> eightDomains = List.of("backend", "frontend", "data", "infrastructure", "security",
                "documentation", "x", "y");
        TaskFeatures full = TaskFeatures.builder().stageKind("qa").category("chore").languages(eightLanguages)
                .domains(eightDomains).changeSize(1).contextSize(1).tests(Requirement.REQUIRED)
                .security(Requirement.REQUIRED).build();

        Encoded encoded = encode(full);

        assertEquals(TaskFeatureEncoder.MAX_SIGNALS, encoded.signals().size());
        assertEquals(22, TaskFeatureEncoder.MAX_SIGNALS);
        // Modeled retained footprint: ~64 B per Signal+FrequencyState on a compressed-oops 64-bit HotSpot,
        // plus an immutable list array (~16 B header + 4 B per reference). A model, not a measurement.
        long modeledBytes = encoded.signals().size() * 64L + 16 + 4L * encoded.signals().size();
        assertTrue(modeledBytes <= 2048, "modeled footprint " + modeledBytes);
        assertEquals(List.of(FeatureDimension.DOMAINS), encoded.otherBucketDimensions());
    }

    @Test
    void policyValidatesVocabulariesAndCeilings() {
        EncodingPolicy ok = EncodingPolicy.defaultV1();
        List<String> tooMany = java.util.stream.IntStream.range(0, 33).mapToObj(i -> "t" + i).toList();

        assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 0, ok.stageKinds(),
                ok.categories(), ok.languages(), ok.domains(), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 1, tooMany,
                ok.categories(), ok.languages(), ok.domains(), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 1, List.of("a", "a"),
                ok.categories(), ok.languages(), ok.domains(), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 1, ok.stageKinds(),
                ok.categories(), ok.languages(), ok.domains(), 0, 10));
        assertFalse(ok.stageKinds().isEmpty());
    }

    @Test
    void emptyVocabularyMapsEveryTokenToOther() {
        EncodingPolicy empty = new EncodingPolicy("empty", 1, List.of(), List.of(), List.of(), List.of(), 1, 1);
        Encoded encoded = assertInstanceOf(Encoded.class,
                new TaskFeatureEncoder(empty).encode(TaskFeatures.builder().stageKind("any").build()));

        assertSignal(encoded.signals().get(0), 1.0, 8.0, 0.0);
        assertEquals(List.of(FeatureDimension.STAGE_KIND), encoded.otherBucketDimensions());
    }

    @Test
    void encodedConstructorEnforcesStructuralInvariants() {
        TaskFeatures features = TaskFeatures.builder().changeSize(1).build();
        Signal ok = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 1.0, 0.0));
        List<Signal> many = java.util.Collections.nCopies(TaskFeatureEncoder.MAX_SIGNALS + 1, ok);
        List<FeatureDimension> none = List.of();

        // Round-trip of a produced value is accepted.
        Encoded produced = encode(features);
        assertEquals(produced, new Encoded(produced.features(), produced.signals(), produced.policyId(),
                produced.policyVersion(), produced.saturatedDimensions(), produced.otherBucketDimensions()));

        for (SignalKind kind : List.of(SignalKind.FEEDBACK, SignalKind.INTERMEDIATE)) {
            Signal bad = new Signal(kind, new FrequencyState(1.0, 1.0, 0.0));
            assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(bad), "p", 1, none, none));
        }
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, many, "p", 1, none, none));
        assertEquals(TaskFeatureEncoder.MAX_SIGNALS, new Encoded(features,
                many.subList(0, TaskFeatureEncoder.MAX_SIGNALS), "p", 1, none, none).signals().size());
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "", 1, none, none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "x".repeat(129), 1, none, none));
        assertThrows(NullPointerException.class, () -> new Encoded(features, List.of(ok), null, 1, none, none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 0, none, none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", -1, none, none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 1,
                List.of(FeatureDimension.CHANGE_SIZE, FeatureDimension.CHANGE_SIZE), none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 1,
                List.of(FeatureDimension.CONTEXT_SIZE, FeatureDimension.CHANGE_SIZE), none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 1,
                List.of(FeatureDimension.TESTS), none));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 1, none,
                List.of(FeatureDimension.CHANGE_SIZE)));
        assertThrows(IllegalArgumentException.class, () -> new Encoded(features, List.of(ok), "p", 1, none,
                List.of(FeatureDimension.DOMAINS, FeatureDimension.CATEGORY)));
    }
}
