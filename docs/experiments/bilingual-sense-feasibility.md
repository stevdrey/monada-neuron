# Bilingual Word-Sense Data Feasibility Report (Issue #183)

## Status

| Field | Value |
| :--- | :--- |
| Issue | [#183](https://github.com/stevdrey/monada-neuron/issues/183) (NCM Research 1/3, Epic [#182](https://github.com/stevdrey/monada-neuron/issues/182)) |
| Kind | Feasibility report. It precedes the protocol (#184) and the evaluation (#185) |
| Audit date | 2026-11-03 |
| Method | Manual desk audit of public pages and papers (below). No dataset archive was downloaded, no example was labeled and no model was trained or run |
| Code changes | None. The runtime, `Signal`, defaults and the opt-in NCM stage are unchanged |
| Verdict | **Conditionally feasible, narrowed.** No existing dataset can serve as held-out evidence for both directions. The task is feasible only with a new, small, independently annotated dataset over redistributable source text, limited to a pre-declared set of target lemmas. Low-frequency senses enter the evaluation only where a count audit meets the minimums in [Stop rules](#stop-and-narrowing-rules); otherwise they are reported descriptively or dropped |

This report does not choose an implementation, a model or thresholds, and it does not claim that any label exists
beyond what a cited source states. It does not inspect, and cannot inspect, any held-out result: none exists.

## Task under assessment

From Epic #182: given a sentence, one occurrence of an ambiguous target word in it, and the language direction,
select an acceptable expression in the other language (English → Spanish or Spanish → English), or abstain when the
context is insufficient or the occurrence falls outside the inventory.

| Element | Definition used for this assessment |
| --- | --- |
| Occurrence | One token span of a target lemma in one source sentence, identified by sentence id and character offsets |
| Context | The source sentence only (optionally its immediate neighbours, if the source has document order). The aligned translation is the evidence for the label, never an input |
| Direction | `EN→ES` and `ES→EN`, evaluated and reported separately. A lemma pair in one direction is not evidence for the other |
| Candidates | A fixed, per-lemma, per-direction inventory of sense clusters, each listing acceptable target expressions; frozen before annotation of any split |
| Gold | A **set** of acceptable clusters (multiple valid answers allowed), or `UNANSWERABLE` (the context does not decide), or `OUT_OF_INVENTORY` (no cluster fits) |
| Abstention | A system output. It is correct on `UNANSWERABLE` and `OUT_OF_INVENTORY` items and counted as non-coverage elsewhere |

## Audit method (reproducible)

Every fact below was read on the cited page on the audit date. Tags:

- **[V]**: verified on the cited page or paper.
- **[NV]**: not verified; the report makes no claim and lists it under [Gaps](#gaps-and-open-checks).

Pages were read only; no archive was fetched, so no count below comes from inspecting data. To repeat the audit,
open each URL in the tables, check the version, terms and counts quoted, and record any change in a revision of this
report. The OPUS en-es sizes come from one listing:
`https://opus.nlpl.eu/opusapi/?source=en&target=es&preprocessing=moses&version=latest`.

## Existing annotated datasets

Main finding: **no audited benchmark is a manually annotated, openly redistributable, occurrence-level
translation-selection set in the `ES→EN` direction**, and the `EN→ES` sets are small, partly unreachable or closed.

| Dataset | Direction / labels | Size (as reported) | Annotation | Access and terms | Usable here? |
| --- | --- | --- | --- | --- | --- |
| SemEval-2010 Task 3 CL-WSD ([paper](https://aclanthology.org/S10-1003.pdf)) | `EN→ES` (and NL, FR, IT, DE); translation clusters from Europarl | 25 English nouns: 5 trial (20 sentences each), 20 test (50 each) [V] | Inventory clustered by one annotator; gold by three annotators per language, up to 3 weighted translations; no IAA reported [V] | Task host `lt3.hogent.be/semeval` no longer resolves; license [NV] | Reference only: one direction, nouns only, terms unknown |
| SemEval-2013 Task 10 CL-WSD ([paper](https://aclanthology.org/S13-2029.pdf)) | `EN→ES` (and others); Europarl clusters | 20 nouns × 50 test sentences from the public ANC [V] | Three annotators per language; no IAA reported [V] | Host dead (HTTP 410); license [NV] | Reference only |
| SemEval-2010 Task 2 CL-LexSub ([paper](https://aclanthology.org/S10-1002.pdf)) | `EN→ES` free-form substitutes; no inventory | 30 dev + 100 test words × 10 sentences [V] | Four annotators, pairwise IAA 0.2777 [V] | License, host [NV] | No: no inventory, low agreement |
| SemEval-2013 Task 12 ([paper](https://aclanthology.org/S13-2040.pdf)) | Monolingual ES and EN senses (BabelNet 1.1.1) over 13 parallel WMT news articles | ES: 1,481 BabelNet instances, 1.15 senses per instance [V] | One annotator per language, projection from English, reviewer on frequent lemmas; no IAA [V] | Host dead; license [NV]; BabelNet inventory is non-commercial | Indirect: monolingual senses, not translation choices |
| SemEval-2015 Task 13 ([paper](https://aclanthology.org/S15-2049.pdf)) | Monolingual ES and EN senses (BabelNet 2.5.1) over 4 parallel OPUS documents | ES: 1,239 instances; EN: 1,261 [V] | Two taggers plus judge; agreed on at least one meaning for 68% [V]; multiple senses allowed | Open zip on the task's Data page; license [NV] | Indirect; only 4 documents, so splits are coarse |
| XL-WSD ([site](https://sapienzanlp.github.io/xl-wsd/)) | ES senses (BabelNet), repackaging SemEval-2013/2015 | ES test 1,851, dev 452, silver train 393,539 [V, site chart data] | Inherited from the SemEval sets | CC BY-NC, sharing "only to research institutions" [V]; full license page 404 | No public redistribution of derived examples |
| DiBiMT ([paper](https://aclanthology.org/2022.acl-long.298.pdf)) | `EN→ES` (and others); good/bad lexicalisations per item | 597 items, 305 lemmas [V] | One professional translator for Spanish; no IAA [V] | Closed benchmark with a public leaderboard [V]; license [NV] | No: gold not downloadable |
| MuCoW WMT 2019 ([paper](https://aclanthology.org/W19-5354.pdf), [repo](https://github.com/Helsinki-NLP/MuCoW)) | `ES→EN` scoring suite only; no `EN→ES` [V] | 515 source words, 1,090 synsets, 72,295 contrastive pairs [V] | **Built automatically** from word alignments and BabelNet 4.0 [V] | Repo CC-BY-4.0 [V] | Not as gold: automatic labels are not independent annotation. Possibly a source of candidate lemmas |
| MCL-WiC ([paper](https://aclanthology.org/2021.semeval-1.3.pdf)), XL-WiC ([site](https://pilehvar.github.io/xlwic/)) | No Spanish [V] | — | — | CC BY-NC 4.0 [V] | No |
| Senseval-3 Spanish lexical sample ([paper](https://aclanthology.org/W04-0806.pdf)) | Monolingual ES, MiniDir-2.1 inventory linked to EuroWordNet | 46 words; train 8,430, test 4,195 [V] | Three experts plus adjudication; full agreement 83–90% by POS [V] | License [NV] | Indirect: monolingual senses, EFE news text of unknown terms |

Consequences:

1. `ES→EN` has no human-annotated occurrence-level gold among the audited sources.
2. The human-annotated `EN→ES` sets cover at most 20 to 25 nouns, their hosts are gone or closed, and their terms are
   unknown. They cannot be redistributed or used as held-out evidence from this public repository on current
   information.
3. The open sense-annotated Spanish sets depend on BabelNet, whose license restricts use and redistribution to
   research institutions [V, [license](https://babelnet.org/full-license)].
4. No audited dataset labels `UNANSWERABLE` contexts explicitly. Abstention gold must be created.

## Redistributable source text

Source text supplies sentences; it supplies **no** sense label. The aligned translation is evidence an annotator
reads, not a label.

| Source | Version and en-es size | Terms | Document structure | Fit |
| --- | --- | --- | --- | --- |
| Tatoeba ([terms](https://tatoeba.org/en/terms_of_use), [downloads](https://tatoeba.org/en/downloads)) | OPUS v2026-07-08: 229,476 pairs [V] | CC BY 2.0 FR by default, per-sentence author attribution; a separate CC0 subset exists [V]; whether EN/ES sentences are in it [NV] | None. Sentence links and an original/translation flag [V] | **Primary candidate**: short, everyday sentences, clear terms. Splits by translation cluster |
| Europarl v10 ([README](https://www.statmt.org/europarl/v10/README), [EP notice](https://www.europarl.europa.eu/legal-notice/en/)) | Same data as v7: 1,965,734 es-en sentences [V] | Reuse allowed, including commercial, with "© European Union – Source: European Parliament" credit [V] | File (session day), chapter and speaker ids; speaker's original language [V] | **Secondary candidate**: strong document ids and a known original language. Formal register |
| UN Parallel Corpus v1.0 ([page](https://www.un.org/dgacm/en/content/uncorpus)) | About 123,844 en-es documents [V, approximate] | Attribution and citation required; "no other restrictions apply"; redistribution not explicit [V] | UN document symbols, dates [V] | Possible, but very formal and terms need confirmation |
| News Commentary v18.1 ([README](https://data.statmt.org/news-commentary/README)) | `news-commentary-v18.en-es.tsv.gz` [V] | Packaging CC0; source text rights under a take-down policy only [V] | Documents in a separate archive [V]; TSV ids [NV] | Not for public redistribution of sentences |
| WikiMatrix v1 ([OPUS](https://opus.nlpl.eu/legacy/WikiMatrix.php)) | Count unresolved [NV] | CC BY-SA 4.0 [V] | No article ids [V] | Not recommended: mined, noisy alignments, no documents |
| OpenSubtitles v2024 | 105,482,431 pairs [V] | Copyright of subtitles [NV], likely third-party works | Per film | **Excluded** from public redistribution |

## Sense and translation inventories

| Inventory | Terms | Links ES and EN? | Role |
| --- | --- | --- | --- |
| Princeton WordNet 3.0 ([SPDX](https://spdx.org/licenses/WordNet.html)) | Permissive, keep notice [V] | Via OMW | Gloss reference for English senses |
| OMW Spanish data from MCR 3.0 ([repo](https://github.com/omwn/omw-data/tree/main/wns/mcr)) | CC BY 3.0 [V] | Through shared PWN 3.0 synsets [NV for this file] | Candidate Spanish lexicalisations per synset |
| English Wiktionary via wiktextract/kaikki ([rawdata](https://kaikki.org/dictionary/rawdata.html)) | Wiktionary CC BY-SA 4.0 / GFDL [V]; extract inherits it [NV legally] | Loosely: per-sense translation tables with a free-text sense hint [V] | Candidate translation clusters for English lemmas |
| BabelNet | Non-commercial, research institutions only [V] | Yes | **Excluded** from published artifacts |

None of these inventories is adopted as-is. Each target lemma's clusters are written by the annotation lead from
WordNet/OMW and Wiktionary entries, frozen before annotating any split, and published with their sources. A
CC BY-SA input makes the published inventory CC BY-SA.

## Word alignment (annotation aid only)

| Tool | License | Neural model |
| --- | --- | --- |
| [fast_align](https://github.com/clab/fast_align) | Apache 2.0 [V] | No (IBM Model 2 variant) |
| [eflomal](https://github.com/robertostling/eflomal) | GPL-3.0 [V] | No |
| [SimAlign](https://github.com/cisnlp/simalign) | MIT [V] | Yes (mBERT-like) |

Alignment may only **pre-highlight** the candidate counterpart for the annotator; it never becomes a label. It runs
offline as a tooling step and adds no dependency to any Neuron module.

## Feasibility by requirement

| Requirement (Epic #182 / issue #183) | Finding |
| --- | --- |
| Real, externally annotated or validated examples | Not available openly for this task. **Requires new independent annotation** |
| Occurrence-level alignment | Feasible: Tatoeba and Europarl give sentence alignment; occurrence spans are marked during annotation |
| `EN→ES` | Feasible with new annotation. Existing CL-WSD/DiBiMT sets are reference material only |
| `ES→EN` | Feasible only with new annotation. MuCoW's automatic labels may suggest lemmas but are not gold |
| Low-frequency senses | **Not guaranteed.** Whether a source holds enough occurrences of a rare sense is unknown until the count audit below. Where minimums fail, that sense is dropped or reported descriptively |
| Multiple valid answers | Supported by set-valued gold |
| Ambiguous / unanswerable cases | Must be created by annotators; no audited source labels them |
| Unfamiliar inputs | Constructed from occurrences of non-inventory lemmas and inventory-external senses, labeled `OUT_OF_INVENTORY` |
| Document / family-disjoint splits | Feasible: Europarl by session file; Tatoeba by translation cluster (below) |
| Redistributability | Feasible for Tatoeba (with attribution) and Europarl (with EP credit). Excludes BabelNet, XL-WSD, OpenSubtitles, News Commentary text |

## Proposed responsible annotation

1. **Lemma selection before any text is labeled.** The annotation lead declares the target lemmas per direction from
   dictionary evidence only (inventory entries with at least two clearly distinct translation clusters). No model
   output, no held-out text and no system score informs the choice.
2. **Guidelines and calibration set.** Written guidelines with examples of every label, piloted on a calibration
   sample that is excluded from every split.
3. **Two independent annotators per item**, bilingual (native in one language, proficient in the other), blind to
   each other, to the system arms and to the split assignment. Each marks the occurrence span and selects the set of
   acceptable clusters, or `UNANSWERABLE`, or `OUT_OF_INVENTORY`, with an optional note.
4. **Adjudication** by a third person on disagreement; the adjudicated label is gold, and both raw labels are kept.
5. **Agreement reporting** per lemma and direction: Krippendorff's α with a set distance (for example MASI) and the
   abstention-label confusion between annotators. #184 registers the minimum α a lemma needs to stay in the
   evaluation; a lemma below it is dropped before any held-out result is seen.
6. **Contrastive and ambiguous items.** Annotators mark naturally occurring minimal-context pairs; a constructed
   contrastive sentence is allowed only if labeled as constructed and kept in its own reported stratum.
7. **Privacy.** Store sentence ids, offsets and labels, not Europarl speaker names; Tatoeba attribution is by sentence
   id and source link, as its terms require. No personal data is added by annotators. Annotator identities are not
   published.
8. **Licensing of the release.** Derived data carries the most restrictive input license (CC BY 2.0 FR and EP credit
   for text; CC BY-SA if Wiktionary content enters the inventory), with a `SOURCES`/attribution file.

## Source text versus synthetic wave encoding

- The **source text and the human labels** are the data. They are the only things that carry meaning in this task.
- A Wave LLM Demo encoding maps token ids to synthetic waveforms. It is a **derived representation of the same
  text**, computed by an optional arm. It carries no label, adds no information about sense, and depends on
  vocabulary order and collisions.
- Under [ADR 0050](../adr/0050-ncm-investment-decision.md), such waveforms are **not** "real, externally labeled
  signals". Nothing in this report reopens investment in the NCM wave pipeline; a protocol that wants to rely on the
  old pipeline must satisfy ADR 0050 Decision item 5 separately.

## Partition proposal (input to #184)

Splitting happens on **families**, never on sentences, and before any annotation of the held-out families is read
by anyone tuning a system.

| Source | Family unit |
| --- | --- |
| Europarl | Session file (one day). All chapters of one day go to the same split |
| Tatoeba | Translation cluster: the connected component of the sentence-link graph, merged with near-duplicate groups |

Near-duplicate grouping: two source sentences belong to one family when they are equal after Unicode normalization,
case folding, punctuation and whitespace removal, or when their word 5-gram Jaccard similarity with the target
occurrence masked exceeds a threshold that #184 registers.

Assignment:

1. Compute each family's id, then a keyed hash of it with a seed registered in #184; order families by hash.
2. Assign families to training, validation and held-out evaluation by hash order, stratified per
   (direction, lemma), with target proportions that #184 registers. A family never spans two splits; a family that
   contains several lemmas is assigned once.
3. Keep `EN→ES` and `ES→EN` families disjoint: a Tatoeba cluster used in one direction is not used in the other, so
   one translation pair cannot leak across directions.
4. Strata reported separately in #185: seen lemma in a new family (new context), contrastive, `UNANSWERABLE`,
   `OUT_OF_INVENTORY`, and memorization (a training-family probe, never counted as held-out).
5. Held-out labels are sealed: produced and adjudicated, but no system is run on them until the protocol is frozen.

## Stop and narrowing rules

These are proposals for #184 to register; they are not results.

- **Minimum per sense.** A (direction, lemma, cluster) enters the scored evaluation only if the held-out split holds
  at least 30 independent families with that gold cluster. At n = 30 the 95% Wilson interval for an accuracy near
  0.5 is about ±0.17; below that, per-sense results are reported descriptively only.
- **Low-frequency senses.** A cluster under 20% of its lemma's occurrences is "low-frequency". If none reaches the
  minimum in a direction, the report states that low-frequency senses were not testable in that direction rather
  than pooling them.
- **Minimum per direction.** At least 4 lemmas that each keep two or more clusters above the minimum. If a direction
  fails, that direction is stopped and the other proceeds alone, stated as such.
- **Agreement.** Lemmas below the registered α are dropped before held-out evaluation.
- **Global stop.** If neither direction meets its minimum, #184 is not started on this data and the epic records a
  data no-go.

Rough budget, for planning only: 6 lemmas per direction × 3 clusters × (30 held-out + 20 validation + 40 training)
families is about 3,240 occurrences and about 6,500 single annotations before adjudication. The real count depends on
the audit below.

## Count audit to run before #184 (not run here)

This step reads candidate text, not labels. It must be run before #184 freezes the lemma list, and it reports only
lemma occurrence counts and family counts per source, never sense labels or system output.

1. Download the Tatoeba `sentences`, `links` and `sentences_base` exports and the Europarl v10 `es-en` TSV, recording
   file names, sizes, dates and SHA-256 in the report revision.
2. For each declared lemma and direction, count occurrences (lemma form list frozen in advance) and distinct families.
3. Draw a fixed-seed sample of 50 occurrences per lemma from **training families only**, and have one annotator
   estimate the cluster distribution. This estimate decides whether the low-frequency minimums are plausible; it
   is not gold and never enters an evaluation.
4. Narrow the lemma list or stop according to the rules above, and record the decision.

## Gaps and open checks

- Licenses of SemEval-2010/2013 CL-WSD, SemEval-2013 Task 12, SemEval-2015 Task 13, DiBiMT and Senseval-3 [NV].
- Whether Tatoeba's CC0 subset includes English and Spanish [NV].
- Whether OMW's Spanish file maps to PWN 3.0 offsets as assumed [NV].
- The legal status of Wiktionary-derived extracts beyond the CC BY-SA inheritance [NV].
- UN corpus redistribution terms [NV].
- The occurrence and per-sense counts in any candidate source: unknown until the count audit.

## Limitations

- The audit relied on public pages and papers; dead hosts and unreachable pages are reported as such, not guessed.
- Counts quoted from papers describe their releases, not files this project has checked.
- The minimums and the budget are design proposals for #184, not measurements.
- Tatoeba sentences are short and partly written to be translated; results on them may not transfer to longer or
  natural text. Europarl is formal parliamentary speech. Both biases are reported strata, not corrected away.

## Boundaries preserved

- No code, dependency, `Signal` change or default change. Neuron core gains no DJL, PyTorch or provider dependency.
- Any dataset, aligner or encoder is offline evaluation tooling; persistent memory stays in Monada Resonance Store.
- ADR 0050 is unchanged and binding. No ADR is added: this report records no durable architectural decision; #184
  will register the data and partition protocol.
