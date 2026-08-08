# Spec Context Template

Use this shape for implementation tasks handled by coding agents.

## Background

Why does this work exist? Which project goal or observed limitation motivates it?

## Current State

Describe what exists in `main` now. Name relevant classes, packages, modules, benchmarks, and constraints.

## Goal

State the concrete outcome expected from the change.

## Non-Goals

State what must remain outside scope.

## Affected Areas

List packages/modules/components expected to change.

## Architecture Boundaries

State any ownership constraints involving:

- Monad/Aeon/Node/Signal responsibilities;
- Monada Resonance Store;
- external models/frameworks;
- native/hardware adapters;
- persistence or interoperability.

## Algorithm and Data-Structure Expectations

For non-trivial algorithms or collections, state expected scale and access patterns.

The implementation should justify material choices using:

- time complexity;
- memory complexity;
- locality/allocation behavior;
- determinism;
- concurrency requirements;
- suitability for primitive/SIMD/accelerator execution when relevant.

## Performance and Resource Expectations

State whether the task is latency-, throughput-, memory-, allocation-, or I/O-sensitive.

If performance is part of the goal, define the baseline workload and the metric to compare.

## Java 26 / Experimental API Policy

State whether preview/incubator/native APIs are allowed for the task. If used, require isolation, documented flags, tests, and a fallback/migration strategy where practical.

## Acceptance Criteria

```text
- [ ] Required behavior is implemented.
- [ ] Tests cover normal and edge cases.
- [ ] Architectural boundaries remain intact.
- [ ] Data-structure/algorithm choices are appropriate for the workload.
- [ ] Performance claims include reproducible evidence when relevant.
- [ ] Experimental/native/accelerator behavior has a reference or fallback path where practical.
- [ ] Documentation/ADR is updated when the decision is durable.
```

## Verification Commands

At minimum:

```bash
./gradlew test
```

Add focused benchmark, integration, preview-feature, or hardware-specific commands when required.

## Risks and Trade-Offs

Call out correctness, determinism, numerical precision, concurrency, memory lifetime, hardware portability, provider lock-in, or migration risks.

## Documentation Updates

List expected README, architecture, ADR, benchmark, or skill changes.
