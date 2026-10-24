# ADR 0036: Deterministic DFT Reference Decomposer

## Status

Accepted (issue #123; Epic #120; implements sections 4 and 5.2 of `ncm-wave-cognition/1` under ADR 0034, on the
types of ADR 0035)

## Context

[NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md) makes issue #123 the owner of
`SampledWave` and of the DFT reference `WaveDecomposer`, the "prism" of Epic #120. Gate G1 requires fixtures P1 to P8,
N1 to N3, N5 and N7 to pass and determinism to be exact. Three questions were left open:

1. **Validation split.** Section 11.2 says invalid inputs are rejected "with a typed reason or
   `IllegalArgumentException` according to whether they are expected values or caller bugs; #122 and #123 fix that
   split." #122 made every invalid `SpectralPattern` argument a caller bug.
2. **Where samples come from.** `Signal` carries no time series (ADR 0005), so a decomposer must never derive samples
   from it; the issue asks for an explicit host-encoder seam.
3. **Aliasing.** The issue asks that "aliasing-risk cases fail/report consistently", while fixture N7 states that
   aliasing is undetectable from the samples and is not an error.

The reference must also be bit-reproducible with `StrictMath` (section 11.2), O(N²) by design and bounded by
`maxSamples = 4096`.

## Decision

- **`SampledWave(samples, sampleRate, unit, provenance)`** is a record that validates the section 4.1 schema in its
  compact constructor and throws `IllegalArgumentException` or `NullPointerException`: an empty array (N1), a NaN or
  infinite sample (N2), more than `MAX_SAMPLES = SpectralPattern.MAX_SAMPLE_COUNT = 4096` samples (N3, checked before
  the copy), a sample rate that is not finite and positive or a missing unit (N5), an `ABSTRACT_PER_INDEX` rate other
  than exactly `1`, and a `windowOffset + sampleCount` that overflows. A malformed wave is a bug of the host encoder
  that built it, as for #122's types. The array is copied once; `-0.0` samples become `+0.0`; `equals`, `hashCode` and
  `toString` are explicit (no sample dump).
- **Typed rejections are transform limits only.** `WaveDecomposer.decompose` returns the sealed
  `DecompositionResult`: `Decomposed(SpectralPattern)` or `DecompositionRejected(DecompositionRejectionReason,
  detail)`. A structurally valid wave can still fall outside what a transform can represent:
  - `SAMPLE_RATE_OUT_OF_RANGE`: the DFT frame guards of ADR 0035 (highest locator overflow, subnormal bin spacing),
    pre-checked through the same package-private `SpectralFrame` predicates so no exception escapes for host data;
  - `ENERGY_OUT_OF_RANGE`: `Σ x_n²` exceeds `SpectralPattern.MAX_SOURCE_ENERGY = Double.MAX_VALUE / 2`, or is
    nonzero but below `Double.MIN_NORMAL`. The upper bound is headroom and belongs to the pattern, so every
    decomposer (#124 included) shares it: `SpectralPattern` recomputes the retained energy from rounded magnitudes,
    which may exceed the source energy by up to the Parseval tolerance, so a finite source energy close to
    `Double.MAX_VALUE` could otherwise overflow there. This amends ADR 0035: a pattern's `sourceEnergy` must now lie
    in `[0, MAX_SOURCE_ENERGY]`. The lower bound covers nonzero samples whose
    energy is subnormal or underflows to `0`, where the Parseval tolerance can no longer be checked and
    `SpectralPattern` would reject the result. Silence (all samples `0`) is valid and decomposes to zero magnitudes.
  #124 adds its own reason (non-power-of-two length, N4) to the same enum. Rejections are equal values for equal
  waves.
- **`WaveEncoder<S>`** is a functional interface, `SampledWave encode(S source)`, implemented by hosts. It is the
  explicit seam where meaning, sample rate, unit, window, pre-filtering and provenance are decided; Neuron ships no
  implementation and no public spectral method accepts or returns a `Signal` (`SpectralCompatibilityTest`).
- **`DftWaveDecomposer`** is the stateless, thread-safe scalar reference:
  - `X_k = Σ x_n · e^{-2πi·k·n/N}` for `k = 0..floor(N/2)`, any `N` in `1..4096`, rectangular window, time origin
    at the first sample of the window.
  - Trigonometric values are `StrictMath.cos`/`StrictMath.sin` of `(2π · m) / N` with `m = (k·n) mod N`, read from a
    table of `N` entries built per call. The residue is advanced by `+k` and reduced by `-N` in exact integer
    arithmetic, so each value is the one the contract formula names, evaluated once per residue instead of once per
    product.
  - Sums run in ascending `n` in `double`; bins in ascending `k`; no concurrency, streams or hashing.
  - The DC bin and the even-`N` Nyquist bin are real sums (`Σ x_n`, `Σ (−1)^n x_n`): magnitude `|S| / N`, phase
    `0` or `π` by sign, as ADR 0035 requires of real-valued components.
  - Other bins: magnitude `2 · hypot(Re, Im) / N`, phase `SpectralPhase.wrap(atan2(Im, Re))`, stored whatever the
    magnitude.
  - The source energy is summed with samples scaled by `2^-e` (`e` the exponent of the largest `|x_n|`), the same
    technique `SpectralPattern` uses for its retained energy, through the shared package-private
    `SpectralArithmetic` helpers, so both sides of the Parseval check round alike.
  - The output arrays are handed to the pattern through a package-private owning factory instead of being copied
    again, and the samples are read from the wave without a defensive copy (both stay inside the package).
- **Aliasing is documented, not detected.** Content above `sampleRate / 2` folds onto a lower bin (N7:
  `cos(2π·7·t)` at 8 Hz reports bin 1 with magnitude 1). It is not a rejection and no heuristic (such as a
  Nyquist-energy flag) suggests otherwise; anti-alias pre-filtering is the encoder's responsibility. "Report
  consistently" is met by the fixed, tested behavior plus this documentation.

## Alternatives

- **All host-data problems as typed rejections** (a `SampledWave` factory returning a result type). Friendlier to
  hosts with noisy sources, but it splits the rule #122 established, adds a second result type and lets invalid
  waves exist as values. Hosts that expect NaN from a sensor filter before encoding.
- **Per-product trigonometry** (`StrictMath.cos` for every `k·n`). Same values, `N²` calls instead of `2N`, and
  the JDK's fdlibm port allocates a small argument-reduction buffer for most arguments, so it would also be O(N²) in
  allocation.
- **Cached twiddle tables per `N`.** Faster for repeated sizes, but shared mutable or lazily populated state in the
  reference; per-call tables are O(N) next to O(N²) work.
- **FFT.** Different summation order, so only equal within a tolerance; out of scope for v1 (contract section 1.2).
- **A Nyquist-occupancy diagnostic for aliasing.** It cannot distinguish genuine Nyquist content from folded
  content, so it would suggest a detection the samples cannot support.

## Consequences

- The DFT path of gate G1 is testable: P1 to P8, N1 to N3, N5 and N7 are unit tests with the section 12.4 tolerances,
  Parseval is checked against an independent direct DFT for `N ∈ {8, 256, 4096}`, and a seeded `N = 256` window has a
  recorded bit-level checksum that detects cross-run or cross-JVM drift.
- Cost: O(N²) time, O(N) memory and allocation. The measured baseline is in
  [wave-decomposer-reference.md](../benchmarks/wave-decomposer-reference.md): about 0.5 µs at `N = 8` and 14 ms at
  `N = 4096` on the recorded machine, about 112 bytes allocated per sample.
- `Signal`, `FrequencyState`, `ScalarResonanceMetric` and the canonical stage kinds are unchanged.

## Follow-Up

- #124 adds the Haar strategy and its rejection reason to `DecompositionRejectionReason`.
- #126 inverts `Decomposed` patterns; its round-trip fixtures (P13) run on this reference.
- An optional FFT backend, if ever added, states its tolerance against this reference and reports end-to-end figures.
