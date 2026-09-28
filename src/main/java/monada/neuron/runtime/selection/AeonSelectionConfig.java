package monada.neuron.runtime.selection;

import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.ContextualParallelism;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for Aeon coordination backend selection.
 *
 * @param preference execution preference for Aeon coordination
 * @param maxParallelism maximum CPU worker parallelism for parallel coordination
 * @param directParallelismThreshold input threshold to trigger direct parallel coordination
 * @param contextualMode contextual parallel execution mode
 * @param explicitBackend target backend when {@link ExecutionPreference#EXPLICIT} is configured
 */
public record AeonSelectionConfig(
        ExecutionPreference preference,
        int maxParallelism,
        int directParallelismThreshold,
        ContextualParallelism contextualMode,
        Optional<AeonBackendId> explicitBackend) {

    public AeonSelectionConfig {
        Objects.requireNonNull(preference, "preference must not be null");
        Objects.requireNonNull(contextualMode, "contextualMode must not be null");
        Objects.requireNonNull(explicitBackend, "explicitBackend must not be null");
        if (maxParallelism <= 0) {
            throw new IllegalArgumentException("maxParallelism must be positive: " + maxParallelism);
        }
        if (directParallelismThreshold <= 0) {
            throw new IllegalArgumentException("directParallelismThreshold must be positive: " + directParallelismThreshold);
        }
    }

    /** Creates a safe reference sequential configuration. */
    public static AeonSelectionConfig reference() {
        return new AeonSelectionConfig(
                ExecutionPreference.REFERENCE,
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                BoundedParallelAeonCoordinator.DEFAULT_PARALLELISM_THRESHOLD,
                ContextualParallelism.SEQUENTIAL_ORACLE,
                Optional.empty());
    }

    /**
     * Creates an automatic selection configuration based on benchmark evidence.
     *
     * <p>Per ADR 0016 and the PR #38 validation gate, contextual parallel coordination was 38.4%
     * slower and increased GC allocation by 39.4%. Therefore, contextual coordination in AUTO mode
     * strictly selects {@link ContextualParallelism#SEQUENTIAL_ORACLE}. Direct parallel coordination
     * uses the conservative threshold {@link BoundedParallelAeonCoordinator#DEFAULT_PARALLELISM_THRESHOLD}
     * unless explicitly configured.
     */
    public static AeonSelectionConfig auto() {
        return new AeonSelectionConfig(
                ExecutionPreference.AUTO,
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                BoundedParallelAeonCoordinator.DEFAULT_PARALLELISM_THRESHOLD,
                ContextualParallelism.SEQUENTIAL_ORACLE,
                Optional.empty());
    }

    /** Creates an explicit backend configuration. */
    public static AeonSelectionConfig explicit(AeonBackendId backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        return new AeonSelectionConfig(
                ExecutionPreference.EXPLICIT,
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                BoundedParallelAeonCoordinator.DEFAULT_PARALLELISM_THRESHOLD,
                ContextualParallelism.SEQUENTIAL_ORACLE,
                Optional.of(backend));
    }
}
