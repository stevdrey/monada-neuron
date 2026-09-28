package monada.neuron.runtime.selection;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.FrequencyStateBatch;

import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic {@link BatchResonanceEvaluator} that delegates scoring to backends selected by
 * {@link RuntimeBackendSelector} based on configured preferences, input layout, and batch size.
 *
 * <p>Caches selection decisions by regime to eliminate allocation and volatile-write overhead in hot loops.
 */
public final class SelectingBatchResonanceEvaluator implements BatchResonanceEvaluator {

    private final RuntimeBackendSelector selector;
    private final boolean isDynamicAuto;
    private final int primitiveThreshold;
    private final int objectThreshold;

    private volatile SelectionDiagnostic<ResonanceBackendId> lastDiagnostic;
    private volatile BackendSelection<BatchResonanceEvaluator, ResonanceBackendId> cachedPrimitiveSelection;
    private volatile BackendSelection<BatchResonanceEvaluator, ResonanceBackendId> cachedObjectSelection;
    private volatile int lastPrimitiveRegime = -1;
    private volatile int lastObjectRegime = -1;

    public SelectingBatchResonanceEvaluator(RuntimeBackendSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.isDynamicAuto = selector.config().overallPreference() != ExecutionPreference.REFERENCE
                && selector.config().resonance().preference() == ExecutionPreference.AUTO
                && selector.capabilities().isVectorApiAvailable();
        this.primitiveThreshold = Math.max(
                selector.config().resonance().vectorCrossoverThreshold(),
                selector.capabilities().vectorLaneWidth());
        this.objectThreshold = Math.max(
                selector.config().resonance().vectorCrossoverThreshold(),
                32);
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
        int regime = isDynamicAuto ? (length >= primitiveThreshold ? 1 : 0) : 0;
        var selection = cachedPrimitiveSelection;
        if (selection == null || lastPrimitiveRegime != regime) {
            selection = selector.selectResonance(length, false);
            this.lastDiagnostic = selection.diagnostic();
            this.cachedPrimitiveSelection = selection;
            this.lastPrimitiveRegime = regime;
        }

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
        int regime = isDynamicAuto ? (length >= objectThreshold ? 1 : 0) : 0;
        var selection = cachedObjectSelection;
        if (selection == null || lastObjectRegime != regime) {
            selection = selector.selectResonance(length, true);
            this.lastDiagnostic = selection.diagnostic();
            this.cachedObjectSelection = selection;
            this.lastObjectRegime = regime;
        }

        selection.backend().scoreBatch(first, second, results, offset, length);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
