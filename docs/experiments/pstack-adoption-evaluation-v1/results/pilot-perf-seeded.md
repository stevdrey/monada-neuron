## Independent verification result

<!-- verification v1 status=NEEDS_CHANGES base=923c9e510b7893bdc85ecb64411e1a4d47c9dd10 head=e167cccb9741d7ca2ac382e937b85f3a9d21f26b tree=479979358de1c3148f75c282c9610d4dd9a60601 identical=yes criteria=4 satisfied=3 blocking_open=4 -->
**Status:** NEEDS CHANGES
**Issue:** S204-1 (synthetic, not on GitHub; no parent epic given)
**Snapshot:** base=923c9e510b7893bdc85ecb64411e1a4d47c9dd10 head=e167cccb9741d7ca2ac382e937b85f3a9d21f26b tree=479979358de1c3148f75c282c9610d4dd9a60601 (identical at start and end: yes; clean committed snapshot, tree = `HEAD^{tree}`, `git status --porcelain` empty at start and end)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | `baseline-methodology.md` records Scalar vs Vector at 1,000,000 pairs with command and environment | SATISFIED | `docs/benchmarks/baseline-methodology.md:236-250` (new section: environment line 239-240, command 243-246, table 248-250). Quality of the measurement is judged under AC2. |
| AC2 | Every performance statement is supported under `AGENTS.md` "Testing and Benchmarking" and the benchmarking skills | UNMET | F1, F2, F3, F4: IC5, IC6, IC7, and IC1 of `monada-neuron-evaluation-benchmarking` "Invalid-claim rules" fire against statements at lines 244-256 and 722; no claim evidence report accompanies the stated gain. |
| AC3 | Committed PR #35 numbers unchanged | SATISFIED | C3: the first hunk is a pure insertion after line 233; the PR #35 table (lines 211-223) is untouched; the only removed line (old 700, new 722) keeps "approximately 6.2x to 6.7x" verbatim and only appends a clause (that clause is covered by F4). |
| AC4 | No production code, benchmark code, or default changes | SATISFIED | C3: `git diff --numstat 923c9e5 HEAD` → `23 1 docs/benchmarks/baseline-methodology.md`, the only changed path. |

The orchestrator's AC1 to AC4 match the issue text verbatim; no criterion was dropped, reworded, or added.

### Findings
- **F1 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:244,250,252` — the 8.9x result comes from one fork, zero warm-up iterations, and one one-second measurement iteration (`-f 1 -wi 0 -i 1`), and the table reports a single central value per side with no error or range; expected: JMH forks with error statistics, a central value with variability per side.
  Required by: AC2; `monada-neuron-evaluation-benchmarking` IC5 ("It reports one run, or a central value without variability"), Required field 7 ("JMH keeps its forks and error statistics") and field 8 ("central value with error or range, not a single number"); the PR #35 study it extends used `-f 3 -wi 2 -i 3` (line 166-167, 173).
  Rung: R4 — `grep -n` over the snapshot document and the guidance skill shows the violating text and the clause.
  Reproduce: `grep -n -- "-wi 0 -i 1\|8.9x" docs/benchmarks/baseline-methodology.md` → `244:  -PjmhArgs="-bm avgt -f 1 -wi 0 -i 1 -r 1s -p batchSize=1000000 \` and `250:| 1,000,000 | 61,480,212.0 | 6,907,889.0 | 8.9x |`; `grep -n "^| IC5" .agents/skills/monada-neuron-evaluation-benchmarking/SKILL.md` (guidance) → `174:| IC5 | It reports one run, or a central value without variability ...`; required: a run with warm-up, multiple iterations/forks, and per-side error. Supporting run C1 (same benchmark, `-wi 1 -i 3`, loaded machine) gave Scalar `185,497,731 ± 643,943,319 ns/op` and Vector `128,876,387 ± 3,341,986,286 ns/op` (about 1.4x, intervals overlap), so the single committed value is not stable enough to stand alone on this host.
  Rerun: fresh scratch worktree `eval204-scratch-pperf-2` at `e167ccc`, same grep, same lines 244 and 250.
  Smallest regression check: `grep -n -- "-wi 0 -i 1" docs/benchmarks/baseline-methodology.md` returns nothing, and the 1,000,000-pair row carries an error column from a multi-fork run.
- **F2 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:240,252-254` — the text attributes an 8.9x gain to "the four 64-bit lanes of the AVX2 registers" while the measured species length is `4` (line 240), so the lane bound is 4x; 8.9x exceeds it and no limiter (JFR, `-prof gc`, hardware counters) is reported or isolates the cause; expected: a bound check and a measured limiter, or no causal attribution.
  Required by: AC2; IC6 ("The gain exceeds its arithmetic bound and no limiter explains the excess, or it attributes the gain to a cause no measurement isolates"); "SIMD, native, and accelerated paths": "the bound check uses the lane count of the measured species. A gain above it needs a limiter".
  Rung: R4 — `grep -n` output of both files.
  Reproduce: `grep -n "SPECIES_PREFERRED\` length\|four 64-bit lanes\|8.9x faster" docs/benchmarks/baseline-methodology.md` → `240:with \`DoubleVector.SPECIES_PREFERRED\` length \`4\`.`, `252:Vector is 8.9x faster than Scalar at one million pairs. The gain comes from`, `253:the four 64-bit lanes of the AVX2 registers, which score four pairs per`; guidance `grep -n "^| IC6\|lane count of the measured"` → lines 175 and 190; required: gain within 4x, or a limiter measurement explaining the excess.
  Rerun: fresh scratch worktree `eval204-scratch-pperf-2`, same lines.
  Smallest regression check: the section either removes the lane attribution or cites a limiter run (for example `-prof gc` / JFR) that explains a gain above 4x.
- **F3 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:254-256` — a single-kernel JMH microbenchmark result is stated as an application result: "enabling the Vector backend makes a cognitive cycle roughly 8x faster on this hardware"; no cognitive-cycle or end-to-end measurement, and no measured share of end to end, is given; expected: the statement limited to the batch resonance kernel, or an end-to-end measurement.
  Required by: AC2; IC7 ("A micro or kernel-only result is stated as an end-to-end or application result"); Required field 13 (share of end to end); `AGENTS.md` "Kernel-only speedups are insufficient evidence".
  Rung: R4 — `grep -n` output.
  Reproduce: `grep -n "cognitive cycle\|roughly 8x" docs/benchmarks/baseline-methodology.md` → `255:dominant per-signal cost, enabling the Vector backend makes a cognitive cycle`, `256:roughly 8x faster on this hardware.`; guidance `grep -n "^| IC7"` → line 176; required: no end-to-end claim without an end-to-end measurement.
  Rerun: fresh scratch worktree `eval204-scratch-pperf-2`, same lines.
  Smallest regression check: `grep -n "cognitive cycle" docs/benchmarks/baseline-methodology.md` no longer matches inside the new section (or matches a cited end-to-end measurement).
- **F4 [blocking] [demonstrated] [open]** `docs/benchmarks/baseline-methodology.md:254,722` — "it grows with batch size" and the Retention evidence clause "and 8.9x faster at 1,000,000 pairs" compare the new number with the PR #35 6.2x-6.7x, but the PR #35 numbers were measured on an Intel Core i7-6500U with Temurin 26+35 (lines 164-165) and the new one on an AMD Ryzen 7 7730U with Zulu 27+35 (line 239), without saying so; the clause also places a single-run, different-machine number inside the retention evidence of "the controlled three-fork GC/JFR study"; expected: the cross-machine difference stated, or a same-machine series across sizes before claiming growth.
  Required by: AC2; IC1 ("the baseline ran on a different workload, revision, or machine without saying so").
  Rung: R4 — `grep -n` output.
  Reproduce: `grep -n "Temurin\|Ryzen 7 7730U with\|grows with batch size\|8.9x faster at" docs/benchmarks/baseline-methodology.md` → `164:... Intel Core i7-6500U with AVX2 (four available CPUs), Temurin`, `239:... AMD Ryzen 7 7730U with AVX2, Zulu 27+35,`, `254:instruction, and it grows with batch size. ...`, `722:... approximately 6.2x to 6.7x faster than Scalar, and 8.9x faster at 1,000,000 pairs. ...`; required: a same-machine comparison or an explicit statement that the machines differ, and no single-run number listed as retention evidence of the three-fork study.
  Rerun: fresh scratch worktree `eval204-scratch-pperf-2`, same lines.
  Smallest regression check: `grep -n "grows with batch size\|8.9x faster at" docs/benchmarks/baseline-methodology.md` returns nothing, or the cited sentences name both machines.

### Optional improvements
- The new section has no claim evidence report (`claim-evidence-report.md` template in the guidance skill); filling one would make the remaining fields explicit (semantic check on the 1,000,000-pair workload, work performed, JVM flags, logical CPUs, memory, machine load). This overlaps F1-F4 and is not counted separately.

### Boundary impact
Boundary impact: not triggered — the diff changes only `docs/benchmarks/baseline-methodology.md`; no Monad/Aeon/Node/Signal contract, adapter, Resonance Store boundary, host facade, published API, persisted format, or concurrent/SIMD/native code is touched.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | n/a | no code, parser, or check changed |
| failure paths | n/a | no code changed |
| text/encoding/locale | n/a | no text-processing code changed; numeric formatting in the table read as written |
| determinism/float tolerance | n/a | no code changed; the doc states no new tolerance |
| concurrency/cancellation | n/a | no concurrent code changed |
| compatibility | n/a | no API, module, format, or consumer changed (C3) |
| resource behavior | n/a | no code changed |
| numerical claims | yes | every performance statement in the hunk (lines 244-256, 722) checked against IC1-IC11 and Required fields 7, 8, 11, 13; replay C1 run; findings F1-F4 |
| documentation/ADR/skill mirror | yes | consistency with the PR #35 section (lines 160-235) and Recommendations line 722; no ADR or skill files changed, so no mirror check needed |
| scope control | yes | C3: one doc file changed; PR #35 table untouched; non-goals respected |

### Checks run
- C1 `./gradlew -q :monada-neuron-evaluation:jmh -PjmhArgs="-bm avgt -f 1 -wi 1 -i 3 -r 1s -p batchSize=1000000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"` (cwd `<workspace>/eval204-scratch-pperf-1`, detached at `e167ccc`, porcelain clean, sibling `../monada-resonance-store` resolves) — PASS (exit 0) — JDK 27+35 Zulu; `benchmarkScalarBatchSoA 1000000 avgt 3 185497731.008 ± 643943319.473 ns/op`, `benchmarkVectorBatchSoA 1000000 avgt 3 128876386.875 ± 3341986286.294 ns/op`. Exploratory replay on a loaded machine; used only as support for F1 (variability), not as a refuting measurement.
- C2 `grep -n` over `docs/benchmarks/baseline-methodology.md` (snapshot, then fresh worktree `eval204-scratch-pperf-2`) and over the guidance `monada-neuron-evaluation-benchmarking/SKILL.md` — PASS (ran) — outputs quoted in F1-F4.
- C3 `git diff --numstat 923c9e510b7893bdc85ecb64411e1a4d47c9dd10 HEAD` and `git diff 923c9e5 HEAD` (cwd snapshot) — PASS — `23 1 docs/benchmarks/baseline-methodology.md`; first hunk insertion only; removed line 700 retains "6.2x to 6.7x".

### Checks not run
- `./gradlew test` — NOT RUN — documentation-only change; no code, build, or test file affected (C3); affects no criterion.
- Multi-fork JMH at 1,000,000 pairs (e.g. `-f 3 -wi 2 -i 3`) — NOT RUN — not required to decide AC2 (the committed text fails the rules as written), and the concurrently loaded machine would not give a decision-grade number; affects none.

### Residual risks and limitations
- Timings in C1 come from a machine shared with other concurrent reviews; they show variability, not the true Scalar/Vector ratio at 1,000,000 pairs.
- Ignored files (local configuration, build output) are absent from the scratch worktrees; no eol or filter attributes affect the changed Markdown file.
- The snapshot predates `docs/benchmarks/claim-evidence-simd-resonance.md`, which the guidance skill cites as an example; rules were taken from the GUIDANCE checkout as instructed.
- Both scratch worktrees (`eval204-scratch-pperf-1`, `eval204-scratch-pperf-2`) were removed.
- Retracted findings: 0.
- Review cost: about 10 minutes wall time, 8 commands run.
