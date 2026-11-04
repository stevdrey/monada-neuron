# Contextual Bilingual Sense Selection Protocol, AI-Agent Annotation (`bilingual-sense/2`)

## Status

| Field | Value |
| :--- | :--- |
| Protocol version | `bilingual-sense/2` |
| Issue | [#188](https://github.com/stevdrey/monada-neuron/issues/188) (Epic [#182](https://github.com/stevdrey/monada-neuron/issues/182)) |
| Supersedes | [`bilingual-sense/1`](bilingual-sense-v1.md) (#184) for the rules in the [change record](#change-record). Every other v1 rule applies unchanged |
| Decision record | [ADR 0052](../adr/0052-ai-agent-annotation-bilingual-sense.md), amending [ADR 0051](../adr/0051-bilingual-sense-evaluation-protocol.md) |
| Binding constraints | [ADR 0050](../adr/0050-ncm-investment-decision.md) (NCM no-go) unchanged |
| Corpus snapshot | Recorded in the [feasibility report](bilingual-sense-feasibility.md#step-2-corpus-snapshot); not replaced |
| Agent configuration | Registered by a separate [configuration commit](#configuration-registry) before step 4. Step 4 cannot start without it |
| Executes | #185, only in the [order below](#execution-order) |
| Code changes | None. No harness, orchestrator, dependency, `Signal` change or default change is added or authorized |

`bilingual-sense/1` requires two independent bilingual human annotators and a human adjudicator. None can be engaged.
This version lets distinct AI agents annotate and adjudicate, and states what the resulting labels can and cannot
support. **Labels made under this version are model judgements, not human annotation, and no result based on them is
independently human-validated evidence.**

**Disclosure.** At the commit that adds this version, the project holds exactly the four corpus files listed in the
feasibility report, downloaded 2026-11-04 between about 03:00 and 03:08 UTC, after the v1 commit (`216db5c`,
2026-11-04 02:38 UTC). Their sizes and SHA-256 were recomputed for this version and match the owner's download record,
and the archives passed `bzip2 -t` / `gzip -t`. No archive has been read beyond those integrity tests: no occurrence
has been counted, no `file_id` inspected, no family formed, no split assigned, no inventory written, no item shown to
any annotator, human or AI, no label created, no agent called for this study and no system built or run. The held-out
split has never been read, so v1's single-use rule is not triggered. An auxiliary owner note (`guia-anotacion.md`,
outside the repository) describes v1's human procedure; it is not part of this protocol.

**Why a new version.** v1 records the download as part of step 2, so step 2 has started and any rule change needs a
new version (v1 [Versioning](bilingual-sense-v1.md#status)). Every change below is registered before any count,
label or evaluation output under this version exists.

## What this version does not claim

- AI agents agreeing with each other is not evidence that they are right. Inter-agent α measures consistency only.
- Independent sessions, or even different providers, do not imply independent errors: models share training data and
  may have seen Tatoeba and Europarl and their translations.
- An agent label is not "a human-annotated label or validated source" in the sense of Epic #182. Under this version,
  meaning comes from **`agent-gold`**: a model-derived judgement constrained to the source sentence and the frozen
  inventory. It is not semantic grounding, and nothing here establishes that any arm understands meaning.
- No verdict re-opens the NCM wave pipeline (ADR 0050), and no verdict opens sentence translation
  ([R12](#change-record)).

## Change record

Each row names the v1 rule, the v2 rule and the reason. Rules not listed are unchanged, including the task, sources,
lemma candidates, forms, inventory freeze, near-duplicate rule, keyed hashes (the HMAC key stays
`"bilingual-sense/1"`, so the split is not re-rolled), split proportions, caps, data gates, arms, grids, selection,
metrics, margins, checks, resource limits and the per-direction verdict structure.

| Id | v1 rule | v2 rule | Reason |
| --- | --- | --- | --- |
| R1 | Two independent bilingual human annotators per item, blind to each other ([Annotation](bilingual-sense-v1.md#annotation); feasibility [item 3](bilingual-sense-feasibility.md#proposed-responsible-annotation)) | Two AI annotator agents **A1** and **A2** from different model families, under the [isolation rules](#isolation-and-blinding) | No human annotators are available |
| R2 | Adjudication by a third person | Adjudication by an AI agent **J**, in two locked calls ([Adjudication](#adjudication)) | As R1 |
| R3 | Annotators may use the inventory and dictionaries, but no machine translation, LLM or system arm in pass A | Agents receive only the frozen guidelines, the inventory with its cited glosses and the item. No tool, web, retrieval, dictionary lookup, translation system or arm output. Their own trained knowledge cannot be removed and is [disclosed](#correlated-errors-and-contamination) | The annotators are themselves LLMs; tool access would make calls non-reproducible and open undisclosed sources |
| R4 | "Machine-generated labels are never gold" | The adjudicated agent label is **`agent-gold`**, the only gold of v2. Every artifact, table and report carries that provenance class and never calls it human annotation | Resolves the direct conflict; keeps the distinction visible |
| R5 | Krippendorff's α ≥ 0.67 on pass A, per `(direction, lemma)` | Unchanged value and computation, between A1 and A2. Reported as **inter-agent consistency**, never as accuracy | Low α still signals an ill-posed lemma or inventory; high α does not signal correctness |
| R6 | Staffing condition: without two annotators and an adjudicator, the direction is a data no-go at step 6 | **Availability condition**: without the [configuration commit](#configuration-registry) satisfying the family rule before step 4, or if a registered model becomes unavailable before step 6 ends, both directions stop as a data no-go (resumption only as `bilingual-sense/3` with a full relabel) | The constraint moves from people to models |
| R7 | The annotation lead writes the inventory from WordNet/OMW and Wiktionary | The lead (the project owner, a human) remains responsible and commits it. An agent may draft clusters only from entries the lead supplies, each cluster citing them; the draft is retained beside the committed file | Lightens the lead's work without changing the freeze rule |
| R8 | The annotator marks the occurrence span | The span is the declared-form match, fixed mechanically at step 2; the agent judges the label only. A non-lemma match is labeled `OOI`, as v1 already requires | The span is already deterministic; marking it adds no information |
| R9 | Contrastive pairs are annotator-marked; constructed sentences are their own stratum | Agents see one item per call and cannot mark pairs, and no sentence is constructed. The contrastive stratum becomes **against-majority**: `ANS` items whose gold set excludes the lemma's training-majority cluster (Arm A's choice). The translation-divergent stratum is computed mechanically from pass B ([Pass B](#pass-b-translation-revealed-diagnostic)) | Keeps a reported contrastive stratum with a definition fixed before labels |
| R10 | Annotator identities are not published | Agent identities (provider, model id, settings) **are** published; the lead's identity as auditor is stated as the owner | Model identity is part of the provenance an audit needs |
| R11 | Verdicts `GO`, `NO-GO`, `NOT TESTABLE`, `UNTRUSTWORTHY` | Same rules, each qualified by its [label status](#verdicts-and-claims): `agent-labeled` or `agent-labeled, audited` | A result is only as strong as its labels |
| R12 | Sentence translation may be proposed if both directions are GO | An agent-labeled GO, audited or not, does **not** satisfy that condition. Sentence translation stays closed for Epic #182 unless a later version adds human-annotated held-out data and both directions are GO on it | A sentence-level decision cannot rest on model-only gold |
| R13 | Family id: SHA-256 of the smallest member sentence id, prefixed with the source name | [Sentence keys and family ids](#sentence-keys-and-family-ids) | v1 did not define Europarl sentence ids or a family that spans both sources |
| R14 | `h(occurrence, ·)` over an unspecified occurrence identity | [Occurrence id](#occurrence-id) | Needed for a reproducible order |
| R15 | Calibration sample in `calibration` order; caps "in `cap` order" for every split | [Sample ordering](#sample-ordering) | The two rules overlap for calibration and pilot |
| R16 | Europarl `file_id` validation at step 2 | Extended with a column-language check ([Sentence keys](#sentence-keys-and-family-ids)) | The `es-en` TSV's column order is unverified |
| R17 | Held-out gold sealed offline by the lead until step 8 | Sealing extends to every raw agent output on held-out items ([Held-out handling](#held-out-handling)) | Raw outputs reveal gold |
| R18 | No independent check of label quality beyond α | Optional, pre-declared [owner audit](#optional-owner-audit) and reported [diagnostics](#correlated-errors-and-contamination). New gate `annotation.unlabeled` ([Gates](#added-gates)) | Characterizes uncertainty without a human reference set |

## Clarifications before partitioning

These rules fill gaps in v1 that would otherwise be decided while partitioning. They use no data.

### Sentence keys and family ids

- Tatoeba sentence key: `tatoeba:<sentence id>`, the decimal id of `sentences.csv`.
- Europarl sentence key: `europarl:<row>:<lang>`, where `<row>` is the 0-based index of the data row in the
  decompressed `europarl-v10.es-en.tsv` (the header row, if present, is not counted) and `<lang>` is `eng` or `spa`,
  the language of the column holding the sentence.
- **Column-language check (step 2).** Before counting, the two text columns are assigned to `eng` and `spa` from the
  file's header row, or, without one, from the v10 README's column order. The assignment is confirmed on the first
  100 data rows in file order by a marker count fixed now. For each column, count the characters `ñ á é í ó ú ¿ ¡`
  plus the tokens (after the registered tokenization) `el la los las de que y en`; a row confirms the assignment when
  the column assigned `spa` has the strictly larger count. If fewer than 90 of the 100 rows confirm it, Europarl is
  dropped under v1's drop rule. Besides these two columns, only `file_id` and the speaker's original-language tag
  are read; the tag is kept as offline item metadata for v1's original-versus-translated stratum. Speaker id, name and
  affiliation are never read into any artifact, and no Europarl metadata, the language tag included, is sent to any
  agent.
- A family's id is `SHA-256(UTF-8(k_min))` in lowercase hex, where `k_min` is the smallest member sentence key in
  byte-wise UTF-8 order. The key already carries the source, so v1's "prefixed with the source name" is satisfied
  without a second prefix.
- A near-duplicate merge may join a Tatoeba family and a Europarl family. The merged family is one family with one id,
  assigned once by v1's rules; source is reported per item, not per family.

### Occurrence id

`direction|lemma|sentenceKey|start|end`, encoded as UTF-8, where `direction` is `EN>ES` or `ES>EN`, `lemma` is the
candidate table's lemma, and `start` (inclusive) and `end` (exclusive) are 0-based offsets in Unicode code points into
the sentence text as stored in the source file, before NFKC or case folding. Offsets are computed on the stored text
and the declared-form match is computed on the registered tokenization; a match is mapped back to stored offsets by
tracking code-point positions through normalization. This string is the `id` of `h(occurrence, id)` and of
`h(audit, id)`.

### Sample ordering

- A family is **eligible** for `(direction, lemma)` when it holds at least one occurrence of a declared form of that
  lemma whose source language is the direction's source language.
- Calibration takes eligible calibration-reserve families in increasing `h(calibration, family id)`; the pilot takes
  eligible training families in increasing `h(pilot, family id)`. Each fills v1's cap for its set.
- `cap` order applies only to training, validation and held-out, each over its eligible families, after pilot
  families are removed from training.
- Within any chosen family, occurrences are taken in increasing `h(occurrence, occurrence id)`, up to the
  per-family limit.

## Agent roles

| Role | Kind | Sees | Produces |
| --- | --- | --- | --- |
| Orchestrator | Deterministic offline script, not an LLM | Everything, mechanically | Prompts, locks, logs, gold, gate counts |
| A1, A2 | AI annotators, different model families | Their own calls only | Pass A, then pass B, per item |
| J | AI adjudicator | Disagreeing items only | Source-only label, then final label |
| Lead | The project owner, human | Inventory, guidelines, calibration outputs, gate counts | Inventory, guidelines, configuration commit, sealed store |
| H (optional) | The project owner, human | Audit items, source-only | Audit labels; never gold |

- **Family rule.** A1 and A2 come from different providers and different base-model lineages (two fine-tunes of one
  base model are one family). J comes from a third family if one is available; otherwise J shares a family with one
  annotator, and that is disclosed in every report.
- Open-weight models run locally are allowed; their weight file SHA-256 is then registered.
- The orchestrator, like corpus and annotation tooling in v1, runs offline outside the Gradle build. No provider SDK
  or client enters any Neuron module.

## Isolation and blinding

- One item per call. Each call is a fresh, stateless request with no conversation history, no tools, no retrieval and
  no web access. Provider features that keep memory across calls are off. Where a provider offers no-retention or
  no-training settings, they are on; the setting in force is recorded either way.
- A1 and A2 never see each other's output. Neither sees J's output, split, family or source ids, item order, other
  items, or any system output.
- J sees the two pass-A labels as `X` and `Y`, ordered by `h(adjudication-order, occurrence id)` (`X` = A1 when
  `u < 0.5`), with no model name.
- No agent sees any arm, harness or metric output at any time. The arms of v1 never see raw agent outputs, cue spans,
  notes or pass-B labels; they see only `agent-gold` of training and validation items, as v1 already specifies.
- Items are submitted in increasing `h(occurrence, occurrence id)` within each step. Calls may run concurrently; no
  call depends on another except the order pass A → pass B and J call 1 → J call 2 on the same item.

## Prompts and item rendering

The prompt templates and output schema are frozen in this version. Each is sent as written, with its placeholders
filled; the configuration commit records only how a template maps onto a provider's request (for example, as one
user message) and may not edit the text.

| File | Use | SHA-256 |
| --- | --- | --- |
| [pass-a.txt](bilingual-sense-v2/pass-a.txt) | A1/A2 pass A | `145ffc86a45ac03a68c64756893c51200eacfcc48d422e5ba769c8938a7949ac` |
| [pass-b.txt](bilingual-sense-v2/pass-b.txt) | A1/A2 pass B | `f02549c04e2cdd691eb65b5a2ce5dc3a71dc33c140349187ee5c021e0e7c6129` |
| [adjudication-1.txt](bilingual-sense-v2/adjudication-1.txt) | J, source only | `5d4fadad0371bfa912af00a4ae26a20eea5653af2dce96325e8b57e2cf35a7e2` |
| [adjudication-2.txt](bilingual-sense-v2/adjudication-2.txt) | J, translation revealed | `8ea122a715fb9d903f5e12a355698f432edc778e63acfa3b10132b5ee7070262` |
| [continuation.txt](bilingual-sense-v2/continuation.txt) | A1/A2 continuation probe | `5e5881d797935bdfefd18900de5f711acaee6c81f1c8aa5423d90b919c11ef95` |
| [output-schema.json](bilingual-sense-v2/output-schema.json) | JSON Schema of every output | `30a899814812edca3afb99e173c6043575f5222f5512645bf31add450d2ef71c` |

Placeholders:

- `{{ROLE}}`: `A1` or `A2`.
- `{{GUIDELINES}}`: the guidelines file, frozen with its SHA-256 at the end of calibration (v1 rule). Every call
  except the continuation probe receives it, since each call is stateless.
- `{{INVENTORY}}`: the frozen clusters of the item's `(direction, lemma)`, one per line as
  `<ordinal>: <target expressions, comma-separated> — <gloss>`.
- `{{ITEM}}`: a JSON object `{"direction", "lemma", "sentence", "target", "start", "end", "marked"}`, where `marked`
  is the sentence with `⟦` and `⟧` inserted around the span. It carries no id of any kind.
- `{{OWN_PASS_A}}`, `{{LABEL_X}}`, `{{LABEL_Y}}`, `{{CALL1_LABEL}}`: the `label` object of the locked output.
- `{{PASS_B_X}}`, `{{PASS_B_Y}}`: the `realized` value of each annotator's pass B, in J's `X`/`Y` order.
- `{{PREFIX}}`: the continuation probe's token prefix.
- `{{TRANSLATION}}`: Tatoeba: the linked sentences in the target language, in increasing sentence id; Europarl: the
  other column of the same row.

## Annotation procedure

### Pass A (source only)

The annotator receives `pass-a.txt`. The output must validate against `passA`, every cluster ordinal must exist in
the inventory and every cue span must lie inside the sentence. The orchestrator appends the raw response and its
SHA-256 to the log before any pass-B request for that item is created. Pass A is then locked.

### Pass B (translation revealed, diagnostic)

A separate call with `pass-b.txt`. It cannot edit pass A; the orchestrator ignores any label it contains. An item is
**translation-divergent** when either annotator's `realized` cluster lies outside the gold set, or the gold is `UNA`
and either annotator names a cluster. Pass B never enters gold.

### Adjudication

- **Agreement** is identical pass-A labels (same kind and, for `ANS`, the same set). An agreed label is `agent-gold`
  without adjudication, as in v1.
- On disagreement, J call 1 (`adjudication-1.txt`) decides from the source and is locked as in pass A.
- J call 2 (`adjudication-2.txt`) reveals both pass-B reports and the translation. The orchestrator accepts a changed
  `final` only when `changeGround` is `SOURCE_CUE` with at least one valid cue span inside the sentence and not
  contained in the target span, or `INVENTORY_COVERAGE` with `final` = `OOI`. Any other change is rejected, logged,
  and the call-1 label stands. This makes v1's rule mechanical: a translation alone cannot turn `UNA` into `ANS`,
  narrow a set, or move an item between clusters.
- The final label is `agent-gold`. The call-2 change rate, by ground, is reported per `(direction, lemma)`.

### Calibration (step 4)

A1 and A2 annotate the calibration items under the draft guidelines; J adjudicates their disagreements. The lead reads
those outputs, revises only the guidelines, and repeats, for at most three rounds over the same items. Neither the
templates nor the configuration may change during calibration. The guidelines are then frozen with their SHA-256
before any pilot item is sent (v1 rule). Calibration items appear in no other set.

## Configuration registry

A single **configuration commit**, after this version is merged and before step 4, adds the filled table below to this
document and changes nothing else. It is part of the `bilingual-sense/2` freeze: changing any value afterwards is
`bilingual-sense/3` with a full relabel.

| Field | A1 | A2 | J |
| --- | --- | --- | --- |
| Provider and model family | | | |
| Exact model id (dated or versioned, never an alias) | | | |
| Weights SHA-256 (open-weight models only) | | | |
| Endpoint and region | | | |
| Request mapping of the template | | | |
| Temperature, top-p, max output tokens | | | |
| Seed (if supported) | | | |
| Structured-output mode (if used) | | | |
| Retention and training-on-data setting | | | |
| Price per input / output token at registration | | | |

Also registered in that commit:

- the per-provider cost cap and the total cap (reaching a cap pauses the run; it resumes only with the same
  configuration);
- whether the [owner audit](#optional-owner-audit) will be performed (`yes` / `no`), declared before any label exists;
- the SHA-256 of the six frozen files above, re-checked by the orchestrator before every step.

Settings rule: the lowest temperature the provider allows (`0` where supported), top-p `1`, no sampling penalties, a
registered seed where supported, and an output limit large enough for the schema (at least 512 tokens).

## Nondeterminism, failures and retries

- Hosted models are not guaranteed to be deterministic, even at temperature `0`. **Reproducibility means re-deriving
  every gold label, gate count and report value from the logged raw outputs, not regenerating them.** The log is the
  record of truth.
- **Log.** Append-only JSON Lines. Each record holds: call id, role, step, occurrence id, template SHA-256, the full
  request body, the raw response, the model id the provider reports, token counts, attempt number, timestamps (UTC),
  the validation result and the SHA-256 of the previous record (a hash chain, so edits are detectable). Held-out
  records go to a separate sealed log ([Held-out handling](#held-out-handling)).
- **Transport errors** (timeout, rate limit, 5xx): up to 3 retries of the identical request with exponential back-off.
- **Schema-invalid output**: exactly one re-ask with the identical request. A schema-valid output is never re-sampled,
  whatever its content, so no label is chosen by retrying.
- After those attempts an output that is still missing or invalid is **exhausted**. Its effect is fixed per call:

  | Exhausted call | Effect on the item |
  | --- | --- |
  | Pass A (either annotator) | The item is **`UNLABELED`**: excluded from gold, α and every diagnostic, and counted by `annotation.unlabeled`. Its pass B and J calls are not issued |
  | Pass B (either annotator) | Pass A stands and the item proceeds. If it needs adjudication, J call 1 runs and its label is final: J call 2 is not issued, because its template needs both pass-B reports. The item is counted as `passB-missing`, excluded from the translation-divergent stratum, and not `UNLABELED` |
  | J call 1 | The item is **`UNLABELED`**, as for pass A |
  | J call 2 | The call-1 label is final, exactly as for a rejected change; counted as `j2-missing` |
  | Stability or continuation probe | Counted as missing in that diagnostic; no effect on gold |

  `passB-missing` and `j2-missing` counts are reported per `(direction, lemma)` ([Gates](#added-gates)).
- **Model drift.** If the reported model id differs from the registered one, or the provider retires it, the run stops.
  Labels already made are kept and reported; continuing needs `bilingual-sense/3` and a relabel of every item with the
  new configuration.
- **Stability probe** (reported, not gated). After step 6, the first 50 training items per direction in
  `h(occurrence, ·)` order (non-pilot) are re-sent once to A1 and A2 as pass A. Intra-agent identical-label rate and
  α are reported. Probe outputs never change gold.

## Correlated errors and contamination

Known threats, disclosed rather than assumed away:

1. **Shared knowledge.** Different providers train on overlapping web text, dictionaries and possibly on these
   corpora. A1 and A2 can share the same error, which agreement then hides. Agreed items are never adjudicated, so a
   shared error becomes gold.
2. **Translation leakage into pass A.** A model may have seen a Tatoeba or Europarl sentence together with its
   translation, so its "source-only" label may reflect the translation. This would shrink `UNA` and the
   translation-divergent stratum.
3. **Inventory dependence.** Agents see glosses written by the lead, possibly drafted by an agent of the same family.
4. **Exposure to evaluated systems.** None of v1's arms is an LLM, and no arm reads agent outputs other than gold, so
   no annotator output can steer an arm. Any future LLM-based arm from a family used here cannot be evaluated on this
   gold (its family labeled it).

Reported diagnostics (none is a gate, none changes gold):

- per `(direction, lemma)`: A1/A2 confusion over `ANS` / `UNA` / `OOI`, agreement by class, `UNA` and `OOI` rates;
- translation-divergent rate by source and by original-versus-translated sentence;
- **verbatim-continuation probe** after step 6: for the first 50 training items per source in `h(occurrence, ·)`
  order with at least 8 tokens, each annotator gets the first `⌊n/2⌋` tokens of the sentence's `n` registered
  tokens, joined by single spaces, in a separate call with `continuation.txt`; the exact-match rate of the remaining
  tokens (the reply tokenized the same way) is reported. A high rate indicates memorization of the source text; a low rate does not prove its absence;
- the J call-2 change rate and the rejected-change count.

## Added gates

Applied mechanically with v1's [gates](bilingual-sense-v1.md#gates-frozen-applied-mechanically); every outcome is
recorded in the feasibility report before step 8.

| Gate | Step | Rule |
| --- | --- | --- |
| `annotation.availability` | 4 | The configuration commit exists, satisfies the family rule and its file hashes match. Otherwise both directions stop (R6) |
| `annotation.unlabeled` | 7 | A `(direction, lemma)` with more than 2% of its annotated items `UNLABELED` (an exhausted pass A or J call 1) is dropped. Remaining `UNLABELED` items are excluded and counted. `passB-missing` and `j2-missing` items are not `UNLABELED`; they are reported only |
| Agreement (v1) | 7 | Unchanged: α ≥ 0.67 between A1 and A2 on pass A |

## Optional owner audit

The audit gives a bounded, partial check of `agent-gold` against one human. It is not independent annotation: the
auditor is one person, the project owner, who designed the protocol and is not blind to its hypotheses.

- **Declared in advance.** Whether it runs is fixed in the configuration commit. If declared, its result is reported
  whatever it is; an incomplete audit is reported as such and the label status stays `agent-labeled`.
- **Sample.** After the step-7 gates, the first 100 validation items per direction, over the scored lemmas, in
  increasing `h(audit, occurrence id)`. Held-out items are never audited. Because families reach validation and
  held-out by the same keyed hash and are labeled by the same procedure, the validation error rate estimates the
  held-out one; this exchangeability is an assumption, stated as such.
- **Procedure.** H sees what pass A shows (guidelines, inventory, item), may use dictionaries, and uses no machine
  translation, LLM or agent output. H's labels are locked before `agent-gold` for those items is revealed to H.
- **Error.** An item is an audit error when H's label and `agent-gold` differ in kind (`ANS` / `UNA` / `OOI`), or both
  are `ANS` with disjoint sets. α (MASI) between H and `agent-gold` is also reported.
- **Rule.** A direction is `audited` when its upper 95% Wilson bound on the error rate is at most `0.15`, with exactly
  100 items: at most 8 errors (bound 0.1500). With fewer than 100 eligible items the direction is not audited.
- Audit labels never change `agent-gold`, are never used by an arm and do not trigger relabeling.

## Held-out handling

- Held-out items are annotated by the same agents, prompts and configuration as every other split, so the label
  process does not reveal the split. They are sent in the same `h(occurrence, ·)` interleaving as validation items of
  the same step.
- Held-out gold **and** every raw held-out record (pass A, pass B, J calls) are written only to the sealed store kept
  offline by the lead. The orchestrator computes step-7 gate counts from them without displaying items or labels.
  The lead does not inspect held-out records before step 8.
- At step 7, the SHA-256 of the held-out gold file and of the sealed log are committed; step 8 verifies both
  (`leakage.sealed-hash`, extended).
- Sending held-out sentences to providers exposes text that is already public; the gold and raw outputs stay sealed.
  Provider retention settings are recorded. No arm is an LLM that a provider could later train on these calls.
- v1's single-use rule applies unchanged: no system has read the held-out split, and once any system reads it at
  step 8 no later version may reuse it as held-out evidence.

## Data handling

- Only the item fields above, the inventory, the guidelines and, in pass B and J call 2, the translation are sent.
  No sentence key, family id, split, Europarl speaker field or personal data is sent or stored in the log.
- Corpus files stay the recorded snapshot. Only ids, offsets, labels, raw non-held-out outputs and the sentences the
  release needs are published, with v1's attribution rules; held-out raw outputs are published only after step 8.

## Verdicts and claims

The per-direction verdict rule of v1 applies unchanged, and every verdict carries the direction's **label status**:

| Label status | Condition |
| --- | --- |
| `agent-labeled` | Default |
| `agent-labeled, audited` | The declared owner audit passes for that direction |

- A GO is reported as **`EXPLORATORY GO (agent-labeled)`** or **`EXPLORATORY GO (agent-labeled, audited)`**. It
  supports only further research on that direction under a protocol with human-annotated held-out data.
- A NO-GO is equally conditioned on `agent-gold`: label noise can hide a real difference, so a NO-GO here is
  evidence against the candidate on agent labels, not against the task.
- No verdict of this version is independently human-validated evidence, satisfies Epic #182's human-annotated or
  validated-source requirement, opens sentence translation (R12), establishes semantic grounding, or bears on
  ADR 0050.

## Execution order

v1's steps apply with these changes.

| Step | v2 change |
| --- | --- |
| 1 | This version; then the configuration commit, before step 4 |
| 2 | The snapshot is the one already recorded. Counts, Europarl `file_id` validation and the column-language check |
| 3 | Split with the clarified family ids |
| 4 | `annotation.availability`; inventory freeze (R7); calibration with A1, A2, J; guideline freeze |
| 5 | Pilot with A1, A2, J; projection gate |
| 6 | Full annotation with A1, A2, J; held-out sealed; then the stability and continuation probes on training items |
| 7 | v1 gates plus `annotation.unlabeled`; hashes committed; then the owner audit if declared |
| 8 | Unchanged, with the label status attached to every verdict |

## Effect on #185 and Epic #182

**#185 may resume only when all of these hold:**

1. this version and ADR 0052 are merged;
2. the configuration commit is merged and `annotation.availability` passes;
3. steps 2 to 7 have run under this version and their outcomes, including every added gate and diagnostic, are
   recorded in the feasibility report;
4. the held-out gold and sealed-log hashes are committed.

#185 may still build and unit-test the harness on synthetic or training data at any time. Its report must state the
label status in its title and summary, report the diagnostics above beside the results, and never describe `agent-gold`
as human annotation.

**Epic #182.** The feasibility report found no human-annotated or validated set for this task. Under ADR 0052 the
epic accepts `agent-gold` as an exploratory substitute: its acceptance criteria can be met with an exploratory
decision, and the epic's final decision states that no human-validated evidence was produced. Any claim that requires
such evidence needs a later version with human-annotated held-out data.

## Limitations

- No independent human reference exists. Without the audit, label accuracy is unknown; with it, it is bounded on one
  person's judgement over 100 items per direction.
- Agent errors may be correlated in ways no diagnostic here can measure, and contamination by the source corpora is
  likely and only partly probed.
- Hosted models may change behind a stable id; the drift rule catches only reported changes.
- The family rule depends on what providers disclose about their models.
- Every v1 limitation remains, including that the data may end in a data no-go.

## Changelog

- `bilingual-sense/2` (#188): AI-agent annotation and adjudication (R1–R12, R17, R18) and partitioning
  clarifications (R13–R16), registered after the snapshot download and before any count, label or system output.
  Review fixes before merge, before any data exists: the Europarl original-language tag is read as offline metadata
  for v1's stratum and never sent to agents; every exhausted call has a fixed effect (pass B and J call 2 failures
  no longer leave adjudication undefined); pass B and J call 2 receive the frozen guidelines, as every stateless
  annotation call must.
