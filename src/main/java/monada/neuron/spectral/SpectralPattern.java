package monada.neuron.spectral;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Bounded, immutable, cycle-local spectral components of one window of host samples (contract
 * {@code ncm-wave-cognition/1} section 6, ADR 0035).
 *
 * <p><b>Layout.</b> Components are stored as parallel primitive arrays (levels, indices, magnitudes, phases) in the
 * canonical order: strictly ascending {@code (level, index)}, so there are no duplicate keys. Storage and the single
 * construction copy are O(k) for k components, about 24 bytes per component plus four array headers, with no
 * per-component object. Indexed accessors ({@link #magnitude(int)}, {@link #phase(int)}, ...) never allocate; the
 * record view {@link #components()} allocates O(k) per call and is meant for diagnostics and tests. No internal
 * array escapes: bulk accessors return copies.
 *
 * <p><b>Derived values.</b> {@link #phaseDefined(int)} and {@link #locator(int)} are computed from the stored data
 * and the {@link SpectralFrame}, never stored, so they cannot disagree with the contract rules:
 * <ul>
 *   <li>a phase is defined when its magnitude is positive and at least {@link #PHASE_EPSILON} times the largest
 *       magnitude of the pattern (absolute {@link #PHASE_EPSILON} when that largest magnitude is {@code 0});
 *       the phase itself is always stored, because synthesis needs it;</li>
 *   <li>the locator is a physical frequency in hertz, an abstract frequency in cycles per index, or a Haar sample
 *       position, as {@link #locatorUnit()} states.</li>
 * </ul>
 *
 * <p><b>Silence and truncation.</b> A pattern may hold zero components (everything dropped) or only zero
 * magnitudes (silent input); then no phase is defined and {@link #retainedEnergy()} is {@code 0}.
 * {@link #sourceEnergy()} is the energy of the decomposed samples and is carried unchanged by truncation, so the
 * retained-energy fraction is computable from the pattern alone.
 *
 * <p><b>Validation.</b> Invalid construction arguments are caller bugs and throw {@link IllegalArgumentException}
 * or {@link NullPointerException}; nothing is repaired except signed zeros, which are canonicalized to {@code +0.0}.
 * The component count is checked against the basis cap before any array is copied. The retained energy may not
 * exceed the source energy by more than {@link #ENERGY_TOLERANCE} (relative), so the retained-energy fraction is
 * never above {@code 1} beyond numerical noise, and a complete pattern must equal its source energy within that
 * tolerance. Real-valued components (every Haar coefficient, the DFT DC bin and, for even {@code N}, the Nyquist bin)
 * encode their sign only: their phase must be exactly {@code 0} or {@code π}. Decomposers compute those bins as real
 * sums.
 */
public final class SpectralPattern {

    /** Pattern schema version of contract {@code ncm-wave-cognition/1}. */
    public static final int CURRENT_VERSION = 1;

    /** Largest number of samples a pattern may describe. */
    public static final int MAX_SAMPLE_COUNT = 4096;

    /** Largest DFT component count: {@code floor(MAX_SAMPLE_COUNT / 2) + 1} single-sided bins. */
    public static final int MAX_COMPONENTS_DFT = MAX_SAMPLE_COUNT / 2 + 1;

    /** Largest Haar component count: a complete Haar pattern of {@code N} samples has {@code N} coefficients. */
    public static final int MAX_COMPONENTS_HAAR = MAX_SAMPLE_COUNT;

    /** Relative magnitude threshold below which a phase is not used for comparison. */
    public static final double PHASE_EPSILON = 1e-12;

    /**
     * Relative tolerance between {@link #retainedEnergy()} and {@link #sourceEnergy()}: the Parseval tolerance of
     * contract section 12.4. Any pattern may retain at most the source energy plus this tolerance; a complete pattern
     * (every key of its frame present) must equal it within the tolerance in both directions. For (near-)subnormal
     * energies, where a relative tolerance is smaller than rounding, the allowance is a bounded number of ULPs at the
     * energy scale ({@code sampleCount + componentCount + 4}). A source energy of {@code 0} (silent samples) admits
     * only zero magnitudes.
     */
    public static final double ENERGY_TOLERANCE = 1e-9;

    /**
     * Largest source energy a pattern may carry, {@code Double.MAX_VALUE / 2}. The retained energy is recomputed from
     * rounded magnitudes and may exceed the source energy by up to {@link #ENERGY_TOLERANCE}; this headroom keeps it
     * finite. Decomposers report a larger energy as a typed rejection before building a pattern.
     */
    public static final double MAX_SOURCE_ENERGY = Double.MAX_VALUE / 2;

    private final SpectralFrame frame;
    private final double sourceEnergy;
    private final WaveProvenance provenance;
    private final int[] levels;
    private final int[] indices;
    private final double[] magnitudes;
    private final double[] phases;
    private final double phaseThreshold;
    private final double retainedEnergy;

    private SpectralPattern(SpectralFrame frame, double sourceEnergy, WaveProvenance provenance,
                            int[] levels, int[] indices, double[] magnitudes, double[] phases) {
        this.frame = frame;
        this.sourceEnergy = sourceEnergy;
        this.provenance = provenance;
        this.levels = levels;
        this.indices = indices;
        this.magnitudes = magnitudes;
        this.phases = phases;
        double maxMagnitude = validateComponents();
        this.phaseThreshold = maxMagnitude == 0.0 ? PHASE_EPSILON : PHASE_EPSILON * maxMagnitude;
        this.retainedEnergy = computeRetainedEnergy(maxMagnitude);
    }

    /**
     * Creates a pattern from parallel primitive arrays, copying each array once.
     *
     * @param frame basis and sampling metadata
     * @param sourceEnergy {@code Σ x_n²} of the decomposed samples, finite and non-negative
     * @param provenance host provenance of the samples
     * @param levels component levels, parallel to the other arrays
     * @param indices component indices
     * @param magnitudes finite, non-negative normalized magnitudes
     * @param phases canonical phases in {@code (-π, π]}
     * @return the immutable pattern
     * @throws IllegalArgumentException if any value, bound, key or ordering rule is violated
     */
    public static SpectralPattern of(SpectralFrame frame, double sourceEnergy, WaveProvenance provenance,
                                     int[] levels, int[] indices, double[] magnitudes, double[] phases) {
        Objects.requireNonNull(levels, "levels must not be null");
        Objects.requireNonNull(indices, "indices must not be null");
        Objects.requireNonNull(magnitudes, "magnitudes must not be null");
        Objects.requireNonNull(phases, "phases must not be null");
        int count = levels.length;
        if (indices.length != count || magnitudes.length != count || phases.length != count) {
            throw new IllegalArgumentException(String.format(
                    "arrays must have equal lengths: levels=%d, indices=%d, magnitudes=%d, phases=%d",
                    count, indices.length, magnitudes.length, phases.length));
        }
        requireHeader(frame, sourceEnergy, provenance, count);
        return new SpectralPattern(frame, canonicalZero(sourceEnergy), provenance, levels.clone(), indices.clone(),
                canonicalZeros(magnitudes), canonicalZeros(phases));
    }

    /**
     * Creates a pattern that takes ownership of freshly allocated arrays instead of copying them, for decomposers in
     * this package. The caller must not keep or mutate the arrays afterwards; signed zeros are canonicalized in place
     * and every rule of the public factory is validated.
     */
    static SpectralPattern ofOwned(SpectralFrame frame, double sourceEnergy, WaveProvenance provenance,
                                   int[] levels, int[] indices, double[] magnitudes, double[] phases) {
        int count = levels.length;
        if (indices.length != count || magnitudes.length != count || phases.length != count) {
            throw new IllegalArgumentException("owned arrays must have equal lengths");
        }
        requireHeader(frame, sourceEnergy, provenance, count);
        for (int i = 0; i < count; i++) {
            magnitudes[i] = canonicalZero(magnitudes[i]);
            phases[i] = canonicalZero(phases[i]);
        }
        return new SpectralPattern(frame, canonicalZero(sourceEnergy), provenance, levels, indices, magnitudes,
                phases);
    }

    /**
     * Creates a pattern from component records. Each record's {@code phaseDefined} and {@code locator} must equal
     * the values this pattern derives; a mismatch is rejected, never repaired.
     *
     * @param frame basis and sampling metadata
     * @param sourceEnergy {@code Σ x_n²} of the decomposed samples, finite and non-negative
     * @param provenance host provenance of the samples
     * @param components components in canonical {@code (level, index)} order
     * @return the immutable pattern
     * @throws IllegalArgumentException if any value, bound, key, ordering or derived-value rule is violated
     */
    public static SpectralPattern of(SpectralFrame frame, double sourceEnergy, WaveProvenance provenance,
                                     List<WaveComponent> components) {
        Objects.requireNonNull(components, "components must not be null");
        int count = components.size();
        requireHeader(frame, sourceEnergy, provenance, count);
        if (components instanceof SpectralPattern.ComponentView view && view.owner().frame.equals(frame)) {
            // Derived values of another pattern over the same frame are consistent by construction.
            var source = view.owner();
            return new SpectralPattern(frame, canonicalZero(sourceEnergy), provenance, source.levels,
                    source.indices, source.magnitudes, source.phases);
        }
        var levels = new int[count];
        var indices = new int[count];
        var magnitudes = new double[count];
        var phases = new double[count];
        var declaredPhaseDefined = new boolean[count];
        var declaredLocators = new double[count];
        int i = 0;
        for (WaveComponent component : components) {
            Objects.requireNonNull(component, "component at index " + i + " must not be null");
            levels[i] = component.level();
            indices[i] = component.index();
            magnitudes[i] = component.magnitude();
            phases[i] = component.phase();
            declaredPhaseDefined[i] = component.phaseDefined();
            declaredLocators[i] = component.locator();
            i++;
        }
        var pattern = new SpectralPattern(frame, canonicalZero(sourceEnergy), provenance, levels, indices,
                magnitudes, phases);
        for (i = 0; i < count; i++) {
            if (declaredPhaseDefined[i] != pattern.phaseDefined(i)) {
                throw new IllegalArgumentException("component at index " + i + " declares phaseDefined="
                        + declaredPhaseDefined[i] + " but the pattern derives " + pattern.phaseDefined(i));
            }
            if (Double.compare(declaredLocators[i], pattern.locator(i)) != 0) {
                throw new IllegalArgumentException("component at index " + i + " declares locator="
                        + declaredLocators[i] + " but the frame derives " + pattern.locator(i));
            }
        }
        return pattern;
    }

    /** Returns the pattern schema version, always {@link #CURRENT_VERSION}. */
    public int version() {
        return CURRENT_VERSION;
    }

    /** Returns the basis and sampling metadata. */
    public SpectralFrame frame() {
        return frame;
    }

    /** Returns the transform family. */
    public SpectralBasis basis() {
        return frame.basis();
    }

    /** Returns the decomposition depth. */
    public SpectralScale scale() {
        return frame.scale();
    }

    /** Returns the number of decomposed samples {@code N}. */
    public int sampleCount() {
        return frame.sampleCount();
    }

    /** Returns the declared sample rate. */
    public double sampleRate() {
        return frame.sampleRate();
    }

    /** Returns the declared sample unit. */
    public SampleUnit unit() {
        return frame.unit();
    }

    /** Returns the unit of every locator of this pattern. */
    public LocatorUnit locatorUnit() {
        return frame.locatorUnit();
    }

    /** Returns {@code Σ x_n²} of the decomposed samples, unchanged by truncation. */
    public double sourceEnergy() {
        return sourceEnergy;
    }

    /** Returns the host provenance of the samples. */
    public WaveProvenance provenance() {
        return provenance;
    }

    /** Returns the number of retained components {@code k}. */
    public int componentCount() {
        return levels.length;
    }

    /** Returns the level of component {@code i}. */
    public int level(int i) {
        return levels[Objects.checkIndex(i, levels.length)];
    }

    /** Returns the index of component {@code i}. */
    public int index(int i) {
        return indices[Objects.checkIndex(i, indices.length)];
    }

    /** Returns the normalized magnitude of component {@code i}. */
    public double magnitude(int i) {
        return magnitudes[Objects.checkIndex(i, magnitudes.length)];
    }

    /** Returns the stored canonical phase of component {@code i}, defined or not. */
    public double phase(int i) {
        return phases[Objects.checkIndex(i, phases.length)];
    }

    /** Returns whether the phase of component {@code i} is meaningful for comparison. */
    public boolean phaseDefined(int i) {
        double magnitude = magnitude(i);
        return magnitude > 0.0 && magnitude >= phaseThreshold;
    }

    /** Returns the locator of component {@code i} in {@link #locatorUnit()}. */
    public double locator(int i) {
        Objects.checkIndex(i, levels.length);
        return frame.locatorOf(levels[i], indices[i]);
    }

    /** Returns component {@code i} as a record (allocates one record). */
    public WaveComponent component(int i) {
        return new WaveComponent(level(i), index(i), magnitudes[i], phases[i], phaseDefined(i), locator(i));
    }

    /** Returns an unmodifiable list view that materializes each component on access. */
    public List<WaveComponent> components() {
        return new ComponentView();
    }

    /** Returns a copy of the levels. */
    public int[] levels() {
        return levels.clone();
    }

    /** Returns a copy of the indices. */
    public int[] indices() {
        return indices.clone();
    }

    /** Returns a copy of the magnitudes. */
    public double[] magnitudes() {
        return magnitudes.clone();
    }

    /** Returns a copy of the phases. */
    public double[] phases() {
        return phases.clone();
    }

    /**
     * Returns the energy the retained components carry, summed in canonical order. For DFT it is
     * {@code N · (m_0² + Σ_{0<k<N/2} m_k² / 2 + m_{N/2}²)}, with the Nyquist term only for even {@code N}; for Haar
     * it is {@code Σ c²}. For a complete pattern it equals {@link #sourceEnergy()} within the Parseval tolerance.
     */
    public double retainedEnergy() {
        return retainedEnergy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SpectralPattern that
                && frame.equals(that.frame)
                && Double.compare(sourceEnergy, that.sourceEnergy) == 0
                && provenance.equals(that.provenance)
                && Arrays.equals(levels, that.levels)
                && Arrays.equals(indices, that.indices)
                && Arrays.equals(magnitudes, that.magnitudes)
                && Arrays.equals(phases, that.phases);
    }

    @Override
    public int hashCode() {
        int result = frame.hashCode();
        result = 31 * result + Double.hashCode(sourceEnergy);
        result = 31 * result + provenance.hashCode();
        result = 31 * result + Arrays.hashCode(levels);
        result = 31 * result + Arrays.hashCode(indices);
        result = 31 * result + Arrays.hashCode(magnitudes);
        return 31 * result + Arrays.hashCode(phases);
    }

    @Override
    public String toString() {
        return "SpectralPattern[version=" + CURRENT_VERSION + ", frame=" + frame + ", sourceEnergy=" + sourceEnergy
                + ", provenance=" + provenance + ", componentCount=" + levels.length + "]";
    }

    private static void requireHeader(SpectralFrame frame, double sourceEnergy, WaveProvenance provenance,
                                      int count) {
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (!(sourceEnergy >= 0.0 && sourceEnergy <= MAX_SOURCE_ENERGY)) {
            throw new IllegalArgumentException(
                    "sourceEnergy must be in [0, MAX_SOURCE_ENERGY], got: " + sourceEnergy);
        }
        provenance.requireWindowFits(frame.sampleCount());
        int cap = frame.basis() == SpectralBasis.DFT ? MAX_COMPONENTS_DFT : MAX_COMPONENTS_HAAR;
        if (count > cap) {
            throw new IllegalArgumentException(
                    "at most " + cap + " " + frame.basis() + " components are allowed, got: " + count);
        }
    }

    private static double[] canonicalZeros(double[] source) {
        var copy = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = canonicalZero(source[i]);
        }
        return copy;
    }

    private static double canonicalZero(double value) {
        return SpectralArithmetic.canonicalZero(value);
    }

    /** Validates keys, order and values in one pass and returns the largest magnitude. */
    private double validateComponents() {
        double maxMagnitude = 0.0;
        for (int i = 0; i < levels.length; i++) {
            int level = levels[i];
            int index = indices[i];
            if (!frame.containsKey(level, index)) {
                throw new IllegalArgumentException("component at index " + i + " has key (" + level + ", " + index
                        + ") outside " + frame);
            }
            if (i > 0 && (level < levels[i - 1] || level == levels[i - 1] && index <= indices[i - 1])) {
                throw new IllegalArgumentException("component at index " + i + " with key (" + level + ", " + index
                        + ") is not strictly after (" + levels[i - 1] + ", " + indices[i - 1] + ")");
            }
            double magnitude = magnitudes[i];
            if (!Double.isFinite(magnitude) || magnitude < 0.0) {
                throw new IllegalArgumentException(
                        "magnitude at index " + i + " must be finite and non-negative, got: " + magnitude);
            }
            double phase = phases[i];
            if (!SpectralPhase.isCanonical(phase)) {
                throw new IllegalArgumentException(
                        "phase at index " + i + " must be finite and in (-π, π], got: " + phase);
            }
            if (frame.isRealValued(level, index) && phase != 0.0 && phase != StrictMath.PI) {
                throw new IllegalArgumentException("component at index " + i + " with key (" + level + ", " + index
                        + ") is real-valued in " + frame.basis() + " and needs phase 0 or π, got: " + phase);
            }
            maxMagnitude = Math.max(maxMagnitude, magnitude);
        }
        return maxMagnitude;
    }

    /**
     * Sums the retained energy with magnitudes scaled by {@code 2^-e}, {@code e} the exponent of the largest magnitude.
     * Power-of-two scaling is exact, so the result is bit-identical to the unscaled sum whenever that sum neither
     * underflows nor overflows, and a representable (possibly subnormal) energy is not lost to squaring underflow.
     */
    private double computeRetainedEnergy(double maxMagnitude) {
        if (sourceEnergy == 0.0 && maxMagnitude != 0.0) {
            throw new IllegalArgumentException(
                    "sourceEnergy 0 means silent samples, so every magnitude must be 0, got maximum " + maxMagnitude);
        }
        if (maxMagnitude == 0.0) {
            requireEnergyConsistent(0.0);
            return 0.0;
        }
        int exponent = SpectralArithmetic.scaleExponent(maxMagnitude);
        double sum = 0.0;
        boolean dft = frame.basis() == SpectralBasis.DFT;
        for (int i = 0; i < magnitudes.length; i++) {
            double squared = SpectralArithmetic.scaledSquare(magnitudes[i], exponent);
            sum += dft && !frame.isRealDftBin(indices[i]) ? squared / 2.0 : squared;
        }
        double energy = SpectralArithmetic.unscaleEnergy(dft ? frame.sampleCount() * sum : sum, exponent);
        if (!Double.isFinite(energy)) {
            throw new IllegalArgumentException("retained energy overflows for magnitudes of " + frame);
        }
        requireEnergyConsistent(energy);
        return energy;
    }

    /**
     * Retained energy may exceed the source energy by at most the allowed difference; a complete pattern (every key of
     * its frame present) must also not fall below it. The allowed difference is the larger of {@link #ENERGY_TOLERANCE}
     * relative to the larger energy and a rounding allowance of {@code sampleCount + componentCount + 4} ULPs at that
     * energy: one per rounded operation of the source sum {@code Σ x_n²} and of the retained sum, plus the scaling and
     * the final multiply. The ULP term only dominates for (near-)subnormal energies, whose relative precision is reduced.
     */
    private void requireEnergyConsistent(double energy) {
        double scale = Math.max(sourceEnergy, energy);
        double roundingUlps = frame.sampleCount() + levels.length + 4.0;
        double allowed = Math.max(ENERGY_TOLERANCE * scale, roundingUlps * Math.ulp(scale));
        if (energy - sourceEnergy > allowed) {
            throw new IllegalArgumentException("retained energy " + energy + " exceeds sourceEnergy " + sourceEnergy
                    + " beyond the allowed difference " + allowed);
        }
        if (levels.length == frame.componentCapacity() && sourceEnergy - energy > allowed) {
            throw new IllegalArgumentException("complete pattern retains energy " + energy + " but sourceEnergy is "
                    + sourceEnergy + ", beyond the allowed difference " + allowed);
        }
    }

    private final class ComponentView extends AbstractList<WaveComponent> implements RandomAccess {

        SpectralPattern owner() {
            return SpectralPattern.this;
        }

        @Override
        public WaveComponent get(int i) {
            return component(i);
        }

        @Override
        public int size() {
            return levels.length;
        }
    }
}
