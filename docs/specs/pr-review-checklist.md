# PR Review Checklist

Use this checklist when validating a Monada Neuron change.

## Goal and Scope

- Does the implementation satisfy the stated goal?
- Is unrelated refactoring kept out of scope?
- Are non-goals respected?

## Cognitive Architecture

- Are Monad, Aeon, Node, and Signal responsibilities preserved?
- Does long-term memory remain owned by Monada Resonance Store?
- Are external models/frameworks/tools behind adapters rather than defining core architecture?
- Does the change require a new or updated ADR?

## Java 26

- Does the code use Java 26 idioms where they improve clarity or efficiency?
- Are records, sealed types, pattern matching, scoped values, or other modern features used only where semantically appropriate?
- Does every new `static` method have a genuine class-level reason?
- Are normal imports/simple names used instead of unnecessary fully qualified type names?
- If preview/incubator APIs are used, are flags, status, isolation, and migration/fallback documented?

## Algorithms and Data Structures

- Is the chosen structure appropriate for the actual access pattern and scale?
- Are time and memory complexity reasonable?
- Is iteration order deterministic where results depend on it?
- Is there avoidable boxing, pointer-heavy layout, copying, or allocation in a hot path?
- Would a primitive/contiguous representation materially improve a measured high-volume path?
- For graph work, does the representation match graph density and mutation rate?
- For top-K or bounded selection, does the implementation avoid unnecessary full sorting when scale makes it material?

## Performance

- Is there a baseline for performance claims?
- Was the algorithm/data layout considered before specialized hardware?
- Are measurements representative and reproducible?
- Are warm-up/JIT effects accounted for?
- Are memory/allocation effects considered, not only elapsed time?

## SIMD / Native / GPU

When applicable:

- Is there a scalar/reference implementation?
- Does SIMD handle tails, small inputs, and numerical tolerance correctly?
- Are `MemorySegment`/`Arena` ownership and lifetimes clear?
- Are native access and platform requirements explicit?
- Does accelerator benchmarking include transfer, conversion, synchronization, and compilation/warm-up?
- Is hardware availability detected rather than assumed?
- Is fallback behavior defined?
- Are vendor-specific types kept outside core domain contracts?

## Concurrency

- Is the concurrency mechanism appropriate for blocking vs CPU-bound work?
- Is CPU parallelism bounded?
- Are cancellation/failure/lifecycle semantics clear?
- Can shared mutable state be reduced through ownership or immutability?
- Are races, false sharing, contention, or unsafe native-memory access possible?

## Correctness and Testing

- Are normal, boundary, invalid, and regression cases covered?
- Are optimized paths compared against a reference implementation?
- Are floating-point tolerances explicit where needed?
- Are tests deterministic?

## Documentation

- Are architecture/design docs updated when behavior or ownership changes?
- Is an ADR added for durable decisions?
- Are benchmark commands and special runtime flags documented?

## Merge Recommendation

Conclude with one of:

- Ready to merge.
- Ready after small fixes.
- Not ready: architectural/performance/correctness issue must be resolved.

List the smallest actionable fixes when changes are needed.
