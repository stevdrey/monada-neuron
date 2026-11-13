# PR Review Checklist

Use this checklist when validating a Monada Neuron change.

## Goal and Scope

- Does the implementation satisfy the stated goal?
- Is unrelated refactoring kept out of scope?
- Are non-goals respected?

## Cognitive Architecture

- Are Monad, Aeon, Node, and Signal responsibilities preserved?
- Does long-term memory remain owned by Monada Resonance Store?
- Are external models/frameworks/tools behind adapters rather than defining core architecture?
- Does the change require a new or updated ADR?

When the change crosses a cognitive or host boundary (Monad, Aeon, Node, or Signal contracts; an adapter; the Store
boundary; a host facade or routing contract; published API, module names, or a serialized format), also screen it with
the four questions below. Each one is tied to a named rule. A flag on its own is a suspected risk, and it blocks only
under the evidence rules in "Evidence Standard". Do not use the screen for general design taste or to propose rewrites.

- **Split ownership:** beyond long-term memory, does a second writer appear for state another component owns? Examples:
  an authorization, quota, or spending decision taken inside Neuron, which Forge owns (ADR 0026), or Neuron mutating
  routing state the caller owns (ADR 0030).
- **Information leakage:** beyond adapter types, does a protocol, policy, or format detail of an external system or
  adapter appear in more than one module (design principle 12, ADR 0018)?
- **Hand-synced list:** does the change add a list that must be kept in step with another one by hand (a mirror, a
  registry, an enum copy, a name table) without a check that fails when they drift, as `diff -r .agents/skills
  .windsurf/skills` does in CI?
- **Importable internals:** does the change rely on a package staying private? The published jars are automatic modules
  that export every package (ADR 0025, ADR 0032), so a consumer can import it. Adding a module descriptor to enforce
  privacy needs a new ADR.

The independent verifier also runs a boundary impact trace for these changes
(`.agents/skills/monada-neuron-independent-verification/SKILL.md`, "Boundary impact trace"): each touched surface is
confirmed or cleared with its evidence, the consumer task of every touched surface runs or is reported `NOT RUN`, and
the one fact the change is safe because of is stated with its evidence or marked unproven.

## Java 27

- Does the code use Java 27 idioms where they improve clarity or efficiency?
- Are records, sealed types, pattern matching, scoped values, or other modern features used only where semantically appropriate?
- Does every new `static` method have a genuine class-level reason?
- Are normal imports/simple names used instead of unnecessary fully qualified type names?
- If preview/incubator APIs are used, are flags, status, isolation, and migration/fallback documented?

## Algorithms and Data Structures

- Is the chosen structure appropriate for the actual access pattern and scale?
- Are time and memory complexity reasonable?
- Is iteration order deterministic where results depend on it?
- Is there avoidable boxing, pointer-heavy layout, copying, or allocation in a hot path?
- Would a primitive/contiguous representation materially improve a measured high-volume path?
- For graph work, does the representation match graph density and mutation rate?
- For top-K or bounded selection, does the implementation avoid unnecessary full sorting when scale makes it material?

## Performance

- Is there a baseline for performance claims?
- Was the algorithm/data layout considered before specialized hardware?
- Are measurements representative and reproducible?
- Are warm-up/JIT effects accounted for?
- Are memory/allocation effects considered, not only elapsed time?
- Does every stated speedup, latency, throughput, memory, recall, ranking, or evaluation gain have a claim evidence
  report (a run labeled exploratory and not used for a decision is exempt, as in SKILL.md "When it applies") (`.agents/skills/monada-neuron-evaluation-benchmarking/SKILL.md`, "Claim Evidence Report") with status
  `SUPPORTED`, or `NARROWED` with the change stating only the narrowed statement? A claim whose invalid-claim rules
  (`IC1` to `IC11`) leave nothing standing is `UNSUPPORTED` and must not ship as a measured result.
- For knowledge, recall, or ranking evaluations: are provenance, independent gold labels, abstention, holdouts, and
  leakage checked, and are retrieval, evidence use, and answer quality reported separately?

## SIMD / Native / GPU

When applicable:

- Is there a scalar/reference implementation?
- Does SIMD handle tails, small inputs, and numerical tolerance correctly?
- Are `MemorySegment`/`Arena` ownership and lifetimes clear?
- Are native access and platform requirements explicit?
- Does accelerator benchmarking include transfer, conversion, synchronization, and compilation/warm-up?
- Is hardware availability detected rather than assumed?
- Is fallback behavior defined?
- Are vendor-specific types kept outside core domain contracts?

## Concurrency

- Is the concurrency mechanism appropriate for blocking vs CPU-bound work?
- Is CPU parallelism bounded?
- Are cancellation/failure/lifecycle semantics clear?
- Can shared mutable state be reduced through ownership or immutability?
- Are races, false sharing, contention, or unsafe native-memory access possible?

## Correctness and Testing

- Are normal, boundary, invalid, and regression cases covered?
- Are optimized paths compared against a reference implementation?
- Are floating-point tolerances explicit where needed?
- Are tests deterministic?

## Documentation

- Are architecture/design docs updated when behavior or ownership changes?
- Is an ADR added for durable decisions, with a number from `scripts/check-adr-numbers.sh --next` that no open pull request also uses?
- Are benchmark commands and special runtime flags documented?

## Evidence Standard

Review the exact diff of a named commit or snapshot, not a description of it. Grade every finding by its proof, using
the evidence ladder in `.agents/skills/monada-neuron-independent-verification/SKILL.md` ("Proof-graded findings"):

- **Blocking defect:** a failing test, a reproducible command with its output, or a recorded command that shows a
  violated contract beside the clause it breaks (for a behavioral clause, a run that shows the behavior). It cites the
  criterion, ADR decision, spec clause, or rule that requires otherwise, and the smallest regression check.
- **Hypothesis:** a suspected risk that was reasoned but not reproduced. State what would prove it. It never blocks on its own. If it
  leaves a required criterion unverifiable, that criterion is `NOT VERIFIABLE` and the review is incomplete
  (`BLOCKED`), not a change request.
- **Optional improvement:** something no requirement demands. It never blocks.

Report impact only. A finding names the smallest change that satisfies the cited requirement, never a speculative
architecture rewrite. One reviewer, no fan-out: cost stays bounded to the touched surfaces.

## Merge Recommendation

Conclude with one of:

- Ready to merge.
- Ready after small fixes.
- Not ready: architectural/performance/correctness issue must be resolved.

List the smallest actionable fixes when changes are needed. Any open blocking defect, as defined in "Evidence Standard",
or unmet criterion rules out "Ready to merge": choose "Ready after small fixes" when each such item has a small, named
fix, and "Not ready" otherwise. A `NOT VERIFIABLE` required criterion also rules out "Ready to merge": report the
review as incomplete. Hypotheses and optional improvements never decide the recommendation; list them separately.
