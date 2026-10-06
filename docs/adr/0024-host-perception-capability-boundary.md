# ADR 0024: Host Perception Capability Boundary

## Status

Accepted

## Context

Monada Forge invokes Neuron for concrete external work items, but every executable input of a cycle is
already a Neuron-native `Signal`. A host had no explicit boundary for turning an external observation into
the initial bounded batch, which pushes it to invent `FrequencyState` values, encode domain payloads into
`Signal` (rejected by ADR 0005), or bypass the cognitive lifecycle. ADR 0023 supplies the request-scoped
`HostExecutionContext` that makes a perception adapter possible without identity on `Signal`, and ADR 0011
established the capability/outcome pattern for the ACTION position.

## Decision

Add a Neuron-owned perception capability in `monada.neuron.perception`, mirroring the Action boundary.

- **Contracts.** `PerceptionCapability.perceive(PerceptionRequest) -> PerceptionResult`.
  `PerceptionRequest(maxSignals, Optional<HostExecutionContext>)` carries an explicit positive bound and the
  opaque host context, never a payload. `PerceptionResult(status, signalLimit, signals)` is immutable,
  bounded, order-preserving, and holds only `SignalKind.OBSERVATION` signals. `PerceptionOutcome` keeps
  request and result together.
- **Status.** `SUCCEEDED` and `PARTIALLY_COMPLETED` require signals; `EMPTY`, `REJECTED`, `UNAVAILABLE`,
  `TIMED_OUT`, and `FAILED` forbid them. These are expected results, not exceptions; an unexpected adapter
  exception is an operational failure surfaced as `CognitiveCycleException` for the PERCEPTION stage.
- **Capability partial completion is not cycle truncation.** `PerceptionStatus.PARTIALLY_COMPLETED` is the
  adapter's report; `ObservationAdmission.TRUNCATED` records that the cycle budget admitted only a prefix.
  Budget truncation never rewrites a status, and the stage result keeps the produced count.
- **Stage.** `PerceptionCognitiveStage` occupies the PERCEPTION position and is a *source*:
  `CognitiveStage.isSource()`. `DeterministicCognitiveCycle` alone owns the source rules: a source must be
  the first stage, executes with empty initial signals, and a cycle with a source stage rejects non-empty
  initial signals with `IllegalArgumentException` before creating a context or running any stage.
  `NeuronRuntime.Builder.perceptionCapability(capability, maxSignals)` only adds the stage.
- **Host context.** The stage copies `CognitiveContext.hostContext()` into the request, as the Action stage
  does; no ambient state is introduced. The `PerceptionRequest` is retained by the `PerceptionOutcome` in the
  returned `CognitiveCycleResult`, so a host that retains that result retains the references, exactly as with
  `ActionOutcome` (ADR 0023); it is not part of the trace or snapshot.
- **Mutual exclusion.** The stage and a PERCEPTION `AeonCognitiveStage` are alternatives for the single
  position; the existing duplicate-position check rejects both.
- **No prescribed encoder.** Neuron defines no universal text or task encoder and does not claim that the
  scalar `FrequencyState` is a semantic embedding; adapters may use any encoder, model, or deterministic
  fixture behind the capability. Stable Java 27 APIs only; no `--enable-preview`.

## Alternatives Considered

### Pre-cycle runtime step (`runtime.perceive(...)` then `execute`)

Rejected: perception would sit outside the canonical cycle, with no trace, budget, or
`CognitiveCycleResult`, bypassing the stage ordering the project requires.

### Request carried in `CycleInput` with a wrapper result

Rejected: it needs a result envelope that ADR 0022 deliberately avoided, and it makes the runtime, not the
cycle, responsible for the first stage's lifecycle.

### Encoding domain payloads or identifiers into `Signal`/`FrequencyState`

Rejected by ADR 0005 and by the domain-independence rule.

### `Map<String, Object>` payload or reflection-based adapters

Rejected: untyped, unbounded, and the first step toward a data-ingestion framework.

### Chaining host perception into a PERCEPTION Aeon

Deferred: a separate architectural decision.

## Consequences

- A host performs perception through one typed capability and needs no payload or identity on `Signal`.
- The cycle gains one default method and a first-stage source rule; plans without a source behave as before.
- A runtime with perception cannot also take initial signals, and cannot use a PERCEPTION Aeon, until
  chaining is decided.
- `perception` depends on `action` through `ObservationAdmission`; acceptable for now.
- Per cycle the Neuron boundary costs one request record and one signal-list copy, bounded by `maxSignals`.

## Follow-Up Work

- Decide how host perception chains into a PERCEPTION Aeon.
- Consider a neutral package for the shared admission enum if a third capability needs it.
- Add cancellation signalling only for a concrete adapter need, as a new typed field.
