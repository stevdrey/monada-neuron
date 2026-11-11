package monada.neuron.aeon.formal;

/**
 * One bounded workload: how many inputs, the coordinator's {@code maxParallelism}, and which inputs fail.
 *
 * @param inputCount number of Aeon inputs, at least one
 * @param maxParallelism the coordinator's window, at least two so the direct parallel path is taken
 * @param failureMask bit {@code i} set when input {@code i} throws
 */
record ScheduleWorkload(int inputCount, int maxParallelism, int failureMask) {

    ScheduleWorkload {
        if (inputCount < 1 || inputCount > 30) {
            throw new IllegalArgumentException("inputCount must be in [1, 30], got: " + inputCount);
        }
        if (maxParallelism < 2) {
            throw new IllegalArgumentException("maxParallelism must be at least 2, got: " + maxParallelism);
        }
        if (failureMask < 0 || failureMask >= (1 << inputCount)) {
            throw new IllegalArgumentException("failureMask must select inputs in [0, inputCount), got: "
                    + failureMask);
        }
    }

    boolean fails(int inputIndex) {
        return (failureMask & (1 << inputIndex)) != 0;
    }

    @Override
    public String toString() {
        var failing = new StringBuilder();
        for (int i = 0; i < inputCount; i++) {
            if (fails(i)) {
                failing.append(failing.isEmpty() ? "" : ",").append(i);
            }
        }
        return "inputs=" + inputCount + " maxParallelism=" + maxParallelism
                + " failing={" + failing + "}";
    }
}
