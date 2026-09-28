package monada.neuron.runtime.selection;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inspectable diagnostic record detailing a runtime backend selection decision.
 *
 * @param <B> the concrete type of backend identifier
 * @param selectedBackendId the selected backend identifier
 * @param reason the reason explaining the selection
 * @param workloadScale the relevant workload scale (e.g. batch size, node count, input count)
 * @param isFallback true if fallback was triggered instead of primary choice
 * @param fallbackReason optional human-readable message explaining why fallback occurred
 * @param metadata additional key-value diagnostic metadata
 */
public record SelectionDiagnostic<B extends BackendId>(
        B selectedBackendId,
        SelectionReason reason,
        long workloadScale,
        boolean isFallback,
        Optional<String> fallbackReason,
        Map<String, String> metadata) {

    public SelectionDiagnostic {
        Objects.requireNonNull(selectedBackendId, "selectedBackendId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(fallbackReason, "fallbackReason must not be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
