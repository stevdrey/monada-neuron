# ADR 0043: Hierarchical Next-Concept Predictor

## Status

Accepted (issue #127; Epic #120; implements section 8 of `ncm-wave-cognition/1` under ADR 0034; composes ADR 0019
and ADR 0041 without amending them)

## Context

`main` decomposes samples into a `SpectralPattern` (ADR 0035, ADR 0036, ADR 0040) and compares two patterns with
`SpectralResonanceDynamics` (ADR 0041), but nothing proposes which host-labeled concept the evidence supports. The
[NCM Wave Cognition Experiment Contract v1](../specs/ncm-wave-cognition-v1.md) makes #127 the owner of the
coarse-to-fine predictor (section 8), its bounds (section 11.1: 256 concepts, depth 3, 16 candidates per level), the
fixtures P15, P16 and N9 to N12, and gate G3. The contract sketch (section 3.4) leaves the model snapshot, the score
formula and several result details to this issue.

Gate G2 was **no-go** ([spectral-resonance-h2.md](../benchmarks/spectral-resonance-h2.md)): the phase-sensitive
aggregate is at chance on the concept fixture, so section 15 lets #127 proceed **without resonance features**.

## Decision

New types only, in the focused package `monada.neuron.reasoning.concept`; no existing type changes.

- **Hierarchy.** `ConceptHierarchy.of(domain, nodes)` validates the section 8.1 rules and rejects, never repairs:
  ordinal equal to position, parents strictly before children (so a self or forward reference, which is the only way
  to write a cycle, is rejected), depth = parent depth + 1 and below `MAX_DEPTH = 3`, `1..MAX_CONCEPTS = 256` nodes,
  unique codes. Children are stored in CSR form and codes in a sorted array searched by binary search: no hashing, no
  per-node collection. `proposition(ordinal)` is the ADR 0019 bridge, `Proposition(domain, conceptCode)`.
- **Snapshot.** `ConceptModel` is immutable and caller owned (contract section 3.3), built only by
  `ConceptModelTrainer` from caller-owned `LabeledSequence`s, so it can always be rebuilt by replay. It holds the
  expected DFT frame, one unit centroid per concept over the **phase-blind unit magnitude spectrum** (an inner concept
  pools its leaves' exemplars) in one contiguous `double[concepts × bins]`, exemplar counts, leaf-to-leaf transition
  counts and leaf marginals, a softmax temperature and per-depth thresholds. Labels must be leaves; nothing is learned
  from unlabeled data.
- **Scores.** Per-level spectral comparisons: each concept with exemplars is compared once (cosine with its
  centroid), siblings are softmax-normalized at temperature `T`, and the products along each root-to-leaf path give
  the spectral evidence of the current leaf. It is multiplied by a Laplace-smoothed prior from the previous concept's
  transition row (an inner previous concept sums its leaves' rows; none: the leaf marginal), normalized, and pushed
  through the smoothed transition table to a next-leaf distribution. A concept's score is the mass of its subtree:
  over the roots at depth 0, conditional on the selected parent deeper. All scores are in `[0, 1]`; they are not
  called probabilities unless held-out calibration supports it (section 8.3, reported by the G3 evaluation).
- **Descent and abstention.** Coarse to fine (section 8.2); ranking score descending then lower ordinal; a bounded
  insertion selection keeps the top `maxCandidatesPerLevel` (and always the runner-up, for the margin). A depth fails
  on `margin < τ_margin[d]` (`LOW_MARGIN`, checked first so exact ties land there) or `score < τ_conf[d]`
  (`LOW_CONFIDENCE`). Before scoring: `INCOMPATIBLE_INPUT` (version or frame), `UNKNOWN_CONCEPT` (N9),
  `BUDGET_EXHAUSTED` (more comparisons than the budget), `LOW_CONFIDENCE` for a model without exemplars or silent
  evidence. Abstentions are values, never exceptions.
- **Refinements of the section 3.4 sketch.**
  - Thresholds are **per depth** (`ConceptThresholds`): a depth with two candidates has a top score of at least
    `0.5`, so one global `τ_conf` cannot serve every depth.
  - `ConceptCandidates` carries the confident `ConceptLevel`s from depth 0 to the selected concept, an optional
    `refinementStop` reason and the `stoppedLevel` diagnostics for a coarse-only result, so "coarse-only" is explicit.
  - `PredictionBudget(maxCandidatesPerLevel, maxComparisons, maxLevels)`: the candidate bound limits diagnostics,
    the comparison bound abstains, and the level bound stops the descent coarse-only with `BUDGET_EXHAUSTED`.
  - Every result carries `PredictionWork` (comparisons, candidates scored, levels visited) and, once scoring ran,
    the full `ConceptScores` table (similarity, current and next subtree mass per concept), so evaluations build their
    controls from exactly the same scoring inputs.
  - `supportingComponents` are the evidence component positions with the largest positive contribution
    `f_k · c_k` to the candidate's similarity, at most 8, strongest first.
- **Determinism and cost.** `StrictMath`, ascending summation order, no hashing, no streams, no concurrency. A call
  costs O(bins) for the feature, O(S · bins) for S concepts with exemplars, O(L²) for the transition mix and O(m · K)
  per visited depth; six arrays of O(concepts + bins) are allocated per call.

## Alternatives

### Use the resonance aggregate or coherence as the similarity

Rejected for v1: G2 is no-go, and the aggregate is at chance on the fixture because window phases are independent.
The magnitude cosine is phase blind. Re-introducing resonance needs its own protocol and gate.

### Score only along the descent (compare the children of the selected concept only)

Rejected for the reference: the next-concept mass of a subtree depends on the current posterior of every leaf, so a
pruned search would change the semantics, not only the cost. The full comparison is O(concepts · bins), bounded by
the budget, and reported. A pruned variant can be evaluated later against this reference.

### One global confidence threshold, or abstention at every depth

Rejected. Scores of different depths live on different scales, and the contract makes a failure below depth 0 a
coarse-only answer, not an abstention.

### A learned classifier (logistic regression, a neural network) over the spectrum

Rejected for the reference: it adds iterative training, hidden weights and a dependency on optimizer settings, while
the contract asks for a minimal, replayable snapshot of centroids and transition counts.

### Store exemplars or centroids in Monada Resonance Store

Rejected (ADR 0001): the snapshot is a caller-owned value passed into each call; persisted recall and ranking stay
in the Store.

## Consequences

- Hosts and evaluations can propose concept hypotheses from labeled spectral evidence with explicit abstention; the
  single-wave path and every existing type are unchanged.
- Calibration, coverage and the value of the hierarchy are empirical questions answered by the G3 report
  ([next-concept-g3.md](../benchmarks/next-concept-g3.md)). G3 is **no-go**: H3 misses its margin in four of five
  repeats, abstention is not informative (H6), and a flat decision over the same score tables is at least as accurate
  (H4 not supported). #128 does not start; the predictor stays a library-only experiment.
- The model snapshot is about `8 · concepts · bins` bytes (4.2 MB at the largest bounds).
- Phase information is unused by this predictor.
- The G3 temperature was selected in-sample on the training windows (as pre-registered), so the reported calibration
  is pessimistic; a held-out temperature choice needs a new protocol.

## Follow-Up

- #128 (blocked: G3 is no-go): would wire the predictor into an existing reasoning stage; decide in its own ADR whether
  spectral components need an `Evidence` variant.
- #129: outcome feedback producing a new snapshot (ADR 0021 pattern).
- #130: Haar features (control D) and the large-workload resource measurements.
