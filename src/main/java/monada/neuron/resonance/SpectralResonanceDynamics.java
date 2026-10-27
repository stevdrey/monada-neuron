package monada.neuron.resonance;

import monada.neuron.resonance.ComparisonResult.Incompatible;
import monada.neuron.resonance.ComparisonResult.SpectralComparison;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Experimental, opt-in reference for the relation between two {@link SpectralPattern}s: per-band alignment, phase
 * difference and signed interference, a {@code [0, 1]} aggregate and a phase coherence (contract
 * {@code ncm-wave-cognition/1} section 7, ADR 0041).
 *
 * <p>This is a mechanism separate from {@link ResonanceMetric} and {@link ScalarResonanceMetric}, which it neither
 * wraps nor replaces. It measures a relation between two patterns of host samples; a high aggregate is a similarity,
 * not an identity, and correlation alone never identifies a semantic concept. It ranks nothing and keeps nothing:
 * recall and ranking belong to the Resonance Store, and negative interference is never written there as a score.
 *
 * <p><b>Compatibility.</b> Two patterns are compared only when their {@code version}, basis, scale, sample count,
 * sample rate and unit are equal; otherwise the result is {@link Incompatible} with the first differing field in
 * {@link IncompatibilityReason} order. Nothing is interpolated, padded or resampled.
 *
 * <p><b>Bands.</b> Components are matched by their {@code (level, index)} key with one two-pointer pass over the
 * canonical, duplicate-free order of both patterns: O(n + m) time, no sort, no hashing, and a band list of at most
 * {@code min(n, m)} entries. A band only one pattern retains is not a {@link BandContribution}, but it still counts in
 * that pattern's norm, so a dropped band lowers the aggregate instead of being ignored. With {@code Δφ} the stored
 * phase difference wrapped by {@link SpectralPhase#wrap(double)}:
 *
 * <pre>{@code
 * interference_k = m1_k · m2_k · cos(Δφ_k)
 * alignment_k    = min(m1_k, m2_k) / max(m1_k, m2_k), 0 when both are 0
 * aggregate      = (1 + Σ_k interference_k / (‖m1‖ · ‖m2‖)) / 2, 0 when either norm is 0
 * coherence      = |Σ_k w_k · e^{iΔφ_k}| / Σ_k w_k, w_k = m1_k · m2_k, over bands whose phases are both defined;
 *                  0 when Σ_k w_k = 0
 * }</pre>
 *
 * <p>Interference uses every stored phase; only the coherence reads {@link SpectralPattern#phaseDefined(int)}. Every
 * band has unit weight beyond its magnitudes: no learned or persistent weighting is applied.
 *
 * <p><b>Numerics.</b> The aggregate ratio and the coherence are summed with each pattern's magnitudes scaled by
 * {@code 2^-e}, {@code e} the exponent of that pattern's largest magnitude. The scaling is exact, so the result is
 * bit-identical to the unscaled formula whenever that formula neither underflows nor overflows, and it stays
 * meaningful for subnormal energies. The reported norms and per-band interference terms are unscaled. They are finite
 * because a pattern's energy is bounded by {@link SpectralPattern#MAX_SOURCE_ENERGY}, which bounds every squared
 * magnitude and every squared norm by it as well, but for very small energies they can underflow: the aggregate can
 * be recomputed from them only while {@code firstNorm · secondNorm} stays a normal double (about {@code 1e-308} and
 * up), whereas the reported aggregate and coherence never lose their meaning. Rounding can push the normalized sum a few ulps past {@code ±1} and the coherence past {@code 1}; both
 * are clamped. Sums run in ascending key order with {@code StrictMath}, so equal inputs give bit-identical results,
 * and swapping the patterns gives the same aggregate and coherence and negated phase differences (except at
 * {@code π}, which is its own negation in {@code (-π, π]}).
 */
public final class SpectralResonanceDynamics {

    /** Creates a stateless reference comparator. */
    public SpectralResonanceDynamics() {
    }

    /**
     * Compares two patterns.
     *
     * @param first first pattern
     * @param second second pattern
     * @return the comparison, or the typed incompatibility of the first differing frame field
     * @throws NullPointerException if either pattern is {@code null}
     */
    public ComparisonResult compare(SpectralPattern first, SpectralPattern second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        Incompatible incompatible = incompatibility(first, second);
        if (incompatible != null) {
            return incompatible;
        }

        int firstExponent = scaleExponent(first);
        int secondExponent = scaleExponent(second);
        double firstScaledNorm = scaledNorm(first, firstExponent);
        double secondScaledNorm = scaledNorm(second, secondExponent);

        int firstCount = first.componentCount();
        int secondCount = second.componentCount();
        List<BandContribution> bands = new ArrayList<>(Math.min(firstCount, secondCount));
        double scaledInterference = 0.0;
        double scaledWeight = 0.0;
        double weightedCos = 0.0;
        double weightedSin = 0.0;
        int i = 0;
        int j = 0;
        while (i < firstCount && j < secondCount) {
            int order = BandKeys.compare(first.level(i), first.index(i), second.level(j), second.index(j));
            if (order < 0) {
                i++;
            } else if (order > 0) {
                j++;
            } else {
                double firstMagnitude = first.magnitude(i);
                double secondMagnitude = second.magnitude(j);
                double phaseDifference = SpectralPhase.wrap(first.phase(i) - second.phase(j));
                double cos = StrictMath.cos(phaseDifference);
                boolean phasesDefined = first.phaseDefined(i) && second.phaseDefined(j);

                double scaledProduct = Math.scalb(firstMagnitude, -firstExponent)
                        * Math.scalb(secondMagnitude, -secondExponent);
                scaledInterference += scaledProduct * cos;
                if (phasesDefined) {
                    scaledWeight += scaledProduct;
                    weightedCos += scaledProduct * cos;
                    weightedSin += scaledProduct * StrictMath.sin(phaseDifference);
                }
                bands.add(new BandContribution(first.level(i), first.index(i), firstMagnitude, secondMagnitude,
                        phaseDifference, alignment(firstMagnitude, secondMagnitude),
                        firstMagnitude * secondMagnitude * cos, phasesDefined));
                i++;
                j++;
            }
        }
        int shared = bands.size();

        return new SpectralComparison(bands,
                Math.scalb(firstScaledNorm, firstExponent), Math.scalb(secondScaledNorm, secondExponent),
                firstCount - shared, secondCount - shared,
                aggregate(scaledInterference, firstScaledNorm, secondScaledNorm),
                coherence(weightedCos, weightedSin, scaledWeight));
    }

    private Incompatible incompatibility(SpectralPattern first, SpectralPattern second) {
        if (first.version() != second.version()) {
            return new Incompatible(IncompatibilityReason.VERSION,
                    "version differs: " + first.version() + " vs " + second.version());
        }
        if (first.basis() != second.basis()) {
            return new Incompatible(IncompatibilityReason.BASIS,
                    "basis differs: " + first.basis() + " vs " + second.basis());
        }
        if (!first.scale().equals(second.scale())) {
            return new Incompatible(IncompatibilityReason.SCALE,
                    "scale differs: " + first.scale().levels() + " vs " + second.scale().levels() + " levels");
        }
        if (first.sampleCount() != second.sampleCount()) {
            return new Incompatible(IncompatibilityReason.SAMPLE_COUNT,
                    "sampleCount differs: " + first.sampleCount() + " vs " + second.sampleCount());
        }
        if (Double.compare(first.sampleRate(), second.sampleRate()) != 0) {
            return new Incompatible(IncompatibilityReason.SAMPLE_RATE,
                    "sampleRate differs: " + first.sampleRate() + " vs " + second.sampleRate());
        }
        if (first.unit() != second.unit()) {
            return new Incompatible(IncompatibilityReason.UNIT,
                    "unit differs: " + first.unit() + " vs " + second.unit());
        }
        return null;
    }

    /** Exponent of the largest magnitude, or {@code 0} for an all-zero pattern (whose scaled values stay 0). */
    private int scaleExponent(SpectralPattern pattern) {
        double peak = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            peak = Math.max(peak, pattern.magnitude(i));
        }
        return peak == 0.0 ? 0 : Math.getExponent(peak);
    }

    private double scaledNorm(SpectralPattern pattern, int exponent) {
        double sum = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            double scaled = Math.scalb(pattern.magnitude(i), -exponent);
            sum += scaled * scaled;
        }
        return StrictMath.sqrt(sum);
    }

    private double alignment(double firstMagnitude, double secondMagnitude) {
        double larger = Math.max(firstMagnitude, secondMagnitude);
        return larger == 0.0 ? 0.0 : Math.min(firstMagnitude, secondMagnitude) / larger;
    }

    private double aggregate(double scaledInterference, double firstScaledNorm, double secondScaledNorm) {
        if (firstScaledNorm == 0.0 || secondScaledNorm == 0.0) {
            return 0.0;
        }
        double ratio = scaledInterference / (firstScaledNorm * secondScaledNorm);
        return (1.0 + Math.clamp(ratio, -1.0, 1.0)) / 2.0;
    }

    private double coherence(double weightedCos, double weightedSin, double weight) {
        if (weight == 0.0) {
            return 0.0;
        }
        return Math.min(1.0, StrictMath.hypot(weightedCos, weightedSin) / weight);
    }
}
