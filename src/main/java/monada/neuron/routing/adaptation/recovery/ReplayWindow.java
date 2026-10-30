package monada.neuron.routing.adaptation.recovery;

import java.util.List;
import java.util.Objects;

/**
 * Which part of the export a replay from a checkpoint consumes: every observation whose ledger sequence is above
 * {@code afterSequence}, plus every revision of the listed samples (the checkpoint's retained markers, still
 * reconciled against them). It mirrors the filter {@code RoutingStateStore.rebuildFrom} applies itself, so passing the
 * whole export stays correct; the window lets a host fetch less.
 *
 * @param afterSequence the checkpoint's cutoff
 * @param retainedSamples sample references of the retained markers, in the checkpoint's order
 */
public record ReplayWindow(long afterSequence, List<String> retainedSamples) {

    /** Requires a non-negative cutoff and copies the list. */
    public ReplayWindow {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative, got: " + afterSequence);
        }
        retainedSamples = List.copyOf(Objects.requireNonNull(retainedSamples, "retainedSamples must not be null"));
    }
}
