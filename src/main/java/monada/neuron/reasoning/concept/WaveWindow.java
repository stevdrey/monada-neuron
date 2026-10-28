package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SampledWave;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One host-supplied window for {@link WaveCognitionReasoningStage}: explicit samples plus the concept the host
 * believes for the preceding window (contract {@code ncm-wave-cognition/2} section 10.1).
 *
 * <p>The previous concept is caller-owned state, as in {@link ConceptQuery}: the stage never derives it from another
 * window or another execution.
 *
 * @param wave the window's samples and provenance
 * @param previousConceptCode the host's concept code for the preceding window, or empty
 */
public record WaveWindow(SampledWave wave, OptionalLong previousConceptCode) {

    /** Validates the components. */
    public WaveWindow {
        Objects.requireNonNull(wave, "wave must not be null");
        Objects.requireNonNull(previousConceptCode, "previousConceptCode must not be null");
    }

    /** Creates a window without a previous concept. */
    public static WaveWindow of(SampledWave wave) {
        return new WaveWindow(wave, OptionalLong.empty());
    }
}
