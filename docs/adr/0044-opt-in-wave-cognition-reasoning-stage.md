# ADR 0044: Opt-In Wave Cognition Reasoning Stage

## Status

Accepted (issue #128; Epic #120; implements section 10.1 of `ncm-wave-cognition/2`; amends ADR 0019 by adding one
`Evidence` variant; composes ADR 0009, ADR 0022, ADR 0023, ADR 0042 and ADR 0043)

## Context

The NCM library on `main` decomposes host samples (ADR 0036), synthesizes them back (ADR 0042, gate G1s go for DFT and
Haar) and predicts the next concept coarse to fine (ADR 0043). Nothing runs it inside the cognitive cycle. The cycle
has a fixed stage order (`PERCEPTION → MEMORY_RECALL → REASONING → EVALUATION → ADAPTATION → ACTION`), `Signal` is a
two-field identity-free record (ADR 0005) that cannot hold sampled frames, and typed hypotheses reach `EVALUATION`
only through a `HypothesisHandOff` from `REASONING` (ADR 0019, ADR 0020).

Gate G3 was evaluated by #127 and is **no-go** ([next-concept-g3.md](../benchmarks/next-concept-g3.md)): H3 misses
its margin in four of five repeats, H6 fails in every repeat and H4 is not supported. Under `ncm-wave-cognition/1`
section 15 that stopped #128. The project owner decided to integrate anyway as an explicitly experimental, opt-in
capability, so that the follow-up work (#129 feedback, #130 ablation) can exercise the predictor inside a real cycle.
Changing a gate is a major revision of the contract (section 17), so this ADR comes with `ncm-wave-cognition/2`. The
G3 result is not reinterpreted: it stays the published negative result, and nothing here claims predictive benefit.

## Decision

- **One optional stage, fixed position.** `WaveCognitionReasoningStage` (package `monada.neuron.reasoning.concept`) is
  an ordinary `REASONING` stage the host composes with `NeuronRuntime.Builder.stage(...)`. No new
  `CognitiveStageKind`, no static registry, no change to `NeuronRuntime`, `DeterministicCognitiveCycle` or any default
  composition. A runtime without the stage is the `main` runtime (fixture N13).
- **Explicit per-execution input, not a `Signal`.** A host `WaveInputResolver` maps the request-scoped
  `HostExecutionContext` (ADR 0023) to a `WaveCognitionInput` of `WaveWindow`s (a `SampledWave` plus the host's
  previous concept code), the pattern of `RoutingInputResolver` and `PerceptionCapability`. Samples never become
  signals and the stage never sees another execution's input. An empty resolver answer means the execution does not
  opt in. The previous concept stays caller-owned: the stage never chains windows.
- **Two positions, both `REASONING`.** The default stage is not a source: it runs after perception or memory recall
  and returns its input signals unchanged, so the cycle's existing input and output admission (ADR 0009) governs
  every signal and typed hypotheses never need to cross a memory stage. A host whose executions carry only samples
  uses `WaveCognitionReasoningStage.source(...)`: the first stage, with no signals (like `RoutingReasoningStage`),
  handing its hypotheses to `EVALUATION` typed-only, so no placeholder signal is ever needed.
- **One step per execution.** Every window of an input is independent evidence for the same next step (channels,
  sensors or views of one instant). A time series is one execution per step; the host carries the previous concept.
  This is what makes merging equal concepts from several windows into one hypothesis sound.
- **Bounded work, DFT only.** `WaveCognitionLimits`: at most `maxWindows` (`1..32`, default 8) windows, else the whole
  input is rejected (`REJECTED_OVERSIZED`, nothing decomposed); `maxTotalComparisons` over the execution's predictions
  (default 2,048); a `PredictionBudget` per call and `HypothesisLimits` for the hand-off. Transform work is bounded by
  `maxWindows` (one DFT and at most one synthesis per window) and counted in the report. Abstentions the predictor
  decides without a pattern (unknown previous concept, model above the prediction budget, model without exemplars)
  are decided before any transform. The decomposer is the DFT reference
  because `ConceptModelTrainer` accepts DFT frames only; a window whose sample count, rate or unit differs from the
  model frame is reported `Incompatible` before any transform. Haar windows are therefore out of this integration,
  although G1s passed for Haar synthesis.
- **Synthesis as a diagnostic.** With `verifyReconstruction`, each decomposed window is synthesized back with
  `WaveSynthesizer` and its `SynthesisStatus` is reported; it never changes a hypothesis.
- **Typed hand-off and report.** `WaveCognitionStageResult` implements `CognitiveStageResult` and
  `HypothesisHandOff` like `RoutingCognitiveStageResult`, so `HypothesisEvaluationCognitiveStage` reads the
  hypotheses unchanged. It carries a `WaveCognitionReport`: a `WaveCognitionOutcome` and one bounded `WindowOutcome`
  per examined window (`Incompatible`, `Rejected`, `Abstained`, `Predicted`) with its prediction work and optional
  reconstruction status.
- **Fail-closed outcomes.** No host context, no opt-in, or no windows: `COMPLETED`, no hypotheses, signals unchanged.
  Exceeding `maxTotalComparisons` before a window, or a selected concept or its evidence not fitting the hypothesis
  limits, stops at that window deterministically with `LIMIT_REACHED` (the cycle ends `STAGE_LIMIT_REACHED`, so
  `EVALUATION` does not run). A resolver that returns `null` is a host bug and fails the cycle.
- **`SpectralEvidence`.** A new sealed `Evidence` variant `SpectralEvidence(sourceReference, windowOffset, relation,
  weight)` references the window by its `WaveProvenance`, under the same reference bound as `MemoryReferenceEvidence`.
  Each selected concept (leaf or coarse-only) becomes `ConceptHierarchy.proposition(ordinal)` with one
  `SpectralEvidence(SUPPORTS, 1.0)`: one window, one unit of support. The predictor's score is reported in the
  `WindowOutcome`, not used as evidence mass, because contract section 8.5 admits it into evaluation only as an
  explicitly configured resonance-like component (and G3 found it uninformative). Equal propositions from several
  windows merge their evidence in window order, so evaluation ranks a concept by how many windows support it. The cycle cannot validate
  a window reference against an occurrence, so `HypothesisSet.validateSignalProvenance` ignores it, as it ignores
  memory references.

## Alternatives Considered

### Stop at G3 and publish only the negative result

The contract's `/1` consequence. Rejected by the project owner in favor of an experimental opt-in; the default stays
library-only in effect because nothing composes the stage by default.

### Hypotheses without evidence (the routing precedent)

Rejected: the reference evaluation policy scores every evidence-free hypothesis 0, so `EVALUATION` would receive no
information, and the hypothesis would not say which window produced it.

### `SignalEvidence` pointing at the stage's input occurrences

Rejected: legal for provenance validation but false, since the input signals do not support the concept.

### `MemoryReferenceEvidence` with the window reference

Rejected: the window is not a memory recall; reusing the type would blur the cognition/memory boundary (ADR 0001).

### A new `CognitiveStageKind` or a source stage

Rejected: contract section 10.1 forbids a new kind, and a source stage could not follow perception or memory recall.

## Consequences

- A host opts in with one `stage(...)` call and a resolver; every runtime that does not is unchanged (N13). All
  existing tests pass; the only test change is the `SpectralEvidence` branch of the analytic
  `HypothesisFootprintModel`, whose switch over the sealed `Evidence` must stay exhaustive.
- Consumers with an exhaustive `switch` over `Evidence` break: their source no longer compiles until they add the
  `SpectralEvidence` case, and code already compiled against the previous release throws `MatchException` when it
  meets one. Consumers that only read `relation()` and `weight()` are unaffected.
- Hypotheses from this stage carry the G3 no-go: they are concept proposals for experiments, not calibrated beliefs.
- Cost per execution is bounded by `maxWindows` DFTs of the model's sample count, `maxTotalComparisons` comparisons
  and, when enabled, as many syntheses.

## Follow-Up

- #129: outcome feedback producing a new model snapshot, reading the hypotheses this stage hands off.
- #130: consolidated ablation; may measure the stage's end-to-end cost.
- Haar evidence would need a Haar-capable model and its own gate.
