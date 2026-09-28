package monada.neuron.runtime.selection;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for batch resonance backend selection.
 *
 * @param preference execution preference for resonance scoring
 * @param vectorCrossoverThreshold minimum batch size to select Vector API SIMD (default 4 from ADR 0013)
 * @param explicitBackend target backend when {@link ExecutionPreference#EXPLICIT} is configured
 */
public record ResonanceSelectionConfig(
        ExecutionPreference preference,
        int vectorCrossoverThreshold,
        Optional<ResonanceBackendId> explicitBackend) {

    /** Default crossover threshold empirically justified in ADR 0013. */
    public static final int DEFAULT_VECTOR_CROSSOVER_THRESHOLD = 4;

    public ResonanceSelectionConfig {
        Objects.requireNonNull(preference, "preference must not be null");
        Objects.requireNonNull(explicitBackend, "explicitBackend must not be null");
        if (vectorCrossoverThreshold < 0) {
            throw new IllegalArgumentException("vectorCrossoverThreshold must be non-negative: " + vectorCrossoverThreshold);
        }
    }

    /** Creates a safe reference configuration. */
    public static ResonanceSelectionConfig reference() {
        return new ResonanceSelectionConfig(
                ExecutionPreference.REFERENCE,
                DEFAULT_VECTOR_CROSSOVER_THRESHOLD,
                Optional.empty());
    }

    /** Creates an automatic selection configuration based on benchmark evidence. */
    public static ResonanceSelectionConfig auto() {
        return new ResonanceSelectionConfig(
                ExecutionPreference.AUTO,
                DEFAULT_VECTOR_CROSSOVER_THRESHOLD,
                Optional.empty());
    }

    /** Creates an explicit backend configuration. */
    public static ResonanceSelectionConfig explicit(ResonanceBackendId backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        return new ResonanceSelectionConfig(
                ExecutionPreference.EXPLICIT,
                DEFAULT_VECTOR_CROSSOVER_THRESHOLD,
                Optional.of(backend));
    }
}
