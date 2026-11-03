# Bilingual Word-Sense Data Feasibility Report (Issue #183)

## Status

| Field | Value |
| :--- | :--- |
| Issue | [#183](https://github.com/stevdrey/monada-neuron/issues/183) (NCM Research 1/3, Epic [#182](https://github.com/stevdrey/monada-neuron/issues/182)) |
| Kind | Feasibility report. It precedes the protocol (#184) and the evaluation (#185) |
| Audit date | 2026-11-03 |
| Method | Manual desk audit of public pages and papers (below). No dataset archive was downloaded, no example was labeled and no model was trained or run |
| Protocol | Step 1 is registered as [`bilingual-sense/1`](bilingual-sense-v1.md) (#184, [ADR 0051](../adr/0051-bilingual-sense-evaluation-protocol.md)) |
| Code changes | None. The runtime, `Signal`, defaults and the opt-in NCM stage are unchanged |
| Verdict | **Conditionally feasible, narrowed.** No existing dataset can serve as held-out evidence for both directions. The task is feasible only with a new, small, independently annotated dataset over redistributable source text, limited to a pre-declared set of target lemmas. Low-frequency senses enter the evaluation only where they pass the frozen gates of the [executable order](#executable-order) (text counts, pilot projection, then held-out gold counts); otherwise they are reported descriptively or dropped |

This report does not choose an implementation, a model or thresholds, and it does not claim that any label exists
beyond what a cited source states. It does not inspect, and cannot inspect, any held-out result: none exists.

## Task under assessment

From Epic #182: given a sentence, one occurrence of an ambiguous target word in it, and the language direction,
select an acceptable expression in the other language (English → Spanish or Spanish → English), or abstain when the
context is insufficient or the occurrence falls outside the inventory.

| Element | Definition used for this assessment |
| --- | --- |
| Occurrence | One token span of a target lemma in one source sentence, identified by sentence id and character offsets |
| Context | The source sentence only (optionally its immediate neighbours, if the source has document order), exactly the window annotators see in the source-only pass. The aligned translation is never a system input |
| Direction | `EN→ES` and `ES→EN`, evaluated and reported separately. A lemma pair in one direction is not evidence for the other |
| Candidates | A fixed, per-lemma, per-direction inventory of sense clusters, each listing acceptable target expressions; frozen before annotation of any split |
| Gold | Decided from the **source context alone** ([two-pass annotation](#proposed-responsible-annotation)): a **set** of acceptable clusters (multiple valid answers allowed), or `UNANSWERABLE` (the context does not decide), or `OUT_OF_INVENTORY` (no cluster fits). The translation may check inventory coverage but never makes an item answerable or narrows its set |
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
| SemEval-2010 Task 2 CL-LexSub ([paper](https://aclanthology.org/S10-1002.pdf)) | `EN→ES` free-form Spanish substitutes; no predefined sense inventory [V] | 30 dev + 100 test words, nouns, verbs, adjectives and adverbs; the same sentences as SemEval-2007 LEXSUB, ten per word, from the English Internet Corpus; no training data [V] | Four native Mexican-Spanish annotators, as many substitutes as they could; pairwise ITA 0.2777 [V] | Distribution site and license not stated in the paper [NV] | Reference only, for lemma choice and substitute candidates: not gold (see consequence 2) |
| SemEval-2013 Task 12 ([paper](https://aclanthology.org/S13-2040.pdf)) | Monolingual ES and EN senses (BabelNet 1.1.1) over 13 parallel WMT news articles | ES: 1,481 BabelNet instances, 1.15 senses per instance [V] | One annotator per language, projection from English, reviewer on frequent lemmas; no IAA [V] | Host dead; license [NV]; BabelNet inventory is non-commercial | Indirect: monolingual senses, not translation choices |
| SemEval-2015 Task 13 ([paper](https://aclanthology.org/S15-2049.pdf)) | Monolingual ES and EN senses (BabelNet 2.5.1) over 4 parallel OPUS documents | ES: 1,239 instances; EN: 1,261 [V] | Two taggers plus judge; agreed on at least one meaning for 68% [V]; multiple senses allowed | Open zip on the task's Data page; license [NV] | Indirect; only 4 documents, so splits are coarse |
| XL-WSD ([site](https://sapienzanlp.github.io/xl-wsd/)) | ES senses (BabelNet), repackaging SemEval-2013/2015 | ES test 1,851, dev 452, silver train 393,539 [V, site chart data] | Inherited from the SemEval sets | CC BY-NC, sharing "only to research institutions" [V]; full license page 404 | No public redistribution of derived examples |
| DiBiMT ([paper](https://aclanthology.org/2022.acl-long.298.pdf)) | `EN→ES` (and others); good/bad lexicalisations per item | 597 items, 305 lemmas [V] | One professional translator for Spanish; no IAA [V] | Closed benchmark with a public leaderboard [V]; license [NV] | No: gold not downloadable |
| MuCoW WMT 2019 ([paper](https://aclanthology.org/W19-5354.pdf), [repo](https://github.com/Helsinki-NLP/MuCoW)) | `ES→EN` scoring suite only; no `EN→ES` [V] | 515 source words, 1,090 synsets, 72,295 contrastive pairs [V] | **Built automatically** from word alignments and BabelNet 4.0 [V] | Repo CC-BY-4.0 [V] | Not as gold: automatic labels are not independent annotation. Possibly a source of candidate lemmas |
| MCL-WiC ([paper](https://aclanthology.org/2021.semeval-1.3.pdf)), XL-WiC ([site](https://pilehvar.github.io/xlwic/)) | No Spanish [V] | — | — | CC BY-NC 4.0 [V] | No |
| Senseval-3 Spanish lexical sample ([paper](https://aclanthology.org/W04-0806.pdf)) | Monolingual ES, MiniDir-2.1 inventory linked to EuroWordNet | 46 words; train 8,430, test 4,195 [V] | Three experts plus adjudication; full agreement 83–90% by POS [V] | License [NV] | Indirect: monolingual senses, EFE news text of unknown terms |

Consequences:

1. `ES→EN` has no human-annotated occurrence-level gold among the audited sources.
2. `EN→ES` has two kinds of human-annotated data, and neither can serve as held-out evidence from this public
   repository on current information:
   - **Sense or translation-cluster gold** (CL-WSD 2010 and 2013) covers 20 to 25 nouns; its hosts are gone and its
     terms are unknown. DiBiMT's gold is closed.
   - **Lexical substitution** (CL-LexSub 2010) covers more lemmas, 130 words across the open parts of speech, but its
     labels are free-form substitutes with no inventory, so they become clusters only through new annotation. It has
     about ten sentences per word, far below any per-sense minimum, low agreement (0.2777), no training data and an
     unverified host and license.
3. The open sense-annotated Spanish sets depend on BabelNet, whose license restricts use and redistribution to
   research institutions [V, [license](https://babelnet.org/full-license)].
4. No audited dataset labels `UNANSWERABLE` contexts explicitly. Abstention gold must be created.

## Redistributable source text

Source text supplies sentences; it supplies **no** sense label. The aligned translation is shown to annotators only
after their source-only judgment, under the rules of [two-pass annotation](#proposed-responsible-annotation); it is
never a label.

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

Alignment may only **pre-highlight** the candidate counterpart in the translation-revealed pass B; it is hidden in
the source-only pass A and never becomes a label. It runs
offline as a tooling step and adds no dependency to any Neuron module.

## Feasibility by requirement

| Requirement (Epic #182 / issue #183) | Finding |
| --- | --- |
| Real, externally annotated or validated examples | Not available openly for this task. **Requires new independent annotation** |
| Occurrence-level alignment | Feasible: Tatoeba and Europarl give sentence alignment; occurrence spans are marked during annotation |
| `EN→ES` | Feasible with new annotation. Existing CL-WSD/DiBiMT sets are reference material only |
| `ES→EN` | Feasible only with new annotation. MuCoW's automatic labels may suggest lemmas but are not gold |
| Low-frequency senses | **Not guaranteed.** Whether a source holds enough occurrences of a rare sense is unknown until the count audit and pilot ([steps 2 and 5](#executable-order)). Where minimums fail, that sense is dropped or reported descriptively |
| Multiple valid answers | Supported by set-valued gold |
| Ambiguous / unanswerable cases | Must be created by annotators; no audited source labels them |
| Unfamiliar inputs | Constructed from occurrences of non-inventory lemmas and inventory-external senses, labeled `OUT_OF_INVENTORY` |
| Document / family-disjoint splits | Feasible: Europarl by session file; Tatoeba by translation cluster (below) |
| Redistributability | Feasible for Tatoeba (with attribution) and Europarl (with EP credit). Excludes BabelNet, XL-WSD, OpenSubtitles, News Commentary text |

## Proposed responsible annotation

1. **Lemma selection before any text is labeled.** The annotation lead declares the target lemmas per direction from
   dictionary evidence only (inventory entries with at least two clearly distinct translation clusters). No model
   output, no held-out text and no system score informs the choice.
2. **Guidelines and calibration set.** Written guidelines with examples of every label, calibrated on a sample that
   is excluded from every split and frozen before the pilot or any split is annotated
   ([step 4](#executable-order)).
3. **Two independent annotators per item**, bilingual (native in one language, proficient in the other), blind to
   each other, to the system arms and to the split assignment. Each item is annotated in two passes, because the
   aligned translation can reveal a sense the source does not determine.
   - **Pass A (source only).** The annotator sees exactly the context a system receives, with the translation and any
     alignment highlight hidden. They mark the occurrence span and select the set of acceptable clusters, or
     `UNANSWERABLE`, or `OUT_OF_INVENTORY`, with an optional note. Pass A is locked when submitted.
   - **Pass B (translation revealed).** The same annotator records the cluster the translation realizes, or
     "none / unclear". Pass B is diagnostic; it cannot edit pass A.
4. **Adjudication** by a third person when the pass-A labels disagree. The adjudicator first sees only the source
   and both pass-A labels and decides from the source. Only then may they consult pass B and the translation, and
   only to check inventory coverage (an `OUT_OF_INVENTORY` miss) or to find a source cue an annotator overlooked; a
   change on that ground cites the source span that justifies it. A translation alone never turns `UNANSWERABLE` into
   an answerable item and never narrows a source-only set. The adjudicated label is gold; all raw labels are kept.
   - **Translation-divergent items** keep their source-only gold and are reported as their own stratum: the
     translation attests a cluster outside the gold set, or the gold is `UNANSWERABLE` although a translation exists.
     Example: «Se acercó al banco» is `UNANSWERABLE` between *bank* and *bench* from the source alone, even when its
     translation says *bank*; a system that abstains there is correct.
5. **Agreement reporting** per lemma and direction on pass A: Krippendorff's α with a set distance (for example MASI)
   and the abstention-label confusion between annotators. The pass-A versus pass-B divergence rate is reported as a
   diagnostic. The minimum α a lemma needs is frozen at [step 1](#executable-order) and applied at step 7; a lemma
   below it is dropped before any system is run.
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

## Partition proposal (frozen at step 1, applied at step 3)

Splitting happens on **families**, never on sentences. Every parameter below is frozen in the #184 preregistration
([step 1](#executable-order)), and families are assigned ([step 3](#executable-order)) before any of them is
annotated.

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
5. Held-out labels are sealed: produced and adjudicated, but no system is run on them until the step-7 gates pass.

## Executable order

Every rule is frozen before the data it governs is read, and each gate is applied mechanically after the step that
produces its input. No system output enters any step before 8. Nothing below has been run; this report is step 0.

| Step | Where | Reads | Produces | Frozen beforehand |
| --- | --- | --- | --- | --- |
| 0 | #183 (this report) | Public pages and papers | Feasibility, proposals | — |
| 1 | #184 preregistration commit, before any corpus download | This report | The frozen protocol: source versions and checksum policy; lemma candidates and form lists (annotation step 1); the inventory freeze rule; family definitions, near-duplicate threshold, hash seed and split proportions; the pilot sample size and seed; the guideline revision policy; the minimum α; the text, projection and data gates below; the evaluation arms, metrics and margins for #185 | — |
| 2 | Count audit, after step 1 | Corpus text only, no labels. Tatoeba `sentences`, `links`, `sentences_base` and the Europarl v10 `es-en` TSV, with file names, sizes, dates and SHA-256 recorded | Occurrence and family counts per (direction, lemma); the lemma list after the **text gate** | Step 1 |
| 3 | Split assignment | Family ids | Family-to-split map, including a calibration reserve outside every split; held-out families sealed | Step 1 (seed, proportions, families) |
| 4 | Calibration | A sample of the calibration reserve | The frozen guidelines; revisions allowed only here | Step 1 (revision policy) |
| 5 | Pilot | A fixed-seed sample from **training families only**, two-pass annotated under the step-4 guidelines | Estimated cluster shares; clusters and lemmas kept after the **projection gate** | Steps 1 to 4 |
| 6 | Full annotation | Every split, two-pass, with adjudication | Gold, raw pass-A and pass-B labels | Step 4 guidelines |
| 7 | Data gates | Gold counts and pass-A agreement only; no system output | Pass or fail per (direction, lemma, cluster), with counts, recorded in a revision of this report | Step 1 (gates) |
| 8 | #185 | The sealed held-out split, for the first time by any system | Results under the step-1 evaluation protocol | Steps 1 and 7 |

Gate proposals for step 1 to freeze (they are not results):

- **Text gate (step 2).** A lemma stays only if it has enough candidate families in a direction to fill the
  registered training, validation and held-out quotas for at least two clusters at the minimum below. This gate is
  based on counts alone.
- **Projection gate (step 5).** For each cluster, the projected held-out count is the held-out families of the lemma
  times the cluster's pilot share. A cluster whose projection is below the minimum per sense is dropped before
  held-out annotation, so no effort is spent on items that cannot pass. Pilot labels come from training families,
  are not gold for any evaluation and are not re-used as held-out items. Because calibration precedes the pilot,
  the gate uses labels made under the final guidelines.
- **Late guideline changes.** Guidelines are frozen at step 4. If a material change is later unavoidable, it is a
  new protocol version recorded in the report: every pilot item is relabeled, the projection gate is re-applied
  from step 5, and every item already annotated in step 6 is relabeled under the new version.
- **Minimum per sense (step 7).** A (direction, lemma, cluster) enters the scored evaluation only if the held-out
  split holds at least 30 independent families with that gold cluster. At n = 30 the 95% Wilson interval for an
  accuracy near 0.5 is about ±0.17. Below that, per-sense results are reported descriptively only.
- **Low-frequency senses (step 7).** A cluster under 20% of its lemma's gold occurrences is "low-frequency". If none
  reaches the minimum in a direction, #185 states that low-frequency senses were not testable in that direction,
  rather than pooling them.
- **Agreement (step 7).** Lemmas whose pass-A α is below the frozen minimum are dropped.
- **Minimum per direction (step 7).** At least 4 lemmas that each keep two or more clusters above the minimum. If a
  direction fails, that direction is stopped and the other proceeds alone, stated as such.
- **Global stop (steps 2, 5 or 7).** If neither direction can still meet its minimum, #185 runs no system on this
  data and the epic records a data no-go.

Rough budget, for planning only: 6 lemmas per direction × 3 clusters × (30 held-out + 20 validation + 40 training)
families is about 3,240 occurrences and about 6,500 single pass-A annotations before adjudication. The real count
depends on steps 2 and 5.

## Gaps and open checks

- Licenses of SemEval-2010/2013 CL-WSD, SemEval-2013 Task 12, SemEval-2015 Task 13, DiBiMT and Senseval-3 [NV].
- Whether Tatoeba's CC0 subset includes English and Spanish [NV].
- Whether OMW's Spanish file maps to PWN 3.0 offsets as assumed [NV].
- The legal status of Wiktionary-derived extracts beyond the CC BY-SA inheritance [NV].
- UN corpus redistribution terms [NV].
- The occurrence and per-sense counts in any candidate source: unknown until steps 2, 5 and 7 of the executable order.

## Limitations

- The audit relied on public pages and papers; dead hosts and unreachable pages are reported as such, not guessed.
- Counts quoted from papers describe their releases, not files this project has checked.
- The minimums and the budget are design proposals for the #184 preregistration to freeze, not measurements.
- Tatoeba sentences are short and partly written to be translated; results on them may not transfer to longer or
  natural text. Europarl is formal parliamentary speech. Both biases are reported strata, not corrected away.

## Boundaries preserved

- No code, dependency, `Signal` change or default change. Neuron core gains no DJL, PyTorch or provider dependency.
- Any dataset, aligner or encoder is offline evaluation tooling; persistent memory stays in Monada Resonance Store.
- ADR 0050 is unchanged and binding. No ADR is added: this report records no durable architectural decision; #184
  will register the data and partition protocol.
