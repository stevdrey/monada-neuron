package monada.neuron.evaluation.visual;

/**
 * The frozen partition of a fixture id (contract Section 8.10). A split is derived from the id's split byte, never
 * typed into a definition. The test split is single use: it is scored once, by issue #151.
 */
public enum FixtureSplit {

    TRAIN("train"),
    VALIDATION("validation"),
    TEST("test");

    private final String printed;

    FixtureSplit(String printed) {
        this.printed = printed;
    }

    /** The value of the manifest's {@code split} column. */
    public String printed() {
        return printed;
    }
}
