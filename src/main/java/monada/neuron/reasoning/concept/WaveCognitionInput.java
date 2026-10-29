package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;

/**
 * Windows a host supplies for one execution of {@link WaveCognitionReasoningStage}, in the host's order (contract
 * {@code ncm-wave-cognition/2} section 10.1). The list may be empty: the execution opted in but has no samples.
 *
 * <p>Every window is independent evidence for the <b>same</b> next step, such as channels, sensors or views of one
 * instant: equal concepts predicted from several windows merge into one hypothesis supported by each of them. The
 * windows of a time series are not one input; the host runs one execution per step and carries the previous concept
 * forward itself.
 *
 * <p>The input is resolved per execution and never retained by the stage; nothing in it reaches a {@code Signal}.
 *
 * @param windows the windows, in the order the stage processes them
 */
public record WaveCognitionInput(List<WaveWindow> windows) {

    /** Copies the windows. */
    public WaveCognitionInput {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows must not be null"));
    }

    /** Creates an input from windows in order. */
    public static WaveCognitionInput of(WaveWindow... windows) {
        return new WaveCognitionInput(List.of(windows));
    }
}
