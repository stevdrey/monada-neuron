package monada.neuron.context;

import monada.neuron.action.ActionStatus;
import monada.neuron.aeon.AeonInputResult;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Mutable, bounded working state owned by exactly one active cognitive cycle.
 *
 * <p>This context is deliberately sequential and not thread-safe. It owns no persistence API,
 * durable identity, wall-clock data, or exception object. Its mutable buffers are released on
 * completion or discard and the instance cannot be reset or reused.
 */
public final class CognitiveContext implements AutoCloseable {

    private static final int INITIAL_BUFFER_CAPACITY = 16;
    private static final long TRACE_ENTRY_OMITTED = -1L;

    private final CognitiveBudget budget;
    private final Optional<HostExecutionContext> hostContext;
    private final ArrayList<CognitiveSignalOccurrence> signalOccurrences;
    private final ArrayList<CognitiveAeonResult> aeonResults;
    private final ArrayList<CognitiveTraceEntry> traceEntries;

    private CognitiveLifecycle lifecycle = CognitiveLifecycle.ACTIVE;
    private int processedSteps;
    private int acceptedSignals;
    private boolean stepBudgetExhausted;
    private boolean signalBudgetExhausted;
    private long nextSignalSequence;
    private long nextTraceSequence;
    private long omittedTraceEntries;

    /** Creates an active context with the supplied explicit bounds. */
    public CognitiveContext(CognitiveBudget budget) {
        this(budget, Optional.empty());
    }

    /**
     * Creates an active context that also carries the host execution it belongs to.
     *
     * <p>The host context has exactly this cycle's lifetime and is exposed only to stages that call
     * external capabilities; it is never recorded in the trace, snapshot, or result.
     */
    public CognitiveContext(CognitiveBudget budget, Optional<HostExecutionContext> hostContext) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.hostContext = Objects.requireNonNull(hostContext, "hostContext must not be null");
        this.signalOccurrences = new ArrayList<>(initialCapacity(budget.maxSignals()));
        this.aeonResults = new ArrayList<>(initialCapacity(budget.maxSteps()));
        this.traceEntries = new ArrayList<>(initialCapacity(budget.maxTraceEntries()));
    }

    /** Returns the immutable resource budget for this cycle. */
    public CognitiveBudget budget() {
        return budget;
    }

    /** Returns the host execution this cycle runs for, or empty for a host-less low-level cycle. */
    public Optional<HostExecutionContext> hostContext() {
        return hostContext;
    }

    /** Returns the lifecycle state; the instance is never reset to ACTIVE. */
    public CognitiveLifecycle lifecycle() {
        return lifecycle;
    }

    /** Returns whether this context may still accept execution work. */
    public boolean isActive() {
        return lifecycle == CognitiveLifecycle.ACTIVE;
    }

    /** Returns the number of successful processor calls recorded for this cycle. */
    public int processedSteps() {
        return processedSteps;
    }

    /** Returns the number of accepted signal occurrences recorded for this cycle. */
    public int acceptedSignals() {
        return acceptedSignals;
    }

    /** Returns whether a step limit suppressed cycle work. */
    public boolean stepBudgetExhausted() {
        return stepBudgetExhausted;
    }

    /** Returns whether a signal limit suppressed a signal occurrence. */
    public boolean signalBudgetExhausted() {
        return signalBudgetExhausted;
    }

    /** Returns whether trace retention omitted one or more events. */
    public boolean traceBudgetExhausted() {
        return omittedTraceEntries > 0;
    }

    /**
     * Returns whether a new node-processing call may start.
     *
     * <p>A false result records that the global step budget, rather than a per-propagation limit,
     * suppressed work.
     */
    public boolean hasRemainingStepCapacity() {
        requireActive();
        if (processedSteps < budget.maxSteps()) {
            return true;
        }
        stepBudgetExhausted = true;
        return false;
    }

    /** Records one accepted propagation input occurrence. */
    public OptionalLong tryRecordInputSignal(UUID startNodeId, Signal signal) {
        Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        var sequence = tryReserveSignalSequence();
        if (sequence.isEmpty()) {
            return sequence;
        }
        signalOccurrences.add(new CognitiveSignalOccurrence.Input(
                sequence.getAsLong(),
                startNodeId,
                signal));
        return sequence;
    }

    /** Records one accepted processor-emission occurrence. */
    public OptionalLong tryRecordEmittedSignal(UUID sourceNodeId, Signal signal) {
        Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        var sequence = tryReserveSignalSequence();
        if (sequence.isEmpty()) {
            return sequence;
        }
        signalOccurrences.add(new CognitiveSignalOccurrence.Emitted(
                sequence.getAsLong(),
                sourceNodeId,
                signal));
        return sequence;
    }

    /** Records one accepted route delivery that will be enqueued by the runtime. */
    public OptionalLong tryRecordDeliveredSignal(
            UUID sourceNodeId,
            UUID targetNodeId,
            Signal signal) {
        Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        var sequence = tryReserveSignalSequence();
        if (sequence.isEmpty()) {
            return sequence;
        }
        signalOccurrences.add(new CognitiveSignalOccurrence.Delivered(
                sequence.getAsLong(),
                sourceNodeId,
                targetNodeId,
                signal));
        return sequence;
    }

    /** Records one Signal admitted as input to a non-Aeon cognitive stage. */
    public OptionalLong tryRecordCognitiveStageInputSignal(
            CognitiveStageKind stage,
            Signal signal) {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        var sequence = tryReserveSignalSequence();
        if (sequence.isEmpty()) {
            return sequence;
        }
        signalOccurrences.add(new CognitiveSignalOccurrence.StageInput(
                sequence.getAsLong(),
                stage,
                signal));
        return sequence;
    }

    /** Records one Signal admitted as output from a non-Aeon cognitive stage. */
    public OptionalLong tryRecordCognitiveStageOutputSignal(
            CognitiveStageKind stage,
            Signal signal) {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        var sequence = tryReserveSignalSequence();
        if (sequence.isEmpty()) {
            return sequence;
        }
        signalOccurrences.add(new CognitiveSignalOccurrence.StageOutput(
                sequence.getAsLong(),
                stage,
                signal));
        return sequence;
    }

    /** Records one successfully completed NodeProcessor call and its deterministic trace event. */
    public int recordCompletedStep(
            UUID nodeId,
            long inputSignalSequence,
            int emittedSignalCount) {
        requireActive();
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (processedSteps >= budget.maxSteps()) {
            throw new IllegalStateException("step budget is exhausted");
        }
        if (inputSignalSequence < 0 || inputSignalSequence >= acceptedSignals) {
            throw new IllegalArgumentException(
                    "inputSignalSequence must identify an accepted signal, got: "
                            + inputSignalSequence);
        }
        if (emittedSignalCount < 0) {
            throw new IllegalArgumentException(
                    "emittedSignalCount must be non-negative, got: " + emittedSignalCount);
        }

        processedSteps++;
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.NodeProcessed(
                            nodeId,
                            inputSignalSequence,
                            processedSteps,
                            emittedSignalCount)));
        }
        return processedSteps;
    }

    /** Records the start of one explicit Aeon input. */
    public void recordAeonInputStarted(UUID aeonId, UUID startNodeId) {
        requireActive();
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.AeonInputStarted(aeonId, startNodeId)));
        }
    }

    /** Records the start of one configured Monad cognitive stage. */
    public void recordCognitiveStageStarted(CognitiveStageKind stage) {
        requireActive();
        Objects.requireNonNull(stage, "stage must not be null");
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.CognitiveStageStarted(stage)));
        }
    }

    /** Records the successful or locally truncated completion of one cognitive stage. */
    public void recordCognitiveStageCompleted(
            CognitiveStageKind stage,
            int inputSignalCount,
            int outputSignalCount,
            CognitiveStageStatus status) {
        requireActive();
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (inputSignalCount < 0) {
            throw new IllegalArgumentException(
                    "inputSignalCount must be non-negative, got: " + inputSignalCount);
        }
        if (outputSignalCount < 0) {
            throw new IllegalArgumentException(
                    "outputSignalCount must be non-negative, got: " + outputSignalCount);
        }
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.CognitiveStageCompleted(
                            stage,
                            inputSignalCount,
                            outputSignalCount,
                            status)));
        }
    }

    /** Records an operational failure before the cycle owner completes a FAILURE snapshot. */
    public void recordCognitiveStageFailed(CognitiveStageKind stage) {
        requireActive();
        Objects.requireNonNull(stage, "stage must not be null");
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.CognitiveStageFailed(stage)));
        }
    }

    /** Records one policy-accepted route that was admitted to the work queue. */
    public void recordAcceptedRoute(
            UUID sourceNodeId,
            UUID targetNodeId,
            long emittedSignalSequence,
            long deliveredSignalSequence) {
        requireActive();
        Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        validateAcceptedSignalSequence(emittedSignalSequence, "emittedSignalSequence");
        validateAcceptedSignalSequence(deliveredSignalSequence, "deliveredSignalSequence");
        if (emittedSignalSequence >= deliveredSignalSequence) {
            throw new IllegalArgumentException(
                    "an emitted signal must precede its delivered occurrence");
        }
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.SignalRouted(
                            sourceNodeId,
                            targetNodeId,
                            emittedSignalSequence,
                            deliveredSignalSequence)));
        }
    }

    /** Records completion or budget truncation of one explicit Aeon input. */
    public void recordAeonInputCompleted(
            UUID aeonId,
            UUID startNodeId,
            int inputProcessedSteps,
            boolean stepLimitReached,
            boolean hopLimitReached,
            boolean contextLimitReached) {
        requireActive();
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        if (inputProcessedSteps < 0) {
            throw new IllegalArgumentException(
                    "inputProcessedSteps must be non-negative, got: " + inputProcessedSteps);
        }
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.AeonInputCompleted(
                            aeonId,
                            startNodeId,
                            inputProcessedSteps,
                            stepLimitReached,
                            hopLimitReached,
                            contextLimitReached)));
        }
    }

    /** Records one adaptation decision applied to a node during the cycle. */
    public void recordNodeAdapted(
            UUID nodeId,
            boolean adapted,
            FrequencyState previousState,
            FrequencyState newState,
            double previousEnergy,
            double newEnergy) {
        requireActive();
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        if (!Double.isFinite(previousEnergy) || previousEnergy < 0) {
            throw new IllegalArgumentException(
                    "previousEnergy must be non-negative and finite, got: " + previousEnergy);
        }
        if (!Double.isFinite(newEnergy) || newEnergy < 0) {
            throw new IllegalArgumentException(
                    "newEnergy must be non-negative and finite, got: " + newEnergy);
        }
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(
                    traceSequence,
                    new CognitiveTraceEvent.NodeAdapted(
                            nodeId,
                            adapted,
                            previousState,
                            newState,
                            previousEnergy,
                            newEnergy)));
        }
    }

    /** Records that a prior cycle's feedback artifact was consumed by this cycle's adaptation stage. */
    public void recordFeedbackConsumed(
            long originCycleOrdinal,
            ActionStatus sourceStatus,
            FeedbackDisposition disposition,
            int entryCount,
            int adaptedCount,
            int unchangedCount,
            int ineligibleCount) {
        requireActive();
        var event = new CognitiveTraceEvent.FeedbackConsumed(
                originCycleOrdinal, sourceStatus, disposition, entryCount, adaptedCount, unchangedCount,
                ineligibleCount);
        long traceSequence = reserveTraceSequence();
        if (traceSequence != TRACE_ENTRY_OMITTED) {
            traceEntries.add(new CognitiveTraceEntry(traceSequence, event));
        }
    }

    /** Retains a completed result association; zero-step truncations are not retained as results. */
    public void recordAeonResult(UUID aeonId, AeonInputResult inputResult) {
        requireActive();
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        Objects.requireNonNull(inputResult, "inputResult must not be null");
        if (inputResult.propagationResult().processedSteps() == 0) {
            return;
        }
        if (aeonResults.size() >= processedSteps) {
            throw new IllegalStateException(
                    "completed Aeon results cannot exceed completed context steps");
        }
        aeonResults.add(new CognitiveAeonResult(aeonId, inputResult));
    }

    /**
     * Produces the immutable result for this cycle and releases all mutable retained references.
     *
     * @param outcome owner-selected successful or failed completion outcome
     * @return immutable cycle snapshot
     */
    public CognitiveCycleSnapshot complete(CognitiveCycleOutcome outcome) {
        requireActive();
        var snapshot = new CognitiveCycleSnapshot(
                Objects.requireNonNull(outcome, "outcome must not be null"),
                budget,
                processedSteps,
                acceptedSignals,
                stepBudgetExhausted,
                signalBudgetExhausted,
                traceBudgetExhausted(),
                signalOccurrences,
                aeonResults,
                traceEntries,
                omittedTraceEntries);
        clearBuffers();
        lifecycle = CognitiveLifecycle.COMPLETED;
        return snapshot;
    }

    /** Discards an active cycle and releases retained references; completed contexts are unchanged. */
    @Override
    public void close() {
        if (lifecycle == CognitiveLifecycle.ACTIVE) {
            clearBuffers();
            lifecycle = CognitiveLifecycle.DISCARDED;
        }
    }

    private OptionalLong tryReserveSignalSequence() {
        requireActive();
        if (acceptedSignals == budget.maxSignals()) {
            signalBudgetExhausted = true;
            return OptionalLong.empty();
        }
        acceptedSignals++;
        return OptionalLong.of(nextSignalSequence++);
    }

    private long reserveTraceSequence() {
        var sequence = nextTraceSequence++;
        if (traceEntries.size() >= budget.maxTraceEntries()) {
            omittedTraceEntries++;
            return TRACE_ENTRY_OMITTED;
        }
        return sequence;
    }

    private void requireActive() {
        if (lifecycle != CognitiveLifecycle.ACTIVE) {
            throw new IllegalStateException("cognitive context is not active: " + lifecycle);
        }
    }

    private void validateAcceptedSignalSequence(long sequence, String name) {
        if (sequence < 0 || sequence >= acceptedSignals) {
            throw new IllegalArgumentException(
                    name + " must identify an accepted signal, got: " + sequence);
        }
    }

    private void clearBuffers() {
        signalOccurrences.clear();
        signalOccurrences.trimToSize();
        aeonResults.clear();
        aeonResults.trimToSize();
        traceEntries.clear();
        traceEntries.trimToSize();
    }

    private static int initialCapacity(int maximum) {
        return Math.min(maximum, INITIAL_BUFFER_CAPACITY);
    }
}
