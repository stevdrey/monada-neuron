package consumer.host;

import com.monada.core.execution.ObservationState;

import java.util.Objects;

/**
 * What the host's fake worker reports for one routed execution, and the host's own acceptance judgment.
 *
 * <p>The worker produces evidence; it never decides acceptance. {@code accepted} is the host's judgment and is
 * recorded as a validated outcome, so a host that accepts without passing evidence is visible to Neuron's quality
 * gates instead of being trusted.
 *
 * @param tests the TESTS evidence the host's runner produced
 * @param security the SECURITY evidence the host's runner produced
 * @param accepted whether the host accepted the result
 */
public record WorkerReport(ObservationState tests, ObservationState security, boolean accepted) {

    public WorkerReport {
        Objects.requireNonNull(tests, "tests");
        Objects.requireNonNull(security, "security");
    }

    /** Both mandatory gates pass and the host accepts. */
    public static WorkerReport passing() {
        return new WorkerReport(ObservationState.PASS, ObservationState.PASS, true);
    }

    /** Tests fail and the host rejects. */
    public static WorkerReport failingTests() {
        return new WorkerReport(ObservationState.FAIL, ObservationState.PASS, false);
    }

    /** The host accepts although the tests produced no verdict: an acceptance without quality evidence. */
    public static WorkerReport acceptedWithoutTestVerdict() {
        return new WorkerReport(ObservationState.UNKNOWN, ObservationState.PASS, true);
    }
}
