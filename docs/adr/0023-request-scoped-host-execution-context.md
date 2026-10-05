# ADR 0023: Request-Scoped Host Execution Context

## Status

Accepted

## Context

Monada Forge executes Neuron cycles for concrete external work items. An `ActionCapability` received only
`ActionRequest(List<Signal>, maxObservations)`, so a Forge adapter could not tell which host execution an
action belonged to without hidden mutable state, `ThreadLocal`, a global registry, or domain data encoded
into `FrequencyState`. ADR 0005 keeps `Signal` free of identifiers and reserved correlation metadata for
"a separate cycle-context or envelope contract with explicit lifetime". ADR 0022 added `NeuronRuntime` and
`CycleInput` and noted that carrying cycle-local input through the cycle and `CognitiveContext` should be
revisited if more cycle-local inputs appeared. A real host integration now requires it.

## Decision

Add a small immutable correlation view that travels explicitly through existing contracts:

```text
CycleInput.hostContext
  -> NeuronRuntime.execute
  -> DeterministicCognitiveCycle.execute(monad, signals, budget, hostContext)
  -> CognitiveContext.hostContext()          (lifetime: exactly one cycle)
  -> ActionCognitiveStage                    (reads it from the context it is given)
  -> ActionRequest.hostContext
  -> ActionCapability adapter                (resolves it on the host side)
```

- `HostReference(String value)` is an opaque, non-blank token of at most 128 characters. Neuron never
  parses or compares its meaning.
- `HostExecutionContext(HostReference executionRef, Optional<HostReference> lookupRef)` holds the stable
  execution/correlation reference and an optional host-owned lookup token. Heavy domain context (task
  text, repository, attachments) stays with the host and is reached only through that token. Both types
  live in `monada.neuron.context`, so `action` and `monad` need no dependency on `host`.
- `Signal`, `Node`, and every other cognitive value are unchanged. Host context is not part of signal
  equality, resonance semantics, `ResonanceMemoryRequest`, the trace, or `CognitiveCycleSnapshot`.
- **Lifetime and ownership.** The host owns the context and supplies it per execution. The cycle copies it
  into one `CognitiveContext`, which is released with the cycle. The built `NeuronRuntime` and its shared
  stages hold none, so consecutive or interleaved executions cannot observe each other's context. The
  `ActionOutcome` already retains its `ActionRequest`, so the host that receives the `CognitiveCycleResult`
  can read the reference there for correlation; it is not persisted by Neuron.
- **Missing.** Hosts that need no correlation pass none: `hostContext` is empty and the low-level cycle,
  `CycleInput.of(signals)`, and every existing signature behave as before. An adapter that requires a
  context and receives none returns an expected `ActionResult` such as `REJECTED`; Neuron does not
  require one.
- **Invalid.** A null, blank, or overlong reference fails in `HostReference`'s constructor with
  `IllegalArgumentException` before any cycle runs.
- **Expired or unknown.** Neuron does not track host liveness. The adapter resolves the references; one it
  cannot resolve (expired, unknown, cancelled) yields an expected non-success `ActionStatus` such as
  `REJECTED` or `UNAVAILABLE`, not an operational exception.
- Only stable Java APIs are used; no preview or incubating API, and no `--enable-preview`.

## Alternatives Considered

### Identity or metadata on `Signal`

Rejected by ADR 0005: it would add allocation and API surface to the hot value and make host identity part
of signal equality.

### `ThreadLocal`, `ScopedValue`, static registry, or global map

Rejected: all are ambient state. ADR 0022's feedback channel uses a `ScopedValue` as a documented
exception for an existing stage; this contract keeps propagation visible in method and record signatures,
so ownership and request isolation hold without relying on thread inheritance.

### `Map<String, Object>` context bag

Rejected: untyped, unbounded, and a first step toward a workflow-engine context.

### Context captured in a per-execution `ActionCognitiveStage`

Rejected: it would rebuild the stage plan per execution, against ADR 0022's configure-once runtime.

### Add a parameter to `CognitiveStage.execute` or `ActionCapability.execute`

Rejected: it breaks every stage and capability implementation. `CognitiveContext` already reaches each
stage and `ActionRequest` is already the capability's request value, so neither interface changes.

## Consequences

- Per cycle the cost is one `Optional` reference copy; the context is control-plane data.
- `ActionRequest` gains a third component; a two-argument constructor keeps existing callers working.
- Memory and persistence ports receive no host context. A future need for it requires a new decision.
- A host that retains a `CognitiveCycleResult` retains the references in its action outcome; that is a
  host choice.

## Follow-Up Work

- Extend the contract only for a concrete adapter need (for example cancellation signalling), as a new
  typed field with its own lifetime rules rather than generic metadata.
