package consumer;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.UUID;

/** Minimal host that embeds Neuron through the public host API only. */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static CognitiveCycleResult runOneCycle() {
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(new CognitiveBudget(100, 100, 100))
                .build();
        var input = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));
        return runtime.execute(List.of(input));
    }
}
