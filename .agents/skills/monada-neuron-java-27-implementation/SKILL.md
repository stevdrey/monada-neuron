---
name: monada-neuron-java-27-implementation
description: Use when implementing or reviewing Java 27 code, APIs, domain models, concurrency, native memory, or tests for Monada Neuron.
---

# Monada Neuron Java 27 Implementation

## Context

Read `AGENTS.md`, `build.gradle.kts`, `settings.gradle.kts`, affected source/tests, and relevant ADRs before changing Java code. Preserve the owning component's domain model unless the task explicitly requires an architectural change.

## Baseline

- Java toolchain: Java 27.
- Build: Gradle Kotlin DSL.
- Tests: JUnit 5.
- Dependencies should remain minimal.
- The JDK is the preferred first source of platform capabilities.

## Modern Java Usage

Use modern features when they improve semantics, safety, or efficiency rather than for novelty.

Prefer where appropriate:

- records for immutable value aggregates;
- sealed classes/interfaces for intentionally closed domain hierarchies;
- pattern matching for exhaustive and readable domain dispatch;
- switch expressions for value-oriented branching;
- local variable type inference when the initializer keeps the type obvious;
- scoped values for immutable contextual data that must flow through call stacks or child tasks;
- virtual threads for high-concurrency blocking I/O/orchestration;
- Structured Concurrency when JDK 27 preview usage is accepted and coordinated lifecycle/cancellation benefits justify it;
- Foreign Function & Memory API for explicit native/off-heap interoperability or layouts;
- Vector API for measured SIMD-compatible hotspots.

Do not enable preview/incubator features repository-wide merely because one experiment needs them. Scope flags/dependencies as narrowly as the build allows.

## Static Members

Treat `static` as a semantic design choice, not an IDE convenience.

- Behavioral/domain/service methods are instance methods by default.
- Do not make a helper `static` merely because it currently reads no instance field.
- Private helpers that participate in an object's behavior should remain instance methods unless they are genuinely class-level operations.
- Appropriate static operations include intentional named factories, pure class-level conversions, constants, and deliberately utility-oriented APIs.
- Do not convert existing instance/static APIs without a design reason required by the task.

Good:

```java
final class SignalNormalizer {
    private final NormalizationPolicy policy;

    Signal normalize(Signal signal) {
        return applyPolicy(signal);
    }

    private Signal applyPolicy(Signal signal) {
        return policy.normalize(signal);
    }
}
```

Avoid making `applyPolicy` static only because the first implementation could technically do so.

## Imports and Type Names

Use explicit imports and simple type names.

Do not write fully qualified names in method signatures, record components, fields, locals, generic arguments, casts, or constructor calls when an import can express the type unambiguously.

Fully qualified names are acceptable only for a real simple-name collision, syntax constraint, generated/external contract, or similarly concrete reason.

Avoid wildcard imports.

## Domain Modeling

- Validate invariants at construction boundaries.
- Prefer immutable values for signal/state snapshots when mutation is not required.
- Keep mutable state ownership explicit.
- Do not expose mutable internal collections directly.
- Define equality from stable identity/value semantics, not incidental mutable fields.
- Avoid generic `Map<String, Object>` domain payloads when a typed record/sealed hierarchy can represent the concept.

## Allocation and Numeric Code

For hot numeric paths:

- prefer primitive storage over boxed collections when scale justifies it;
- avoid stream pipelines that create material boxing/allocation overhead in a measured hotspot;
- avoid repeated temporary arrays/collections when a reusable or direct layout is safe;
- make bounds, alignment, and dimensions explicit;
- keep simple counted loops when they are the clearest form for JIT/SIMD optimization.

Do not replace clear code with allocation-avoiding complexity without evidence.

## Foreign Function & Memory API

When using `java.lang.foreign`:

- make `Arena` ownership and lifetime explicit;
- use documented `MemoryLayout`/alignment and byte order;
- isolate restricted/native access;
- avoid leaking raw native addresses into domain APIs;
- validate sizes before arithmetic/access to prevent overflow and out-of-bounds behavior;
- document concurrency rules for shared segments;
- distinguish off-heap optimization from required native interoperability.

Memory-mapped or native/shared-memory integrations should live behind narrow adapters.

## Vector API

`jdk.incubator.vector` remains incubating in JDK 27.

Use it when:

- the operation is numeric and data-parallel;
- contiguous primitive data is available or can be obtained cheaply;
- profiling identifies the operation as material;
- a scalar/reference implementation exists.

Handle vector tails correctly and test numerical differences caused by floating-point reordering.

Do not hard-code assumptions about one CPU vector width unless the implementation is intentionally architecture-specific.

## Concurrency

- Virtual threads are for blocking/high-concurrency tasks, not automatically for CPU-bound loops.
- Bound CPU parallelism to sensible compute capacity and task granularity.
- Prefer immutable data, partitioned ownership, or structured lifetimes to fine-grained shared mutation.
- Make interruption, cancellation, and failure propagation explicit.
- Avoid `parallelStream()` as a reflex; use it only when its common-pool semantics are acceptable and benchmarked.

## Error Handling

Fail clearly at architectural and data-integrity boundaries. Prefer precise exceptions and messages over silent fallback when invalid state would corrupt reasoning.

Hardware/native capability absence is different from corrupt input: optional acceleration may fall back, while invalid domain state should fail predictably.

## Implementation Review Checklist

Before considering Java work complete, verify:

- Java 27 features are used intentionally rather than ceremonially.
- Every new `static` method has class-level semantics.
- Simple type names/imports are used wherever unambiguous.
- Domain invariants and mutable ownership are explicit.
- Hot paths avoid unnecessary boxing/allocation when relevant.
- Preview/incubator/native usage is isolated and documented.
- SIMD/native/accelerated paths have semantic tests against a reference implementation.
- Concurrency matches the workload type.
- `./gradlew test` passes.

## Acceptance

The implementation is idiomatic for Java 27, preserves project boundaries, uses modern APIs where they provide concrete value, and does not introduce accidental utility-style design, unnecessary qualification, or unmeasured performance complexity.
