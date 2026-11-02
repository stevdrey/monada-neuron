package consumer.forge;

import monada.neuron.routing.RoutingDecision;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** JUnit side unwrapping of the host's outcomes; the inputs themselves live in {@link SampleInputs}. */
final class Fixtures {

    private Fixtures() {
    }

    static Advice advised(Readiness readiness) {
        return assertInstanceOf(Readiness.Advised.class, readiness).advice();
    }

    static RoutingDecision.Selected selected(Advice advice) {
        return assertInstanceOf(RoutingDecision.Selected.class, advice.decision());
    }
}
