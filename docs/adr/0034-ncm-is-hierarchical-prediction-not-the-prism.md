# ADR 0034: NCM Is Hierarchical Prediction, Not the Prism

## Status

Accepted (documentation only; issue #121; Epic #120; composes ADR 0001, 0005, 0006, 0019 and 0022 without amending them)

## Context

Epic #120 proposes an experimental Next-Concept Modeling (NCM) path described with a prism analogy: a structured input is
a beam, a decomposer separates it into spectral components, resonance studies how components relate, a hierarchical
model forms concept hypotheses, and a synthesizer reconstructs samples. The analogy is useful for orientation and
dangerous as a specification: it invites treating concepts as waves, treating a frequency as meaning, and treating a
good reconstruction as evidence of understanding.

`main` has a single-wave baseline: `FrequencyState(amplitude, frequency, phase)`, the identity-free `Signal`
(ADR 0005), the deterministic `ScalarResonanceMetric` (ADR 0006), whose formula is not a spectral or coherence measure,
typed `Hypothesis` and `Evidence` (ADR 0019) and the bounded cycle and host facade (ADR 0009, ADR 0022). There is no
spectral type, no decomposition or synthesis, and no hierarchical concept predictor. Nine child issues (#122 to #130)
each need the same semantics, bounds, tolerances and gates; without one contract each would invent its own, and the
experiment would have no point at which it can be declared a failure. `AGENTS.md` asks for an ADR when a change fixes
cognitive boundaries, signal representation or a performance-critical data layout.

## Decision

Adopt [NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md), contract identifier
`ncm-wave-cognition/1`, as the normative contract for Epic #120. The durable decisions:

- **Four operations, four owners.** Decomposition (`WaveDecomposer`, the prism), resonance
  (`SpectralResonanceDynamics`), NCM inference (`NextConceptPredictor` over a `ConceptHierarchy`) and synthesis
  (`WaveSynthesizer`) are separate types, separately tested. **NCM is the coarse-to-fine formation of concept hypotheses
  from evidence; it is not the transform**, it does not learn the transform end to end, and it never derives concept
  semantics from frequencies.
- **Meaning comes from the host.** Samples mean what the host's modality encoder declares, recorded in provenance.
  Concept labels and ground truth are host or fixture supplied. Neuron never infers semantics from arbitrary text and
  has no text-to-frequency oracle.
- **Physical versus abstract units.** A frequency is reported in hertz only when the input declares a physical sample
  rate; abstract sequences report cycles per index (`k / N` with a unit sample rate); wavelet coefficients report scale and position, never hertz.
- **Scalar references first.** A direct DFT computed with `StrictMath` in a fixed summation order is the decomposition
  reference; an orthonormal Haar transform is the multiresolution strategy. FFT, SIMD, native and GPU backends are later
  options checked against these references within a documented tolerance, and none is in v1.
- **The single-wave path is untouched.** `Signal`, `FrequencyState`, `ResonanceMetric` and `ScalarResonanceMetric` keep
  their semantics. Spectral data travels only in new typed values, never inside a `Signal`. Spectral resonance is a new,
  explicitly named mechanism; negative interference is a diagnostic and never a Store score.
- **Hypotheses, not truths.** Concept candidates are hypotheses with evidence, scores, margins and explicit abstention
  reasons; calibration is measured on held-out data, not assumed.
- **Opt-in, cycle-local, bounded.** Every artifact is immutable and discarded after its call or cycle. Integration uses
  existing stage positions without a new `CognitiveStageKind`. Model state is a caller-owned snapshot updated only through
  validated outcome feedback carried across cycles (ADR 0021). Long-term memory, recall and ranking stay in Monada
  Resonance Store (ADR 0001).
- **Binding stop/go gates.** Gates G0 to G5 (with the Haar and synthesis sub-gates G1h and G1s) with pre-registered margins decide whether work continues. In particular,
  cycle integration (#128) does not start unless hierarchical NCM beats the no-spectral baseline at matched coverage
  (gate G3). A negative result is a valid, published outcome.

The contract introduces no Java type and no runtime behavior. Every named type is a sketch owned by its issue, and every
numeric bound is a proposal owned by its issue.

## Alternatives

### Extend `FrequencyState` with harmonics or a component list

Rejected. It would change a compact record used across signals, nodes, batches and the scalar metric, break the
compatibility that ADR 0005 and ADR 0006 rely on, and force every existing caller to reason about spectra.

### Treat NCM as the decomposer, or learn the transform end to end

Rejected. It merges an exact, testable operation with an uncertain inference, removes the reference against which
optimized backends and predictions can be checked, and makes the prism analogy unfalsifiable.

### Depend on an FFT or signal processing library from the start

Rejected for v1. The workloads are small enough for a direct DFT reference, a dependency must justify measurable benefit
(`AGENTS.md`), and a library backend can be evaluated later against the reference.

### Add a new `CognitiveStageKind` for wave analysis

Rejected. It changes the canonical stage order for every host. The existing positions and typed results already allow
opt-in composition.

### Keep learned concept memory inside Neuron

Rejected. Persisted or reusable experience belongs to Monada Resonance Store (ADR 0001); Neuron holds only an
immutable caller-owned snapshot during a call.

### Specify each child issue independently

Rejected. Tolerances, units, tie rules and gates would diverge, and no single place would say when the experiment
should stop.

## Consequences

- Children #122 to #130 cite one contract for semantics, bounds, fixtures, tolerances and gates.
- The experiment can end early at G1, G2 or G3 with a documented negative result instead of an architectural adoption.
- The contract must be kept in sync with implementation; any change to a formula, tolerance, hypothesis or gate is a
  major revision (`ncm-wave-cognition/2`).
- A spectral evidence variant may require extending the sealed `Evidence` hierarchy; #128 records that in its own ADR.
- No production code changes; `./gradlew test` is unaffected.

## Follow-Up

- #122 to #127 implement the reference types and algorithms against the fixtures of Section 12 of the contract.
- #130 pre-registers the margins of hypotheses H2, H3 and H5 before computing held-out results.
- Revisit FFT or SIMD backends only after gate G1 passes and a profiled workload shows the DFT reference is the
  bottleneck.
