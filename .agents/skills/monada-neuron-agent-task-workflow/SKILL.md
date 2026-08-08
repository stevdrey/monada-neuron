---
name: monada-neuron-agent-task-workflow
description: Use when preparing implementation plans, Issues, PR reviews, or agent-scoped work for Monada Neuron.
---

# Monada Neuron Agent Task Workflow

## Before Work

1. Read `AGENTS.md`.
2. Read `README.md`, `docs/architecture.md`, and `docs/design-principles.md`.
3. Read relevant ADRs under `docs/adr/`.
4. Inspect current `main`, affected code, tests, and build configuration.
5. Use `docs/specs/spec-context-template.md` to shape implementation work.
6. Use `docs/specs/pr-review-checklist.md` for review.

## Task Shape

A strong task should include:

- background;
- current state;
- concrete goal;
- non-goals;
- affected areas;
- architecture boundaries;
- data-structure/algorithm expectations;
- resource/performance expectations;
- Java 26 or experimental API policy;
- acceptance criteria;
- verification commands;
- documentation/ADR updates.

## Implementation Planning

Prefer the smallest coherent change that advances the architecture.

Do not introduce abstractions only because a future phase might need them. Conversely, do not choose a Phase-1 representation that closes off obvious future high-volume execution paths without documenting the trade-off.

For performance-sensitive work, identify the workload and baseline before prescribing an implementation technique.

## Review Shape

Check:

- goal alignment;
- scope control;
- cognitive vs memory ownership;
- module/package boundaries;
- data structures and complexity;
- Java 26 idioms;
- allocation/resource behavior;
- concurrency safety;
- SIMD/native/GPU fallback and evidence when applicable;
- deterministic behavior;
- tests;
- benchmark reproducibility;
- docs/ADR consistency;
- merge safety.

## Acceptance

The task or review is self-contained enough that another agent can understand what exists, what must change, what must not change, and how success will be verified without guessing the architecture.
