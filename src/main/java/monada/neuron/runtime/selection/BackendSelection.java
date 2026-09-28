package monada.neuron.runtime.selection;

import java.util.Objects;

/**
 * Immutable pair coupling the instantiated backend implementation with its inspectable selection diagnostic.
 *
 * @param <T> the operational interface type (e.g. {@code BatchResonanceEvaluator}, {@code CognitiveSignalPropagationEngine})
 * @param <B> the backend identifier type
 * @param backend the active backend instance
 * @param diagnostic inspectable details on why and how this backend was selected
 */
public record BackendSelection<T, B extends BackendId>(
        T backend,
        SelectionDiagnostic<B> diagnostic) {

    public BackendSelection {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
    }
}
