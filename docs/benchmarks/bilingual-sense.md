# Bilingual Sense Harness (`bilingual-sense/2`, Issue #185)

## Status

| Field | Value |
| :--- | :--- |
| Issue | [#185](https://github.com/stevdrey/monada-neuron/issues/185) (NCM Research 3/3, Epic [#182](https://github.com/stevdrey/monada-neuron/issues/182)) |
| Protocol | [`bilingual-sense/2`](../experiments/bilingual-sense-v2.md), amending [`bilingual-sense/1`](../experiments/bilingual-sense-v1.md); [ADR 0051](../adr/0051-bilingual-sense-evaluation-protocol.md), [ADR 0052](../adr/0052-ai-agent-annotation-bilingual-sense.md) |
| What exists | The step-8 harness: arms A, B, C and N, the selection rule, metrics, paired cluster bootstrap, gates, every registered check except `w.*`, verdict rule and report, unit-tested and run end to end on **synthetic** data |
| What does not | Any result. No corpus has been counted, split or labeled; no `agent-gold` exists; the held-out split has never been read |
| Label status | None yet. Every future verdict carries `agent-labeled` or `agent-labeled, audited` |
| Arm W | Omitted (optional under the protocol); no wave-token result is produced and `w.*` checks do not apply |
| Code boundary | `monada-neuron-evaluation`, package `monada.neuron.evaluation.bilingual`, explicit task only. Core, `Signal`, defaults, the hypothesis policy and memory ownership are unchanged; no dependency added |

**This document reports no evidence.** The self-test below runs on generated sentences with generated gold. It shows
that the harness computes what the protocol registers; it says nothing about the hypotheses H1, H2a or H2b.

## Why step 8 has not run

v2 ([effect on #185](../experiments/bilingual-sense-v2.md#effect-on-185-and-epic-182)) lets #185 resume only when:

1. v2 and ADR 0052 are merged — **done**;
2. the agent configuration commit (A1, A2, J with exact model ids) is merged and `annotation.availability` passes —
   **not done** (the [registry](../experiments/bilingual-sense-v2.md#configuration-registry) is empty);
3. steps 2 to 7 have run under v2, with every gate and diagnostic recorded in the feasibility report — **not done**
   (only the step-2 corpus download is recorded);
4. the held-out gold and sealed-log hashes are committed — **not done**.

Both protocol versions allow the harness to be built and unit-tested on synthetic or training data at any time, which
is what this change does. `runBilingualSense` without `--synthetic` refuses to run, prints these prerequisites and
exits with status 3. A loader for the step-7 artifacts is deliberately absent: their file format is fixed by the
offline orchestrator, which does not exist yet.

## Running

```bash
./gradlew :monada-neuron-evaluation:runBilingualSense -PbenchmarkArgs="--synthetic"
```

Options: `--seed <long>` (synthetic generator seed), `--quick` (200 resamples and no cost passes, so `budget` fails and
every direction is `UNTRUSTWORTHY`: a smoke run, never a verdict), `--output-dir <dir>` (default
`build/reports/benchmarks`, files `bilingual-sense-selftest.json` and `.md`). The task runs with a 2 GiB heap, the
protocol's limit. Exit status: 0 all checks pass, 1 a check failed, 2 invalid arguments, 3 step 8 not ready,
4 harness failure.

## Protocol to code

| Protocol rule | Code |
| --- | --- |
| System input only; no translation, alignment, pass-B, family or split field | `SenseItem` (record components checked by `leakage.input-surface`) |
| Tokenization: NFKC, `Locale.ROOT` case folding, whitespace split, punctuation tokens; v2 offsets in stored code points | `SenseTokenizer` (normalizes one base code point plus its combining marks at a time, so offsets map back) |
| Keyed hash `HMAC-SHA-256("bilingual-sense/1", label:id)`, `u = h / 2^256` | `KeyedHash` (checked against Python's `hmac` vectors) |
| Family and sentence keys (v2 R13), occurrence id (v2 R14), direction rule, split quantiles 0.05 / 0.50 / 0.70 / 1.00 | `SensePartitions` |
| Near-duplicate rule: equality pass + 3-gram Jaccard ≥ 0.5 with exact prefix filtering | `NearDuplicateMerger` (verified against brute force) |
| Arms A, B, C, N and their 1 / 90 / 90 / 90-point grids | `MajoritySenseArm`, `NearestExampleArm`, `ContextualLexicalArm`, `NeuronSenseArm` |
| N tied to core: bit-identical scores and ranking vs `ReferenceHypothesisEvaluationPolicy` | `NeuronPolicyOracle` (`n.policy-equivalence`; the only use of `SignalEvidence`) |
| Selection on validation: coverage ≥ 0.70, least selective risk, ties to coverage then grid order | `GridSelection` |
| Metrics: forced accuracy, Macro, Macro_const, coverage, selective accuracy, abstention P/R, tie-aware AURC and AUROC, ECE, Brier, `n/a` denominators | `SenseMetrics` (`metrics.fixture`: `MetricsFixture`, 21 hand-computed values) |
| Paired cluster bootstrap over held-out families, `B = 2000`, seed `0x1840_B007` | `ClusterBootstrap` |
| Step-7 gates, projection Wilson bound, v2 `annotation.unlabeled` | `SenseGates` |
| Guarded held-out gold | `GuardedGold` (`leakage.test-labels`) |
| Checks, H1 / H2a / H2b, verdict with label status, strata, memorization probe, cost | `BilingualSenseEvaluation` |

## Interpretations recorded before any data

The protocol leaves these details open. Each is fixed here, before any `agent-gold` exists, and applies to every arm
alike; none changes a rule, grid, margin or gate.

- **B's vote.** Each of the `k` neighbours votes with its gold weights (`1/m` per cluster of a gold set of size `m`),
  so the votes sum to `k`; the top vote share is the top vote over `k`. Similarity does not weight the vote; it enters
  only through `s = share × highest similarity`. Unknown query tokens count in the Jaccard union.
- **C's counts.** Multinomial over window token occurrences (a repeated token counts each time), with the lemma's
  per-window training vocabulary as the Laplace denominator.
- **N's `n(c)`.** An item counts once for a token however often the token appears in its window; each window position
  still yields its own cue. `d` is computed as `(K (n_k + 1) − (n + K)) / ((K − 1)(n + K))`, algebraically equal to the
  registered form, so `d = 0` is exact when the weights are.
- **AURC.** `Σ over tie blocks (|block| / n) × cumulative risk after the block`, ranking by decreasing `s`.
- **ECE.** Equal-mass bins over `ANS` items ordered by increasing `s`, ties by item order; bin `b` holds positions
  `[⌊b·n/10⌋, ⌊(b+1)·n/10⌋)`.
- **Bootstrap.** Percentile bounds at sorted indices `⌊0.025·B′⌋` and `⌈0.975·B′⌉ − 1` (the convention of the NCM
  harness), the one-sided 0.995 bound at `⌊0.005·B′⌋`; a resample on which a statistic is undefined is left out and
  counted. In `Macro`, a sense with no weight in a resample is left out of that resample's mean.
- **Permutations.** Fisher–Yates for `i = n−1 … 1` swaps `i` with `h(permute, <group>|i) mod (i+1)`. The held-out
  counterfactual permutes within each `(direction, lemma)`; `nc.label-permutation` permutes the `ANS` gold sets among
  the lemma's `ANS` training items (group `training|<direction>|<lemma>`), so `UNA`/`OOI` items stay unused.
- **Strata.** The context OOV rate uses the whole-sentence window against the vocabulary of every training sentence of
  the direction. The low-frequency statement uses held-out gold.
- **Verdict precedence.** `NOT TESTABLE` (a step-7 data gate, or H2 not testable) takes precedence over
  `UNTRUSTWORTHY`; a failed check makes every other direction `UNTRUSTWORTHY`.
- **Oracle indices.** In `n.policy-equivalence` each hypothesis's `SignalEvidence` entries carry `0..m−1`, `m` its own
  cue count.
- **Lemma without training.** A and C answer the lowest ordinal with `s = 0`; the step-7 gates keep such a lemma out of
  scoring.
- **Cost.** Per-item latency includes tokenization (the window cache is cleared before each measured item); fit time is
  shared by both directions and counted against each.

## Synthetic self-test

`SyntheticSenseWorkload` generates five lemmas per direction with planted cue tokens per cluster, 5% two-cluster gold
sets, `UNA` items with no cue, `OOI` items with foreign cues and 12 unseen-lemma items per direction; every sentence
carries a unique middle token so no two are near-duplicates. On it (seed `0x18505E1F`, 2,000 resamples, measured
cost) every one of the 14 checks passes and the run takes about 17 s on an AMD Ryzen 7 7730U (Azul JDK 27). Held-out
inference, tokenization included, has a median of about 6–12 µs per item for N and C and 12–23 µs for B (limit 10 ms);
the estimated retained state is below 0.5 MiB per arm (limit 64 MiB).

Both synthetic directions end `NO-GO`: arm C, a naive Bayes control, fits the planted relation better than N, so H1
and H2a fail against C, and N's abstention AUROC stays below 0.70. This is a property of the generator, not evidence
about the task; it shows only that the harness can produce a negative verdict and that no check forces one.

## Limitations

- Nothing here is a result. The harness has never seen protocol data, and its synthetic data does not resemble
  Tatoeba or Europarl text.
- The step-8 data loader, the step 2–7 tooling (count audit, split map over the corpus, agent orchestrator) and arm W
  are out of scope; the first two are offline tooling under the protocol.
- A real step-8 report still needs its label status in the title (the title is fixed to the synthetic self-test) and
  the v2 agent diagnostics beside the results; `SenseDataset` has no field for them yet. Both belong with the loader.
- The harness assumes the training list is already in training-file order (B's ties and the memorization probe use
  it); the future loader must guarantee and check that order.
- `retainedBytes` is an estimate from array and map sizes, not a heap measurement.
- Agreement α (MASI) and the projection gate's pilot shares are computed offline at steps 5–7; the harness takes their
  outcome as the step-7 scored set and only re-applies the gold-count gates.
