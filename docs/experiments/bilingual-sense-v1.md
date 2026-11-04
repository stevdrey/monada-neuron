# Contextual Bilingual Sense Selection Protocol (`bilingual-sense/1`)

## Status

| Field | Value |
| :--- | :--- |
| Protocol version | `bilingual-sense/1` |
| Issue | [#184](https://github.com/stevdrey/monada-neuron/issues/184) (NCM Research 2/3, Epic [#182](https://github.com/stevdrey/monada-neuron/issues/182)) |
| Builds on | [Feasibility report](bilingual-sense-feasibility.md) (#183). This document is step 1 of its [executable order](bilingual-sense-feasibility.md#executable-order) |
| Decision record | [ADR 0051](../adr/0051-bilingual-sense-evaluation-protocol.md) |
| Binding constraints | [ADR 0050](../adr/0050-ncm-investment-decision.md) (NCM no-go) unchanged |
| Executes | #185, only in the order below and only past the data gates |
| Code changes | None. No harness, dependency, `Signal` change or default change is added or authorized by this document |

This document pre-registers the task, data, partitions, arms, metrics, margins, checks and stop/go rules **before any
corpus is downloaded and before any example is labeled**. Nothing in it is a result.

**Disclosure.** At the commit that adds this protocol: no Tatoeba or Europarl file has been downloaded by this project,
no occurrence has been counted, no label exists, no system has been built and no split has been formed. The lemma
candidates below were chosen from general dictionary knowledge of English and Spanish only, without any corpus count,
model output or system score. The only code inspected for the optional wave arm is the `WordWaveTokenizer` of
`stevdrey/wave-llm-demo` at commit `bc03f33f7a8a4851363d6d4159d74c30116410b2` (`main`, 2026-11-03), read to document
its encoding; no wave-llm-demo model was trained or run.

**Versioning.** Changing any rule, value, grid, seed or gate after step 2 starts requires `bilingual-sense/2`, recorded
in the [changelog](#changelog) with its reason. The held-out split is **single-use**: once any system has been run on
it (step 8), a later protocol version cannot use it as held-out evidence and needs newly annotated held-out families.

## Question

Per language direction (`EN→ES`, `ES→EN`), on unseen bilingual examples:

1. Does an explainable Neuron hypothesis/evidence candidate select an acceptable target-language expression for an
   ambiguous word in context more accurately than majority-sense, nearest-example and simple context-sensitive lexical
   controls, by a registered margin?
2. Does it rank its own answers so that abstention separates unanswerable and out-of-inventory occurrences from
   answerable ones better than those controls?
3. Separately, and optionally: does a Wave LLM Demo token-wave representation add anything over a lexical
   representation of the same tokens with the same capacity?

## What this protocol does not claim

- No sentence translation, general language understanding, synonym/antonym or part-of-speech learning, or generation
  ([separate decision](#sentence-translation-is-a-separate-decision)).
- Meaning comes only from the human annotation. A score, a waveform similarity or an output confidence is not
  evidence of semantic understanding.
- A GO result supports further *research* on one direction. It enables nothing by default and changes no runtime.
- Nothing here re-opens the NCM wave pipeline ([ADR 0050 compliance](#adr-0050-compliance)).

## Task

The definitions of the feasibility report's [task table](bilingual-sense-feasibility.md#task-under-assessment) are
adopted unchanged and made precise here.

| Element | Registered definition |
| --- | --- |
| Item | `(direction, lemma, source sentence id, character span)`. One occurrence of one declared form of a target lemma |
| System input | The direction, the lemma, the frozen inventory of that `(direction, lemma)`, the source sentence and the span. Nothing else: no translation, no alignment, no document neighbours, no source or split id |
| Context window | Tokens of the source sentence, excluding the target span, at most `w` tokens on each side (`w` selected per arm from the registered grid; `S` means the whole sentence) |
| Tokenization | Unicode NFKC, case folding with `Locale.ROOT`, split on whitespace, punctuation as separate tokens. Identical for every arm |
| Candidates | The inventory clusters of `(direction, lemma)`, ordered by cluster ordinal. Each cluster lists acceptable target expressions |
| Gold | Adjudicated pass-A label: a non-empty set `G` of clusters (`ANS`), or `UNANSWERABLE` (`UNA`), or `OUT_OF_INVENTORY` (`OOI`) |
| Output | A forced choice `k*` (always a cluster), a confidence `s ∈ [0, 1]`, and either the answer `k*` or `ABSTAIN` |
| Correct | On `ANS`: answered and `k* ∈ G`. On `UNA` and `OOI`: `ABSTAIN` |

Homographs of another lemma (Spanish *cuenta* of *contar*, *piso* of *pisar*, *nota* of *notar*) are kept and labeled
`OOI`; they are the natural source of in-inventory-lemma unfamiliar inputs. They are not filtered out.

## Data

### Sources and checksum policy

| Source | Version | Files | Family unit |
| --- | --- | --- | --- |
| Tatoeba | The first weekly export downloaded after this protocol is committed | `sentences.tar.bz2`, `links.tar.bz2`, `sentences_base.tar.bz2` | Translation cluster (connected component of the link graph), merged with near-duplicates |
| Europarl | v10 | `training/europarl-v10.es-en.tsv.gz` | Session file, read from the `file_id` column, merged with near-duplicates |

- At step 2, record for each file: URL, download time (UTC), byte size and SHA-256, in a revision of the feasibility
  report. That snapshot is the only one this protocol uses; a file is never replaced by a later export.
- A checksummed copy is retained by the annotation lead for reproduction. Only ids, offsets, labels and the sentences
  needed by the release are published, with the attribution the source terms require (feasibility report,
  annotation item 8).
- If a file cannot be downloaded, or its terms are found incompatible with publishing derived sentences, that source
  is dropped before step 3 and the drop is recorded. If both sources are dropped, the epic records a data no-go.
- **Europarl `file_id` validation (step 2).** The v10 README lists the bilingual TSV columns as source, target,
  `file_id`, `chapter_id`, `speaker_id`, speaker name, language and affiliation, and warns that some fields may be
  blank [V, README]. The header row and the values are unverified until step 2. Europarl is kept only if `file_id` is
  present and non-blank on at least 99% of the rows that hold a candidate occurrence, and inspected values name
  session-day files (for example `ep-YY-MM-DD`). Rows with a blank `file_id` are excluded. If the validation fails,
  Europarl is dropped under the rule above; no other family rule is substituted after the freeze.
- UN, News Commentary, WikiMatrix, OpenSubtitles, BabelNet and XL-WSD are not used.

### Source language rule

- `EN→ES` items use English source sentences; `ES→EN` items use Spanish ones.
- Tatoeba: the sentence's language code must be `eng` or `spa`. Whether the sentence is an original or a translation
  (`sentences_base`) is recorded and reported as a stratum, not filtered.
- Europarl: the speaker's original-language tag is recorded and reported as a stratum, not filtered.

### Lemma candidates and forms

Candidates are declared per direction before any count. Each one is kept only if, at the inventory freeze (step 4),
the WordNet/OMW and Wiktionary entries give it at least two clearly distinct translation clusters; the clusters below
are sketches for that check, not the frozen inventory.

| Direction | Lemma | Declared forms | Cluster sketch (to be frozen at step 4) |
| --- | --- | --- | --- |
| `EN→ES` | bank | bank, banks | *banco* (institution) · *orilla, ribera* (river) |
| `EN→ES` | light | light, lights, lit, lighted, lighter, lightest | *luz* · *ligero, liviano* · *claro* (colour) · *encender* |
| `EN→ES` | right | right, rights | *derecho* (entitlement) · *derecha* (side) · *correcto* · *justo / exactamente* |
| `EN→ES` | spring | spring, springs | *primavera* · *muelle, resorte* · *manantial, fuente* |
| `EN→ES` | glass | glass, glasses | *vaso, copa* · *vidrio, cristal* · *gafas, lentes* |
| `EN→ES` | date | date, dates | *fecha* · *cita* · *dátil* |
| `EN→ES` | letter | letter, letters | *carta* · *letra* |
| `EN→ES` | fair | fair, fairs, fairer, fairest | *justo* · *feria* · *claro, rubio* (complexion) |
| `ES→EN` | banco | banco, bancos | *bank* · *bench* · *school / shoal* (fish) |
| `ES→EN` | tiempo | tiempo, tiempos | *time* · *weather* · *tense* · *half* (sport) |
| `ES→EN` | carta | carta, cartas | *letter* · *menu* · *card* (playing) · *charter* |
| `ES→EN` | cuenta | cuenta, cuentas | *account* · *bill, check* · *count* · *bead* |
| `ES→EN` | papel | papel, papeles | *paper* · *role, part* |
| `ES→EN` | llave | llave, llaves | *key* · *tap, faucet* · *wrench, spanner* · *hold* (grappling) |
| `ES→EN` | nota | nota, notas | *note* · *grade, mark* · *(musical) note* |
| `ES→EN` | piso | piso, pisos | *floor, storey* · *flat, apartment* · *floor (surface)* |

Matching is on the declared forms only, after the registered tokenization, with whole-token boundaries. A form shared
by an unrelated word is still an item, labeled by annotators (usually `OOI`).

**Unseen-lemma check items.** Two lemmas per direction with no inventory and no training data are drawn for a
mechanical check only (`unseen-lemma.abstain`): `EN→ES` *pitch*, *match*; `ES→EN` *cola*, *muñeca*. Their
occurrences take part in the near-duplicate merge, but they form no stratum. Items are drawn only from families that
the step-3 map already assigns to held-out (families of declared strata that also contain an unseen-lemma form), up to
30 families per lemma in `cap` order; fewer are used if fewer exist, and the count is reported. They are labeled `OOI`
by construction, never annotated and never pooled into any hypothesis.

### Inventory freeze rule

- The annotation lead writes each kept lemma's clusters from WordNet 3.0/OMW and Wiktionary entries only, with the
  entries cited per cluster, before calibration (step 4).
- The inventory file is committed with its SHA-256 before any calibration item is shown to an annotator. After that,
  a cluster may be split, merged or added only as a new protocol version that relabels every item of that lemma.
- Clusters are ordered by ordinal; every tie-break below uses the lower ordinal.

## Partitions

### Families and near-duplicates

1. Form structural families: Tatoeba translation clusters; Europarl session files.
2. Near-duplicate merge, over every candidate occurrence of every lemma and both directions, before any assignment:
   two source sentences are near-duplicates when their masked token sequences (target span replaced by one `⟨T⟩`
   token, punctuation removed) are equal, or when both have at least 3 tokens and the Jaccard similarity of their word
   3-gram sets is at least `0.5`. Families of near-duplicates are merged with union-find. Candidates come from two
   exact passes, so that every pair meeting either branch is found:
   - **Equality pass.** Every occurrence is grouped by its masked token sequence (a hash map keyed by the sequence,
     compared by content on hash collision). This catches short sequences that share no 3-gram, such as `Bank!` and
     `Banks?`, which both reduce to `⟨T⟩`.
   - **Jaccard pass.** Pairs of sequences with at least 3 tokens come from an inverted index on 3-grams with prefix
     filtering (3-grams ordered rarest first by global frequency; a set `x` indexes its first
     `|x| − ⌈0.5·|x|⌉ + 1` 3-grams), which finds every pair at or above the threshold; each candidate pair is then
     verified with the exact Jaccard similarity.
   - **Deviation from the feasibility proposal.** It proposed word 5-grams. Tatoeba sentences are often shorter than
     ten tokens, so 5-grams would leave most pairs without any shared n-gram; 3-grams with a `0.5` threshold are
     registered instead.
3. A family's id is the SHA-256 of the lexicographically smallest member sentence id in the merged family, prefixed
   with the source name.

### Keyed hashes and seeds

`h(label, id) = HMAC-SHA-256(key = "bilingual-sense/1", message = label + ":" + id)`, read as an unsigned 256-bit
integer; `u(label, id) = h / 2²⁵⁶ ∈ [0, 1)`.

| Use | Label | Rule |
| --- | --- | --- |
| Direction | `direction` | A family with source sentences in only one source language goes to that direction. A family with both (Tatoeba clusters, Europarl sessions) goes to `EN→ES` when `u < 0.5`, else `ES→EN`. A family is never used in both directions |
| Split | `split` | See below |
| Candidate cap order | `cap` | Families taken in increasing `h` |
| Occurrence choice | `occurrence` | Within a family, occurrences in increasing `h` |
| Pilot sample | `pilot` | Increasing `h` among training families |
| Calibration sample | `calibration` | Increasing `h` among reserve families |
| Label permutation control | `permute` | Fisher–Yates driven by `h(permute, i)` |
| Wave vocabulary shuffle control | `vocab-shuffle` | As above |
| Bootstrap | seed `0x1840_B007` | `java.util.SplittableRandom`, `B = 2000` resamples. There is one registered run; `determinism` re-runs it with the same seed |

### Split assignment

Each family has a primary stratum: its direction and the lowest-ordinal **declared** lemma it contains, in the order
of the candidate table. The stratum never depends on which lemmas later gates keep, so step 3 fixes the split once and
no lemma dropped at steps 2, 4, 5 or 7 moves a family. A family whose declared lemmas are all dropped stays in its
split and contributes no items.

Within each primary stratum, families are ordered by increasing `h(split, family id)` and indexed `i = 0, …, n − 1`.
The family at index `i` goes to the first split, in the order of the table below, whose cumulative boundary exceeds
`(i + 0.5) / n`; the boundaries are 0.05, 0.50, 0.70 and 1.00.

| Split | Proportion | Use |
| --- | --- | --- |
| Calibration reserve | 0.05 | Guideline calibration only; never in any other set |
| Training | 0.45 | Fitting and pilot |
| Validation | 0.20 | Every selection: windows, grid points, thresholds |
| Held-out | 0.30 | Sealed; read by systems once, at step 8 |

A family that holds several lemmas is assigned once, by its primary stratum, and contributes items of every lemma it
contains to that split.

### Annotation caps

To bound cost, per `(direction, lemma)`:

| Split | Cap | Occurrences per family |
| --- | --- | --- |
| Calibration | 10 occurrences | 1 |
| Pilot (from training) | 20 occurrences | 1; pilot families are then excluded from every set |
| Training | 90 occurrences | at most 3 |
| Validation | 60 families | 1 |
| Held-out | 120 families | 1 |

Families are taken in `cap` order until the cap is filled. Maximum load if all 16 lemmas survive: 300 occurrences per
lemma, 4,800 occurrences and 9,600 pass-A annotations before adjudication.

### Strata reported in #185

New context (held-out family, seen lemma), contrastive pairs (annotator-marked; constructed ones separately),
translation-divergent, `UNA`, `OOI`, source (Tatoeba / Europarl), original vs translated source sentence, context OOV
rate (`0`, `(0, 0.3]`, `> 0.3` of window tokens absent from the training vocabulary), and the **memorization probe**:
30 training occurrences per `(direction, lemma)`, re-scored after fitting and never counted as held-out.

## Annotation

The feasibility report's [two-pass procedure](bilingual-sense-feasibility.md#proposed-responsible-annotation) is
adopted unchanged: two blind bilingual annotators, locked source-only pass A, diagnostic pass B, source-first
adjudication by a third person, translation-divergent stratum. Registered additions:

- Annotators may use the inventory and printed or online dictionaries. They may not use machine translation, an LLM or
  any system arm during pass A. Machine-generated labels are never gold.
- Guideline revision policy: guidelines change only during calibration (step 4). When calibration ends, before any
  pilot item is shown, the final guidelines are committed with their SHA-256.
  A later material change follows the feasibility report's late-change rule (new version, pilot relabeled, projection
  gate re-applied, step-6 items relabeled).
- Agreement: Krippendorff's α on pass A with MASI distance over label sets, where `UNA` and `OOI` are singleton labels.
  The minimum is **α ≥ 0.67** per `(direction, lemma)`.
- **Staffing condition.** If two independent annotators and an adjudicator who meet the bilingual requirement cannot
  be engaged for a direction, that direction is stopped at step 6 as a data no-go. Fewer annotators are not a smaller
  version of this protocol.
- **Sealing.** Held-out gold is kept offline by the annotation lead until step 8. At step 7 the SHA-256 of the
  held-out gold file is committed; the step-8 run verifies it before scoring.

## Gates (frozen; applied mechanically)

| Gate | Step | Rule |
| --- | --- | --- |
| Text | 2–3 | Step 2 counts occurrences and families; the gate is then read from the step-3 map, which does not depend on it, before step 4. A `(direction, lemma)` stays only if it has at least 80 training, 40 validation and 60 held-out candidate families. Counts only |
| Projection | 5 | For each cluster, the projection is `min(heldOutFamilies, 120) × p⁺`, where 120 is the held-out annotation cap and `p⁺` is the upper bound of the 80% Wilson interval of its pilot share among pilot `ANS` items (an item with gold set `G` counts `1/|G|` toward each cluster in it). A cluster with projection below 30 is removed from the scored set (its items stay and are reported descriptively); a lemma left with fewer than two scorable clusters is dropped before step 6. A lemma whose pilot holds no `ANS` item has no defined share; it is dropped before step 6 with that reason, and the minimum-per-direction and global-stop rules then apply |
| Agreement | 7 | Drop a `(direction, lemma)` with pass-A α below 0.67. α is undefined when the pass-A labels show no variation (zero expected disagreement); such a lemma is dropped with that reason |
| Minimum per sense | 7 | A `(direction, lemma, cluster)` is **scored** only with at least 30 held-out families whose gold set contains it |
| Low-frequency | 7 | A cluster below 20% of its lemma's gold `ANS` occurrences is low-frequency. If none is scored in a direction, #185 states that low-frequency senses were not testable there |
| Abstention items | 7 | H2 is testable in a direction only with at least 30 held-out `UNA ∪ OOI` families of scored lemmas |
| Minimum per direction | 7 | At least 4 lemmas, each with at least two scored clusters. Otherwise the direction stops; the other proceeds alone, stated as such |
| Global stop | 2, 5, 6, 7 | If no direction can meet its minimum, no system is run on this data and the epic records a data no-go |

Every gate outcome is recorded, with counts, in a revision of the feasibility report before step 8.

## Arms

All arms receive identical inputs (the [system input](#task) only), the same training items with gold, the same
validation items, and the same tokenization. Every arm is deterministic and single-threaded when scoring. No arm reads
pass-B labels, translations, alignments, family ids or split ids. A lemma with no inventory returns `ABSTAIN` in every
arm.

Training gold with a set `G` of `m` clusters contributes weight `1/m` to each cluster. `UNA` and `OOI` training items
are used by no arm; abstention behavior is selected on validation items only.

**Training-file order** is defined once: training families in increasing `h(split, family id)`, then their occurrences
in increasing `h(occurrence, ·)`. B's tie-break and W's vocabulary order both use it.

| Arm | Definition | Forced choice and confidence `s` | Grid (points) |
| --- | --- | --- | --- |
| A — majority sense | Per `(direction, lemma)`, the cluster with the largest training weight | `k*` = that cluster; `s` = its training share. Always answers | none (1) |
| B — nearest example | Jaccard similarity over the window token sets (target excluded) to every training item of the lemma; weighted vote of the `k` most similar, ties by lower item order in training-file order | `k*` = top vote; `s` = (top vote share) × (highest similarity). When every neighbour has similarity 0, `k*` is the lowest-ordinal cluster and `s = 0`. Abstain if `s < τ` | `k ∈ {1, 3, 5}` × `w ∈ {3, 5, S}` × `τ ∈ {0, 0.1, …, 0.9}` (90) |
| C — contextual lexical | Multinomial naive Bayes per lemma over window tokens, Laplace `α`; tokens absent from training are ignored | `k*` = argmax posterior; `s` = its posterior. Abstain if `s < τ` | `α ∈ {0.1, 0.5, 1}` × `w ∈ {3, 5, S}` × `τ ∈ {0, 0.55, 0.6, …, 0.95}` (90) |
| N — Neuron hypothesis/evidence | [Below](#arm-n-neuron-candidate) | `k*` = top-ranked cluster; `s` = its score. Abstain if `s < τ` | `λ ∈ {0.5, 1, 2}` × `w ∈ {3, 5, S}` × `τ ∈ {0, 0.1, …, 0.9}` (90) |
| W — wave tokens (optional) | [Below](#arm-w-optional-wave-token-representation) | as below | as below |

### Arm N: Neuron candidate

For each item, one candidate per inventory cluster, scored with the evidence formula of the existing
`ReferenceHypothesisEvaluationPolicy` under `HypothesisScoringConfig.NONE` (evidence only; no resonance component,
since G2 is a no-go and a three-scalar `FrequencyState` cannot carry a context):

```text
score(k) = S_k / (S_k + C_k + 1)
```

Evidence comes from the window tokens. For lemma `ℓ` with `K` clusters and a training-vocabulary token `c`, let
`n(c)` be the training weight of items whose window contains `c` and `n_k(c)` the part of it with cluster `k`.
Tokens with `n(c) < 2` give no evidence. Otherwise, with smoothed share `r_k(c) = (n_k(c) + 1) / (n(c) + K)`:

```text
d = (K / (K − 1)) · (r_k(c) − 1/K)              d ∈ (−1/(K − 1), 1)
weight = min(1, λ · |d| · log2(1 + n(c)))
relation = SUPPORTS if d > 0, CONTRADICTS if d < 0, none if d = 0
```

Each window token position yields at most one cue per cluster, held in an evaluation-side cue record
`(cluster ordinal, token position, relation, weight)`. No core type is repurposed: no `Signal`, `FrequencyState` or
`SignalEvidence` carries text or token positions. Per cluster the 16 largest weights are kept (the bound of
`HypothesisLimits.DEFAULT`), ties to the lower position. Ties in score rank the lower ordinal first, as the policy does.

N is a reference scorer in the evaluation module, following the pattern of ADR 0050's `FeatureConceptScorer`. Check
`n.policy-equivalence` ties it to core: for every validation item, the same weights are passed, outside any cognitive
cycle, to `ReferenceHypothesisEvaluationPolicy` as a `HypothesisSet` built in cluster-ordinal order (so the
`Hypothesis` sequence equals the 0-based ordinal) whose `SignalEvidence` entries carry the opaque indices `0..m − 1`.
That oracle check is the only place `SignalEvidence` appears, and it requires bit-identical scores and ranking.

Properties registered as expected behavior, to be checked rather than assumed: a context with no known token has
score `0` for every cluster, so N abstains whenever `τ > 0` (unknown tokens and unfamiliar contexts produce no
evidence rather than a guess); every answer carries the list of cue positions and weights that produced it.

N is built in the evaluation module only. It adds no type to core and changes neither the policy nor `Evidence`.

### Selection (validation only)

For every arm, among the grid points whose `ANS` coverage on validation is at least `0.70`, choose the one with the
least **selective risk over all validation items** (`ANS` answered wrongly plus `UNA`/`OOI` answered, divided by the
number of answered items, and `0` when nothing is answered); ties to higher coverage, then to grid order. If no point reaches `0.70`, choose the point
with the highest coverage and report it. Selection is per direction, shared across that direction's lemmas. The
held-out split is never read during selection.

### Arm W: optional wave-token representation

**Feasibility decision: feasible as an optional, separately reported arm; not required.** #185 may omit it; an
omission is reported with its reason and changes no other result.

What the encoding is (read from `WordWaveTokenizer` at the pinned commit):

- Token ids are assigned in first-seen order by `fitOnTexts`, after `<pad> = 0` and `<unk> = 1`, up to a vocabulary
  limit; tokens beyond the limit or unseen in fitting map to `<unk>`.
- Id `i` maps to `sin(2π·f₁·t/L) + 0.5·sin(2π·f₂·t/L + φ)` with `f₁ = i mod 7 + 1`, `f₂ = i mod 13 + 1`,
  `φ = (i mod 5)·π/4`; `<pad>` is the zero vector.
- Consequences documented in advance, each to be confirmed by a check in #185:
  1. **455-id collisions.** For ids `i ≥ 1`, the waveform depends only on `i mod 455` (`455 = lcm(7, 13, 5)`), so ids
     `i` and `i + 455` are identical. `<pad>` (id 0) is the zero vector, so id 455 does not equal it. Collisions
     follow vocabulary order, not meaning.
  2. **ID-order dependence.** The same token gets a different wave when the fitting text order changes.
  3. **Rank bound.** For `L ≥ 27`, every token wave lies in the span of sines and cosines of frequencies `1..13`, so any
     sum or mean of token waves lies in a subspace of dimension at most 26.
  4. **Unknown tokens** all share the `<unk>` wave.

Registered construction, inside the evaluation module only, with no DJL, PyTorch or wave-llm-demo dependency:

- The formula is re-implemented and checked bit-for-bit against fixture vectors generated offline from the pinned
  commit (check `w.reference-equivalence`); the fixture file and the command that produced it are committed.
- Vocabulary fitted on training windows only, in [training-file order](#arms), limit 20,000,
  `L = 64`.
- Representation: mean of the window's token waves. Classifier: per-lemma nearest class centroid by cosine over the
  training weights; `k*` = nearest; `s` = softmax of cosines at temperature `0.1`, top probability. The cosine with a zero
  vector (an empty or all-padding window) is `0`, so such an item gets `k*` = the lowest-ordinal cluster and `s = 1/K`.
  Abstain if `s < τ`.
- Grid: `w ∈ {3, 5, S}` × `τ ∈ {0, 0.1, …, 0.9}` (30), selected as above.

Controls with identical classifier, grid and data, so that only the representation changes:

| Control | Representation |
| --- | --- |
| W-H455 | Mean one-hot of `id mod 455` (the bucket identity that W's collisions imply, without waves) |
| W-BoW | Mean one-hot of the full training vocabulary (no collisions) |
| W-shuffle | W with the vocabulary order permuted (`vocab-shuffle`), measuring ID-order dependence |

Reported for W: collision count (vocabulary pairs sharing `id mod 455`), `<unk>` rate per split, measured rank of the
pooled representations, parameter and state size, cost. A claim that the wave representation *adds* something requires
both the lower 95% bound of `Macro(W) − Macro(W-H455)` and that of `Macro(W) − Macro(W-BoW)` to exceed `0`. W never
changes N's verdict, and no W result counts as evidence under ADR 0050.

## Metrics

Per arm, per direction, per lemma, per scored sense and per stratum:

- **Forced accuracy** on `ANS`: `k* ∈ G`.
- **Sense-macro forced accuracy** `Macro`: an `ANS` item contributes weight `1/|G|` to each scored cluster in `G`;
  per-sense accuracy is the weighted mean; `Macro` is the unweighted mean over scored `(lemma, cluster)` senses of the
  direction.
- **Coverage** on `ANS`, **selective accuracy** on answered `ANS`, **abstention precision and recall** for
  `UNA ∪ OOI`, separately for `UNA` and `OOI`. A ratio with a zero denominator (for example precision when an arm
  never abstains) is reported as `n/a`, never as `0` or `1`, and enters no gate.
- **Risk-coverage curve and AURC** over all held-out items of scored lemmas, ranked by decreasing `s`, where risk
  counts wrong answers on `ANS` and any answer on `UNA`/`OOI`. Tied scores form one block: the curve steps once, to the
  block's combined coverage and mean risk, so the result does not depend on item order (an arm with a constant `s`
  per lemma, such as A, gets one step per distinct value).
- **Abstention AUROC**: `1 − s` as a detector of `UNA ∪ OOI` against `ANS`; a tied pair counts ½.
- **Calibration**: ECE of `s` against forced correctness on `ANS` (10 equal-mass bins) and the Brier score.
  Reported; not a gate.
- **Constant bound** `Macro_const`: for each lemma, the best held-out sense-macro accuracy of always choosing one
  cluster, averaged over the direction's scored senses as `Macro` is. Under set-valued gold, Arm A is not the ceiling
  of a context-free predictor (a cluster shared by many gold sets can beat the training majority); `Macro` is linear
  in a context-free predictor's per-lemma choice, so `Macro_const` is that ceiling. It reads held-out gold, so it is
  computed only after scoring and used only by checks.
- **Generalization gap**: memorization-probe accuracy minus new-context accuracy.
- **Cost**: training and selection time, held-out inference time per item (median and p95), allocated bytes per item
  and retained model size, with `EvaluationMetricsCollector` (3 warm-up, 10 measured iterations). Reported per arm.

Uncertainty: 95% percentile intervals from a paired **cluster bootstrap over held-out families** (`B = 2000`,
registered seeds), resampling the same families for every arm so differences are paired.

## Hypotheses and margins (per direction)

| Id | Estimand | Passes when |
| --- | --- | --- |
| H1 | `Macro(N) − Macro(X)` for each `X ∈ {A, B, C}` | The lower 95% bound exceeds **0.05** for every `X` |
| H2a | `AURC(X) − AURC(N)` for each `X ∈ {A, B, C}` | The lower 95% bound exceeds **0.02** for every `X` |
| H2b | Abstention AUROC of N | The lower 95% bound exceeds **0.70** |

Margin rationale: with at least 4 lemmas × 2 scored clusters × 30 families, a pooled macro estimate has a 95% interval
of roughly ±0.06; a 0.05 lower-bound margin therefore needs a point difference of about 0.10, large enough to matter for
a task where A is often above 0.5. Per-sense intervals at n = 30 (about ±0.17) are reported, never gated.

## Checks (a failed check makes the run untrustworthy)

| Check | Rule |
| --- | --- |
| `leakage.family-disjoint` | No family id in two splits or two directions; calibration and pilot families appear in no other set |
| `leakage.near-duplicate` | No cross-split pair of source sentences meets the near-duplicate rule |
| `leakage.input-surface` | The system input type carries no translation, alignment, pass-B, family or split field (enforced by type, verified by test) |
| `leakage.test-labels` | No held-out gold is read before scoring (guarded labels, as `GuardedLabels` does for NCM) |
| `leakage.sealed-hash` | Held-out gold SHA-256 equals the hash committed at step 7 |
| `leakage.inventory-hash` | The inventory hash equals the one committed before calibration (step 4 starts); the guideline hash equals the one committed when calibration ends, before the pilot (step 5) |
| `nc.label-permutation` | N and C refitted with training labels permuted within each lemma. A context-dependent predictor can exceed `Macro_const` by chance, so the rule is statistical: for each (arm, direction), the paired cluster bootstrap of `Macro(arm, permuted) − Macro_const` (with `Macro_const` recomputed in every resample) fails the check only if its one-sided lower bound at level 0.995 exceeds `0`. The nominal false-alarm rate is 0.005 per test and at most 0.02 over the four tests (Bonferroni); the percentile bootstrap makes it approximate |
| `nc.context-mask` | N with every window token masked: every cluster scores exactly `0`, `k*` is the lowest-ordinal cluster of each lemma (the policy's tie-break), and N abstains on every item when `τ > 0`. Checked structurally, not by an accuracy bound |
| `nc.heldout-counterfactual` | Held-out labels permuted within each lemma: every arm's outputs (`k*`, `s`, answer or `ABSTAIN`) are bit-identical. Metrics may legitimately stay equal, so no change is required |
| `metrics.fixture` | A small committed synthetic fixture with hand-computed values: the harness reproduces exactly its `Macro`, `Macro_const`, coverage, selective risk, tie-aware AURC and AUROC, ECE and Brier, including a case where changing one item's gold flips its correctness by a known amount and a case with zero denominators (`n/a`) |
| `unseen-lemma.abstain` | At least one unseen-lemma item exists, and every arm abstains on every one |
| `n.policy-equivalence` | N's scores and ranking equal `ReferenceHypothesisEvaluationPolicy`'s bit-for-bit on every validation item ([Arm N](#arm-n-neuron-candidate)) |
| `w.reference-equivalence` | (If W runs) waves equal the pinned-commit fixtures bit-for-bit, and the four documented consequences hold as stated (consequence 1 for ids `i ≥ 1`) |
| `determinism` | A second full run is bit-identical in outputs, selections and intervals |
| `budget` | Resource limits below are respected |

A failing check halts the run before any verdict is recorded. A harness defect may be fixed without changing a rule;
the fixed run is reported with the failed one. Any fix that changes a rule, grid or value is `bilingual-sense/2`.

## Resource limits and ownership

- CPU only, one machine, no GPU, no network access during a run. Per direction and arm: fitting plus selection at most
  15 minutes, held-out median inference at most 10 ms per item, retained model state at most 64 MiB; the whole harness
  within a 2 GiB heap. An arm over budget is reported as such and cannot support a GO.
- Harness, arms and checks belong to `monada-neuron-evaluation` (planned package `monada.neuron.evaluation.bilingual`)
  and run only through an explicit evaluation task. Core gains no type, dependency, `Signal` change or default change.
- Corpus download, near-duplicate grouping, annotation tooling and any aligner run offline outside the Gradle build.
  No DJL, PyTorch, provider SDK or wave-llm-demo artifact enters any Neuron module.
- The annotated dataset is evaluation data, not cognitive memory. Persistent memory stays with Monada Resonance Store,
  and no arm composes a memory port.

## Verdict rule (per direction)

| Verdict | Condition |
| --- | --- |
| NOT TESTABLE | The direction stopped at a data gate, or H2 is not testable there |
| UNTRUSTWORTHY | Any check fails and is not resolved without a rule change |
| GO | H1, H2a and H2b pass, every check passes, and N is within budget |
| NO-GO | Otherwise |

- Directions are never pooled. One GO and one NO-GO is reported as exactly that.
- A GO means only that N earned further research in that direction. It changes no default, opens no implementation
  outside the evaluation module and is not evidence for sentence translation.
- The epic publishes negative results with the same detail as positive ones.

## ADR 0050 compliance

- Text encoded as token-id waveforms, by W or any other arm, is a derived representation of the same text. It is not a
  "real, externally labeled signal", and no W result satisfies or approaches ADR 0050 Decision item 5.
- N uses the hypothesis/evidence path and no NCM type (`ConceptModel`, `NextConceptPredictor`, spectral types,
  `WaveCognitionReasoningStage`). No investment in the NCM wave pipeline follows from any verdict here.

## Sentence translation is a separate decision

This protocol produces no evidence about sentence translation. That direction may be *proposed*, as a new issue with its
own protocol and ADR, only if:

1. both directions are GO under this protocol;
2. the proposal registers sentence-level data with human references, metrics and controls of its own, and a held-out set
   disjoint from every family used here; and
3. it states how word-level abstention would be carried into a sentence output.

A NO-GO or NOT TESTABLE in either direction closes sentence translation for Epic #182.

## Execution order

Steps 2 to 8 of the [feasibility report](bilingual-sense-feasibility.md#executable-order) run in order, with the rules
of this document, with one recorded refinement: the feasibility report applies the text gate at step 2, before the
split. Here step 2 counts, step 3 forms the split from the declared lemmas alone, and the text gate is then read from
the step-3 map before step 4, so no gate outcome can move a family. No system output enters any step before 8. #185 may build and unit-test the harness on synthetic or
training-split data at any time; it may read the held-out split only at step 8, once.

## Review against Epic #182

| Epic #182 acceptance | Where |
| --- | --- |
| Feasibility, provenance/licensing and exact task documented | Feasibility report; [Task](#task); [Sources](#sources-and-checksum-policy) |
| Protocol and thresholds frozen before held-out; splits prevent document/template leakage | [Status and versioning](#status); [Partitions](#partitions); [Gates](#gates-frozen-applied-mechanically); leakage checks |
| Comparable information and budgets; Wave arm separate and optional | [Arms](#arms) (shared inputs, 90-point grids, one selection rule); [Arm W](#arm-w-optional-wave-token-representation) |
| Report separates memorization, new context, contrastive, unknown input, calibration/abstention, costs, directions | [Strata](#strata-reported-in-185); [Metrics](#metrics) |
| Limitations and evidence-based go/no-go; sentence translation separate | [Verdict rule](#verdict-rule-per-direction); [Limitations](#limitations); [Sentence translation](#sentence-translation-is-a-separate-decision) |
| Default behavior and architecture boundaries preserved | [Resource limits and ownership](#resource-limits-and-ownership); ADR 0051 |

## Limitations

- The data does not exist yet. Every count is unknown until step 2, and the protocol may end in a data no-go.
- The task depends on staffing two bilingual annotators and an adjudicator; nothing replaces them.
- Tatoeba sentences are short and partly written for translation; Europarl is formal speech. Results may not transfer
  to other text. Both are reported strata.
- Lemma candidates were chosen from general dictionary knowledge; the inventory check may drop some. The list was not
  tuned on data, which also means it may hold lemmas whose senses are rare in these corpora.
- One occurrence per family in validation and held-out bounds correlation but discards data; up to three in training
  adds correlated items there, which only the fit sees.
- The margins are design judgements for a first study, not derived from a power analysis on real variance.
- Controls are deliberately simple. Beating them shows the candidate is not trivially replaceable, not that it is a
  strong word-sense system.

## Changelog

- `bilingual-sense/1` (#184): initial protocol. Review fixes before merge, before any data exists:
  - the near-duplicate rule adds an exact equality pass, so short masked sequences that share no 3-gram are merged,
    and the 3-gram pass uses exact prefix filtering;
  - the guideline hash is the one committed after calibration, not before it;
  - the negative controls compare against the constant bound `Macro_const` and a structural masking check instead of
    `Macro(A)`, which set-valued gold does not make a ceiling;
  - the primary stratum uses the declared lemmas, so the split no longer depends on later gates, and the text gate is
    read from the step-3 map;
  - the split quantile states a 0-based index and the cumulative boundaries;
  - the projection gate applies the 120-family held-out cap and defines the pilot share over `ANS` items;
  - AURC and AUROC handle tied scores as blocks instead of by item order;
  - C's threshold grid has 10 values, so every grid has 90 points;
  - Europarl families come from the `file_id` column of `europarl-v10.es-en.tsv.gz`, validated at step 2;
  - N carries cues in an evaluation-side record and is checked against the core policy (`n.policy-equivalence`),
    instead of storing token positions in `SignalEvidence`;
  - unseen-lemma items come from families already in held-out;
  - training `UNA`/`OOI` items are used by no arm, and training-file order and the bootstrap seed are defined once;
  - the 455-id collision rule excludes `<pad>` (id 0);
  - `nc.label-permutation` is a one-sided bootstrap test with a registered false-alarm rate instead of a fixed bound;
  - `nc.heldout-counterfactual` requires identical outputs only, and the new `metrics.fixture` check verifies metric
    computation on hand-computed values;
  - the projection gate drops a lemma whose pilot holds no `ANS` item, and every zero denominator (α without label
    variation, B with zero similarity, selection with nothing answered, W's zero vector, metric ratios) has a defined
    outcome.
