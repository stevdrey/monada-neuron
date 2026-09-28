package monada.neuron.runtime.selection;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for signal propagation graph backend selection.
 *
 * @param preference execution preference for graph propagation
 * @param explicitBackend target backend when {@link ExecutionPreference#EXPLICIT} is configured
 */
public record GraphSelectionConfig(
        ExecutionPreference preference,
        Optional<GraphBackendId> explicitBackend) {

    public GraphSelectionConfig {
        Objects.requireNonNull(preference, "preference must not be null");
        Objects.requireNonNull(explicitBackend, "explicitBackend must not be null");
    }

    /** Creates a safe reference configuration. */
    public static GraphSelectionConfig reference() {
        return new GraphSelectionConfig(ExecutionPreference.REFERENCE, Optional.empty());
    }

    /**
     * Creates an automatic selection configuration based on benchmark evidence.
     *
     * <p>Per ADR 0014, compact CSR traversal remains explicit and opt-in because it did not
     * satisfy the 75% allocation reduction target and is slower on threshold-routed workloads.
     * In AUTO mode, the reference object engine remains active unless an explicit backend is set.
     */
    public static GraphSelectionConfig auto() {
        return new GraphSelectionConfig(ExecutionPreference.AUTO, Optional.empty());
    }

    /** Creates an explicit backend configuration. */
    public static GraphSelectionConfig explicit(GraphBackendId backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        return new GraphSelectionConfig(ExecutionPreference.EXPLICIT, Optional.of(backend));
    }
}
