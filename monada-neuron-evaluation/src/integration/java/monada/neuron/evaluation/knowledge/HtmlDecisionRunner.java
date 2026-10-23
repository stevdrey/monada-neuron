package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;

/**
 * CLI of the HTML design-decision benchmark (Issue #112, protocol extension {@value HtmlDecisionExperiment#EXTENSION}).
 * It imports {@code html-kc-v1} with the {@code html-kd-v1} supplement into a fresh, temporary embedded Store root,
 * reopens it with a new handle and the production memory port, runs every {@code html-decision-v1} case through the
 * arms of {@link HtmlDecisionExperiment} and scores the rows against the frozen gold labels in this process, after the
 * experiment has finished. The experiment and the selector never receive the gold labels.
 *
 * <p>Options: {@code [--output-dir <dir>] [--store-root <dir>] [--generator test-double]}. The generator option runs
 * arm C with {@link TestDoubleAnswerGenerator}, labelled {@code TEST_DOUBLE}; without it arm C is reported as not
 * configured. No provider, model or network is used.
 *
 * <p>Outputs: {@code html-decision.json} (verdicts, metadata, the scored payload under {@code decisions} and the run's
 * {@code semanticDigest}), {@code html-decision.md} and {@code html-decision-v1-trace.tsv} (every raw row). The digest
 * covers the trace and the scored payload, which hold no time, pid or host value, so two runs over the same inputs have
 * the same digest. Exit status: {@code 0} all checks passed, {@code 1} a semantic check failed (fixture, provenance or
 * metric integrity, never an accuracy threshold), {@code 2} invalid usage or rejected input.
 */
public final class HtmlDecisionRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Design Decision Report";

    /** SHA-256 of the frozen, reviewed {@code html-decision-v1} gold file. */
    static final String FROZEN_GOLD_HASH = "a1db13ba8e98831ead4fcb9cc90c5be7f0369d93856090b34ddd6d61e5b79b39";

    static final String REPORT_JSON = "html-decision.json";
    static final String REPORT_MARKDOWN = "html-decision.md";
    static final String TRACE = HtmlDecisionCases.VERSION + "-trace.tsv";
    static final String GENERATOR_OPTION = "--generator";
    static final String TEST_DOUBLE = "test-double";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>] [--generator test-double]";

    private final Options options;
    private final Optional<GroundedAnswerGenerator> generator;
    private final String frozenHash;

    HtmlDecisionRunner(Options options, Optional<GroundedAnswerGenerator> generator, String frozenHash) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.frozenHash = Objects.requireNonNull(frozenHash, "frozenHash must not be null");
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param decisions the scored payload as JSON and Markdown, when the rows were scored
     * @param semanticDigest SHA-256 of the trace and the scored payload
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeRecallRunner.Rendered> decisions,
            String semanticDigest, Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
        }

        /** Returns the JSON report with the scored payload as {@code decisions}. */
        public String json() {
            return report.toJson(Map.of("decisions", decisions.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null"), "semanticDigest", string(semanticDigest)));
        }

        /** Returns the Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + "\nSemantic digest: `" + semanticDigest + "`\n"
                    + decisions.map(rendered -> "\n" + rendered.markdown()).orElse("");
        }
    }

    /**
     * Parsed CLI arguments.
     *
     * @param options the shared output and Store root options
     * @param generator whether arm C runs with the test double
     */
    record Arguments(Options options, boolean generator) {

        static Arguments parse(String[] args) {
            var rest = new ArrayList<String>();
            var generator = false;
            for (var i = 0; i < args.length; i++) {
                if (args[i].equals(GENERATOR_OPTION)) {
                    if (i + 1 >= args.length || !args[i + 1].equals(TEST_DOUBLE)) {
                        throw new IllegalArgumentException(GENERATOR_OPTION + " accepts only " + TEST_DOUBLE);
                    }
                    generator = true;
                    i++;
                } else {
                    rest.add(args[i]);
                }
            }
            return new Arguments(Options.parse(rest.toArray(String[]::new)), generator);
        }
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Outcome outcome;
        try {
            outcome = run(arguments.options(), arguments.generator()
                    ? Optional.of(new TestDoubleAnswerGenerator()) : Optional.empty());
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Decision input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Decision outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write decision outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic decision checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the benchmark with the bundled fixture. The temporary work directory, and the Store root unless
     * {@code --store-root} was given, are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus, the cases or the gold labels violate their contract
     */
    public static Outcome run(Options options, Optional<GroundedAnswerGenerator> generator) {
        return new HtmlDecisionRunner(options, generator, FROZEN_GOLD_HASH).execute();
    }

    /** Writes the reports and the trace into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var written = new ArrayList<Path>();
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outcome.outputDir().resolve(REPORT_JSON), outcome.json()));
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outcome.outputDir().resolve(REPORT_MARKDOWN),
                outcome.markdown()));
        for (var file : new TreeMap<>(outcome.files()).entrySet()) {
            written.add(HtmlKnowledgeIngestionRunner.writeFile(outcome.outputDir().resolve(file.getKey()),
                    file.getValue()));
        }
        return written;
    }

    Outcome execute() {
        var outputDir = options.outputDir();
        HtmlKnowledgeIngestionRunner.prepareOutput(outputDir, List.of(outputDir.resolve(REPORT_JSON),
                outputDir.resolve(REPORT_MARKDOWN), outputDir.resolve(TRACE)));
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);
        var corpus = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
        var cases = HtmlDecisionCases.bundled();
        var gold = HtmlDecisionGold.bundled(cases, corpus);

        var work = temporaryDirectory();
        try {
            var root = options.storeRoot().orElse(work.resolve("store"));
            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();
            var ingestion = new HtmlKnowledgeIngestion(corpus);
            var imported = ingestion.ingest(root);
            imported.checks().forEach(check -> checks.add(new Check("seed." + check.name(), check.passed(),
                    check.detail())));

            var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
            var readback = ingestion.readbackSweep(memory, imported.manifest());
            checks.add(new Check("reopen.persistence.readback", readback.unread().isEmpty(), readback.unread().isEmpty()
                    ? "every " + corpus.version() + " chunk was read back through a new handle with matching bytes"
                    : "not read back: " + readback.unread()));
            var resolver = new RecalledReferenceResolver(corpus, imported.manifest(), Set.copyOf(readback.unread()));
            var selector = new DecisionRuleBaseline();
            var experiment = new HtmlDecisionExperiment(selector, generator);
            HtmlDecisionExperiment.Observation observation;
            try (var adapter = ResonanceStoreMemoryAdapter.open(root, new HtmlKnowledgeRecall(corpus)
                    .adapterConfig())) {
                observation = experiment.run(new HtmlDecisionExperiment.Root(memory, adapter,
                        new RecalledEvidence(corpus, imported.manifest()), resolver), cases.cases());
            }
            checks.addAll(observation.checks());
            metadata.putAll(observation.metadata());

            var trace = DecisionTrace.toTsv(cases.caseHash(), corpus.corpusHash(), observation.rows());
            Optional<HtmlKnowledgeRecallRunner.Rendered> rendered;
            try {
                var scores = DecisionMetrics.score(observation.rows(), cases, gold);
                var report = new HtmlDecisionReport(scores, cases, gold, selector, generator);
                rendered = Optional.of(new HtmlKnowledgeRecallRunner.Rendered(report.toJson(), report.toMarkdown()));
                checks.add(new Check("decision.scored", true, "the scorer accepted " + observation.rows().size()
                        + " rows"));
            } catch (IllegalArgumentException e) {
                rendered = Optional.empty();
                checks.add(new Check("decision.scored", false, "the rows were rejected by the scorer; nothing was"
                        + " scored: " + e.getMessage()));
            }
            checks.add(new Check("gold.hashFrozen", gold.hash().equals(frozenHash), gold.hash().equals(frozenHash)
                    ? "the gold file hashes to its frozen value " + frozenHash
                    : "the gold file hashes to " + gold.hash() + ", not the frozen " + frozenHash));
            var digest = CorpusDigests.sha256(trace + "\n" + rendered.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null"));

            describe(corpus, cases, gold, root, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, HtmlDecisionExperiment.PERF_WARMUPS,
                            HtmlDecisionExperiment.PERF_ITERATIONS, "one JVM: import, reopen through a new handle,"
                            + " two verified passes, then separately measured steps; quality and latency are"
                            + " reported separately"),
                    observation.perf(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Decision Metadata", evaluation, checks, metadata);
            files.put(TRACE, "# raw decision rows, not a verified result on their own: allChecksPassed "
                    + report.allPassed() + " (see " + REPORT_JSON + ")\n" + trace);
            return new Outcome(report, rendered, digest, files, outputDir);
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work);
        }
    }

    private void describe(HtmlKnowledgeCorpus corpus, HtmlDecisionCases cases, HtmlDecisionGold gold, Path root,
            Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", HtmlDecisionExperiment.EXTENSION);
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        metadata.put("corpus.parts", String.join(", ", List.of(HtmlKnowledgeCorpus.VERSION,
                HtmlKnowledgeCorpus.DECISION_SUPPLEMENT_VERSION)));
        metadata.put("cases.version", cases.version());
        metadata.put("cases.hash", cases.caseHash());
        metadata.put("cases.count", Integer.toString(cases.cases().size()));
        metadata.put("cases.pairs", Integer.toString(cases.pairs().size()));
        metadata.put("gold.version", HtmlDecisionGold.VERSION);
        metadata.put("gold.hash", gold.hash());
        metadata.put("gold.origin", gold.origin());
        metadata.put("rules.version", DecisionRuleBaseline.VERSION);
        metadata.put("seed", "none (no randomness)");
        HtmlKnowledgePersistenceRunner.describeStore(root, options.storeRoot(), metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + "; profile html-knowledge-protocol-v1 " + HtmlDecisionExperiment.EXTENSION);
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", root, metadata);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-decision-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
