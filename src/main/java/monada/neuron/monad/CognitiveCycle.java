package monada.neuron.monad;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.signal.Signal;

import java.util.List;

/** Executes one bounded cognitive lifecycle for a registered Primary Monad. */
@FunctionalInterface
public interface CognitiveCycle {

    /** Executes one cycle with ordered initial signals and an explicit context budget. */
    CognitiveCycleResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveBudget budget);
}
