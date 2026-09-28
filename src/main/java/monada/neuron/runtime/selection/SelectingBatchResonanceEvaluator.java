package monada.neuron.runtime.selection;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.FrequencyStateBatch;

import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic {@link BatchResonanceEvaluator} that delegates scoring to backends selected by
 * {@link RuntimeBackendSelector} based on configured preferences and batch size.
 */
public final class SelectingBatchResonanceEvaluator implements BatchResonanceEvaluator {

    private final RuntimeBackendSelector selector;
    private volatile SelectionDiagnostic<ResonanceBackendId> lastDiagnostic;

    public SelectingBatchResonanceEvaluator(RuntimeBackendSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    public SelectingBatchResonanceEvaluator(RuntimeSelectionConfig config) {
        this(new RuntimeBackendSelector(config));
    }

    /** Returns the underlying backend selector. */
    public RuntimeBackendSelector selector() {
        return selector;
    }

    /** Returns the diagnostic record from the most recent batch evaluation, if any. */
    public Optional<SelectionDiagnostic<ResonanceBackendId>> lastDiagnostic() {
        return Optional.ofNullable(lastDiagnostic);
    }

    @Override
    public void scoreBatch(
            double[] firstAmplitudes,
            double[] firstFrequencies,
            double[] firstPhases,
            double[] secondAmplitudes,
            double[] secondFrequencies,
            double[] secondPhases,
            double[] results,
            int offset,
            int length) {
        var selection = selector.selectResonance(length);
        this.lastDiagnostic = selection.diagnostic();
        selection.backend().scoreBatch(
                firstAmplitudes,
                firstFrequencies,
                firstPhases,
                secondAmplitudes,
                secondFrequencies,
                secondPhases,
                results,
                offset,
                length);
    }

    @Override
    public void scoreBatch(
            FrequencyState[] first,
            FrequencyState[] second,
            double[] results,
            int offset,
            int length) {
        var selection = selector.selectResonance(length);
        this.lastDiagnostic = selection.diagnostic();
        selection.backend().scoreBatch(first, second, results, offset, length);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
