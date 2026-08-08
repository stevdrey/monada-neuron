# ADR 0003: Java 26 Performance-First and Heterogeneous Compute Strategy

## Status

Accepted

## Context

Monada Neuron is expected to process potentially large numbers of nodes, signals, numeric state transitions, similarity operations, graph relationships, and adaptive updates. Resource efficiency is therefore a system requirement rather than a late-stage concern.

At the same time, premature dependence on specialized native or accelerator stacks would damage portability and make correctness harder to validate.

Java 26 provides a strong CPU/JVM baseline plus APIs that can support advanced execution strategies. Relevant capabilities include the Foreign Function & Memory API and the incubating Vector API. Structured Concurrency remains preview in JDK 26. OpenJDK Project Babylon/HAT explores GPU and heterogeneous acceleration but is not a standard Java SE production API.

## Decision

Monada Neuron will use a layered optimization strategy.

### Portable reference layer

Every performance-critical operation begins with a correct and inspectable Java implementation.

### Algorithm and data-layout layer

Before specialized acceleration, optimize:

- asymptotic complexity;
- selected data structures;
- memory density;
- data locality;
- allocation rate;
- batching;
- unnecessary copying;
- avoidable boxing and indirection.

### CPU specialization layer

When measurements justify it, use:

- primitive/contiguous layouts;
- JIT-friendly loops;
- `jdk.incubator.vector` for SIMD-compatible hotspots;
- bounded CPU parallelism for sufficiently coarse independent work.

### Native/off-heap layer

Use `java.lang.foreign` when explicit layouts, native interop, memory mapping, off-heap storage, alignment, or shared/native memory integration provide a concrete benefit.

### Accelerator layer

GPU or other accelerator support is optional and isolated behind capabilities/backends.

Project Babylon/HAT may be used for experiments, including GPU shared-memory techniques, when its evolving API and runtime requirements are acceptable. Native compute libraries may also be accessed through FFM when a benchmark justifies the integration.

No vendor accelerator API may leak into cognitive domain contracts.

## Selection Rule

Choose the lowest-complexity layer that satisfies the measured requirement.

A faster kernel is not sufficient evidence for an accelerator. Compare end-to-end execution including layout conversion, transfer, compilation/warm-up, synchronization, batching, and fallback.

## Preview and Incubator Policy

Preview/incubator APIs are allowed when:

1. the capability materially improves the experiment or target metric;
2. usage is isolated behind a narrow boundary;
3. build/runtime flags are documented;
4. a migration/removal path exists;
5. a reference or fallback implementation exists where practical;
6. tests protect semantics independently of the experimental API.

## Alternatives Considered

### Restrict the project to stable Java SE APIs only

Rejected because it would unnecessarily prevent controlled experiments with important performance capabilities.

### Make GPU execution the default architecture

Rejected because many cognitive workloads are too small, irregular, latency-sensitive, or transfer-bound to benefit from GPU execution.

### Use native/GPU libraries directly throughout the domain model

Rejected because it couples cognitive semantics to deployment hardware and vendor APIs.

### Optimize only after the system is feature-complete

Rejected because inefficient object/data models can become expensive architectural constraints if scale requirements are ignored until late development.

## Consequences

- Performance decisions require representative benchmarks.
- Reference paths remain important even after optimization.
- Some modules may use preview/incubator flags while core domain code remains portable.
- Data layout becomes an explicit design consideration.
- Accelerator backends must report availability and support graceful fallback.
- Hardware-specific experiments can move quickly without turning the entire project into a hardware-specific codebase.

## Follow-Up

When the first large-scale node/signal workload is implemented, establish reproducible CPU scalar, SIMD, and allocation baselines before selecting more specialized execution paths.
