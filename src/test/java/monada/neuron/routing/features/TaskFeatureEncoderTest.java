package monada.neuron.routing.features;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.ScalarResonanceMetric;
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
    private static final long ALLOCATION_BOUND_BYTES = 4 * 1024;
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
                .stageKind("implement")                     // k=1 of n=4  -> 1   * (1 + 2/5)  = 1.4
                .category("bugfix")                         // k=0 of n=6  -> 3   * (1 + 1/7)  = 24/7
                .languages(List.of("rust", "java"))         // n=8: java k=0 -> 9 * 10/9 = 10 ; rust k=6 -> 9 * 16/9 = 16
                .domains(List.of("backend"))                // k=0 of n=6  -> 27  * (1 + 1/7)  = 216/7
                .changeSize(100)                            // bitLength 7, L=16 -> 81 * (1 + 7/16) = 1863/16
                .contextSize(1_048_575)                     // bitLength 20, L=20 -> 243 * 2 = 486
                .tests(Requirement.REQUIRED)                // 2/3 -> 729 * 5/3 = 1215
                .security(Requirement.NOT_REQUIRED)         // 1/3 -> 2187 * 4/3 = 2916
                .build();

        Encoded encoded = encode(features);
        List<Signal> s = encoded.signals();

        assertEquals(9, s.size());
        assertSignal(s.get(0), 1.0, 1.4, 0.0);
        assertSignal(s.get(1), 1.0, 24.0 / 7, 0.0);
        assertSignal(s.get(2), 1.0, 10.0, 0.0);
        assertSignal(s.get(3), 1.0, 16.0, 0.0);
        assertSignal(s.get(4), 1.0, 216.0 / 7, 0.0);
        assertSignal(s.get(5), 1.0, 116.4375, 0.0);
        assertSignal(s.get(6), 1.0, 486.0, 0.0);
        assertSignal(s.get(7), 1.0, 1215.0, 0.0);
        assertSignal(s.get(8), 1.0, 2916.0, 0.0);
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
        for (int i = 0; i < 8; i++) {
            assertSignal(unknown.signals().get(i), 1.0, 2.5 * Math.pow(3, i), Math.PI);
            assertNotEquals(new Signal(SignalKind.OBSERVATION, FrequencyState.ZERO), unknown.signals().get(i));
        }
        // Measured zero: band base (change size band starts at 3^4 = 81), phase 0.
        assertSignal(zero.signals().get(3), 1.0, 81.0, 0.0);
        assertNotEquals(unknown.signals().get(4), zero.signals().get(3));
        // 8 dimensions minus the known empty language set, which emits no signal.
        assertEquals(7, zero.signals().size());
    }

    @Test
    void unknownMatchesOnlyUnknownUnderTheResonanceMetric() {
        ScalarResonanceMetric metric = new ScalarResonanceMetric();
        List<Signal> unknownA = encode(TaskFeatures.builder().build()).signals();
        List<Signal> unknownB = encode(TaskFeatures.builder().build()).signals();
        List<Signal> known = encode(TaskFeatures.builder().stageKind("plan").category("docs").languages(List.of("go"))
                .domains(List.of("data")).changeSize(7).contextSize(7).tests(Requirement.REQUIRED)
                .security(Requirement.REQUIRED).build()).signals();

        for (int i = 0; i < 8; i++) {
            assertEquals(1.0, metric.score(unknownA.get(i).frequencyState(), unknownB.get(i).frequencyState()), EPS);
            assertEquals(0.0, metric.score(unknownA.get(i).frequencyState(), known.get(i).frequencyState()), EPS);
        }
    }

    @Test
    void everyDimensionIsEquallyWeightedUnderTheResonanceMetric() {
        ScalarResonanceMetric metric = new ScalarResonanceMetric();
        // Extreme known values of each dimension: first vocabulary entry / zero versus OTHER / saturation.
        TaskFeatures low = TaskFeatures.builder().stageKind("plan").category("bugfix").languages(List.of("java"))
                .domains(List.of("backend")).changeSize(0).contextSize(0).tests(Requirement.NOT_REQUIRED)
                .security(Requirement.NOT_REQUIRED).build();
        TaskFeatures high = TaskFeatures.builder().stageKind("zz").category("zz").languages(List.of("zz"))
                .domains(List.of("zz")).changeSize(Long.MAX_VALUE).contextSize(Long.MAX_VALUE)
                .tests(Requirement.REQUIRED).security(Requirement.REQUIRED).build();
        List<Signal> a = encode(low).signals();
        List<Signal> b = encode(high).signals();

        for (int i = 0; i < 8; i++) {
            double r = metric.score(a.get(i).frequencyState(), b.get(i).frequencyState());
            assertTrue(r >= 0.5 && r < 1.0, "dimension " + i + " resonance " + r);
        }
    }

    @Test
    void numericRangeSaturatesAtTheCeilingAndIsReported() {
        Encoded atCeiling = encode(TaskFeatures.builder().changeSize(65_535).build());
        Encoded above = encode(TaskFeatures.builder().changeSize(65_536).contextSize(Long.MAX_VALUE).build());
        Encoded huge = encode(TaskFeatures.builder().changeSize(Long.MAX_VALUE).build());

        assertTrue(atCeiling.saturatedDimensions().isEmpty());
        assertSignal(atCeiling.signals().get(4), 1.0, 162.0, 0.0);

        assertEquals(List.of(FeatureDimension.CHANGE_SIZE, FeatureDimension.CONTEXT_SIZE), above.saturatedDimensions());
        assertSignal(above.signals().get(4), 1.0, 162.0, 0.0);
        assertSignal(above.signals().get(5), 1.0, 486.0, 0.0);
        assertSignal(huge.signals().get(4), 1.0, 162.0, 0.0);
        // Saturation loses information in Signals but never in the typed features.
        assertNotEquals(atCeiling.features(), above.features());
    }

    @Test
    void saturationFlagIsExactlyTheClampCondition() {
        for (long value : new long[] {0, 1, 2, 3, 255, 256, 65_534, 65_535, 65_536, 131_071, 1L << 40}) {
            Encoded encoded = encode(TaskFeatures.builder().changeSize(value).build());
            boolean flagged = encoded.saturatedDimensions().contains(FeatureDimension.CHANGE_SIZE);
            double frequency = encoded.signals().get(4).frequencyState().frequency();

            assertEquals(value > 65_535, flagged, "value " + value);
            // Values above the ceiling all encode at the band top; values at or below it never exceed it.
            assertEquals(flagged, frequency == 162.0 && value > 65_535, "value " + value);
            assertTrue(frequency <= 162.0, "value " + value);
        }
    }

    @Test
    void outOfVocabularyTokensShareTheOtherPositionButTypedFieldsDiffer() {
        Encoded a = encode(TaskFeatures.builder().category("zebra-migration").build());
        Encoded b = encode(TaskFeatures.builder().category("quantum-tuning").build());

        assertEquals(a.signals().get(1), b.signals().get(1));
        assertSignal(a.signals().get(1), 1.0, 6.0, 0.0);
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
    void encodingIsDeterministicAndEverySignalNamesItsDimension() {
        TaskFeatures features = TaskFeatures.builder().stageKind("qa").category("docs")
                .languages(List.of("java", "csharp")).domains(List.of("security")).changeSize(5).contextSize(9)
                .tests(Requirement.REQUIRED).security(Requirement.REQUIRED).build();

        Encoded first = encode(features);
        Encoded second = encode(features);

        assertEquals(first, second);
        int[] dimensionOfSignal = {0, 1, 2, 2, 3, 4, 5, 6, 7};
        assertEquals(dimensionOfSignal.length, first.signals().size());
        for (int i = 0; i < dimensionOfSignal.length; i++) {
            assertEquals(dimensionOfSignal[i], dimensionOf(first.signals().get(i)), "signal " + i);
        }
    }

    @Test
    void dimensionsAreRecoverableFromFrequencyWhateverTheTagCounts() {
        TaskFeatures few = TaskFeatures.builder().languages(List.of("java")).domains(List.of()).changeSize(0)
                .contextSize(1_048_575).build();
        TaskFeatures many = TaskFeatures.builder().languages(List.of("java", "go", "rust"))
                .domains(List.of("data", "backend")).changeSize(0).contextSize(1_048_575).build();

        for (TaskFeatures features : List.of(few, many, TaskFeatures.builder().build())) {
            for (Signal signal : encode(features).signals()) {
                int dimension = dimensionOf(signal);
                double base = Math.pow(3, dimension);
                double f = signal.frequencyState().frequency();
                boolean inBand = f >= base && f <= 2 * base;
                boolean unknownMarker = f == 2.5 * base && signal.frequencyState().phase() == Math.PI;
                assertTrue(inBand || unknownMarker, "frequency " + f + " not in dimension " + dimension);
            }
        }
        // Closed lower bound: a measured zero sits exactly on its band base.
        assertEquals(81.0, encode(few).signals().get(3).frequencyState().frequency(), EPS);
    }

    /** Band lookup: dimension {@code i} owns frequencies in {@code [3^i, 3^(i+1))}. */
    private static int dimensionOf(Signal signal) {
        double frequency = signal.frequencyState().frequency();
        int dimension = 0;
        while (frequency >= Math.pow(3, dimension + 1)) {
            dimension++;
        }
        return dimension;
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
    void maximumCapacityStaysWithinTheDocumentedSignalBoundAndAllocation() {
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
        assertEquals(List.of(FeatureDimension.DOMAINS), encoded.otherBucketDimensions());

        // Measured (not modeled) allocation of one full-capacity encode; an upper-bound check, not a benchmark.
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 20_000; i++) {
            encoder.encode(full);
        }
        long before = threads.getThreadAllocatedBytes(thread);
        encoder.encode(full);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated <= ALLOCATION_BOUND_BYTES, "allocated " + allocated + " bytes");
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
        for (long bad : new long[] {-1, 0, 2, 100, 65_536, 1L << 40}) {
            assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 1, ok.stageKinds(),
                    ok.categories(), ok.languages(), ok.domains(), bad, 1), "ceiling " + bad);
            assertThrows(IllegalArgumentException.class, () -> new EncodingPolicy("p", 1, ok.stageKinds(),
                    ok.categories(), ok.languages(), ok.domains(), 1, bad), "ceiling " + bad);
        }
        for (long good : new long[] {1, 3, 255, 65_535, Long.MAX_VALUE}) {
            new EncodingPolicy("p", 1, ok.stageKinds(), ok.categories(), ok.languages(), ok.domains(), good, good);
        }
        assertFalse(ok.stageKinds().isEmpty());
    }

    @Test
    void emptyVocabularyMapsEveryTokenToOther() {
        EncodingPolicy empty = new EncodingPolicy("empty", 1, List.of(), List.of(), List.of(), List.of(), 1, 1);
        Encoded encoded = assertInstanceOf(Encoded.class,
                new TaskFeatureEncoder(empty).encode(TaskFeatures.builder().stageKind("any").build()));

        assertSignal(encoded.signals().get(0), 1.0, 2.0, 0.0);
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
