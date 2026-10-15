package monada.neuron.evaluation.routing;

/**
 * The three replay arms, run over identical fixtures.
 *
 * <ul>
 *   <li>{@link #STATIC}: the #62 rule baseline; every decision sees an empty preference and no feedback is
 *       derived;</li>
 *   <li>{@link #DERIVED_NOT_CONSUMED}: feedback is derived for every observed outcome against a bound state that never
 *       consumes it, so decisions must equal the static arm's;</li>
 *   <li>{@link #ADAPTIVE}: the caller-owned state consumes each derivation once its outcome is visible, and later
 *       decisions read its snapshot.</li>
 * </ul>
 */
public enum ReplayArm {
    STATIC("RoutingReplay.static"),
    DERIVED_NOT_CONSUMED("RoutingReplay.derived-not-consumed"),
    ADAPTIVE("RoutingReplay.adaptive");

    private final String benchmarkName;

    ReplayArm(String benchmarkName) {
        this.benchmarkName = benchmarkName;
    }

    /** The report row and metadata prefix of this arm. */
    public String benchmarkName() {
        return benchmarkName;
    }
}
