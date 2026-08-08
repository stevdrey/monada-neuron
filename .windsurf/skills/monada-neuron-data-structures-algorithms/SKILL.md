---
name: monada-neuron-data-structures-algorithms
description: Use when selecting or changing collections, graph layouts, queues, indexes, caches, numeric buffers, or algorithms in Monada Neuron.
---

# Monada Neuron Data Structures and Algorithms

## Context

Read `AGENTS.md`, `docs/architecture.md`, `docs/design-principles.md`, affected code/tests, and representative workload assumptions before choosing a structure.

The current `Node` implementation uses object references, `HashSet` connections, and lazy `ArrayList` history as a Phase-1 correctness baseline. Do not replace it without a measurable or clearly specified scale requirement.

## Core Rule

Select data structures from the workload, not from habit.

For every material structure, answer:

1. What operations dominate: lookup, append, remove, iteration, range scan, top-K, graph traversal, random access, bulk numeric transform?
2. What is the expected element count now and at target scale?
3. Is mutation frequent or mostly build-once/read-many?
4. Is stable ordering required?
5. Is identity object-based or can compact integer ids be used internally?
6. Is memory density important enough to avoid per-element objects?
7. Is the operation SIMD/accelerator-friendly?
8. Is concurrent access required, and what ownership model applies?

## Complexity Is Necessary but Not Sufficient

Document expected asymptotic behavior for non-trivial algorithms, but also consider:

- cache locality;
- pointer chasing;
- object headers;
- boxing;
- branch predictability;
- memory bandwidth;
- allocation and GC pressure;
- synchronization;
- constant factors at realistic sizes.

An O(1) hash lookup is not automatically better than an O(log n) or O(n) alternative for tiny, dense, or cache-friendly data.

## Collection Guidance

### Lists / Sequences

Use `ArrayList` for general indexed/append-heavy object sequences when its growth/allocation profile is acceptable.

Prefer primitive arrays or compact buffers for large numeric sequences or hot loops.

Avoid `LinkedList` unless frequent structural edits at known positions actually outweigh pointer overhead and poor locality. `ArrayDeque` is usually a better queue/deque baseline.

### Sets and Membership

Use `HashSet` for general sparse object membership when hashing/object overhead is acceptable.

Consider:

- `EnumSet` for enum domains;
- `BitSet` or indexed primitive flags for dense bounded ids;
- sorted arrays/binary search for small mostly-static sets;
- compact integer sets only when scale justifies a dependency or custom representation.

Do not rely on hash iteration order for deterministic output.

### Maps

Use `HashMap` for general key lookup when order is irrelevant.

Consider:

- `EnumMap` for enum keys;
- arrays/indexed tables for dense integer ids;
- `LinkedHashMap` only when insertion/access order is part of semantics;
- `TreeMap` for ordered/range operations where its O(log n) cost is justified;
- immutable maps for stable configuration/state snapshots.

Avoid maps when a typed record or direct indexed field is the real model.

### Queues / Scheduling

Use `ArrayDeque` for FIFO/LIFO work queues.

Use `PriorityQueue` for priority scheduling or bounded top-K where heap semantics fit.

For bounded top-K over a large candidate stream, prefer an O(n log k) bounded heap/selection approach over sorting all n candidates when k is materially smaller than n.

## Graph Representation

Choose graph representation from density, mutation rate, traversal pattern, and scale.

### Object adjacency sets

Good for small/moderate mutable graphs where direct object semantics and simple updates matter.

### Compact id adjacency

For large stable sparse graphs, consider integer node ids plus adjacency arrays, CSR-like layouts, offsets, or segmented primitive buffers to reduce per-edge object/hash overhead and improve traversal locality.

### Dense adjacency

For genuinely dense bounded graphs, bit matrices or packed representations may outperform sparse object structures.

Do not prematurely move to compact graph storage if mutation semantics or scale do not justify it.

## Histories and Time-Series State

For state history, define retention semantics first:

- unbounded history;
- bounded ring buffer;
- sampled history;
- event log;
- latest-only state.

Do not let an unbounded `List` become the accidental production retention policy for millions of nodes.

For bounded histories, evaluate circular/ring buffers to avoid repeated shifting and uncontrolled growth.

## Numeric and Signal Layouts

For repeated bulk operations, consider data-oriented layouts.

### Array of Structures (AoS)

Useful when operations consume most fields of one entity together.

### Structure of Arrays (SoA)

Useful when operations repeatedly process one/few numeric fields across many entities and can benefit from sequential access/SIMD.

Example conceptual SoA:

```text
amplitudes[]
frequencies[]
phases[]
energies[]
```

rather than millions of individually allocated state objects in a hot numeric kernel.

Keep domain APIs separate from the physical optimized representation if both clarity and throughput are required.

## Primitive vs Object Identity

For large internal networks, compact integer ids can reduce memory and improve locality compared with UUID/object references in every hot structure.

If compact ids are introduced:

- preserve stable external/logical identity separately;
- document mapping lifecycle;
- prevent stale id reuse bugs;
- keep deterministic mapping where reproducibility requires it.

## Copying and Views

Defensive copies are appropriate at trust boundaries but can be expensive inside high-frequency internal operations.

Distinguish:

- public immutable API safety;
- internal read-only views;
- ownership transfer;
- explicitly borrowed buffers with documented lifetime.

Never remove safety merely to avoid a copy without defining ownership.

## Cache Design

A cache needs an invalidation/ownership policy, memory bound, and measured hit benefit.

Do not add a cache to compensate for an algorithm or data-layout problem.

Prefer bounded caches and explicit metrics over unbounded maps.

## Third-Party Primitive Collections

Libraries such as primitive-specialized collections may reduce boxing/object overhead, but dependencies are not free.

Introduce one only when:

- a representative benchmark shows material benefit;
- JDK structures/primitive arrays are inadequate;
- API leakage is avoided;
- maintenance and compatibility costs are acceptable.

## SIMD / Accelerator Compatibility

When a future SIMD or GPU path is plausible, prefer layouts that can be converted cheaply to contiguous primitive data.

Do not contort ordinary domain code solely for hypothetical accelerator use. Instead, keep a clear boundary between domain representation and execution representation.

## Acceptance

A non-trivial data-structure or algorithm change is complete when:

- workload assumptions are explicit;
- time and memory complexity are understood;
- determinism is preserved where required;
- memory/allocation/locality implications were considered;
- scale-sensitive choices have representative tests or benchmarks;
- simpler alternatives were considered;
- the structure does not accidentally redefine domain semantics.
