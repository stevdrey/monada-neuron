# HTML Knowledge Corpus Ingestion (`html-kc-v1`)

## Question

Can a small, versioned HTML learning corpus be imported into a real, temporary embedded Monada Resonance Store
through its published `MonadaMemory.remember` API with exact, inspectable provenance, and can every chunk be
mapped back from the atom the Store returned?

This page covers ingestion only (Experiment Knowledge 2/8,
[#105](https://github.com/stevdrey/monada-neuron/issues/105)). It implements the
[corpus contract](../experiments/html-knowledge-v1.md#corpus-contract-html-kc-v1-delivered-by-105) of the
HTML knowledge-memory protocol. It does not score recall, evidence use or answers (#106 to #109). Remembering
text is external memory, not training: nothing here learns HTML.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. Without it the task
does not exist and `./gradlew test` does not change.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion -PbenchmarkArgs="--store-root /tmp/html-kc-store"
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*'
```

| Option | Meaning |
| :--- | :--- |
| `--output-dir <dir>` | report directory, default `build/reports/benchmarks` (relative to `monada-neuron-evaluation`) |
| `--store-root <dir>` | keep the Store in this directory instead of a temporary one that is deleted afterwards; it must be absent or empty |
| `--mdn-snapshot <index.md>` | opt-in external snapshot (see [below](#optional-mdn-snapshot)) |
| `--mdn-revision <commit>` | `mdn/content` commit the snapshot was taken from |
| `--mdn-sha256 <hex>` | SHA-256 of the snapshot's raw bytes |
| `--mdn-store-root <dir>` | keep the snapshot's own Store root |

Exit status: `0` when all checks pass, `1` when a semantic check fails, `2` for invalid usage or rejected input.
Rejected input includes an invalid corpus, a missing or mismatched requested snapshot, and a target root that
already holds a store.

## Outputs

| File | Content |
| :--- | :--- |
| `html-knowledge-ingestion.json`, `.md` | semantic checks, metadata (versions, `corpusHash`, bounds, chunking policy, licenses, Store manifest fields, commits), environment |
| `html-kc-v1-chunks.tsv` | every chunk's labels and provenance: `chunkId`, `documentId`, `topic`, `section`, `source`, `license`, `attribution`, `[start, end)` code point span, `sha256`, `documentSha256`, conflict pair and role (no text) |
| `html-kc-v1-manifest.tsv` | `chunkId → atomId → opaqueReference` for this import (no content hashes) |
| `html-kc-ext-mdn-v1-*.tsv` | the same two files for a supplied snapshot |

Semantic checks:

| Check | Verifies |
| :--- | :--- |
| `corpus.valid` | normalization, labels, spans, digests, bounds and the single conflict pair, against canonical rechunking |
| `corpus.deterministicReload` | a second load yields identical labels, order, spans, fingerprints and `corpusHash` |
| `ingestion.manifestComplete` | every chunk maps to its own returned atom, in `chunkId` order |
| `ingestion.contentFidelity` | each returned atom's content hashes to the chunk `sha256` |
| `ingestion.readback` | `resonate(<chunk text>).topK(<corpus size>).threshold(0.0)` returns the mapped atom with matching content |

The `perf.*` metadata values are a single exploratory wall-clock sample and never affect a verdict.

## Corpus

`monada-neuron-evaluation/src/integration/resources/html-kc-v1/` holds seven originally authored, Apache-2.0
Markdown documents with 33 chunks. They cover headings, landmarks, `div` vs `iframe`, links, images and `alt`,
media, `ol` vs `ul`, `strong` vs `b`, `em` vs `i`, forms, and `script` inline vs `src` with defer/async and CSP. The
corpus deliberately includes:

- confusable pairs;
- facts stated inside fenced code;
- cross-topic distractors (`iframe` sandboxing, obsolete presentational elements);
- one conflicting-source pair, `conflict:img-alt`, where a legacy note contradicts the requirement for `alt`.

`documents.tsv` cites the original specifications by URL only. No third-party prose is copied.

`chunks.tsv` must equal the canonical chunking of the documents (policy `heading-section-v1`, see the
[protocol](../experiments/html-knowledge-v1.md#delivered-implementation-105)). Changing any text means
regenerating the labels and bumping the corpus version. `HtmlKnowledgeCorpusTest` pins the `corpusHash` of
`html-kc-v1`, so an unversioned change fails the build.

Rejected input: an empty or oversize file, a malformed table row, a bad id, a path in `file`, invalid UTF-8, a
byte-order mark or control character, a digest, span or section mismatch, duplicate ids, identical chunk texts,
too many chunks, and a missing or doubled conflict pair. Each rejection is a `CorpusValidationException` naming the
file, row and rule.

## Store atom ids

The Store derives atom ids from content, and `remember` is idempotent. The harness relies on neither:

- it records what `remember` returned in the manifest;
- it rejects identical chunk texts, which would collapse into one atom;
- it refuses to import into a root that already holds files, because the Store would silently accept a second
  import.

See the protocol [errata](../experiments/html-knowledge-v1.md#errata-store-atom-ids).

## Optional MDN snapshot

Nothing is downloaded. To ingest MDN's *HTML: Creating the content*, take the Markdown source from your own
`mdn/content` checkout. Its path is
`files/en-us/learn_web_development/getting_started/your_first_website/creating_the_content/index.md`.

```bash
git -C ~/src/mdn-content rev-parse HEAD
sha256sum ~/src/mdn-content/files/en-us/learn_web_development/getting_started/your_first_website/creating_the_content/index.md
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion -PbenchmarkArgs="--mdn-snapshot <path to index.md> --mdn-revision <commit> --mdn-sha256 <sha256>"
```

The snapshot becomes the separate corpus `html-kc-ext-mdn-v1` in its own root.

Licensing: MDN prose is licensed **CC BY-SA 2.5** by Mozilla Contributors. Every chunk records:

- the license;
- the attribution;
- the page URL with the pinned revision;
- the modifications made: front matter removed, normalized and chunked.

Share-alike applies to any adapted text. Never commit the snapshot or any output that contains its text, and do not
run it in CI, without an explicit licensing review. The outputs of this task contain ids, spans and digests only.

When the snapshot options are absent, the report states `external.mdn: unavailable (not supplied)`. When a
requested snapshot is missing or its digest differs, the run fails before any Store is written.
