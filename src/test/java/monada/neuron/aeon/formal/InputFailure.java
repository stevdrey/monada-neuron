package monada.neuron.aeon.formal;

/**
 * The failure a scripted propagation throws for one input. Each failing input has its own instance,
 * so a thrown or suppressed exception identifies exactly one input.
 */
final class InputFailure extends RuntimeException {

    private final int inputIndex;

    InputFailure(int inputIndex) {
        // Suppression stays enabled: the coordinator attaches concurrent failures to the primary one.
        super("input " + inputIndex + " failed", null, true, false);
        this.inputIndex = inputIndex;
    }

    int inputIndex() {
        return inputIndex;
    }
}
