package monada.neuron.evaluation.knowledge;

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Best-effort host and Store-root identity that the protocol requires in every report ("Required report fields")
 * beyond what {@code EnvironmentMetadata} records: CPU model, total physical memory, a hashed host id, and the
 * storage type and file system of the Store root. A value that cannot be read is recorded as
 * {@code unknown (<reason>)}; reading it never fails a run. The plain host name is never written.
 */
final class RunEnvironment {

    /** Salt of {@code env.hostId}, so the id cannot be matched against plain host-name hashes from elsewhere. */
    static final String HOST_ID_SALT = "monada-neuron-host-v1:";

    private static final int MAX_CPUINFO_BYTES = 256 * 1024;
    private static final int HOST_ID_HEX_LENGTH = 16;

    private final Path cpuInfo;
    private final Path blockDevices;

    /** Reads the running host's {@code /proc/cpuinfo} and {@code /sys/class/block}. */
    RunEnvironment() {
        this(Path.of("/proc/cpuinfo"), Path.of("/sys/class/block"));
    }

    /** Reads the given files instead, for tests. */
    RunEnvironment(Path cpuInfo, Path blockDevices) {
        this.cpuInfo = Objects.requireNonNull(cpuInfo, "cpuInfo must not be null");
        this.blockDevices = Objects.requireNonNull(blockDevices, "blockDevices must not be null");
    }

    /** Records {@code env.cpuModel}, {@code env.totalPhysicalMemoryBytes} and {@code env.hostId}. */
    void describeHost(Map<String, String> metadata) {
        metadata.put("env.cpuModel", cpuModel());
        metadata.put("env.totalPhysicalMemoryBytes", totalPhysicalMemory());
        metadata.put("env.hostId", hostName().map(RunEnvironment::hostId).orElse("unknown (host name unavailable)"));
    }

    /**
     * Records {@code <prefix>.fileSystem} and {@code <prefix>.storageType} of the file store holding {@code root}, or
     * its nearest existing ancestor when the root does not exist (yet or any more).
     */
    void describeStoreRoot(String prefix, Path root, Map<String, String> metadata) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        var existing = existingAncestor(root);
        if (existing == null) {
            metadata.put(prefix + ".fileSystem", "unknown (no existing ancestor)");
            metadata.put(prefix + ".storageType", "unknown (no existing ancestor)");
            return;
        }
        try {
            var store = Files.getFileStore(existing);
            metadata.put(prefix + ".fileSystem", store.type());
            metadata.put(prefix + ".storageType", storageType(store.type(), store.name()));
        } catch (IOException | RuntimeException e) {
            metadata.put(prefix + ".fileSystem", "unknown (" + e.getClass().getSimpleName() + ")");
            metadata.put(prefix + ".storageType", "unknown (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Returns whether {@code first} and {@code second} (or their nearest existing ancestors) live on the same file
     * store, so latency rows measured on them are comparable; empty when either cannot be resolved.
     */
    Optional<Boolean> sameFileStore(Path first, Path second) {
        try {
            var a = existingAncestor(first);
            var b = existingAncestor(second);
            if (a == null || b == null) {
                return Optional.empty();
            }
            return Optional.of(Files.getFileStore(a).equals(Files.getFileStore(b)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Path existingAncestor(Path path) {
        var existing = path.toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    /** Returns the first {@code model name} of the CPU information file. */
    String cpuModel() {
        try (var input = Files.newInputStream(cpuInfo)) {
            var text = new String(input.readNBytes(MAX_CPUINFO_BYTES), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .filter(model -> !model.isEmpty())
                    .findFirst()
                    .orElse("unknown (no model name in " + cpuInfo + ")");
        } catch (IOException | RuntimeException e) {
            return "unknown (" + cpuInfo + " unreadable)";
        }
    }

    /**
     * Classifies a file store: {@code memory-backed} for {@code tmpfs}/{@code ramfs}, otherwise {@code ssd} or
     * {@code hdd} from the block device's {@code queue/rotational} flag (a partition falls back to its parent device),
     * otherwise {@code unknown}.
     */
    String storageType(String type, String name) {
        var fileSystem = type.toLowerCase(Locale.ROOT);
        if (fileSystem.equals("tmpfs") || fileSystem.equals("ramfs")) {
            return "memory-backed (" + fileSystem + ")";
        }
        if (!name.startsWith("/dev/")) {
            return "unknown (device " + name + ")";
        }
        var device = Path.of(name).getFileName().toString();
        return rotational(blockDevices.resolve(device))
                .map(flag -> flag ? "hdd (rotational)" : "ssd (non-rotational)")
                .orElse("unknown (no rotational flag for " + device + ")");
    }

    /** Returns the salted, truncated SHA-256 of {@code hostName}. */
    static String hostId(String hostName) {
        return CorpusDigests.sha256(HOST_ID_SALT + hostName).substring(0, HOST_ID_HEX_LENGTH);
    }

    private Optional<Boolean> rotational(Path device) {
        var direct = flag(device.resolve("queue/rotational"));
        if (direct.isPresent()) {
            return direct;
        }
        try {
            var parent = device.toRealPath().getParent();
            return parent == null ? Optional.empty() : flag(parent.resolve("queue/rotational"));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> flag(Path file) {
        try {
            var value = Files.readString(file, StandardCharsets.US_ASCII).strip();
            return value.equals("1") ? Optional.of(true) : value.equals("0") ? Optional.of(false) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private String totalPhysicalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            return Long.toString(os.getTotalMemorySize());
        }
        return "unknown (no com.sun.management.OperatingSystemMXBean)";
    }

    private Optional<String> hostName() {
        try {
            return Optional.of(InetAddress.getLocalHost().getHostName());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
