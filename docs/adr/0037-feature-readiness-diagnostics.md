# ADR 0037: Feature Readiness Diagnostics

## Status

Accepted (issue #95; adds Section 5.4 to `forge-integration-readiness/1` under ADR 0033 as a minor, additive bump)

## Context

[Forge Integration Readiness Contract v1](../specs/forge-integration-readiness-v1.md) has a host turn a reviewed task
draft into `TaskFeatures` and a `RoutingRequest` before it calls `decide`. Before that call, a preview needs to explain to
a person which feature facts the host has and which it lacks. Without a shared report, every host pattern matches each
`Feature` itself and invents its own wording, and each host decides on its own whether an unknown field blocks advice,
which drifts toward hosts treating Neuron output as a gate.

The forces:

1. **Completeness is not permission.** Section 2.2 lists what is never permission, and Section 5.1 keeps the readiness
   outcomes (`NOT_READY`, `UNAVAILABLE`, `ADVISED`, `STALE`) as host vocabulary. Anything Neuron adds must stay below
   that line.
2. **A built `TaskFeatures` is already consistent.** It validates bounds, tokens and tags at construction. The only
   inconsistency left is between the snapshot and the request around it: `RoutingRequest` throws when a known
   `features.stageKind()` differs from the request's `stageKind`.
3. **Unsupported schemas are reported, not guessed.** `TaskFeatureEncoder` returns `EncodingResult.SchemaMismatch`.
4. **Hard limits from the issue.** No edit to `TaskFeatures` schema v1, `RouteEligibilityFilter`, `RoutingPreference` or
   ranking; no reading of task text, files or runtime state; no I/O, no dependency, no preview API; bounded allocation.

## Decision

- **`TaskFeatureReadiness`** is a `final` class with a public no argument constructor and one instance method,
  `assess(TaskFeatures features, String expectedStageKind, Set<FeatureDimension> required)`. It is an instance method,
  like `TaskFeatureEncoder.encode`, so a later versioned assessor can replace it without changing call sites.
- **The result is a sealed `FeatureReadinessAssessment`**: `Assessed(features, expectedStageKind, required)` or
  `SchemaMismatch(expected, actual)`, the same shape as `EncodingResult`. A schema other than
  `TaskFeatures.SCHEMA_VERSION` returns `SchemaMismatch` with no rows; no field of a newer schema is read.
- **`Assessed` stores only its inputs.** `diagnostics()` derives exactly eight `FieldDiagnostic` rows, one per
  `FeatureDimension` in declaration order, on each call; `missingForHostPreview()` and `inconsistentWithRequest()` filter
  them. No stored rows means no hand built report can claim more than its inputs support. `required` is copied into an
  unmodifiable `EnumSet`, so any `Set` with the same members gives an equal value. `features` is kept as the same
  instance.
- **Rows carry no values**: dimension, `FieldStatus` (`KNOWN`, `UNKNOWN`, `MISMATCH`), the caller's `required` flag, and
  a `DiagnosticSeverity` (`INFORMATION`, `MISSING_FOR_HOST_PREVIEW`, `INCONSISTENT_WITH_REQUEST`). `MISMATCH` is only
  possible on `STAGE_KIND`, when the known token differs from `expectedStageKind` by `String.equals`.
- **The severity table is fixed** and lives once, in `FieldDiagnostic.of`; the canonical constructor rejects a severity
  that disagrees with it. `KNOWN` gives `INFORMATION`; `UNKNOWN` gives `INFORMATION`, or `MISSING_FOR_HOST_PREVIEW` when
  required; `MISMATCH` gives `INCONSISTENT_WITH_REQUEST` either way. Severity declaration order is not a ranking, and
  there is no boolean, no "highest severity" and no summary verdict.
- **Argument errors throw before the schema check.** Null arguments throw `NullPointerException` with
  `"<name> must not be null"`, an invalid `expectedStageKind` throws through `OpaqueTokens.require`, and a null element
  of `required` throws `"required must not contain null"`. The `Assessed` constructor repeats these checks so it stands
  alone, and throws `IllegalArgumentException` for a non v1 schema.
- **Information only.** Every one of the seven types carries the sentence: Diagnostics are information only: they are
  never eligibility, readiness, authentication or authorization, and no routing decision reads them. Nothing in
  `decide`, `RouteEligibilityFilter`, `RoutingPreference` or ranking reads an assessment.

## Alternatives

- **A helper that returns the unknown required fields as a list.** Smallest surface, but it has no stage kind check and
  no schema handling, and an empty list for a full snapshot looks like a pass, the exact misreading this ADR guards
  against.
- **Readiness accessors on `TaskFeatures`.** Discoverable, but edits the schema v1 type and puts a host preview concern
  into the type that feeds eligibility.
- **A caller configurable severity policy.** Lets each host tune its blockers, but a host could define a severity that
  reads as approval, and two hosts would report the same snapshot differently.
- **Stored rows checked by the constructor.** Equivalent output, but two copies of the status rule, and a hand built
  value would need a full consistency check to stay honest.

## Consequences

- Hosts share one deterministic vocabulary for "what we know about this task", and a stage kind mismatch becomes a
  reportable fact instead of a `RoutingRequest` constructor exception.
- Seven public types join the API covered by the contract versioning rule; removing or renaming one is a breaking change.
- Rows are rebuilt on each `diagnostics()` call (eight rows and one list, constant work) and do not appear in
  `Assessed.toString()`; a host that renders often should keep the list it got.
- The argument checks exist in `assess` and in the `Assessed` constructor and must stay in step.
- A ninth `FeatureDimension` in a future schema changes the row count, a contract change for this report as well.
- `decide`, eligibility, ranking and `TaskFeatures` are unchanged; `FeatureReadinessDecideIndependenceTest` checks that a
  prior `assess` leaves the decision equal.

## Follow-Up

- Issue #96 (human review summaries) should build its "what we knew" part from `FeatureReadinessAssessment.Assessed`
  rather than recomputing field status.
- When receipts (#97) and dispositions (#98) land, they join the Section 2.2 never permission list next to the
  assessment.
