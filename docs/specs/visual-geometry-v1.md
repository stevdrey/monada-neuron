# Visual Geometry Experiment Contract v1 (`visual-geometry/1`)

## Status

| Field | Value |
| :--- | :--- |
| Contract identifier | `visual-geometry/1` |
| Issue | [#141](https://github.com/stevdrey/monada-neuron/issues/141) (Visual Experiment 1 of 3, child 01 of 11) |
| Epic | [#138](https://github.com/stevdrey/monada-neuron/issues/138), geometry first visual research |
| Decision record | [ADR 0053](../adr/0053-visual-geometry-experiment-protocol.md) |
| Composes, unchanged | [ADR 0001](../adr/0001-cognition-memory-boundary.md), [ADR 0005](../adr/0005-signals-without-identifiers.md), [ADR 0006](../adr/0006-deterministic-scalar-resonance-metric.md), [ADR 0019](../adr/0019-typed-hypothesis-and-evidence-model.md), [ADR 0050](../adr/0050-ncm-investment-decision.md) |
| Reference only | [`ncm-wave-cognition/2`](ncm-wave-cognition-v1.md), ADR 0034 to ADR 0050, the `monada.neuron.spectral` types (Section 14.2) |
| Executes | #142 to #151, each only after every issue on its blocked by list has merged into `main` and its gate (Section 11) is go |
| Code changes | None. This document adds no Java type, no dependency, no fixture file, no `Signal` change and no default |
| Date | 2026-11-05 |

This document is the normative contract for the geometry first visual experiment of Epic #138. It fixes what a
shape, an instance, an appearance and a spatial spectrum mean, who owns each, the numeric conventions, the bounds,
the fixture set with its gold values, the splits, the metrics, the thresholds, the falsifiable hypotheses and the
stop or go gates **before** any visual code or fixture file exists. Every type it names is a sketch marked
`proposed, owned by issue #N`; the owning issue may refine the shape, and any change to a semantic, convention,
fixture, threshold, hypothesis or gate stated here is a new contract version (Section 15).

**Disclosure.** At the commit that adds this contract: no visual type, fixture generator, PNG file, manifest,
fitter, transform or runner exists in this repository, and no algorithm has been run on any fixture. The grid
numerators, layer transforms, split bytes and seeds in Section 8 were computed from the rules of this contract by a
scratch script that was not committed; the same script checked that no two fixture ids share decoded pixels at either
size. #142 recomputes every one of them and V1 fails on any mismatch (Section 11).

**Versioning.** Changing any rule here after V1 is merged is `visual-geometry/2`, recorded in Section 15 with its
reason. The test split is **single use**: once #151 scores it at V6, a later contract version needs new held out ids.

```text
host or evaluation runner
  -> BinaryMask | RgbaImage | Annotation              typed inputs, caller owned; no Signal, no stage
  -> PrimitiveFitter                                  ranked ShapeHypothesis list | typed Abstain
  -> VisualGeometry (canonical) + Contour / SignedDistanceField views
  -> ShapeInstance = geometry reference + Transform2D one geometry, many instances, in memory only
  -> ImagePlane / RgbaImage with explicit ColorSpace conversions
  -> Dft2d | Haar2d                                   forward and inverse, typed rejection outside the caps
  -> Compositor                                       instances + Appearance -> deterministic RgbaImage
  -> evaluation module                                fixtures, manifest, baselines, ablation runner, report
```

## 1. Scope, Claims and Non Claims

### 1.1 Question

On project authored 32x32 and 64x64 binary masks and RGB images, can a bounded, deterministic CPU reference:

1. fit an annotated mask to a canonical geometry and a separate instance transform within frozen geometry
   thresholds, and abstain with the right typed reason on negative and ambiguous masks;
2. keep a canonical geometry exactly invariant under instance transforms and never merge two distinct silhouettes;
3. convert color and alpha, and transform pixel planes into 2D spatial spectra and back, within frozen numeric
   tolerances;
4. render many instances from one geometry by reference and recompose a stored image from geometry plus appearance?

### 1.2 What this contract covers

The vocabulary and the two truth tables (Section 2), the pipeline, ownership and API sketches (Section 3), every
numeric convention (Sections 4 to 7), the fixtures, manifest and splits (Section 8), metrics and thresholds
(Section 9), hypotheses, gates, arms and the verdict rule for Epic #139 (Sections 10 and 11), baselines and report
fields (Sections 12 and 13), the issue DAG and the relationship to NCM (Section 14).

### 1.3 Non goals and boundaries

- No change to `Signal`, `FrequencyState`, `ScalarResonanceMetric`, `PerceptionCapability`, the stage order or the
  Monada Resonance Store vector formats. Nothing image shaped enters a `Signal`, a stage or the Store.
- No persistence, indexing, deduplication, similarity search or geometry registry in Neuron. Durable visual atoms
  need a Store side spec and ADR in Monada Resonance Store.
- No cognitive stage and no default composition. A stage is an Epic #139 decision after a `GO`.
- No new mandatory dependency, no preview or incubator API, no GPU path, no Java WebP codec.
- No image larger than 64x64, no anisotropic scale or shear, no unsupervised segmentation.

### 1.4 Non claims (what this experiment does not claim)

- A `GO` does not show that Neuron can segment an image: every combined arm layer arrives with its coverage mask
  as host supplied segmentation.
- The combined arm shows color fidelity only on the agreeing pixels (Section 9.1); textured color is reported, not
  claimed.
- Fitted composites are not claimed to be pose invariant to the grid step; only catalogue primitives are.
- Nothing here claims a light wavelength, a semantic label, compression against PNG or WebP, or behaviour on images
  larger than 64x64.

## 2. Vocabulary and Truth Tables

### 2.1 Entities

Five entities, kept as separate types. A value of one never carries a value of another.

| Entity | Meaning | Example | Never |
|---|---|---|---|
| Canonical shape (`VisualGeometry`) | The silhouette and topology of an object in canonical form (centered, unit extent, zero rotation) | the duck outline | position, size, rotation, color, a label |
| Shape instance (`ShapeInstance`) | One placement of a canonical shape: translation, uniform scale, rotation | the same duck at half size, lower left, turned 30 degrees | a copy of the geometry, a color |
| Appearance (`Appearance`) | Color (flat RGB or an `RgbaImage` texture) and alpha attached to one instance at render time | green body, yellow beak, alpha 0.5 | geometry, a label |
| Spatial spectrum (`Spectrum2D`, `WaveletPyramid2D`) | DFT2D or Haar2D coefficients of one `ImagePlane`, with basis and provenance | the 64x64 complex plane of the red channel | an object label, a light frequency |
| Semantic concept | A host supplied label | "duck" | anything derived from pixels, bins or masks inside this epic |

### 2.2 Truth table 1: what a change to the input changes

| Change applied to an input | Canonical shape | Instance | Appearance | Spectrum | Concept |
|---|---|---|---|---|---|
| Move the duck 10 px right | same | changes | same | changes (phase only, for a whole pixel shift without clipping) | same |
| Scale the duck by 0.5 | same | changes | same | changes | same |
| Rotate the square 30 degrees | same | changes | same | changes | same |
| Paint the duck blue | same | same | changes | changes (per color plane) | same |
| Set alpha to 0.5 | same | same | changes | R, G, B planes unchanged; A plane and the composited output change | same |
| Cut a notch out of the square | changes | new instance of a new shape | same | changes | unknown |
| Replace the duck with a circle of the same color | changes | changes | same | changes | changes |
| Stretch the circle into an ellipse | changes (not a similarity) | not expressible | same | changes | unknown |

### 2.3 Truth table 2: optical RGB versus 2D spatial frequency

| Statement | True or false | Why |
|---|---|---|
| The red channel is light at about 700 nm | false | sRGB red is a display primary with a transfer function, not a wavelength |
| A 2D DFT bin `(u, v)` is a frequency of light | false | it is cycles per image width and height of a sample grid |
| A flat red fill has energy only in the DC bin of the red plane | true | constant plane, every other bin is zero within tolerance |
| Shifting an image by whole pixels, cyclically, changes its spectrum magnitudes | false (phase changes, magnitudes stay) | shift theorem; a sub pixel or clipped shift re rasterizes and does change magnitudes |
| Two images with the same magnitude spectrum show the same shape | false | phase carries the layout; the contract keeps phase |
| sRGB 128 is half as bright as sRGB 255 | false | linear value is about 0.216, not 0.5; conversions go through the transfer function |

## 3. Pipeline, Ownership and API Sketches

### 3.1 Diagram with owners

```text
Host or evaluation runner
  -> BinaryMask | RgbaImage | annotations (part and hole kinds)  typed inputs, caller owned; no Signal, no stage
  -> PrimitiveFitter (#144)                        mask (+ annotation) -> ranked ShapeHypothesis list | Abstain
  -> VisualGeometry (#143), Contour and SignedDistanceField views (#145)
  -> ShapeInstance = geometry reference + Transform2D (#146)   one geometry, many instances, in memory only
  -> ImagePlane / RgbaImage, ColorSpace conversions (#147)
  -> Dft2d (#148) | Haar2d (#149): forward and inverse, typed rejection outside the caps
  -> Compositor (#150): instances + Appearance -> deterministic RgbaImage
  -> evaluation module (#142 fixtures and manifest, #151 ablation runner, baselines, report)
```

### 3.2 Ownership

| Owner | Owns | Does not own |
|---|---|---|
| Neuron core, package `monada.neuron.visual` (opt in, experimental) | the contracts and scalar reference algorithms above; no stage, no default composition | persistence, indexing, deduplication, similarity search, labels, a geometry registry |
| `monada-neuron-evaluation`, package `monada.neuron.evaluation.visual`, resources `visual-fixtures-v1/` | the fixture generator, PNG files, manifest, splits, baselines, metrics, the ablation runner, the reuse map and reports | any public library API |
| Host | masks, images and annotations as typed values; labels | anything inside Neuron |
| Monada Resonance Store | nothing in this epic; durable visual atoms need a Store side spec and ADR | |

One package, `monada.neuron.visual`, is this contract's deliberate resolution of the per concern packages the issues
name (`monada.neuron.visual.geometry` in #143, `.color` in #147, `.synthesis` in #150). JPMS exports are per
package, so one experimental package means one opt in export and one place to remove when the experiment ends;
the children keep the issue titles and build into the package named here (ADR 0053 decision 4).

### 3.3 Data model (the target the children build)

| Entity | Owner | Fields | Rules |
|---|---|---|---|
| `ShapePrimitive` (sealed) | #143 | `Circle(cx, cy, r)`, `Square(cx, cy, side, rotation)`, `Triangle(x1, y1, x2, y2, x3, y3)`, `Composite(List<ShapePrimitive> parts, List<ShapePrimitive> holes)` | unit square coordinates; every parameter is a multiple of `2^-12` (record constructors throw otherwise); rotation is normalized to the shape's symmetry period (circle `0`, square `[0, π/2)`); composites hold at most 16 parts and 16 holes, each a non composite, in canonical order (Section 4.2); the record constructor does not enforce the cap (a larger `Composite` value is constructible), every operation that receives one returns `CAP_EXCEEDED` (Section 7) |
| `Canonicalized` | #143 | `geometry` (`VisualGeometry`), `pose` (`Transform2D`) | the result of `VisualGeometry.canonicalize(primitive)`: center, scale the larger extent to `1`, remove the rotation, snap to the grid; the pose maps the canonical geometry back to the input |
| `VisualGeometry` | #143 | `primitive` (canonical), `canonicalMask` (`BinaryMask` 64x64) | one per canonical primitive; the mask is derived at construction and is the equality witness in tests; immutable; `equals` is exact on the primitive |
| `BinaryMask` | #143 | `width`, `height`, `long[] words` | bit `i = y * width + x` lives in word `i >>> 6`, bit `i & 63`, least significant bit first; tail bits are zero; `1 <= width, height <= 64`; the factory copies the array and returns `Result` |
| `Contour` | #145 | `List<Ring> rings`; `Ring(double[] xy, boolean hole)` closed | derived view; pixel edge polygon over a square raster; outer rings have a positive shoelace sum (clockwise on screen), holes negative; each ring starts at its minimum `(y, x)` vertex; only turning vertices are kept; ring order: outer rings by start vertex, each followed by its holes by start vertex |
| `SignedDistanceField` | #145 | `width`, `height`, `double[] distances` in pixels | negative inside, positive outside, clamped to `[-4, 4]`; band `4.0` is a constant of the contract; outside the raster is background; empty mask gives `+4` everywhere (Section 4.6) |
| `Transform2D` | #146 | `tx`, `ty`, `scale`, `rotation` | finite; `tx`, `ty` in `[-1, 2]`; `scale > 0`; `rotation` stored in `(-π, π]`, positive clockwise on screen; `inverse()` defined |
| `ShapeInstance` | #146 | `geometry` (shared reference), `transform`, `ordinal` | many instances to one geometry; carries no color; `ordinal` orders draw order, ties keep list order |
| `ImagePlane` | #147 | `width`, `height`, `double[] samples`, `ColorSpace` (`SRGB`, `LINEAR`) | samples in `[0, 1]`, finite; `1 <= width, height <= 64`, rectangular allowed; copied at construction; the factory returns `Result` |
| `RgbaImage` | #147 | `r`, `g`, `b`, `alpha` planes, `colorSpace` | all planes same size; `colorSpace` applies to `r`, `g`, `b`; `alpha` is straight (unassociated) and linear by definition |
| `Spectrum2D` | #148 | `width`, `height`, `double[] re`, `double[] im`, `basis = DFT2D`, `sourceColorSpace`, `sourceChannel` | index `v * width + u`; DC at `(0, 0)`; full plane, always invertible; computed on the plane's stored values, never linearized by the transform |
| `WaveletPyramid2D` | #149 | `width`, `height`, `levels`, `double[] ll`, per level `lh[j]`, `hl[j]`, `hh[j]` | `width`, `height` powers of two; `1 <= levels <= min(6, log2(min(width, height)))`; level `1` is the finest; subband `j` is `(width / 2^j) x (height / 2^j)` row major; pairs are `(2m, 2m + 1)` with `x0` at the even index |
| `Appearance` | #150 | `Fill` (sealed: `Flat(r, g, b)` in sRGB `[0, 1]` or `Texture(RgbaImage)`), `alpha` in `[0, 1]` | attached at render time; never stored on a geometry |
| `ShapeHypothesis` | #144 | `geometry` (`VisualGeometry`), `pose` (`Transform2D`), `score` in `[0, 1]`, `ordinal` | lists are bounded to 8, ordered by score descending then ordinal ascending; `ordinal` is the catalogue index of the candidate (`0` to `5`, Section 4.9) or `6` for a composite hypothesis from an annotated fit, which always comes back as a list of exactly one hypothesis or an `Abstain` |
| `Abstain` | #144 | `reason` (`EMPTY`, `TOO_SMALL`, `FULL`, `NO_PRIMITIVE_ABOVE_MARGIN`, `AMBIGUOUS`, `PART_COUNT_MISMATCH`) | returned instead of a list, never beside one |
| `Result<T>` | #143 | `Ok(T value)` or `Rejected(VisualRejection)` | the typed result for a valid value outside a limit; no partial output |
| `VisualRejection` | #143 | `reason` (`SIZE_UNSUPPORTED`, `NOT_POWER_OF_TWO`, `LEVELS_UNSUPPORTED`, `CAP_EXCEEDED`, `COLOR_SPACE_MISMATCH`, `TRANSFORM_OUT_OF_RANGE`) | |
| Manifest row (one per `(id, size)`) | #142 | `id`, `size`, `kind`, `group`, `seed`, `formula`, `gold_geometry`, `gold_transform`, `gold_accept`, `gold_abstain`, `gold_layers`, `split`, `pixel_sha256`, `png_bytes`, `webp_lossless_bytes`, `webp_q75_bytes` | frozen; a changed gold value is a new manifest version with a new header hash; `gold_layers` is `NONE` for every id except the shape render ids (Section 8.8) |

### 3.4 API sketches

Each sketch is `proposed, owned by issue #N`. The owner may refine the shape; a refinement that changes a semantic or
bound here is `visual-geometry/2`.

```java
// #143, monada.neuron.visual (proposed, owned by issue #143)
public sealed interface ShapePrimitive permits Circle, Square, Triangle, Composite {}
public record Circle(double cx, double cy, double r) implements ShapePrimitive {}
public record Square(double cx, double cy, double side, double rotation) implements ShapePrimitive {}
public record Triangle(double x1, double y1, double x2, double y2, double x3, double y3) implements ShapePrimitive {}
public record Composite(List<ShapePrimitive> parts, List<ShapePrimitive> holes) implements ShapePrimitive {}
public sealed interface Result<T> permits Ok, Rejected {}       // Ok(T value) | Rejected(VisualRejection rejection)
public record VisualRejection(VisualRejectionReason reason) {}
public final class BinaryMask { static Result<BinaryMask> of(int w, int h, long[] words); int width(); int height(); boolean get(int x, int y); long[] words(); /* copy */ }
public final class VisualGeometry { static Result<Canonicalized> canonicalize(ShapePrimitive p); ShapePrimitive primitive(); BinaryMask canonicalMask(); }
public record Canonicalized(VisualGeometry geometry, Transform2D pose) {}

// #144 (proposed, owned by issue #144)
public interface PrimitiveFitter { FitResult fit(BinaryMask mask); FitResult fit(BinaryMask mask, Annotation annotation); }
public enum Kind { CIRCLE, SQUARE, TRIANGLE }
public record Annotation(List<Kind> parts, List<Kind> holes) {}   // parts non empty, each list at most 16
public sealed interface FitResult permits Hypotheses, Abstain {}
public record Hypotheses(List<ShapeHypothesis> ranked) implements FitResult {}
public record ShapeHypothesis(VisualGeometry geometry, Transform2D pose, double score, int ordinal) {}
public record Abstain(AbstainReason reason) implements FitResult {}

// #145 (proposed, owned by issue #145)
public record Contour(List<Ring> rings) { static Contour of(BinaryMask m); Result<BinaryMask> rasterize(int n); }
public record SignedDistanceField(int width, int height, double[] distances) { static Result<SignedDistanceField> of(Contour c, int n); BinaryMask mask(); }

// #146 (proposed, owned by issue #146)
public record Transform2D(double tx, double ty, double scale, double rotation) { Transform2D inverse(); double[] apply(double x, double y); }
public record ShapeInstance(VisualGeometry geometry, Transform2D transform, int ordinal) {}
public final class InstanceRasterizer { Result<BinaryMask> render(ShapeInstance i, int n); }

// #147 (proposed, owned by issue #147)
public enum ColorSpace { SRGB, LINEAR }
public final class ImagePlane { static Result<ImagePlane> of(int w, int h, double[] s, ColorSpace cs); ImagePlane toLinear(); ImagePlane toSrgb(); }
public record RgbaImage(ImagePlane r, ImagePlane g, ImagePlane b, ImagePlane alpha, ColorSpace colorSpace) {}
public final class SrgbCodec { static Result<RgbaImage> decode(byte[] rgba8, int w, int h); static byte[] encode(RgbaImage img); }

// #148 Dft2d (proposed, owned by issue #148); #149 Haar2d (proposed, owned by issue #149)
public record Inverse(ImagePlane plane, double maxOvershoot, double maxImaginary) {}
public final class Dft2d { Result<Spectrum2D> forward(ImagePlane p); Result<Inverse> inverse(Spectrum2D s); }
public final class Haar2d { Haar2d(int levels); Result<WaveletPyramid2D> forward(ImagePlane p); Result<Inverse> inverse(WaveletPyramid2D w); }

// #150 (proposed, owned by issue #150)
public sealed interface Fill permits Flat, Texture {}
public record Appearance(Fill fill, double alpha) {}
public record Layer(ShapeInstance instance, Appearance appearance) {}
public final class Compositor { Result<RgbaImage> render(List<Layer> layers, int w, int h, ColorSpace out); }

// monada.neuron.evaluation.visual: FixtureGenerator, FixtureManifest (proposed, owned by issue #142);
// ReuseMap, VisualAblationRunner (proposed, owned by issue #151)
public final class FixtureGenerator { /* manifest row -> decoded pixels, deterministic; PNG written once by #142 */ }
public record FixtureManifest(List<Row> rows, String sha256) { static FixtureManifest load(); }
public final class GeometryMetrics { /* IoU, boundary distances, pose error; owned by #144 */ }
public final class ReuseMap { /* caller owned Map<ShapePrimitive, VisualGeometry>; counts instances per geometry */ }
public final class VisualAblationRunner { /* arms: geometry, rgb, spectral, combined; writes build/visual-ablation/report.{md,json} */ }
```

## 4. Geometry Conventions and Fitting

### 4.1 Coordinates

Unit square `[0, 1] x [0, 1]`, origin top left, `y` down. The center of pixel `(x, y)` in an `N x N` raster is
`((x + 0.5) / N, (y + 0.5) / N)`; geometry rasters are square, color planes may be rectangular. Rotation is positive
clockwise on screen; the matrix is `[cos -sin; sin cos]` applied in these `y` down coordinates. An instance maps a
canonical point `p` to `R(rotation) * (scale * (p - c)) + c + (tx, ty)` with `c = (0.5, 0.5)`.

### 4.2 Canonical form, the grid and equality

A primitive is canonical when its axis aligned bounding box is centered at `(0.5, 0.5)`, its larger extent is `1`
within one grid step, and its rotation is `0`. For a square, rotation `0` means axis aligned; for a triangle, the
longest edge lies horizontal at the bottom with the third vertex above, ties pick the lexicographically smallest
vertex sequence; a circle has no rotation. A composite is canonical at rotation `0` as given by its frozen gold
decomposition (composites are never re oriented); its parts and holes are canonicalized as a whole, not
individually.

`canonicalize` centers, scales, removes the rotation, then snaps every parameter to the grid `k / 4096` (a dyadic
rational with 12 fractional bits) and returns the geometry and the pose that undoes the normalization. Record
constructors throw on a value off the grid. Equality is exact on the snapped canonical parameters. Similarity (IoU,
boundary distance) is a reported metric and never an identity. In memory reuse happens only on exact equality,
through a map the caller owns; Neuron keeps no registry.

Composite canonical order: parts and holes are each sorted by `cy`, then `cx`, then kind ordinal, then the remaining
parameters in declaration order; equal entries stay adjacent.

### 4.3 Rasterization and rendered extent

A pixel is foreground if and only if its center is inside the closed shape (boundary counts as inside); for a
composite, inside any part and not inside any hole, both with the closed rule. Circle: `d² <= r²`. Square and
triangle: every edge function `>= 0`. Rendering an instance maps each pixel center through the inverse transform and
applies the same test on the canonical primitive, so a hypothesis is rendered analytically at any size, never by
resampling the 64x64 witness mask. Centers that land outside the unit square are background (clipping). No
interpolation, no antialiasing.

A render is `TRANSFORM_OUT_OF_RANGE` when either side of the canonical bounding box (its width and its height in the
canonical frame, before rotation), times `scale` times the target size `N`, is below `2` pixels. The smaller side
counts, so a needle thin triangle is out of range even when its long side is large; the fitter never proposes such a
hypothesis.

The **rendered extent** of an instance is the larger side of its canonical bounding box times `scale` times `N`,
which is `scale · N` because a canonical geometry has larger extent `1`. It does not depend on rotation or clipping.
It decides the `8` px gating floor and the `24` px H-G3 floor (Section 9.2).

### 4.4 Render arithmetic

#142's fixture generator, #146's `InstanceRasterizer`, #144's hypothesis render and #150's `Compositor` all use this
exact sequence in `double`, evaluated left to right as written, with no fused multiply add, so every renderer gives
the same pixels.

- Once per render: `cos = StrictMath.cos(rotation)`, `sin = StrictMath.sin(rotation)`.
- Per pixel `(x, y)`: `qx = (x + 0.5) / N - 0.5 - tx`; `qy = (y + 0.5) / N - 0.5 - ty`; canonical point
  `u = (qx * cos + qy * sin) / scale + 0.5`, `v = (-qx * sin + qy * cos) / scale + 0.5`.
- Circle: inside when `(u - cx) * (u - cx) + (v - cy) * (v - cy) <= r * r`.
- Square, with `c = cos(φ)`, `s = sin(φ)` by `StrictMath` for its own rotation `φ`, `dx = u - cx`, `dy = v - cy`:
  inside when `abs(dx * c + dy * s) <= side / 2` and `abs(-dx * s + dy * c) <= side / 2`.
- Triangle, with vertices in the order of Section 4.8 item 2: inside when
  `(x2 - x1) * (v - y1) - (y2 - y1) * (u - x1) >= 0` for each edge `(x1, y1) -> (x2, y2)` in that order, closing back
  to the first vertex.
- A point with `u` or `v` outside `[0, 1]` is background.

Pixel centers can land exactly on a boundary; this sequence decides them the same way everywhere, and #146 must
reproduce the fixture pixel hashes of #142 bit for bit (checked at V3).

Every geometry render target is square. `InstanceRasterizer.render(i, n)` takes one `n`, and
`Compositor.render(layers, w, h, out)` returns `SIZE_UNSUPPORTED` when `w != h`, because this arithmetic is defined
for one `N`. Rectangular planes exist only on the color and spectral paths, which carry no geometry.

### 4.5 Connectivity and contours

Foreground is 8 connected, background 4 connected. A contour is the pixel edge polygon of each foreground component
(outer ring) and of each enclosed background component (hole ring). An enclosed background component is a 4
connected background component with no pixel on the raster border; outside the raster counts as background, so a
background component that touches the border is open, not a hole. Tracing walks the pixel edges with the foreground
on the right hand side; at a corner shared by two foreground pixels that touch only diagonally, the walk continues
along the edge that keeps the same 8 connected component on its right. Vertices are pixel corners divided by `N`;
collinear corners are dropped. Orientation, start vertex and ring order follow Section 3.3. Re rasterizing a contour
with the center rule reproduces the mask exactly.

### 4.6 Signed distance field

Sampled at pixel centers, Euclidean distance in pixels to the nearest contour segment, negative inside, positive
outside, clamped to `[-4, 4]`. No center lies on a pixel edge, so the sign is never zero and `sign(sdf) < 0`
reproduces the mask exactly. Reference computation: brute force over every contour segment, in ring then segment
order. Outside the raster counts as background, so a foreground pixel on the raster border is at most `0.5` px from a
contour segment that runs along the border edge: a full mask gives `-0.5` on its border pixels, `-1.5` one pixel in,
and so on, clamped at `-4`. Empty mask (no contour): `+4` everywhere.

### 4.7 `Transform2D`

Similarity only: `tx`, `ty` in `[-1, 2]`, `scale > 0`, `rotation` in `(-π, π]`, all finite. Anisotropic scale and
shear are out of scope because they change the shape, not the instance. A gold or fitted rotation is reported
normalized to the shape's symmetry period: circle `0`, square `[0, π/2)`, equilateral triangle `[0, 2π/3)`, any other
shape `(-π, π]`. Pose accuracy (translation error in pixels, scale ratio, rotation error after normalization) is a
required report field, not a gate.

### 4.8 Construction details

These close the gaps a fixture author would otherwise fill by guessing.

1. **Grid snap**: nearest `k / 4096`, ties to even: `Math.rint(v * 4096) / 4096`.
2. **Triangle vertex order**: clockwise on screen (positive shoelace sum, `y` down), starting at the minimum `(y, x)`
   vertex, the same rule as contour rings. Every canonical `Triangle` and every triangle in a gold decomposition is
   written in this order.
3. **Composite ordering keys**: a triangle's `(cx, cy)` is its bounding box center; the kind ordinal is the
   `permits` order (Circle `0`, Square `1`, Triangle `2`, Composite `3`).
4. **Catalogue triangles** (canonical numerators): `T1` equilateral `(2048, 274), (4096, 3822), (0, 3822)`; `T2` right
   isosceles with the hypotenuse at the bottom `(2048, 1024), (4096, 3072), (0, 3072)`; `T3` scalene `3 : 4 : 5` with
   side `3` on the left and the apex at `x = 0.36` before snapping `(1475, 1065), (4096, 3031), (0, 3031)`. "Right angle
   at the bottom left" describes `T2` as it is placed inside composites (legs on the axes), not its canonical form.
5. **Composite placement**: Section 8.5 gives each decomposition in unit square coordinates; its gold geometry is that
   decomposition canonicalized as a whole (centered, larger extent `1`, snapped), and the fixture renders it at the
   base pose (`scale 0.75`), plus the pose delta for the duck. "At `(x, y)`" for a part means its bounding box center.
6. **Composite layouts**: two circles, dumbbell and touching squares are horizontal with a shared `cy = 0.5`, and a
   gap is measured edge to edge. The snowman is vertical, largest circle at the bottom, shared `cx = 0.5`, gaps edge
   to edge. The notch is a square hole flush with the top edge, centered horizontally. The L shape is a square with a
   square hole of side `1 - arm width` in its top right corner. The `T1` hole has the same orientation as its outer
   `T1` and the same bounding box center.
7. **Ellipses** (`m-amb-03`, `04`): major extent `0.75` and `0.45`, ratio `1.2 : 1`, major axis horizontal, centered.
   **Pentagon** (`m-amb-05`): one vertex straight up. **Hexagon** (`m-amb-06`): two vertices on the horizontal axis.
   For both polygons the extent is the larger bounding box extent and the box is centered.
8. **Two overlapping circles** (`m-amb-09`, `10`): absolute coordinates, horizontal, centered on `(0.5, 0.5)`; at
   centre gap `0.5` the circles clip the frame by `0.05`.
9. **Pose deltas** compose with the base pose: scale deltas multiply `0.75` (pose 3 is `0.375`, pose 4 is `0.1875`,
   the circle's pose 5 is `0.675`, pose 7 is `0.375`), translations add to `(tx, ty)`, rotations add.
10. **Angles**: degrees become radians through `Math.toRadians(d)`; the manifest's `Double.toHexString` value is the
    frozen one. Gold rotations are written normalized to the symmetry period, so the circle's pose 7 stores rotation
    `0`.
11. **Rendered extent**: as defined in Section 4.3 (`scale · N`).

### 4.9 Fitting (owned by #144)

- **Input**: a `BinaryMask`; optionally an `Annotation(List<Kind> parts, List<Kind> holes)` for composites, where
  `Kind` is `CIRCLE`, `SQUARE` or `TRIANGLE`. Without an annotation the fitter fits one primitive. With one, it fits a
  `Composite` with exactly those parts and those holes, in the annotation's kinds (the fitter puts the result in
  canonical order; the annotation order carries no meaning). Holes are named because a hole that touches the outer
  edge (the notch, the L shape) leaves no enclosed background to infer it from. Unsupervised decomposition is out of
  scope. The runner builds the annotation of a composite id, of `m-amb-09` and `10`, and of an annotated layer from the
  kinds of the parts and holes of its gold decomposition.
- **`PART_COUNT_MISMATCH`**: with an annotation, the fitter abstains with this reason when the mask has more 8
  connected foreground components than annotated parts (a part is connected, so it cannot span two components), or
  more enclosed 4 connected background components than annotated holes. #144 exercises it in unit tests with tiny
  inline masks; no fixture id targets it.
- **Precedence before fitting**, in this order: no foreground pixel is `EMPTY`; a foreground bounding box with
  either side (width or height) below `2` pixels is `TOO_SMALL`, so a single row or a single column abstains however
  long it is; a foreground fraction of `0.999` or more is `FULL`; with an annotation, the component count check above
  is `PART_COUNT_MISMATCH`.
- **Candidates** come from the frozen catalogue: circle, square, and the catalogue triangles `T1`, `T2`, `T3`
  (Section 4.8 item 4). `ordinal` is the catalogue index (`0` circle, `1` square, `2` to `4` triangles `T1` to `T3`).
  The fitter may also propose a free form triangle (ordinal `5`); it snaps to a catalogue triangle when its score is
  within `τ_margin` of that triangle's score. The pose search and the free form triangle algorithm are #144's choice;
  the contract fixes only inputs, outputs, scores and thresholds. Snapping and de duplication happen after scoring
  and before ranking: a snapped free form triangle is replaced by the catalogue triangle it snapped to (the catalogue
  score and ordinal are kept), and when two candidates share one canonical geometry only the higher scoring one
  stays. The ambiguity check below therefore never compares a free form triangle with its own catalogue twin.
- **Score**: the IoU, at the input mask's size, of the hypothesis rendered analytically from its canonical geometry
  and pose against the input mask. The list holds every candidate with `score >= τ_fit`, at most 8.
- **Decision order after scoring, snapping and de duplication**: first drop every candidate below `τ_fit`; if none
  remain, `NO_PRIMITIVE_ABOVE_MARGIN`; if two or more remain and the top two of those differ by less than `τ_margin`,
  `AMBIGUOUS`; otherwise the ranked list. `τ_margin` is one value used twice: as the snap distance between a free form
  triangle and a catalogue triangle, and as the minimum gap between the top two distinct geometries. `τ_fit` and
  `τ_margin` are selected on the validation split by #144 and recorded in its benchmark document before any test
  split result exists.

## 5. Appearance Conventions

- Fixtures are 8 bit sRGB (PNG). Decode `v / 255.0`.
- sRGB to linear: `c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4)`.
- Linear to sRGB: `c <= 0.04045 / 12.92 ? 12.92 * c : 1.055 * StrictMath.pow(c, 1 / 2.4) - 0.055`. The linear
  breakpoint is the image of the sRGB breakpoint under the linear branch. The two branches do not meet exactly: with
  the IEC 61966-2-1 constants the power branch evaluated at `0.04045` is about `2.33e-9` above `0.04045 / 12.92`.
  That step is inherent to the published constants, not an implementation defect, and it is why the round trip
  tolerance in Section 9.2 differs by direction.
- Encode `clamp(floor(c * 255 + 0.5), 0, 255)`.
- Channels are named planes `R`, `G`, `B`, `A`, never positional. Alpha is straight, stored in `[0, 1]`, linear. A
  `Flat(r, g, b)` fill is sRGB in `[0, 1]`.
- Compositing: convert color to `LINEAR`, premultiply, source over onto a transparent black canvas in draw order
  (`ordinal`, then list order), unpremultiply (zero alpha gives color `0`), convert to the output color space.
- A `Texture` is sampled in canonical shape space with nearest neighbour sampling at the texture's own size: the
  canonical point `(u, v)` from Section 4.4 reads texel `(min(floor(u * W), W - 1), min(floor(v * H), H - 1))` of a
  `W x H` texture; its texel alpha multiplies the appearance alpha.

## 6. Spectral Conventions

### 6.1 DFT2D (owned by #148)

`F[u, v] = Σ_x Σ_y f[x, y] · exp(-2πi (u x / W + v y / H))`, rows then columns, in `double`, trig by
`StrictMath.cos` and `StrictMath.sin` of `2π · m / W` (or `H`) with `m = (u · x) mod W` tracked in exact integer
arithmetic and read from a per call table of `W` (or `H`) residues, summed in ascending `x` then ascending `y`.

- The input is the plane's stored values (decoded sRGB `v / 255` for fixtures); the transform never linearizes.
  `R`, `G`, `B` and `A` planes are each transformed.
- Phase is `atan2(im, re)` wrapped to `(-π, π]` (`-π` becomes `π`, `-0.0` becomes `0.0`) and compared circularly,
  only where the magnitude is at least `1e-12` times the plane's largest magnitude.
- Inverse uses the conjugate kernel, same order, one division by `W · H` per output at the end; the imaginary residue
  is checked against the round trip tolerance and dropped, the largest overshoot outside `[0, 1]` is recorded as a
  diagnostic and the values are then clamped.
- The full complex plane is stored. Cost: `W · H · (W + H)` complex multiply adds, about 0.5 M at 64x64 per plane.
  Rectangular and non power of two sizes are allowed under the cap.
- Parseval: `Σ |f|² = (1 / (W H)) Σ |F|²`.

### 6.2 Haar2D (owned by #149)

Square (Mallat) decomposition. One level: orthonormal pair `a = (x0 + x1) / √2`, `d = (x0 - x1) / √2` with
`√2 = StrictMath.sqrt(2.0)` and `x0` at the even index, applied to rows then to columns of each half, giving
`LL, LH, HL, HH`; recurse on `LL`. `LH` is low pass horizontally and high pass vertically (responds to horizontal
edges), `HL` the reverse, `HH` diagonal.

- `W` and `H` must each be a power of two; otherwise `NOT_POWER_OF_TWO`, never padded or truncated.
- The depth is `levels`, with `2^levels <= min(W, H)` and `levels <= 6`, otherwise `LEVELS_UNSUPPORTED`; the fixtures
  use the full depth `log2(N)` (`5` at 32, `6` at 64).
- Inverse: the same clamp and diagnostic rule as DFT2D. Energy preserved: `Σ c² = Σ x²`.

## 7. Bounds, Rejection and Determinism

**Caps.** Every factory and operation that takes a width, height or `n` returns `SIZE_UNSUPPORTED` for a value
above 64 or below 1 (zero and negative alike; no dimension throws). More than 16 parts, 16 holes, 8 hypotheses or
256 layers is `CAP_EXCEEDED`, returned by the operation that receives the value, never thrown by a record
constructor: `VisualGeometry.canonicalize` and the fitter for a `Composite` over the part or hole cap, the fitter
for its own hypothesis list, and `Compositor.render` for the layer cap. A `Composite` or `List<Layer>` value over a
cap is constructible; it is rejected when used.

**Rejection versus exception.** A malformed value (null, NaN, infinity, a sample outside `[0, 1]`, a parameter off
the grid) throws `IllegalArgumentException` from a record constructor: it is a caller bug. A well formed value
outside a limit (size, power of two, depth, cap, color space mismatch, transform range) comes back as
`Rejected(VisualRejection)` from a factory or an operation: it is an expected outcome. No operation returns a
partial result.

**Determinism.** Same inputs give bit identical outputs: `StrictMath` only, fixed summation order, no `parallel()`
streams, no hash order iteration, ties by explicit ordinals. Every reference path is single threaded.

**Key invariants.**

- A `VisualGeometry` never changes with position, scale, rotation, color, alpha or texture.
- Equality of geometries is exact on grid snapped canonical parameters; no threshold ever defines identity.
- Nothing image shaped enters `Signal`, `FrequencyState`, a stage or the Store; Neuron keeps no geometry registry.
- Every operation returns a complete typed result or a typed rejection; never a partial raster or spectrum.
- Every threshold, fixture id, formula and split is committed before the first algorithm PR; the test split is
  scored once.

## 8. Fixtures, Manifest and Splits (owned by #142)

### 8.1 Location, license and provenance

Location: `monada-neuron-evaluation/src/main/resources/visual-fixtures-v1/`.

- `LICENSE`: CC0 1.0 Universal; the project is the author. No external file is downloaded in #141 or #142.
- `PROVENANCE.md`: generator class, seed scheme, PNG writer, `cwebp` version and flags.
- `manifest-v1.tsv`: Section 8.3.
- PNG files `<id>-32.png` and `<id>-64.png`. Masks are 8 bit grayscale PNG whose decoded values must be exactly `0` or
  `255` (anything else throws); RGB fixtures are 8 bit RGBA PNG.
- `pixel_sha256` is the SHA-256 of the decoded pixel bytes in raster order with channels interleaved. Replay compares
  decoded pixels, so the PNG writer's byte stream is not part of the frozen contract, and `png_bytes` is the
  committed file's size. The generator writes PNGs through `javax.imageio` once; a replay test regenerates pixels and
  compares hashes.
- Core unit tests use tiny inline masks, never these files.

### 8.2 Seeds and random values

`seed` is the first 8 bytes of SHA-256 of the id (UTF-8), big endian, as a `long`. Random values come from
`new SplittableRandom(seed)` through `nextLong()` only, with the derived values written out here, because the
derivations inside the JDK are implementation details (`nextInt(bound)` was reimplemented in JDK 17) while the
`nextLong()` sequence has been unchanged since JDK 8. #142 freezes the pixel hashes, so if that sequence ever
changes the replay check of V1 catches it. Masks draw one `long` per pixel in raster order and take the pixel as
foreground when `(nextLong() >>> 11) * 0x1.0p-53 < p`; RGB noise draws one `long` per channel in `R, G, B(, A)`
order per pixel in raster order and takes the byte `(int) (nextLong() >>> 56)`. Each size starts a fresh generator
from the same seed.
Five ids draw random values:

| Id | `seed` |
|---|---|
| `m-neg-10` | `5137362645076074028` |
| `m-neg-11` | `7281893230392710761` |
| `m-neg-12` | `-4849810620050002153` |
| `c-deg-04` | `-4199152380548194853` |
| `c-deg-05` | `-3897877353076230856` |

### 8.3 Manifest format and grammar

`manifest-v1.tsv` is UTF-8 with LF line ends. Header lines: `# visual-fixtures/1`, `# split-key: visual-fixtures/1`,
`# sha256: <hash of the body>`; then the column name line with the columns of the manifest row (Section 3.3); then one
row per `(id, size)`.

- Rows are sorted by id (byte order), then size (`32` before `64`).
- The body hashed into `# sha256:` is every line after that header line, the column name line included, each ending
  in LF, hashed as UTF-8 and written as lowercase hex.
- Grid values are decimal integer numerators of `1 / 4096`; other doubles are `Double.toHexString`; `NONE` marks an
  absent value.
- A primitive is `circle(cx,cy,r)`, `square(cx,cy,side,rotation)` or `triangle(x1,y1,x2,y2,x3,y3)` in grid numerators
  (a square rotation is a numerator of `k / 4096` radians); a composite is `composite(parts=[p;p;...],holes=[p;...])`
  in canonical order; a transform is `transform(tx,ty,scale,rotation)` in hex doubles.
- A set is `{a,b}` sorted by string, `{}` when empty, with kinds in lower case and abstain reasons as enum names.
- An annotation is `annotation(parts=[KIND,...],holes=[KIND,...])`.
- A `gold_layers` value is layers joined by ` | `, each `layer(geometry=<primitive or composite>,transform=<transform>,
  annotation=<annotation or NONE>,fill=<rgb(r,g,b) in bytes or texture(<fixture id>)>,alpha=<hex double>,
  background=<true or false>)`.

### 8.4 Poses

Every positive and composite pose is the base pose composed with a delta (Section 4.8 item 9). The base pose is the
canonical shape at `scale 0.75` (extent 24 px at 32, 48 px at 64). The circle replaces poses 5 and 6, because a
rotation does not change it. The `gold_transform` column holds these exact values:

| Pose | Delta (non circle) | `gold_transform` (non circle) | Delta (circle) | `gold_transform` (circle) |
|---|---|---|---|---|
| 1 | base | `transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0)` | base | `transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0)` |
| 2 | translate `(+0.2, -0.1)` | `transform(0x1.999999999999ap-3,-0x1.999999999999ap-4,0x1.8p-1,0x0.0p0)` | translate `(+0.2, -0.1)` | `transform(0x1.999999999999ap-3,-0x1.999999999999ap-4,0x1.8p-1,0x0.0p0)` |
| 3 | scale `0.5` | `transform(0x0.0p0,0x0.0p0,0x1.8p-2,0x0.0p0)` | scale `0.5` | `transform(0x0.0p0,0x0.0p0,0x1.8p-2,0x0.0p0)` |
| 4 | scale `0.25` | `transform(0x0.0p0,0x0.0p0,0x1.8p-3,0x0.0p0)` | scale `0.25` | `transform(0x0.0p0,0x0.0p0,0x1.8p-3,0x0.0p0)` |
| 5 | rotate 30 degrees | `transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x1.0c152382d7365p-1)` | scale `0.9` | `transform(0x0.0p0,0x0.0p0,0x1.599999999999ap-1,0x0.0p0)` |
| 6 | rotate 45 degrees | `transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x1.921fb54442d18p-1)` | translate `(-0.3, 0.3)` | `transform(-0x1.3333333333333p-2,0x1.3333333333333p-2,0x1.8p-1,0x0.0p0)` |
| 7 | translate `(0.1, 0.1)`, scale `0.5`, rotate 15 degrees | `transform(0x1.999999999999ap-4,0x1.999999999999ap-4,0x1.8p-2,0x1.0c152382d7365p-2)` | translate `(0.1, 0.1)`, scale `0.5`, rotate 15 degrees (normalizes to `0`) | `transform(0x1.999999999999ap-4,0x1.999999999999ap-4,0x1.8p-2,0x0.0p0)` |
| 8 | translate `(0.4, 0)` | `transform(0x1.999999999999ap-2,0x0.0p0,0x1.8p-1,0x0.0p0)` | translate `(0.4, 0)` | `transform(0x1.999999999999ap-2,0x0.0p0,0x1.8p-1,0x0.0p0)` |

Some poses clip at the raster edge: poses 2 and 8 at the right edge (by `0.075` and `0.275`), circle pose 6 at the
left and bottom edges (by `0.175`), the 45 degree square of pose 6 at its four corners (its half diagonal is
`0.375 · √2`, about `0.03` past each edge), and triangles by their own vertex positions. Clipped renders are gated
like any other, because the fitter renders its hypotheses with the same clipping; #142 reports the clipped
`(id, pose, size)` triples in its benchmark document.

### 8.5 Mask fixtures (72 ids, each at 32 and 64)

Ids are assigned in the order listed. `b` is the split byte of Section 8.10.

**Positive (24).** `m-pos-01` to `08` circle, `09` to `16` square, `17` to `24` triangle (`T1` poses 1 to 4, `T2` poses
5 and 6, `T3` poses 7 and 8). `gold_transform` is the pose of the table above; `gold_accept` is the id's kind and
`gold_abstain` is `{}`. *Gated at* lists the sizes whose rendered extent is at least `8` px; *H-G3 at* lists those of
at least `24` px.

| Id | Shape | Pose | `gold_geometry` | Extent px at 32, 64 | Gated at | H-G3 at | `b` | Split |
|---|---|---|---|---|---|---|---|---|
| `m-pos-01` | circle | 1 | `circle(2048,2048,2048)` | 24, 48 | 32, 64 | 32, 64 | 231 | train |
| `m-pos-02` | circle | 2 | `circle(2048,2048,2048)` | 24, 48 | 32, 64 | 32, 64 | 26 | test |
| `m-pos-03` | circle | 3 | `circle(2048,2048,2048)` | 12, 24 | 32, 64 | 64 | 255 | train |
| `m-pos-04` | circle | 4 | `circle(2048,2048,2048)` | 6, 12 | 64 | none | 215 | train |
| `m-pos-05` | circle | 5 | `circle(2048,2048,2048)` | 21.6, 43.2 | 32, 64 | 64 | 1 | test |
| `m-pos-06` | circle | 6 | `circle(2048,2048,2048)` | 24, 48 | 32, 64 | 32, 64 | 106 | validation |
| `m-pos-07` | circle | 7 | `circle(2048,2048,2048)` | 12, 24 | 32, 64 | 64 | 176 | train |
| `m-pos-08` | circle | 8 | `circle(2048,2048,2048)` | 24, 48 | 32, 64 | 32, 64 | 249 | train |
| `m-pos-09` | square | 1 | `square(2048,2048,4096,0)` | 24, 48 | 32, 64 | 32, 64 | 158 | train |
| `m-pos-10` | square | 2 | `square(2048,2048,4096,0)` | 24, 48 | 32, 64 | 32, 64 | 230 | train |
| `m-pos-11` | square | 3 | `square(2048,2048,4096,0)` | 12, 24 | 32, 64 | 64 | 208 | train |
| `m-pos-12` | square | 4 | `square(2048,2048,4096,0)` | 6, 12 | 64 | none | 129 | train |
| `m-pos-13` | square | 5 | `square(2048,2048,4096,0)` | 24, 48 | 32, 64 | 32, 64 | 87 | validation |
| `m-pos-14` | square | 6 | `square(2048,2048,4096,0)` | 24, 48 | 32, 64 | 32, 64 | 196 | train |
| `m-pos-15` | square | 7 | `square(2048,2048,4096,0)` | 12, 24 | 32, 64 | 64 | 130 | train |
| `m-pos-16` | square | 8 | `square(2048,2048,4096,0)` | 24, 48 | 32, 64 | 32, 64 | 24 | test |
| `m-pos-17` | T1 | 1 | `triangle(2048,274,4096,3822,0,3822)` | 24, 48 | 32, 64 | 32, 64 | 58 | test |
| `m-pos-18` | T1 | 2 | `triangle(2048,274,4096,3822,0,3822)` | 24, 48 | 32, 64 | 32, 64 | 13 | test |
| `m-pos-19` | T1 | 3 | `triangle(2048,274,4096,3822,0,3822)` | 12, 24 | 32, 64 | 64 | 250 | train |
| `m-pos-20` | T1 | 4 | `triangle(2048,274,4096,3822,0,3822)` | 6, 12 | 64 | none | 82 | validation |
| `m-pos-21` | T2 | 5 | `triangle(2048,1024,4096,3072,0,3072)` | 24, 48 | 32, 64 | 32, 64 | 124 | train |
| `m-pos-22` | T2 | 6 | `triangle(2048,1024,4096,3072,0,3072)` | 24, 48 | 32, 64 | 32, 64 | 142 | train |
| `m-pos-23` | T3 | 7 | `triangle(1475,1065,4096,3031,0,3031)` | 12, 24 | 32, 64 | 64 | 67 | validation |
| `m-pos-24` | T3 | 8 | `triangle(1475,1065,4096,3031,0,3031)` | 24, 48 | 32, 64 | 32, 64 | 68 | validation |

**Composite (24).** Decompositions in unit square coordinates before canonicalization (Section 4.8 items 5 and 6):

- duck: body circle `r 0.3` at `(0.45, 0.6)`, head circle `r 0.18` at `(0.7, 0.35)`, beak `T2` in its catalogue
  placement (right angle at the bottom left, legs on the axes) with extent `0.15` at `(0.9, 0.4)`; poses 1, 2, 3, 5;
- ring: circle `r 0.5` with a concentric circle hole of radius `0.15`, `0.3`, `0.45`;
- square frame: square side `1` with a concentric square hole of side `0.5`, `0.9`;
- two disconnected circles `r 0.2`, gaps `0.05`, `0.2`, `0.4`;
- triangle `T1` (extent `1`) with a `T1` hole of extent `0.3`, `0.6`;
- snowman: circles `r 0.3`, `0.2`, `0.12` stacked bottom to top, gaps `0`, `0.05`;
- square side `1` with an edge notch of side `0.1`, `0.3`;
- L shape: square side `1` with a square hole of side `1 - w` in its top right corner, arm widths `w = 0.3`, `0.5`;
- dumbbell: two circles `r 0.2` joined by a centered square whose side equals the gap, gaps `0.2`, `0.4`;
- two touching squares, sides `0.4` and `0.4`; `0.5` and `0.3`.

Every composite is at pose 1 except the duck poses. `gold_accept` is `{composite}` and `gold_abstain` is `{}`. Every
composite render has a rendered extent of at least `12` px, so every composite is gated at both sizes.

| Id | Content | Pose | `gold_geometry` | `b` | Split |
|---|---|---|---|---|---|
| `m-cmp-01` | duck | 1 | `composite(parts=[circle(2731,1130,894);triangle(3351,1005,4096,1750,3351,1750);circle(1489,2371,1489)],holes=[])` | 104 | train |
| `m-cmp-02` | duck | 2 | `composite(parts=[circle(2731,1130,894);triangle(3351,1005,4096,1750,3351,1750);circle(1489,2371,1489)],holes=[])` | 73 | validation |
| `m-cmp-03` | duck | 3 | `composite(parts=[circle(2731,1130,894);triangle(3351,1005,4096,1750,3351,1750);circle(1489,2371,1489)],holes=[])` | 196 | train |
| `m-cmp-04` | duck | 5 | `composite(parts=[circle(2731,1130,894);triangle(3351,1005,4096,1750,3351,1750);circle(1489,2371,1489)],holes=[])` | 230 | train |
| `m-cmp-05` | ring, hole r 0.15 | 1 | `composite(parts=[circle(2048,2048,2048)],holes=[circle(2048,2048,614)])` | 214 | train |
| `m-cmp-06` | ring, hole r 0.3 | 1 | `composite(parts=[circle(2048,2048,2048)],holes=[circle(2048,2048,1229)])` | 14 | test |
| `m-cmp-07` | ring, hole r 0.45 | 1 | `composite(parts=[circle(2048,2048,2048)],holes=[circle(2048,2048,1843)])` | 238 | train |
| `m-cmp-08` | square frame, hole side 0.5 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(2048,2048,2048,0)])` | 192 | train |
| `m-cmp-09` | square frame, hole side 0.9 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(2048,2048,3686,0)])` | 234 | train |
| `m-cmp-10` | two circles r 0.2, gap 0.05 | 1 | `composite(parts=[circle(964,2048,964);circle(3132,2048,964)],holes=[])` | 153 | train |
| `m-cmp-11` | two circles r 0.2, gap 0.2 | 1 | `composite(parts=[circle(819,2048,819);circle(3277,2048,819)],holes=[])` | 21 | test |
| `m-cmp-12` | two circles r 0.2, gap 0.4 | 1 | `composite(parts=[circle(683,2048,683);circle(3413,2048,683)],holes=[])` | 66 | validation |
| `m-cmp-13` | T1 with T1 hole, hole extent 0.3 | 1 | `composite(parts=[triangle(2048,274,4096,3822,0,3822)],holes=[triangle(2048,1516,2662,2580,1434,2580)])` | 143 | train |
| `m-cmp-14` | T1 with T1 hole, hole extent 0.6 | 1 | `composite(parts=[triangle(2048,274,4096,3822,0,3822)],holes=[triangle(2048,984,3277,3112,819,3112)])` | 17 | test |
| `m-cmp-15` | snowman, gap 0 | 1 | `composite(parts=[circle(2048,396,396);circle(2048,1453,661);circle(2048,3105,991)],holes=[])` | 17 | test |
| `m-cmp-16` | snowman, gap 0.05 | 1 | `composite(parts=[circle(2048,367,367);circle(2048,1498,611);circle(2048,3179,917)],holes=[])` | 135 | train |
| `m-cmp-17` | square with edge notch, notch side 0.1 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(2048,205,410,0)])` | 225 | train |
| `m-cmp-18` | square with edge notch, notch side 0.3 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(2048,614,1229,0)])` | 10 | test |
| `m-cmp-19` | L shape, arm width 0.3 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(2662,1434,2867,0)])` | 168 | train |
| `m-cmp-20` | L shape, arm width 0.5 | 1 | `composite(parts=[square(2048,2048,4096,0)],holes=[square(3072,1024,2048,0)])` | 216 | train |
| `m-cmp-21` | dumbbell, gap 0.2 | 1 | `composite(parts=[circle(819,2048,819);square(2048,2048,819,0);circle(3277,2048,819)],holes=[])` | 90 | validation |
| `m-cmp-22` | dumbbell, gap 0.4 | 1 | `composite(parts=[circle(683,2048,683);square(2048,2048,1365,0);circle(3413,2048,683)],holes=[])` | 104 | train |
| `m-cmp-23` | touching squares, sides 0.4 and 0.4 | 1 | `composite(parts=[square(1024,2048,2048,0);square(3072,2048,2048,0)],holes=[])` | 31 | validation |
| `m-cmp-24` | touching squares, sides 0.5 and 0.3 | 1 | `composite(parts=[square(1280,2048,2560,0);square(3328,2048,1536,0)],holes=[])` | 85 | validation |

**Negative and degenerate (14).** `gold_geometry`, `gold_transform` and `gold_layers` are `NONE`; `gold_accept` is `{}`.

| Id | Pixels lit (`x`, `y` from `0`) | `gold_abstain` | `b` | Split |
|---|---|---|---|---|
| `m-neg-01` | no pixel | `{EMPTY}` | 185 | train |
| `m-neg-02` | every pixel | `{FULL}` | 95 | train |
| `m-neg-03` | every pixel except `(N / 2, N / 2)` | `{FULL}` | 127 | train |
| `m-neg-04` | `x` in `{0, N - 1}` or `y` in `{0, N - 1}` (one pixel border frame) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 66 | test |
| `m-neg-05` | only `(N / 2, N / 2)` | `{TOO_SMALL}` | 12 | test |
| `m-neg-06` | only `(0, 0)` | `{TOO_SMALL}` | 152 | train |
| `m-neg-07` | row `y = N / 2` | `{TOO_SMALL}` | 5 | test |
| `m-neg-08` | `x = y` (1 px diagonal) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 194 | train |
| `m-neg-09` | `(x + y) mod 2 = 0`, so `(0, 0)` is lit (1 px checkerboard) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 195 | train |
| `m-neg-10` | draw `< 0.5` per pixel (the `nextLong()` draw of Section 8.2) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 239 | train |
| `m-neg-11` | draw `< 0.5` per pixel (Section 8.2) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 82 | validation |
| `m-neg-12` | draw `< 0.05` per pixel (salt, Section 8.2) | `{NO_PRIMITIVE_ABOVE_MARGIN}` | 73 | validation |
| `m-neg-13` | column `x = N / 2` | `{TOO_SMALL}` | 66 | validation |
| `m-neg-14` | sliver: `triangle(2048,1984,3584,2112,512,2112)` at 32 and `triangle(2048,2016,3584,2080,512,2080)` at 64, rendered at the identity transform; it lights row `N / 2` for `x` from `N / 8` to `7N / 8 - 1` | `{TOO_SMALL}` | 84 | train |

`m-neg-08` has an `N x N` bounding box, so precedence passes it to fitting, and the 2 px rule of Section 4.3 rules out
a needle triangle. The sliver `m-neg-14` has its base on the center line of row `N / 2` and its apex `1` px above it
at `x = 0.5`, so exactly row `N / 2` is lit.

**Ambiguous (10).** Drawn by the closed center rule on the predicate at pixel centers `(u, v) = ((x + 0.5) / N,
(y + 0.5) / N)`, with `d² = (u - 0.5)² + (v - 0.5)²`, evaluated in `double` with `StrictMath` for trigonometry. These
masks are generated by the generator, not rendered from a `ShapePrimitive`. The rounded squares intersect a circle
`r 0.41` (`m-amb-01`) or `r 0.45` (`m-amb-02`) with a centered square of side `0.75`; `r` must lie
strictly inside `(0.375, 0.53)`: at `0.375` the circle is inscribed and the id would equal `m-pos-01`. The pentagon has
circumradius `R = 0.375 / sin(2π / 5)` and center `(0.5, 0.5 + R (1 - cos(π / 5)) / 2)`, vertices at angles
`-π / 2 + 2πk / 5`; the hexagon has circumradius `0.375`, center `(0.5, 0.5)`, vertices at angles `2πk / 6`.

| Id | Pixel centers `(u, v)` lit | `gold_geometry` | `gold_transform` | `gold_accept` | `gold_abstain` | `b` | Split |
|---|---|---|---|---|---|---|---|
| `m-amb-01` | rounded square: `d² <= 0.41²` and `|u - 0.5| <= 0.375` and `|v - 0.5| <= 0.375` | `NONE` | `NONE` | `{circle,square}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 137 | train |
| `m-amb-02` | rounded square: `d² <= 0.45²` and `|u - 0.5| <= 0.375` and `|v - 0.5| <= 0.375` | `NONE` | `NONE` | `{circle,square}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 254 | train |
| `m-amb-03` | ellipse: `((u - 0.5) / 0.375)² + ((v - 0.5) / 0.3125)² <= 1` | `NONE` | `NONE` | `{circle}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 224 | train |
| `m-amb-04` | ellipse: `((u - 0.5) / 0.225)² + ((v - 0.5) / 0.1875)² <= 1` | `NONE` | `NONE` | `{circle}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 5 | test |
| `m-amb-05` | regular pentagon, one vertex straight up, larger extent `0.75`, bounding box centered | `NONE` | `NONE` | `{circle}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 76 | validation |
| `m-amb-06` | regular hexagon, two vertices on the horizontal axis, larger extent `0.75`, centered | `NONE` | `NONE` | `{circle}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 68 | validation |
| `m-amb-07` | `d² <= 0.375²` and not `d² <= (1 / N)²` (circle with a center hole) | `circle(2048,2048,2048)` | `transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0)` | `{circle}` | `{}` | 206 | train |
| `m-amb-08` | `d² <= 0.5²` (circle touching the frame edges) | `circle(2048,2048,2048)` | `transform(0x0.0p0,0x0.0p0,0x1.0p0,0x0.0p0)` | `{circle}` | `{}` | 63 | test |
| `m-amb-09` | `(u - 0.35)² + (v - 0.5)² <= 0.3²` or `(u - 0.65)² + (v - 0.5)² <= 0.3²` | `composite(parts=[circle(1365,2048,1365);circle(2731,2048,1365)],holes=[])` | `transform(0x0.0p0,0x0.0p0,0x1.ccccccccccccdp-1,0x0.0p0)` | `{}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 159 | train |
| `m-amb-10` | `(u - 0.25)² + (v - 0.5)² <= 0.3²` or `(u - 0.75)² + (v - 0.5)² <= 0.3²` (clips the frame by `0.05`) | `composite(parts=[circle(1117,2048,1117);circle(2979,2048,1117)],holes=[])` | `transform(0x0.0p0,0x0.0p0,0x1.199999999999ap0,0x0.0p0)` | `{}` | `{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}` | 194 | train |

`m-amb-09` and `10` are scored twice: without an annotation by `gold_abstain`; with the annotation
`annotation(parts=[CIRCLE,CIRCLE],holes=[])` by the composite IoU row of Section 9.2 (`>= 0.95`). Their
`gold_transform` is the pose that `canonicalize` returns for the absolute decomposition.

### 8.6 Gold columns and pass rules

- `gold_geometry`: the canonical primitive, or the composite decomposition, or `NONE`.
- `gold_transform`: the base pose composed with the delta, or `NONE`.
- `gold_accept`: the set of acceptable top hypothesis kinds (`circle`, `square`, `triangle`, `composite`), empty when
  a hypothesis is wrong.
- `gold_abstain`: the set of acceptable abstain reasons, empty when abstaining is wrong.

A positive or composite id passes H-G1 when the top hypothesis meets the geometry thresholds; a negative id passes
when the result is `Abstain` with a reason in `gold_abstain`; an ambiguous id passes when the top hypothesis kind is
in `gold_accept` or the abstain reason is in `gold_abstain`. A wrong abstain reason is a failure. A positive id has
`gold_accept = {its kind}` and a composite id `{composite}`, both with `gold_abstain = {}`; a negative id has
`gold_accept = {}`.

### 8.7 RGB fixtures (48 ids, each at 32 and 64)

`N` is the size, `x` and `y` are pixel indices from `0`, and every value is sRGB in `[0, 1]` before encoding (Section
5). Alpha is `1` unless stated; the same formula fills `R`, `G` and `B` unless stated. Tones are
`0.5 + 0.5 · cos(2π (u x / N + v y / N) + φ)` with the listed `(u, v, φ)` unless stated. A checkerboard with cell `c`
is `(floor(x / c) + floor(y / c)) mod 2`, so pixel `(0, 0)` is `0`. Shape render ids are composited from their layers
(Section 8.8).

| Id | Group | Formula | `b` | Split |
|---|---|---|---|---|
| `c-flat-01` | flat | `0` (black, opaque) | 201 | validation |
| `c-flat-02` | flat | `1` (white) | 235 | train |
| `c-flat-03` | flat | `128 / 255` | 122 | test |
| `c-flat-04` | flat | `R = 1`, `G = B = 0` | 222 | train |
| `c-grad-01` | gradient | `x / (N - 1)` | 50 | test |
| `c-grad-02` | gradient | `y / (N - 1)` | 139 | train |
| `c-grad-03` | gradient | `(x + y) / (2N - 2)` | 16 | test |
| `c-grad-04` | gradient | `max(0, 1 - dist((x + 0.5, y + 0.5), (N / 2, N / 2)) / (N / 2))` | 87 | validation |
| `c-grad-05` | gradient | `R = x / (N - 1)`, `G = y / (N - 1)`, `B = (x + y) / (2N - 2)` | 106 | train |
| `c-grad-06` | gradient | flat red with `A = x / (N - 1)` | 56 | validation |
| `c-tone-01` | tone | `(u, v, φ) = (1, 0, 0)` | 18 | test |
| `c-tone-02` | tone | `(0, 1, 0)` | 188 | train |
| `c-tone-03` | tone | `(3, 0, 0)` | 163 | validation |
| `c-tone-04` | tone | `(1, 1, 0)` | 163 | validation |
| `c-tone-05` | tone | `(1, 0, π / 2)` | 175 | train |
| `c-tone-06` | tone | `(N / 2, 0, 0)` (Nyquist: `1` at even `x`, `0` at odd `x`) | 184 | train |
| `c-tone-07` | tone | amplitude `0.25` at `(1, 0, 0)`: `0.5 + 0.25 · cos(2π x / N)` | 216 | train |
| `c-tone-08` | tone | `0.5 + 0.3 · cos(2π x / N) + 0.2 · cos(2π (3x + 2y) / N)` | 65 | test |
| `c-imp-01` | impulse | `1` at `(N / 2, N / 2)`, else `0` | 228 | train |
| `c-imp-02` | impulse | `1` at `(0, 0)`, else `0` | 186 | validation |
| `c-imp-03` | impulse | `1` at `(N / 4, N / 4)` and `(3N / 4, 3N / 4)`, else `0` | 47 | test |
| `c-chk-01` | checkerboard | `c = 1` | 201 | train |
| `c-chk-02` | checkerboard | `c = 2` | 15 | test |
| `c-chk-03` | checkerboard | `c = 4` | 197 | train |
| `c-chk-04` | checkerboard | `c = 8` | 171 | validation |
| `c-step-01` | step | `x < N / 2 ? 0 : 1` (dyadic boundary) | 51 | validation |
| `c-step-02` | step | `x < floor(0.3125 N) ? 0 : 1` (boundary `10` at 32, `20` at 64) | 201 | train |
| `c-step-03` | step | `y < N / 4 ? 0 : 1` | 218 | train |
| `c-step-04` | step | `x + y < N ? 0 : 1` | 47 | test |
| `c-shp-01` | shape render | duck, three layers | 138 | validation |
| `c-shp-02` | shape render | red circle | 219 | train |
| `c-shp-03` | shape render | square with the `c-grad-01` texture | 28 | test |
| `c-shp-04` | shape render | blue triangle | 144 | validation |
| `c-shp-05` | shape render | two overlapping half alpha circles | 183 | train |
| `c-shp-06` | shape render | red circle on a grey background | 196 | train |
| `c-shp-07` | shape render | square with the `c-chk-03` texture | 233 | train |
| `c-shp-08` | shape render | red ring on a blue background | 28 | test |
| `c-chan-01` | channel probe | `R = 10 / 255`, `G = B = 0` (below the sRGB breakpoint, linear branch) | 185 | validation |
| `c-chan-02` | channel probe | `G = 1` only | 241 | train |
| `c-chan-03` | channel probe | `B = 1` only | 215 | train |
| `c-chan-04` | channel probe | flat red with `A = 0` | 13 | test |
| `c-chan-05` | channel probe | flat red with `A = 128 / 255` | 37 | test |
| `c-chan-06` | channel probe | `128 / 255` on `R`, `G`, `B` and `A` (color linear about `0.216`) | 153 | validation |
| `c-deg-01` | degenerate | `0` everywhere with `A = 0` | 149 | train |
| `c-deg-02` | degenerate | `1` on `R`, `G`, `B` with `A = 0` (transparent white) | 213 | train |
| `c-deg-03` | degenerate | `1` on `R`, `G`, `B` with `A = 1 / 255` (smallest non zero alpha) | 105 | test |
| `c-deg-04` | degenerate | seeded RGB noise, `A = 1` | 105 | validation |
| `c-deg-05` | degenerate | seeded RGBA noise | 144 | train |

### 8.8 Shape render layers

A shape render id is a list of layers, drawn in list order. A layer is one `ShapeInstance` plus one `Appearance`,
exactly the `Compositor`'s `Layer`. The column `gold_layers` lists, per layer: its gold canonical geometry (grid
numerators), its gold transform, its annotation (`NONE` for a single primitive), its fill (flat sRGB bytes, or the
texture fixture id), its alpha, and whether it is a background layer.

- A **background layer** is the canonical `Square(2048, 2048, 4096, 0)` at the identity transform (it covers every
  pixel center) with a flat fill; it is stored, never fitted. Every other layer is a **fitted layer**.
- A layer's **coverage mask** is the analytic render of its gold instance at the fixture size, unoccluded by the
  layers drawn after it. The runner hands each fitted layer's coverage mask to the fitter as host supplied
  segmentation, the same kind of supervision as the part annotation: this epic tests whether geometry plus
  appearance recomposes an image, not whether pixels can be segmented.
- A layer may reuse a mask id's geometry and transform only when that mask id is in the train split, so no validation
  or test mask reappears in another split through a shape render id. Every reused id below (`m-pos-01`, `m-pos-09`,
  `m-pos-21`, `m-cmp-01`, `m-cmp-05`) is in train. The leakage test also scans `gold_layers` for such references.
- A texture is the decoded RGBA image of the named fixture id at the same fixture size.

| Id | Layers, in draw order (sRGB bytes; alpha `1` unless stated) |
|---|---|
| `c-shp-01` | the three parts of the `m-cmp-01` decomposition at pose 1, one layer each: body circle `(0, 160, 0)`, head circle `(0, 160, 0)`, beak `T2` `(255, 200, 0)` |
| `c-shp-02` | circle `m-pos-01` (geometry and transform), `(255, 0, 0)` |
| `c-shp-03` | square `m-pos-09`, texture `c-grad-01` |
| `c-shp-04` | triangle `m-pos-21` (`T2` turned 30 degrees, a train id), `(0, 0, 255)` |
| `c-shp-05` | canonical circle at `Transform2D(tx -0.2, ty 0, scale 0.5, rotation 0)`, so radius `0.25` centered at `(0.3, 0.5)`, `(255, 0, 0)`, alpha `0.5`; the same geometry at `tx 0.2`, centered at `(0.7, 0.5)`, `(0, 0, 255)`, alpha `0.5`; the two overlap by `0.1` |
| `c-shp-06` | background `(200, 200, 200)`; circle `m-pos-01`, `(255, 0, 0)` |
| `c-shp-07` | square `m-pos-09`, texture `c-chk-03` (4 px cells) |
| `c-shp-08` | background `(0, 0, 200)`; ring `m-cmp-05` at pose 1, `(255, 0, 0)`, annotation `([CIRCLE], [CIRCLE])` |

The duck layers of `c-shp-01` are the parts of the canonical `m-cmp-01` decomposition, each canonicalized on its own
and composed with pose 1, evaluated left to right in `double`:

- a circle part `circle(cx, cy, r)` (numerators) becomes the canonical circle at
  `transform(0.75 * (cx / 4096 - 0.5), 0.75 * (cy / 4096 - 0.5), 0.75 * (2 * r / 4096), 0)`;
- the beak `triangle(3351,1005,4096,1750,3351,1750)` has legs of length `ℓ = 745 / 4096` and its right angle at
  `(bx, by) = (3351 / 4096, 1750 / 4096)`; it becomes the canonical `T2` at
  `transform(0.75 * (bx + ℓ / 4 - 0.5), 0.75 * (by - ℓ / 4 - 0.5), 0.75 * ℓ * StrictMath.sqrt(2.0), Math.toRadians(-135))`.

Each duck layer's coverage mask equals the pixels of its part in the `m-cmp-01` render at both sizes. The frozen
`gold_layers` values:

```text
c-shp-01
  layer(geometry=circle(2048,2048,2048),transform=transform(-0x1.a34p-4,0x1.e48p-5,0x1.173p-1,0x0.0p0),annotation=NONE,fill=rgb(0,160,0),alpha=0x1.0p0,background=false)
  layer(geometry=circle(2048,2048,2048),transform=transform(0x1.002p-3,-0x1.584p-3,0x1.4f4p-2,0x0.0p0),annotation=NONE,fill=rgb(0,160,0),alpha=0x1.0p0,background=false)
  layer(geometry=triangle(2048,1024,4096,3072,0,3072),transform=transform(0x1.173cp-2,-0x1.6b3p-4,0x1.8b188dd1b19e3p-3,-0x1.2d97c7f3321d2p1),annotation=NONE,fill=rgb(255,200,0),alpha=0x1.0p0,background=false)
c-shp-02
  layer(geometry=circle(2048,2048,2048),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0),annotation=NONE,fill=rgb(255,0,0),alpha=0x1.0p0,background=false)
c-shp-03
  layer(geometry=square(2048,2048,4096,0),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0),annotation=NONE,fill=texture(c-grad-01),alpha=0x1.0p0,background=false)
c-shp-04
  layer(geometry=triangle(2048,1024,4096,3072,0,3072),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x1.0c152382d7365p-1),annotation=NONE,fill=rgb(0,0,255),alpha=0x1.0p0,background=false)
c-shp-05
  layer(geometry=circle(2048,2048,2048),transform=transform(-0x1.999999999999ap-3,0x0.0p0,0x1.0p-1,0x0.0p0),annotation=NONE,fill=rgb(255,0,0),alpha=0x1.0p-1,background=false)
  layer(geometry=circle(2048,2048,2048),transform=transform(0x1.999999999999ap-3,0x0.0p0,0x1.0p-1,0x0.0p0),annotation=NONE,fill=rgb(0,0,255),alpha=0x1.0p-1,background=false)
c-shp-06
  layer(geometry=square(2048,2048,4096,0),transform=transform(0x0.0p0,0x0.0p0,0x1.0p0,0x0.0p0),annotation=NONE,fill=rgb(200,200,200),alpha=0x1.0p0,background=true)
  layer(geometry=circle(2048,2048,2048),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0),annotation=NONE,fill=rgb(255,0,0),alpha=0x1.0p0,background=false)
c-shp-07
  layer(geometry=square(2048,2048,4096,0),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0),annotation=NONE,fill=texture(c-chk-03),alpha=0x1.0p0,background=false)
c-shp-08
  layer(geometry=square(2048,2048,4096,0),transform=transform(0x0.0p0,0x0.0p0,0x1.0p0,0x0.0p0),annotation=NONE,fill=rgb(0,0,200),alpha=0x1.0p0,background=true)
  layer(geometry=composite(parts=[circle(2048,2048,2048)],holes=[circle(2048,2048,614)]),transform=transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0),annotation=annotation(parts=[CIRCLE],holes=[CIRCLE]),fill=rgb(255,0,0),alpha=0x1.0p0,background=false)
```

Rendered extents of the fitted layers: the duck body `17.4` and `34.9` px, the head `10.5` and `21.0` px, the beak
`6.2` px at 32 (reported, not gated) and `12.3` px at 64 (gated); every other fitted layer is at least `16` px.

### 8.9 RGB gold

Every RGB id is checked by the lossless rows of Section 9.2 (round trips, Parseval, energy). In addition:

**DFT2D exact bins** (flat, impulse, Nyquist tone, checkerboard and channel probe ids; `W = H = N`; every bin not
listed is zero within the tolerance; DC phase is `0`). Every plane with `A = 1` everywhere has the single `A` bin
`F[0, 0] = N²`.

| Ids | Plane | Non zero bins |
|---|---|---|
| `c-flat-01` | `R`, `G`, `B` | none |
| `c-flat-02` | `R`, `G`, `B` | `F[0, 0] = N²` |
| `c-flat-03` | `R`, `G`, `B` | `F[0, 0] = N² · 128 / 255` |
| `c-flat-04` | `R`; `G`, `B` | `F[0, 0] = N²`; none |
| `c-imp-01` | `R`, `G`, `B` | every bin, magnitude `1`, phase `0` when `u + v` is even and `π` when odd |
| `c-imp-02` | `R`, `G`, `B` | every bin, magnitude `1`, phase `0` |
| `c-imp-03` | `R`, `G`, `B` | bins with `u + v` even, magnitude `2`, phase `0` when `u + v ≡ 0 (mod 4)` and `π` when `u + v ≡ 2 (mod 4)` |
| `c-tone-06` | `R`, `G`, `B` | `F[0, 0] = N² / 2`; `F[N / 2, 0] = N² / 2`, phase `0` |
| `c-chk-01` to `04` (cell `c`) | `R`, `G`, `B` | `F[0, 0] = N² / 2`; at `(u, v) = (m N / 2c, n N / 2c)` for odd `m`, `n` in `[1, 2c - 1]`, magnitude `2 (N / 2c)² / (sin(π m / 2c) · sin(π n / 2c))` and phase `π (m + n) / 2c` wrapped to `(-π, π]`; `c² + 1` non zero bins |
| `c-chan-01` | `R`; `G`, `B` | `F[0, 0] = N² · 10 / 255`; none |
| `c-chan-02` | `G`; `R`, `B` | `F[0, 0] = N²`; none |
| `c-chan-03` | `B`; `R`, `G` | `F[0, 0] = N²`; none |
| `c-chan-04` | `R`; `G`, `B`; `A` | `F[0, 0] = N²`; none; none |
| `c-chan-05` | `R`; `G`, `B`; `A` | `F[0, 0] = N²`; none; `F[0, 0] = N² · 128 / 255` |
| `c-chan-06` | `R`, `G`, `B`, `A` | `F[0, 0] = N² · 128 / 255` |

**DFT2D reference check** (tone and step ids): the plane equals the reference DFT of the decoded bytes within the
exact bin tolerance; for tones the dominant non DC bin pair equals gold: `c-tone-01`, `05`, `07`, `08`: `(1, 0)` and
`(N - 1, 0)`; `c-tone-02`: `(0, 1)` and `(0, N - 1)`; `c-tone-03`: `(3, 0)` and `(N - 3, 0)`; `c-tone-04`: `(1, 1)` and
`(N - 1, N - 1)`; `c-tone-06` is covered by the exact bins. Step ids have no short closed form and join this check.

**Haar2D gold** (full depth `log2 N`; level `1` is the finest):

| Ids | Gold |
|---|---|
| `c-imp-01`, `c-imp-02` | at each level `j`, the three detail coefficients `LH`, `HL`, `HH` covering the pixel have magnitude `2^-j`, and the final `LL` coefficient has magnitude `2^-levels`, so the energy sums to `1`; every other coefficient is zero |
| `c-imp-03` | the sum of the two single impulse pyramids, by linearity; the two impulses share a block only at the coarsest level |
| `c-step-01` | zero detail at every level below `log2 N` |
| `c-step-02` | zero detail at levels `1` and `2` at 64 and at level `1` at 32 (the boundary `20` or `10` is even, so it never splits a level 1 pair); the first non zero `HL` is at level `3` at 64 and level `2` at 32 |
| `c-step-03` | zero detail at levels `1` to `log2 N - 2`; the first non zero `LH` is at level `log2 N - 1` |
| `c-step-04` | no subband gold; lossless checks only |
| `c-chk-01` to `04` (cell `c`) | detail energy only at level `log2 c + 1`; the final `LL` coefficient carries the mean |

**Composition recipe** (shape render ids): composite the layers of `gold_layers` with the `Compositor` (Section 9.2,
composition row).

### 8.10 Splits

Stratified per group: within each group, ids are sorted by `b = first byte of SHA-256("visual-fixtures/1:" + id)`
(UTF-8; ties by id); the first `ceil(0.2 n)` go to test, the next `ceil(0.2 n)` to validation, the rest to train. Both
sizes of an id share its split. The per id `b` and split are in the tables above; the summary:

| Group | Ids | Test | Validation | Train |
|---|---|---|---|---|
| positive | 24 | 5: `m-pos-02`, `m-pos-05`, `m-pos-16`, `m-pos-17`, `m-pos-18` | 5: `m-pos-06`, `m-pos-13`, `m-pos-20`, `m-pos-23`, `m-pos-24` | 14 |
| composite | 24 | 5: `m-cmp-06`, `m-cmp-11`, `m-cmp-14`, `m-cmp-15`, `m-cmp-18` | 5: `m-cmp-02`, `m-cmp-12`, `m-cmp-21`, `m-cmp-23`, `m-cmp-24` | 14 |
| negative | 14 | 3: `m-neg-04`, `m-neg-05`, `m-neg-07` | 3: `m-neg-11`, `m-neg-12`, `m-neg-13` | 8 |
| ambiguous | 10 | 2: `m-amb-04`, `m-amb-08` | 2: `m-amb-05`, `m-amb-06` | 6 |
| flat | 4 | 1: `c-flat-03` | 1: `c-flat-01` | 2 |
| gradient | 6 | 2: `c-grad-01`, `c-grad-03` | 2: `c-grad-04`, `c-grad-06` | 2 |
| tone | 8 | 2: `c-tone-01`, `c-tone-08` | 2: `c-tone-03`, `c-tone-04` | 4 |
| impulse | 3 | 1: `c-imp-03` | 1: `c-imp-02` | 1 |
| checkerboard | 4 | 1: `c-chk-02` | 1: `c-chk-04` | 2 |
| step | 4 | 1: `c-step-04` | 1: `c-step-01` | 2 |
| shape render | 8 | 2: `c-shp-03`, `c-shp-08` | 2: `c-shp-01`, `c-shp-04` | 4 |
| channel probe | 6 | 2: `c-chan-04`, `c-chan-05` | 2: `c-chan-01`, `c-chan-06` | 2 |
| degenerate | 5 | 1: `c-deg-03` | 1: `c-deg-04` | 3 |
| all | 120 | 28 | 28 | 64 |

- Selection (#144's `τ_fit` and `τ_margin`, #151's arms) uses validation only. Gates V2 to V5 are scored on
  validation; the test split is scored once, by #151 at V6, and is single use.
- "Reading" the test split means scoring it with selected parameters. The byte replay check of V1 and the lossless
  checks (round trips, exact bins, contour and SDF inversion) run on every split, because nothing is selected.
- A leakage test scans the test sources for test split ids used outside those exempt checks and fails on a hit.
- The train split is never scored for a gate or the verdict; it exists for development and diagnostics. Both
  `m-amb-09` and `m-amb-10` fall in train, so their annotated composite fit is development evidence only.

### 8.11 Distinctness

No two ids share decoded pixels at either size. The check that computed Section 8 rendered all 72 mask ids and all 48
RGB ids at 32 and 64 under these rules (the noise ids with `SplittableRandom` on JDK 27) and found no duplicate; #142's
replay test repeats it on the committed files.

## 9. Metrics, Thresholds and Tolerances

### 9.1 Metrics (owned by #144 for geometry, #147 for color, #151 for the arms)

- **IoU**: intersection over union of two masks of the same size; both empty gives `1`, one empty gives `0`. For a
  hypothesis, the mask is the analytic render at the input size. For a shape render RGB id, IoU is computed per fitted
  layer: the analytic render of the fitted layer against that layer's coverage mask. The fixture's `alpha` plane is
  never used as a coverage mask.
- **Agreeing pixels** (combined arm only): the pixels where, for every fitted layer of a shape render id, the fitted
  render and the gold coverage mask agree (both foreground or both background). Background layers always agree
  because they are stored, not fitted.
- **Boundary set**: foreground pixel centers with at least one 4 neighbour that is background or outside the raster.
  **Mean symmetric boundary distance**: `(mean_{a in A} min_{b in B} |a - b| + mean_{b in B} min_{a in A} |a - b|) / 2`
  in pixels; **Hausdorff**: the larger of the two directed maxima. One empty boundary set with the other non empty is
  a failure; both empty gives `0`.
- **PSNR and RMSE**: over `R`, `G`, `B` of the reconstruction re encoded to 8 bit sRGB against the fixture bytes, peak
  `255`; a zero MSE reports `100 dB` (the lossless marker). Alpha is compared separately as max absolute byte error.
  Whenever a PSNR is aggregated over ids, the report gives the mean (lossless ids enter as `100 dB`), the median, and
  the count of ids below `40 dB`, so a few exact ids cannot hide many misses behind the mean.
- **Reuse**: the runner's `ReuseMap` keyed by exact canonical primitive; a geometry's reuse count is the number of
  instances that reference it by identity.

### 9.2 Thresholds and tolerances (frozen)

Geometry gates apply to renders whose rendered extent (Section 4.3) is at least `8` pixels at the fixture size;
smaller renders (the `scale 0.1875` pose at 32, the `c-shp-01` beak at 32) are reported, not gated.

| Check | Threshold | Scope |
|---|---|---|
| Primitive reconstruction IoU | `>= 0.98` | positive ids and every gated single primitive fitted layer of a shape render id, at 32 and 64 (an annotated layer, the ring of `c-shp-08`, uses the composite row) |
| Composite reconstruction IoU (annotated fit) | `>= 0.95` | composite ids, `m-amb-09`, `10` with annotation, and the annotated ring layer of `c-shp-08` |
| Mean symmetric boundary distance | `<= 0.5 px` | same, in pixels of the fixture size |
| Hausdorff boundary distance | `<= 1.5 px` | same |
| Negative and ambiguous ids | `Abstain` reason in `gold_abstain`, or top kind in `gold_accept` | every negative and ambiguous id |
| Contour re rasterization | exact (0 mismatched pixels) | every mask |
| SDF sign reproduces mask | exact | every mask |
| SDF value versus brute force reference | absolute `1e-9 px` | every mask |
| Canonical invariance (H-G3) | for every gated pose with rendered extent `>= 24 px`, the fitted canonical primitive equals the id's `gold_geometry` exactly; trivially true for circle and square (constant parameters), so the real test is the catalogue triangles. Fitted composites are excluded: the grid step `1 / 4096` is far finer than a pixel, so two fits of one composite can never be expected to agree exactly; they are judged by H-G1 (IoU) and the false merge row | positive ids |
| False merge | 0 pairs of ids whose `gold_geometry` values are both not `NONE` and differ, and whose fitted canonical geometries are equal (an id with gold `NONE`, such as `m-amb-03` to `06`, may fit the canonical circle without counting as a merge; its fit is judged by `gold_accept`). Pairs are formed within one split only: validation mask ids at V3, test mask ids at V6, never across splits, because the row needs fitter output and is not one of the lossless checks exempt from the single use rule of Section 8.10. The 32 and 64 renders of one id are the same id and never form a pair; a pair is a merge when the fitted geometries are equal at either size | validation mask ids (V3), test mask ids (V6) |
| sRGB bytes → linear → sRGB bytes | byte identical for all 256 values | unit |
| Transfer function round trip in doubles, sRGB → linear → sRGB | absolute `1e-12` | unit, `[0, 1]` |
| Transfer function round trip in doubles, linear → sRGB → linear | absolute `1e-8` (the branch step of about `2.33e-9` at the breakpoint, Section 5, makes `1e-12` unreachable in this direction) | unit, `[0, 1]`, sampling must include `[0.04045 / 12.92, 0.04045 / 12.92 + 1e-8]` |
| DFT2D exact bins | magnitude and phase absolute `1e-12 · W · H · max(1, max |f|)` | closed form ids only (Section 8.9) |
| DFT2D off bins expected zero | same tolerance | same |
| DFT2D of tone and step ids | plane equals the reference DFT of the decoded bytes within the tolerance above; for tones the dominant bin index equals gold. The reference is a direct double sum `Σ f · (cos θ - i sin θ)` with `θ = 2π (u x / W + v y / H)` computed per term by `StrictMath`, written in #148's test sources and sharing no code with `Dft2d` | tone and step ids |
| DFT2D Parseval | relative `1e-9`; absolute `1e-9` when the plane's energy is `0` (`c-flat-01`, `c-deg-01`, any all zero channel) | every plane |
| DFT2D and Haar2D round trip | max absolute `<= 1e-9 · max(1, max |f|)` before clamping; imaginary residue below the same; re encoded bytes identical | every plane |
| Haar2D exact values (impulse, step, checkerboard) | absolute `1e-12` | as listed in Section 8.9 |
| Haar2D energy | relative `1e-9`; absolute `1e-9` when the plane's energy is `0` | every pyramid |
| Lossy spectral arm (`K = W · H / 8` real numbers kept per plane: Haar keeps the `K` largest magnitude coefficients; DFT keeps bins by magnitude until `K` reals are reached, counting a conjugate pair as `2` reals (one complex bin stored, its mirror implied) and a self conjugate bin as `1` real (DC `(0, 0)`, `(W / 2, 0)`, `(0, H / 2)` and `(W / 2, H / 2)`, whose imaginary part is `0` for a real plane); ties by index; a bin that would push the count past `K` is dropped) | PSNR `>= 40 dB` | validation at V4 (informational), test at V6, reported per id and as mean, median and count below `40 dB` (Section 9.1) |
| Composition versus direct mask fill (gold layers) | coverage IoU `1.0`; flat fills byte identical; textured and alpha blended fills within `1` byte per channel | every shape render id, composited from its gold layers |
| Combined arm (fitted layers) | every gated fitted layer meets its geometry row (primitive or composite IoU, mean boundary, Hausdorff) against its coverage mask; on the agreeing pixels, flat fills byte identical and alpha blended flat fills within `1` byte per channel, against the stored RGBA; a textured layer gates its coverage only, and its color on the agreeing pixels is reported as PSNR, not gated, because a sub pixel pose error shifts the texel lookup and pose accuracy is a report field, never a gate; PSNR over every pixel reported, not gated; a gated fitted layer that abstains fails its id, and an ungated one that abstains is left out of the composite (its pixels then disagree) | every shape render id; a fitted layer under `8` px of rendered extent (the `c-shp-01` beak at 32) is reported, not gated, and its pixels still count toward the agreeing pixel check where they agree |
| Determinism | two fresh JVM runs give identical digests and files | every runner |
| Latency, 64x64, single thread, JMH (2 forks, 5 warm up, 5 measurement, average time) | DFT2D forward or inverse `<= 10 ms`; Haar2D levels 6 `<= 2 ms`; render one instance `<= 1 ms`; IoU `<= 0.1 ms` | reported per operation; the budgets are compared to the JMH score (the mean over both forks); a 10x overrun of any budget fails V5 as a sanity bound, nothing smaller gates |
| Allocation per operation (JMH `-prof gc`, `gc.alloc.rate.norm`, mean over both forks) | reported for every operation; `Compositor.render` allocates no more than the output planes plus `1 KiB` per call, and this one budget gates at V5 because it is the measurable form of H-C1's "without a per instance geometry copy" | every operation reported; the `Compositor.render` budget gated at V5 |
| Bytes on disk | reported, no threshold | every fixture, every representation |

IoU `0.98` at the `8` px floor is a hard bar: at 12 px extent (`m-pos-20`, `T1` pose 4 at 64, validation) one or two
boundary pixels decide it. It stays because the gold pose lies in the fitter's search space and gives IoU `1.0`, so a
fitter that searches can reach it; a near miss there is a fitter result, reported as such.

## 10. Falsifiable Hypotheses

| Id | Hypothesis | Scored on | Refuted when | Gate |
|---|---|---|---|---|
| H-G1 | Annotated primitive masks reconstruct within the geometry thresholds and negatives abstain with the right reason | validation (V2), test (V6) | any gated id fails | V2 |
| H-G2 | Contour and SDF views invert to the mask exactly and match the brute force reference | every split (lossless) | any mask fails | V3 |
| H-G3 | Canonical catalogue primitives are invariant under instance transforms and distinct silhouettes never merge | validation (V3), test (V6) | any gated positive pose maps to a canonical primitive other than its gold, or any false merge | V3 |
| H-A1 | Color conversions and compositing round trip within tolerance and reject malformed input | every split (lossless) | any conversion or composition check fails | V4 |
| H-S1 | DFT2D inverts exactly and matches the exact bin gold | every split (lossless) | any plane fails | V4 |
| H-S2 | Haar2D inverts exactly, preserves energy and matches the subband gold | every split (lossless) | any plane fails | V4 |
| H-S3 | `W · H / 8` real coefficients reach 40 dB mean PSNR | validation (V4), test (V6) | mean PSNR below 40 dB | V4 (informational: it does not stop V4, it feeds the spectral arm of V6) |
| H-C1 | Eight instances render from one geometry by reference without a per instance geometry copy and match direct mask filling | validation (V5), test (V6) | `geometry()` identity differs across the instances, the `Compositor.render` allocation budget of Section 9.2 is exceeded, any instance misses the reuse oracle of Section 11.3 (coverage against the gold render at its pose), or color differs on the agreeing pixels of a fitted layer (combined arm row of Section 9.2) | V5 |
| H-V | The combined arm, given each layer's coverage mask, reproduces the stored flat appearance on the agreeing pixels and shows reuse on composites | test only, once | the verdict rule of Section 11.3 fails | V6 |

The lossy spectral bar may fail on checkerboards and noise; that is an allowed negative result, not a defect.

## 11. Gates, Arms and Verdict

### 11.1 Stop or go gates

| Gate | After | Go when | On failure |
|---|---|---|---|
| V0 | #141 | this contract, ADR 0053 and the pointers are merged | children do not start |
| V1 | #142, #143 | fixtures replay by pixel hash, manifest hash and splits verified, contracts tested for bounds, immutability and ordering | stop; a fixture or contract defect is fixed under the same contract version; `NOT TESTABLE` if unfixable |
| V2 | #144 | H-G1 on validation; determinism exact | stop #145, #146, #150 and #151 (nothing is left for V6 to score); #147, #148 and #149 may still run as standalone reference paths with their own benchmark documents; the verdict is `NO-GO`, recorded by #144 in its benchmark document with the required report fields of Section 13, and Epic #138 closes on that document |
| V3 | #145, #146 | H-G2, H-G3 on validation; determinism exact | the failing path is excluded from #150; the report says so; the verdict is already `NO-GO` but V4 and V5 still run for evidence |
| V4 | #147, #148, #149 | H-A1, H-S1, H-S2; determinism exact | a failing transform is excluded from the spectral arm; the report says so; the verdict is already `NO-GO`; H-S3 is reported either way |
| V5 | #150 | H-C1 on validation (including the `Compositor.render` allocation budget); no 10x latency overrun on any budget of Section 9.2 | the combined arm runs without composition; the verdict is already `NO-GO` |
| V6 | #151 | the verdict rule gives `GO` | `NO-GO` closes Epic #138 with a documented negative result; Epic #139 does not open |

### 11.2 Arms (#151; its runner protocol may add commands and seeds, never definitions)

| Arm | Stored | Reconstruction | Metrics |
|---|---|---|---|
| geometry | per mask id, the fitted canonical geometry (through the `ReuseMap`) and pose; per fitted layer of a shape render id, the same from its coverage mask | analytic render at fixture size | IoU, boundary distances, pose error, bytes per shape, reuse counts |
| rgb | the fixture's `RgbaImage` as 8 bit planes | identity | PSNR `100 dB` by definition, bytes `4 · W · H` against PNG and WebP |
| spectral | the `K` real truncation of DFT2D and of Haar2D of each `R`, `G`, `B` plane; the alpha plane is stored as its 8 bit bytes unchanged, like the rgb arm (the lossless checks of Section 9.2 still transform `A`, Section 6; only the lossy arm leaves it alone, because the 40 dB bar is a color claim) | inverse, clamp, re encode; alpha by identity | PSNR over `R`, `G`, `B`, alpha byte error `0` by construction, bytes `8 · K` per color plane plus `W · H` for alpha whenever the fixture's alpha is not all `255` (the ids whose formula sets `A` below `1`: `c-grad-06`, `c-chan-04` to `06`, `c-deg-01` to `03`, and any shape render id whose stored alpha is not all `255`) |
| combined | per shape render id, the stored background layers plus each fitted layer's instance from the geometry arm, each with its `Appearance` from `gold_layers` (flat or texture, alpha) | `Compositor`, layers in `gold_layers` order | per layer IoU, agreeing pixel color check, PSNR against the stored RGBA, reuse counts, bytes |

### 11.3 Verdict rule for Epic #139 (preregistered, evaluated once on the test split)

- `NOT TESTABLE` when V1 fails or the test split has been scored before #151.
- `GO` when V1 to V5 are go and, on the test split: H-G1, H-G3 and H-C1 hold; the combined arm meets its row in
  Section 9.2 for every shape render id (every gated fitted layer within its geometry thresholds; on the agreeing
  pixels, flat fills byte identical, blended flat fills within `1` byte per channel, textured layers by coverage
  only); for every composite id of the test split the runner takes its fitted canonical geometry and renders `8`
  instances from it by reference, one at each non circle positive pose (1 to 8 of Section 8.4) (the reuse claim; a
  pose whose render is `TRANSFORM_OUT_OF_RANGE` at a size is skipped at that size and reported). The reuse oracle:
  `geometry()` identity across the `8` instances is necessary and never sufficient; every rendered instance must
  meet the composite reconstruction row of Section 9.2 (IoU `>= 0.95`, mean symmetric boundary `<= 0.5 px`,
  Hausdorff `<= 1.5 px`) against the analytic render of the id's `gold_geometry` at the same pose, at both gated
  sizes. Composite ids are masks, so the oracle is coverage only; color under reuse is judged by the combined arm row
  on the shape render ids. Skipped poses are listed per id and do not count toward the `8`; the clause fails when any
  remaining instance misses. With `0` false merges within the test split's mask ids (Section 9.2 false merge row);
  determinism is exact; and every claim is an aggregate over the test split with a per id breakdown, never one example.
- `NO-GO` otherwise. H-S3 and bytes on disk are reported and never decide the verdict. Latency and allocation are
  reported, and exactly two footprint conditions gate, both at V5 and both preregistered in Section 9.2: the
  `Compositor.render` allocation budget (the measurable form of H-C1's "by reference") and a 10x overrun of any
  latency budget (a sanity bound). No other latency or allocation number decides a gate or the verdict.
- The train split is never scored for a gate or the verdict.

## 12. Baselines

- **PNG**: `javax.imageio` with the default PNG writer; bytes of the committed file recorded per fixture.
- **WebP**: measured once by #142 with a named `cwebp` version at `-lossless -exact` and `-q 75 -exact` (`-exact`
  keeps the color of transparent pixels, which `c-deg-01` and `c-deg-02` need); bytes and tool version recorded in
  the manifest and `PROVENANCE.md`; marked as externally produced data; no Java WebP dependency; `NA` when no `cwebp`
  is available. This column is reproducible only with the same `cwebp` build.
- **Polygon**: Douglas Peucker simplification of each contour ring at `ε = 0.5 px`, applied to a closed ring by
  splitting it at its start vertex and at the vertex farthest from it; bytes = 4 byte header (2 byte ring count,
  flags, size; a 1 px checkerboard has about 2000 rings) + per ring 2 byte vertex count + 2 bytes per vertex (two
  `uint8` pixel corner coordinates in `0..64`); the IoU of the re rasterized polygon is reported beside its bytes.
- **Representation bytes** (reported, fixed layouts): circle 13, square 17, triangle 25, composite `3 + Σ` over parts
  and holes (kind, part count, hole count, then each part's and each hole's layout; rotations stored as `k / 4096`
  radians); canonical mask 512 at 64x64; instance 36 (4 byte geometry reference plus four doubles); spectrum
  `16 · W · H`; pyramid `8 · W · H`; layer `1` flag byte (background or fitted, flat or texture) + instance `36` (a
  background layer stores no instance) + fill (`3` sRGB bytes for flat, `4 · W · H` for a texture not already stored,
  else a `4` byte texture reference) + alpha `8`; the combined arm's bytes per shape render id are the sum over its
  layers plus the geometries they reference, each geometry counted once.

Bytes are reported, never gated: the Epic promises no compression, and a geometry description may be larger than PNG
for tiny sprites.

## 13. Required Report Fields (#151 and every child benchmark)

Contract version, manifest version and sha256, split key and per id split, seeds, arms and their selection on
validation, `τ_fit` and `τ_margin` with how they were chosen, every threshold with its source section, pose error
statistics, JDK vendor and version, OS, architecture, CPU model, available processors, heap and GC settings, JMH
settings, `cwebp` version, Neuron commit (`+dirty` when uncommitted), determinism check result, per fixture table,
negative results with the same prominence as positive ones.

## 14. Issue DAG and NCM Relationship

### 14.1 Issue DAG (matches Epic #138)

```text
#141 -> #142, #143
#142, #143 -> #144
#142, #143, #144 -> #145
#143, #144 -> #146
#141, #142 -> #147
#142, #147 -> #148
#142, #147 -> #149
#145, #146, #147 -> #150
#144, #145, #146, #147, #148, #149, #150 -> #151
```

The graph is acyclic: every edge goes from a lower to a higher issue number. Each child's own "Blocked by" list on
GitHub is authoritative, and every prerequisite PR merges into `main` before a dependent child starts.

| Issue | Delivers | Blocked by | Package or module | Gate |
|---|---|---|---|---|
| #141 | this contract, ADR 0053, pointers | none | `docs/` | V0 |
| #142 | fixture generator, PNGs, manifest, splits, WebP and PNG baselines | #141 | `monada-neuron-evaluation` | V1 |
| #143 | `ShapePrimitive`, `VisualGeometry`, `BinaryMask`, `Result`, `VisualRejection` | #141 | `monada.neuron.visual` | V1 |
| #144 | `PrimitiveFitter`, `GeometryMetrics`, `τ_fit`, `τ_margin` | #142, #143 | `monada.neuron.visual`, evaluation | V2 |
| #145 | `Contour`, `SignedDistanceField` | #142, #143, #144 | `monada.neuron.visual` | V3 |
| #146 | `Transform2D`, `ShapeInstance`, `InstanceRasterizer` | #143, #144 | `monada.neuron.visual` | V3 |
| #147 | `ImagePlane`, `RgbaImage`, `ColorSpace`, `SrgbCodec` | #141, #142 | `monada.neuron.visual` | V4 |
| #148 | `Dft2d`, `Spectrum2D` | #142, #147 | `monada.neuron.visual` | V4 |
| #149 | `Haar2d`, `WaveletPyramid2D` | #142, #147 | `monada.neuron.visual` | V4 |
| #150 | `Appearance`, `Layer`, `Compositor` | #145, #146, #147 | `monada.neuron.visual` | V5 |
| #151 | `ReuseMap`, `VisualAblationRunner`, baselines, report, verdict | #144, #145, #146, #147, #148, #149, #150 | `monada-neuron-evaluation` | V6 |

### 14.2 Relationship to NCM

Related, never a blocker: NCM Epic #120 ([`ncm-wave-cognition/2`](ncm-wave-cognition-v1.md), ADR 0034 to ADR 0050).
The 2D transforms copy the numeric rules of [ADR 0036](../adr/0036-deterministic-dft-reference-decomposer.md) (residue
table, fixed summation order, `StrictMath`, rejection instead of a partial result) and
[ADR 0040](../adr/0040-haar-multiresolution-decomposer.md) (orthonormal Haar, rejection instead of padding) in their own
text and take no code dependency on `monada.neuron.spectral`: the 1D types carry sample rates and units that mean
nothing for pixels, and [ADR 0050](../adr/0050-ncm-investment-decision.md) froze NCM investment. Sharing numerics is
allowed only after a compatibility review recorded in a later contract version. This contract changes no NCM type,
ADR or verdict.

### 14.3 Follow up owned by the children

- #142 records the `cwebp` version and flags in `PROVENANCE.md` before measuring; without `cwebp` the WebP columns
  are `NA` and the report says so.
- #144 registers `τ_fit` and `τ_margin` in its benchmark document, selected on validation, before any test split
  result exists.
- #151 writes its own preregistered runner protocol under `docs/experiments/visual-ablation-v1.md`, reusing these
  thresholds and arms and adding none.
- A Store side spec and ADR in Monada Resonance Store is the only path to durable visual atoms.

## 15. Versioning and Change Record

- `visual-geometry/1` is this document. Changing any semantic, convention, fixture, threshold, hypothesis or gate
  after V1 is merged is `visual-geometry/2`, recorded here with its reason. Before V1, a fixture or contract defect
  found by #142 or #143 is fixed under `/1` and listed here.
- A child issue whose implementation diverges updates this document in the same PR, or records an ADR, and reports
  the deviation in its PR description. A silent mismatch between code and this contract is a defect (`AGENTS.md`).
- The test split is single use.

### 15.1 Change record

- `/1` (issue #141, ADR 0053, 2026-11-05): the original contract.
- `/1` (issue #141, review before first merge, 2026-11-06): the sRGB branches are stated to meet within `2.33e-9`,
  not exactly, and the double round trip is split by direction (`1e-12` sRGB first, `1e-8` linear first); the false
  merge row pairs ids within one split only; the reuse clause of the verdict rule gains a coverage oracle against the
  gold render at each pose; the two footprint conditions that gate (the `Compositor.render` allocation budget and a
  10x latency overrun, both at V5) are named once in Sections 9.2, 10, 11.1, 11.3 and ADR 0053. Smaller fixes from
  the same review: caps are returned by operations, never thrown by record constructors, and any dimension outside
  `[1, 64]` is `SIZE_UNSUPPORTED`; snapping and de duplication run before ranking and a composite hypothesis has
  ordinal `6`; `TOO_SMALL` means either side below `2`; the clipping note lists every clipped pose family; a V2
  failure names #144 as the publisher of the `NO-GO`; `Compositor.render` rejects `w != h`; Parseval and Haar energy
  fall back to absolute `1e-9` on zero energy, self conjugate bins count as `1` real, aggregated PSNR reports mean,
  median and count below `40 dB`, and the spectral arm stores alpha by identity; random bytes and draws are defined
  from `nextLong()` alone; the single package `monada.neuron.visual` is recorded as deliberate.

## 16. References

- Epic [#138](https://github.com/stevdrey/monada-neuron/issues/138) and child issues #141 to #151.
- [ADR 0053](../adr/0053-visual-geometry-experiment-protocol.md): visual geometry experiment protocol (#141).
- [ADR 0001](../adr/0001-cognition-memory-boundary.md): cognition and memory boundary.
- [ADR 0005](../adr/0005-signals-without-identifiers.md): signals without identifiers.
- [ADR 0006](../adr/0006-deterministic-scalar-resonance-metric.md): deterministic scalar resonance metric.
- [ADR 0019](../adr/0019-typed-hypothesis-and-evidence-model.md): typed hypothesis and evidence model.
- [ADR 0036](../adr/0036-deterministic-dft-reference-decomposer.md): deterministic DFT reference decomposer.
- [ADR 0040](../adr/0040-haar-multiresolution-decomposer.md): Haar multiresolution decomposer.
- [ADR 0043](../adr/0043-hierarchical-next-concept-predictor.md): ranked hypotheses with typed abstention.
- [ADR 0050](../adr/0050-ncm-investment-decision.md): NCM investment decision; rules before results.
- [ADR 0051](../adr/0051-bilingual-sense-evaluation-protocol.md): keyed hash splits and single use held out data.
- [NCM Wave Cognition Experiment Contract](ncm-wave-cognition-v1.md): the contract shape this document follows.
- [`bilingual-sense/1`](../experiments/bilingual-sense-v1.md) and [`ncm-ablation/1`](../experiments/ncm-ablation-v1.md):
  preregistration, disclosure and versioning conventions.
- IEC 61966-2-1: sRGB transfer functions and breakpoints.
- PNG specification: straight alpha, 8 bit grayscale and RGBA color types.
- Porter and Duff: source over compositing with premultiplied alpha.
- Discrete Fourier transform shift theorem and Parseval's identity; Mallat's square 2D wavelet decomposition.
- Douglas Peucker polyline simplification; 8 and 4 connectivity in digital topology.
