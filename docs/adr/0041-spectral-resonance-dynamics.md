# ADR 0041: Spectral Resonance Dynamics

## Status

Accepted (issue #125; Epic #120; implements section 7 of `ncm-wave-cognition/1` under ADR 0034, on the pattern of
ADR 0035; ADR 0006 is unchanged)

## Context

`ScalarResonanceMetric` (ADR 0006) scores two single `FrequencyState` values by an amplitude, frequency and phase
product. It cannot compare multi-band spectra, and it has no signed constructive or destructive term. The
[NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md) makes #125 the owner of a separate
`SpectralResonanceDynamics` over two `SpectralPattern`s (section 7). It fixes the reference quantities: signed
per-band interference, a magnitude alignment, a `[0, 1]` aggregate and a phase coherence. It also assigns the H2
evidence (scalar control, concept fixture generator, gate G2) to #125. Questions left open by the sketch of
section 3.4:

1. **Result shape.** How are an incompatible pair and the per-band diagnostics typed, and are they enough to recompute
   the summary values?
2. **Matching.** How are bands matched, at what cost, and how is a Haar key represented?
3. **Numerics.** How do the quantities stay finite and in range for every valid pattern, including subnormal and
   bound energies?
4. **Scalar control.** Where does the dominant-component projection live, and what does it do for Haar?
5. **Batch bound.** Does v1 need the `maxPatternsPerComparisonBatch` bound of section 11.1?

## Decision

- **A separate, stateless `SpectralResonanceDynamics`** in `monada.neuron.resonance`, with one method
  `ComparisonResult compare(SpectralPattern, SpectralPattern)`. It does not implement or wrap `ResonanceMetric`, and
  `ScalarResonanceMetric` is untouched.
- **Sealed result.** `ComparisonResult` permits `SpectralComparison` and `Incompatible(IncompatibilityReason,
  detail)`, mirroring `DecompositionResult`. An incompatible pair is an expected value, not an exception. Null
  patterns are caller bugs and throw.
- **Compatibility order.** The fields are checked in the fixed order `VERSION`, `BASIS`, `SCALE`, `SAMPLE_COUNT`,
  `SAMPLE_RATE`, `UNIT`, and the first difference is reported, so a pair always gets the same reason. Nothing is
  interpolated or resampled (N6).
- **Band key and diagnostics.** `BandContribution(level, index, firstMagnitude, secondMagnitude, phaseDifference,
  alignment, interference, phasesDefined)`:
  - It adds `level` to the sketch, because a Haar key is `(level, index)`.
  - It adds the two magnitudes and the phase-defined flag, so every summary value can be recomputed from the bands
    by the scaled procedure under *Numerics*.
  - `SpectralComparison` carries the shared bands in ascending key order, both full norms, the counts of
    unshared components, `aggregate` and `coherence`.
- **Two-pointer matching.** One merge over the canonical, duplicate-free key order that `SpectralPattern` guarantees.
  O(n + m) time, no sort, no hashing, a band list of at most `min(n, m)` records, and one scan per pattern for its
  peak and norm. A band only one pattern retains is not a contribution but counts in that pattern's norm (P17).
- **Formulas** exactly as section 7.3, with `Δφ = SpectralPhase.wrap(φ1 − φ2)`:
  - Interference uses every stored phase.
  - Only coherence reads `phaseDefined`.
  - A zero norm gives aggregate `0`; `Σ w = 0` gives coherence `0`.
  - No band weighting is applied (unit weights beyond the magnitudes), and nothing is learned or persisted.
- **Numerics.**
  - **Scaling.** The aggregate ratio and the coherence sums use magnitudes scaled by `2^-e` per pattern, `e` the
    exponent of its largest magnitude. The scaling is exact, so the result is bit-identical to the unscaled formula
    whenever that formula does not underflow, and it stays meaningful for subnormal energies.
  - **Finiteness.** `SpectralPattern` bounds energy by `MAX_SOURCE_ENERGY = Double.MAX_VALUE / 2`. That bounds every
    squared magnitude and squared norm, so raw interference terms and norms are finite. They are unscaled, so a product of
    small magnitudes can underflow, even when `firstNorm · secondNorm` is normal (a tiny shared band of two
    otherwise large patterns). Both summary values are invariant under a separate positive scale per pattern, so they
    are recomputed by scaling each pattern's band magnitudes and norm by `2^-getExponent(norm)` and evaluating the
    formulas on the scaled magnitudes and the stored `phaseDifference`; an unscaled recomputation is reliable only
    while every product it uses is a normal double.
  - **Clamping.** The normalized sum is clamped to `[−1, 1]` and the coherence to `[0, 1]`, against a few ulps of
    rounding.
  - **Determinism.** Sums run in ascending key order with `StrictMath`. Results are bit-reproducible and symmetric
    in the aggregate and the coherence. A phase difference of exactly `π` (its own negation) contributes a sine of
    `0` rather than `StrictMath.sin(π) ≈ 1.2e-16`, so swapping the patterns cannot move the coherence by an ulp.
- **`DominantComponentProjection`** in `monada.neuron.resonance`: an explicit, lossy helper. It returns the
  largest-magnitude component, ties to the lower key, as `FrequencyState(magnitude, locator, phase)`.
  - A silent pattern gives `FrequencyState.ZERO`.
  - Haar patterns are rejected (`IllegalArgumentException`), because a Haar locator is a position, not a frequency
    (section 2.1).
  - It is the scalar control of H2 and is never applied implicitly.
- **No batch API in v1.** A one-against-many comparison would drift towards ranking, which the Store owns
  (ADR 0001). Callers loop over pairs, so `maxPatternsPerComparisonBatch` is deferred to whichever issue first needs
  a batch API (a minor revision of the contract, section 17).

## Alternatives

- **Extending `ScalarResonanceMetric` or `ResonanceMetric`.** That would change ADR 0006 semantics and the H2
  control. Rejected by contract section 7.1.
- **Folding signed interference into a `[0, 1]` score.** That hides destructive relations, and the aggregate already
  maps the normalized sum. Interference stays a separate signed diagnostic and is never a Store score.
- **Hash-map band matching.** O(n + m) expected, but it needs boxing or a custom map, and it adds an iteration order
  the canonical sort already gives for free.
- **Norms over shared bands only.** A pattern could then match well by dropping its other components. Rejected by
  section 7.3 (P17).
- **Coupled oscillators (Kuramoto) or PLV over time.** These describe dynamics over many windows, not one pair.
  Out of default scope (issue non-goal, section 5.1).
- **Projection in the evaluation module only.** It would hide a documented helper the contract offers to hosts as a
  control. Kept in core, small and explicit.

## Consequences

- P10, P11, P12, P17 and N6 are unit tests within `1e-12`. The tests also cover:
  - uncorrelated, mismatched-frequency, zero-energy, phase-wrap, duplicate-key, undefined-phase, energy-bound and
    subnormal cases
  - exact symmetry and determinism
  - recomputing the summary values from the bands
  - a seeded phase-randomized control and a frequency-shifted control
- The cost is one allocation of the band list and one record per shared band. The measured end-to-end cost over
  sparse and dense mixtures is in [spectral-resonance-h2.md](../benchmarks/spectral-resonance-h2.md).
- The aggregate is phase-sensitive by definition: two windows of the same tones with unrelated phases sit near
  `0.5`. Whether it separates concepts better than the scalar control is H2's question. The pre-registered answer
  decides gate G2, recorded in the benchmark document.
- `Signal`, `FrequencyState`, `ScalarResonanceMetric`, the canonical stage kinds and the Store recall path are
  unchanged.

## Follow-Up

- #127 may use the aggregate, the coherence or the band diagnostics as features only if G2 is go; otherwise it
  proceeds without resonance features.
- An optimized backend (SIMD, primitive result layout) is out of scope for v1. If added later, it is checked
  against this reference within a documented tolerance.
