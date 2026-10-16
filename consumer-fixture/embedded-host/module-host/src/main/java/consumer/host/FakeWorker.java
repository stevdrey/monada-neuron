package consumer.host;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Authorized fake worker owned by the host: no provider, credential, network or wall clock. Its result for a route
 * is scripted, and it completes asynchronously from the host's point of view: {@link #submit} only queues the work,
 * and the report is collected on a later host tick with {@link #complete}.
 */
public final class FakeWorker {

    /** Bound on work in flight; a host that never collects its results is a caller error, not a growing queue. */
    public static final int MAX_IN_FLIGHT = 8;

    private final Map<String, WorkerReport> scripted;
    private final Map<String, WorkerReport> inFlight = new HashMap<>();

    /** @param scripted the report per route id; a route without a script is not authorized for this worker */
    public FakeWorker(Map<String, WorkerReport> scripted) {
        this.scripted = Map.copyOf(scripted);
    }

    /** Queues the routed execution; nothing is reported until {@link #complete}. */
    public void submit(String executionId, String routeId) {
        var report = scripted.get(routeId);
        if (report == null) {
            throw new IllegalArgumentException("route " + routeId + " is not authorized for this worker");
        }
        if (inFlight.size() == MAX_IN_FLIGHT) {
            throw new IllegalStateException("more than " + MAX_IN_FLIGHT + " executions in flight");
        }
        if (inFlight.putIfAbsent(executionId, report) != null) {
            throw new IllegalStateException("execution " + executionId + " is already in flight");
        }
    }

    /** Collects the delayed report of a previously submitted execution. */
    public WorkerReport complete(String executionId) {
        return Objects.requireNonNull(inFlight.remove(executionId), () -> "nothing in flight for " + executionId);
    }
}
