package monada.neuron.context;

import java.util.List;
import java.util.Objects;

/**
 * Immutable diagnostic and result snapshot produced when a cognitive cycle completes.
 *
 * <p>The snapshot owns the retained references after completion; the originating context clears
 * its mutable buffers and cannot be reused.
 */
public record CognitiveCycleSnapshot(
        CognitiveCycleOutcome outcome,
        CognitiveBudget budget,
        int processedSteps,
        int acceptedSignals,
        boolean stepBudgetExhausted,
        boolean signalBudgetExhausted,
        boolean traceBudgetExhausted,
        List<CognitiveSignalOccurrence> signalOccurrences,
        List<CognitiveAeonResult> aeonResults,
        List<CognitiveTraceEntry> traceEntries,
        long omittedTraceEntries) {

    /** Validates counters and snapshots all retained cycle data. */
    public CognitiveCycleSnapshot {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        if (processedSteps < 0 || processedSteps > budget.maxSteps()) {
            throw new IllegalArgumentException(
                    "processedSteps must be in [0, maxSteps], got: " + processedSteps);
        }
        if (acceptedSignals < 0 || acceptedSignals > budget.maxSignals()) {
            throw new IllegalArgumentException(
                    "acceptedSignals must be in [0, maxSignals], got: " + acceptedSignals);
        }
        if (stepBudgetExhausted && processedSteps != budget.maxSteps()) {
            throw new IllegalArgumentException(
                    "a step-budget exhaustion requires all step capacity to be used");
        }
        if (signalBudgetExhausted && acceptedSignals != budget.maxSignals()) {
            throw new IllegalArgumentException(
                    "a signal-budget exhaustion requires all signal capacity to be used");
        }
        if (omittedTraceEntries < 0) {
            throw new IllegalArgumentException(
                    "omittedTraceEntries must be non-negative, got: " + omittedTraceEntries);
        }
        if (traceBudgetExhausted != (omittedTraceEntries > 0)) {
            throw new IllegalArgumentException(
                    "traceBudgetExhausted must match whether trace entries were omitted");
        }

        signalOccurrences = List.copyOf(Objects.requireNonNull(
                signalOccurrences,
                "signalOccurrences must not be null"));
        aeonResults = List.copyOf(Objects.requireNonNull(aeonResults, "aeonResults must not be null"));
        traceEntries = List.copyOf(Objects.requireNonNull(
                traceEntries,
                "traceEntries must not be null"));

        if (acceptedSignals != signalOccurrences.size()) {
            throw new IllegalArgumentException(
                    "acceptedSignals must equal the retained signal occurrence count");
        }
        if (aeonResults.size() > processedSteps) {
            throw new IllegalArgumentException(
                    "completed Aeon results cannot exceed completed processing steps");
        }
        if (traceEntries.size() > budget.maxTraceEntries()) {
            throw new IllegalArgumentException(
                    "trace entry count must not exceed maxTraceEntries");
        }
    }
}
