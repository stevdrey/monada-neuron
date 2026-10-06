# Host Perception Capability (Issue #41)

Design spec for [Issue #41](https://github.com/stevdrey/monada-neuron/issues/41), following
`docs/specs/spec-context-template.md`. Path: architectural. Dependencies: #39 (ADR 0022), #40 (ADR 0023).

## Background

Monada Forge invokes Neuron for external work items. Neuron's executable inputs are already Neuron-native
`Signal` values, so a host has no explicit boundary for turning an external observation into the initial
bounded `Signal` batch. Without one, hosts invent `FrequencyState` values, encode payloads into `Signal`
(forbidden by ADR 0005), or bypass the cycle. #40 provides the request-scoped `HostExecutionContext` that
makes a perception adapter possible; this issue adds the host-facing perception contract.

## Current State

- `Signal` holds only `SignalKind` and `FrequencyState`; `SignalKind.OBSERVATION` is the entering kind.
- `CognitiveStageKind.PERCEPTION` is the first canonical position and maps to `AeonPurpose.PERCEPTION`.
- `ActionCapability`/`ActionRequest`/`ActionResult`/`ActionOutcome`/`ActionCognitiveStage(Result)` are the
  template for a Neuron-owned capability boundary (ADR 0011).
- `NeuronRuntime` (ADR 0022) and `CycleInput`/`HostExecutionContext` (ADR 0023) exist. The context reaches
  stages through `CognitiveContext.hostContext()`.
- `DeterministicCognitiveCycle` requires non-empty signals for every stage and ends with `NO_SIGNALS`
  otherwise; it rejects two stages at one position.
- No host perception capability exists.

## Goal

A small Neuron-owned perception boundary that lets a host adapter turn one request-scoped external
observation into a deterministic, bounded, ordered batch of `OBSERVATION` signals that enter the canonical
cycle at the `PERCEPTION` position.

## Non-Goals

- No GitHub, Jira, Forge, JavaFX, LLM, or provider types in Neuron.
- No text, repository objects, maps, or identifiers in `Signal`; `FrequencyState` is not a payload container.
- No universal text/task encoding algorithm and no mandatory LLM.
- No bypass of canonical stage ordering; no ambient/global request state.
- No chaining of host perception into a `PERCEPTION` Aeon (future architectural decision).
- No change to `Signal`, `FrequencyState`, memory ports, or `ActionCapability`.

## Affected Areas

- New package `monada.neuron.perception`.
- `monada.neuron.monad`: `CognitiveStage` (default `isSource()`), `DeterministicCognitiveCycle`.
- `monada.neuron.host`: `NeuronRuntime.Builder.perceptionCapability`, javadoc.
- Tests, `docs/architecture.md`, `README.md` embedding example, new ADR 0024.

## Architecture Boundaries

- The host owns domain data and resolves the opaque `HostExecutionContext` inside its adapter. Neuron owns
  the capability contract, bounded output semantics, and cycle lifecycle.
- The adapter may live outside the core and use encoders, models, or analyzers; provider types stay behind
  `PerceptionCapability`. Core cognition needs no LangChain/LLM/vendor API.
- `Signal` stays a compact cognitive value. Perception output enters the cycle only as `OBSERVATION`.
- Long-term memory remains in Monada Resonance Store; perception does no recall.

## Design

### Contracts (`monada.neuron.perception`)

All are immutable records/enums on stable Java 27 APIs, mirroring the Action boundary.

- `PerceptionCapability`: `@FunctionalInterface`, `PerceptionResult perceive(PerceptionRequest request)`.
  Expected outcomes are returned as results; an unexpected runtime failure is an operational failure of the
  calling stage.
- `PerceptionRequest(int maxSignals, Optional<HostExecutionContext> hostContext)`: `maxSignals` positive
  and explicit; context non-null `Optional`. No input signals and no payload: the adapter resolves the
  observation through the context's references. A convenience constructor without context mirrors
  `ActionRequest`.
- `PerceptionStatus`: `SUCCEEDED`, `EMPTY`, `PARTIALLY_COMPLETED`, `REJECTED`, `UNAVAILABLE`, `TIMED_OUT`,
  `FAILED`. These describe what the **adapter** achieved.
- `PerceptionResult(PerceptionStatus status, int signalLimit, List<Signal> signals)`:
  - `signalLimit` positive; `signals` snapshotted with `List.copyOf`, size at most `signalLimit`;
  - every signal is `SignalKind.OBSERVATION` (else `IllegalArgumentException`); non-finite frequencies are
    already rejected by `Signal`/`FrequencyState`;
  - `SUCCEEDED` and `PARTIALLY_COMPLETED` require at least one signal; `EMPTY`, `REJECTED`, `UNAVAILABLE`,
    `TIMED_OUT`, `FAILED` require none (exhaustive `switch`, as in `ActionResult`);
  - `withAdmittedSignalPrefix(List<Signal>)` keeps only an ordered prefix and **never changes the status**.
- `PerceptionOutcome(PerceptionRequest request, PerceptionResult result)`: requires
  `result.signalLimit() == request.maxSignals()`; `withAdmittedSignalPrefix` delegates to the result. The
  request, host context, adapter status, and signals stay inspectable together.

### Stage (`PerceptionCognitiveStage`, `PerceptionCognitiveStageResult`)

- `PerceptionCognitiveStage(capability, maxSignals)`: `kind() = PERCEPTION`, `isSource() = true`. It builds
  `PerceptionRequest(maxSignals, context.hostContext())`, requires a non-null result, and returns
  `PerceptionCognitiveStageResult(new PerceptionOutcome(request, result))`.
- `PerceptionCognitiveStageResult(outcome, producedSignalCount, signalAdmission)`, retaining the outcome like
  `ActionCognitiveStageResult`:
  - `outputSignals()` are the admitted `OBSERVATION` signals in adapter order; `status()` is `COMPLETED`
    (expected outcomes are not a local execution limit);
  - `withAdmittedOutputSignals` keeps the outcome's adapter status and the produced count, and records
    `TRUNCATED` when the cycle admitted a proper prefix;
  - the record validates counters against the admission flag with the same rules as the Action result.
- **Capability status vs. cycle admission.** `PerceptionStatus.PARTIALLY_COMPLETED` is the adapter's report
  that its observation was incomplete. `ObservationAdmission.TRUNCATED` is the cycle admitting only a
  prefix because of its budget. They are independent: budget truncation never rewrites `SUCCEEDED` to
  `PARTIALLY_COMPLETED`, and an adapter's `PARTIALLY_COMPLETED` is never reported as truncation. The stage
  reuses `monada.neuron.action.ObservationAdmission` rather than duplicating a two-value enum; the
  resulting `perception -> action` package dependency is accepted for now and noted in the ADR.

### Source semantics, owned by `DeterministicCognitiveCycle`

`CognitiveStage` gains `default boolean isSource() { return false; }`: a source produces signals from the
host rather than consuming the preceding stage's. All source rules live in the cycle; `NeuronRuntime`
duplicates none of them.

- **First stage only.** The constructor rejects a plan where a source stage is not the first normalized
  stage (`IllegalArgumentException`). A source is the first executed stage by construction.
- **Empty initial signals.** A source executes with an empty initial batch: the cycle skips the
  `NO_SIGNALS` check and stage-input admission for that stage and records the stage started/completed as it
  does for every stage.
- **Initial signals with a source.** A cycle with a source stage that is given non-empty initial signals
  fails with `IllegalArgumentException` **before** any context is created or stage executed (not wrapped in
  `CognitiveCycleException`). `NeuronRuntime.execute` just delegates, so the runtime path fails the same way.
- **After the source.** Its admitted output flows on as usual. Zero output signals (`EMPTY`, `REJECTED`,
  `UNAVAILABLE`, `TIMED_OUT`, `FAILED`) ends a multi-stage cycle with `NO_SIGNALS`; a source-only cycle
  completes with empty output. The `CognitiveCycleResult` keeps the `PerceptionCognitiveStageResult`, so the
  host reads the exact adapter status without a wrapper type.
- **Failure.** An unexpected exception from the adapter is recorded as a failed `PERCEPTION` stage and
  surfaces as `CognitiveCycleException(PERCEPTION, ...)`, unchanged cycle behavior.
- Non-source plans behave exactly as before, so low-level cycles that already receive `Signal` values
  remain supported.

### Mutual exclusion with a PERCEPTION Aeon

`PerceptionCognitiveStage` and `AeonCognitiveStage(PERCEPTION)` occupy the same position; the existing
duplicate-stage check rejects both in one plan (`build()` in the runtime, constructor in the cycle).
Chaining host perception into a perception Aeon is future work and needs its own decision.

### Runtime (`NeuronRuntime.Builder`)

`perceptionCapability(PerceptionCapability, int maxSignals)` adds the stage, next to `memoryPort` and
`actionCapability`. A runtime with it executes `CycleInput` with empty signals and a host context; the
cycle enforces the source rules. `CycleInput` is unchanged. Javadoc of `execute` states that a runtime with
perception requires empty initial signals.

## Algorithm and Data-Structure Expectations

One request record, one result list copy, one prefix scan: O(k) over `k <= maxSignals`, which is a small
explicit bound. `List.copyOf` snapshots are acceptable at this boundary; there are no hot numeric paths, so
no primitive layout is warranted. The `isSource` branch adds one boolean read per stage.

## Performance and Resource Expectations

The boundary is allocation-bounded and negligible. Adapter encoding/model cost is outside Neuron. Host
payloads are never copied into Neuron; the adapter resolves them through the context. No benchmark is
required; none is added.

## Java 27 / Experimental API Policy

Stable APIs only: records, enums, exhaustive `switch`, `Optional`. No preview or incubator API and no
`--enable-preview`. Provider/native/experimental implementations stay behind `PerceptionCapability`.

## Tests and Fixture

- **Fixture** `DeterministicPerceptionCapability` (test source set, beside `DeterministicActionExecutor`):
  scripted results keyed by `HostReference` value of the request's `executionRef`, optional default
  result, records every `PerceptionRequest`, no network, credentials, or model. A variant throws on demand.
- **`PerceptionContractsTest`**: non-positive limit; null arguments; over-limit signals; non-`OBSERVATION`
  signal; non-finite frequency rejected via existing validation; status/signal-count matrix for all seven
  statuses; outcome limit mismatch; `withAdmittedSignalPrefix` rejects non-prefix and keeps status.
- **`PerceptionCognitiveStageTest`**: ordered output preserved; request carries `maxSignals` and the
  cycle's host context; result retains the outcome; `SUCCEEDED` stays `SUCCEEDED` under budget truncation
  with `TRUNCATED` and correct produced count; adapter `PARTIALLY_COMPLETED` stays so without truncation;
  both together; `EMPTY`/`REJECTED`/`UNAVAILABLE`/`TIMED_OUT`/`FAILED` end the cycle with `NO_SIGNALS`.
- **`DeterministicCognitiveCycleSourceTest`**: source runs with empty initial signals; non-empty initial
  signals fail with `IllegalArgumentException` before execution and leave no context/trace; source not
  first rejected at construction; source plus PERCEPTION Aeon rejected as duplicate; non-source plans
  unchanged (existing suite stays green).
- **`NeuronRuntime` perception tests** (extend `HostContextRuntimeTest`/`NeuronRuntimeTest`): end to end
  perception -> later stage(s); runtime with perception and signals fails like the cycle; two consecutive
  and interleaved executions with different contexts observe only their own reference (context isolation);
  absent context reaches the adapter as empty and a rejecting adapter yields `REJECTED`, not an exception;
  adapter exception propagates as `CognitiveCycleException` with kind `PERCEPTION`.

## Acceptance Criteria

- [ ] Immutable typed request/outcome/result contracts exist in `monada.neuron.perception`.
- [ ] `PerceptionCognitiveStage` occupies `PERCEPTION` and `NeuronRuntime.Builder.perceptionCapability`
      wires it, using the #40 host context and no payload/identity on `Signal`.
- [ ] Explicit positive `maxSignals`; adapter order preserved; output bounded and all `OBSERVATION`.
- [ ] Empty, unavailable, rejected, partial, timed-out, and failed outcomes are explicit and tested.
- [ ] Capability partial completion and cycle truncation are distinct; budget never rewrites a status.
- [ ] Source rules are owned by `DeterministicCognitiveCycle`; the runtime duplicates none.
- [ ] No provider/domain types in core contracts; deterministic fixture needs no network or model.
- [ ] Existing `Signal`-driven cycles are unchanged and tests pass.
- [ ] `docs/architecture.md`, `README.md` example, and ADR 0024 updated; no universal encoder prescribed.

## Verification Commands

```bash
./gradlew test
```

## Risks and Trade-Offs

- **Scope creep into ingestion.** Mitigated by a source-only contract with no payload type and an explicit
  bound.
- **Scalar `FrequencyState` is not a semantic embedding.** The contract enables different encoders and
  claims nothing about the encoding; the docs say so.
- **Cycle change.** `isSource` touches the core loop; scoped to the first stage, default false, with the
  existing suite as the regression net.
- **`perception -> action` dependency** via `ObservationAdmission`. Accepted; revisit by moving the enum to
  a neutral package only if a third user appears.
- **Mutual exclusion** with a PERCEPTION Aeon limits some compositions until the chaining decision is made.

## Documentation Updates

- `docs/architecture.md`: external perception boundary, stage order, source semantics, status vs.
  admission, host-embedding flow including the perception capability.
- `docs/adr/0024-host-perception-capability-boundary.md`: context, decision, alternatives (pre-cycle
  runtime step; `CycleInput`-carried request with wrapper result; signals encoded in `Signal`; map-based
  payload), consequences, follow-up (chaining into a PERCEPTION Aeon, cancellation, shared admission enum).
- `README.md`: embedding example shows optional perception wiring.
- No skill changes expected.
