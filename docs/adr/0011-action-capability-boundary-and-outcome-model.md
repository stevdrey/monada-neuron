# ADR 0011: Action Capability Boundary and Outcome Model

## Status

Accepted

## Context

The native cognitive flow ends in explicit action and outcome/feedback, but the core previously
had no contract for requesting an external action or observing its result. Directly importing tool,
device, browser, process, framework, or provider request types into cognition would make an adapter
the lifecycle owner and make deterministic testing difficult.

The reference cycle already has one canonical `ACTION` position and cycle-owned admission for
non-Aeon stage inputs and outputs. That position needs a small, typed boundary that can represent
expected outcomes without adding retries, transport policy, or learning behavior to the core.

## Decision

Monada Neuron owns the synchronous, transport-neutral `ActionCapability` contract and immutable
`ActionRequest`, `ActionResult`, and `ActionOutcome` values. A request carries one non-empty ordered
batch of Neuron `Signal` values and an explicit positive maximum observation count. The injected
capability instance identifies the concrete action; the core defines no provider identifier,
generic argument map, or command taxonomy.

An action result acknowledges the requested observation limit and has one explicit status:
`SUCCEEDED`, `PARTIALLY_COMPLETED`, `REJECTED`, `UNAVAILABLE`, `TIMED_OUT`, or `FAILED`. Only a
successful or partially completed result can carry observations. Observation order and duplicates
belong to the capability. Rejection, availability, timeout, and expected failure expose only their
typed status, avoiding provider-specific result payloads in core contracts.

`ActionCognitiveStage` occupies the existing `ACTION` position. It submits one request made from
the cycle-admitted input Signals, exposes the request/result association through
`ActionCognitiveStageResult`, and emits only result observations. It never forwards its input
Signals automatically. Expected action statuses are non-fatal: the cycle can complete with a typed
outcome that later evaluation or evolution code may inspect. A thrown exception, null result,
inconsistent limit, or invalid contract remains an operational stage failure.

The cycle owns admission of action observations. If its global signal budget accepts only a proper
prefix of a successful result, the retained result becomes `PARTIALLY_COMPLETED`; no rejected
observation is stored in the result or context. This expresses a partial observable outcome, not a
claim about an external side effect beyond what the capability reported.

`ActionCognitiveStage` and `AeonCognitiveStage` are alternative implementations of the one
`ACTION` slot. The existing unique-stage validation rejects a plan containing both; no additional
selection or execution position is introduced.

The first deterministic executor is test-only and fixture-backed. No production tool, process,
network, browser, device, retry, concurrency, framework, or learning-policy integration is added.

## Alternatives Considered

### Use a generic tool name plus argument map

Rejected because untyped provider-shaped data would become the core action model and would not
express ordered Signal semantics or bounded observations.

### Add a separate action-selection and action-execution stage

Rejected for this issue because the current canonical `ACTION` slot and extension mechanism already
provide a narrow execution boundary. A later need to compose an Action Aeon with an executor can
introduce a separately documented lifecycle decision.

### Treat all non-success statuses as cycle failures

Rejected because rejection, temporary unavailability, timeout, and reported execution failure are
observable cognitive outcomes rather than evidence that core orchestration is corrupt.

## Consequences

- Core tests can exercise action outcomes without credentials, network access, or external tools.
- Future adapters translate provider types at the `ActionCapability` edge.
- Evolution and evaluation can consume a typed request/result association without owning action
  execution or requiring a feedback policy in this change.
- Blocking, virtual-thread, retry, cancellation, compatibility, and provider-resource policies
  remain adapter decisions until a concrete integration requires them.
