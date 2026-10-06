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
