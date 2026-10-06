package monada.neuron.resonance.adapter;

import com.monada.core.ResonanceResult;

import java.util.List;

/**
 * Store-side recall operation the adapter delegates to for each encoded query.
 *
 * <p>The production implementation wraps {@code MonadaMemory}; the seam exists so the Neuron-owned
 * work around a recall (encoding, bounded merge, references, decoding) can run, and be measured,
 * without a real store.
 */
@FunctionalInterface
public interface ResonanceStoreRecall {

    /** Returns at most {@code limit} store results for the query, best first. */
    List<ResonanceResult> recall(String query, int limit, double threshold);
}
