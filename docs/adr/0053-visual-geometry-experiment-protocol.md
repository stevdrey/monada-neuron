# ADR 0053: Geometry First Visual Experiment Protocol and Its Boundaries

## Status

Accepted (issue #141; Epic #138; ADR 0050 unchanged and binding). The contract is
[`visual-geometry/1`](../specs/visual-geometry-v1.md).

## Context

Epic #138 opens a geometry first visual research track: keep an object's canonical silhouette apart from its
placements, its color and its 2D spatial frequencies, then measure how well each one reconstructs. Its ten other
children (#142 to #151) were filed in advance and all wait on #141 for one versioned contract that fixes what the words
mean, who owns what, which numbers are frozen and when the epic stops.

The repo already has two patterns for this. `ncm-wave-cognition/2` is a contract with vocabulary, ownership, API
sketches, bounds, fixtures, tolerances, hypotheses, gates and a DAG; it let #122 to #130 ship as bounded PRs and end in
a documented no go ([ADR 0050](0050-ncm-investment-decision.md)). `bilingual-sense/1` and `ncm-ablation/1` are
preregistrations with keyed hash splits, single use held out data, frozen margins and verdict rules. ADR 0050 also
records that a verdict is only trusted when every rule preceded the first result, and that an unbudgeted reference
transform ends up dominating cost.

The forces specific to vision:

- Pixels do not yield labels, so semantics must stay a host supplied value.
- A 2D spatial frequency is not a light frequency and RGB is not three wavelengths, so the contract must say so in a
  form a reviewer can check.
- Near matches between silhouettes invite fuzzy merges that would make "reuse" meaningless.
- The issue asks for 32x32 and 64x64 fixtures, PNG and WebP baselines, a Java 27 CPU only reference with no new
  dependency, no preview API and no change to `Signal`, `FrequencyState`, `ScalarResonanceMetric`, the stage order or
  the Store formats. The JDK has no WebP codec.

Without a decision, each child would choose its own coordinate origin, alpha rule, transform normalization and
threshold, and the ablation would compare incompatible numbers.

## Decision

1. **One normative contract, `visual-geometry/1`** ([visual-geometry-v1.md](../specs/visual-geometry-v1.md)), modeled
   on `ncm-wave-cognition/2` with the preregistration folded in. It freezes the vocabulary and truth tables, the
   pipeline and ownership, API sketches per owning issue, every numeric convention, 72 mask and 48 RGB fixture ids with
   gold values and per id splits, metrics, thresholds, hypotheses H-G1 to H-V, gates V0 to V6, the arms, the verdict
   rule for Epic #139, baselines, report fields and the DAG. #151 later writes only a runner protocol that reuses
   these definitions.
2. **Shape, instance, appearance and spectrum are separate types.** A `VisualGeometry` is canonical (centered, unit
   extent, zero rotation) and never changes with position, scale, rotation, color, alpha or texture. A `ShapeInstance`
   is a geometry reference plus a similarity `Transform2D`; anisotropic scale is a new shape. Appearance is attached
   at render time. A spectrum is the DFT2D or Haar2D of one plane and is never a label or a light frequency.
3. **Exact equality on a `2^-12` grid.** Canonical parameters snap to `k / 4096`; geometries are equal only when the
   snapped parameters are equal. Similarity (IoU, boundary distance) is a metric, never an identity. Reuse is exact
   equality through a map the caller owns; Neuron keeps no registry.
4. **Ownership.** Contracts and scalar reference algorithms live in the opt in, experimental package
   `monada.neuron.visual`, with no stage and no default composition. That one package replaces the per concern
   packages named in issues #143, #147 and #150 (`.geometry`, `.color`, `.synthesis`), so the opt in module exports
   one experimental package and the children do not split it again. Fixtures, baselines, metrics, the reuse map and
   the ablation runner live in `monada-neuron-evaluation`. Nothing image shaped enters a `Signal`, a stage or the
   Store; durable visual atoms need a Store side spec and ADR in Monada Resonance Store.
5. **Rules before results.** Fixtures are project authored under CC0 from a seeded generator, with exact gold by
   construction. Splits are stratified keyed hash splits; gates V2 to V5 score validation; the test split is scored
   once, by #151 at V6. The verdict decides separation and reuse, not footprint: bytes, latency and allocation are
   reported, and only two footprint conditions gate, both at V5: the `Compositor.render` allocation budget, which is
   the measurable form of "render by reference without a per instance geometry copy" (H-C1), and a 10x overrun of
   any latency budget as a sanity bound.
6. **Independent 2D numerics.** The 2D transforms copy the rules of ADR 0036 and ADR 0040 in their own text and take
   no code dependency on `monada.neuron.spectral`; sharing waits for a recorded compatibility review. WebP bytes are
   measured offline with a named `cwebp`, never through a Java dependency.
7. **Coverage is host supplied.** The combined arm gets each layer's coverage mask as segmentation, and composites are
   fitted only with a part and hole annotation. A `GO` therefore says geometry plus appearance recomposes an image,
   not that Neuron can segment one; the contract states this in its non claims.

## Alternatives Considered

- **A short preregistration only, under `docs/experiments`.** Rejected: #143 and #147 would each run their own design
  round for coordinates, alpha, color spaces and transforms, and the issue asks for expected APIs and a pipeline
  diagram with owners.
- **A contract plus a separate preregistration, both written by #141.** Rejected: two documents that must agree
  before the runner exists, and #151 still needs its own runner protocol, so the middle document would be a third copy
  of the thresholds.
- **Everything in the evaluation module until #151 says go.** Rejected: the Epic and the children name Neuron
  packages, the consumer fixture cannot see evaluation types, and ADR 0050 shows that isolated, tested, opt in core
  code is cheap to keep after a no go.
- **Approximate equality with a threshold.** Rejected: that is a fuzzy merge by definition. No equality at all would
  make "reuse without a false merge" untestable.
- **Coverage from the fixture's `alpha > 0`.** Rejected: an opaque background makes the mask the whole frame, so the
  fitter returns `FULL`, and a multi color object would need two appearances on one instance; V6 could only give
  `NO-GO`. Shape render ids are layers instead, with opaque backgrounds stored, never fitted.
- **Judging the combined arm byte exact over the whole image.** Rejected: one boundary pixel that IoU `0.98` allows
  would decide the Epic. Geometry error is judged once, by the geometry rows; color on the agreeing pixels.
- **A gate on bytes on disk.** Rejected: the Epic promises no compression, and a geometry may be larger than PNG for
  tiny sprites.

## Consequences

- Ten children can be built and judged independently; #142 and #143 can start in parallel once V0 merges.
- The verdict for Epic #139 cannot be tuned after seeing results: formulas, thresholds, splits and the single use test
  split are committed first.
- Exact canonical equality makes "no fuzzy merge" a property a test can prove. A fitter that lands one grid step off
  produces a different geometry, so H-G3 is in practice a test of the catalogue triangles; fitted composites are
  judged by IoU and the false merge rule only.
- A hard 64x64 cap and a direct DFT keep the reference slow and small by design; nothing here scales to real images.
- The contract is long, and a convention wrong here is wrong in ten PRs; fixing it after V1 is `visual-geometry/2`.
- IoU `0.98` at the `8` px gating floor is a hard bar on the smallest renders; it stays because the gold pose lies in
  the fitter's search space.
- The default runtime, `Signal`, the stage order and the Store formats stay untouched through the whole epic.

## Follow-Up

- #142: fixtures, manifest, splits, PNG and WebP baselines; records the `cwebp` version before measuring.
- #143 to #150: the contracts and reference algorithms of the contract's API sketches, each behind its gate.
- #144: registers `τ_fit` and `τ_margin`, selected on validation, before any test split result exists.
- #151: writes `docs/experiments/visual-ablation-v1.md`, runs V6 once and records the verdict in a new ADR.
- Monada Resonance Store: a Store side spec and ADR is the only path to durable visual atoms.
