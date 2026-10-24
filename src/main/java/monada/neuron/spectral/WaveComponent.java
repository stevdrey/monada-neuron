package monada.neuron.spectral;

/**
 * One spectral component as a standalone value.
 *
 * <p>{@link SpectralPattern} stores its components in compact primitive arrays and materializes this record only on
 * request ({@link SpectralPattern#component(int)}, {@link SpectralPattern#components()}). A record built by hand is
 * accepted by {@link SpectralPattern#of(SpectralFrame, double, WaveProvenance, java.util.List)} only when its
 * {@code phaseDefined} and {@code locator} equal the values the pattern derives.
 *
 * <p>Magnitude {@code -0.0} and phase {@code -0.0} are canonicalized to {@code 0.0} so equal components are equal
 * records. A phase outside {@code (-π, π]}, including {@code -π}, is rejected rather than wrapped; use
 * {@link SpectralPhase#wrap(double)} first.
 *
 * @param level component level: always {@code 0} for DFT; for Haar {@code 0} is the final approximation and
 *              {@code 1..L} are details (a scale, never a frequency)
 * @param index bin index {@code k} for DFT, coefficient position within its level for Haar
 * @param magnitude finite, non-negative normalized magnitude
 * @param phase canonical phase in radians, {@code (-π, π]}; always stored, whatever the magnitude
 * @param phaseDefined whether the phase is meaningful for comparison (magnitude above the pattern's phase epsilon)
 * @param locator frequency for DFT (unit given by {@link LocatorUnit}), support start position for Haar
 */
public record WaveComponent(int level, int index, double magnitude, double phase, boolean phaseDefined,
                            double locator) {

    /** Validates finite values and canonicalizes signed zeros. */
    public WaveComponent {
        if (level < 0) {
            throw new IllegalArgumentException("level must be non-negative, got: " + level);
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative, got: " + index);
        }
        if (!Double.isFinite(magnitude) || magnitude < 0.0) {
            throw new IllegalArgumentException("magnitude must be finite and non-negative, got: " + magnitude);
        }
        if (!Double.isFinite(phase) || phase <= -SpectralPhase.PI || phase > SpectralPhase.PI) {
            throw new IllegalArgumentException("phase must be finite and in (-π, π], got: " + phase);
        }
        if (!Double.isFinite(locator) || locator < 0.0) {
            throw new IllegalArgumentException("locator must be finite and non-negative, got: " + locator);
        }
        magnitude = magnitude == 0.0 ? 0.0 : magnitude;
        phase = phase == 0.0 ? 0.0 : phase;
        locator = locator == 0.0 ? 0.0 : locator;
    }
}
