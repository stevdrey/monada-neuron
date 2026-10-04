# ADR 0012: Deterministic Baseline Feedback and Adaptation Policy

## Status

Accepted

## Context

The native cognitive lifecycle includes an `ADAPTATION` stage, but Monada Neuron previously had no typed feedback representation, adaptation policy abstraction, or learning baseline. Without an adaptation capability, the cognitive node graph remained static across cycles and could not adapt to evaluated outcomes.

Before introducing complex or heuristic learning mechanisms, the project requires an inspectable, bounded, deterministic baseline update policy along with a no-op reference policy for reproducible A/B evaluation. Long-term memory and persisted learning logs belong to Monada Resonance Store; Neuron requires only ephemeral in-memory adaptation and cycle trace diagnostics.

## Decision

Monada Neuron owns the `monada.neuron.evolution` package containing typed feedback contracts, policy interfaces, and the deterministic baseline implementation:

1. `FeedbackInput` is an immutable record pairing a target `Node` UUID with optional observed/target `Signal` values and a finite scalar feedback score in `[-1.0, 1.0]` (positive for reinforcement, negative for penalty, zero for neutral).
2. `AdaptationDecision` is an immutable record documenting previous and new `FrequencyState` and energy values and whether adaptation was applied.
3. `AdaptationPolicy` is the functional contract for evaluating feedback and transitioning `Node` state.
4. `NoOpAdaptationPolicy` makes zero mutations to `Node` state or energy and returns `adapted = false`, serving as the reference baseline for A/B testing.
5. `DeterministicBaselineAdaptationPolicy` is a conservative, deterministic baseline policy bounded by `AdaptationConfig`:
   - Enforces finite numeric inputs and rejects invalid `NaN` or infinite values.
   - When a target signal is provided, positive feedback moves frequency state (amplitude, frequency, phase) towards the target state proportionally to `learningRate * score` and increases energy; negative feedback attenuates amplitude/energy and diverges from error-causing frequency.
   - When scalar feedback is provided without a target signal, amplitude and energy scale proportionally to `1 + learningRate * score`.
   - Clamps amplitude, frequency, and energy to configurable finite minimum and maximum bounds, and wraps phase deltas using `StrictMath.IEEEremainder`.
   - Applies state updates coarse-grained via `node.transition(newState)` and `node.setEnergy(newEnergy)`, preserving node audit history.
6. `AdaptationCognitiveStage` occupies `CognitiveStageKind.ADAPTATION` in `DeterministicCognitiveCycle`. It adapts candidate target nodes in deterministic order and emits `AdaptationCognitiveStageResult`.
7. `CognitiveTraceEvent.NodeAdapted` records adaptation decisions in `CognitiveContext` respecting the cycle's trace budget without persisting feedback data inside Neuron.

## Alternatives Considered

### Implement backpropagation or gradient descent

Rejected because Monada Neuron is built around frequency wave patterns, scalar resonance, and discrete cognitive nodes rather than a fixed-layer differentiable tensor network.

### Persist feedback history and learning logs inside Neuron

Rejected because durable learning experience and persisted memory belong exclusively to Monada Resonance Store. Neuron retains only in-memory node state and active-cycle trace entries.

### Automatically modify graph topology during adaptation

Rejected because topology mutation introduces non-deterministic structural divergence before a stable node-state adaptation baseline is established.

## Consequences

- Nodes can adapt their frequency state and energy in response to evaluated cycle outcomes.
- Adaptation decisions are recorded deterministically in the ephemeral cycle trace.
- A/B evaluation can compare cognitive cycles with `NoOpAdaptationPolicy` versus `DeterministicBaselineAdaptationPolicy`.
- Update rules are strictly bounded, preventing numerical instability or overflow.
- No third-party frameworks or external services are required.
- Amended by ADR 0021: a Node's state history is now a bounded ring buffer (default 256 states), and the cross-cycle handoff that carries action outcomes into a later cycle's adaptation is defined there.
