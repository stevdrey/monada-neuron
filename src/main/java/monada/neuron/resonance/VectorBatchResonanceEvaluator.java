package monada.neuron.resonance;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * SIMD batch resonance evaluator using the Vector API ({@code jdk.incubator.vector}).
 *
 * <p>Vectorizes amplitude, frequency, and phase similarity calculations using {@link DoubleVector}
 * and the preferred CPU vector species. Handles tail elements deterministically via scalar fallback
 * and validates domain constraints for all inputs.
 */
public final class VectorBatchResonanceEvaluator implements BatchResonanceEvaluator {

    /** Singleton instance. */
    public static final VectorBatchResonanceEvaluator INSTANCE = new VectorBatchResonanceEvaluator();

    private static final VectorSpecies<Double> SPECIES;
    private static final boolean AVAILABLE;

    private static final double TWO_PI = 2.0 * StrictMath.PI;
    private static final double INV_TWO_PI = 1.0 / (2.0 * StrictMath.PI);
    private static final double MAGIC = 1.5 * (1L << 52); // 6755399441055744.0 (2^52 + 2^51) for round-to-nearest
    private static final double EXTREME_PHASE_THRESHOLD = 1000.0;

    static {
        VectorSpecies<Double> species = DoubleVector.SPECIES_PREFERRED;
        SPECIES = species;
        AVAILABLE = species.length() > 1;
    }

    /** Creates a Vector API batch resonance evaluator. */
    public VectorBatchResonanceEvaluator() {
    }

    @Override
    public void scoreBatch(
            double[] firstAmplitudes,
            double[] firstFrequencies,
            double[] firstPhases,
            double[] secondAmplitudes,
            double[] secondFrequencies,
            double[] secondPhases,
            double[] results,
            int offset,
            int length) {
        validatePrimitiveInputs(
                firstAmplitudes,
                firstFrequencies,
                firstPhases,
                secondAmplitudes,
                secondFrequencies,
                secondPhases,
                results,
                offset,
                length);

        if (length == 0) {
            return;
        }

        if (!AVAILABLE || SPECIES == null) {
            ScalarBatchResonanceEvaluator.INSTANCE.scoreBatch(
                firstAmplitudes, firstFrequencies, firstPhases,
                secondAmplitudes, secondFrequencies, secondPhases,
                results, offset, length);
            return;
        }

        int speciesLength = SPECIES.length();
        int loopBound = SPECIES.loopBound(length);
        int vectorEnd = offset + loopBound;

        // Temporary stack arrays for rare extreme phase fallback
        double[] tempP1 = null;
        double[] tempP2 = null;
        double[] tempPRes = null;

        for (int i = offset; i < vectorEnd; i += speciesLength) {
            var va1 = DoubleVector.fromArray(SPECIES, firstAmplitudes, i);
            var vf1 = DoubleVector.fromArray(SPECIES, firstFrequencies, i);
            var vp1 = DoubleVector.fromArray(SPECIES, firstPhases, i);

            var va2 = DoubleVector.fromArray(SPECIES, secondAmplitudes, i);
            var vf2 = DoubleVector.fromArray(SPECIES, secondFrequencies, i);
            var vp2 = DoubleVector.fromArray(SPECIES, secondPhases, i);

            validateVectorValues(va1, vf1, vp1, va2, vf2, vp2, i);

            // 1. Vectorized Amplitude Similarity
            VectorMask<Double> maskAZero = va1.eq(0.0).or(va2.eq(0.0));
            DoubleVector minA = va1.min(va2);
            DoubleVector maxA = va1.max(va2);
            DoubleVector simA = minA.div(maxA).blend(0.0, maskAZero);

            // 2. Vectorized Frequency Similarity
            VectorMask<Double> maskFEq = vf1.eq(vf2);
            VectorMask<Double> maskF1Zero = vf1.eq(0.0);
            VectorMask<Double> maskF2Zero = vf2.eq(0.0);
            VectorMask<Double> maskFOneZero = maskF1Zero.xor(maskF2Zero);
            DoubleVector minF = vf1.min(vf2);
            DoubleVector maxF = vf1.max(vf2);
            DoubleVector simF = minF.div(maxF).blend(0.0, maskFOneZero).blend(1.0, maskFEq);

            // 3. Vectorized Phase Similarity
            VectorMask<Double> extremeMask = vp1.abs().compare(VectorOperators.GT, EXTREME_PHASE_THRESHOLD)
                    .or(vp2.abs().compare(VectorOperators.GT, EXTREME_PHASE_THRESHOLD));
            DoubleVector simP;

            if (extremeMask.anyTrue()) {
                if (tempP1 == null) {
                    tempP1 = new double[speciesLength];
                    tempP2 = new double[speciesLength];
                    tempPRes = new double[speciesLength];
                }
                vp1.intoArray(tempP1, 0);
                vp2.intoArray(tempP2, 0);
                for (int lane = 0; lane < speciesLength; lane++) {
                    double w1 = StrictMath.IEEEremainder(tempP1[lane], TWO_PI);
                    double w2 = StrictMath.IEEEremainder(tempP2[lane], TWO_PI);
                    double delta = StrictMath.IEEEremainder(w1 - w2, TWO_PI);
                    tempPRes[lane] = (1.0 + StrictMath.cos(delta)) / 2.0;
                }
                simP = DoubleVector.fromArray(SPECIES, tempPRes, 0);
            } else {
                DoubleVector q1 = vp1.mul(INV_TWO_PI).add(MAGIC).sub(MAGIC);
                DoubleVector w1 = vp1.sub(q1.mul(TWO_PI));

                DoubleVector q2 = vp2.mul(INV_TWO_PI).add(MAGIC).sub(MAGIC);
                DoubleVector w2 = vp2.sub(q2.mul(TWO_PI));

                DoubleVector diff = w1.sub(w2);
                DoubleVector qDiff = diff.mul(INV_TWO_PI).add(MAGIC).sub(MAGIC);
                DoubleVector delta = diff.sub(qDiff.mul(TWO_PI));

                DoubleVector cosDelta = delta.lanewise(VectorOperators.COS);
                simP = cosDelta.add(1.0).mul(0.5);
            }

            // 4. Combined Score
            DoubleVector score = simA.mul(simF).mul(simP);
            score.intoArray(results, i);
        }

        // Tail elements processed deterministically using scalar oracle formula
        int totalEnd = offset + length;
        for (int i = vectorEnd; i < totalEnd; i++) {
            double a1 = firstAmplitudes[i];
            double a2 = secondAmplitudes[i];
            double f1 = firstFrequencies[i];
            double f2 = secondFrequencies[i];
            double p1 = firstPhases[i];
            double p2 = secondPhases[i];

            validateScalarValues(a1, f1, p1, a2, f2, p2, i);

            results[i] = computeScalarScore(a1, f1, p1, a2, f2, p2);
        }
    }

    @Override
    public void scoreBatch(
            FrequencyState[] first,
            FrequencyState[] second,
            double[] results,
            int offset,
            int length) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        Objects.requireNonNull(results, "results must not be null");

        if (offset < 0 || length < 0
                || offset > first.length - length
                || offset > second.length - length
                || offset > results.length - length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Invalid offset/length: offset=%d, length=%d, first=%d, second=%d, results=%d",
                    offset, length, first.length, second.length, results.length));
        }

        if (length == 0) {
            return;
        }

        if (!AVAILABLE || SPECIES == null || length < 32) {
            ScalarBatchResonanceEvaluator.INSTANCE.scoreBatch(first, second, results, offset, length);
            return;
        }

        // Bounded stack chunking to vectorize without unbounded heap allocation
        final int chunkSize = 128;
        double[] chunkA1 = new double[chunkSize];
        double[] chunkF1 = new double[chunkSize];
        double[] chunkP1 = new double[chunkSize];
        double[] chunkA2 = new double[chunkSize];
        double[] chunkF2 = new double[chunkSize];
        double[] chunkP2 = new double[chunkSize];
        double[] chunkRes = new double[chunkSize];

        int processed = 0;
        while (processed < length) {
            int currentBatch = Math.min(chunkSize, length - processed);
            int startIdx = offset + processed;

            for (int k = 0; k < currentBatch; k++) {
                FrequencyState s1 = Objects.requireNonNull(first[startIdx + k], "first state must not be null");
                FrequencyState s2 = Objects.requireNonNull(second[startIdx + k], "second state must not be null");
                chunkA1[k] = s1.amplitude();
                chunkF1[k] = s1.frequency();
                chunkP1[k] = s1.phase();
                chunkA2[k] = s2.amplitude();
                chunkF2[k] = s2.frequency();
                chunkP2[k] = s2.phase();
            }

            scoreBatch(
                    chunkA1, chunkF1, chunkP1,
                    chunkA2, chunkF2, chunkP2,
                    chunkRes, 0, currentBatch);

            System.arraycopy(chunkRes, 0, results, startIdx, currentBatch);
            processed += currentBatch;
        }
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE;
    }

    /** Returns the active vector species used by this evaluator, or {@code null} if unavailable. */
    public VectorSpecies<Double> species() {
        return SPECIES;
    }

    /** Returns the vector lane count (width) of this evaluator, or 1 if unavailable. */
    public int vectorWidth() {
        return SPECIES != null ? SPECIES.length() : 1;
    }

    private double computeScalarScore(double a1, double f1, double p1, double a2, double f2, double p2) {
        if (a1 == 0.0 || a2 == 0.0) {
            return 0.0;
        }

        double simA = a1 < a2 ? a1 / a2 : a2 / a1;

        double simF;
        if (f1 == f2) {
            simF = 1.0;
        } else if (f1 == 0.0 || f2 == 0.0) {
            simF = 0.0;
        } else {
            simF = f1 < f2 ? f1 / f2 : f2 / f1;
        }

        double w1 = StrictMath.IEEEremainder(p1, TWO_PI);
        double w2 = StrictMath.IEEEremainder(p2, TWO_PI);
        double delta = StrictMath.IEEEremainder(w1 - w2, TWO_PI);
        double simP = (1.0 + StrictMath.cos(delta)) / 2.0;

        return simA * simF * simP;
    }

    private void validateVectorValues(
            DoubleVector va1, DoubleVector vf1, DoubleVector vp1,
            DoubleVector va2, DoubleVector vf2, DoubleVector vp2,
            int index) {
        // Fast SIMD constraint validation: check for negative numbers or non-finite values (NaN / Inf)
        VectorMask<Double> invalidA = va1.lt(0.0).or(va2.lt(0.0))
                .or(va1.test(VectorOperators.IS_FINITE).not())
                .or(va2.test(VectorOperators.IS_FINITE).not());
        VectorMask<Double> invalidF = vf1.lt(0.0).or(vf2.lt(0.0))
                .or(vf1.test(VectorOperators.IS_FINITE).not())
                .or(vf2.test(VectorOperators.IS_FINITE).not());
        VectorMask<Double> invalidP = vp1.test(VectorOperators.IS_FINITE).not()
                .or(vp2.test(VectorOperators.IS_FINITE).not());

        if (invalidA.anyTrue() || invalidF.anyTrue() || invalidP.anyTrue()) {
            throw new IllegalArgumentException("Invalid frequency state values detected near index " + index);
        }
    }

    private void validateScalarValues(double a1, double f1, double p1, double a2, double f2, double p2, int index) {
        if (!Double.isFinite(a1) || a1 < 0.0 || !Double.isFinite(a2) || a2 < 0.0) {
            throw new IllegalArgumentException("amplitude at index " + index + " must be non-negative and finite");
        }
        if (!Double.isFinite(f1) || f1 < 0.0 || !Double.isFinite(f2) || f2 < 0.0) {
            throw new IllegalArgumentException("frequency at index " + index + " must be non-negative and finite");
        }
        if (!Double.isFinite(p1) || !Double.isFinite(p2)) {
            throw new IllegalArgumentException("phase at index " + index + " must be finite");
        }
    }

    private void validatePrimitiveInputs(
            double[] a1, double[] f1, double[] p1,
            double[] a2, double[] f2, double[] p2,
            double[] results, int offset, int length) {
        Objects.requireNonNull(a1, "firstAmplitudes must not be null");
        Objects.requireNonNull(f1, "firstFrequencies must not be null");
        Objects.requireNonNull(p1, "firstPhases must not be null");
        Objects.requireNonNull(a2, "secondAmplitudes must not be null");
        Objects.requireNonNull(f2, "secondFrequencies must not be null");
        Objects.requireNonNull(p2, "secondPhases must not be null");
        Objects.requireNonNull(results, "results must not be null");

        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be non-negative, got offset=" + offset + ", length=" + length);
        }

        if (offset > a1.length - length || offset > f1.length - length || offset > p1.length - length
                || offset > a2.length - length || offset > f2.length - length || offset > p2.length - length
                || offset > results.length - length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Range offset=%d, length=%d exceeds array bounds", offset, length));
        }
    }
}
