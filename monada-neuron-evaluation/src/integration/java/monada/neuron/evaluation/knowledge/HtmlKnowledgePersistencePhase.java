package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Entrypoint of one persistence phase, run by {@link HtmlKnowledgePersistenceRunner} in its own JVM so that the
 * {@code reopen} phase shares no heap, handle or cache with the {@code seed} phase that wrote the root.
 *
 * <p>Arguments: {@code seed|reopen <store-root> <work-dir>}. {@code seed} imports the corpus into the fresh root and
 * writes the manifest, its recall snapshot and its result to the work directory. {@code reopen} reads those files,
 * observes the existing root with new handles and writes its own snapshot and result. Each result also records the
 * phase JVM's own environment ({@code jvm.*}), because its timings were measured there and not in the orchestrator.
 * Exit status: {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected
 * input (for example a root without a Store manifest).
 */
public final class HtmlKnowledgePersistencePhase {

    static final String SEED = "seed";
    static final String REOPEN = "reopen";

    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgePersistence persistence;
    private final WorkDirectory work;

    private HtmlKnowledgePersistencePhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.persistence = new HtmlKnowledgePersistence(corpus);
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !(args[0].equals(SEED) || args[0].equals(REOPEN))) {
            System.err.println("Usage: seed|reopen <store-root> <work-dir>");
            System.exit(2);
            return;
        }
        int status;
        try {
            status = new HtmlKnowledgePersistencePhase(Path.of(args[2])).run(args[0], Path.of(args[1]));
        } catch (IngestionInputException e) {
            System.err.println("Persistence input rejected: " + e.getMessage());
            status = 2;
        }
        System.exit(status);
    }

    private int run(String phase, Path root) {
        Result result;
        if (phase.equals(SEED)) {
            var seeded = persistence.seed(root);
            work.write(work.manifest(), seeded.manifest().toTsv());
            work.write(work.snapshot(SEED), seeded.observation().snapshot().toTsv());
            result = new Result(seeded.checks(), environment(seeded.observation().metadata()));
        } else {
            var manifest = IngestionManifest.parseTsv(work.read(work.manifest()), corpus.version(),
                    corpus.corpusHash()).requireComplete(corpus);
            var seededSnapshot = Snapshot.parseTsv(work.read(work.snapshot(SEED)));
            var observation = persistence.reopen(root, manifest, seededSnapshot);
            work.write(work.snapshot(REOPEN), observation.snapshot().toTsv());
            result = new Result(observation.checks(), environment(observation.metadata()));
        }
        work.write(work.result(phase), result.render());
        return result.checks().stream().allMatch(Check::passed) ? 0 : 1;
    }

    /** Returns {@code metadata} followed by this phase JVM's process id and environment ({@code jvm.*}). */
    static Map<String, String> environment(Map<String, String> metadata) {
        var all = new LinkedHashMap<>(metadata);
        var environment = EnvironmentMetadata.current();
        all.put("pid", Long.toString(ProcessHandle.current().pid()));
        all.put("jvm.version", environment.javaVendor() + " " + environment.javaVersion() + " ("
                + environment.jvmName() + ")");
        all.put("jvm.arguments", String.join(" ", environment.jvmArguments()));
        all.put("jvm.availableProcessors", Integer.toString(environment.availableProcessors()));
        all.put("jvm.maxMemoryBytes", Long.toString(environment.maxMemoryBytes()));
        all.put("jvm.garbageCollectors", String.join(", ", environment.garbageCollectors()));
        return all;
    }

    /**
     * The files one run exchanges between the orchestrator and its phase JVMs.
     *
     * @param path the run's temporary work directory
     */
    record WorkDirectory(Path path) {

        WorkDirectory {
            Objects.requireNonNull(path, "path must not be null");
        }

        /** Returns the manifest file written by the seed phase. */
        Path manifest() {
            return path.resolve("manifest.tsv");
        }

        /** Returns the manifest file written by {@code phase}; the seed's is {@link #manifest()}. */
        Path manifest(String phase) {
            return phase.equals("seed") ? manifest() : path.resolve(phase + "-manifest.tsv");
        }

        /** Returns the labels-free query workload file the judged recall orchestrator writes for its phases. */
        Path workload() {
            return path.resolve("queries.tsv");
        }

        /** Returns the snapshot file of {@code phase}. */
        Path snapshot(String phase) {
            return path.resolve(phase + "-recall.tsv");
        }

        /** Returns the result file of {@code phase}. */
        Path result(String phase) {
            return path.resolve(phase + "-result.tsv");
        }

        String read(Path file) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
        }

        void write(Path file, String content) {
            try {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + file, e);
            }
        }
    }

    /**
     * A phase result as written by one JVM and read by the orchestrator.
     *
     * @param checks verdicts in phase order
     * @param metadata exploratory values and the environment of the phase JVM
     */
    record Result(List<Check> checks, Map<String, String> metadata) {

        Result {
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }

        /**
         * Renders {@code check\tname\tpassed\tdetail} and {@code meta\tkey\tvalue} lines, then the terminator
         * {@code end\t<checks>\t<metadata>}; tabs and newlines in values become spaces. The terminator lets a reader
         * tell a complete result from an empty or truncated file.
         */
        String render() {
            var sb = new StringBuilder();
            for (var check : checks) {
                sb.append("check\t").append(check.name()).append('\t').append(check.passed()).append('\t')
                        .append(flat(check.detail())).append('\n');
            }
            metadata.forEach((key, value) -> sb.append("meta\t").append(key).append('\t').append(flat(value))
                    .append('\n'));
            return sb.append("end\t").append(checks.size()).append('\t').append(metadata.size()).append('\n')
                    .toString();
        }

        /**
         * Parses {@link #render} output strictly: the terminator must be the last line and match the counts, and at
         * least one check must be present, so an empty or truncated file is rejected.
         */
        static Result parse(String text) {
            var checks = new ArrayList<Check>();
            var metadata = new LinkedHashMap<String, String>();
            var lines = text.lines().toList();
            if (lines.isEmpty() || !lines.getLast().startsWith("end\t")) {
                throw new IllegalArgumentException("phase result is empty or truncated (no end line)");
            }
            var end = lines.getLast().split("\t", -1);
            for (var line : lines.subList(0, lines.size() - 1)) {
                var columns = line.split("\t", -1);
                if (columns.length == 4 && columns[0].equals("check")
                        && (columns[2].equals("true") || columns[2].equals("false"))) {
                    checks.add(new Check(columns[1], Boolean.parseBoolean(columns[2]), columns[3]));
                } else if (columns.length == 3 && columns[0].equals("meta")) {
                    metadata.put(columns[1], columns[2]);
                } else {
                    throw new IllegalArgumentException("malformed phase result line: " + line);
                }
            }
            if (end.length != 3 || !end[1].equals(Integer.toString(checks.size()))
                    || !end[2].equals(Integer.toString(metadata.size())) || checks.isEmpty()) {
                throw new IllegalArgumentException("phase result is incomplete: end line " + lines.getLast()
                        + " after " + checks.size() + " checks and " + metadata.size() + " metadata lines");
            }
            return new Result(checks, metadata);
        }

        private String flat(String value) {
            return Objects.requireNonNull(value).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }
    }
}
