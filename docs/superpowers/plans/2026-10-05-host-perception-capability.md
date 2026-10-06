# Host Perception Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Neuron-owned host perception capability that turns one request-scoped host observation into a bounded, ordered batch of `OBSERVATION` signals entering the canonical cycle at the `PERCEPTION` position.

**Architecture:** A new `monada.neuron.perception` package mirrors the Action boundary (`PerceptionCapability`, `PerceptionRequest`, `PerceptionResult`, `PerceptionOutcome`, `PerceptionCognitiveStage`, `PerceptionCognitiveStageResult`). The stage is a *source* stage (`CognitiveStage.isSource()`), whose rules (first stage only, empty initial signals, fail-fast otherwise) are owned entirely by `DeterministicCognitiveCycle`; `NeuronRuntime` only gains a builder method.

**Tech Stack:** Java 27 (stable features only, no `--enable-preview`), JUnit 5, Gradle (`./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-10-05-host-perception-capability-design.md`

## Global Constraints

- Java 27 is the baseline; no preview or incubator API, no `--enable-preview`.
- No GitHub, Jira, Forge, JavaFX, LLM, or provider types in Neuron; no new dependencies.
- No text, maps, repository objects, or identifiers in `Signal`; `Signal`, `FrequencyState`, memory ports, `ActionCapability` are unchanged.
- Perception output is only `SignalKind.OBSERVATION`; `maxSignals` is explicit and positive.
- `PerceptionStatus.PARTIALLY_COMPLETED` (adapter) is distinct from `ObservationAdmission.TRUNCATED` (cycle budget); budget truncation never rewrites a status.
- Source semantics live in `DeterministicCognitiveCycle`; `NeuronRuntime` duplicates none.
- `PerceptionCognitiveStage` and a `PERCEPTION` `AeonCognitiveStage` are mutually exclusive (duplicate-position check).
- Use normal imports and simple type names; `static` only when genuinely class-level; records for value data.
- Existing `Signal`-driven cycles stay supported; `./gradlew test` must stay green after every task.
- Commit messages: `[Issue-41] <type>(<scope>): <summary>` and end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- Source stage given initial signals through the runtime: fails with `IllegalArgumentException` before any stage runs (Task 2, Task 4).
- Budget so small that the source's output is truncated to one signal: status stays `SUCCEEDED`, admitted prefix is never empty because a source runs first with `maxSignals >= 1` (Task 3).
- Adapter returns an invalid result (over the limit, non-`OBSERVATION`, null): surfaces as `CognitiveCycleException(PERCEPTION)`, never a silently trimmed batch (Task 3).
- Absent host context reaches the adapter as empty and a rejecting adapter yields `REJECTED`, not an exception (Task 4).
- Two interleaved executions with different contexts never see each other's reference (Task 4).

---

### Task 1: Perception contracts

**Files:**
- Create: `src/main/java/monada/neuron/perception/PerceptionStatus.java`
- Create: `src/main/java/monada/neuron/perception/PerceptionRequest.java`
- Create: `src/main/java/monada/neuron/perception/PerceptionResult.java`
- Create: `src/main/java/monada/neuron/perception/PerceptionOutcome.java`
- Create: `src/main/java/monada/neuron/perception/PerceptionCapability.java`
- Create: `src/main/java/monada/neuron/perception/package-info.java`
- Test: `src/test/java/monada/neuron/perception/PerceptionContractsTest.java`

**Interfaces:**
- Consumes: `Signal`, `SignalKind`, `HostExecutionContext`.
- Produces:
  - `enum PerceptionStatus { SUCCEEDED, EMPTY, PARTIALLY_COMPLETED, REJECTED, UNAVAILABLE, TIMED_OUT, FAILED }`
  - `record PerceptionRequest(int maxSignals, Optional<HostExecutionContext> hostContext)` plus `PerceptionRequest(int maxSignals)`
  - `record PerceptionResult(PerceptionStatus status, int signalLimit, List<Signal> signals)` with `PerceptionResult withAdmittedSignalPrefix(List<Signal>)`
  - `record PerceptionOutcome(PerceptionRequest request, PerceptionResult result)` with `PerceptionOutcome withAdmittedSignalPrefix(List<Signal>)`
  - `@FunctionalInterface interface PerceptionCapability { PerceptionResult perceive(PerceptionRequest request); }`

- [ ] **Step 1: Write the failing test**

```java
package monada.neuron.perception;

import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerceptionContractsTest {

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    @Test
    void requestRequiresAPositiveLimitAndAnExplicitOptionalContext() {
        var context = HostExecutionContext.of(new HostReference("run-1"));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new PerceptionRequest(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new PerceptionRequest(-1)),
                () -> assertThrows(NullPointerException.class, () -> new PerceptionRequest(1, null)),
                () -> assertEquals(Optional.empty(), new PerceptionRequest(2).hostContext()),
                () -> assertEquals(Optional.of(context), new PerceptionRequest(2, Optional.of(context)).hostContext()),
                () -> assertEquals(2, new PerceptionRequest(2).maxSignals()));
    }

    @Test
    void resultSnapshotsSignalsAndEnforcesTheLimit() {
        var first = observation(1.0);
        var second = observation(2.0);
        var mutable = new java.util.ArrayList<>(List.of(first, second));

        var result = new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, mutable);
        mutable.clear();

        assertAll(
                () -> assertEquals(List.of(first, second), result.signals()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, List.of(first, second))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 0, List.of(first))),
                () -> assertThrows(NullPointerException.class,
                        () -> new PerceptionResult(null, 1, List.of(first))),
                () -> assertThrows(NullPointerException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, null)));
    }

    @Test
    void resultRejectsAnySignalThatIsNotAnObservation() {
        var intermediate = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));
        var feedback = new Signal(SignalKind.FEEDBACK, new FrequencyState(1.0, 10.0, 0.0));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, List.of(observation(1.0), intermediate))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.PARTIALLY_COMPLETED, 1, List.of(feedback))));
    }

    @Test
    void nonFiniteFrequencyStatesAreAlreadyRejectedByTheExistingValidation() {
        assertThrows(IllegalArgumentException.class, () -> observation(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> observation(Double.POSITIVE_INFINITY));
    }

    @Test
    void statusDecidesWhetherSignalsAreRequiredOrForbidden() {
        var signal = observation(1.0);

        for (var status : List.of(PerceptionStatus.SUCCEEDED, PerceptionStatus.PARTIALLY_COMPLETED)) {
            assertAll(
                    () -> assertEquals(List.of(signal), new PerceptionResult(status, 1, List.of(signal)).signals()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new PerceptionResult(status, 1, List.of())));
        }
        for (var status : List.of(
                PerceptionStatus.EMPTY,
                PerceptionStatus.REJECTED,
                PerceptionStatus.UNAVAILABLE,
                PerceptionStatus.TIMED_OUT,
                PerceptionStatus.FAILED)) {
            assertAll(
                    () -> assertEquals(List.of(), new PerceptionResult(status, 1, List.of()).signals()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new PerceptionResult(status, 1, List.of(signal))));
        }
    }

    @Test
    void outcomeRequiresTheResultLimitToMatchTheRequest() {
        var request = new PerceptionRequest(2);
        var result = new PerceptionResult(PerceptionStatus.EMPTY, 3, List.of());

        assertThrows(IllegalArgumentException.class, () -> new PerceptionOutcome(request, result));
        assertThrows(NullPointerException.class, () -> new PerceptionOutcome(null, result));
        assertThrows(NullPointerException.class, () -> new PerceptionOutcome(request, null));
    }

    @Test
    void admittedPrefixKeepsTheAdapterStatusAndRejectsAnythingButAnOrderedPrefix() {
        var first = observation(1.0);
        var second = observation(2.0);
        var third = observation(3.0);
        var result = new PerceptionResult(PerceptionStatus.SUCCEEDED, 3, List.of(first, second, third));
        var outcome = new PerceptionOutcome(new PerceptionRequest(3), result);

        var prefix = outcome.withAdmittedSignalPrefix(List.of(first, second));

        assertAll(
                () -> assertEquals(List.of(first, second), prefix.result().signals()),
                // the cycle budget limits what is kept; it never turns a success into a partial one
                () -> assertEquals(PerceptionStatus.SUCCEEDED, prefix.result().status()),
                () -> assertEquals(3, prefix.result().signalLimit()),
                () -> assertSame(result, result.withAdmittedSignalPrefix(List.of(first, second, third))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedSignalPrefix(List.of(second))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedSignalPrefix(List.of(first, second, third, observation(4.0)))));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'monada.neuron.perception.PerceptionContractsTest'`
Expected: FAIL to compile ("package monada.neuron.perception" classes do not exist).

- [ ] **Step 3: Write minimal implementation**

`PerceptionStatus.java`:

```java
package monada.neuron.perception;

/**
 * Explicit expected outcome of one perception-capability execution, as reported by the adapter.
 *
 * <p>These values describe what the adapter achieved, never what the cognitive cycle admitted; see
 * {@code ObservationAdmission} for budget truncation.
 */
public enum PerceptionStatus {
    /** The adapter resolved the observation and produced at least one signal. */
    SUCCEEDED,

    /** The adapter resolved the observation, which legitimately yielded no signals. */
    EMPTY,

    /** The adapter reported that it observed only part of the requested input; it still produced signals. */
    PARTIALLY_COMPLETED,

    /** The adapter declined the request, for example because the host context is missing or unknown. */
    REJECTED,

    /** The adapter was unavailable for this request. */
    UNAVAILABLE,

    /** The adapter reached its own timeout before producing a usable result. */
    TIMED_OUT,

    /** The adapter reported an expected failure without provider-specific payloads. */
    FAILED
}
```

`PerceptionRequest.java`:

```java
package monada.neuron.perception;

import monada.neuron.context.HostExecutionContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable request for one bounded host observation.
 *
 * <p>It carries no payload: the adapter resolves the observation on the host side through the opaque
 * {@code hostContext}. When the context is absent, or the adapter cannot resolve it, the adapter reports
 * an expected status such as {@link PerceptionStatus#REJECTED}; Neuron never interprets the references.
 *
 * @param maxSignals positive output limit
 * @param hostContext request-scoped host correlation, or empty when the cycle has none
 */
public record PerceptionRequest(int maxSignals, Optional<HostExecutionContext> hostContext) {

    /** Requires an explicit positive output limit and a non-null optional context. */
    public PerceptionRequest {
        if (maxSignals <= 0) {
            throw new IllegalArgumentException("maxSignals must be positive, got: " + maxSignals);
        }
        Objects.requireNonNull(hostContext, "hostContext must not be null");
    }

    /** Creates a request without host context. */
    public PerceptionRequest(int maxSignals) {
        this(maxSignals, Optional.empty());
    }
}
```

`PerceptionResult.java`:

```java
package monada.neuron.perception;

import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.Objects;

/** Immutable bounded result whose signal order is supplied by the perception adapter. */
public record PerceptionResult(
        PerceptionStatus status,
        int signalLimit,
        List<Signal> signals) {

    /** Validates the explicit bound, the OBSERVATION kind, and the per-status signal rule. */
    public PerceptionResult {
        Objects.requireNonNull(status, "status must not be null");
        if (signalLimit <= 0) {
            throw new IllegalArgumentException("signalLimit must be positive, got: " + signalLimit);
        }
        signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        if (signals.size() > signalLimit) {
            throw new IllegalArgumentException(
                    "signals must not exceed signalLimit: " + signals.size() + " > " + signalLimit);
        }
        for (var signal : signals) {
            if (signal.kind() != SignalKind.OBSERVATION) {
                throw new IllegalArgumentException(
                        "perception signals must be OBSERVATION, got: " + signal.kind());
            }
        }
        var requiresSignals = switch (status) {
            case SUCCEEDED, PARTIALLY_COMPLETED -> true;
            case EMPTY, REJECTED, UNAVAILABLE, TIMED_OUT, FAILED -> false;
        };
        if (requiresSignals && signals.isEmpty()) {
            throw new IllegalArgumentException(status + " results must contain signals");
        }
        if (!requiresSignals && !signals.isEmpty()) {
            throw new IllegalArgumentException(status + " results must not contain signals");
        }
    }

    /**
     * Returns this result with only the cycle-admitted ordered signal prefix retained.
     *
     * <p>The status is the adapter's report and is never changed by admission: a cycle budget limits what
     * the cycle keeps, not what the adapter observed.
     *
     * @throws IllegalArgumentException when the argument is not an ordered prefix of {@link #signals()}
     */
    public PerceptionResult withAdmittedSignalPrefix(List<Signal> admittedSignals) {
        var stable = List.copyOf(Objects.requireNonNull(admittedSignals, "admittedSignals must not be null"));
        if (stable.size() > signals.size()) {
            throw new IllegalArgumentException("admittedSignals cannot exceed original signal count");
        }
        for (var index = 0; index < stable.size(); index++) {
            if (!signals.get(index).equals(stable.get(index))) {
                throw new IllegalArgumentException(
                        "admittedSignals must be an ordered prefix of the original signals");
            }
        }
        if (stable.size() == signals.size()) {
            return this;
        }
        return new PerceptionResult(status, signalLimit, stable);
    }
}
```

`PerceptionOutcome.java`:

```java
package monada.neuron.perception;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable outcome that keeps one admitted request associated with its observed result. */
public record PerceptionOutcome(PerceptionRequest request, PerceptionResult result) {

    /** Requires the result to acknowledge the request's explicit signal limit. */
    public PerceptionOutcome {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (result.signalLimit() != request.maxSignals()) {
            throw new IllegalArgumentException("result signalLimit must match request maxSignals");
        }
    }

    /** Returns this outcome with only the cycle-admitted signal prefix retained. */
    public PerceptionOutcome withAdmittedSignalPrefix(List<Signal> admittedSignals) {
        return new PerceptionOutcome(request, result.withAdmittedSignalPrefix(admittedSignals));
    }
}
```

`PerceptionCapability.java`:

```java
package monada.neuron.perception;

/**
 * Neuron-owned boundary for turning one request-scoped host observation into bounded signals.
 *
 * <p>Implementations own transport, encoding, model, and provider details, and resolve the observation
 * from the request's host context. Neuron prescribes no universal text or task encoder. Expected outcomes
 * are returned through {@link PerceptionResult}; an unexpected runtime failure remains an operational
 * failure of the calling cognitive stage.
 */
@FunctionalInterface
public interface PerceptionCapability {

    /** Performs one bounded perception request; signals must be ordered deterministically. */
    PerceptionResult perceive(PerceptionRequest request);
}
```

`package-info.java`:

```java
/**
 * Host perception boundary: a Neuron-owned capability that turns one request-scoped host observation
 * into a bounded, ordered batch of OBSERVATION signals (ADR 0024).
 */
package monada.neuron.perception;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'monada.neuron.perception.PerceptionContractsTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/monada/neuron/perception src/test/java/monada/neuron/perception/PerceptionContractsTest.java
git commit -m "[Issue-41] feat(perception): immutable perception request, result and outcome contracts

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Source-stage semantics in the cycle

**Files:**
- Modify: `src/main/java/monada/neuron/monad/CognitiveStage.java` (add `isSource()` after `acceptsTypedOnlyHandOff()`)
- Modify: `src/main/java/monada/neuron/monad/DeterministicCognitiveCycle.java` (constructor, `execute(...)`)
- Test: `src/test/java/monada/neuron/monad/DeterministicCognitiveCycleSourceTest.java`

**Interfaces:**
- Consumes: existing `CognitiveStage`, `CognitiveStageResultSnapshot(kind, status, signals)`.
- Produces: `default boolean CognitiveStage.isSource()` (false). Cycle rules: a source must be the first normalized stage (constructor `IllegalArgumentException`); it runs with empty initial signals; non-empty initial signals with a source plan throw `IllegalArgumentException` before any context or stage work.

- [ ] **Step 1: Write the failing test**

```java
package monada.neuron.monad;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicCognitiveCycleSourceTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(10, 10, 20);

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    /** Test stage that emits fixed signals and counts executions; optionally declares itself a source. */
    private static final class FixedStage implements CognitiveStage {
        final CognitiveStageKind kind;
        final boolean source;
        final List<Signal> outputs;
        final AtomicInteger executions = new AtomicInteger();
        List<Signal> lastInputs = List.of();

        FixedStage(CognitiveStageKind kind, boolean source, List<Signal> outputs) {
            this.kind = kind;
            this.source = source;
            this.outputs = outputs;
        }

        @Override
        public CognitiveStageKind kind() {
            return kind;
        }

        @Override
        public boolean isSource() {
            return source;
        }

        @Override
        public CognitiveStageResult execute(
                PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
            executions.incrementAndGet();
            lastInputs = inputSignals;
            return new CognitiveStageResultSnapshot(kind, CognitiveStageStatus.COMPLETED, outputs);
        }
    }

    @Test
    void stagesAreNotSourcesByDefault() {
        CognitiveStage stage = new FixedStage(CognitiveStageKind.REASONING, false, List.of());

        assertEquals(false, stage.isSource());
    }

    @Test
    void sourceRunsWithEmptyInitialSignalsAndHandsItsOutputToTheNextStage() {
        var first = observation(1.0);
        var second = observation(2.0);
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of(first, second));
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of(observation(3.0)));

        var result = new DeterministicCognitiveCycle(List.of(next, source))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(), source.lastInputs),
                () -> assertEquals(List.of(first, second), next.lastInputs),
                () -> assertEquals(1, source.executions.get()),
                () -> assertEquals(1, next.executions.get()),
                () -> assertEquals(
                        List.of(CognitiveStageKind.PERCEPTION, CognitiveStageKind.REASONING),
                        result.stageResults().stream().map(CognitiveStageResult::kind).toList()));
    }

    @Test
    void sourceThatEmitsNothingEndsAMultiStageCycleWithNoSignals() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of());

        var result = new DeterministicCognitiveCycle(List.of(source, next))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertEquals(0, next.executions.get()),
                () -> assertEquals(1, result.stageResults().size()));
    }

    @Test
    void sourceOnlyCycleCompletesEvenWhenItEmitsNothing() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());

        var result = new DeterministicCognitiveCycle(List.of(source)).execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(), result.outputSignals()));
    }

    @Test
    void initialSignalsWithASourcePlanFailBeforeAnyStageRuns() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of(observation(1.0)));
        var next = new FixedStage(CognitiveStageKind.REASONING, false, List.of());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DeterministicCognitiveCycle(List.of(source, next))
                                .execute(monad(), List.of(observation(9.0)), BUDGET)),
                () -> assertEquals(0, source.executions.get()),
                () -> assertEquals(0, next.executions.get()));
    }

    @Test
    void aSourceThatIsNotTheFirstStageIsRejectedAtConstruction() {
        var earlier = new FixedStage(CognitiveStageKind.PERCEPTION, false, List.of());
        var lateSource = new FixedStage(CognitiveStageKind.MEMORY_RECALL, true, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicCognitiveCycle(List.of(earlier, lateSource)));
    }

    @Test
    void aSourceAndAnotherStageAtThePerceptionPositionAreRejectedAsDuplicates() {
        var source = new FixedStage(CognitiveStageKind.PERCEPTION, true, List.of());
        var other = new FixedStage(CognitiveStageKind.PERCEPTION, false, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicCognitiveCycle(List.of(source, other)));
    }

    @Test
    void planWithoutASourceStillEndsWithNoSignalsForEmptyInitialSignals() {
        var stage = new FixedStage(CognitiveStageKind.REASONING, false, List.of(observation(1.0)));

        var result = new DeterministicCognitiveCycle(List.of(stage)).execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertEquals(0, stage.executions.get()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'monada.neuron.monad.DeterministicCognitiveCycleSourceTest'`
Expected: FAIL to compile (`isSource()` is not a method of `CognitiveStage`).

- [ ] **Step 3: Write minimal implementation**

In `CognitiveStage.java`, add after `acceptsTypedOnlyHandOff()`:

```java
    /**
     * Returns whether this stage produces signals from the host instead of consuming a predecessor's.
     *
     * <p>The deterministic cycle owns the rules: a source must be the first configured stage, runs with
     * an empty initial batch, and a cycle with a source stage rejects non-empty initial signals before
     * executing anything. Stages that consume signals keep the default.
     */
    default boolean isSource() {
        return false;
    }
```

In `DeterministicCognitiveCycle.java`:

1. At the end of the constructor, replace `this.stages = List.copyOf(stagesByKind.values());` with:

```java
        var normalized = List.copyOf(stagesByKind.values());
        for (var index = 1; index < normalized.size(); index++) {
            if (normalized.get(index).isSource()) {
                throw new IllegalArgumentException(
                        "a source stage must be the first stage: " + normalized.get(index).kind());
            }
        }
        this.stages = normalized;
```

2. In the 4-argument `execute`, directly after `Objects.requireNonNull(budget, "budget must not be null");` and before `validateBindings(monad);`, add:

```java
        var hasSource = !stages.isEmpty() && stages.getFirst().isSource();
        if (hasSource && !stableInputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "a cycle with source stage " + stages.getFirst().kind()
                            + " must not receive initial signals, got: " + stableInputs.size());
        }
```

3. In the stage loop, introduce `var source = stage.isSource();` right after `currentStage = stage;` and change the three conditions:

```java
                if (currentSignals.isEmpty() && !typedHandOff && !source) {
```

```java
                var stageInputs = stage instanceof AeonCognitiveStage || source
                        ? currentSignals
                        : admitStageInputs(stage.kind(), currentSignals, context);
                if (!source && stageInputs.isEmpty() && (!currentSignals.isEmpty() || !typedHandOff)) {
```

(the body of both `if` blocks is unchanged).

- [ ] **Step 4: Run tests to verify they pass, including the existing cycle suite**

Run: `./gradlew test --tests 'monada.neuron.monad.*'`
Expected: PASS (new source tests and all existing `DeterministicCognitiveCycleTest` cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/monada/neuron/monad/CognitiveStage.java src/main/java/monada/neuron/monad/DeterministicCognitiveCycle.java src/test/java/monada/neuron/monad/DeterministicCognitiveCycleSourceTest.java
git commit -m "[Issue-41] feat(monad): source-stage semantics owned by the deterministic cycle

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Perception stage, stage result and deterministic fixture

**Files:**
- Create: `src/main/java/monada/neuron/perception/PerceptionCognitiveStage.java`
- Create: `src/main/java/monada/neuron/perception/PerceptionCognitiveStageResult.java`
- Create: `src/test/java/monada/neuron/perception/DeterministicPerceptionCapability.java`
- Test: `src/test/java/monada/neuron/perception/PerceptionCognitiveStageTest.java`

**Interfaces:**
- Consumes: Task 1 contracts; Task 2 `CognitiveStage.isSource()`; `monada.neuron.action.ObservationAdmission` (`COMPLETE`, `TRUNCATED`).
- Produces:
  - `PerceptionCognitiveStage(PerceptionCapability capability, int maxSignals)` implementing `CognitiveStage` (`kind() == PERCEPTION`, `isSource() == true`).
  - `record PerceptionCognitiveStageResult(PerceptionOutcome outcome, int producedSignalCount, ObservationAdmission signalAdmission)` implementing `CognitiveStageResult`, plus `PerceptionCognitiveStageResult(PerceptionOutcome)` and `int admittedSignalCount()`.
  - Test fixture `public final class DeterministicPerceptionCapability implements PerceptionCapability` with `record Script(PerceptionStatus status, List<Signal> signals)`, constructor `(Map<String, Script> scriptsByExecutionRef)`, and `List<PerceptionRequest> receivedRequests()`. An absent context or an unscripted reference yields `REJECTED` with no signals.

- [ ] **Step 1: Write the failing tests and fixture**

Fixture `DeterministicPerceptionCapability.java`:

```java
package monada.neuron.perception;

import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic test-only perception capability scripted by execution reference.
 *
 * <p>It needs no network, credentials, or model. A request without a host context, or whose reference has
 * no script, is rejected, as a real adapter that cannot resolve a reference would do.
 */
public final class DeterministicPerceptionCapability implements PerceptionCapability {

    /** Scripted adapter report for one execution reference. */
    public record Script(PerceptionStatus status, List<Signal> signals) {

        public Script {
            Objects.requireNonNull(status, "status must not be null");
            signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        }
    }

    private final Map<String, Script> scripts;
    private final List<PerceptionRequest> receivedRequests = new ArrayList<>();

    /** Snapshots the scripts, keyed by {@code HostExecutionContext.executionRef().value()}. */
    public DeterministicPerceptionCapability(Map<String, Script> scriptsByExecutionRef) {
        this.scripts = Map.copyOf(Objects.requireNonNull(scriptsByExecutionRef, "scripts must not be null"));
    }

    @Override
    public PerceptionResult perceive(PerceptionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        receivedRequests.add(request);
        var script = request.hostContext()
                .map(context -> scripts.get(context.executionRef().value()))
                .orElse(null);
        if (script == null) {
            return new PerceptionResult(PerceptionStatus.REJECTED, request.maxSignals(), List.of());
        }
        return new PerceptionResult(script.status(), request.maxSignals(), script.signals());
    }

    /** Returns requests in execution order as an immutable test observation. */
    public List<PerceptionRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
```

Test `PerceptionCognitiveStageTest.java`:

```java
package monada.neuron.perception;

import monada.neuron.action.ObservationAdmission;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResultSnapshot;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerceptionCognitiveStageTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(10, 10, 20);

    private static HostExecutionContext context(String ref) {
        return HostExecutionContext.of(new HostReference(ref));
    }

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static PerceptionCognitiveStageResult stageResult(
            monada.neuron.monad.CognitiveCycleResult result) {
        return assertInstanceOf(PerceptionCognitiveStageResult.class, result.stageResults().getFirst());
    }

    /** Downstream stage that records whether it ran. */
    private static final class CountingStage implements CognitiveStage {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public CognitiveStageKind kind() {
            return CognitiveStageKind.REASONING;
        }

        @Override
        public CognitiveStageResultSnapshot execute(
                PrimaryMonad monad, List<Signal> inputSignals, monada.neuron.context.CognitiveContext context) {
            executions.incrementAndGet();
            return new CognitiveStageResultSnapshot(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
        }
    }

    @Test
    void emitsOrderedObservationsAndRetainsTheOutcomeWithItsHostContext() {
        var first = observation(1.0);
        var second = observation(2.0);
        var host = context("run-1");
        var capability = new DeterministicPerceptionCapability(Map.of(
                "run-1", new DeterministicPerceptionCapability.Script(
                        PerceptionStatus.SUCCEEDED, List.of(first, second))));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 2)))
                .execute(monad(), List.of(), BUDGET, Optional.of(host));
        var stage = stageResult(result);
        var request = new PerceptionRequest(2, Optional.of(host));

        assertAll(
                () -> assertEquals(List.of(first, second), result.outputSignals()),
                () -> assertEquals(
                        new PerceptionOutcome(request, new PerceptionResult(
                                PerceptionStatus.SUCCEEDED, 2, List.of(first, second))),
                        stage.outcome()),
                () -> assertEquals(List.of(request), capability.receivedRequests()),
                () -> assertEquals(CognitiveStageKind.PERCEPTION, stage.kind()),
                () -> assertEquals(CognitiveStageStatus.COMPLETED, stage.status()),
                () -> assertEquals(2, stage.producedSignalCount()),
                () -> assertEquals(2, stage.admittedSignalCount()),
                () -> assertEquals(ObservationAdmission.COMPLETE, stage.signalAdmission()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }

    @Test
    void stageIsASourceAtThePerceptionPosition() {
        var stage = new PerceptionCognitiveStage(request -> null, 1);

        assertAll(
                () -> assertEquals(CognitiveStageKind.PERCEPTION, stage.kind()),
                () -> assertTrue(stage.isSource()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStage(request -> null, 0)),
                () -> assertThrows(NullPointerException.class, () -> new PerceptionCognitiveStage(null, 1)));
    }

    @Test
    void nonSuccessOutcomesEndTheCycleWithNoSignalsAndKeepTheAdapterStatus() {
        for (var status : List.of(
                PerceptionStatus.EMPTY,
                PerceptionStatus.REJECTED,
                PerceptionStatus.UNAVAILABLE,
                PerceptionStatus.TIMED_OUT,
                PerceptionStatus.FAILED)) {
            var downstream = new CountingStage();
            PerceptionCapability capability = request ->
                    new PerceptionResult(status, request.maxSignals(), List.of());

            var result = new DeterministicCognitiveCycle(List.of(
                    new PerceptionCognitiveStage(capability, 2), downstream))
                    .execute(monad(), List.of(), BUDGET);

            assertAll(
                    () -> assertEquals(status, stageResult(result).outcome().result().status()),
                    () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                    () -> assertEquals(0, downstream.executions.get()),
                    () -> assertEquals(List.of(), result.outputSignals()));
        }
    }

    @Test
    void adapterPartialCompletionIsKeptAndIsNotTruncation() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.PARTIALLY_COMPLETED, request.maxSignals(), List.of(first, second));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 3)))
                .execute(monad(), List.of(), BUDGET);
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(PerceptionStatus.PARTIALLY_COMPLETED, stage.outcome().result().status()),
                () -> assertEquals(ObservationAdmission.COMPLETE, stage.signalAdmission()),
                () -> assertEquals(List.of(first, second), result.outputSignals()));
    }

    @Test
    void cycleBudgetTruncationKeepsTheReportedSuccessAndRecordsTruncation() {
        var first = observation(1.0);
        var second = observation(2.0);
        var rejected = observation(3.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.SUCCEEDED, request.maxSignals(), List.of(first, second, rejected));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 3)))
                .execute(monad(), List.of(), new CognitiveBudget(10, 2, 20));
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, result.termination()),
                () -> assertEquals(List.of(first, second), result.outputSignals()),
                // the budget limits what the cycle keeps; it never rewrites SUCCEEDED to PARTIALLY_COMPLETED
                () -> assertEquals(PerceptionStatus.SUCCEEDED, stage.outcome().result().status()),
                () -> assertEquals(3, stage.producedSignalCount()),
                () -> assertEquals(2, stage.admittedSignalCount()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, stage.signalAdmission()));
    }

    @Test
    void adapterPartialCompletionAndBudgetTruncationAreIndependent() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.PARTIALLY_COMPLETED, request.maxSignals(), List.of(first, second));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 2)))
                .execute(monad(), List.of(), new CognitiveBudget(10, 1, 20));
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(PerceptionStatus.PARTIALLY_COMPLETED, stage.outcome().result().status()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, stage.signalAdmission()),
                () -> assertEquals(List.of(first), result.outputSignals()));
    }

    @Test
    void resultRecordRejectsCountersThatDisagreeWithAdmission() {
        var signal = observation(1.0);
        var outcome = new PerceptionOutcome(
                new PerceptionRequest(2),
                new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, List.of(signal)));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 0, ObservationAdmission.COMPLETE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 3, ObservationAdmission.TRUNCATED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 2, ObservationAdmission.COMPLETE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 1, ObservationAdmission.TRUNCATED)),
                () -> assertEquals(1, new PerceptionCognitiveStageResult(outcome).producedSignalCount()));
    }

    @Test
    void anInvalidAdapterResultIsAnOperationalFailureNotATrimmedBatch() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability overLimit = request ->
                new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, List.of(first, second));
        PerceptionCapability wrongLimit = request ->
                new PerceptionResult(PerceptionStatus.EMPTY, request.maxSignals() + 1, List.of());
        PerceptionCapability nullResult = request -> null;

        for (var capability : List.of(overLimit, wrongLimit, nullResult)) {
            var failure = assertThrows(CognitiveCycleException.class,
                    () -> new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                            .execute(monad(), List.of(), BUDGET));
            assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage());
        }
    }

    @Test
    void anUnexpectedAdapterExceptionPropagatesAsACycleFailureOfThePerceptionStage() {
        var cause = new IllegalStateException("adapter exploded");
        PerceptionCapability capability = request -> {
            throw cause;
        };

        var failure = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                        .execute(monad(), List.of(), BUDGET));

        assertAll(
                () -> assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage()),
                () -> assertEquals(cause, failure.getCause()));
    }

    @Test
    void missingContextReachesTheAdapterAsEmptyAndIsAnExpectedRejection() {
        var capability = new DeterministicPerceptionCapability(Map.of());

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(List.of(new PerceptionRequest(1)), capability.receivedRequests()),
                () -> assertEquals(PerceptionStatus.REJECTED, stageResult(result).outcome().result().status()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'monada.neuron.perception.PerceptionCognitiveStageTest'`
Expected: FAIL to compile (`PerceptionCognitiveStage`, `PerceptionCognitiveStageResult` missing).

- [ ] **Step 3: Write minimal implementation**

`PerceptionCognitiveStage.java`:

```java
package monada.neuron.perception;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Optional PERCEPTION-stage source that asks one {@link PerceptionCapability} for bounded observations.
 *
 * <p>It is a source stage: it needs no initial signals, and the deterministic cycle owns the rules that
 * follow from that (see {@link CognitiveStage#isSource()}). It occupies the single PERCEPTION position,
 * so it is an alternative to, not a companion of, an {@code AeonCognitiveStage} at that position.
 */
public final class PerceptionCognitiveStage implements CognitiveStage {

    private final PerceptionCapability capability;
    private final int maxSignals;

    /** Creates a perception stage with one explicit signal limit for every request. */
    public PerceptionCognitiveStage(PerceptionCapability capability, int maxSignals) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        if (maxSignals <= 0) {
            throw new IllegalArgumentException("maxSignals must be positive, got: " + maxSignals);
        }
        this.maxSignals = maxSignals;
    }

    /** Returns the fixed perception position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.PERCEPTION;
    }

    /** Declares this stage a source: it produces signals from the host rather than consuming any. */
    @Override
    public boolean isSource() {
        return true;
    }

    /** Executes one perception request bound to the cycle's host context, if any. */
    @Override
    public PerceptionCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        Objects.requireNonNull(context, "context must not be null");
        var request = new PerceptionRequest(maxSignals, context.hostContext());
        var result = Objects.requireNonNull(capability.perceive(request), "perception result must not be null");
        return new PerceptionCognitiveStageResult(new PerceptionOutcome(request, result));
    }
}
```

`PerceptionCognitiveStageResult.java`:

```java
package monada.neuron.perception;

import monada.neuron.action.ObservationAdmission;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Observable perception-stage result retaining its typed outcome and admitted signal prefix.
 *
 * <p>{@link #outcome()} keeps the status the adapter reported, whatever the cycle budget admitted. The
 * cycle-specific facts live here: how many signals the adapter produced and whether the cycle admitted
 * all of them. {@code PerceptionStatus.PARTIALLY_COMPLETED} and {@code ObservationAdmission.TRUNCATED}
 * are independent: the first describes the adapter's observation, the second the cycle's capacity.
 *
 * @param outcome the request and the adapter result, holding only the admitted signals
 * @param producedSignalCount signals the adapter produced before cycle admission
 * @param signalAdmission whether the cycle admitted every produced signal
 */
public record PerceptionCognitiveStageResult(
        PerceptionOutcome outcome,
        int producedSignalCount,
        ObservationAdmission signalAdmission) implements CognitiveStageResult {

    /**
     * Requires a typed outcome and counters that agree with it and with the admission flag.
     *
     * @throws IllegalArgumentException if fewer signals were produced than admitted, more than the request
     *     limit, or the admission flag disagrees with the counters
     */
    public PerceptionCognitiveStageResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(signalAdmission, "signalAdmission must not be null");
        var admitted = outcome.result().signals().size();
        if (producedSignalCount < admitted) {
            throw new IllegalArgumentException(
                    "producedSignalCount must not be below the admitted count: "
                            + producedSignalCount + " < " + admitted);
        }
        if (producedSignalCount > outcome.request().maxSignals()) {
            throw new IllegalArgumentException(
                    "producedSignalCount must not exceed the request limit: "
                            + producedSignalCount + " > " + outcome.request().maxSignals());
        }
        var truncated = producedSignalCount > admitted;
        if (truncated != (signalAdmission == ObservationAdmission.TRUNCATED)) {
            throw new IllegalArgumentException(
                    "signalAdmission " + signalAdmission + " disagrees with produced "
                            + producedSignalCount + " and admitted " + admitted + " signals");
        }
    }

    /** Creates a result whose signals were all admitted. */
    public PerceptionCognitiveStageResult(PerceptionOutcome outcome) {
        this(outcome, Objects.requireNonNull(outcome, "outcome must not be null").result().signals().size(),
                ObservationAdmission.COMPLETE);
    }

    /** Returns how many of the produced signals the cycle admitted. */
    public int admittedSignalCount() {
        return outcome.result().signals().size();
    }

    /** Returns the fixed perception position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.PERCEPTION;
    }

    /** Expected perception outcomes complete the stage without a local execution limit. */
    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    /** Returns the observations in adapter-defined deterministic order. */
    @Override
    public List<Signal> outputSignals() {
        return outcome.result().signals();
    }

    /**
     * Keeps the adapter-reported status and the produced count while dropping every signal the cycle
     * budget rejected, and records whether that truncated the output.
     */
    @Override
    public PerceptionCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        var admitted = outcome.withAdmittedSignalPrefix(admittedOutputSignals);
        var truncated = producedSignalCount > admitted.result().signals().size();
        return new PerceptionCognitiveStageResult(
                admitted,
                producedSignalCount,
                truncated ? ObservationAdmission.TRUNCATED : ObservationAdmission.COMPLETE);
    }
}
```

Invariant to keep in mind (do not add code): the cycle admits a prefix of at least one signal for a source, because a source is the first stage and `CognitiveBudget.maxSignals` is positive, so `withAdmittedSignalPrefix` never has to produce an empty `SUCCEEDED`/`PARTIALLY_COMPLETED` result.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'monada.neuron.perception.*'`
Expected: PASS. If `cycleBudgetTruncationKeepsTheReportedSuccessAndRecordsTruncation` terminates with a different value than `CONTEXT_BUDGET_EXHAUSTED`, compare with the equivalent assertion in `ActionCognitiveStageTest` and fix the test's budget, not the production code.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/monada/neuron/perception src/test/java/monada/neuron/perception
git commit -m "[Issue-41] feat(perception): PERCEPTION source stage with typed outcome and deterministic fixture

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Runtime integration

**Files:**
- Modify: `src/main/java/monada/neuron/host/NeuronRuntime.java` (builder method, class and `execute` javadoc)
- Test: `src/test/java/monada/neuron/host/PerceptionRuntimeTest.java`

**Interfaces:**
- Consumes: `PerceptionCognitiveStage`, `PerceptionCapability`, `DeterministicPerceptionCapability` (test fixture), `CycleInput`.
- Produces: `NeuronRuntime.Builder perceptionCapability(PerceptionCapability capability, int maxSignals)`.

- [ ] **Step 1: Write the failing test**

```java
package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.DeterministicPerceptionCapability;
import monada.neuron.perception.DeterministicPerceptionCapability.Script;
import monada.neuron.perception.PerceptionCapability;
import monada.neuron.perception.PerceptionCognitiveStageResult;
import monada.neuron.perception.PerceptionRequest;
import monada.neuron.perception.PerceptionResult;
import monada.neuron.perception.PerceptionStatus;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerceptionRuntimeTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);

    private static HostExecutionContext host(String ref) {
        return HostExecutionContext.of(new HostReference(ref));
    }

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static NeuronRuntime.Builder runtimeBuilder() {
        return NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(BUDGET);
    }

    @Test
    void perceivedObservationsFlowThroughTheCanonicalCycleToTheActionStage() {
        var first = observation(1.0);
        var second = observation(2.0);
        var perception = new DeterministicPerceptionCapability(Map.of(
                "run-1", new Script(PerceptionStatus.SUCCEEDED, List.of(first, second))));
        var actionInputs = new ArrayList<List<Signal>>();
        ActionCapability action = request -> {
            actionInputs.add(request.inputSignals());
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        };
        var runtime = runtimeBuilder()
                .perceptionCapability(perception, 4)
                .actionCapability(action, 1)
                .build();
        var context = host("run-1");

        var result = runtime.execute(CycleInput.of(List.of()).withHostContext(context));

        assertAll(
                () -> assertEquals(List.of(List.of(first, second)), actionInputs),
                () -> assertEquals(
                        List.of(CognitiveStageKind.PERCEPTION, CognitiveStageKind.ACTION),
                        result.stageResults().stream().map(stage -> stage.kind()).toList()),
                () -> assertEquals(
                        List.of(new PerceptionRequest(4, Optional.of(context))),
                        perception.receivedRequests()));
    }

    @Test
    void initialSignalsWithAPerceptionStageFailBeforeAnythingRuns() {
        var perception = new DeterministicPerceptionCapability(Map.of());
        var runtime = runtimeBuilder().perceptionCapability(perception, 2).build();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> runtime.execute(List.of(observation(1.0)))),
                () -> assertEquals(List.of(), perception.receivedRequests()));
    }

    @Test
    void aPerceptionStageAndAnotherPerceptionPositionStageAreMutuallyExclusive() {
        CognitiveStage otherPerception = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.PERCEPTION;
            }

            @Override
            public monada.neuron.monad.CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, monada.neuron.context.CognitiveContext context) {
                throw new AssertionError("must not execute");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> runtimeBuilder()
                .stage(otherPerception)
                .perceptionCapability(new DeterministicPerceptionCapability(Map.of()), 2)
                .build());
    }

    @Test
    void missingContextIsAnExpectedRejectionNotAnException() {
        var perception = new DeterministicPerceptionCapability(Map.of());
        var runtime = runtimeBuilder().perceptionCapability(perception, 2).build();

        var result = runtime.execute(CycleInput.of(List.of()));
        var stage = assertInstanceOf(PerceptionCognitiveStageResult.class, result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(PerceptionStatus.REJECTED, stage.outcome().result().status()),
                () -> assertEquals(Optional.empty(), stage.outcome().request().hostContext()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }

    @Test
    void consecutiveExecutionsObserveOnlyTheirOwnHostContext() {
        var capability = new DeterministicPerceptionCapability(Map.of(
                "run-a", new Script(PerceptionStatus.SUCCEEDED, List.of(observation(1.0))),
                "run-b", new Script(PerceptionStatus.SUCCEEDED, List.of(observation(2.0)))));
        var runtime = runtimeBuilder().perceptionCapability(capability, 1).build();

        var first = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-a")));
        var second = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-b")));
        var third = runtime.execute(CycleInput.of(List.of()));

        assertAll(
                () -> assertEquals(List.of(observation(1.0)), first.outputSignals()),
                () -> assertEquals(List.of(observation(2.0)), second.outputSignals()),
                () -> assertEquals(List.of(), third.outputSignals()),
                () -> assertEquals(
                        List.of(Optional.of(host("run-a")), Optional.of(host("run-b")), Optional.empty()),
                        capability.receivedRequests().stream().map(PerceptionRequest::hostContext).toList()));
    }

    @Test
    void separateRuntimesRunInParallelWithoutSharingContext() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        PerceptionCapability recording = request -> {
            var ref = request.hostContext().orElseThrow().executionRef().value();
            seen.add(ref);
            return new PerceptionResult(PerceptionStatus.SUCCEEDED, request.maxSignals(), List.of(observation(1.0)));
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<List<Signal>>>();
            for (var index = 0; index < 8; index++) {
                var ref = "run-" + index;
                futures.add(executor.submit(() -> runtimeBuilder().perceptionCapability(recording, 1).build()
                        .execute(CycleInput.of(List.of()).withHostContext(host(ref))).outputSignals()));
            }
            for (var future : futures) {
                assertEquals(List.of(observation(1.0)), future.get());
            }
        }

        assertEquals(
                java.util.stream.IntStream.range(0, 8).mapToObj(index -> "run-" + index).sorted().toList(),
                seen.stream().sorted().toList());
    }

    @Test
    void adapterFailurePropagatesAsACycleFailureOfThePerceptionStage() {
        PerceptionCapability failing = request -> {
            throw new IllegalStateException("adapter exploded");
        };
        var runtime = runtimeBuilder().perceptionCapability(failing, 1).build();

        var failure = assertThrows(CognitiveCycleException.class,
                () -> runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-1"))));

        assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage());
    }

    @Test
    void existingSignalDrivenRuntimesRemainSupported() {
        var runtime = runtimeBuilder().build();
        var input = List.of(observation(1.0));

        assertEquals(input, runtime.execute(input).outputSignals());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'monada.neuron.host.PerceptionRuntimeTest'`
Expected: FAIL to compile (`perceptionCapability` missing on `NeuronRuntime.Builder`).

- [ ] **Step 3: Write minimal implementation**

In `NeuronRuntime.java`:

1. Add imports `monada.neuron.perception.PerceptionCapability` and `monada.neuron.perception.PerceptionCognitiveStage` (keep import order alphabetical with the existing block).
2. In the class javadoc, change "Memory and action capabilities are optional." to "Perception, memory and action capabilities are optional." and "They are reached only through {@link ResonanceMemoryPort} and {@link ActionCapability}" to "They are reached only through {@link PerceptionCapability}, {@link ResonanceMemoryPort} and {@link ActionCapability}".
3. Add to the `execute(CycleInput)` javadoc, after the host-context sentence: `A runtime with a perception capability takes its signals from that capability, so the input must carry none; the cycle rejects initial signals with an {@link IllegalArgumentException} before executing anything.` and add `@throws IllegalArgumentException when a perception stage is configured and the input carries initial signals` next to the existing `@throws`.
4. Add the builder method before `memoryPort`:

```java
        /**
         * Adds the optional perception source stage backed by the given capability and signal limit.
         *
         * <p>It occupies the single PERCEPTION position, so it excludes a PERCEPTION Aeon stage, and the
         * executions of the built runtime must supply no initial signals (ADR 0024).
         */
        public Builder perceptionCapability(PerceptionCapability capability, int maxSignals) {
            return stage(new PerceptionCognitiveStage(capability, maxSignals));
        }
```

No logic is added to `execute`: the cycle owns the source rules.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS (all existing tests plus the new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/monada/neuron/host/NeuronRuntime.java src/test/java/monada/neuron/host/PerceptionRuntimeTest.java
git commit -m "[Issue-41] feat(host): optional perception capability on NeuronRuntime

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Documentation and ADR 0024

**Files:**
- Create: `docs/adr/0024-host-perception-capability-boundary.md`
- Modify: `docs/architecture.md` (stage description near the `ACTION` paragraph, cycle semantics paragraph, "Host Runtime Boundary" flow and ownership text)
- Modify: `README.md` (embedding example under "Embedding Neuron in a Host Application")
- Modify: `src/main/java/monada/neuron/host/package-info.java` (mention perception)

**Interfaces:**
- Consumes: Tasks 1-4 final names.
- Produces: durable decision record and user-facing docs; no code behavior.

- [ ] **Step 1: Write ADR 0024**

Create `docs/adr/0024-host-perception-capability-boundary.md` using the structure of ADR 0022/0023 (Status: Accepted; Context; Decision; Alternatives Considered; Consequences; Follow-Up Work) with exactly this content:

```markdown
# ADR 0024: Host Perception Capability Boundary

## Status

Accepted

## Context

Monada Forge invokes Neuron for concrete external work items, but every executable input of a cycle is
already a Neuron-native `Signal`. A host had no explicit boundary for turning an external observation into
the initial bounded batch, which pushes it to invent `FrequencyState` values, encode domain payloads into
`Signal` (rejected by ADR 0005), or bypass the cognitive lifecycle. ADR 0023 supplies the request-scoped
`HostExecutionContext` that makes a perception adapter possible without identity on `Signal`, and ADR 0011
established the capability/outcome pattern for the ACTION position.

## Decision

Add a Neuron-owned perception capability in `monada.neuron.perception`, mirroring the Action boundary.

- **Contracts.** `PerceptionCapability.perceive(PerceptionRequest) -> PerceptionResult`.
  `PerceptionRequest(maxSignals, Optional<HostExecutionContext>)` carries an explicit positive bound and the
  opaque host context, never a payload. `PerceptionResult(status, signalLimit, signals)` is immutable,
  bounded, order-preserving, and holds only `SignalKind.OBSERVATION` signals. `PerceptionOutcome` keeps
  request and result together.
- **Status.** `SUCCEEDED` and `PARTIALLY_COMPLETED` require signals; `EMPTY`, `REJECTED`, `UNAVAILABLE`,
  `TIMED_OUT`, and `FAILED` forbid them. These are expected results, not exceptions; an unexpected adapter
  exception is an operational failure surfaced as `CognitiveCycleException` for the PERCEPTION stage.
- **Capability partial completion is not cycle truncation.** `PerceptionStatus.PARTIALLY_COMPLETED` is the
  adapter's report; `ObservationAdmission.TRUNCATED` records that the cycle budget admitted only a prefix.
  Budget truncation never rewrites a status, and the stage result keeps the produced count.
- **Stage.** `PerceptionCognitiveStage` occupies the PERCEPTION position and is a *source*:
  `CognitiveStage.isSource()`. `DeterministicCognitiveCycle` alone owns the source rules: a source must be
  the first stage, executes with empty initial signals, and a cycle with a source stage rejects non-empty
  initial signals with `IllegalArgumentException` before creating a context or running any stage.
  `NeuronRuntime.Builder.perceptionCapability(capability, maxSignals)` only adds the stage.
- **Host context.** The stage copies `CognitiveContext.hostContext()` into the request, as the Action stage
  does; no ambient state is introduced.
- **Mutual exclusion.** The stage and a PERCEPTION `AeonCognitiveStage` are alternatives for the single
  position; the existing duplicate-position check rejects both.
- **No prescribed encoder.** Neuron defines no universal text or task encoder and does not claim that the
  scalar `FrequencyState` is a semantic embedding; adapters may use any encoder, model, or deterministic
  fixture behind the capability. Stable Java 27 APIs only; no `--enable-preview`.

## Alternatives Considered

### Pre-cycle runtime step (`runtime.perceive(...)` then `execute`)

Rejected: perception would sit outside the canonical cycle, with no trace, budget, or
`CognitiveCycleResult`, bypassing the stage ordering the project requires.

### Request carried in `CycleInput` with a wrapper result

Rejected: it needs a result envelope that ADR 0022 deliberately avoided, and it makes the runtime, not the
cycle, responsible for the first stage's lifecycle.

### Encoding domain payloads or identifiers into `Signal`/`FrequencyState`

Rejected by ADR 0005 and by the domain-independence rule.

### `Map<String, Object>` payload or reflection-based adapters

Rejected: untyped, unbounded, and the first step toward a data-ingestion framework.

### Chaining host perception into a PERCEPTION Aeon

Deferred: a separate architectural decision.

## Consequences

- A host performs perception through one typed capability and needs no payload or identity on `Signal`.
- The cycle gains one default method and a first-stage source rule; plans without a source behave as before.
- A runtime with perception cannot also take initial signals, and cannot use a PERCEPTION Aeon, until
  chaining is decided.
- `perception` depends on `action` through `ObservationAdmission`; acceptable for now.
- Per cycle the Neuron boundary costs one request record and one signal-list copy, bounded by `maxSignals`.

## Follow-Up Work

- Decide how host perception chains into a PERCEPTION Aeon.
- Consider a neutral package for the shared admission enum if a third capability needs it.
- Add cancellation signalling only for a concrete adapter need, as a new typed field.
```

- [ ] **Step 2: Update `docs/architecture.md`**

a. Immediately before the sentence beginning "`ACTION` can instead use `ActionCognitiveStage`", insert this paragraph:

```markdown
`PERCEPTION` can instead use `PerceptionCognitiveStage`, backed by a Neuron-owned `PerceptionCapability`
(ADR 0024). It is a *source* stage: it asks the capability for at most `maxSignals` `OBSERVATION` signals,
resolved by the host adapter from the request-scoped `HostExecutionContext`, and exposes a
`PerceptionOutcome` with the admitted request and typed result. `SUCCEEDED` and `PARTIALLY_COMPLETED` carry
signals in adapter order; `EMPTY`, `REJECTED`, `UNAVAILABLE`, `TIMED_OUT`, and expected `FAILED` carry none
and end the cycle with `NO_SIGNALS` without failing it. The adapter's `PARTIALLY_COMPLETED` and the cycle's
`TRUNCATED` admission are independent: a budget never rewrites a reported status. A source must be the first
stage, runs with empty initial signals, and a cycle with a source stage rejects initial signals before
executing anything. A `PerceptionCognitiveStage` and an `AeonCognitiveStage` are alternatives for the
single `PERCEPTION` position. Neuron prescribes no universal text or task encoder, and the scalar
`FrequencyState` is not claimed to be a semantic embedding.
```

b. In the "Host Runtime Boundary" flow diagram replace the line `  -> prepares Signals from its own domain input        (host-owned)` with:

```text
  -> prepares Signals from its own domain input        (host-owned), or
  -> supplies a HostExecutionContext and a PerceptionCapability that resolves it into OBSERVATION signals
```

and replace `            -> optional ResonanceMemoryPort / ActionCapability` with `            -> optional PerceptionCapability / ResonanceMemoryPort / ActionCapability`.

c. At the end of the paragraph that ends "...no preview, incubator, or native type is part of the host-facing contract.", append: ` A runtime configured with \`perceptionCapability(capability, maxSignals)\` takes its initial signals from the adapter, so its executions supply a host context and no signals.`

- [ ] **Step 3: Update `README.md` and `package-info.java`**

In the README embedding snippet add one line before `.memoryPort(memoryPort, 8)`:

```java
        .perceptionCapability(perception, 16)         // optional: host observation -> up to 16 OBSERVATION signals
```

and after the first snippet add this paragraph and snippet:

```markdown
A runtime with a perception capability takes its initial signals from the host adapter, which resolves the
opaque `HostExecutionContext` ([ADR 0024](docs/adr/0024-host-perception-capability-boundary.md)); executions
therefore supply the context and no signals. Neuron does not prescribe a universal text or task encoder.

```java
CognitiveCycleResult perceived = runtime.execute(
        CycleInput.of(List.of()).withHostContext(hostContext));
```
```

In `host/package-info.java`, change the sentence to: "...{@link monada.neuron.host.NeuronRuntime} composes existing Neuron contracts once, including the optional perception, memory, and action capabilities, and executes bounded cognitive cycles ...".

- [ ] **Step 4: Verify**

Run: `./gradlew test`
Expected: PASS. Then `git grep -n "perceptionCapability" docs README.md src/main` and confirm the name matches Task 4 everywhere.

- [ ] **Step 5: Commit**

```bash
git add docs README.md src/main/java/monada/neuron/host/package-info.java
git commit -m "[Issue-41] docs: ADR 0024, architecture and embedding example for host perception

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** contracts (Task 1); outcome retention, stage result, status vs admission, fixture, stage tests (Task 3); source rules in the cycle with fail-fast and first-stage (Task 2); runtime wiring, isolation, mutual exclusion, failure propagation (Task 4); ADR/architecture/README (Task 5). Acceptance criteria map: typed contracts (1), stage + runtime + context (3, 4), positive bound (1), order (3), `OBSERVATION` only (1), explicit statuses (1, 3), no provider types (all), deterministic fixture (3), bounds/order/invalid/isolation/failure tests (1, 3, 4), low-level cycles unchanged (2, 4), documentation (5).
- **Type consistency:** `producedSignalCount`, `signalAdmission`, `admittedSignalCount()`, `withAdmittedSignalPrefix`, `perceptionCapability(capability, maxSignals)`, `isSource()` are used identically in every task.
- **Known gap by design:** cycle-level concurrency is out of scope; the parallel test uses separate runtimes because a runtime is not thread-safe (ADR 0022).
