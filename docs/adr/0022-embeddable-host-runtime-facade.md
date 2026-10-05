# ADR 0022: Embeddable Host Runtime Facade

## Status

Accepted

## Context

Product hosts such as Monada Forge consume Neuron as a cognitive engine. Neuron exposes the building
blocks of a cycle (`PrimaryMonad`, `CognitiveStage`, `DeterministicCognitiveCycle`, `CognitiveBudget`,
`ResonanceMemoryPort`, `ActionCapability`), but a host had to understand and construct that composition
itself: wrap the port and capability in their stage classes, normalize stage positions, and keep the
cycle, Monad, and budget together. That couples every host to Neuron's internal wiring and would let
each host re-implement it differently.

The facade must stay small. The issue names the risk of an abstraction broader than real use cases and of
freezing internals too early, and ADR 0001 and ADR 0002 keep memory, models, and orchestration frameworks
outside the cognitive core.

## Decision

Add `monada.neuron.host.NeuronRuntime` to the core module, with no new module and no new dependency.

- **One composition, built once.** `NeuronRuntime.builder()` collects a required `PrimaryMonad`, cognitive
  stages, and an optional default `CognitiveBudget`. `build()` constructs a single
  `DeterministicCognitiveCycle` and validates each stage against the Monad. Nothing about the composition
  is rebuilt per execution.
- **Delegation only.** `execute(signals, budget)` is `cycle.execute(monad, signals, budget)`. Canonical
  stage order, termination, budget handling, and failure semantics remain owned by the cycle and the
  stages; the facade duplicates none of them.
- **Transparent result and failure.** The result is the cycle's own `CognitiveCycleResult`, and failures
  propagate as `CognitiveCycleException`. There is no wrapper type, no `Map`-based configuration or
  result, and no hidden budget or termination status.
- **Typed shortcuts plus generic stages.** `memoryPort(port, maxResults)` and
  `actionCapability(capability, maxObservations)` wrap the existing stage classes so the optional
  capabilities are explicit and neither is mandatory. `stage(CognitiveStage)` admits every other position
  (Aeon, evaluation, adaptation) without the runtime knowing their types. Two stages for one position
  fail in `build()` through the cycle's own duplicate check.
- **Budget.** An optional default budget serves `execute(signals)`; `execute(signals, budget)` overrides it.
  Executing without any budget throws `IllegalStateException` rather than inventing one.
- **Not thread-safe.** The runtime shares the sequential contract of `PrimaryMonad` and
  `CognitiveContext`: executions are sequential and Aeon registrations must not change during a cycle.
- **Static composition, cycle-local input.** The built runtime shares its stage instances across every
  execution, so cycle-local data does not belong in them. It travels in an immutable
  `CycleInput(signals, budget, priorFeedback)`; `execute(signals)` and `execute(signals, budget)` are
  conveniences over it. This keeps ADR 0021's caller-owned feedback handoff while letting one runtime
  serve a whole feedback loop.
- **Prior feedback through a scoped stage.** `feedbackAdaptation(policy, targets|aeon)` adds
  `ScopedFeedbackAdaptationCognitiveStage`, configured only with its policy and targets. `execute` binds
  `CycleInput.priorFeedback()` with a `ScopedValue` (a final API since Java 25, so no preview flag) around
  `cycle.execute`; each execution the stage delegates to a `FeedbackAdaptationCognitiveStage` created for
  that feedback, so consumption, trace events, and results are those of the existing stage and nothing is
  duplicated. Without bound feedback the stage passes its signals through, records no event, and leaves
  the artifact with the caller. The cycle, `CognitiveContext`, `PrimaryMonad`, and
  `FeedbackAdaptationCognitiveStage` are unchanged.
- **No silent loss.** Supplying prior feedback to a runtime with no feedback adaptation stage fails with
  `IllegalStateException`, and feedback from another Monad fails in the stage's `validate` before any Node
  changes. Adding a capturing `FeedbackAdaptationCognitiveStage` through `stage(...)` remains possible for
  one-cycle runtimes and still reapplies its captured artifact on each execution; this is documented rather
  than rejected.
- **Contract surface.** Only `NeuronRuntime`, its `Builder`, and the existing public types it accepts or
  returns are part of the embedding contract. No preview, incubator, native, Vector API, Forge, or provider
  type appears in it, and `--enable-preview` is not required.

The low-level cycle and stage APIs are unchanged and stay usable for experiments and focused tests.

## Alternatives Considered

### Generic stages only

Rejected: the host would still build `ResonanceMemoryCognitiveStage` and `ActionCognitiveStage`, so the
optional-capability boundary the issue asks for would stay implicit and the internal wiring would leak.

### Typed shortcuts only

Rejected: it would stop hosts from adding Aeon, evaluation, or adaptation stages and push the next
requirement into an immediate API change.

### Explicit budget on every call only

Rejected as the only form: it is allowed through the override, but hosts that run many cycles under one
policy would repeat the budget in each call. The budget is still never hidden: it is part of the result
snapshot.

### Build a new runtime for every cycle of a feedback loop

Rejected after review: it avoids stale feedback but moves the lifecycle problem to the host, which would
have to know to rebuild the runtime whenever feedback changes, against the purpose of a configure-once
boundary. It remains valid for a runtime that deliberately captures one artifact.

### Reject `FeedbackAdaptationCognitiveStage`, or accept an arbitrary per-call stage list

Rejected: refusing the stage couples the facade to one class, and a per-call stage list or stage factory
widens the public API into a plugin mechanism. Feedback is the one concrete cycle-local input, so a typed
`CycleInput` is enough.

### Assemble a cycle per execution when feedback is present

Rejected: it needs no scoped value, but rebuilds the cycle composition on every feedback execution, which
the issue asks to avoid.

### Carry the input through `DeterministicCognitiveCycle` and `CognitiveContext`

Rejected for now: it is the most explicit data flow, but it changes core types that ADR 0021 left untouched
and would make `context` depend on `evolution`. Revisit if more cycle-local inputs appear.

### Wrapper result or sealed host outcome

Rejected: `CognitiveCycleResult` already carries termination, stage results, outputs, and snapshot, and a
second hierarchy would need to be kept in step with it.

### Generic plugin framework, service locator, or classpath discovery

Rejected by the issue and by the design principles: explicit typed composition is sufficient and avoids
reflection and per-cycle discovery cost.

## Consequences

- A host needs only the `monada.neuron.host` entry point plus the Neuron value types it already passes in
  and out.
- Per-cycle overhead is one delegate call and one `Optional` read; setup is control-plane work done once.
- The facade is a compatibility promise on a small surface. Changing the builder methods or the
  result/failure types later needs a new decision.
- A feedback loop uses one `NeuronRuntime`; per execution the cost is one `ScopedValue` binding and one
  short-lived `FeedbackAdaptationCognitiveStage` that holds the artifact, with no state kept between
  executions.
- The prior-feedback channel is implicit between the runtime and the scoped stage. It is thread-confined
  to the calling thread, so the stage is meant for the sequential deterministic cycle, whose stages run on
  that thread.
- Because hosts hold the same `PrimaryMonad` reference they passed in, they remain responsible for not
  mutating its registrations during a cycle.

## Follow-Up Work

- Extend the host API only when a concrete integration needs it.
- Revisit concurrency (for example, one runtime per virtual thread or a partitioned model) only with a
  measured host workload; this ADR does not add asynchronous execution.
