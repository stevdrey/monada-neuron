package monada.neuron.visual;

/**
 * The typed rejection carried by {@link Result.Rejected}: an expected outcome for a valid value outside a limit,
 * never a caller bug (contract {@code visual-geometry/1} Section 7).
 *
 * @param reason why the value was rejected; never null
 */
public record VisualRejection(VisualRejectionReason reason) {

    public VisualRejection {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
    }
}
