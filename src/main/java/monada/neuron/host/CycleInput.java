package monada.neuron.host;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.evolution.OutcomeFeedback;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable cycle-local input of one {@link NeuronRuntime} execution.
 *
 * <p>It holds only data that belongs to a single cycle, so the runtime composition itself stays
 * static: the ordered initial signals, an optional budget that overrides the runtime default, and the
 * optional {@link OutcomeFeedback} a caller carries forward from the previous cycle (ADR 0021), and the
 * optional {@link HostExecutionContext} that correlates this execution with the host's own work item
 * (ADR 0023). The host context lives for this execution only and is handed explicitly to action
 * capabilities through {@code ActionRequest}.
 *
 * @param signals ordered initial signals
 * @param budget budget for this execution, or empty to use the runtime's default
 * @param priorFeedback feedback derived from the previous cycle, or empty when there is none
 * @param hostContext host execution correlation for this cycle, or empty when there is none
 */
public record CycleInput(
        List<Signal> signals,
        Optional<CognitiveBudget> budget,
        Optional<OutcomeFeedback> priorFeedback,
        Optional<HostExecutionContext> hostContext) {

    /** Snapshots the signals and requires explicit, non-null optional values. */
    public CycleInput {
        signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(priorFeedback, "priorFeedback must not be null");
        Objects.requireNonNull(hostContext, "hostContext must not be null");
    }

    /** Creates an input without host context, preserving the ADR 0022 three-argument form. */
    public CycleInput(
            List<Signal> signals,
            Optional<CognitiveBudget> budget,
            Optional<OutcomeFeedback> priorFeedback) {
        this(signals, budget, priorFeedback, Optional.empty());
    }

    /** Creates an input with only signals: default budget and no prior feedback. */
    public static CycleInput of(List<Signal> signals) {
        return new CycleInput(signals, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Returns this input with a budget that overrides the runtime default for this execution. */
    public CycleInput withBudget(CognitiveBudget budget) {
        return new CycleInput(
                signals,
                Optional.of(Objects.requireNonNull(budget, "budget must not be null")),
                priorFeedback,
                hostContext);
    }

    /** Returns this input carrying the previous cycle's feedback. */
    public CycleInput withPriorFeedback(OutcomeFeedback feedback) {
        return new CycleInput(
                signals,
                budget,
                Optional.of(Objects.requireNonNull(feedback, "feedback must not be null")),
                hostContext);
    }

    /** Returns this input bound to the host execution it runs for. */
    public CycleInput withHostContext(HostExecutionContext hostContext) {
        return new CycleInput(
                signals,
                budget,
                priorFeedback,
                Optional.of(Objects.requireNonNull(hostContext, "hostContext must not be null")));
    }
}
