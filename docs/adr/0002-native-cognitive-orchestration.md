# ADR 0002: Keep Cognitive Orchestration Native to Monada Neuron

## Status

Accepted

## Context

Frameworks such as LangChain, LangGraph, Spring AI, and provider SDKs can accelerate integration work, but they also bring their own abstractions for agents, graphs, memory, tools, and execution.

Monada Neuron has its own cognitive model: Monad, Aeons, Nodes, Signals, resonance, evaluation, adaptation, and action. Mapping the core architecture directly onto an external framework would make those project concepts secondary to the framework's lifecycle.

## Decision

The core cognitive orchestration model is implemented using Monada Neuron concepts and contracts.

External agent frameworks, LLM frameworks, provider SDKs, and model runtimes are optional adapters/capabilities. They may implement a capability used by a Node or Aeon, but they do not own the system lifecycle or define the core domain model.

The preferred conceptual flow remains:

```text
Signal
  -> cognitive processing
  -> optional resonance recall
  -> hypothesis/reasoning
  -> evaluation
  -> adaptation
  -> action
```

rather than a framework-defined prompt/tool/memory loop.

## Alternatives Considered

### Build directly on LangGraph or a similar orchestration graph

Rejected as the architectural foundation because framework concepts would become the primary domain language.

### Build the system as an LLM agent wrapper

Rejected because Monada Neuron is intended to explore cognitive behavior beyond model prompting.

### Avoid external frameworks entirely

Rejected as unnecessarily restrictive. External frameworks can still be useful at adapter boundaries.

## Consequences

- Core tests do not require network access, provider credentials, or a framework runtime.
- Adapters must translate between provider/framework concepts and Monada concepts.
- Some integration code may be more explicit than a framework-first design.
- The cognitive architecture remains replaceable, inspectable, and experimentally controllable.

## Follow-Up

When adding an external framework integration, document the capability it provides, the core contract it implements, failure behavior, lifecycle ownership, and any framework-specific resource costs.
