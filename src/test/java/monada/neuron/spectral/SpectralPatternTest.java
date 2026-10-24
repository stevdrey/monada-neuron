package monada.neuron.spectral;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static monada.neuron.spectral.SpectralFixtures.DFT_8;
import static monada.neuron.spectral.SpectralFixtures.HAAR_4_L2;
import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static monada.neuron.spectral.SpectralFixtures.p4;
import static monada.neuron.spectral.SpectralFixtures.p9;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectralPatternTest {

    private static final double PI = StrictMath.PI;

    @Test
    void dftPatternExposesComponentsInHertz() {
        var pattern = p4();

        assertAll(
                () -> assertEquals(SpectralPattern.CURRENT_VERSION, pattern.version()),
                () -> assertEquals(SpectralBasis.DFT, pattern.basis()),
                () -> assertEquals(SpectralScale.SINGLE, pattern.scale()),
                () -> assertEquals(8, pattern.sampleCount()),
                () -> assertEquals(8.0, pattern.sampleRate()),
                () -> assertEquals(SampleUnit.HERTZ, pattern.unit()),
                () -> assertEquals(LocatorUnit.HERTZ, pattern.locatorUnit()),
                () -> assertEquals(5, pattern.componentCount()),
                () -> assertEquals(3.0, pattern.locator(3)),
                () -> assertEquals(0.5, pattern.magnitude(3)),
                () -> assertEquals(PI / 4, pattern.phase(3)),
                () -> assertEquals(new WaveComponent(0, 1, 1.0, 0.0, true, 1.0), pattern.component(1)),
                () -> assertEquals(PROVENANCE, pattern.provenance()));
    }

    @Test
    void abstractUnitLocatorsAreCyclesPerIndex() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 1.0,
                SampleUnit.ABSTRACT_PER_INDEX);
        var pattern = SpectralPattern.of(frame, 4.0, PROVENANCE,
                new int[] {0}, new int[] {2}, new double[] {1.0}, new double[] {0.0});

        assertEquals(LocatorUnit.CYCLES_PER_INDEX, pattern.locatorUnit());
        assertEquals(0.25, pattern.locator(0));
    }

    @Test
    void haarPatternUsesLevelZeroForTheApproximationAndPositionsAsLocators() {
        var pattern = p9();

        assertAll(
                () -> assertEquals(LocatorUnit.SAMPLE_POSITION, pattern.locatorUnit()),
                () -> assertEquals(3, pattern.scale().levelCount()),
                () -> assertEquals(0.0, pattern.locator(0)),
                () -> assertEquals(0.0, pattern.locator(1)),
                () -> assertEquals(2.0, pattern.locator(2)),
                () -> assertEquals(0.0, pattern.locator(3)),
                () -> assertEquals(PI, pattern.phase(3)),
                () -> assertEquals(70.0, pattern.retainedEnergy(), 1e-12),
                () -> assertEquals(70.0, pattern.sourceEnergy()));
    }

    @Test
    void retainedEnergyWeightsDcAndNyquistOnceForEvenLength() {
        // N = 8: DC 3, bin 1 magnitude 1, Nyquist bin 4 magnitude 1 -> 8 * (9 + 1/2 + 1)
        var pattern = SpectralPattern.of(DFT_8, 84.0, PROVENANCE,
                new int[] {0, 0, 0}, new int[] {0, 1, 4}, new double[] {3.0, 1.0, 1.0},
                new double[] {PI, 0.0, 0.0});

        assertEquals(84.0, pattern.retainedEnergy());
        assertEquals(PI, pattern.phase(0), "a negative DC component keeps phase π");
    }

    @Test
    void oddLengthHasNoNyquistBin() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 7, 7.0, SampleUnit.HERTZ);
        // floor(7/2) = 3 is an ordinary bin weighted by 1/2.
        var pattern = SpectralPattern.of(frame, 3.5, PROVENANCE,
                new int[] {0}, new int[] {3}, new double[] {1.0}, new double[] {0.0});

        assertEquals(3.5, pattern.retainedEnergy());
        assertEquals(4, frame.componentCapacity());
        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(frame, 0.0, PROVENANCE,
                new int[] {0}, new int[] {4}, new double[] {1.0}, new double[] {0.0}));
    }

    @Test
    void silenceHasNoDefinedPhaseAndZeroEnergy() {
        var silent = SpectralPattern.of(DFT_8, 0.0, PROVENANCE,
                new int[] {0, 0}, new int[] {0, 1}, new double[] {0.0, 0.0}, new double[] {0.0, 1.0});
        var empty = SpectralPattern.of(DFT_8, 4.0, PROVENANCE, new int[0], new int[0], new double[0], new double[0]);

        assertAll(
                () -> assertFalse(silent.phaseDefined(0)),
                () -> assertFalse(silent.phaseDefined(1)),
                () -> assertEquals(1.0, silent.phase(1), "the phase is stored even when undefined"),
                () -> assertEquals(0.0, silent.retainedEnergy()),
                () -> assertEquals(0, empty.componentCount()),
                () -> assertEquals(0.0, empty.retainedEnergy()),
                () -> assertEquals(4.0, empty.sourceEnergy(), "truncation keeps the source energy"),
                () -> assertTrue(empty.components().isEmpty()));
    }

    @Test
    void phaseDefinedIsRelativeToTheLargestMagnitude() {
        var pattern = SpectralPattern.of(DFT_8, 32.0, PROVENANCE,
                new int[] {0, 0, 0, 0}, new int[] {0, 1, 2, 3},
                new double[] {2.0, 2e-12, 1.9e-12, 0.0}, new double[] {0.0, 1.0, 1.0, 1.0});

        assertAll(
                () -> assertTrue(pattern.phaseDefined(0)),
                () -> assertTrue(pattern.phaseDefined(1)),
                () -> assertFalse(pattern.phaseDefined(2)),
                () -> assertFalse(pattern.phaseDefined(3)));
    }

    @Test
    void tinyPatternUsesTheRelativeThresholdButNeverDefinesZero() {
        var pattern = SpectralPattern.of(DFT_8, Double.MIN_VALUE, PROVENANCE,
                new int[] {0, 0}, new int[] {0, 1}, new double[] {Double.MIN_VALUE, 0.0}, new double[] {0.0, 0.0});

        assertTrue(pattern.phaseDefined(0));
        assertFalse(pattern.phaseDefined(1));
    }

    @Test
    void rejectsUnorderedDuplicateAndOutOfRangeKeys() {
        assertAll(
                () -> assertRejected(DFT_8, new int[] {0, 0}, new int[] {2, 1}),
                () -> assertRejected(DFT_8, new int[] {0, 0}, new int[] {1, 1}),
                () -> assertRejected(DFT_8, new int[] {0}, new int[] {5}),
                () -> assertRejected(DFT_8, new int[] {0}, new int[] {-1}),
                () -> assertRejected(DFT_8, new int[] {1}, new int[] {0}),
                () -> assertRejected(HAAR_4_L2, new int[] {0}, new int[] {1}),
                () -> assertRejected(HAAR_4_L2, new int[] {1}, new int[] {2}),
                () -> assertRejected(HAAR_4_L2, new int[] {3}, new int[] {0}),
                () -> assertRejected(HAAR_4_L2, new int[] {2, 1}, new int[] {0, 0}));
    }

    @Test
    void rejectsNonFiniteAndNegativeMagnitudesAndNonCanonicalPhases() {
        for (double magnitude : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0}) {
            assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 4.0, PROVENANCE,
                    new int[] {0}, new int[] {1}, new double[] {magnitude}, new double[] {0.0}), "magnitude " + magnitude);
        }
        for (double phase : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -PI, 4.0, -3.5}) {
            assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 4.0, PROVENANCE,
                    new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {phase}), "phase " + phase);
        }
    }

    @Test
    void rejectsInvalidHeaderValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, -1.0, PROVENANCE,
                        new int[0], new int[0], new double[0], new double[0])),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, Double.NaN,
                        PROVENANCE, new int[0], new int[0], new double[0], new double[0])),
                () -> assertThrows(NullPointerException.class, () -> SpectralPattern.of(null, 1.0, PROVENANCE,
                        new int[0], new int[0], new double[0], new double[0])),
                () -> assertThrows(NullPointerException.class, () -> SpectralPattern.of(DFT_8, 1.0, null,
                        new int[0], new int[0], new double[0], new double[0])),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 1.0, PROVENANCE,
                        new int[1], new int[2], new double[1], new double[1])));
    }

    @Test
    void sourceEnergyLeavesHeadroomForTheRetainedEnergy() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 1, 1.0, SampleUnit.HERTZ);
        double max = SpectralPattern.MAX_SOURCE_ENERGY;

        assertEquals(max, SpectralPattern.of(frame, max, PROVENANCE, new int[0], new int[0], new double[0],
                new double[0]).sourceEnergy());
        for (double bad : new double[] {Math.nextUp(max), Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN,
                -1.0}) {
            assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(frame, bad, PROVENANCE,
                    new int[0], new int[0], new double[0], new double[0]));
        }
    }

    @Test
    void rejectsWindowOffsetOverflow() {
        var late = new WaveProvenance("encoder.fixture", 1, "window", Long.MAX_VALUE - 7);
        var ok = new WaveProvenance("encoder.fixture", 1, "window", Long.MAX_VALUE - 8);

        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 0.0, late,
                new int[0], new int[0], new double[0], new double[0]));
        assertEquals(0, SpectralPattern.of(DFT_8, 0.0, ok, new int[0], new int[0], new double[0], new double[0])
                .componentCount());
    }

    @Test
    void rejectsRetainedEnergyOverflow() {
        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 0.0, PROVENANCE,
                new int[] {0}, new int[] {0}, new double[] {1e200}, new double[] {0.0}));
    }

    @Test
    void enforcesTheBasisComponentCapBeforeCopying() {
        var dft = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 4096, 4096.0, SampleUnit.HERTZ);
        var haar = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(12), 4096, 4096.0, SampleUnit.HERTZ);

        assertEquals(SpectralPattern.MAX_COMPONENTS_DFT, full(dft).componentCount());
        assertEquals(SpectralPattern.MAX_COMPONENTS_HAAR, full(haar).componentCount());
        int over = SpectralPattern.MAX_COMPONENTS_DFT + 1;
        var error = assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(dft, 0.0, PROVENANCE,
                new int[over], new int[over], new double[over], new double[over]));
        assertTrue(error.getMessage().contains("at most " + SpectralPattern.MAX_COMPONENTS_DFT), error.getMessage());
        int haarOver = SpectralPattern.MAX_COMPONENTS_HAAR + 1;
        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(haar, 0.0, PROVENANCE,
                new int[haarOver], new int[haarOver], new double[haarOver], new double[haarOver]));
    }

    @Test
    void canonicalizesSignedZerosSoEqualPatternsAreEqual() {
        var negative = SpectralPattern.of(DFT_8, 1.0, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {-0.0}, new double[] {-0.0});
        var positive = SpectralPattern.of(DFT_8, 1.0, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {0.0}, new double[] {0.0});

        assertEquals(positive, negative);
        assertEquals(positive.hashCode(), negative.hashCode());
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(negative.phase(0)));
    }

    @Test
    void canonicalizesNegativeZeroSourceEnergy() {
        var negative = SpectralPattern.of(DFT_8, -0.0, PROVENANCE, new int[0], new int[0], new double[0], new double[0]);
        var positive = SpectralPattern.of(DFT_8, 0.0, PROVENANCE, new int[0], new int[0], new double[0], new double[0]);
        var fromList = SpectralPattern.of(DFT_8, -0.0, PROVENANCE, List.of());

        assertEquals(positive, negative);
        assertEquals(positive.hashCode(), negative.hashCode());
        assertEquals(positive, fromList);
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(negative.sourceEnergy()));
    }

    @Test
    void rejectsRetainedEnergyAboveSourceEnergy() {
        // bin 1 magnitude 1 retains 8 * 1/2 = 4
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 0.0, PROVENANCE,
                        new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 3.9, PROVENANCE,
                        new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(HAAR_4_L2, 69.0,
                        PROVENANCE, p9().components())));
    }

    @Test
    void acceptsRetainedEnergyWithinTheParsevalTolerance() {
        double source = 4.0 * (1.0 - 0.5e-9);
        var pattern = SpectralPattern.of(DFT_8, source, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {0.0});

        assertEquals(4.0, pattern.retainedEnergy());
        assertEquals(source, pattern.sourceEnergy());
    }

    @Test
    void completePatternMustMatchItsSourceEnergy() {
        var single = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 1, 1.0, SampleUnit.ABSTRACT_PER_INDEX);
        var dcOnly = List.of(new WaveComponent(0, 0, 1.0, 0.0, true, 0.0));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(single, 2.0, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {1.0}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SpectralPattern.of(single, 2.0, PROVENANCE, dcOnly)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SpectralPattern.of(HAAR_4_L2, 71.0, PROVENANCE, p9().components())),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(HAAR_4_L2, 71.0,
                        PROVENANCE, p9().levels(), p9().indices(), p9().magnitudes(), p9().phases())),
                () -> assertEquals(1.0, SpectralPattern.of(single, 1.0 + 0.5e-9, PROVENANCE, dcOnly).retainedEnergy()),
                () -> assertEquals(1.0, SpectralPattern.of(single, 1.0 - 0.5e-9, PROVENANCE, dcOnly).retainedEnergy()));
    }

    @Test
    void smallNormalEnergiesKeepTheirRelativeTolerance() {
        var single = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 1, 1.0, SampleUnit.ABSTRACT_PER_INDEX);
        double magnitude = StrictMath.sqrt(Double.MIN_NORMAL);
        var component = List.of(new WaveComponent(0, 0, magnitude, 0.0, true, 0.0));
        double twice = 2 * Double.MIN_NORMAL;

        assertAll(
                () -> assertEquals(Double.MIN_NORMAL, SpectralPattern.of(single, Double.MIN_NORMAL, PROVENANCE,
                        component).retainedEnergy()),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(single, twice, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {magnitude}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SpectralPattern.of(single, twice, PROVENANCE, component)),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(single, 0.0, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {magnitude}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SpectralPattern.of(single, 0.0, PROVENANCE, component)));
    }

    @Test
    void zeroSourceEnergyAdmitsOnlyZeroMagnitudes() {
        var tiny = new double[] {Double.MIN_VALUE};

        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 0.0, PROVENANCE,
                new int[] {0}, new int[] {1}, tiny, new double[] {0.0}));
        assertEquals(0.0, SpectralPattern.of(DFT_8, 0.0, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {0.0}, new double[] {0.0}).retainedEnergy());
    }

    @Test
    void subnormalEnergiesAllowOnlyABoundedUlpAllowance() {
        var single = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 1, 1.0, SampleUnit.ABSTRACT_PER_INDEX);
        double magnitude = Math.scalb(1.0, -530);
        double energy = Math.scalb(1.0, -1060);
        double withinRounding = energy + 3 * Double.MIN_VALUE;

        assertAll(
                () -> assertEquals(energy, SpectralPattern.of(single, withinRounding, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {magnitude}, new double[] {0.0}).retainedEnergy()),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(single, 2 * energy,
                        PROVENANCE, new int[] {0}, new int[] {0}, new double[] {magnitude}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(single,
                        energy + 100 * Double.MIN_VALUE, PROVENANCE,
                        List.of(new WaveComponent(0, 0, magnitude, 0.0, true, 0.0)))));
    }

    @Test
    void truncatedPatternMayRetainLessThanItsSourceEnergy() {
        var truncated = SpectralPattern.of(DFT_8, 5.0, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {0.0});

        assertEquals(4.0, truncated.retainedEnergy());
        assertEquals(5.0, truncated.sourceEnergy());
    }

    @Test
    void realValuedComponentsNeedASignOnlyPhase() {
        var haar2 = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(1), 2, 2.0, SampleUnit.HERTZ);
        double half = PI / 2;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(haar2, 1.0, PROVENANCE,
                        new int[] {0, 1}, new int[] {0, 0}, new double[] {1.0, 0.0}, new double[] {half, 0.0})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(haar2, 1.0, PROVENANCE,
                        List.of(new WaveComponent(0, 0, 1.0, half, true, 0.0),
                                new WaveComponent(1, 0, 0.0, 0.0, false, 0.0)))),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(haar2, 1.0, PROVENANCE,
                        new int[] {0, 1}, new int[] {0, 0}, new double[] {1.0, 0.0}, new double[] {0.0, 0.1})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 8.0, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {1.0}, new double[] {half})),
                () -> assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 8.0, PROVENANCE,
                        List.of(new WaveComponent(0, 4, 1.0, 0.1, true, 4.0)))),
                () -> assertEquals(PI, SpectralPattern.of(DFT_8, 8.0, PROVENANCE,
                        new int[] {0}, new int[] {0}, new double[] {1.0}, new double[] {PI}).phase(0)),
                () -> assertEquals(half, SpectralPattern.of(DFT_8, 4.0, PROVENANCE,
                        new int[] {0}, new int[] {1}, new double[] {1.0}, new double[] {half}).phase(0)));
    }

    @Test
    void oddLengthLastBinIsNotRealValued() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 7, 7.0, SampleUnit.HERTZ);

        assertEquals(0.5, SpectralPattern.of(frame, 3.5, PROVENANCE,
                new int[] {0}, new int[] {3}, new double[] {1.0}, new double[] {0.5}).phase(0));
    }

    @Test
    void retainedEnergyKeepsARepresentableSubnormalResult() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 4096, 4096.0, SampleUnit.HERTZ);
        double expected = new BigDecimal("4096").multiply(new BigDecimal(1e-162).pow(2))
                .divide(new BigDecimal(2)).doubleValue();
        var pattern = SpectralPattern.of(frame, expected, PROVENANCE,
                new int[] {0}, new int[] {1}, new double[] {1e-162}, new double[] {0.0});

        assertTrue(expected > 0.0 && expected < Double.MIN_NORMAL, "expected a subnormal energy, got " + expected);
        assertEquals(expected, pattern.retainedEnergy());
    }

    @Test
    void scaledEnergyIsBitIdenticalInTheNormalRange() {
        // Truncated (bin 4 dropped) so the source energy only bounds the retained energy.
        var magnitudes = new double[] {3.0, 0.1, 0.7, 1e-3};
        var pattern = SpectralPattern.of(DFT_8, 100.0, PROVENANCE,
                new int[] {0, 0, 0, 0}, new int[] {0, 1, 2, 3}, magnitudes, new double[4]);
        double naive = 0.0;
        for (int i = 0; i < magnitudes.length; i++) {
            double squared = magnitudes[i] * magnitudes[i];
            naive += i == 0 ? squared : squared / 2.0;
        }

        assertEquals(8 * naive, pattern.retainedEnergy());
    }

    @Test
    void rewrappingAnotherPatternsComponentsKeepsItsValues() {
        var original = p4();
        var rewrapped = SpectralPattern.of(DFT_8, original.sourceEnergy(),
                new WaveProvenance("encoder.fixture", 2, "window-1", 8L), original.components());

        assertArrayEquals(original.magnitudes(), rewrapped.magnitudes());
        assertArrayEquals(original.phases(), rewrapped.phases());
        assertEquals(original.components(), rewrapped.components());
        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(
                new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 16.0, SampleUnit.HERTZ),
                original.sourceEnergy(), PROVENANCE, original.components()),
                "a view over another frame takes the validating path and its locators disagree");
    }

    @Test
    void bothFactoriesProduceEqualPatterns() {
        var fromArrays = p4();
        var fromList = SpectralPattern.of(DFT_8, fromArrays.sourceEnergy(), PROVENANCE, fromArrays.components());

        assertEquals(fromArrays, fromList);
        assertEquals(fromArrays.hashCode(), fromList.hashCode());
        assertEquals(fromArrays.components(), fromList.components());
    }

    @Test
    void listFactoryRejectsInconsistentDerivedValues() {
        var wrongFlag = List.of(new WaveComponent(0, 1, 0.0, 0.0, true, 1.0));
        var wrongLocator = List.of(new WaveComponent(0, 1, 1.0, 0.0, true, 1.5));

        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 1.0, PROVENANCE, wrongFlag));
        assertThrows(IllegalArgumentException.class, () -> SpectralPattern.of(DFT_8, 4.0, PROVENANCE, wrongLocator));
        var withNull = new ArrayList<WaveComponent>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> SpectralPattern.of(DFT_8, 1.0, PROVENANCE, withNull));
    }

    @Test
    void equalityCoversFrameEnergyProvenanceAndComponents() {
        var base = p4();
        var otherEnergy = SpectralPattern.of(DFT_8, Math.nextUp(5.0), PROVENANCE, base.components());
        var otherProvenance = SpectralPattern.of(DFT_8, base.sourceEnergy(),
                new WaveProvenance("encoder.fixture", 2, "window-0", 0L), base.components());
        var otherMagnitude = SpectralPattern.of(DFT_8, 4.25, PROVENANCE,
                base.levels(), base.indices(), new double[] {0.0, 1.0, 0.0, 0.25, 0.0}, base.phases());

        assertAll(
                () -> assertEquals(p4(), base),
                () -> assertNotEquals(base, otherEnergy),
                () -> assertNotEquals(base, otherProvenance),
                () -> assertNotEquals(base, otherMagnitude),
                () -> assertNotEquals(base, p9()));
    }

    @Test
    void inputArraysAreCopiedAndOutputArraysAreIsolated() {
        int[] levels = {0, 0};
        int[] indices = {1, 2};
        double[] magnitudes = {1.0, 2.0};
        double[] phases = {0.0, 1.0};
        var pattern = SpectralPattern.of(DFT_8, 20.0, PROVENANCE, levels, indices, magnitudes, phases);

        levels[0] = 9;
        indices[0] = 0;
        magnitudes[0] = 99.0;
        phases[0] = 3.0;
        pattern.levels()[1] = 9;
        pattern.indices()[1] = 9;
        pattern.magnitudes()[1] = 99.0;
        pattern.phases()[1] = 3.0;

        assertAll(
                () -> assertArrayEquals(new int[] {0, 0}, pattern.levels()),
                () -> assertArrayEquals(new int[] {1, 2}, pattern.indices()),
                () -> assertArrayEquals(new double[] {1.0, 2.0}, pattern.magnitudes()),
                () -> assertArrayEquals(new double[] {0.0, 1.0}, pattern.phases()),
                () -> assertEquals(SpectralPattern.of(DFT_8, 20.0, PROVENANCE, new int[] {0, 0}, new int[] {1, 2},
                        new double[] {1.0, 2.0}, new double[] {0.0, 1.0}), pattern));
    }

    @Test
    void componentViewIsUnmodifiableAndIndexChecked() {
        var components = p4().components();
        var extra = new WaveComponent(0, 0, 0.0, 0.0, false, 0.0);

        assertThrows(UnsupportedOperationException.class, () -> components.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> components.set(0, extra));
        assertThrows(UnsupportedOperationException.class, () -> components.remove(0));
        assertThrows(IndexOutOfBoundsException.class, () -> components.get(5));
        assertThrows(IndexOutOfBoundsException.class, () -> p4().magnitude(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> p4().locator(5));
    }

    @Test
    void constructionIsDeterministic() {
        assertEquals(p4(), p4());
        assertEquals(p4().toString(), p4().toString());
        assertEquals(p4().retainedEnergy(), p4().retainedEnergy());
    }

    private static void assertRejected(SpectralFrame frame, int[] levels, int[] indices) {
        var magnitudes = new double[levels.length];
        var phases = new double[levels.length];
        assertThrows(IllegalArgumentException.class,
                () -> SpectralPattern.of(frame, 0.0, PROVENANCE, levels, indices, magnitudes, phases));
    }

    private static SpectralPattern full(SpectralFrame frame) {
        int count = frame.componentCapacity();
        var levels = new int[count];
        var indices = new int[count];
        int i = 0;
        for (int level = 0; level <= frame.scale().levels(); level++) {
            for (int index = 0; frame.containsKey(level, index); index++) {
                levels[i] = level;
                indices[i] = index;
                i++;
            }
        }
        assertEquals(count, i);
        return SpectralPattern.of(frame, 0.0, PROVENANCE, levels, indices, new double[count], new double[count]);
    }
}
