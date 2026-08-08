---
name: monada-neuron-cognitive-architecture
description: Use when changing Monad, Aeon, Node, Signal, resonance, evolution, action, or external capability boundaries.
---

# Monada Neuron Cognitive Architecture

## Context

Read `README.md`, `AGENTS.md`, `docs/architecture.md`, `docs/design-principles.md`, and the accepted ADRs before changing cognitive concepts.

Inspect the current Phase-1 model under `src/main/java/monada/neuron/model` before introducing parallel abstractions.

## Core Ownership

- Monad coordinates global cognitive identity/state.
- Aeons organize coherent cognitive capabilities.
- Nodes execute focused operations.
- Signals carry explicit structured information.
- Evolution/adaptation transforms behavior from evaluated outcomes.
- Actions cross explicit capability boundaries.
- Long-term memory belongs to Monada Resonance Store.
- LLMs, frameworks, tools, and hardware backends remain optional adapters.

## Principles

- Do not model the Monad as an LLM client, controller service, or framework agent.
- Do not let Aeons become arbitrary dependency containers.
- Keep Nodes focused, measurable, replaceable, and independently testable.
- Prefer typed signal/domain models over opaque maps or framework payloads.
- Preserve explicit state transitions and observable outcomes.
- Keep memory calls visible through a resonance port/adapter.
- Keep provider-specific and hardware-specific types outside core contracts.
- Do not create a new module until ownership or dependency isolation justifies it.

## State Design

Classify state before storing it:

1. immutable domain configuration;
2. active-cycle/working state;
3. adaptive/evolution state;
4. long-term remembered experience.

Only categories 1-3 belong naturally in Monada Neuron. Category 4 belongs in Monada Resonance Store.

When state is large or frequently updated, consider whether an object-rich model is still appropriate or whether compact indexed/numeric storage should back the domain abstraction.

## Extensibility

Core capabilities should be expressed as contracts that allow:

- deterministic local implementations;
- experimental implementations;
- model-backed implementations;
- hardware-accelerated implementations;
- fake/reference implementations for tests.

Avoid interfaces that merely mirror a third-party SDK.

## Architecture Decision Trigger

Create/update an ADR when the change establishes:

- new ownership between Monad/Aeon/Node/Signal;
- new cross-project responsibility;
- new state lifetime category;
- new module/dependency direction;
- new external capability contract;
- new hardware/native boundary;
- new durable signal semantics.

## Acceptance

The design remains expressible in Monada concepts, core behavior is testable without external providers, memory ownership is not duplicated, and new boundaries are documented when durable.
