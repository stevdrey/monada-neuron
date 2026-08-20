package monada.neuron.evolution;

/**
 * Bounded numerical configuration for deterministic adaptation policies.
 *
 * @param learningRate  rate of state transition per adaptation step (range: {@code (0.0, 1.0]})
 * @param minAmplitude  lower bound for frequency amplitude (must be non-negative)
 * @param maxAmplitude  upper bound for frequency amplitude (must be &gt;= minAmplitude)
 * @param minEnergy     lower bound for node energy (must be non-negative)
 * @param maxEnergy     upper bound for node energy (must be &gt;= minEnergy)
 * @param minFrequency  lower bound for wave frequency (must be non-negative)
 * @param maxFrequency  upper bound for wave frequency (must be &gt;= minFrequency)
 * @param energyStep    base energy delta for feedback reinforcement (must be positive)
 */
public record AdaptationConfig(
        double learningRate,
        double minAmplitude,
        double maxAmplitude,
        double minEnergy,
        double maxEnergy,
        double minFrequency,
        double maxFrequency,
        double energyStep) {

    /** Default conservative adaptation configuration. */
    public static final AdaptationConfig DEFAULT = new AdaptationConfig(
            0.1,
            0.0,
            100.0,
            0.0,
            1000.0,
            0.0,
            10000.0,
            1.0);

    /** Validates that all configuration bounds and parameters are finite, valid, and consistent. */
    public AdaptationConfig {
        if (!Double.isFinite(learningRate) || learningRate <= 0.0 || learningRate > 1.0) {
            throw new IllegalArgumentException(
                    "learningRate must be finite and in (0.0, 1.0], got: " + learningRate);
        }
        if (!Double.isFinite(minAmplitude) || minAmplitude < 0.0) {
            throw new IllegalArgumentException(
                    "minAmplitude must be finite and non-negative, got: " + minAmplitude);
        }
        if (!Double.isFinite(maxAmplitude) || maxAmplitude < minAmplitude) {
            throw new IllegalArgumentException(
                    "maxAmplitude must be finite and >= minAmplitude (" + minAmplitude + "), got: " + maxAmplitude);
        }
        if (!Double.isFinite(minEnergy) || minEnergy < 0.0) {
            throw new IllegalArgumentException(
                    "minEnergy must be finite and non-negative, got: " + minEnergy);
        }
        if (!Double.isFinite(maxEnergy) || maxEnergy < minEnergy) {
            throw new IllegalArgumentException(
                    "maxEnergy must be finite and >= minEnergy (" + minEnergy + "), got: " + maxEnergy);
        }
        if (!Double.isFinite(minFrequency) || minFrequency < 0.0) {
            throw new IllegalArgumentException(
                    "minFrequency must be finite and non-negative, got: " + minFrequency);
        }
        if (!Double.isFinite(maxFrequency) || maxFrequency < minFrequency) {
            throw new IllegalArgumentException(
                    "maxFrequency must be finite and >= minFrequency (" + minFrequency + "), got: " + maxFrequency);
        }
        if (!Double.isFinite(energyStep) || energyStep <= 0.0) {
            throw new IllegalArgumentException(
                    "energyStep must be finite and positive, got: " + energyStep);
        }
    }
}
