/**
 * Experimental, opt in visual geometry contracts of Visual Experiment 1 ({@code visual-geometry/1},
 * {@code docs/specs/visual-geometry-v1.md}, ADR 0053, issue #143).
 *
 * <p>The package keeps three things apart. <b>Geometry</b> is a {@link monada.neuron.visual.ShapePrimitive}
 * ({@link monada.neuron.visual.Circle}, {@link monada.neuron.visual.Square}, {@link monada.neuron.visual.Triangle},
 * {@link monada.neuron.visual.Composite}) reduced to a canonical {@link monada.neuron.visual.VisualGeometry}.
 * <b>Pose</b> (position, size, rotation) is a {@link monada.neuron.visual.Transform2D}. <b>Pixels</b> are a
 * {@link monada.neuron.visual.BinaryMask}. Appearance and semantics have no type here: no shape carries a color, a
 * texture or a label, and nothing infers a class from coefficients or silhouettes.
 *
 * <p><b>Coordinates.</b> Unit square, origin top left, {@code y} down, pixel centers at {@code (x + 0.5) / N},
 * rotation positive clockwise on screen. Shape parameters are multiples of {@code 2^-12}, and geometry equality is
 * exact on those snapped values.
 *
 * <p><b>Failure model.</b> A malformed value (null, NaN, off the grid, degenerate) is a caller bug and throws
 * {@link java.lang.IllegalArgumentException}. A valid value outside a limit comes back as
 * {@link monada.neuron.visual.Result.Rejected} with a {@link monada.neuron.visual.VisualRejectionReason}. No
 * operation returns a partial result. Everything is bounded (64x64 pixels, 16 parts, 16 holes), immutable,
 * deterministic and single threaded.
 *
 * <p><b>Boundaries.</b> Using the package is the opt in: no stage, no default composition and no other core package
 * refers to it. Nothing here is a {@link monada.neuron.signal.Signal}, extends
 * {@link monada.neuron.model.FrequencyState} or is persisted; durable visual atoms belong to Monada Resonance Store.
 */
package monada.neuron.visual;
