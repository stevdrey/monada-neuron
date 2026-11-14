## Independent verification result

<!-- verification v1 status=NEEDS_CHANGES base=923c9e510b7893bdc85ecb64411e1a4d47c9dd10 head=e167cccb9741d7ca2ac382e937b85f3a9d21f26b tree=479979358de1c3148f75c282c9610d4dd9a60601 identical=yes criteria=4 satisfied=3 blocking_open=3 -->
**Status:** NEEDS CHANGES
**Issue:** S204-1 (synthetic, not on GitHub; no parent epic)
**Snapshot:** base=923c9e510b7893bdc85ecb64411e1a4d47c9dd10 head=e167cccb9741d7ca2ac382e937b85f3a9d21f26b tree=479979358de1c3148f75c282c9610d4dd9a60601 (identical at start and end: yes; tree equals HEAD^{tree}, working tree clean)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Records Scalar vs Vector at 1,000,000 pairs with command and environment | SATISFIED | `docs/benchmarks/baseline-methodology.md:236-250` (section, command block, environment sentence, result table). Quality of the measurement is judged under AC2. |
| AC2 | Every performance statement supported by its measurement under the benchmarking rules | UNMET | F1, F2, F3; C1, C2 |
| AC3 | Committed PR #35 numbers unchanged | SATISFIED | `git diff 923c9e5 e167ccc` touches only lines 236-257 (added) and line 722 (sentence extended; "6.2x to 6.7x" kept); PR #35 table at `baseline-methodology.md:207-219` byte-identical to base. |
| AC4 | No production code, benchmark code, or default changes | SATISFIED | `git diff --stat 923c9e5 e167ccc` → only `docs/benchmarks/baseline-methodology.md` (23 insertions, 1 deletion). |

Criteria AC1-AC4 checked against the issue file; they match the request verbatim. No implied rows added (non-goals are already AC3/AC4).

### Findings
- **F1 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:242-250` and `:722` — the recorded measurement uses `-f 1 -wi 0 -i 1 -r 1s`: no warm-up, one measurement iteration, one fork, so JMH reports no error estimate. This violates the evaluation-benchmarking skill (forks, warm-up, measurement iterations must be meaningful and documented) and is not the PR #35 method (`-f 3 -wi 2 -i 3`) it claims to "extend", so the 8.9x is not comparable with the 6.2x-6.7x rows it is placed next to (line 722 adds it to the ADR 0013 retention evidence). The recorded command does not reproduce the recorded result.
  Reproduce: in a scratch worktree of e167ccc (sibling `../monada-resonance-store` present), `./gradlew -q :monada-neuron-evaluation:jmh -PjmhArgs="-bm avgt -f 1 -wi 0 -i 1 -r 1s -p batchSize=1000000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"` → `benchmarkScalarBatchSoA 1000000 avgt 169059070.286 ns/op`, `benchmarkVectorBatchSoA 1000000 avgt 2851650490.000 ns/op` (Vector about 17x slower than Scalar: with zero warm-up the single iteration measures uncompiled code).
- **F2 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:250,252,722` — the 8.9x figure is not reproduced by a warmed run either. With warm-up the ratio observed here is about 4.0x, with very wide error bars on this loaded machine, so neither 8.9x nor its stability is established; a three-fork run with the PR #35 settings (and ideally `-prof gc`) is needed before any ratio is stated.
  Reproduce: same worktree, `./gradlew -q :monada-neuron-evaluation:jmh -PjmhArgs="-bm avgt -f 1 -wi 2 -i 3 -r 1s -p batchSize=1000000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"` → `Scalar 75593595.804 ± 67142052.834 ns/op`, `Vector 18795001.261 ± 39782235.340 ns/op`.
- **F3 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:252-256` — performance statements with no measurement in the change: (a) "it grows with batch size" (one new point, measured by a different method; PR #35 rows are not monotonic, e.g. 1,000 → 6.2x, 10,000 → 6.7x, 100,000 → 6.6x); (b) the mechanism "four 64-bit lanes ... score four pairs per instruction" (no profiling, generated-code, or counter evidence); (c) "resonance scoring is the dominant per-signal cost" (no profile); (d) "enabling the Vector backend makes a cognitive cycle roughly 8x faster" (an end-to-end claim derived from a microbenchmark, which the benchmarking skill forbids; no system benchmark was run, and Vector is already selected by `AdaptiveBatchResonanceEvaluator` above the crossover, so "enabling" it is not a measured configuration change). Demonstrated by inspection of the full diff: it adds no measurement for any of these.
  Reproduce: `git -C <workspace>/eval204-perf-seeded diff 923c9e510b7893bdc85ecb64411e1a4d47c9dd10 HEAD` → only the single-row table at line 250 is measured data.
- **F4 [non-blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:238-240` — the environment omits JVM flags/heap settings, JMH JSON artifact path, allocation metric (`-prof gc`, used by the PR #35 study the section extends), and the run's machine load; the stated JDK "Zulu 27+35" is not checkable from the record (this machine's toolchain reports `OpenJDK 64-Bit Server VM, 27+35`).
  Reproduce: read lines 236-250; compare with the PR #35 section at lines 162-176.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | `-p batchSize=1000000` outside the `@Param` list of `ScalarResonanceBenchmark.java:39` accepted by JMH (C1, C2 ran) |
| failure paths / reproducibility | yes | recorded command rerun as written (C2) and with warm-up (C1); result not reproduced |
| text/encoding/locale | n/a | no parser or text input changed |
| determinism / floating-point tolerance | n/a | no numeric code changed; equivalence claim at line 722 is pre-existing |
| concurrency / cancellation | n/a | documentation-only change |
| compatibility (API, JPMS, formats) | n/a | AC4: no code changed (diff stat) |
| resource behavior | yes | no allocation measurement recorded for the new size (F4) |
| documentation/ADR/skill-mirror consistency | yes | new numbers propagated into ADR 0013 retention summary (line 722; F1); no skill files changed, so no mirror diff needed |
| scope control | yes | diff limited to one doc file; PR #35 numbers unchanged (AC3, AC4) |

### Checks run
- `git rev-parse HEAD HEAD^{tree}` plus throwaway-index fingerprint (cwd `<workspace>/eval204-perf-seeded`) — PASS — head e167ccc..., tree 4799793... at start and end; `git status --porcelain` empty.
- `git diff --stat 923c9e510b7893bdc85ecb64411e1a4d47c9dd10 HEAD` (same cwd) — PASS — one file, `docs/benchmarks/baseline-methodology.md`, 23+/1-.
- C1: `./gradlew -q :monada-neuron-evaluation:jmh -PjmhArgs="-bm avgt -f 1 -wi 2 -i 3 -r 1s -p batchSize=1000000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"` (cwd scratch worktree `<workspace>/eval204-scratch-bperf-1` at e167ccc, removed afterwards) — FAIL — does not reproduce 8.9x: Scalar 75,593,595.8 ± 67,142,052.8 ns/op, Vector 18,795,001.3 ± 39,782,235.3 ns/op (~4.0x). JDK 27+35.
- C2: `./gradlew -q :monada-neuron-evaluation:jmh -PjmhArgs="-bm avgt -f 1 -wi 0 -i 1 -r 1s -p batchSize=1000000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"` (same scratch cwd) — FAIL — the recorded command gives Scalar 169,059,070.3 ns/op, Vector 2,851,650,490.0 ns/op (Vector slower).

### Checks not run
- `./gradlew test` — NOT RUN — not required: documentation-only change with no code, build, or test change (AC4); affects none.
- Three-fork PR #35-method run at 1,000,000 pairs with `-prof gc` — NOT RUN — not required to decide the result (F1-F3 are already demonstrated) and would add noise-dominated numbers on a shared machine; affects AC2 only as a remedy, not as evidence.

### Residual risks and limitations
- Machine was shared with concurrent reviews; C1/C2 absolute times are noisy (C1 error bars exceed the means). They are used only to show the recorded figure is not reproducible and the method is unsound, not as replacement numbers.
- Reviewer hardware (Ryzen 7 7730U, AVX2) matches the stated CPU; JDK vendor could not be confirmed.
- Ignored files and build output were absent from the scratch worktree; the build resolved `../monada-resonance-store` as in the author's checkout.
