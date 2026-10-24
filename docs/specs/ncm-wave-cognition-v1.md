# NCM Wave Cognition Experiment Contract v1

**Contract identifier**: `ncm-wave-cognition/1`
**Status**: Accepted (issue #121; documentation only; experimental and opt-in)
**Parent**: Epic #120, Hierarchical Wave Cognition
**Decision record**: [ADR 0034](../adr/0034-ncm-is-hierarchical-prediction-not-the-prism.md)
**Composes, unchanged**: [ADR 0001](../adr/0001-cognition-memory-boundary.md),
[ADR 0005](../adr/0005-signals-without-identifiers.md), [ADR 0006](../adr/0006-deterministic-scalar-resonance-metric.md),
[ADR 0019](../adr/0019-typed-hypothesis-and-evidence-model.md), [ADR 0022](../adr/0022-embeddable-host-runtime-facade.md)

This document is the normative contract for the Next-Concept Modeling (NCM) wave cognition experiment. It fixes the
pipeline, the meaning of each value, who owns what, the bounds, the numerical tolerances, the test fixtures, the
falsifiable hypotheses and the stop/go gates **before** any implementation exists. It introduces no Java type, no value
of an existing type and no runtime behavior. Every type it names is a sketch marked `proposed, owned by issue #N`; the
owning issue may refine the shape, and any change to a semantic, tolerance or gate stated here must update this
document (Section 17).

```text
explicit samples
  -> WaveDecomposer (the prism)            signal analysis: samples -> spectral components
  -> SpectralPattern                       bounded, immutable, cycle-local spectral data
  -> SpectralResonanceDynamics             alignment, phase coherence, signed interference between patterns
  -> coarse-to-fine NCM                    hierarchical concept hypotheses from evidence, with abstention
  -> optional WaveSynthesizer              reconstruction of samples when the pattern is invertible
  -> optional cognitive-stage wiring       host composition inside the existing bounded cycle
```

## 1. Scope and Claims

### 1.1 What this contract covers

- The four distinct operations of the pipeline (decomposition, resonance, NCM inference, synthesis) and the boundary
  between them (Section 2).
- The architecture contract: diagram, lifecycle, API sketches and the ownership table (Section 3).
- The input schema: sampling, units, provenance and aliasing rules (Section 4).
- The transform family and the choice of reference transforms (Section 5).
- The semantics of `SpectralPattern`, `SpectralResonanceDynamics`, the hierarchical predictor and `WaveSynthesizer`
  (Sections 6 to 9), including which outputs are hypotheses rather than physical truths (Section 8.5).
- Optional cognitive-stage wiring and feedback (Section 10).
- Bounds, determinism, tie rules and fallback (Section 11).
- Positive and negative fixtures with numerical tolerances (Section 12), falsifiable hypotheses (Section 13), synthetic
  workloads and metrics (Section 14), stop/go gates (Section 15) and the issue/PR DAG (Section 16).

### 1.2 Non-goals

- No production feature code in this issue, and no Java type until its owning issue.
- No new `CognitiveStageKind`, no change to the canonical stage order, no `CycleInput` field.
- No change to `Signal`, `SignalKind`, `FrequencyState`, `ResonanceMetric` or `ScalarResonanceMetric` semantics.
- No automatic semantics inferred from arbitrary text, no generic text-to-frequency oracle, no tokenizer, no LLM, no
  prompt framework and no provider-specific model.
- No long-term memory, persisted concept store, recall or ranking inside Neuron (ADR 0001).
- No mandate to replace the single-wave path; it stays the default and the fallback.
- No FFT library, SIMD, native, GPU or incubator backend in the reference path.

### 1.3 What this experiment does not claim

- Concepts are **not** waves. No physical, biological or electromagnetic equivalence between a concept and a spectral
  component is claimed or implied.
- A frequency reported in hertz is a property of the **samples**, not of the concept they were encoded from. Its
  meaning is only what the modality encoder that produced the samples declares (Section 4.3).
- Concept labels are **host supplied ground truth**. Nothing in the pipeline discovers, names or grounds a concept;
  the predictor only proposes which known label the evidence supports.
- A good reconstruction says the transform kept the information of the samples; it says nothing about whether a
  concept prediction is right (Section 9).
- No performance figure is promised. Section 14 defines what is measured, not what will be found.

## 2. Vocabulary and the Prism Analogy

The Epic's analogy: a structured input is the incoming beam, the decomposer is the prism, the resulting components are
the spectrum, resonance studies how spectra relate, and NCM is the observer who forms hypotheses about what the
spectrum indicates. **NCM is not the prism.** The four operations stay separate types with separate owners and
separate tests.

| Operation | Input | Output | Question it answers | Owning issue |
| --- | --- | --- | --- | --- |
| Decomposition | `SampledWave` | `SpectralPattern` | Which components make up these samples? | #123 (DFT), #124 (Haar) |
| Resonance | two `SpectralPattern`s | `SpectralComparison` | How aligned, coherent or interfering are two patterns? | #125 |
| NCM inference | patterns, comparisons, a `ConceptHierarchy` | `ConceptPrediction` | Which known concept, coarse then fine, does the evidence support, or abstain? | #127 |
| Synthesis | `SpectralPattern` | `SynthesisResult` | Which samples does this pattern reconstruct, and how faithfully? | #126 |

### 2.1 Physical frequencies versus abstract features

| Kind | Example | Unit | When it may be reported |
| --- | --- | --- | --- |
| Physical frequency | DFT bin `k` of samples taken at `sampleRate` Hz | `HERTZ`, `k * sampleRate / N` | Only when the input declares `SampleUnit.HERTZ` |
| Abstract index frequency | DFT bin `k` of a sequence with no physical clock | cycles per index (per sample), `k / N`, which is `k * sampleRate / N` with the conventional `sampleRate = 1`; equivalently `k` cycles per window | When the input declares `SampleUnit.ABSTRACT_PER_INDEX` |
| Scale | Haar level `j`, position `m` | dyadic level, sample positions | Always; never converted to Hz |
| Learned feature | a weight inside a later learned predictor | none | Never reported as a frequency |

A value is reported in hertz only when it is a physical frequency in this table. A wavelet coefficient is a scale and
position, not a frequency (#124's non-goal). A learned feature is not a wave component.

## 3. Architecture Contract

### 3.1 Diagram with owners

```text
Host / modality encoder (host owned)
  -> chooses what the samples mean, the sample rate, the unit and the window       (meaning lives here)
  -> builds SampledWave(samples, sampleRate, unit, provenance)                      (#123 type)
        |
        v
Neuron, cycle-local, pure and deterministic
  WaveDecomposer.decompose(SampledWave)                 -> SpectralPattern | Rejected(reason)    (#123, #124)
  SpectralResonanceDynamics.compare(p1, p2)             -> SpectralComparison | Incompatible     (#125)
  NextConceptPredictor.predict(evidence, hierarchy, model snapshot)
                                                         -> ConceptPrediction (candidates | Abstain(reason))  (#127)
  WaveSynthesizer.synthesize(SpectralPattern)           -> SynthesisResult (EXACT | LOSSY | NOT_INVERTIBLE)  (#126)
        |
        v  optional, host composed (#128)
  existing CognitiveStage positions (no new kind) -> typed result -> later stages / ActionOutcome
        |
        v  optional, caller carried (#129)
  validated outcome -> NcmFeedback -> caller-owned model snapshot -> next cycle
        |
        v  only through existing ports
Monada Resonance Store (Store owned): persisted experience, recall, ranking
```

### 3.2 Lifecycle

1. **Construct**: the host builds `SampledWave` and, when inference is wanted, supplies a `ConceptHierarchy` and an
   immutable model snapshot. Construction validates; invalid input is rejected with a typed reason, never repaired.
2. **Decompose**: one transform per window. The result carries its basis, scale and the input provenance.
3. **Compare**: optional. Only compatible patterns (Section 7.2) are compared.
4. **Infer**: optional. Coarse level first; finer levels only when the coarser level is confident (Section 8.2).
5. **Synthesize**: optional. Only an invertible pattern reconstructs exactly.
6. **Hand off**: optional, inside the existing cycle through a typed stage result (Section 10).
7. **Discard**: every artifact is cycle-local and immutable. Neuron keeps nothing after the call or cycle returns.
   Anything durable is a caller-owned snapshot (ADR 0021 and ADR 0030 pattern) or Store experience behind a port.

### 3.3 Ownership table

| Item | Owner | Notes |
| --- | --- | --- |
| Meaning of the samples, encoder identity and version | Host / modality encoder | Neuron never infers meaning from samples |
| Sample rate, unit, window and pre-filtering | Host | Neuron validates and refuses; it does not resample or filter |
| Concept labels, hierarchy and ground truth | Host (fixture author in evaluation) | Labels are opaque ids mapped to `Proposition(domain, code)` |
| Transforms (`WaveDecomposer`, strategies) | Neuron | Pure functions, scalar references |
| `SpectralPattern` value and validation | Neuron | Cycle-local, immutable, versioned |
| `SpectralResonanceDynamics` | Neuron | Separate from `ScalarResonanceMetric`, which is unchanged |
| Hierarchical NCM inference, hypotheses, abstention | Neuron | Proposals only, never truth (Section 8.5) |
| `WaveSynthesizer` and fidelity diagnostics | Neuron | Reconstruction of samples only |
| Model snapshot (priors, transition counts, centroids) | Caller | Immutable snapshot passed in; updates through #129 only |
| Stage composition and budgets | Host through existing APIs | No new stage kind, existing `CognitiveBudget` |
| Persisted experience, recall, ranking | Monada Resonance Store | Through `ResonanceMemoryPort` only |
| Evaluation fixtures, harness and reports | `monada-neuron-evaluation` | Never in the published artifact |

### 3.4 API sketches

All sketches are illustrative Java 27 shapes, `proposed, owned by issue #N`. Records validate in their compact
constructor, copy arrays and lists, and expose no mutable state.

```java
// #123
record SampledWave(double[] samples, double sampleRate, SampleUnit unit, WaveProvenance provenance) { }
enum SampleUnit { HERTZ, ABSTRACT_PER_INDEX }
record WaveProvenance(String encoderId, int encoderVersion, String sourceReference, long windowOffset) { }

sealed interface DecompositionResult permits Decomposed, DecompositionRejected { }
interface WaveDecomposer { DecompositionResult decompose(SampledWave wave); }

// #122 (implemented; ADR 0035 records the deviations from the first sketch)
final class SpectralPattern { /* parallel primitive arrays; of(frame, sourceEnergy, provenance, arrays | List<WaveComponent>) */ }
record SpectralFrame(SpectralBasis basis, SpectralScale scale, int sampleCount, double sampleRate, SampleUnit unit) { }
enum SpectralBasis { DFT, HAAR }
record SpectralScale(int levels) { }                      // decomposition depth: DFT 0, Haar 1..12
record WaveComponent(int level, int index, double magnitude, double phase, boolean phaseDefined,
                     double locator) { }                  // locator: frequency for DFT, position for HAAR
enum LocatorUnit { HERTZ, CYCLES_PER_INDEX, SAMPLE_POSITION }

// #125
sealed interface ComparisonResult permits SpectralComparison, Incompatible { }
record BandContribution(int index, double alignment, double phaseDifference, double interference) { }
record SpectralComparison(List<BandContribution> bands, double aggregate, double coherence) { }

// #127
record ConceptNode(int ordinal, long conceptCode, int parentOrdinal, int depth) { }
record ConceptHierarchy(int domain, List<ConceptNode> nodes) { }
sealed interface ConceptPrediction permits ConceptCandidates, ConceptAbstention { }
record ConceptCandidate(int conceptOrdinal, int depth, double score, double margin, List<Integer> supportingComponents) { }
enum AbstentionReason { LOW_CONFIDENCE, LOW_MARGIN, INCOMPATIBLE_INPUT, UNKNOWN_CONCEPT, BUDGET_EXHAUSTED }

// #126
record SynthesisResult(SynthesisStatus status, double[] samples, double retainedEnergyFraction,
                       OptionalDouble maxAbsoluteError, OptionalDouble relativeEnergyError) { }
enum SynthesisStatus { EXACT, LOSSY, NOT_INVERTIBLE }
```

`FrequencyState` is not extended. A projection from a `SpectralPattern` to one `FrequencyState` (for example, the
dominant component) may be offered by #125 (deferred from #122, ADR 0035) as an explicit, documented, lossy helper so
the single-wave path can be compared as a control (H2); it is never implicit.

## 4. Input Schema

### 4.1 `SampledWave` (proposed, owned by issue #123)

| Field | Rule |
| --- | --- |
| `samples` | Copied `double[]`; length `1..maxSamples`; every value finite; NaN and infinities are rejected |
| `sampleRate` | Finite and `> 0`; for `ABSTRACT_PER_INDEX` it must be exactly `1` (one sample per index), so locators are in cycles per index |
| `unit` | `HERTZ` or `ABSTRACT_PER_INDEX`; there is no default |
| `provenance.encoderId` | Opaque, non-blank, at most 128 UTF-16 chars (`String.length()`), the same unit and value as `ResonanceMemoryResult.MAX_REFERENCE_LENGTH` (ADR 0019) |
| `provenance.encoderVersion` | `>= 1` |
| `provenance.sourceReference` | Opaque, bounded like `encoderId`; never a payload, text or file content |
| `provenance.windowOffset` | `>= 0`, index of the first sample in the host's stream |

The samples are uniformly spaced by construction: `SampledWave` has no timestamps. Non-uniform data must be resampled
by the host before it enters Neuron, and the host reports that in `provenance`.

### 4.2 Sampling and aliasing

- DFT bins `k = 0..floor(N/2)` are reported (single-sided). Bin `k` locates at `k * sampleRate / N`.
- The Nyquist limit is `sampleRate / 2`. Content above it **aliases** and cannot be detected from the samples
  (fixture N7). Pre-filtering is the host's responsibility and is recorded in provenance.
- The DC component (`k = 0`) is reported like any other bin; its phase is `0` or `π` by sign. For even `N` the
  Nyquist bin (`k = N/2`) is real for real input too, so its phase is also `0` or `π`; the decomposer computes both
  bins as real sums, and `SpectralPattern` rejects any other phase for them (ADR 0035).
- There is no implicit windowing (rectangular window). Spectral leakage for non-integer cycle counts is expected and is
  covered by fixture P7, not hidden.
- Two patterns with different `sampleRate`, `unit`, `sampleCount` or basis are incompatible (Section 7.2). Neuron never
  interpolates or resamples to make them comparable.

### 4.3 Where meaning comes from

The samples mean only what the encoder declares. An encoder that turns, for example, a feature trace into a sequence of
numbers is a host adapter and owns that mapping. In the evaluation (Section 12.3) the encoders are synthetic generators with a
documented formula and seed, so the "meaning" of each fixture is known exactly. Arbitrary text never enters the
pipeline as text.

## 5. Transform Family

### 5.1 Comparison

| Family | What it yields | Invertible | Reference cost | Determinism | Role in v1 |
| --- | --- | --- | --- | --- | --- |
| Direct DFT | Magnitude and phase per frequency bin over the whole window | Yes (inverse DFT) | O(N²) time, O(N) memory | Bit-reproducible with `StrictMath` and fixed summation order | **Reference** (#123) |
| FFT (radix 2, mixed radix) | Same values as DFT | Yes | O(N log N) | Reproducible only within tolerance of the DFT (different summation order) | Later optional backend, checked against the DFT; not in v1 |
| Haar wavelet (orthonormal) | Approximation and detail per dyadic level and position; time and scale localized | Yes (inverse Haar), for power-of-two N | O(N) | Bit-reproducible | **Multiresolution strategy** (#124) |
| Other wavelets (Daubechies, etc.) | Smoother scale localization | Yes, with boundary handling | O(N · filter length) | Depends on boundary rule | Out of scope for v1 |
| Phase coupling, PLV-style | Consistency of phase differences between two patterns | Not a transform | O(bands) | Bit-reproducible | Measure inside #125 |
| Coupled oscillators (Kuramoto) | Synchronization dynamics over time | Not a transform | O(steps · oscillators²) | Depends on the integrator | Out of default scope (#125 non-goal) |

### 5.2 Decision

- **DFT reference** (#123): `X_k = Σ_{n=0}^{N-1} x_n · e^{-2πi·k·n/N}`, computed in `double` with `StrictMath.cos` and
  `StrictMath.sin` of the reduced angle `2π·((k·n) mod N)/N`, summed in ascending `n`. Any length `1..maxSamples` is
  valid; no power-of-two requirement.
- **Haar strategy** (#124): orthonormal Haar, `a = (x_{2m} + x_{2m+1}) / √2`, `d = (x_{2m} − x_{2m+1}) / √2`, applied
  to the approximation of each level, levels `1..L` with `L ≤ log2 N`. Requires `N` a power of two; any other length is
  rejected (fixture N4), never padded silently.
- Neither strategy is the default for existing hosts. Choosing one is a host decision.

## 6. `SpectralPattern` (implemented by issue #122)

Implemented in `monada.neuron.spectral`; [ADR 0035](../adr/0035-compact-immutable-spectral-pattern.md) records the
representation: a compact final class over parallel primitive arrays, a `SpectralFrame` for basis and sampling
metadata, `SpectralScale(levels)` as the decomposition depth, Haar level `0` for the final approximation and levels
`1..L` for the details (so the approximation and the level-`L` detail never share a key), and `phaseDefined` and the
locator derived on access. `SampleUnit` and `WaveProvenance` are delivered by #122 and reused by #123.

- **Immutable and bounded**: at most the component bound of its basis (`maxComponentsDft` or `maxComponentsHaar`,
  Section 11), component arrays copied once at construction, sorted by `(level, index)`, no duplicate `(level, index)`.
- **Versioned**: `version` is the pattern schema version, `1` for this contract.
- **Normalization, DFT**: magnitude `|X_k| / N` for `k = 0` and, when `N` is even, `k = N/2`; `2·|X_k| / N` otherwise.
  A pure cosine of amplitude `A` at an integer bin therefore reports magnitude `A`.
- **Normalization, Haar**: coefficients are the orthonormal values themselves; `magnitude = |c|`, `phase = 0` for
  `c ≥ 0` and `π` for `c < 0`. Energy is preserved (fixture P9). A Haar phase other than `0` or `π` is rejected.
- **Phase**: `atan2(Im, Re)` wrapped to `(−π, π]` (a result of `−π` becomes `π`, and `−0.0` becomes `0.0`, so equal
  patterns are equal values). The computed phase is **always stored**, whatever the
  magnitude, because synthesis needs it. `phaseDefined` is only a comparison-validity flag, derived on access and never
  stored: it is `false` when the
  magnitude is below `phaseEpsilon` (`1e-12` relative to the largest magnitude of the pattern, or absolute when that
  is 0), and the coherence of Section 7.3 and NCM phase evidence ignore such phases. Interference, the aggregate and synthesis
  use the stored phase and never read the flag.
- **Locator**: for DFT, `k * sampleRate / N`: hertz for `HERTZ`, cycles per index for `ABSTRACT_PER_INDEX` (Section 2.1); sample position of the coefficient's support start
  for Haar.
- **Source energy**: `sourceEnergy = Σ x_n²` of the decomposed samples, finite and `≥ 0`, set by the decomposer and
  carried unchanged by any truncation (top-K, thresholding) of the pattern. It is what makes the retained-energy
  diagnostic of Section 9 computable without the original samples. The energy a pattern retains is, for DFT,
  `N · (m_0² + Σ_{0<k<N/2} m_k² / 2 + m_{N/2}²)` over its retained normalized magnitudes (the Nyquist term only for
  even `N`), and for Haar `Σ c²` over its retained coefficients; for a complete pattern it equals `sourceEnergy`
  within the Parseval tolerance of Section 12.4, and a complete pattern (every key of its frame present) that does not
  is rejected. Any pattern whose retained energy exceeds `sourceEnergy` by more than that relative tolerance (`1e-9`)
  is rejected too. For (near-)subnormal energies, where the relative tolerance is smaller than rounding, the allowance
  is `(sampleCount + componentCount + 4)` ULPs at the energy scale instead; a `sourceEnergy` of `0` (silent samples)
  admits only zero magnitudes. The retained energy is summed with magnitudes scaled by a power of two, so a representable subnormal
  energy is not lost to squaring underflow.
- **Compact**: a later optimized layout (parallel primitive arrays, as `FrequencyStateBatch` does) is allowed behind the
  same value semantics; the public record stays the reference.

## 7. `SpectralResonanceDynamics` (proposed, owned by issue #125)

### 7.1 Separation from the scalar metric

`SpectralResonanceDynamics` is a new, explicitly named mechanism. `ResonanceMetric` and `ScalarResonanceMetric`
(ADR 0006) are not changed, not wrapped and not replaced. The scalar metric remains the control for H2.

### 7.2 Compatibility

Two patterns are comparable only when `version`, `basis`, `scale`, `sampleCount`, `sampleRate` and `unit` are equal.
Otherwise the result is `Incompatible(reason)`. There is no interpolation, padding or rate conversion.

### 7.3 Reference quantities

Components are matched by their `(level, index)` key. A band `k` is **shared** when both patterns retain it; with
magnitudes `m1_k`, `m2_k` and stored phases, `Δφ_k` is the stored phase difference wrapped to `(−π, π]`. Sums run over
shared bands; `‖m1‖` and `‖m2‖` are the Euclidean norms over **all** components each pattern retains, so a band one
pattern dropped lowers the aggregate instead of being ignored:

```text
interference_k = m1_k · m2_k · cos(Δφ_k)                         signed; may be negative
alignment_k    = min(m1_k, m2_k) / max(m1_k, m2_k), 0 when both are 0
aggregate      = (1 + Σ_k interference_k / (‖m1‖ · ‖m2‖)) / 2     in [0, 1]; 0 when either norm is 0
coherence      = |Σ_k w_k · e^{iΔφ_k}| / Σ_k w_k,  w_k = m1_k · m2_k, over shared bands whose phases are both defined
                                                                  in [0, 1]; 0 when Σ w_k = 0
```

The aggregate is the normalized real part of the spectral
cross-correlation mapped to `[0, 1]`; it is symmetric. Negative interference is a diagnostic of destructive phase
relation and is **never** written to the Store as a score. #125 may add weights per band; each must be documented and
default to the reference above.

## 8. Hierarchical NCM (proposed, owned by issue #127)

### 8.1 Concept hierarchy

`ConceptHierarchy` is a host-supplied rooted forest of labeled concepts: `ordinal` is the index in the list (stable,
`0..maxConcepts−1`), `conceptCode` is the host's opaque label, `parentOrdinal` is `−1` for a root, `depth` is `0` for a
root and at most `maxDepth − 1`. Parents precede children in the list; the structure is validated acyclic. A concept is
emitted as `Proposition(domain, conceptCode)` (ADR 0019).

### 8.2 Coarse-to-fine prediction

The predictor reads an immutable caller-owned model snapshot (#127 defines its content; the minimal reference is a
per-concept spectral centroid plus a concept-transition count table, both learned only from labeled training
sequences), the current pattern evidence and optionally the previous concept. At evaluation time the previous
concept is the predictor's **own** prediction for the preceding window, or absent at the start of a sequence; it is
never a ground-truth label of a test window (Section 12.3).

1. Score every depth 0 concept.
2. Select the best; if `score < τ_conf` or `score − secondScore < τ_margin` at **depth 0**, the result is
   `ConceptAbstention` with `LOW_CONFIDENCE` or `LOW_MARGIN`, keeping the candidates as diagnostics.
3. Otherwise descend into the selected concept's children and repeat, until a leaf or `maxDepth`.
4. When the test fails at a depth `d > 0`, the result is `ConceptCandidates` at the deepest confident level `d − 1`
   (a coarse-only prediction), not an abstention. Scoring is per depth: a coarse-only prediction counts as an answer at
   the depths it reaches and as not covered at deeper depths. H3 and H6 are scored at the leaf depth, so a coarse-only
   prediction counts there as not covered.

"Next concept" means the concept the evidence of the **next** window is predicted to support, given the current
evidence and the previous concept; the fixtures (Section 12.3) define the label of each next window.

### 8.3 Scores, uncertainty and calibration

- Scores are finite and in `[0, 1]`; the margin is the difference to the runner-up at the same depth.
- Calibration is **measured, not assumed**: the evaluation reports reliability bins and expected calibration error
  (Section 14.3). A score is not called a probability unless calibration on held-out data supports it.
- `supportingComponents` lists the component indices that contributed, so every candidate is explainable.

### 8.4 Ties and abstention

- Ranking: score descending, then lower `conceptOrdinal`. No hash order is used anywhere.
- Abstention reasons: `LOW_CONFIDENCE`, `LOW_MARGIN`, `INCOMPATIBLE_INPUT` (pattern does not match the snapshot's
  basis), `UNKNOWN_CONCEPT` (previous concept not in the hierarchy), `BUDGET_EXHAUSTED` (candidate or level bound
  reached). An abstention is an expected value, not an exception.
- Thresholds `τ_conf` and `τ_margin` are snapshot parameters, chosen on training data only, and reported.

### 8.5 Hypotheses, not truths

| Output | Status |
| --- | --- |
| Spectral components of the samples | Mathematical fact about the samples within tolerance (H1), not about the concept |
| Resonance aggregate, coherence, interference | Measured relation between two patterns; a similarity, not an identity |
| Coarse and fine concept candidates | **Hypotheses** with evidence and score; never asserted as true |
| Abstention | A claim of insufficient evidence |
| Reconstructed samples | Reconstruction of the input samples, not generation of a concept |
| Calibration figures | Empirical properties of one fixture suite, not general guarantees |

When integrated (Section 10) candidates become `Hypothesis` values scored downstream by the existing evaluation policy
(ADR 0020); the predictor's own score may enter only as an explicitly configured resonance-like support component.

## 9. `WaveSynthesizer` (proposed, owned by issue #126)

- `EXACT` is decided structurally: an inverse DFT from a complete single-sided DFT pattern (all bins `0..floor(N/2)`
  present), with the conjugate-symmetric reconstruction for real input, or an inverse Haar from a complete multilevel
  Haar pattern (every detail level and the final approximation present). Its reconstruction error is bounded by the
  round-trip tolerance of Section 12.4, which fixtures P13 and P14 verify against the original samples. Synthesis
  uses every stored phase, including those flagged `phaseDefined = false` (Section 6), so many small components cannot
  accumulate an error that zeroed phases would introduce.
- A pattern with dropped components (top-K or thresholded) returns `LOSSY`. It never reports `EXACT`.
- Every result reports `retainedEnergyFraction`, the retained energy (Section 6) divided by `sourceEnergy`, or `1` when
  `sourceEnergy` is `0`. It is computable from the pattern alone.
- `maxAbsoluteError` and `relativeEnergyError` are computed against the original samples only when the caller supplies
  them; otherwise they are reported as unavailable (empty `OptionalDouble`), never estimated.
- A pattern that is incomplete in a way the reference cannot invert (for example, a Haar pattern missing its final
  approximation) returns `NOT_INVERTIBLE` with no samples.
- Synthesis reconstructs samples. It does not generate images, audio, text or concept semantics (#126 non-goal).

## 10. Cognitive-Stage Wiring and Feedback

### 10.1 Stage wiring (proposed, owned by issue #128)

- Opt-in host composition inside existing positions: for example a `REASONING` stage that obtains its `SampledWave`
  windows from a host resolver keyed by the request-scoped `HostExecutionContext` (ADR 0023), the same pattern the
  routing reasoning adapter uses for its inputs, runs decomposition and prediction, and returns a
  `ReasoningCognitiveStageResult` with the hypotheses. Samples never travel as `Signal` values; any input signals the
  stage receives are ordinary cycle signals, not encoded samples or provenance.
- No new `CognitiveStageKind`, no static registry, no payload map on `Signal`. `Signal` stays identity-free (ADR 0005);
  spectral data travels only in typed stage results, never inside a `Signal`.
- Budgets: the stage honors `CognitiveBudget` (ADR 0009), `HypothesisLimits` (ADR 0019) and this contract's bounds; a
  bound reached produces `BUDGET_EXHAUSTED` or a `LIMIT_REACHED` stage status, never an unbounded loop.
- Evidence: `SignalEvidence` references accepted signal occurrences. If spectral components need their own evidence
  variant, #128 records that change to the sealed `Evidence` hierarchy in an ADR.
- A runtime that does not opt in behaves exactly as before; existing tests are the regression check (gate G4).

### 10.2 Feedback (proposed, owned by issue #129)

Outcome-driven updates follow the cross-cycle rule of ADR 0021: an outcome produces an explicit feedback artifact the
caller carries forward; the caller applies it to its own model snapshot, producing a new immutable snapshot. No hidden
online training, no mutation of the cycle that produced the outcome, no feedback derived from the predictor's own
unvalidated output, and every update is reversible by keeping the previous snapshot.

## 11. Bounds, Determinism and Fallback

### 11.1 Proposed bounds

Each bound is a proposal owned by the named issue, which may lower it or justify a change; none is a promise of
performance.

| Bound | Proposed value | Rationale | Owner |
| --- | --- | --- | --- |
| `maxSamples` | 4,096 | Largest workload (Section 14); direct DFT at 4,096 is about 16.8 M multiply-adds per window | #123 |
| `maxComponentsDft` | 2,049 | `floor(maxSamples/2) + 1` single-sided bins | #122 |
| `maxComponentsHaar` | 4,096 | `maxSamples`: a complete Haar pattern of `N` samples has `N` coefficients at any level count | #122 |
| `maxLevels` (Haar) | 12 | `log2 4096` | #124 |
| `maxConcepts` | 256 | Largest workload | #127 |
| `maxDepth` | 3 | Largest workload | #127 |
| `maxCandidatesPerLevel` | 16 | Bounded diagnostics per depth | #127 |
| `maxPatternsPerComparisonBatch` | 1,024 | Largest workload | #125 |

### 11.2 Determinism

- Same inputs and snapshot give bit-identical outputs in the reference implementations.
- `StrictMath` for trigonometry and roots; fixed summation order; no `parallel()` streams, no concurrency, no hash-order
  iteration in the reference.
- Ties are broken by explicit ordinals (Sections 6 and 8.4).
- Inputs are validated at construction: NaN, infinities, negative magnitudes, empty arrays and out-of-bound sizes are
  rejected with a typed reason or `IllegalArgumentException` according to whether they are expected values or caller
  bugs; #122 and #123 fix that split.

### 11.3 Fallback

The single-wave path (`Signal`, `FrequencyState`, `ScalarResonanceMetric`, existing stages) is the default and the
fallback. A host that does not opt in, or whose experiment fails a gate, keeps it unchanged. Optimized FFT, SIMD,
native or GPU backends are out of scope for v1; if later added, they are checked against these scalar references
within a documented tolerance.

## 12. Test Fixtures and Tolerances

`t_n = n / sampleRate`. Unless stated, `N = 8`, `sampleRate = 8`, unit `HERTZ`, so bin `k` locates at `k` Hz.

### 12.1 Positive fixtures

| Id | Input | Expected | Owner |
| --- | --- | --- | --- |
| P1 | DC: `x_n = 3` | bin 0 magnitude 3, phase 0; all other magnitudes 0 | #123 |
| P2 | `cos(2π·1·t)` | bin 1 magnitude 1, phase 0; others 0 | #123 |
| P3 | `sin(2π·1·t)` | bin 1 magnitude 1, phase `−π/2` | #123 |
| P4 | `cos(2π·1·t) + 0.5·cos(2π·3·t + π/4)` | bin 1 magnitude 1, phase 0; bin 3 magnitude 0.5, phase `π/4` | #123 |
| P5 | Nyquist: `x_n = (−1)^n` | bin 4 magnitude 1, phase 0 | #123 |
| P6 | Parseval on P4 and on seeded random `N = 256`, `N = 4096` | `Σ x_n² = (1/N) Σ_{k=0}^{N-1} |X_k|²` within 12.4 | #123 |
| P7 | Leakage: `cos(2π·1.5·t)` | energy spread over several bins; Parseval still holds; no bin claims 1.5 Hz | #123 |
| P8 | Odd length `N = 7`, `cos(2π·2·n/7)` | bin 2 magnitude 1; no Nyquist bin | #123 |
| P9 | Haar of `[4, 2, 5, 5]`, 2 levels | approximation `[8]`, details level 2 `[−2]`, level 1 `[√2, 0]`; energy 70 preserved | #124 |
| P10 | Identical patterns (P4 with itself) | aggregate 1, coherence 1, every interference `m_k² ≥ 0` | #125 |
| P11 | P2 against P2 shifted by `π` | interference bin 1 = −1, aggregate 0 | #125 |
| P12 | P2 against `cos(2π·1·t + π/2)` | interference 0, aggregate 0.5, coherence 1 | #125 |
| P17 | P4 against P4 with bin 3 dropped | shared bin 1 interference 1; aggregate `(1 + 1/√1.25) / 2 ≈ 0.947214` | #125 |
| P13 | DFT → inverse DFT round trip on P1–P5, P8 and seeded random `N ∈ {8, 256, 4096}` | `EXACT` within 12.4 | #126 |
| P14 | Haar → inverse Haar round trip, seeded random `N ∈ {8, 256, 4096}` | `EXACT` within 12.4 | #126 |
| P15 | Hierarchy fixture of Section 12.3, clean evidence | correct coarse and fine concept, deterministic order | #127 |
| P16 | Two concepts with equal scores | lower `conceptOrdinal` first | #127 |

### 12.2 Negative fixtures

| Id | Input | Expected | Owner |
| --- | --- | --- | --- |
| N1 | Empty sample array | rejected | #123 |
| N2 | Any sample NaN, `+∞` or `−∞` | rejected | #123 |
| N3 | Length `maxSamples + 1` | rejected | #123 |
| N4 | Haar with `N = 6` | rejected, not padded | #124 |
| N5 | `sampleRate ≤ 0`, NaN or infinite; missing unit | rejected | #123 |
| N6 | Compare patterns with `sampleRate` 8 and 16, or DFT with Haar | `Incompatible`, never interpolated | #125 |
| N7 | Aliasing: `cos(2π·7·t)` at `sampleRate = 8` | reported as bin 1 magnitude 1; documented as undetectable from samples, not an error | #123 |
| N8 | Drop bin 1 of P4, then synthesize with the original P4 samples supplied | `LOSSY`, never `EXACT`; `retainedEnergyFraction` 0.2; `maxAbsoluteError` and `relativeEnergyError` present, finite and positive | #126 |
| N9 | Previous concept code not in the hierarchy | `Abstain(UNKNOWN_CONCEPT)` | #127 |
| N10 | Evidence equidistant from two concepts | `Abstain(LOW_MARGIN)` at depth 0 | #127 |
| N11 | Random-noise evidence on the trained hierarchy | abstention rate reported; any confident prediction counted as an error | #127, #130 |
| N12 | Hierarchy with a cycle, a child before its parent, or depth over `maxDepth` | rejected | #127 |
| N13 | Opt-out runtime with the same inputs as before | outputs identical to `main` | #128 |

### 12.3 Concept fixture (labels and ground truth)

Synthetic, seeded and generated in the evaluation module. The generator is delivered by #125, the first issue whose gate
needs it (G2); #127 and #130 reuse it unchanged, and #127 also uses a small copy for unit tests.

- **Hierarchy**: depth 0 categories `LOW_BAND` (code 1) and `HIGH_BAND` (code 2); depth 1 concepts `LOW_PURE` (11),
  `LOW_CHORD` (12) under `LOW_BAND`, `HIGH_PURE` (21), `HIGH_CHORD` (22) under `HIGH_BAND`. Domain is a fixture constant.
- **Generator**: `N = 256`, `sampleRate = 256` Hz. `LOW_*` dominant tone in 4–16 Hz, `HIGH_*` in 64–80 Hz; `*_PURE` one
  tone, `*_CHORD` the dominant tone plus a second at a fixed ratio 3/2 (at most 120 Hz). Every generated tone stays
  below the 128 Hz Nyquist limit; the generator rejects any parameter set that would exceed it. Amplitude, phase and exact frequency drawn
  from seeded ranges; additive Gaussian noise at documented SNRs (for example 20, 10 and 0 dB).
- **Sequences**: windows follow a seeded first-order transition table over the four leaf concepts. The ground-truth
  label of a window is the concept that generated it. The "next concept" label of window `w` is the generator label of
  window `w + 1`.
- **Splits**: disjoint seeds for training, threshold selection and held-out test. Labels of the test split are never
  read during training, threshold selection, feedback **or inference**: no predictor or control receives a test label
  as input, including as the previous concept. Test labels are read only by the scorer. A leakage check (as in the HTML protocol) fails the run.

### 12.4 Numerical tolerances

| Check | Tolerance | Scope |
| --- | --- | --- |
| Exact-bin magnitude and phase (P1–P5, P8) | absolute `1e-12` | `N ≤ 64`, amplitudes in `[0, 10]` |
| Off-bin magnitudes expected to be 0 | absolute `1e-12 · max(1, Σ|x_n|)` | `N ≤ 64` |
| Parseval identity | relative `1e-9` of `Σ x_n²` | `N ≤ 4096`, samples in `[−10, 10]` |
| Round trip (P13, P14) | max absolute error `≤ 1e-9 · max(1, max|x_n|)` | `N ≤ 4096` |
| Haar exact values (P9) | absolute `1e-12` | as listed |
| Resonance values (P10–P12, P17) | absolute `1e-12` | `N ≤ 64` |
| Phase defined | magnitude `≥ 1e-12` relative to the pattern maximum | all |

Tolerances are documented per scale, not universal. An optimized backend later states its own tolerance against these
references. Determinism checks (same input, same output) are exact equality.

## 13. Falsifiable Hypotheses

Each hypothesis is evaluated on the held-out split of Section 12.3, with seeded repeats and confidence intervals, and
each is refuted by the stated observation.

| Id | Hypothesis | Refuted when | Gate |
| --- | --- | --- | --- |
| H1 | The DFT reference decomposer recovers the generating components of synthetic signals within Section 12.4 | any positive or negative fixture owned by #123 fails, or Parseval fails | G1 |
| H1h | The Haar strategy preserves energy and matches its exact values within Section 12.4 | any fixture owned by #124 fails | G1h |
| H1s | The reference synthesizers invert complete patterns within Section 12.4 and never report a truncated one as `EXACT` | P13 or N8 fails (DFT path); P14 fails (Haar path, evaluated only when G1h is go) | G1s |
| H2 | Spectral resonance separates same-concept from different-concept window pairs better than `ScalarResonanceMetric` on the dominant-component `FrequencyState` projection | the AUC of the spectral aggregate does not exceed the scalar control's AUC by the pre-registered margin | G2 |
| H3 | Hierarchical NCM predicts the next concept better than baseline A (no spectral input) on the windows it answers, with enough coverage | C's leaf accuracy on the windows C covers is not above A's accuracy on the **same** windows by the pre-registered margin with the interval excluding 0, or C's leaf coverage is below the pre-registered minimum coverage | G3 |
| H4 | Coarse-to-fine prediction beats flat prediction over the leaf concepts with the same features and scoring inputs | control C-flat, with its threshold set on the threshold-selection split to reach C's leaf coverage, matches or beats C's leaf accuracy on covered windows at equal work budget | G3 (informational) |
| H5 | Validated outcome feedback improves H3's metric on later held-out windows without label leakage | no improvement, or any leakage detected | G5 |
| H6 | Abstention is informative: C's leaf accuracy on covered windows exceeds the accuracy of C's forced top-1 leaf answer on all windows (abstention and coarse-only results replaced by the top-ranked leaf candidate, never scored as errors), and N11 abstains more than clean evidence | either inequality fails | G3 |

Each margin is pre-registered by the issue whose gate evaluates it, **before** held-out results are computed: H2 by
#125, H3, H4 and H6 by #127, H5 by #129. Pre-registration means the margin is committed to that issue's benchmark document
in a commit that precedes the first commit containing held-out results, and it is recorded in the report. #130 reuses
the registered margins and adds none for H2 to H6. Changing a margin after seeing results requires a new protocol version and is reported.

## 14. Workloads, Controls and Metrics

### 14.1 Synthetic workloads

| Workload | Samples per window | Windows | Concepts | Depth | Purpose |
| --- | --- | --- | --- | --- | --- |
| tiny | 8 | 1 | 2 | 1 | Exact fixtures, determinism |
| medium | 256 | 64 per sequence, 32 sequences | 4 to 16 | 2 | Section 12.3 fixture, main quality evidence |
| large | 4,096 | 1,024 | 256 | 3 | Bounds, latency, allocation and memory behavior |

### 14.2 Controls (named as in #130; delivered by the gate that needs them)

- **A**: no spectral input (the no-NCM baseline). It predicts, for every test window, the most frequent next concept of
  the training split, ties to the lower `conceptOrdinal`; it reads no test input and always answers. Because A has no
  confidence score, H3 compares it with C paired on the windows C covers, plus a pre-registered minimum coverage.
  Delivered by #127 for G3.
- **A-oracle** (informational only): the training transition table applied to the **true** label of the current window.
  It reads a test label, so it is reported as a labelled upper reference and never used by a gate.
- **B**: spectral-only nearest centroid, flat over leaf concepts, no resonance and no hierarchy. Delivered by #127 for
  G3.
- **C**: DFT plus resonance plus hierarchy, the system under test at G3. Delivered by #127.
- **C-flat**: C with the hierarchy removed: the same features (including resonance, or without it exactly when C runs
  without it), the same scoring inputs and thresholds rule, flat over the leaf concepts, at the same work budget. It is
  the control for H4, so that comparison changes only the hierarchy. Delivered by #127. B remains the spectral-only
  baseline and is not used to evaluate H4.
- **D**: C with Haar and coherence features. Delivered by #130.
- **E**: C or D with validated feedback. Delivered by #129 for G5, reused by #130.
- Scalar control for H2: `ScalarResonanceMetric` on the dominant-component projection. Delivered by #125 for G2.

Each gate's evidence is therefore produced before the gate is evaluated; #130 consolidates every control over all
workloads, adds D and the large-workload resource measurements, and publishes the final report.

### 14.3 Metrics

- **Fidelity**: per-fixture maximum absolute error, relative energy error, Parseval residual.
- **Prediction**: top-1 and top-k accuracy at depth 0 and leaf depth; selective accuracy and coverage; risk-coverage
  curve.
- **Abstention**: abstention rate, precision and recall of abstention against windows the model gets wrong.
- **Calibration**: reliability bins and expected calibration error on the held-out split.
- **Resources**: latency per window and per sequence (JMH, stated warm-up and forks), allocation per operation (JMH
  `-prof gc`), retained snapshot size; end to end, not kernel only.

### 14.4 Required report fields

Contract version `ncm-wave-cognition/1`, fixture generator version and seeds, split seeds, SNR levels, workload sizes,
pre-registered margins, thresholds `τ_conf` and `τ_margin` with how they were chosen, JDK vendor and version, OS,
architecture, CPU model, available processors, heap and GC settings, JMH settings, Neuron commit (`+dirty` when
uncommitted), and the determinism check result. Negative results are reported with the same prominence as positive ones.

## 15. Stop/Go Gates

Gates are evaluated in order. A failed gate stops the dependent work and is published as a result; it is not worked
around by widening scope.

| Gate | After | Go when | On failure |
| --- | --- | --- | --- |
| G0 | #121 | This contract and ADR 0034 are merged | Children do not start |
| G1 | #123 | H1 holds; all P and N fixtures owned by #123 pass; determinism is exact | Stop; fix the transform or record a negative result; no #125–#127 |
| G1h | #124 | H1h holds; determinism is exact | Haar is excluded: #126 inverts DFT patterns only, control D is dropped, and the report says so; G2 and G3 are unaffected |
| G1s | #126 | H1s holds for each synthesis path in scope (DFT always; Haar only when G1h is go); determinism is exact | Evaluated per path: a failing path stays out of #128, which integrates with the passing path or without synthesis, and the report says so; G2 and G3 are unaffected |
| G2 | #125 (with the generator and scalar control) | H2 holds | Spectral resonance stays a diagnostic only; #127 may still proceed without resonance features, and the report says so |
| G3 | #127 (with controls A, B, C and C-flat) | H3 and H6 hold on the medium workload | **Stop before #128**; publish the negative result; no cycle integration |
| G4 | #128 | All existing tests pass unchanged; N13 holds; budgets honored | Revert to library-only use; no default change |
| G5 | #129 (H5 with control E), #130 | H5 holds or is reported negative; #130 publishes the go/no-go report with every control | The report's decision is binding for further investment |

The Epic may end after any gate with a documented negative result. That is a valid outcome of the experiment.

## 16. Issue and PR DAG

Each issue is one focused PR under 40 changed files, starting from an updated `main` that contains all of its
prerequisites. The graph is acyclic; no issue builds on a descendant.

```text
#121 -> #122
#122 -> #123
#122, #123 -> #124      (independent of G1; its own gate is G1h)
#122, #123 -> #125      (and gate G1 = go)
#123, #124 -> #126      (and gate G1 = go; Haar inversion only if G1h = go)
#122, #125 -> #127
#126, #127 -> #128      (and gate G3 = go; G1s decides only whether synthesis is wired in)
#127, #128 -> #129
#123 .. #129 -> #130
```

| Issue | Delivers | Prerequisites | Main area | Gate served |
| --- | --- | --- | --- | --- |
| #121 | This contract, ADR 0034 | none | `docs/` | G0 |
| #122 | `SpectralPattern`, `WaveComponent`, `SpectralBasis`, `SpectralScale`, provenance types | #121 | new spectral package in core | G1, G1h prerequisite |
| #123 | `SampledWave`, DFT `WaveDecomposer` reference | #122 | spectral package | G1 |
| #124 | Haar strategy | #122, #123 | spectral package | G1h |
| #125 | `SpectralResonanceDynamics`; concept fixture generator, scalar control and H2 evaluation | #122, #123, G1 = go | `monada.neuron.resonance` (new types only), `monada-neuron-evaluation` | G2 |
| #126 | `WaveSynthesizer`, fidelity diagnostics | #123, #124, G1 = go | spectral package | G1s |
| #127 | `ConceptHierarchy`, `NextConceptPredictor`; controls A, B, C, C-flat and the H3, H4, H6 evaluation | #122, #125 | `monada.neuron.reasoning` (new types only), `monada-neuron-evaluation` | G3 |
| #128 | Opt-in cycle integration, typed hand-off | #126, #127, G3 go | stages, host wiring | G4 |
| #129 | Feedback and caller-owned snapshots; control E and the H5 evaluation | #127, #128 | `monada.neuron.evolution`, `monada-neuron-evaluation` | G5 |
| #130 | Consolidated ablation over all workloads, control D, large-workload resources, go/no-go report | #123 to #129 | `monada-neuron-evaluation` | G5 |

The evaluation pieces assigned to #125, #127 and #129 are scope additions relative to those issues' current text; each
issue is updated to cite this table before it starts. #124 and #125 may proceed in parallel after #123 (#125 once G1 is go); #126 and #127 may proceed in parallel after their prerequisites.

## 17. Versioning and Deviations

- `ncm-wave-cognition/1` is this document. A **minor** revision adds clarification, a fixture or a report field without
  changing any semantic. Changing a formula, a normalization, a tolerance, a bound's owner, a hypothesis, a margin rule or
  a gate is a **major** revision (`/2`).
- A child issue whose implementation diverges updates this document in the same PR, or records an ADR, and reports the
  deviation in its PR description. A silent mismatch between code and this contract is a defect (`AGENTS.md`).
- Proposed bounds (Section 11.1) may be lowered by their owner as a minor revision; raising one needs measured evidence.

## 18. References

- Epic #120 and child issues #122 to #130.
- [ADR 0034](../adr/0034-ncm-is-hierarchical-prediction-not-the-prism.md): NCM is hierarchy and prediction, not the prism.
- [ADR 0001](../adr/0001-cognition-memory-boundary.md): cognition and memory boundary.
- [ADR 0005](../adr/0005-signals-without-identifiers.md): signals without identifiers.
- [ADR 0006](../adr/0006-deterministic-scalar-resonance-metric.md): deterministic scalar resonance metric.
- [ADR 0009](../adr/0009-bounded-ephemeral-cognitive-context.md): bounded ephemeral cognitive context.
- [ADR 0019](../adr/0019-typed-hypothesis-and-evidence-model.md): typed hypothesis and evidence model.
- [ADR 0020](../adr/0020-deterministic-hypothesis-evaluation-policy.md): deterministic hypothesis evaluation policy.
- [ADR 0021](../adr/0021-cross-cycle-outcome-feedback-handoff.md): cross-cycle outcome feedback handoff.
- [ADR 0022](../adr/0022-embeddable-host-runtime-facade.md): embeddable host runtime facade.
- [HTML knowledge-memory evaluation protocol](../experiments/html-knowledge-v1.md): report fields, leakage and
  determinism conventions reused in Section 14.
- [Baseline methodology](../benchmarks/baseline-methodology.md): measurement conventions.
