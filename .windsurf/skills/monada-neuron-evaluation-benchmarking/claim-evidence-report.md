# Claim Evidence Report Template

Copy this template into the document, Issue, or PR that states the claim. The rules, verdicts, and invalid-claim ids
(`IC1` to `IC11`) are defined in `SKILL.md`, section "Claim Evidence Report". Fill every field, or write
`pending: <reason>`. Delete the hints in parentheses.

For an environment block, run `.agents/skills/monada-neuron-evaluation-benchmarking/claim-environment.sh` right
before and right after the measured run, and paste both outputs. Pass `--java <path>` with the JDK the benchmark
actually runs on: for a Gradle task that is the toolchain JDK (`./gradlew -q javaToolchains` lists it), which may
differ from `JAVA_HOME` or the `java` on `PATH`.

```markdown
## Claim evidence: <short name>

| Field | Value |
| :--- | :--- |
| Claim | (the sentence that will ship, written before the run: metric, direction, size, scope) |
| Source | (Issue, PR, ADR, or document that states it, with revision) |
| Target metric and decision | (metric, unit, and what the result changes) |
| Baseline | (class or configuration, revision, flags, tuned as it ships) |
| Variant | (class or configuration, revision, flags, tuned as it ships) |
| Workload | (benchmark or fixture, revision or hash, seed, sizes, input distribution, paths not reached) |
| Commands | (exact commands and output paths) |
| Environment | (output of claim-environment.sh, before and after) |
| Method | (warm-up, iterations, forks or alternating runs, mode, profilers) |
| Semantic check | (reference, tolerance, sizes and ranges, command or test that shows it) |
| Work performed | (counter, checksum, or terminal status that shows the timed region did the work) |
| Bound check | (arithmetic limit on the gain and whether the result is within it) |
| Limiter | (measured cause, or `not required` for a claim that does not explain or adopt) |
| Share of end to end | (share of the path users run, or `end to end` when the measurement is) |
| Limits | (sizes, hardware, input ranges, JDK not covered) |

### Results

| Metric | Size | Baseline | Variant | Gain (direction) | Result |
| :--- | ---: | ---: | ---: | ---: | :--- |
| (metric, unit) | (n) | (value ± error or range) | (value ± error or range) | (baseline/variant for lower-is-better such as latency; variant/baseline for higher-is-better such as throughput; variant minus baseline for bounded scores such as recall; state which) | (better, worse, no measurable difference, inconclusive) |

### Knowledge, recall, and ranking evaluations only

| Field | Value |
| :--- | :--- |
| Provenance | (corpus and query set revisions or hashes, gold label authorship and timing) |
| Gold labels | (independent of system output, read only by the scorer: where that is enforced) |
| Abstention | (no-answer queries scored, false-positive rate) |
| Holdouts | (split used for tuning versus split reported) |
| Leakage | (check or test showing no gold, holdout, or near-duplicate reaches the system under test) |
| Outcome separation | (retrieval, evidence use, answer quality, reported separately) |
| Protocol verdict rule | (registered protocol and how its rule decided the verdict, or `none`) |

### Verdict

- Status: (SUPPORTED, NARROWED, UNSUPPORTED, or PENDING)
- Invalid-claim rules that fired: (ids, or `none`)
- Statement to ship: (the claim as supported, or `none`)
- Pending fields and what would resolve them: (list, or `none`)
```
