package monada.neuron.evaluation.visual;

/** The thirteen fixture groups of contract Section 8.10; splits are stratified within each one. */
public enum FixtureGroup {

    POSITIVE("positive", FixtureKind.MASK),
    COMPOSITE("composite", FixtureKind.MASK),
    NEGATIVE("negative", FixtureKind.MASK),
    AMBIGUOUS("ambiguous", FixtureKind.MASK),
    FLAT("flat", FixtureKind.RGB),
    GRADIENT("gradient", FixtureKind.RGB),
    TONE("tone", FixtureKind.RGB),
    IMPULSE("impulse", FixtureKind.RGB),
    CHECKERBOARD("checkerboard", FixtureKind.RGB),
    STEP("step", FixtureKind.RGB),
    SHAPE_RENDER("shape render", FixtureKind.RGB),
    CHANNEL_PROBE("channel probe", FixtureKind.RGB),
    DEGENERATE("degenerate", FixtureKind.RGB);

    private final String printed;
    private final FixtureKind kind;

    FixtureGroup(String printed, FixtureKind kind) {
        this.printed = printed;
        this.kind = kind;
    }

    /** The value of the manifest's {@code group} column, spaces included. */
    public String printed() {
        return printed;
    }

    /** The kind every fixture of this group has. */
    public FixtureKind kind() {
        return kind;
    }
}
