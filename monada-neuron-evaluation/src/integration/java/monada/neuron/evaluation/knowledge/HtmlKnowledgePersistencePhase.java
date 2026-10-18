package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;

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
 * observes the existing root with new handles and writes its own snapshot and result. Exit status: {@code 0} all
 * checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input (for example a root
 * without a Store manifest).
 */
public final class HtmlKnowledgePersistencePhase {

    static final String SEED = "seed";
    static final String REOPEN = "reopen";
    static final String MANIFEST_FILE = "manifest.tsv";

    private HtmlKnowledgePersistencePhase() {
    }

    /** Returns the snapshot file a phase writes into {@code work}. */
    static Path snapshotFile(Path work, String phase) {
        return work.resolve(phase + "-recall.tsv");
    }

    /** Returns the result file a phase writes into {@code work}. */
    static Path resultFile(Path work, String phase) {
        return work.resolve(phase + "-result.tsv");
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !(args[0].equals(SEED) || args[0].equals(REOPEN))) {
            System.err.println("Usage: seed|reopen <store-root> <work-dir>");
            System.exit(2);
            return;
        }
        var root = Path.of(args[1]);
        var work = Path.of(args[2]);
        var corpus = HtmlKnowledgeCorpus.bundled();
        var persistence = new HtmlKnowledgePersistence(corpus);
        List<Check> checks;
        Map<String, String> metadata;
        try {
            if (args[0].equals(SEED)) {
                var seeded = persistence.seed(root);
                write(work.resolve(MANIFEST_FILE), seeded.manifest().toTsv());
                write(snapshotFile(work, SEED), seeded.observation().snapshot().toTsv());
                checks = seeded.checks();
                metadata = seeded.observation().metadata();
            } else {
                var manifest = IngestionManifest.parseTsv(read(work.resolve(MANIFEST_FILE)), corpus.version(),
                        corpus.corpusHash());
                var seededSnapshot = Snapshot.parseTsv(read(snapshotFile(work, SEED)));
                var observation = persistence.reopen(root, manifest, seededSnapshot);
                write(snapshotFile(work, REOPEN), observation.snapshot().toTsv());
                checks = observation.checks();
                metadata = observation.metadata();
            }
        } catch (IngestionInputException e) {
            System.err.println("Persistence input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        var all = new LinkedHashMap<>(metadata);
        all.put("pid", Long.toString(ProcessHandle.current().pid()));
        write(resultFile(work, args[0]), Result.render(checks, all));
        System.exit(checks.stream().allMatch(Check::passed) ? 0 : 1);
    }

    /**
     * A phase result as written by one JVM and read by the orchestrator.
     *
     * @param checks verdicts in phase order
     * @param metadata exploratory values of the phase
     */
    record Result(List<Check> checks, Map<String, String> metadata) {

        Result {
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }

        /** Renders {@code check\tname\tpassed\tdetail} and {@code meta\tkey\tvalue} lines; tabs and newlines in values become spaces. */
        static String render(List<Check> checks, Map<String, String> metadata) {
            var sb = new StringBuilder();
            for (var check : checks) {
                sb.append("check\t").append(check.name()).append('\t').append(check.passed()).append('\t')
                        .append(flat(check.detail())).append('\n');
            }
            metadata.forEach((key, value) -> sb.append("meta\t").append(key).append('\t').append(flat(value))
                    .append('\n'));
            return sb.toString();
        }

        /** Parses {@link #render} output strictly. */
        static Result parse(String text) {
            var checks = new ArrayList<Check>();
            var metadata = new LinkedHashMap<String, String>();
            for (var line : text.lines().toList()) {
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
            return new Result(checks, metadata);
        }

        private static String flat(String value) {
            return Objects.requireNonNull(value).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
