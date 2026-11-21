package monada.neuron.visual;

/**
 * Why a well formed visual value was rejected (contract {@code visual-geometry/1} Section 7).
 *
 * <p>All seven reasons are defined here so later children add none. Issue #143 returns {@link #SIZE_UNSUPPORTED},
 * {@link #CAP_EXCEEDED}, {@link #TRANSFORM_OUT_OF_RANGE} and {@link #BELOW_GRID_RESOLUTION}; the others are first
 * returned by the color and spectral children. No code may depend on the ordinal of a reason.
 */
public enum VisualRejectionReason {
    /** A width, height or raster size outside {@code [1, 64]}. */
    SIZE_UNSUPPORTED,
    /** A transform that needs a power of two size received another size. */
    NOT_POWER_OF_TWO,
    /** A wavelet depth outside the supported levels. */
    LEVELS_UNSUPPORTED,
    /** More than 16 parts, 16 holes, 8 hypotheses or 256 layers. */
    CAP_EXCEEDED,
    /** Planes or images whose color spaces do not match. */
    COLOR_SPACE_MISMATCH,
    /** A pose translation outside {@code [-1, 2]}, or a render below two pixels. */
    TRANSFORM_OUT_OF_RANGE,
    /**
     * A shape that the bounded {@code 2^-12} grid cannot hold once snapped: a feature collapses to nothing, a
     * triangle does not stay canonical, or a composite hole lands beyond the {@code 2^28} numerator bound.
     */
    BELOW_GRID_RESOLUTION
}
