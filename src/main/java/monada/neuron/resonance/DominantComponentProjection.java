package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralPattern;

import java.util.Objects;

/**
 * Explicit, lossy projection of a DFT {@link SpectralPattern} onto one {@link FrequencyState}: its dominant component
 * (contract {@code ncm-wave-cognition/1} section 3.4, ADR 0035 and ADR 0041).
 *
 * <p>It exists so the single-wave path can serve as a control for spectral comparisons (hypothesis H2: the scalar
 * control is {@link ScalarResonanceMetric} on this projection). It is never applied implicitly, and
 * {@code FrequencyState} is not extended.
 *
 * <ul>
 *   <li>The dominant component is the one with the largest magnitude; ties go to the lower {@code (level, index)},
 *       the pattern's canonical order.</li>
 *   <li>The state is {@code FrequencyState(magnitude, locator, phase)}: the normalized magnitude, the bin frequency
 *       in the pattern's {@link SpectralPattern#locatorUnit() locator unit} and the stored phase.</li>
 *   <li>A pattern with no component, or only zero magnitudes, projects to {@link FrequencyState#ZERO}.</li>
 *   <li>Every other component is dropped: the projection cannot distinguish patterns that share their dominant
 *       component.</li>
 * </ul>
 *
 * <p>Only DFT patterns are projected. A Haar locator is a sample position, not a frequency (contract section 2.1), so
 * a Haar pattern is rejected rather than reported as a wave of some frequency.
 */
public final class DominantComponentProjection {

    /** Creates a stateless projection. */
    public DominantComponentProjection() {
    }

    /**
     * Projects a DFT pattern onto its dominant component.
     *
     * @param pattern the DFT pattern
     * @return the dominant component as a frequency state, or {@link FrequencyState#ZERO} for a silent pattern
     * @throws NullPointerException if {@code pattern} is {@code null}
     * @throws IllegalArgumentException if {@code pattern} is not a DFT pattern
     */
    public FrequencyState project(SpectralPattern pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.basis() != SpectralBasis.DFT) {
            throw new IllegalArgumentException("only DFT patterns have frequency locators, got: " + pattern.basis());
        }
        int dominant = -1;
        double peak = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            if (pattern.magnitude(i) > peak) {
                peak = pattern.magnitude(i);
                dominant = i;
            }
        }
        if (dominant < 0) {
            return FrequencyState.ZERO;
        }
        return new FrequencyState(peak, pattern.locator(dominant), pattern.phase(dominant));
    }
}
