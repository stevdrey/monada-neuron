# Forge-like Host Fixture v1

**Issue**: #102 · **Location**: `consumer-fixture/forge-host/` · **Task**: `./gradlew forgeHostConsumerTest`

This document describes the external consumer that proves the review to routing boundary of the
[Forge Integration Readiness Contract v1](forge-integration-readiness-v1.md): a Java 27 host that begins with reviewed
task metadata and ends with advisory routing output and a host disposition, with no authorized worker and no side effect.
It is a proof that the boundary can be built against the published core alone. It is not a Forge integration, which has
not shipped (Section 7), and its guards are one possible host policy, not an authorization implementation or a security
proof for Forge (Section 5).

## 1. What the fixture proves

| Claim | Where it is checked |
| --- | --- |
| The host compiles and runs against the published artifact only, as a named module (`requires monada.neuron`) and on the plain classpath, with the same source | `forge-host/build.gradle.kts` `printResolution` asserts that both classpaths resolve exactly `monada.neuron:monada-neuron`; `ForgeHostMain.checkModuleMode` asserts the module form and that the adapter, the Store and `jdk.incubator.vector` are absent |
| Valid reviewed intake produces advice and nothing else | `reviewedIntake`: a `READY` draft gives `ADVISED(Selected(local-small/1, HOST_PRIORITY))`, a feature assessment, a review with `RECHECK_BEFORE_EXECUTION`, a receipt that decodes to the same decision; no disposition is recorded and the side effect boundary counts zero |
| Unknown stays unknown and changes no eligibility | `unknownFeaturesStayUnknown`: every unpicked and unmeasured field is `Unknown`; a nine tag list, a repeated tag and a negative size are `Unknown`, never truncated; the advice is the same route on configuration alone |
| An unset scope, a withdrawn confirmation and a missing stage pick build no request | `unsetScopeIsNotReady`: `NOT_READY` names the prerequisites in a fixed order and the host's history stays empty |
| A scope edit invalidates the review and the advice | `scopeEditInvalidatesReview`: the draft returns to `REVIEWABLE`, the guard fails checks 1 and 3, a disposition is refused, the re request keeps the task and execution ids, mints a new attempt id and takes the next ordinal, and the stale decision never receives a disposition |
| Every other captured input is guarded | `otherInputsInvalidateToo`: a workspace replacement (epoch), a new source revision, a text edit, a new context collection and a catalog change each fail exactly one check |
| The host does not call without a catalog, with the integration off, when cancelled, or on a version mismatch | `hostDoesNotCallWithoutCatalogIntegrationOrWhenCancelled`: each is `UNAVAILABLE(reason)` with an empty history; the version probe's detail is in the host log only |
| Selected, abstained, alternative and deferred dispositions all run with no side effect | `dispositionsHaveNoSideEffect`: `Followed`, `AlternativeProposed` with each `AlternativeStanding`, `Deferred` and `Declined` over `Selected`, `Abstain` and `NoEligibleRoute` advice; the boundary counts zero on every host |
| Routing never accesses paths or treats task text as permission | `taskTextAndPathsNeverEnterNeuron`: instruction like text and scope entries change no feature and no advice, only the constraints digest, and no token of either appears in the request, the decision, the review, the assessment or the receipt bytes; the scope id is not a path |
| Two hosts over the same draft advise identically | `main`: the receipts of two fresh hosts have the same digest; the fixture uses fixed ids, counters and no clock |

## 2. Layout and commands

```text
consumer-fixture/forge-host/
  settings.gradle.kts          FAIL_ON_PROJECT_REPOS; the core from -PneuronRepo (build/consumer-repo) only,
                               plus Maven Central filtered to the JUnit groups for the classpath-host tests
  build.gradle.kts             one declared coordinate, printResolution, verifyConsumer
  module-host/                 application, mainModule monada.neuron.consumer.forge, run with "module"
    src/main/java/module-info.java
    src/main/java/consumer/forge/*.java   the host types, ForgeHostMain, and SampleInputs shared with the tests
  classpath-host/              the module-host sources minus module-info.java, run with "classpath"
    src/test/java/consumer/forge/*Test.java   JUnit 5 unit tests over the host types, run by verifyConsumer
```

Run from the repository root, never from inside the folder:

```bash
./gradlew forgeHostConsumerTest     # publishes the core to build/consumer-repo, runs both hosts
./gradlew consumerSmokeTest jpmsConsumerTest   # the issue #68 fixtures, unchanged
```

CI runs `forgeHostConsumerTest` in the `verify` job, the core only job that has no Store checkout, after
`jpmsConsumerTest` (`.github/workflows/ci.yml`). Each host prints `[forge-host:<mode>] all checks passed` and every
check throws on failure, so the Gradle `run` task fails; no output is grepped.

## 3. The flow

| Step | Type (package `consumer.forge`) | Contract section |
| --- | --- | --- |
| Reviewed task metadata | `TaskDraft` with nested `Workspace`, `Specification`, `Scope`, `Review`, `Context`, `State`, `Missing`; imports nothing from Neuron | 4.3, Forge security invariants 1 and 3 |
| Version probe at host start | `VersionProbe.mismatch(forgeRoutingV1())`, five reflective reads compared with literals | 5.2 |
| Transformation boundary | `ForgeRoutingAdapter.features`, `request`, `advise`; `Fingerprints.of` | 4.1, 4.2, 4.4, 3.1 |
| Readiness outcome | `Readiness.NotReady(prerequisites)`, `Unavailable(reason)`, `Advised(advice)`; `ForgeHost.request` checks in the Section 5.3 order: prerequisites, integration off, no catalog, version mismatch, cancelled | 5.1, 5.3 |
| What the host keeps per answer | `Advice(request, decision, assessment, review, receipt, capturedEpoch)` | 5.4, 5.5, receipts |
| Staleness rule | `HostGuard.check` with checks 1 to 4 as `Check` constants, verdict `Fresh` or `Stale(failed)` | 6.1 |
| Re request | `ForgeHost.reRequest`: same task and execution ids, new attempt id, next ordinal, inputs rebuilt | 6.2 |
| Disposition | `ForgeHost.dispose(advice, current, Choice)` returning `DispositionOutcome.Recorded`, `Refused(Refusal)` or `Rejected(DispositionRejection)` | never permission list, 2.2 |
| Where a side effect would go | `SideEffectBoundary`, every method refuses and counts; the fixture requires zero | Forge security invariant 2 |

The stub types are the fixture's own. They are not Forge's `TaskDraft` and they are not a proposal for Forge's API;
when Forge publishes a host API, its adapter module (contract Section 8.3) replaces `TaskDraft` here with the real type
and keeps the rest of the flow.

## 4. Bounds

The fixture exercises only existing inputs within the bounds of the readiness contract's Section 9.1: two routes, three
stage kinds, at most one tag list of nine entries (reported as `Unknown`), fingerprints of 64 hexadecimal characters, and
one request per attempt. It adds no numeric limit of its own and no type of the contract's own.

## 5. Host policies that are this fixture's, not Neuron's and not Forge's

- **Follow and redirect wait for the required features.** When the feature assessment reports a dimension the
  host's preview requires as missing, the host refuses to record `Followed` or `AlternativeProposed` and allows
  `Deferred` and `Declined`. The readiness contract leaves this to the host (Section 5.4); the fixture chooses the
  conservative side.
- **The confirmed characteristics are part of the constraints digest.** The contract names the confirmed
  specification, the scope and the policy in force (Section 4.2). The fixture also folds the person's picks (stage,
  category, tests, security) into that digest, so a later pick makes earlier advice stale even after re confirmation.
- **A later disposition for the same advice replaces the earlier one.** The host keeps one disposition per
  `DecisionRef`; a real host may keep a history.
- **The side effect boundary is a counter.** It demonstrates that no step of the flow reaches the place where a host
  would act. It is not a sandbox and proves nothing about Forge's execution boundary.

## 6. What the fixture does not do

No Forge jar, no JavaFX, no Store, no adapter module, no real file system read or write, no agent invocation, no model
or network call, no checkpoint or export, and no assertion that a confirmed draft, a receipt or a disposition grants
anything. The issue #68 embedded host fixture keeps the execution, evidence and learning loop; this fixture does not
duplicate it. The restart subcase over a checkpoint (issues #100 and #101) is out of scope for the core only scenario.

## 7. Future Forge implementation work (not shipped)

Nothing below exists in Forge. The list is what Forge would build to adopt the boundary this fixture proves; each
item is Forge's decision and Forge's code.

1. A host adapter module that `requires monada.neuron` and keeps Forge core Neuron free (contract Section 8.3),
   replacing this fixture's `TaskDraft` stub with Forge's reviewed draft and workspace types.
2. A host minted opaque `scopeId` per workspace registration and an epoch that bumps on replacement or clearing, never
   derived from the path.
3. The three fingerprints computed by Forge over its own revision, gathered context and confirmed specification,
   scope and policy.
4. The fixed stage pick list, the category list, the tests and security toggles in the review UI, and a static preview
   catalog whose routes declare every stage kind and a known locality (contract Section 3.1).
5. The host guard placed where advice becomes actionable in the UI, with the re request path and the "unverified
   preview, recheck before execution" label.
6. A disposition record in Forge's own storage keyed by `DecisionRef`, and, from Phase 3, the side effect boundary
   with its own authorization, availability and quota revalidation (contract Section 6.1, check 5).

## 8. Verification

- `./gradlew forgeHostConsumerTest` passes: both hosts print `all checks passed`, `printResolution` shows exactly
  the core artifact on each main classpath, and the `classpath-host` unit tests pass. The test runtime resolves from
  a content filtered Maven Central entry that serves only the JUnit groups, so the core only assertion stands.
- `./gradlew test consumerSmokeTest jpmsConsumerTest` passes unchanged.
- `grep -rn 'java.nio.file\|ProcessBuilder\|java.net' consumer-fixture/forge-host/module-host/src` finds nothing: the
  host reaches no file system, process or network API.
- No sentence in this document states or implies that Forge integration has shipped.
