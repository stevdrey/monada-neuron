package monada.neuron.resonance;

import monada.neuron.spectral.SpectralPhase;

/**
 * Contribution of one band that both compared patterns retain, keyed by its {@code (level, index)}.
 *
 * <p>The values follow contract {@code ncm-wave-cognition/1} section 7.3:
 *
 * <pre>{@code
 * phaseDifference = wrap(phase1 - phase2)                          in (-π, π]
 * alignment       = min(m1, m2) / max(m1, m2), 0 when both are 0    in [0, 1]; magnitude ratio, phase ignored
 * interference    = m1 · m2 · cos(phaseDifference)                  signed; negative is destructive
 * }</pre>
 *
 * <p>Alignment and interference are distinct quantities: alignment ignores phase, interference is signed. Negative
 * interference is a diagnostic of a destructive phase relation and is never a persisted score.
 *
 * @param level component level of the shared key ({@code 0} for every DFT bin)
 * @param index component index of the shared key (the DFT bin, or the Haar position)
 * @param firstMagnitude magnitude retained by the first pattern
 * @param secondMagnitude magnitude retained by the second pattern
 * @param phaseDifference stored phase of the first minus that of the second, wrapped to {@code (-π, π]}
 * @param alignment magnitude ratio in {@code [0, 1]}
 * @param interference signed interference term
 * @param phasesDefined whether both patterns flag this band's phase as defined, so it enters the coherence
 */
public record BandContribution(int level, int index, double firstMagnitude, double secondMagnitude,
                               double phaseDifference, double alignment, double interference,
                               boolean phasesDefined) {

    /** Validates the key, the finite magnitudes and the ranges of the derived values. */
    public BandContribution {
        if (level < 0 || index < 0) {
            throw new IllegalArgumentException("level and index must be non-negative, got: (" + level + ", "
                    + index + ")");
        }
        requireMagnitude(firstMagnitude, "firstMagnitude");
        requireMagnitude(secondMagnitude, "secondMagnitude");
        if (!SpectralPhase.isCanonical(phaseDifference)) {
            throw new IllegalArgumentException("phaseDifference must be canonical in (-π, π], got: "
                    + phaseDifference);
        }
        if (!(alignment >= 0.0 && alignment <= 1.0)) {
            throw new IllegalArgumentException("alignment must be in [0, 1], got: " + alignment);
        }
        if (!Double.isFinite(interference)) {
            throw new IllegalArgumentException("interference must be finite, got: " + interference);
        }
    }

    private static void requireMagnitude(double magnitude, String name) {
        if (!Double.isFinite(magnitude) || magnitude < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative, got: " + magnitude);
        }
    }
}
