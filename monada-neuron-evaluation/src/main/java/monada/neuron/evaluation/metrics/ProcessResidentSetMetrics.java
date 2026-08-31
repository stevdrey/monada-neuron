package monada.neuron.evaluation.metrics;

import java.util.OptionalLong;

/**
 * Resident-set telemetry captured around an evaluation measurement phase.
 *
 * <p>Linux exposes this information through {@code /proc/self/status}. Other platforms report
 * the metric as unavailable instead of estimating it from Java heap counters.
 */
public record ProcessResidentSetMetrics(Source source, long beforeBytes, long afterBytes) {

    public enum Source {
        PROC_SELF_STATUS,
        UNAVAILABLE
    }

    public ProcessResidentSetMetrics {
        if (source == null) {
            throw new NullPointerException("source must not be null");
        }
    }

    public static ProcessResidentSetMetrics fromReadings(OptionalLong before, OptionalLong after) {
        if (before.isPresent() && after.isPresent()) {
            return new ProcessResidentSetMetrics(Source.PROC_SELF_STATUS, before.getAsLong(), after.getAsLong());
        }
        return unavailable();
    }

    public static ProcessResidentSetMetrics unavailable() {
        return new ProcessResidentSetMetrics(Source.UNAVAILABLE, -1L, -1L);
    }

    public boolean isAvailable() {
        return source != Source.UNAVAILABLE;
    }

    public long deltaBytes() {
        return isAvailable() ? afterBytes - beforeBytes : -1L;
    }
}
