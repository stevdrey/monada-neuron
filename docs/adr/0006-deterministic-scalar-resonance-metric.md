# ADR 0006: Deterministic Scalar Resonance Metric

## Status

Accepted

## Context

Resonance is a first-class cognitive concept in Monada Neuron, but the Phase-1 model previously had
no measurable operation for comparing two frequency states. Future routing, evaluation, and
optimized numeric backends need one inspectable semantic baseline before specialized execution is
considered.

`FrequencyState` contains non-negative finite amplitude and frequency plus finite phase. Signal
intensity is represented by amplitude, while Node energy is separate mutable activation state.
Long-term associative recall and ranking remain owned by Monada Resonance Store.

## Decision

The core defines a narrow `ResonanceMetric` contract over two `FrequencyState` values and provides
`ScalarResonanceMetric` as the deterministic portable reference implementation.

For states `(a1, f1, p1)` and `(a2, f2, p2)`, define:

```text
A = 0                                      if a1 = 0 or a2 = 0
    min(a1, a2) / max(a1, a2)             otherwise

F = 1                                      if f1 = f2
    0                                      if exactly one frequency is zero
    min(f1, f2) / max(f1, f2)             otherwise

w1 = IEEE-remainder(p1, 2*pi)
w2 = IEEE-remainder(p2, 2*pi)
d  = IEEE-remainder(w1 - w2, 2*pi)
P  = (1 + cos(d)) / 2

R = A * F * P
```

The calculation wraps each phase before subtraction so any finite input phase, including extreme
values, avoids subtraction overflow. It uses `StrictMath` for reproducible phase reduction and
cosine behavior.

For valid states, `R` is finite, symmetric, and in `[0, 1]`. Identical non-silent states score `1`.
Any silent state scores `0`, including silence compared with itself. Relative amplitude and
frequency divergence monotonically lowers the corresponding factor when the other factors remain
fixed. Equal numeric scores are ties and the metric performs no ranking or tie-breaking.

The contract accepts only frequency states. Signal callers explicitly extract their
`frequencyState`; `SignalKind`, Node energy, persistence, recall ranking, and storage encodings do
not participate. Null states fail immediately, while invalid numeric components remain rejected by
`FrequencyState` construction.

The reference path uses primitive scalar operations without boxing, streams, temporary
collections, or hidden normalization allocation. It remains available as the correctness oracle
for future optimized implementations.

## Alternatives Considered

### Add the component similarities

Rejected because an additive or weighted average lets strong agreement in one component hide a
complete mismatch in another and introduces policy weights without experimental evidence.

### Use raw sinusoidal correlation

Rejected for the first reference metric because its signed range and amplitude scaling introduce
more complex semantics than the normalized relationship currently required.

### Include Signal kind or Node energy

Rejected because kind is a cognitive role rather than wave content, and Node energy is activation
state outside `FrequencyState`. Including either would broaden the primitive and couple it to a
specific caller.

### Treat two silent states as fully resonant

Rejected because equality of absent signal strength should not create an actionable resonance.

## Consequences

- Cognitive code has a deterministic normalized resonance primitive with explicit semantics.
- Multiplication makes a zero component suppress the complete score.
- The ratio factors measure relative similarity rather than absolute physical distance or harmonic
  relationships.
- Two equally shaped low-amplitude positive states can score highly; the metric measures similarity,
  not absolute activation strength.
- Alternative biological, harmonic, learned, or workload-specific metrics can implement the same
  contract but must be evaluated and selected explicitly.
- Optimized numeric backends must be checked against the scalar reference with a documented
  tolerance; current regression tests use absolute tolerance `1e-12`.

## Follow-Up

Before adding SIMD, native, parallel, or accelerator implementations, define a representative bulk
workload, retain this scalar oracle, and measure semantic equivalence plus end-to-end benefit.
