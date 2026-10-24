# ADR 0035: Compact Immutable Spectral Pattern

## Status

Accepted (issue #122; Epic #120; implements section 6 of `ncm-wave-cognition/1` under ADR 0034, with the deviations
listed below)

## Context

[NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md) makes issue #122 the owner of
`SpectralPattern`, `WaveComponent`, `SpectralBasis`, `SpectralScale` and the provenance types that every later child
issue (#123 to #127) consumes. Its section 3.4 sketches `SpectralPattern` as a record holding a
`List<WaveComponent>` and `SpectralScale(level, levelCount)`, and its section 6 states the value rules: bounded,
canonical `(level, index)` order without duplicates, versioned, normalized magnitudes, canonical phases, a derived
`phaseDefined` flag and a basis-dependent locator.

Three problems appear when the sketch is implemented literally:

1. **Per-component objects.** A list of records costs one object (about 48 bytes) per component plus the list. A
   complete DFT pattern of 4,096 samples has 2,049 components and a complete Haar pattern 4,096. Issue #122 asks for a
   compact layout for hot loops without per-component boxing, and #125 iterates over every component of two patterns
   per comparison.
2. **Haar key collision.** Contract fixture P9 has an approximation `[8]` and a level-2 detail `[-2]` for a 2-level
   decomposition. Both would be `(level 2, index 0)`, which the "no duplicate `(level, index)`" rule forbids.
3. **Stored derived values.** `phaseDefined` (relative to the pattern's largest magnitude) and the locator
   (`k · sampleRate / N`, or a Haar position) are functions of other fields. Storing them lets a caller build a
   pattern that contradicts the contract rules.

## Decision

- **New package `monada.neuron.spectral`** holds the opt-in types. No existing type changes: `Signal`,
  `FrequencyState`, `ScalarResonanceMetric` and `CognitiveStageKind` are untouched, and `SpectralCompatibilityTest`
  guards that.
- **`SpectralPattern` is a final class with parallel primitive arrays** (`int[] levels`, `int[] indices`,
  `double[] magnitudes`, `double[] phases`), following `FrequencyStateBatch`. Value semantics are explicit
  (`equals`/`hashCode` over the frame, source energy, provenance and arrays). Two public factories copy their input
  once: `of(frame, sourceEnergy, provenance, int[], int[], double[], double[])` for decomposers and
  `of(frame, sourceEnergy, provenance, List<WaveComponent>)` for hand-built or truncated patterns. Indexed accessors
  never allocate; bulk accessors return copies; `components()` is an unmodifiable lazy view that materializes a
  `WaveComponent` record per `get`.
- **`SpectralFrame(basis, scale, sampleCount, sampleRate, unit)`** groups the basis and sampling metadata. It
  validates their consistency once, decides which keys exist and computes locators. Contract section 7.2
  compatibility becomes "equal version and equal frame".
- **`SpectralScale(levels)`** is the decomposition depth `L`: `0` for DFT, `1..12` for Haar with `2^L ≤ N`;
  `levelCount()` is `L + 1`. It replaces the sketch's `(level, levelCount)`, whose second field was redundant.
- **Haar level encoding.** Level `0` is the final approximation (`N / 2^L` positions, locator `m · 2^L`); levels
  `1..L` are the details of each decomposition level (`N / 2^j` positions, locator `m · 2^j`), so level 1 is the
  finest detail as in fixture P9. DFT components are all level `0`, bins `0..floor(N/2)`.
- **`phaseDefined` and the locator are derived on access**, never stored. A phase is defined when its magnitude is
  positive and at least `1e-12` times the largest magnitude (absolute `1e-12` when that is `0`); a zero magnitude
  never defines a phase, even when the relative threshold underflows. The list factory rejects records whose flag or
  locator differ from the derived values.
- **`LocatorUnit`** (`HERTZ`, `CYCLES_PER_INDEX`, `SAMPLE_POSITION`) states the unit of every locator explicitly, so a
  Haar position or an abstract frequency is never mistaken for hertz.
- **`SampleUnit` and `WaveProvenance`**, sketched under #123, are delivered here because the pattern carries them;
  #123 reuses them. `WaveProvenance` reuses `ResonanceMemoryResult.MAX_REFERENCE_LENGTH` (128 UTF-16 chars).
- **`SpectralPhase.wrap`** is the single canonical phase rule, `(-π, π]` with `-π → π` and `-0.0 → 0.0`, for the
  decomposers and resonance.
- **Validation split.** Every invalid construction argument is a caller bug and throws `IllegalArgumentException` or
  `NullPointerException`; typed rejections of host input belong to #123's `DecompositionResult`. Nothing is
  repaired except signed zeros. Bounds: `MAX_SAMPLE_COUNT = 4096`, `MAX_COMPONENTS_DFT = 2049`,
  `MAX_COMPONENTS_HAAR = 4096`, `MAX_HAAR_LEVELS = 12`; the count is checked against the basis cap before any copy.
  Overflow and underflow guards: the highest DFT locator must be finite, the DFT bin spacing `sampleRate / N` must be
  at least `Double.MIN_NORMAL` (so distinct bins never collapse to one subnormal or zero locator),
  `windowOffset + sampleCount` must fit in a `long`, and the retained energy must be finite. The retained energy is
  summed with magnitudes scaled by `2^-e`, `e` the exponent of the largest magnitude: power-of-two scaling is exact,
  so the sum is bit-identical to the unscaled one in the normal range and keeps a representable subnormal energy
  instead of losing it to squaring underflow.
- **Energy consistency.** The allowed difference is the larger of `ENERGY_TOLERANCE = 1e-9` (the contract section 12.4
  Parseval tolerance) relative to the larger energy `E`, and a rounding allowance of
  `(sampleCount + componentCount + 4) · ulp(E)`: one ULP per rounded operation of the source sum `Σ x_n²` and of the
  retained sum, plus the scaling and the final multiply. The ULP term only dominates when `E` is (near-)subnormal,
  where a relative tolerance falls below rounding; normal energies keep the full relative test, so no absolute floor
  such as `Double.MIN_NORMAL` can admit an inconsistent small-scale pattern. A `sourceEnergy` of `0` means silent
  samples and admits only zero magnitudes. Any pattern may retain at most `sourceEnergy` plus that difference, so the retained-energy
  fraction of #126 never exceeds `1` beyond numerical noise. A complete pattern (every key of its frame present,
  which #126 classifies as `EXACT`) must also not retain less, so its energy equals `sourceEnergy` within the
  tolerance. `sourceEnergy = -0.0` is canonicalized to `0.0`.
- **Real-valued components.** Every Haar coefficient, the DFT DC bin and, for even `N`, the DFT Nyquist bin are real by
  definition, so their phase encodes only the sign and must be exactly `0` or `π`, whatever the magnitude. The
  decomposers (#123, #124) compute those values as real sums, so a real-valued inverse can honor every complete
  pattern.
- **Semantics fixed here.** Silence (zero components or only zero magnitudes) is valid, defines no phase and retains
  energy `0`. DFT retained energy weights DC and, for even `N` only, the Nyquist bin once and every other bin by `1/2`;
  odd `N` has no Nyquist bin, and its last bin `floor(N/2)` is an ordinary complex bin with any canonical phase.
  `sourceEnergy` is carried unchanged by truncation.

## Alternatives

- **Record with `List<WaveComponent>` (the sketch).** Simplest and closest to the contract text, but one object per
  component and a second, optimized layout later; the contract allowed the compact layout "behind the same value
  semantics", so the reference is compact from the start.
- **Store `phaseDefined` and the locator.** Cheaper reads, but the values could contradict the rules; derivation costs
  a multiply and a compare per read.
- **Haar approximation at level `L + 1` or a separate kind flag.** Either works; level `0` keeps keys dense, sorts the
  approximation first and leaves detail levels numbered as in the contract.
- **Off-heap (FFM) or Vector API layout.** Out of scope for this issue (CPU portable reference only).

## Consequences

- O(k) storage: about 24 bytes per component plus a fixed overhead, one copy per array at construction, no
  per-component object. Measured on OpenJDK 27 (Zulu 27+35, default flags, `AllocationProbe` after 2,000 warm-up
  constructions, Haar frame of 4,096 samples) against the analytic model of `SpectralPatternFootprintModel`
  (compressed references, 12-byte object headers):

  | k | Modeled retained bytes | Measured construction allocation |
  | ---: | ---: | ---: |
  | 0 | 128 | 120 |
  | 1 | 160 | 136 |
  | 8 | 320 | 312 |
  | 129 | 3,232 | 3,208 |
  | 2,049 | 49,312 | 49,288 |
  | 4,096 | 98,432 | 98,424 |

  The measured values stay at or below the model. The measuring JVM uses compact object headers: the pattern object is
  8 bytes smaller (56 instead of 64), and an `int[]` with an odd length is 8 bytes smaller after alignment because its
  array header is 12 bytes instead of 16; `double[]` and even-length `int[]` sizes are unchanged. That gives the 8 to
  24 byte differences above. Indexed reads (`magnitude`, `phase`, `locator`, `phaseDefined`) and `hashCode` allocate
  0 bytes; rewrapping another pattern's `components()` view over the same frame shares its immutable arrays and
  allocates only the pattern object.
- The public shape differs from the contract sketch; section 3.4 and section 6 of the contract now point here.
- #123 and #124 build patterns through the array factory and reuse `SampleUnit`, `WaveProvenance`, `SpectralPhase`
  and `MAX_SAMPLE_COUNT`; #125 compares frames for compatibility and iterates with indexed accessors.

## Follow-Up

- #125 may add the explicit, lossy dominant-component `FrequencyState` projection for its H2 control (contract
  section 3.4); it is not part of this issue.
- Package-private raw array access for #125's inner loops, if profiling shows the index checks matter.
