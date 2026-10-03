# ADR 0020: Deterministic Hypothesis Evaluation Policy

## Status

Accepted

## Context

ADR 0019 gave `REASONING` a typed, bounded `HypothesisSet` and left scoring and top-K selection as
follow-ups. The canonical cycle already names `EVALUATION`, but no stage occupied it and nothing
compared candidates, explained a score, handled ties, or bounded the result. A transparent,
deterministic evaluator is needed before any learned, model-backed, probabilistic, or
accelerator-backed evaluator is tried, in the same way `ScalarResonanceMetric` and the deterministic
graph runtime are the reference semantics for their areas.

Hypothesis evaluation is cognition and belongs to Neuron. Memory retrieval ranking belongs to the
Resonance Store (ADR 0001, ADR 0010) and must not be copied into the evaluator.

## Decision

`monada.neuron.evaluation` defines a Neuron-owned contract, one reference policy, and a typed
`EVALUATION` stage.

**Contract.** `HypothesisEvaluationPolicy.evaluate(HypothesisSet, int maxSelected)` returns an
immutable `HypothesisEvaluation`. Implementations are pure: no mutation of Nodes, adaptation state,
the input set, or shared state, and identical inputs and configuration give equal results.
`maxSelected >= 0`; zero selects nothing and a bound above the candidate count selects all.

**Results.** `HypothesisEvaluation(evaluated, selected, requestedMaxSelected)` holds the scored
`HypothesisSet` and the selected candidates in rank order; `evaluatedCount()` is derived from the set.
Carrying the set (a shared reference, not a copy) means the selected sequences always resolve against
the set they were computed from, so a mismatched evaluation/set pairing cannot be constructed. Each `EvaluatedHypothesis(sequence, HypothesisScoreBreakdown)`
references its candidate by the cycle-local `sequence` (no copy). `HypothesisScoreBreakdown` keeps
`supportMass`, `contradictionMass`, `neutralMass`, the three counts, `resonanceContribution`, and the
final `score`. Constructors validate finiteness, ranges (including `resonanceContribution <= 1`, since
both the weight and the resonance are in `[0, 1]`), the selected-size bound, rank order, and that each
sequence appears at most once. `HypothesisScoreBreakdown` also checks that its evidence
components agree: each mass is zero exactly when its count is zero and never exceeds its count
(weights are at most 1). How `score` derives from the components stays policy-defined and is not
enforced by the record.

**Reference formula.** With `S` the sum of `SUPPORTS` weights, `C` the sum of `CONTRADICTS` weights,
and `R = resonanceWeight * resonance(h)` (zero by default):

```text
score = (S + R) / (S + R + C + 1)            score in [0, 1)
```

The constant 1 is one unit of ignorance. A candidate with no evidence and no resonance contribution
scores 0, thin evidence scores low (one supporting entry of weight 1 gives 0.5), and contradiction
always lowers the score. `NEUTRAL` evidence is counted and reported but does not change the score. Weights are summed in
evidence-list order with primitive `double` accumulation, so results are bit-identical on replay.
Supporting and contradicting evidence stay separate in the breakdown rather than collapsing into one
opaque confidence value.

**Resonance.** Resonance is an optional, injectable `HypothesisResonanceComponent` configured through
`HypothesisScoringConfig(component, weight)` with `weight` in `[0, 1]`; the default `NONE` has no
component, and a zero weight never invokes it. It can only add to the support side: a low value is
absence of support, not contradiction, and `weight <= 1` keeps it from outweighing one fully weighted
supporting entry. Component output must be finite and within `[0, 1]`; anything else fails with
`IllegalArgumentException` instead of being clamped. The core defines no memory or vendor type: deriving the
value from the Resonance Store belongs behind an adapter, and the evaluator never ranks memory.

**Tie rule and selection.** The ranking rule is defined once, in the package-private
`HypothesisRanking`: score descending under `Double.compare` (so `+0.0` ranks before `-0.0`; the
policy itself never produces `-0.0`), then lower `Hypothesis.sequence()` (insertion order). Both
selectors and `HypothesisEvaluation` validation call it, so the heap, the oracle, and the result
validator cannot drift apart. `HypothesisSelector` ranks a `double[]` of scores indexed by sequence.
NaN has no rank and is rejected with `IllegalArgumentException`; infinities rank normally. Two
implementations exist: `BoundedHeapSelector` (primitive int min-heap, O(N log K), K ints of working
memory, no boxing; NaN sorts above every number under `Double.compare`, so it always reaches the
insertion path, where it is rejected, without a separate O(N) pass) and `FullSortSelector` (full
sort, boxed, simple). The reference policy uses the bounded heap by default, chosen from the evidence
below. `FullSortSelector` stays as the semantic oracle and benchmark baseline; tests assert both
produce identical output on tie-heavy random inputs that include `-0.0`.

**Allocation shape.** Scoring ranks every candidate without allocating per-candidate objects. It
needs one `double[]` of scores (8 B per candidate) and, only when a resonance component is configured,
a second `double[]` of resonance contributions (16 B per candidate in total). A
single per-call `EvidenceAccumulator` sums the evidence and holds the score formula; it scores every
candidate and then builds the `HypothesisScoreBreakdown` of the selected ones only, so ranking and
explanation cannot diverge. `HypothesisEvaluation` validates uniqueness pairwise for up to 16
selected candidates (no allocation) and on a sorted copy of the selected sequences above that, so its
working state is bounded by the selection size and never by the candidate count.

**Stage.** `HypothesisEvaluationCognitiveStage(policy, maxSelected)` occupies `EVALUATION` and accepts
any `maxSelected >= 0`, like the policy (zero is an evaluate-without-selection pass). It reads
hypotheses with `ReasoningCognitiveStageResult.hypothesesOf(previousResult)`, and opts in through
`acceptsTypedOnlyHandOff()` so it runs when reasoning produced hypotheses but no output signals. It
returns `EvaluationCognitiveStageResult(status, outputSignals, evaluation)`, a record that keeps the
typed evaluation (never a map; `evaluated()` returns the set the evaluation scored) and passes input signals through so `ADAPTATION` and `ACTION`
still run. `withAdmittedOutputSignals` trims only the signals. The result also validates the
provenance of signal evidence in the evaluated set after the stage, exactly like the reasoning result
(one shared `HypothesisSet.validateSignalProvenance`), so a custom `EVALUATION` stage cannot return
evidence for a signal occurrence the context never accepted (ADR 0019). The stage does not touch Nodes or
adaptation state; mutation remains the responsibility of adaptation/evolution.

## Alternatives Considered

### Noisy-OR composition `support * (1 - contradiction)`
Rejected: a single fully weighted contradiction vetoes a candidate and independence between evidence
entries is assumed implicitly. The smoothed ratio is easier to verify by hand.

### Normalized net balance `(1 + (S - C) / (S + C)) / 2`
Rejected: it does not penalize missing evidence, so one supporting entry and ten score the same.

### Resonance as the definition of truth, or only in the baseline
Rejected: resonance similarity is not proof, and keeping it out entirely would give future adapters
no sanctioned entry point. An optional, bounded, support-only component is the conservative middle.

### Sort all candidates, or `PriorityQueue<Integer>` for top-K
Full sort is retained only as an oracle. A boxed `PriorityQueue` was not benchmarked: the primitive
heap already removes boxing and is simple, and AGENTS.md asks to avoid boxing in high-volume
numeric paths.

### Carry scores in `Hypothesis`, `Signal`, or `CognitiveContext`
Rejected: ADR 0019 omitted a score from `Hypothesis` until an evaluation consumer defined its
semantics, ADR 0005 keeps `Signal` compact, and a context slot turns trace/budget state into a data
bus. A separate typed result avoids all three.

## Consequences

- **Retained size (modeled, 64-bit HotSpot, compressed references).** A result costs 24 B plus a
  list plus 88 B per selected candidate (24 B reference record, 64 B breakdown): 136 B at K = 1 and
  984 B at K = 10, independent of N. The evaluated `HypothesisSet` is shared by reference. This is
  an estimate (`HypothesisEvaluationFootprintModel`), not a measurement.
- **Transient allocation (measured with `-prof gc`, evidence-only scoring).** About 8 B per candidate
  for the score array plus a K-sized result: 80,244 B/op at N = 10,000 and K = 1, and 81,052 B/op at
  K = 10. The full-sort strategy allocates about 330 KB/op at N = 10,000 (4.1x more). With a resonance
  component the policy keeps a second N-sized `double[]`, so it costs about 16 B per candidate
  (161,112 B at N = 10,000, K = 10, measured by `HypothesisEvaluationFootprintTest`, not by JMH).
- Equal inputs give equal results, which keeps whole-cycle replay comparable.
- The formula is a research baseline: it treats evidence as independent additive mass, ignores
  provenance and recency, and is not a calibrated probability.
- No new trace event is added; stage start and completion are already traced.

## Empirical Evidence

Linux x86_64, Intel Core i7-6500U (4 CPUs), Zulu 27+35, G1, evidence profile of 4 entries per
candidate (60% supporting, 25% contradicting, 15% neutral, weights on an eighth grid). Command:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 2 -i 3 -r 1s -prof gc HypothesisEvaluation"
```

Selection only (µs/op, scores on a coarse grid so ties occur):

| N | K | bounded heap | full sort | speedup |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 10 | 0.532 | 4.659 | 8.8x |
| 1,000 | 10 | 2.613 | 152.3 | 58.3x |
| 1,000 | 100 | 19.4 | 153.1 | 7.9x |
| 10,000 | 10 | 20.9 | 1,781 | 85.1x |
| 10,000 | 100 | 56.1 | 1,801 | 32.1x |

The heap was never slower than the full sort in any of the 12 N x K cells: selection was 1.6x to 85x
faster and end-to-end evaluation 1.1x to 5.7x faster. Where K >= N the two are close (for example
2.98 vs 4.75 µs at N = K = 100, and 0.760 vs 0.852 µs end to end at N = 10, K = 10), so no crossover
was observed. The full matrix and end-to-end figures are in `docs/benchmarks/baseline-methodology.md`.
At N = 10,000 the end-to-end cost (about 555 µs) is dominated by the scoring pass (about 528 µs,
53 ns per candidate), not by selection, so a further specialized layout is not justified by these
measurements.

These runs are exploratory, not a gate: three forks and three iterations on a laptop CPU. Four of 52
rows exceed 15% relative error, the worst at 23% (`selectBoundedHeap` at N = 1,000 and 10,000 with
K = 1, `evaluateBoundedHeap` at N = 1,000 and K = 10, `evaluateFullSort` at N = 100 and K = 100);
conclusions rely only on differences much larger than the reported error. `scoreOnly` runs the policy
with a no-op selector so it excludes the selector's NaN scan. The figures include the code merged after
review: the single ranking rule, NaN rejection, the shared evidence accumulator, and the O(K)
uniqueness check. Compared with the heap measured before the shared ranking rule (about 15 µs at
N = 10,000, K = 10), heap selection alone is now about 21 µs because the shared comparator adds work
per element; end-to-end evaluation did not change measurably (555 vs 552 µs), since selection is under
4% of it.

## Follow-up Work

- Optional hypothesis/evaluation trace events (carried over from ADR 0019).
- A Store-backed `HypothesisResonanceComponent` behind an adapter, if a concrete need appears.
- Learned or model-backed policies implementing `HypothesisEvaluationPolicy` and checked against this
  policy as the oracle within a documented tolerance.
- A compact primitive result layout only if retained-size measurements demand it.
