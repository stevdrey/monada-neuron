# Monada Neuron

**Monada Neuron** is an experimental artificial intelligence system focused on building a modular, adaptive, and resonance-oriented intelligent architecture.

The goal of this project is **not** to build another LLM wrapper, chatbot, or LangChain clone. The goal is to explore an artificial intelligent system composed of specialized cognitive units that can perceive signals, reason over them, evolve from feedback, and interact with external memory systems through resonance-based protocols.

## Core Objective

Monada Neuron aims to become a native artificial intelligent system based on:

- modular cognitive units;
- signal and frequency-oriented processing;
- resonance-based interaction with memory;
- adaptive reasoning cycles;
- evolutionary feedback loops;
- explicit separation between intelligence, memory, tools, and external models.

In simple terms:

> **Monada Neuron thinks, adapts, and acts. Monada Resonance Store remembers.**

## Architectural Vision

The project follows a layered cognitive architecture:

```text
Primary Monad
   ↓ coordinates
Aeons
   ↓ organize cognitive domains
Nodes
   ↓ execute focused operations
Signals
   ↓ carry information as structured wave/frequency-like data
Resonance Bridge
   ↓ communicates with external resonance memory
Monada Resonance Store
```

## Key Concepts

### Monad

A **Monad** is the primary intelligent identity of the system.

It coordinates the global cognitive state, integrates results from different Aeons, and decides how the system should evolve or act.

A Monad should not be treated as a single LLM, service, class, or prompt. It is the coordinating intelligence layer of the system.

### Aeon

An **Aeon** is a specialized cognitive emanation of the Monad.

Each Aeon represents a major intelligence capability, such as perception, reasoning, evolution, action, or self-monitoring.

Aeons should organize internal nodes and process signals within a clear cognitive purpose.

Examples:

```text
Perception Aeon
Reasoning Aeon
Evolution Aeon
Action Aeon
Constraint Aeon
Self-Monitoring Aeon
```

### Node

A **Node** is a focused operational unit inside an Aeon.

Nodes perform concrete work, such as:

- encoding inputs;
- comparing signals;
- extracting errors;
- evaluating confidence;
- generating hypotheses;
- selecting actions;
- transforming feedback.

Nodes should remain small, composable, measurable, and testable.

### Signal

A **Signal** is the primary information carrier inside Monada Neuron.

Signals should be treated as structured data that may represent text, audio, images, numeric values, behavioral traces, or abstract cognitive states.

The long-term direction of the project is to represent learning and memory-related data through wave-like, frequency-like, or resonance-compatible structures whenever it makes technical sense.

### Resonance Bridge

The **Resonance Bridge** connects Monada Neuron with external resonance memory.

Monada Neuron should not own long-term memory directly. Instead, it should interact with **Monada Resonance Store** through an explicit adapter or protocol.

The bridge is responsible for:

- sending encoded signals to memory;
- requesting resonance-based retrieval;
- receiving resonance matches;
- preserving boundaries between cognition and storage;
- avoiding memory logic duplication inside Monada Neuron.

## Relationship with Monada Resonance Store

Long-term memory, resonance-based storage, retrieval, ranking, and diagnostics belong to the separate project:

```text
monada-resonance-store
```

Monada Neuron consumes that system as an external memory capability.

This boundary is intentional:

```text
Monada Neuron
   ├── perception
   ├── reasoning
   ├── evolution
   └── action

Monada Resonance Store
   ├── memory
   ├── resonance retrieval
   ├── storage
   ├── ranking
   └── evaluation diagnostics
```

## Relationship with LangChain and LangGraph

LangChain, LangGraph, Spring AI, OpenAI, Ollama, Groq, and similar systems may be useful as external integrations.

However, they should not define the core architecture of Monada Neuron.

Monada Neuron should use a native orchestration model aligned with its own concepts:

```text
Signal → Aeon → Resonance → Hypothesis → Evaluation → Adaptation → Action
```

This is intentionally different from the common LLM application flow:

```text
Prompt → LLM → Tool → Memory → Response
```

Recommended approach:

```text
Monada Neuron Core
│
├── Native Aeon orchestration
├── Native signal pipeline
├── Native resonance protocol
├── Native evolution loop
│
└── Optional external adapters
    ├── LangChain / LangGraph
    ├── Spring AI
    ├── OpenAI-compatible models
    ├── Ollama
    ├── Groq
    └── External APIs / tools
```

LangChain should be treated as an optional adapter, not as the foundation.

## Proposed Initial Modules

The initial implementation may evolve around these modules:

```text
monada-neuron-core
monada-neuron-aeon
monada-neuron-signal
monada-neuron-evolution
monada-neuron-resonance-adapter
monada-neuron-langchain-adapter
```

The exact module structure may change as the project matures, but the architectural boundaries should remain clear.

## Consuming Neuron as a Library

Monada Neuron is published as a plain Java library artifact. Java 27 is the supported baseline, and the artifact has
no runtime dependencies.

| | |
| --- | --- |
| Coordinates | `monada.neuron:monada-neuron:0.1.0-SNAPSHOT` |
| JDK | 27 |
| Contents | Core contracts and runtime, including the host API (`monada.neuron.host`), plus sources and Javadoc jars |

Publish to your local Maven repository (no remote repository is involved):

```bash
./gradlew publishToMavenLocal
```

Then depend on it from another Gradle project:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("monada.neuron:monada-neuron:0.1.0-SNAPSHOT")
}
```

**Vector API.** The SIMD resonance backend uses the incubating `jdk.incubator.vector` module and is optional. A
consumer on the portable path needs no extra flag and no incubator type appears in the API. Without the module, Neuron
falls back to the scalar reference implementation; to opt into SIMD, run the JVM with
`--add-modules jdk.incubator.vector`.

**Core library vs. evaluation.** `monada-neuron-evaluation` (benchmarks, JMH, baseline runners) is a
development-only module. It is not part of the published artifact and never a transitive dependency.

To verify the contract end to end, `./gradlew consumerSmokeTest` publishes to a repository-local directory and builds
the standalone `consumer-fixture/` project against it with no incubator flags. The standalone demo entry point is run
with `./gradlew runDemo`; it is a plain task, so it does not affect library consumers. See
[ADR 0025](docs/adr/0025-consumable-library-packaging.md).

## Embedding Neuron in a Host Application

A Java host configures a `NeuronRuntime` once and executes bounded cognitive cycles with it, without
wiring the cycle's stages itself. Memory and action capabilities are optional and stay behind
`ResonanceMemoryPort` and `ActionCapability`.

```java
var runtime = NeuronRuntime.builder()
        .monad(primaryMonad)                          // your PrimaryMonad with its registered Aeons
        .stage(reasoningStage)                        // any CognitiveStage, in any order
        .memoryPort(memoryPort, 8)                    // optional: recall up to 8 results
        .actionCapability(actionCapability, 4)        // optional: up to 4 observations
        .defaultBudget(new CognitiveBudget(1_000, 4_000, 256))
        .build();

CognitiveCycleResult result = runtime.execute(inputSignals);            // default budget
CognitiveCycleResult bounded = runtime.execute(inputSignals, budget);   // per-call budget
```

Alternatively, a runtime with a perception capability takes its initial signals from the host adapter, which
resolves the opaque `HostExecutionContext` ([ADR 0024](docs/adr/0024-host-perception-capability-boundary.md)).
Such a runtime is signal-less: executions supply the context and no signals, and a non-empty signal list is
rejected. Neuron does not prescribe a universal text or task encoder.

```java
var perceiving = NeuronRuntime.builder()
        .monad(primaryMonad)
        .stage(reasoningStage)
        .perceptionCapability(perception, 16)         // host observation -> up to 16 OBSERVATION signals
        .defaultBudget(new CognitiveBudget(1_000, 4_000, 256))
        .build();

CognitiveCycleResult perceived = perceiving.execute(
        CycleInput.of(List.of()).withHostContext(hostContext));
```

To close the feedback loop of [ADR 0021](docs/adr/0021-cross-cycle-outcome-feedback-handoff.md) with the
signal-driven runtime, configure `.feedbackAdaptation(adaptationPolicy, targetNodes)` once and hand each cycle the feedback
derived from the previous one:

```java
CycleInput next = feedbackPolicy.derive(result, targetNodeIds, ordinal)   // Optional<OutcomeFeedback>
        .map(feedback -> CycleInput.of(nextSignals).withPriorFeedback(feedback))
        .orElseGet(() -> CycleInput.of(nextSignals));
CognitiveCycleResult nextResult = runtime.execute(next);
```

The result is the cycle's own `CognitiveCycleResult` (termination, stage results, output signals,
snapshot) and failures propagate as `CognitiveCycleException`. The host prepares input Signals and
interprets the outputs; a runtime is sequential and not thread-safe. Its composition is static, and
per-cycle data (signals, budget override, prior feedback) travels in `CycleInput`. See
[ADR 0022](docs/adr/0022-embeddable-host-runtime-facade.md).

## Forge Routing (contract proposed; first slices implemented)

Monada Forge needs vendor-neutral, stage-level worker/model recommendations that learn from validated outcomes. The
versioned contract is defined in [Forge Routing Contract v1](docs/specs/forge-routing-contract-v1.md) and recorded in
[ADR 0026](docs/adr/0026-forge-routing-ownership.md). The contract is the normative design: `TaskFeatures` and its signal encoder
(`monada.neuron.routing.features`, issue #60) and the immutable route catalog with fail-closed eligibility filtering
(`monada.neuron.routing.catalog`, issue #61) and the explainable lexicographic `RoutingPolicy` with `RoutingDecision`
(`Selected`, `Abstain`, `NoEligibleRoute`) and the optional `RoutingReasoningStage` (`monada.neuron.routing`, issue #62)
are implemented, while the other routing types it names are proposals that issues #63 to #68 implement, all additive and opt-in, and the Monada Resonance Store capabilities it depends on are not yet
available. A routing decision is advice. Forge keeps authorization, execution and evidence judgment, and Monada Resonance Store
keeps persistent experience.

## Design Principles

### 1. Do not build an LLM wrapper

LLMs can be used as external capabilities, but Monada Neuron should not depend on an LLM as its identity or core intelligence model.

### 2. Keep cognition separate from memory

Monada Neuron is responsible for cognitive processing.

Monada Resonance Store is responsible for long-term resonance memory.

### 3. Prefer measurable behavior

Each phase should include evaluation, diagnostics, or observable metrics before optimization.

### 4. Preserve modular evolution

Aeons and nodes should be replaceable, testable, and evolvable without rewriting the whole system.

### 5. Use Java as a first-class implementation language

The project is intended to be implemented in modern Java, using the latest stable language features whenever they make sense.

### 6. Treat resonance as a first-class concept

Resonance should not be only a metaphor. It should progressively become a measurable relationship between signals, patterns, states, and outcomes.

## Non-Goals

This project is not intended to be:

- a chatbot clone;
- a LangChain clone;
- a generic RAG application;
- a traditional neural network framework;
- a simple prompt orchestration layer;
- a database replacement;
- a replacement for Monada Resonance Store.

## Long-Term Direction

The long-term research direction is to explore whether an artificial intelligent system can emerge from coordinated specialized units that:

1. receive signals;
2. encode signals into resonance-compatible representations;
3. reason over relationships and patterns;
4. retrieve related experience from external resonance memory;
5. evaluate outcomes;
6. extract error;
7. evolve internal strategies;
8. act through explicit capabilities;
9. preserve a coherent cognitive identity over time.

## Guiding Statement

> **Monada Neuron is a modular artificial intelligence system built around signals, Aeons, resonance, adaptation, and evolution. It may use LLMs, LangChain, or external tools, but it must not become dependent on them as its core architecture.**

## License

Monada Neuron is licensed under the [Apache License 2.0](LICENSE).

This license permits use, modification, distribution, and commercial integration while preserving attribution and patent protections.
