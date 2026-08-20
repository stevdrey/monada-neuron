package monada.neuron.evaluation.metrics;

import java.lang.management.ManagementFactory;
import java.util.Objects;

/**
 * Captures immutable JVM and hardware runtime metadata for benchmark reproducibility.
 */
public record EnvironmentMetadata(
        String javaVersion,
        String javaVendor,
        String jvmName,
        String osName,
        String osArch,
        int availableProcessors,
        long maxMemoryBytes,
        long totalMemoryBytes) {

    public EnvironmentMetadata {
        Objects.requireNonNull(javaVersion, "javaVersion must not be null");
        Objects.requireNonNull(javaVendor, "javaVendor must not be null");
        Objects.requireNonNull(jvmName, "jvmName must not be null");
        Objects.requireNonNull(osName, "osName must not be null");
        Objects.requireNonNull(osArch, "osArch must not be null");
    }

    /** Captures current system environment metadata. */
    public static EnvironmentMetadata current() {
        var runtime = Runtime.getRuntime();
        return new EnvironmentMetadata(
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                runtime.availableProcessors(),
                runtime.maxMemory(),
                runtime.totalMemory());
    }
}
