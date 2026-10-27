# ADR 0040: Haar Multiresolution Decomposer

## Status

Accepted (issue #124; Epic #120; implements the Haar strategy of section 5.2 of `ncm-wave-cognition/1` under
ADR 0034, on the types of ADR 0035 and the decomposer boundary of ADR 0036)

## Context

The DFT reference (#123, ADR 0036) reports global frequency content over a whole window; it cannot say *where* a
transient or a step happened, or at which scale. NCM coarse-to-fine reasoning (#127) and control D of #130 need an
explicitly separate scale analysis. [NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md)
makes #124 the owner of an orthonormal Haar `WaveDecomposer`, served by gate G1h: fixtures P9 and N4 pass within
section 12.4, energy is preserved and determinism is exact. Non-power-of-two lengths are rejected, not padded, and
coefficients are reported as scale and position, never in hertz (section 2.1).

#122 already reserved the representation: `SpectralBasis.HAAR`, `SpectralScale` with `MAX_HAAR_LEVELS = 12`, a
`SpectralFrame` that requires a power-of-two `N` and `1 <= L`, `2^L <= N`, level `0` for the final approximation and
levels `1..L` for the details, sample-position locators and the real-valued phase rule. Questions left open:

1. **Depth policy.** What happens when a window cannot be decomposed to the requested depth?
2. **Boundary and length policy.** Pad, extend or reject lengths that are not powers of two?
3. **Scale-local energy.** How does a consumer read the energy of one scale?
4. **Fourier vs wavelet.** How is a Haar pattern kept from being compared bin by bin with a DFT pattern?

## Decision

- **`HaarWaveDecomposer(int levels)`** is a stateless, thread-safe `WaveDecomposer` with a **fixed depth**
  `L in 1..12`; an out-of-range depth is a caller bug (`IllegalArgumentException`). Every pattern it produces carries
  the same `SpectralScale(L)`. Two of its patterns share a frame only when their windows also have the same sample
  count, sample rate and unit, which come from the wave, not from the decomposer; #125 treats any frame mismatch,
  including a different rate, as `Incompatible` (fixture N6).
- **Length policy: reject, never pad, never clamp.** A wave whose `N` is not a power of two, or with `2^L > N`
  (including `N = 1`), is `DecompositionRejected(SAMPLE_COUNT_UNSUPPORTED)`, a new constant of
  `DecompositionRejectionReason` (fixture N4: `N = 6`). Window lengths are chosen by the host encoder.
- **Transform.** `a_m = (x_{2m} + x_{2m+1}) / √2`, `d_m = (x_{2m} − x_{2m+1}) / √2` with
  `√2 = StrictMath.sqrt(2.0)`, ascending `m`, levels `1..L`, each level over the previous approximation, in
  `double`, single-threaded. Bit-reproducible: the only operations are IEEE 754 additions, subtractions and
  divisions in a fixed order.
- **Normalization and phase.** Coefficients are the orthonormal values: `magnitude = |c|`, `phase = π` for `c < 0`
  and `0` otherwise (ADR 0035's real-valued rule). The transform is orthonormal, so `Σ c² = Σ x_n²`; the existing
  `SpectralPattern` Parseval check of a complete pattern enforces it.
- **Layout.** Outputs are written straight into canonical order: level `0` (`N / 2^L` approximations), then level
  `1` (`N / 2` finest details), ..., level `L` (`N / 2^L` coarsest details). Locators are the support start
  positions `m · 2^j` (`m · 2^L` for the approximation), `LocatorUnit.SAMPLE_POSITION`.
- **Memory.** Level `1` reads the wave's samples without a copy; one working array of `N / 2` approximations is
  reused **in place** by every later level (writing `w[m]` after reading `w[2m]` and `w[2m+1]` is safe in ascending
  `m`). The four output arrays of `N` entries are handed to the pattern through the owning factory of ADR 0036. O(N)
  time and memory, no quadratic intermediate.
- **Energy rule shared with the DFT.** The source energy uses the same scaled sum and the same accepted range as
  `DftWaveDecomposer`, now one package-private predicate (`SpectralArithmetic.sourceEnergyRepresentable`) used by
  both decomposers. An accepted energy is at most `MAX_SOURCE_ENERGY = Double.MAX_VALUE / 2`, so every sample and
  intermediate coefficient is bounded by `√(Double.MAX_VALUE / 2)` and pairwise sums cannot overflow. The sample rate
  only travels into the frame; Haar locators do not depend on it, so the Haar strategy has no
  `SAMPLE_RATE_OUT_OF_RANGE` case.
- **Scale-local energy: `SpectralPattern.levelEnergy(int level)`.** The energy retained at one level, summed in
  canonical order with its own power-of-two scaling (the `retainedEnergy()` technique). For a DFT pattern, whose only
  level is `0`, it equals `retainedEnergy()` bit for bit; for Haar, the level energies add up to `retainedEnergy()`
  within rounding. An absent level has energy `0`; a level outside the frame is an `IllegalArgumentException`.
- **Fourier vs wavelet is explicit in the frame.** A Haar and a DFT pattern of the same wave differ in basis, scale,
  locator unit and component count, and are not equal. Any comparison must check the frame first; #125 returns
  `Incompatible` for a basis mismatch (fixture N6). No API converts a Haar level or locator to a frequency.

## Alternatives

- **Zero, periodic or symmetric padding to the next power of two.** Lets any `N` through, but every boundary rule
  creates detail energy at the padded edge that the samples do not contain, at every scale, and changes the energy
  bookkeeping. The contract forbids it; an encoder that wants it pads explicitly and records it in provenance.
- **Clamping `L` to `log2 N` per window**, or a full-depth factory. Patterns of one decomposer would then have
  different scales depending on `N`, silently. A host that wants full depth constructs
  `new HaarWaveDecomposer(log2 N)` for its window size.
- **Unnormalized pairwise sums with a final `2^{-j/2}` scaling.** Exact for even `j`, but not the per-level formula
  the contract names and P9 checks; the per-level division is within `1e-12` on P9 and within the Parseval tolerance
  at `N = 4096`.
- **Lifting or integer Haar.** Useful for lossless integer codecs, not orthonormal, so energy and P9 would not hold.
- **A separate `ScaleEnergyProfile` value type.** More explicit, but a second public type for a value the pattern can
  derive in O(k) without allocation.
- **Cohen-Daubechies-Feauveau or Daubechies wavelets.** Smoother localization, but boundary handling and longer
  filters; out of scope for v1 (contract section 5.1).

## Consequences

- Gate G1h is testable: P9 (exact values within `1e-12`, order, locators, energy 70) and N4 are unit tests;
  impulse, aligned and misaligned step, alternating, period-8 square and mixed transient-plus-periodic fixtures show
  scale separation through `levelEnergy`; energy is preserved within `1e-9` for seeded `N ∈ {8, 256, 4096}`; a
  test-only inverse round-trips complete patterns within section 12.4; a seeded `N = 256` window has a recorded
  bit-level checksum; extreme-magnitude fuzzing finds no escaping exception.
- Cost: O(N) time and memory, no trigonometric call. The measured comparison with the DFT reference at fixed `N` is in
  [wave-decomposer-reference.md](../benchmarks/wave-decomposer-reference.md).
- Neither decomposer is a default. Choosing one, and the depth, is a host decision; nothing enables wavelets
  automatically. `Signal`, `FrequencyState`, `ScalarResonanceMetric` and the canonical stage kinds are unchanged.

## Follow-Up

- #126 inverts complete Haar patterns (fixture P14) when G1h is go; this ADR fixes the layout it reads.
- #125 treats a basis or scale mismatch as `Incompatible` (N6).
- #130 builds control D with an explicitly constructed `HaarWaveDecomposer` and reports its depth.
