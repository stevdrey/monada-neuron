package monada.neuron.evaluation.visual;

/** What a fixture holds: a one channel binary mask or a four channel RGBA image. */
public enum FixtureKind {

    /** One byte per pixel, {@code 0} or {@code 255}. */
    MASK("mask", 1),

    /** Four interleaved bytes per pixel in {@code R, G, B, A} order, 8 bit sRGB with straight alpha. */
    RGB("rgb", 4);

    private final String printed;
    private final int channels;

    FixtureKind(String printed, int channels) {
        this.printed = printed;
        this.channels = channels;
    }

    /** The value of the manifest's {@code kind} column. */
    public String printed() {
        return printed;
    }

    /** Bytes per decoded pixel. */
    public int channels() {
        return channels;
    }
}
