package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;

/**
 * Typed, bounded diagnostics of one {@link WaveCognitionReasoningStage} execution: how it ended and what happened to
 * each window it examined, in input order (contract {@code ncm-wave-cognition/2} section 10.1).
 *
 * @param outcome how the execution ended
 * @param windowsReceived how many windows the host supplied
 * @param windows the examined windows, positions {@code 0..k} in order; empty unless
 *     {@link WaveCognitionOutcome#examinedWindows()}
 * @param totalComparisons centroid comparisons summed over the examined windows
 */
public record WaveCognitionReport(WaveCognitionOutcome outcome, int windowsReceived, List<WindowOutcome> windows,
                                  int totalComparisons) {

    /** Copies the windows and validates their consistency. */
    public WaveCognitionReport {
        Objects.requireNonNull(outcome, "outcome must not be null");
        windows = List.copyOf(Objects.requireNonNull(windows, "windows must not be null"));
        if (windowsReceived < 0 || windows.size() > windowsReceived) {
            throw new IllegalArgumentException("windowsReceived must be non-negative and cover the examined windows");
        }
        if (!outcome.examinedWindows() && !windows.isEmpty()) {
            throw new IllegalArgumentException(outcome + " examines no window");
        }
        if (outcome == WaveCognitionOutcome.PROCESSED && windows.size() != windowsReceived) {
            throw new IllegalArgumentException("PROCESSED reports every received window");
        }
        if ((outcome == WaveCognitionOutcome.NO_HOST_CONTEXT || outcome == WaveCognitionOutcome.NOT_REQUESTED
                || outcome == WaveCognitionOutcome.NO_WINDOWS) && windowsReceived != 0) {
            throw new IllegalArgumentException(outcome + " receives no window");
        }
        long sum = 0;
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).position() != i) {
                throw new IllegalArgumentException("window " + i + " has position " + windows.get(i).position());
            }
            sum += windows.get(i).comparisons();
        }
        if (sum != totalComparisons) {
            throw new IllegalArgumentException("totalComparisons " + totalComparisons + " differs from the windows' "
                    + sum);
        }
    }

    /** Returns a report for an execution that examined no window. */
    static WaveCognitionReport empty(WaveCognitionOutcome outcome, int windowsReceived) {
        return new WaveCognitionReport(outcome, windowsReceived, List.of(), 0);
    }
}
