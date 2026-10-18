# HTML Knowledge Persistence and Reopen (`html-kc-v1`)

## Question

Is every `html-kc-v1` chunk actually persisted by a real embedded Monada Resonance Store, and does it survive a new
Store open in a separate process? And what exactly does Neuron's `ResonanceStoreMemoryAdapter` return for that root,
traced to the exact persisted atoms?

This page covers Experiment Knowledge 3/8, [#106](https://github.com/stevdrey/monada-neuron/issues/106). It implements
the protocol's [ingestion readback and post-reopen (restart)](../experiments/html-knowledge-v1.md#run-conditions)
conditions. It exercises the textual `MonadaMemory` API, not `ExecutionMemory`.

It does **not** score retrieval quality ([#107](html-knowledge-recall.md)), evidence use (#108) or answers (#109). Recalling a stored chunk is
external memory, not reasoning, and nothing here learns HTML.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. CI runs these tests in the
adapter job as part of `:monada-neuron-evaluation:integrationTest`.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgePersistence
./gradlew :monada-neuron-evaluation:runHtmlKnowledgePersistence -PbenchmarkArgs="--store-root /tmp/html-kc-persisted"
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Persistence*'
```

| Option | Meaning |
| :--- | :--- |
| `--output-dir <dir>` | report directory, default `build/reports/benchmarks` (relative to `monada-neuron-evaluation`) |
| `--store-root <dir>` | keep the Store root instead of deleting a temporary one; it must be absent or empty and must not overlap the output directory |

Exit status:
- `0`: all checks pass.
- `1`: a semantic check failed.
- `2`: invalid usage or rejected input, such as an occupied store root or an unsafe output location.

The output directory is prepared exactly as for [ingestion](html-knowledge-ingestion.md#running). Earlier outputs are
deleted first, and nothing is ever written through a symbolic link.

## How a run works

| Step | Where | What it does |
| :--- | :--- | :--- |
| `seed` | child JVM 1 | imports the corpus with `HtmlKnowledgeIngestion` (the #105 path), observes the root, writes the manifest, its recall snapshot and its checks, then exits |
| `reopen` | child JVM 2 | requires `manifest.json`, parses the seed's manifest, opens a **new** `MonadaMemory` and a **separately opened** adapter, observes the root and compares it with the seed snapshot; then observes again with a second set of handles in the same JVM |
| audits | orchestrator | measures the default codecs and records failure cases, each on its own copy of the root |

Both child JVMs use the orchestrator's `java` and class path (`HtmlKnowledgePersistencePhase`). They also get its heap,
`-XX:` and module arguments (`phase.forwardedJvmArguments`), but not system properties or agents. Each child records
its own environment as `<phase>.jvm.*` (version, arguments, processors, max memory, GCs), since its timings were
measured there. The report's environment section describes the orchestrator. Process ids and exit statuses are
reported. `process.restart` judges isolation and completion only: it requires three distinct processes and both
children to have finished their phase (result written, exit `0` or `1`). Semantic failures fail their own `seed.*` or
`reopen.*` checks, not this one.

The Store has no `close`, and `adapter.close()` only marks the adapter unavailable. Same-process reopening is
therefore reported only as `persistence.freshHandleConsistency`, never as persistence evidence.

Reopen runs only if the seed left its result, manifest and snapshot and they parse. A seed JVM that crashed exits `1`,
the same as a semantic failure, so the exit status alone cannot decide this; a crashed seed is reported as a failed
`process.restart`. Each audit runs guarded: an exception it did not expect becomes a failed check carrying that
exception, so one audit cannot abort the run. `audit.removedVectorSegment` also counts a failure on first use
(readback or recall) as visible.

The harness checks `manifest.json` before every open, because `MonadaMemory.open` on a missing path silently creates
an empty store. A phase that finds no manifest exits `2` without a result, which the report shows as
`<phase>.completed` failing. If the root has lost its manifest by the time the audits run, they report a single failed
`audit.preflight`.

### Observation (each phase)

| Check | Verifies |
| :--- | :--- |
| `persistence.atomCount` | the Store's own `StorageIntegrityAuditor` is healthy, with 33 active atoms, 33 with a vector, no orphans and no duplicate vector ids |
| `persistence.encodingProfile` | the persisted `EncodingProfile` equals `MonadaMemory.getExpectedProfile(dims, defaults())`, and the vector format is the current `FIXED_RAW` |
| `persistence.readback` | every chunk recalls its manifest atom through `resonate(text).topK(33).threshold(0.0)`, and the content hash matches the corpus `sha256`; a failure names the chunk |
| `persistence.noUnmappedAtoms` | no recalled atom is missing from the manifest |
| `neuron.recallComplete` | every probe answers `COMPLETE` through the adapter |
| `neuron.referenceAttribution` | every adapter result's opaque reference maps through the manifest to a chunk |
| `neuron.signalAttribution` | the chunk named by the decoded Signal equals the chunk named by the reference |
| `neuron.directVsAdapterEqual` | for each single-Signal probe, the adapter returns the same chunks, order and scores as `MonadaMemory.resonate(query).topK(5).threshold(0.0)` |

The `reopen` phase adds the restart comparison:

| Check | Verifies |
| :--- | :--- |
| `persistence.restartProfileEqual` | the persisted profile equals the one the seed process read |
| `persistence.restartProbeSetEqual` | both processes ran the same probe set (equal `probeHash`) |
| `persistence.restartRecallEqual` | direct results of every probe equal the seed's, compared on chunk ids mapped through the manifest, order and bit-exact scores; a failure shows the first differing row |
| `neuron.restartEqual` | the same comparison for the adapter's results |
| `persistence.freshHandleConsistency` | a second set of handles in the same JVM observes the identical snapshot; same-process only |

Each phase check appears in the report prefixed with `seed.` or `reopen.`.

### Probes and codec

The probe set is fixed before any run and contains:
- one **readback probe** per chunk, whose query is the chunk's own normalized text;
- six hand-written **paraphrase probes** (`PersistenceProbes`).

The probe set is versioned (`probes.version`, currently `html-kc-probes-v1`). Its `probes.hash` is a canonical SHA-256
over the version, the `corpusHash`, the probe count and every probe id and query text, as length-prefixed fields (the
same canonical form as `corpusHash`). A changed query text therefore changes the hash even when the ids and count
stay the same. Each snapshot records the hash it was produced with.

Probes carry no expected answer. They are compared across processes and paths, not judged. All probes use `k = 5` and
`t = 0.0`.

The adapter is configured with the evaluation-only `ChunkTextProbeCodec` (`chunk-text-probe v1`):
- **Encoder:** a bounded lookup from a probe's query Signal (keyed by frequency) to its text.
- **Decoder:** hashes the recalled content **as the Store returned it** and maps the SHA-256 to a Signal unique to
  the corpus chunk with that digest. Content of no chunk is rejected, and the adapter then answers `FAILED` instead of
  inventing a match.

Attribution therefore has two independent routes: the opaque reference through the manifest, and the decoded Signal
through the persisted content. There is no query-to-answer table. Because the codec is a lookup, the protocol does not
allow it for L2 quality metrics; it is used here only for attribution and run comparison.

### Ordering and ties

- **Direct Store:** score descending, then atom id ascending (`LinearScanResonanceIndex`).
- **Adapter:** score descending, then query Signal index, then Store rank, deduplicated by atom. With one query
  Signal this reduces to the Store order, which `neuron.directVsAdapterEqual` checks.

Atom ids are never compared across roots, only chunk ids mapped through each root's manifest.

### Default codecs and audits (orchestrator)

| Check | Records |
| :--- | :--- |
| `neuron.defaultCodecInsufficient` | `ResonanceStoreAdapterConfig.defaults()` (`CanonicalSignalQueryEncoder` + `HashedSignalDecoder`) on the same root, defaults unchanged. Records: statuses, the number of distinct result lists, top-1 agreement with the probe codec, and hashed-Signal collisions. Passes only when every probe answers `COMPLETE` with at least one attributable result, the default's readback recall@1 is below `DEFAULT_CODEC_MAX_RECALL_AT_1 = 0.5`, and every hashed Signal could be attributed only by re-hashing the external corpus. Readback recall@1 is the share of `readback:<chunkId>` probes whose top result is that chunk; the label is the probe itself, not a curated answer. The probe codec's own readback recall@1 is reported next to it. A failed, unavailable or empty default response fails the check instead of counting as insufficiency. |
| `audit.missingPath` | the harness preflight refuses the path. Raw `MonadaMemory.open` on the same path creates an empty store, with `manifest.json` written (required), and readback then fails for 33/33 chunks. |
| `audit.pathIsFile` | the actual exception from a direct open, and a `ResonanceStoreAdapterException` from the adapter |
| `audit.changedEncodingProfile` | opening with `NoOpTextNormalizer` options: the direct exception (`IllegalArgumentException`), a `ResonanceStoreAdapterException` from the adapter, and the persisted profile left unchanged |
| `audit.removedVectorSegment` | the vector segment is deleted: the integrity audit reports `SEGMENT_MISSING`, and both opens fail (or a later recall fails); never a silent success |
| `audit.duplicateRemember` | remembering a chunk again returns its manifest atom and writes no atom record or vector; a second import into the root is refused |
| `audit.closedAdapter` | `COMPLETE` before `close()`, `UNAVAILABLE` with no results after it |
| `audit.alteredAtom` | an atom's content is changed on disk: readback fails for exactly that chunk, and the adapter answers `FAILED` for its probe |
| `audit.removedAtom` | an atom record is removed on disk: readback fails for exactly that chunk, and the integrity audit reports `VECTOR_WITHOUT_ATOM` |

## Outputs

| File | Content |
| :--- | :--- |
| `html-knowledge-persistence.json`, `.md` | checks, metadata and environment |
| `html-kc-v1-persistence-manifest.tsv` | `chunkId → atomId → opaqueReference` written by the seed JVM |
| `html-kc-v1-seed-recall.tsv`, `html-kc-v1-reopen-recall.tsv` | persisted profile and `probeHash`, plus every probe result: probe, path (`direct` or `neuron`), rank, mapped chunk id and hex score |

The metadata includes:
- `reopenMode`, plus `phase.{seed,reopen}.{pid,exit,nanos}`;
- the codec description and scope, `probes.{version,hash,count,topK,threshold}` and the ordering rules;
- `store.manifest.*` (version, encoder, normalizer, dimensions, vector format);
- `store.implementationVersion` (`unknown` when the Store classes come from directories, not jars);
- `store.checkoutCommit` and `neuron.commit` (`+dirty` when there are uncommitted changes), and `neuron.version`.

The `*.perf.*` values (open, first recall, warm-recall median) are single exploratory wall-clock samples. They never
affect a verdict.

## Observed behaviour (Store `main`, 2026-10-17)

- **Persistence:** all 33 chunks are persisted and read back with identical content in the restarted JVM. The 195
  direct results and the 195 adapter results (39 probes × 5) are bit-identical between the two processes.
- **Attribution:** every adapter result maps to an exact persisted atom by reference and by content-derived Signal,
  and equals the direct `MonadaMemory` results.
- **Default codecs:** the canonical query text is unrelated to the probes, giving 20 distinct result lists over 39
  probes. The top-1 chunk agrees with the probe codec for only 1 of 39 probes, against 33/33 readback recall@1 for the
  probe codec. `HashedSignalDecoder` Signals
  carry no text and had no collisions on this corpus; mapping them back to text requires the external corpus.
- **Failures** are visible as real errors:
  - a profile mismatch raises `IllegalArgumentException`;
  - a missing vector segment raises an `UncheckedIOException` caused by an `IOException`;
  - the adapter translates both into `ResonanceStoreAdapterException`;
  - a missing path silently becomes an empty store, which only the harness preflight catches.

## Limits

- No new Store API and no writable Neuron memory port. Store and Forge code is unchanged.
- The Store has no export, so readback relies on `resonate` with `topK` equal to the corpus size. The count comes from
  the Store's integrity auditor, which reads the files without writing.
- The probe codec is a lookup and says nothing about retrieval quality.
- `ResonanceMemoryResult` carries a Signal, a reference and a score, not text. Text is recovered only through the
  evaluation's reference-to-manifest mapping and the corpus, so the core does not "know" any HTML prose.
