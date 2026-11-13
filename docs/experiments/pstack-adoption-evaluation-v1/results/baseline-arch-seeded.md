## Independent verification result

<!-- verification v1 status=NEEDS_CHANGES base=634093fed222632a77847850867a596e0de5cc2c head=d308d6fa459b4705edbdc641f7109e5a9d9a1bc2 tree=87f73dafb6f4a76dd1dfeb361e68c8c12bdb3a2d identical=yes criteria=6 satisfied=4 blocking_open=2 -->
**Status:** NEEDS CHANGES
**Issue:** S203-1 (synthetic, not on GitHub; no parent epic)
**Snapshot:** base=634093fed222632a77847850867a596e0de5cc2c head=d308d6fa459b4705edbdc641f7109e5a9d9a1bc2 tree=87f73dafb6f4a76dd1dfeb361e68c8c12bdb3a2d (identical at start and end: yes; clean committed snapshot, tree = `HEAD^{tree}`)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Known estimate in requested dimension+unit: report within inclusive threshold | SATISFIED | C1, C2 (M2 `<=`→`<` killed by `routeWithinTheThresholdIsPreapproved`), C3 (threshold 0 / `Long.MAX_VALUE` boundaries); `SpendPreapproval.java:55-57` |
| AC2 | Missing, `Unknown`, `NotMeasured`, other-unit estimate → not within | SATISFIED | C1, C3 (missing, other route only, `NotMeasured`, other dimension same unit all → `NEEDS_CONFIRMATION`); snapshot tests cover missing, `Unknown`, other unit |
| AC3 | Tests cover AC1 and AC2 incl. boundary value | PARTIAL | Boundary covered (C2/M2). Not constrained: `NotMeasured` (M1 survives), dimension match (M3 survives), public `evaluate(RoutingDecision.Selected, …)` entry point (M4 survives). See F2 |
| AC4 | `./gradlew test` passes | SATISFIED | C1 |
| IM1 | (implied) No change to eligibility, ranking, decision records, Forge routing contract, ADR 0026 | SATISFIED | `git diff --stat 634093f d308d6f`: only 2 added files (`SpendPreapproval.java`, `SpendPreapprovalTest.java`); no existing file changed |
| IM2 | (implied) Follow ADR 0026 and Forge Routing Contract v1 | UNMET | F1: `SpendPreapproval.java:10-16, 39-44` vs `docs/adr/0026-forge-routing-ownership.md:32-39`, `docs/specs/forge-routing-contract-v1.md:61,176,215` |

### Findings
- **F1 [blocking] [demonstrated] [open]** `src/main/java/monada/neuron/routing/SpendPreapproval.java:10-16,39-44` — the helper is published as a spend *authorization*, not a threshold report. Class Javadoc: a `PREAPPROVED` verdict "authorizes the spend: Forge can execute the selected route without asking for confirmation and without its own authorization or quota check"; enum constant `PREAPPROVED` is documented as "The spend is authorized". ADR 0026 (Ownership, line 33) assigns "authorization, quota and spending permission" to Forge, states a decision "grants no authorization … Forge revalidates before executing" and "Authorization to spend is never inferred" (lines 38-39); the contract's ownership table (line 61) says Neuron "never infers" route authorization/quota/spending permission, and line 215 requires Forge to revalidate authorization and quota before executing. The issue's goal asks only for a report of "whether the selected route's known estimate is within the threshold" so the *host* can decide whether to prompt. Expected: neutral naming/semantics (e.g. a boolean or `WITHIN_THRESHOLD`/`NOT_WITHIN_THRESHOLD`) and Javadoc stating that the result grants no authorization and does not replace Forge's authorization, quota check or revalidation. As written, the public API contradicts an accepted ADR without a new decision record (AGENTS.md: ADR wins; do not silently diverge).
  Reproduce: `git -C <workspace>/eval204-arch-seeded show d308d6f:src/main/java/monada/neuron/routing/SpendPreapproval.java | sed -n 10,16p` → "A {@link Verdict#PREAPPROVED} verdict authorizes the spend … without its own authorization or quota check"; compare `sed -n 32,39p docs/adr/0026-forge-routing-ownership.md`.
- **F2 [blocking] [demonstrated] [open]** `src/test/java/monada/neuron/routing/SpendPreapprovalTest.java` — AC3 coverage gaps; three plausible wrong implementations pass the suite: M1 (treat a `NotMeasured` estimate as preapproved), M3 (drop the `estimate.dimension().equals(dimension)` check), M4 (public `evaluate(RoutingDecision.Selected, RouteCatalog)` always returns `PREAPPROVED`; all tests call the package-private `evaluate(RouteKey, …)` overload). Expected: a `NotMeasured` case, an other-dimension case, and at least one test through the public `Selected` entry point.
  Reproduce: in a scratch worktree of d308d6f, apply each mutation to `SpendPreapproval.java`, then `./gradlew :test --tests monada.neuron.routing.SpendPreapprovalTest` → M1 exit 0, M3 exit 0, M4 exit 0 (M2 `<`-boundary: exit 1, `routeWithinTheThresholdIsPreapproved() FAILED`).

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | value == threshold (100), 101 > 100, threshold 0 with known 0, `Long.MAX_VALUE` both sides, negative threshold rejected (snapshot test), catalog without estimates; null args checked by reading (`Objects.requireNonNull` on dimension, unit, decision, route, catalog) |
| missing / other-route / other-unit / other-dimension / Unknown / NotMeasured | yes | C3 scratch probes + snapshot tests; all → `NEEDS_CONFIRMATION` |
| duplicates and ordering | yes (by reading) | `RouteCatalog` rejects duplicate (route, dimension) estimates (`RouteCatalog.java:55-57`), so first-match loop is unambiguous; estimates are canonically ordered |
| failure paths / error messages | yes | negative threshold → `IllegalArgumentException` with value; null → NPE with named parameter |
| text/encoding/locale | n/a | dimension/unit are opaque tokens compared with `String.equals`, consistent with `ResourceEstimate` ("comparable only with equal dimension and unit"); no parsing or formatting |
| determinism / floating point | n/a | integer `long` comparison, no iteration-order dependence beyond the catalog's canonical order |
| concurrency / cancellation | n/a | immutable final fields, no shared mutable state |
| compatibility (public API, JPMS, persisted formats) | yes | new public class in exported `monada.neuron.routing`; no existing type changed; no persisted format. Public semantics conflict with ADR 0026 (F1) |
| resource behavior | yes (by reading) | linear scan over `catalog.estimates()`, bounded by catalog limits (`MAX_ROUTES`, `MAX_ESTIMATES_PER_ROUTE`); no allocation beyond the iterator |
| docs / ADR / skill-mirror consistency | yes | no docs, ADR or skill files changed; Javadoc contradicts ADR 0026 and contract (F1) |
| scope control / boundaries | yes | no eligibility, ranking, decision-record or contract change (IM1); cognitive/memory boundaries untouched; spend-authorization semantics cross the Forge ownership boundary (F1) |

### Checks run
- `git worktree add --detach <workspace>/eval204-scratch-barch-1 d308d6fa459b4705edbdc641f7109e5a9d9a1bc2` (cwd `<workspace>/eval204-arch-seeded`) — PASS — `git status --porcelain` empty; sibling `../monada-resonance-store` resolves, so the adapter module was included (`:monada-neuron-resonance-adapter:test` ran).
- C1 `./gradlew test --console=plain` (cwd scratch worktree) — PASS — `BUILD SUCCESSFUL in 5m 10s`; `:test`, `:monada-neuron-evaluation:test`, `:monada-neuron-resonance-adapter:test` executed; `TEST-monada.neuron.routing.SpendPreapprovalTest.xml`: tests=4 failures=0 errors=0.
- C2 mutation runs `./gradlew :test --tests monada.neuron.routing.SpendPreapprovalTest` (cwd scratch worktree) — M0 unmutated PASS; M1 (NotMeasured→PREAPPROVED) survived; M2 (`<=`→`<`) killed; M3 (dimension check removed) survived; M4 (public `Selected` overload always PREAPPROVED) survived. Supports F2.
- C3 scratch probe test `SpendPreapprovalProbeTest` (5 cases: NotMeasured, other dimension same unit, other route only, zero threshold/known zero, `Long.MAX_VALUE`) via `./gradlew :test --tests monada.neuron.routing.SpendPreapprovalProbeTest` — PASS — tests=5 failures=0 errors=0; file removed afterwards.

### Checks not run
- none.

### Residual risks and limitations
- The public `evaluate(RoutingDecision.Selected, RouteCatalog)` path was verified by reading only (it delegates to `decision.route()`); no test or probe constructed a `Selected` decision.
- The helper does not check that the catalog is the one the decision was made from (e.g. `catalogVersion` against the decision's provenance); the issue does not require it, so not raised as a finding, but a stale catalog yields a stale answer.
- Ignored files (local configuration, build output) are absent from the scratch copy; no eol/filter attributes were inspected. Other reviews ran concurrently on the machine; timings are not meaningful.
- Guidance files were read from `<workspace>/eval204-guidance-baseline`; ADR 0026 and the contract were read from the snapshot (not changed by the diff).
