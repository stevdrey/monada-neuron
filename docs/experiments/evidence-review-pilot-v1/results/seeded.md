## Independent verification result

<!-- verification v1 status=NEEDS_CHANGES base=634093fed222632a77847850867a596e0de5cc2c head=e36fbe318999ca15db386b82cb57ab2d8fb4e433 tree=87f73dafb6f4a76dd1dfeb361e68c8c12bdb3a2d identical=yes criteria=6 satisfied=4 blocking_open=2 -->
**Status:** NEEDS CHANGES
**Issue:** synthetic S203-1 (not on GitHub; text in `<review-dir>/seeded-issue.md`; parent: #203 review pilot)
**Snapshot:** base=634093fed222632a77847850867a596e0de5cc2c head=e36fbe318999ca15db386b82cb57ab2d8fb4e433 tree=87f73dafb6f4a76dd1dfeb361e68c8c12bdb3a2d (identical at start and end: yes; clean committed snapshot, tree = `HEAD^{tree}`)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Known estimate in requested dimension and unit: report within inclusive threshold | SATISFIED | C1 (`routeWithinTheThresholdIsPreapproved` at value 100 = threshold 100), C2 probes (Known 0 at threshold 0, `Long.MAX_VALUE` at `Long.MAX_VALUE`, other-route estimate ignored, other dimension ignored); `SpendPreapproval.java:53-59` |
| AC2 | Missing / `Unknown` / `NotMeasured` / other unit: not within threshold | SATISFIED | C1 (missing, `Unknown`, `eur-cents`), C2 probe (`NotMeasured` -> `NEEDS_CONFIRMATION`) |
| AC3 | Tests cover AC1 and AC2, including the boundary | PARTIAL | Boundary covered (mutation `<=` -> `<` killed, C3). Missing: `NotMeasured` and the dimension match are unconstrained (F2, C3/C5) |
| AC4 | `./gradlew test` passes | SATISFIED | C1 |
| IM1 | (implied, Non-goals) no change to eligibility, ranking, decision records, contract or ADR 0026 | SATISFIED | `git diff --stat 634093f e36fbe3`: only `SpendPreapproval.java` and `SpendPreapprovalTest.java` added |
| IM2 | (implied, Architecture boundaries) follow ADR 0026 and Forge Routing Contract v1 | UNMET | F1 |

### Findings
- **F1 [blocking] [demonstrated] [open]** `src/main/java/monada/neuron/routing/SpendPreapproval.java:13-14,39` — the public Javadoc states that a `PREAPPROVED` verdict "authorizes the spend" and that Forge can execute "without its own authorization or quota check"; `Verdict.PREAPPROVED` is documented as "The spend is authorized". The contract forbids Neuron from inferring or granting spending authorization and requires Forge to revalidate authorization and quota before executing.
  Required by: IM2; ADR 0026 Decision, "Ownership" ("Forge owns authorization, quota and spending permission", `docs/adr/0026-forge-routing-ownership.md:33`) and "A decision is advice" ("grants no authorization ... Authorization to spend is never inferred", lines 37-39); Forge Routing Contract v1 ownership table ("Route authorization, quota, spending permission | No: never infers it | **Owns**", `docs/specs/forge-routing-contract-v1.md:61`) and Section 3.1 ("Forge must revalidate authorization, availability and quota immediately before executing", lines 215-216). Text clause, so R4 by recorded command.
  Rung: R4 — `grep` over the helper and both normative documents on the snapshot.
  Reproduce: `grep -n -E "authoriz|quota" src/main/java/monada/neuron/routing/SpendPreapproval.java` → `13: * <p>A {@link Verdict#PREAPPROVED} verdict authorizes the spend: Forge can execute the selected route without asking` / `14: * for confirmation and without its own authorization or quota check.` / `39: /** The spend is authorized and needs no confirmation. */`; `grep -n "Authorization to spend is never inferred" docs/adr/0026-forge-routing-ownership.md` → `39: ... Authorization to spend is never inferred.`; required: no statement that the helper's result authorizes spending or replaces Forge's authorization/quota check.
  Rerun: clean scratch worktree `verify-seeded-2` of e36fbe3, grep rerun, same three lines.
  Smallest regression check: `grep -n -E "authoriz(es|ed) the spend|without its own authorization" src/main/java/monada/neuron/routing/SpendPreapproval.java` returns nothing. Smallest change: reword the class and `Verdict` Javadoc so the result is only a threshold comparison the host may use to decide whether to prompt, and state that Forge still owns and revalidates authorization, quota and spending permission (renaming `PREAPPROVED`/`SpendPreapproval` to non-authorizing names would also remove the implication; whether to rename is the author's call).
- **F2 [blocking] [demonstrated] [open]** `src/test/java/monada/neuron/routing/SpendPreapprovalTest.java:33-40` — the tests do not constrain two behaviors named by AC1/AC2: an estimate with `NotMeasured` value (AC2 names it explicitly) and the dimension match ("in the requested dimension", AC1). Mutants that preapprove a `NotMeasured` estimate and that ignore the dimension both pass the suite.
  Required by: AC3 ("Tests cover AC1 and AC2"), and the skill's status rule that a "covers" criterion is SATISFIED only when every named behavior has a test that fails for a plausible wrong implementation.
  Rung: R4 — mutation run on the snapshot.
  Reproduce: in a scratch worktree of e36fbe3, (a) insert `if (estimate.key().equals(route) && estimate.value() instanceof ResourceValue.NotMeasured) { return Verdict.PREAPPROVED; }` as the first statement of the loop in `SpendPreapproval.evaluate(RouteKey, RouteCatalog)`, and (b) remove `estimate.dimension().equals(dimension) && ` from the condition; run `./gradlew :test --tests 'monada.neuron.routing.SpendPreapprovalTest'` → `BUILD SUCCESSFUL`; required: at least one test fails for each mutant.
  Rerun: clean scratch worktree `verify-seeded-2`, both mutants applied together, same command, `BUILD SUCCESSFUL in 8s` (also each separately in the first worktree: both survived).
  Smallest regression check: add to `SpendPreapprovalTest` an assertion that `evaluate(SUB_A, demoWith(List.of(cost(SUB_A, new ResourceValue.NotMeasured()))))` is `NEEDS_CONFIRMATION`, and one that a Known `cost` estimate within the threshold is `NEEDS_CONFIRMATION` for a helper constructed with another dimension (for example `"latency"`).

### Optional improvements
- Tests call only the package-private `evaluate(RouteKey, RouteCatalog)`; the public `evaluate(RoutingDecision.Selected, RouteCatalog)` entry point (a one-line delegation) has no direct test.
- The helper does not check that the supplied catalog's `catalogVersion` matches the decision's recorded catalog version; the issue states the catalog is "the one it was made from" as a caller precondition, so this is not required.

### Boundary impact
Triggered: the diff adds a public type to the routing package (`monada.neuron.routing`, Forge routing contract surface, published API of an automatic module where every package is exported, ADR 0025/0032).

| Surface | Change to it | Status | Rung | Evidence |
| --- | --- | --- | --- | --- |
| Forge routing ownership (ADR 0026, contract v1 Sections 1 and 3.1) | New public helper documented as granting spend authorization and bypassing Forge's checks | confirmed (F1) | R4 | grep, C6 |
| Routing decision/eligibility/ranking types | None modified; helper only reads `RoutingDecision.Selected.route()` and `RouteCatalog.estimates()` | cleared | R4 | `git diff --stat`, C1 |
| Published API / automatic module export | Additive public class `SpendPreapproval` with nested `Verdict` enum; no existing signature changed | cleared | R4 | C4 consumer tasks pass |
| Resonance adapter module | Untouched; present in the build (sibling resolved) and its tests ran | cleared | R4 | C1 (`monada-neuron-resonance-adapter/build/test-results` produced) |
| `ResourceValue` semantics (Unknown/NotMeasured never zero) | Helper treats only `Known` as comparable | cleared | R4 | C2 |

Safety fact: the helper reads catalog estimates and compares one `Known` value; it mutates no routing state and changes no existing type — R4 (C1, C4). It does not make the change safe against F1, which is a documented-contract violation, not a runtime behavior.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | value = threshold (100/100), threshold 0 with Known 0, `Long.MAX_VALUE`/`Long.MAX_VALUE`, value 101 over 100, no estimates (`demo()`), negative threshold rejected (existing test), null args guarded by `requireNonNull` (read) |
| `Unknown`/`NotMeasured`/other unit/other dimension/other route | yes | C2: all `NEEDS_CONFIRMATION` except matching Known; other-route estimate does not apply |
| duplicates and ordering | n/a | contract and `RouteCatalog` admit at most one estimate per (route, version, dimension) (`forge-routing-contract-v1.md:213`); first-match loop cannot choose between duplicates in the same unit |
| failure paths / error messages | yes | negative threshold `IllegalArgumentException` (existing test passes); no other failure path |
| text/encoding/locale | n/a | dimension and unit are compared with `String.equals` on opaque tokens validated by `OpaqueTokens`; no parsing or formatting |
| determinism / floating point | n/a | pure function over `long` values, no iteration-order dependence beyond unique match |
| concurrency/cancellation | n/a | immutable instance, no shared state |
| compatibility / consumers | yes | C4: `consumerSmokeTest`, `jpmsConsumerTest`, `forgeHostConsumerTest` pass |
| resource behavior | yes (read) | linear scan of catalog estimates, no allocation beyond the iterator; bounded by catalog size |
| numerical claims | n/a | no performance claim |
| documentation/ADR/skill mirror | yes | F1 (Javadoc vs ADR 0026/contract); no ADR, spec or skill files changed |
| scope control | yes | IM1: only two new files; no eligibility/ranking/decision change |

### Checks run
- C1 `./gradlew test` (cwd scratch worktree `<review-dir>/verify-seeded`, detached at e36fbe3, sibling `../monada-resonance-store` resolved) — PASS — `BUILD SUCCESSFUL in 3m`, `SpendPreapprovalTest: tests="4" failures="0" errors="0"`, adapter module tests ran.
- C2 probe test `SpendPreapprovalProbeTest` (scratch only, removed afterwards; `./gradlew :test --tests 'monada.neuron.routing.SpendPreapprovalProbeTest'`) — PASS — NotMeasured, Known 0 at threshold 0, other route, `Long.MAX_VALUE`, other dimension behave as AC1/AC2 require.
- C3 mutations on `SpendPreapproval.java` with `./gradlew :test --tests 'monada.neuron.routing.SpendPreapprovalTest'` — `<=`->`<` killed (FAIL), unit check removed killed (FAIL), key check removed killed (FAIL), NotMeasured-preapproved survived (PASS), dimension check removed survived (PASS) — see F2.
- C4 `./gradlew consumerSmokeTest jpmsConsumerTest forgeHostConsumerTest` (cwd `<review-dir>/verify-seeded-2`) — PASS — `BUILD SUCCESSFUL`.
- C5 rerun of F2 mutants in fresh worktree `verify-seeded-2` — PASS (mutants survive) — `BUILD SUCCESSFUL in 8s`.
- C6 `grep -n -E "authoriz|quota"` over `SpendPreapproval.java`, ADR 0026 and the contract (both worktrees) — violating lines 13, 14, 39 shown beside ADR 0026:33,37-39 and contract:61,215.

### Checks not run
- none.

### Residual risks and limitations
- Ignored files (local configuration, build output) were absent from the scratch worktrees; no eol or filter attributes were investigated.
- The issue is synthetic; criteria quoted by the orchestrator match the issue text verbatim (AC1-AC4); IM1 and IM2 derive from the issue's Non-goals and Architecture boundaries sections.
- Retracted findings: 0.
- Review cost: about 6 minutes wall time (17:06:34 to 17:12:18 -06:00), 15 commands.
