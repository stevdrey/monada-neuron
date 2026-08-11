# Architecture

## Overview

Monada Neuron is an experimental Java 26 cognitive system whose architecture separates cognition from long-term memory and from optional external AI/tool integrations.

The current implementation is intentionally small. The repository presently contains the Phase-1 node model (`Node`, `FrequencyState`, `NodeType`), the immutable signal model and node-processing contract, plus Gradle scaffolding. The module structure below is therefore a **target architecture**, not a claim that every module already exists.

## Core Cognitive Flow

```text
Input / Observation
  -> Signal
  -> Perception / Interpretation Nodes
  -> Aeon-level coordination
  -> Resonance request when memory is needed
  -> Hypothesis / Reasoning
  -> Evaluation
  -> Adaptation / Evolution
  -> Action
  -> Outcome / Feedback
```

The flow is intentionally native to Monada Neuron. External LLMs or agent frameworks may participate through adapters, but they do not define this lifecycle.

## Conceptual Layers

```text
Primary Monad
  -> coordinates identity and global cognitive state

Aeons
  -> organize coherent cognitive capabilities

Nodes
  -> execute focused operations

Signals
  -> carry explicit structured information between cognitive stages

Adapters
  -> connect memory, models, tools, devices, and accelerators
```

## Current Phase-1 Model

The current domain baseline is under `src/main/java/monada/neuron/model`.

`Node` currently provides:

- UUID-based identity;
- a mutable current `FrequencyState`;
- scalar energy;
- `NodeType` classification;
- directed node connections;
- lazily allocated state history.

This representation prioritizes correctness and inspectability. It should remain the reference behavior until scale measurements justify a different internal representation.

In particular, a future compact graph representation must not silently alter node identity, connection semantics, state transition behavior, or deterministic iteration requirements.

## Current Signal and Processing Model

`Signal` is an immutable, cycle-ephemeral information carrier composed of a modality-neutral `SignalKind` and a `FrequencyState`. Its amplitude is the signal intensity; the signal does not duplicate energy, identity, or sequence metadata.

Signal has no durable or cycle-local identity. Correlation and tracing belong to a surrounding cognitive context when required, rather than to the signal value. Signal equality is structural across its kind and frequency state.

`NodeProcessor` receives a `NodeView` and one input signal. The view exposes node identity, type, energy, and frequency state for observation but excludes graph connections and mutation operations. Processing therefore emits its complete observable output through `NodeProcessingResult` rather than changing the node. State transition and adaptation remain separate explicit behaviors.

`NodeProcessingResult` owns an immutable snapshot of its emitted signals. The list order is the observable emission order, including duplicate signals. An empty list represents successful processing with no output; failures propagate to the caller rather than being hidden as empty output.

## Target Module Boundaries

As the repository grows, prefer boundaries similar to:

| Area | Responsibility |
| --- | --- |
| `monada-neuron-core` | Monad, Aeon, Node contracts, shared cognitive primitives and invariants. |
| `monada-neuron-signal` | Signal types, transformations, routing metadata, encoding boundaries. |
| `monada-neuron-aeon` | Aeon coordination and cognitive capability composition. |
| `monada-neuron-evolution` | Feedback, adaptation policies, strategy evolution and outcome learning. |
| `monada-neuron-resonance-adapter` | Explicit protocol/adapter to Monada Resonance Store. |
| `monada-neuron-action` | Tool/action contracts and execution boundaries. |
| `monada-neuron-evaluation` | Cognitive benchmarks, diagnostics, reproducible experiments and regression baselines. |
| optional adapters | LLM providers, LangChain/LangGraph, Spring AI, hardware accelerators, native libraries. |

Do not create modules merely to match this table. Split only when ownership, dependency direction, build isolation, or independent evolution justifies it.

## Memory Boundary

Long-term memory belongs outside this repository:

```text
Monada Neuron
  -> asks for memory through an explicit resonance interface

Monada Resonance Store
  -> persists knowledge
  -> encodes/indexes persisted memory
  -> performs associative recall and ranking
  -> owns compatibility of stored memory formats
```

Neuron may hold ephemeral working state needed for an active cognitive cycle. Ephemeral state must not become an accidental second long-term memory subsystem.

## External Model Boundary

External models are capabilities, not identity:

```text
Cognitive core
  -> ModelCapability interface / adapter
     -> local model
     -> OpenAI-compatible model
     -> Ollama
     -> Groq
     -> other provider
```

The core must remain testable without network calls or provider credentials.

## Data-Oriented Execution

Performance-critical cognitive workloads should be designed around their access patterns.

Potential representations include:

- object-rich domain models for low-volume orchestration and clarity;
- primitive arrays or memory segments for dense numeric state;
- structure-of-arrays layouts for repeated SIMD-friendly operations over many nodes/signals;
- compact integer ids and adjacency arrays for large stable sparse graphs;
- bitsets or indexed flags for dense membership/state masks;
- bounded heaps or selection algorithms for top-K operations.

No representation is universally preferred. The chosen representation must be justified by workload, memory footprint, locality, update pattern, and measured behavior.

## CPU Execution Strategy

The default portable execution path is the CPU/JVM implementation.

Optimization order should generally be:

```text
correct scalar implementation
  -> algorithm/data-structure improvement
  -> allocation/layout improvement
  -> JIT-friendly primitive loops
  -> Vector API SIMD where beneficial
  -> bounded CPU parallelism where beneficial
```

The scalar/reference path should remain available for correctness testing when an optimized implementation becomes difficult to inspect.

## Native Memory and FFM

The Foreign Function & Memory API may be used for:

- off-heap numeric buffers;
- explicit memory alignment;
- memory-mapped data;
- interoperability with native libraries;
- controlled native/shared-memory adapters;
- reducing object overhead where a contiguous layout is demonstrably beneficial.

Memory ownership, `Arena` lifetime, alignment, endianness, concurrency, and failure behavior must be explicit. Off-heap memory is not automatically faster than heap memory; its use requires evidence or a clear interoperability requirement.

## SIMD

The Java 26 Vector API is an incubating API and may be used for vectorizable numeric hotspots such as bulk signal transforms, similarity operations, normalization, or activation updates.

Every SIMD implementation should have:

1. a scalar/reference equivalent;
2. tests for tails and small inputs;
3. numerical tolerance defined where floating-point reordering matters;
4. benchmark evidence on relevant CPU architectures;
5. no assumption that one preferred vector width is optimal everywhere.

## Heterogeneous / GPU Execution

Java SE 26 does not define a standard production GPU-offload API. Accelerator support therefore belongs behind an explicit optional boundary.

Potential experimental paths include:

- OpenJDK Project Babylon / HAT for Java-authored accelerator kernels;
- FFM bindings to native compute libraries;
- vendor-neutral OpenCL-style backends where appropriate;
- vendor-specific CUDA or other accelerator libraries when a concrete experiment justifies them.

GPU execution must not contaminate domain APIs with vendor types.

A GPU path is considered beneficial only when end-to-end measurement includes:

- host-to-device and device-to-host transfer;
- data layout conversion;
- kernel compilation/warm-up;
- synchronization;
- batching requirements;
- actual kernel time;
- fallback behavior.

## Concurrency Architecture

Use different concurrency mechanisms for different workload classes:

- virtual threads: blocking I/O and high-concurrency orchestration;
- structured concurrency: coordinated task lifetimes and failure/cancellation propagation when preview usage is accepted;
- bounded CPU worker parallelism: CPU-bound independent computation;
- partitioned ownership: mutable hot state when avoiding synchronization is possible.

Avoid unbounded CPU fan-out and shared mutable global registries.

## Dependency Direction

Preferred direction as the architecture grows:

```text
external adapters
       |
       v
application / coordination
       |
       v
Aeons / evolution / action
       |
       v
signals + core cognitive contracts
```

The resonance adapter depends on a memory protocol/client, not on storage implementation internals.

Evaluation code may depend on production modules. Production modules must not depend on evaluation-only code.

## Architecture Change Rule

A change should receive an ADR when it establishes or materially changes:

- cognitive ownership;
- memory ownership;
- signal semantics;
- module/dependency boundaries;
- a persistent or interoperability contract;
- a concurrency model;
- a hardware acceleration contract;
- reliance on preview/incubator APIs;
- a performance-critical data layout.
