package monada.neuron.memory;

import java.util.List;
import java.util.Objects;

/** Immutable bounded response whose result order and tie semantics belong to the adapter. */
public record ResonanceMemoryResponse(
        ResonanceMemoryStatus status,
        int resultLimit,
        List<ResonanceMemoryResult> results) {

    /** Validates the explicit bound and expected-failure result semantics. */
    public ResonanceMemoryResponse {
        Objects.requireNonNull(status, "status must not be null");
        if (resultLimit <= 0) {
            throw new IllegalArgumentException("resultLimit must be positive, got: " + resultLimit);
        }
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        if (results.size() > resultLimit) {
            throw new IllegalArgumentException(
                    "results must not exceed resultLimit: " + results.size() + " > " + resultLimit);
        }
        if (switch (status) {
            case UNAVAILABLE, TIMED_OUT, FAILED -> !results.isEmpty();
            case COMPLETE, PARTIAL -> false;
        }) {
            throw new IllegalArgumentException(status + " responses must not contain results");
        }
    }

    /** Returns this response with only the cycle-admitted ordered result prefix retained. */
    ResonanceMemoryResponse withAdmittedResultPrefix(int admittedResultCount) {
        if (admittedResultCount < 0 || admittedResultCount > results.size()) {
            throw new IllegalArgumentException(
                    "admittedResultCount must be in [0, results.size], got: " + admittedResultCount);
        }
        if (admittedResultCount == results.size()) {
            return this;
        }
        var admittedStatus = status == ResonanceMemoryStatus.COMPLETE
                ? ResonanceMemoryStatus.PARTIAL
                : status;
        return new ResonanceMemoryResponse(
                admittedStatus,
                resultLimit,
                results.subList(0, admittedResultCount));
    }
}
