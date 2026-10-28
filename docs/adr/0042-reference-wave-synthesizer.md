# ADR 0042: Reference Wave Synthesizer

## Status

Accepted (issue #126; Epic #120; implements section 9 of `ncm-wave-cognition/1` under ADR 0034, on the pattern of
ADR 0035 and the decomposers of ADR 0036 and ADR 0040)

## Context

The DFT reference (#123, ADR 0036) and the Haar strategy (#124, ADR 0040) turn a `SampledWave` into a
`SpectralPattern`, but nothing turns a pattern back into samples, and the single `FrequencyState` cannot reconstruct
an arbitrary window. The [NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md) makes #126
the owner of a `WaveSynthesizer` served by gate G1s: complete patterns invert within the round-trip tolerance of
section 12.4 (fixtures P13 for DFT, P14 for Haar), a truncated pattern is never `EXACT` (N8), and determinism is
exact. Gates G1 and G1h are go, so both paths are in scope. Section 9 fixes the rules (structural `EXACT`, `LOSSY`
for dropped components, `retainedEnergyFraction` from the pattern, errors only against supplied samples, every stored
phase used). Questions left open by the sketch of section 3.4:

1. **Result shape.** A record with a status and possibly empty samples, or a typed variant per outcome?
2. **Lossy versus not invertible.** Which incomplete patterns still synthesize, and which do not?
3. **Diagnostics.** How are the errors defined, in which units, and do they stay finite for every valid pattern?
4. **Original samples.** How does the caller supply them, and what if they are not the decomposed window?
5. **Backend shape.** One class, or an interface with one implementation per basis?

## Decision

- **Sealed result**, mirroring `DecompositionResult` and `ComparisonResult` (ADR 0041):
  `SynthesisResult permits Reconstructed, NotInvertible`, both exposing `status()` and `retainedEnergyFraction()`.
  - `Reconstructed`, status `EXACT` or `LOSSY`, with `samples`, `retainedEnergyFraction`, `reconstructedEnergy`,
    `maxAbsoluteError`, `rmsError` and `relativeEnergyError`. It is a final value class rather than a record, like
    `SpectralPattern` (ADR 0035): the public factory `Reconstructed.of(...)` copies the samples, while the synthesizer
    hands over the array it just built through a package-private owning factory, so a synthesis allocates its output
    once. Samples never escape (`samples()` copies, `sample(i)` reads); signed zeros are canonicalized, so equal
    results are equal values.
  - Invariants checked at construction: an `EXACT` result has `retainedEnergyFraction == 1`; the three errors are
    present or absent together; when present, `rmsError <= maxAbsoluteError`.
  - `NotInvertible(NotInvertibleReason, retainedEnergyFraction, detail)` carries no samples; its detail is bounded
    (256 chars) and never contains sample data.
- **Status is structural.** `EXACT` iff the pattern holds every key of its frame (`componentCount ==
  frame.componentCapacity()`, sufficient because keys are unique and in the frame). Otherwise `LOSSY`: each absent
  component is synthesized as zero, which is what top-K or thresholding meant by dropping it.
- **`NOT_INVERTIBLE` only for a missing approximation.** A Haar pattern missing **any** coefficient of its final
  approximation (level `0`) is `NotInvertible(MISSING_APPROXIMATION)`. The approximation is the coarse content every
  detail level refines; the reference does not guess it. A DFT pattern is always invertible, a missing DC bin
  included: every bin is an independent orthogonal term.
- **Inverse DFT.** Single-sided, conjugate-symmetric for real samples, rectangular window, time origin at the first
  sample, `N` from the frame: `x̂_n = c_0 + Σ_{0<k<N/2} m_k · cos(2π·k·n/N + φ_k) + c_{N/2} · (−1)^n`. Real bins
  (DC, even-`N` Nyquist) are applied by sign, without a `cos(π)` rounding. Complex bins precompute `m · cos φ` and
  `m · sin φ` (`StrictMath`) and read the decomposer's twiddle table (one helper, `SpectralArithmetic.fillTwiddles`, used by both) with exact
  integer residues. Order: components
  ascending, samples ascending inside, so each sample sums its bins in ascending `k`. Every stored phase is used;
  `phaseDefined` is never read. O(N·k) ≤ O(N²) time, O(N) memory (output and two lazily built twiddle tables).
- **Inverse Haar.** Retained coefficients (sign from the phase) are scattered into one dense `N`-array in canonical
  layout; from level `L` down to `1`, `x_{2m} = (a_m + d_m) / √2` and `x_{2m+1} = (a_m − d_m) / √2` with
  `√2 = StrictMath.sqrt(2.0)`, in place in the output array in descending `m` (writes land at `2m >= m`, never on an
  approximation not yet read). O(N) time and memory, no trigonometry.
- **Diagnostics and units.**
  - `retainedEnergyFraction = min(1, retainedEnergy / sourceEnergy)`, `1` for silence, dimensionless, from the
    pattern alone; reported by both variants. A complete pattern reports exactly `1`: `SpectralPattern` already
    rejects a complete pattern whose retained energy differs from its source energy beyond the Parseval allowance,
    so the remaining difference is rounding. The clamp at `1` removes the same noise from above.
  - `reconstructedEnergy = Σ x̂_n²`, squared sample units, always reported: for an orthonormal path it equals the
    retained energy within rounding, a check that needs no original.
  - With the original: `maxAbsoluteError = max |x̂_n − x_n|` and `rmsError = √(Σ (x̂_n − x_n)² / N)` in sample units,
    `relativeEnergyError = |Σ x̂_n² − Σ x_n²| / Σ x_n²`, dimensionless, `0` for silence. The RMS is clamped to the
    peak against an ulp of rounding in the mean.
  - Squares near the energy bound can overflow (`|x| ≤ √(Double.MAX_VALUE / 2)`, so a residual squared may not be
    finite). Energies use `SpectralArithmetic.sumOfSquares`; the peak and RMS of `x̂ − x`
    (`peakDifference`, `rootMeanSquareDifference`) and the relative energy error (`relativeEnergyDifference`, both
    sums scaled by one common exponent, so a subnormal energy does not underflow to `0`) live in
    `SpectralArithmetic` with the same power-of-two rule. Every value is finite for every valid pattern, and no
    residual array is allocated.
- **Original samples are the decomposed `SampledWave`.** `synthesize(pattern, original)` requires equal sample count,
  sample rate, unit and provenance, and an energy equal to `sourceEnergy` within the pattern's Parseval allowance
  (the allowance is now one helper, `SpectralArithmetic.energyAllowance`, shared with `SpectralPattern`). A mismatch
  is a caller bug (`IllegalArgumentException`), consistent with the section 11.2 split. Silence must match exactly,
  as in `SpectralPattern`: an original whose samples are all `0` only for a pattern of source energy `0`, and the
  reverse. Silence is judged by the samples, because the energy of subnormal samples can underflow to `0`. `synthesize(pattern)` reports
  empty errors, never estimated ones.
- **One stateless class.** `WaveSynthesizer` dispatches on the basis with an exhaustive switch. Unlike decomposers,
  the synthesizer needs no configuration (depth and length are in the frame) and there is no second backend; an
  interface is extracted when an optimized backend exists.
- **Determinism.** `StrictMath`, fixed order, `double`, single-threaded: bit-identical results on every run and JVM,
  covered by golden checksums of a seeded `N = 256` window for both paths.

## Alternatives

- **The record sketch of section 3.4** (`SynthesisResult(status, samples, ...)`). One type, but a
  `NOT_INVERTIBLE` value would carry an empty array and meaningless energy, and nothing would type why it failed.
- **Zero-filling a missing approximation** (treating it as `LOSSY`). Uniform, but it reconstructs a window whose
  coarse level is silently zero; the contract names this case `NOT_INVERTIBLE`.
- **`NOT_INVERTIBLE` for a missing DFT DC bin.** The DC term is one orthogonal component like any other; refusing it
  would make thresholded zero-mean windows non-invertible for no structural reason.
- **Plain `double[]` for the original.** Cheaper, but cannot check that the samples are the decomposed window;
  a `SampledWave` carries the frame metadata and provenance to compare.
- **Raw error sums.** Simpler, but `(x̂ − x)²` overflows near `MAX_SOURCE_ENERGY` and underflows for subnormal
  energies; scaled sums cost one extra pass.
- **Interface plus `DftWaveSynthesizer` and `HaarWaveSynthesizer`.** Mirrors the decomposers, but adds two public types
  and a basis-mismatch error path for a single reference with no configuration.
- **FFT-based inverse.** O(N log N), but a different summation order; out of scope for v1 (section 1.2) and checked
  against this reference when added.

## Consequences

- Gate G1s is testable per path and passes: P13 (P1 to P5, P8, impulse, silence and seeded `N ∈ {8, 256, 4096}`) and
  P14 (seeded `N ∈ {8, 256, 4096}`, depths `1`, `3` and `log2 N`, and P9) are `EXACT` within
  `1e-9 · max(1, max |x_n|)`; N8 is `LOSSY` with fraction `0.2`, max error `1`, RMS `1/√2` and relative energy error
  `0.8` within `1e-12`. Dropping any single component is never `EXACT`; extreme magnitudes stay finite.
- Cost: measured against decomposition at fixed `N` in
  [wave-decomposer-reference.md](../benchmarks/wave-decomposer-reference.md).
- The test-only Haar inverse of ADR 0040 stays in the decomposer's tests, so the decomposer is never checked only by
  the synthesizer it round-trips with; `WaveSynthesizer` has its own closed-form checks (P9, N8).
- `Signal`, `FrequencyState`, `ScalarResonanceMetric`, `SpectralPattern` semantics and the canonical stage kinds are
  unchanged. Synthesis is library-only: nothing in the cycle calls it.

## Follow-Up

- #128 decides whether synthesis is wired into a stage (G1s go for both paths): ADR 0044 wires DFT synthesis as an
  optional fidelity diagnostic; Haar is not wired because the predictor is DFT-only.
- A later FFT or vectorized backend extracts a synthesizer interface and states its tolerance against this reference.
