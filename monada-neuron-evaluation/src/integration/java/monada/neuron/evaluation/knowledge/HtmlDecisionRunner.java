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
    static final String FROZEN_GOLD_HASH = "5506175cde52efcfbec6e4590ab470f1f728edf96e5844552b3f35315de87440";

    /** SHA-256 of the frozen, independently adjudicated {@code gold-v2.tsv} revision. */
    static final String FROZEN_GOLD_REVISION_HASH = "d9d480d88df1966f5b9358e9efbead28dab29ba9c377609a92de89da0f5109b9";

    /** SHA-256 of the frozen independent adjudication by gpt-6.1-sol (Issue #152). */
    static final String FROZEN_ADJUDICATION_HASH = "ff8cd6db6e50e0b9169acf2749122416384e6f3e2d5cfaa2cdfc519bf4563cc7";

    static final String REPORT_JSON = "html-decision.json";
    static final String REPORT_MARKDOWN = "html-decision.md";
    static final String TRACE = HtmlDecisionCases.VERSION + "-trace.tsv";
    static final String GENERATOR_OPTION = "--generator";
    static final String TEST_DOUBLE = "test-double";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>] [--generator test-double]";

    private final Options options;
    private final Optional<GroundedAnswerGenerator> generator;
    private final FrozenHashes frozen;

    HtmlDecisionRunner(Options options, Optional<GroundedAnswerGenerator> generator, FrozenHashes frozen) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.frozen = Objects.requireNonNull(frozen, "frozen must not be null");
    }

    /**
     * The frozen hashes the bundled label files must match.
     *
     * @param gold the {@value HtmlDecisionGold#VERSION} file
     * @param revision the {@value HtmlDecisionGold#REVISION_VERSION} file
     * @param adjudication the {@value HtmlDecisionAdjudication#VERSION} file
     */
    record FrozenHashes(String gold, String revision, String adjudication) {

        static final FrozenHashes BUNDLED = new FrozenHashes(FROZEN_GOLD_HASH, FROZEN_GOLD_REVISION_HASH,
                FROZEN_ADJUDICATION_HASH);

        FrozenHashes {
            Objects.requireNonNull(gold, "gold must not be null");
            Objects.requireNonNull(revision, "revision must not be null");
            Objects.requireNonNull(adjudication, "adjudication must not be null");
        }
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
        return new HtmlDecisionRunner(options, generator, FrozenHashes.BUNDLED).execute();
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
        var authored = HtmlDecisionGold.bundled(cases, corpus);
        var adjudication = HtmlDecisionAdjudication.bundled(cases, authored, corpus);
        var revision = HtmlDecisionGold.bundledRevision(cases, corpus);
        var gold = revision.orElse(authored);

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
                var report = new HtmlDecisionReport(scores, cases, gold, adjudication, selector, generator);
                rendered = Optional.of(new HtmlKnowledgeRecallRunner.Rendered(report.toJson(), report.toMarkdown()));
                checks.add(new Check("decision.scored", true, "the scorer accepted " + observation.rows().size()
                        + " rows"));
            } catch (IllegalArgumentException e) {
                rendered = Optional.empty();
                checks.add(new Check("decision.scored", false, "the rows were rejected by the scorer; nothing was"
                        + " scored: " + e.getMessage()));
            }
            var frozenGold = revision.isPresent() ? frozen.revision() : frozen.gold();
            checks.add(new Check("gold.hashFrozen", gold.hash().equals(frozenGold), gold.hash().equals(frozenGold)
                    ? "the " + gold.version() + " file hashes to its frozen value " + frozenGold
                    : "the " + gold.version() + " file hashes to " + gold.hash() + ", not the frozen " + frozenGold));
            if (adjudication.isPresent() || revision.isPresent()) {
                adjudicationChecks(adjudication, revision, checks);
            }
            var digest = CorpusDigests.sha256(trace + "\n" + rendered.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null"));

            describe(corpus, cases, gold, adjudication, root, metadata);
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

    /**
     * Adds the checks of the independent adjudication (Issue #152): its hash is frozen, every case and item has a
     * verdict, no reviewer wrote the rules or drafted a label, and a revision exists exactly when a label changed
     * and holds exactly the changed rows.
     */
    void adjudicationChecks(Optional<HtmlDecisionAdjudication> adjudication,
            Optional<HtmlDecisionGold> revision, List<Check> checks) {
        if (adjudication.isEmpty()) {
            checks.add(new Check("gold.matchesAdjudication", false, "a " + HtmlDecisionGold.REVISION_VERSION
                    + " file exists without an adjudication"));
            return;
        }
        var recorded = adjudication.get();
        var hash = recorded.hash();
        checks.add(new Check("adjudication.hashFrozen", hash.equals(frozen.adjudication()),
                hash.equals(frozen.adjudication()) ? "the adjudication hashes to its frozen value " + hash
                        : "the adjudication hashes to " + hash + ", not the frozen '" + frozen.adjudication() + "'"));
        checks.add(new Check("adjudication.complete", recorded.missing().isEmpty(), recorded.missing().isEmpty()
                ? "every case and item has a verdict (" + recorded.agreements() + " agree, " + recorded.changes()
                        + " change)"
                : "without a verdict: " + recorded.missing()));
        checks.add(new Check("adjudication.independent", recorded.dependentReviewers().isEmpty(),
                recorded.dependentReviewers().isEmpty() ? "reviewed by " + recorded.reviewers()
                        + ", none of whom wrote " + DecisionRuleBaseline.VERSION + " or drafted a label"
                        : "not independent: " + recorded.dependentReviewers()));
        boolean matches;
        String detail;
        if (recorded.changes() == 0) {
            matches = revision.isEmpty();
            detail = matches ? "no label changed, so " + HtmlDecisionGold.VERSION + " stays the active gold"
                    : "no label changed, but a " + HtmlDecisionGold.REVISION_VERSION + " file exists";
        } else {
            matches = revision.map(value -> value.cells().equals(recorded.revisedTable())).orElse(false);
            detail = revision.isEmpty() ? recorded.changes() + " changes but no " + HtmlDecisionGold.REVISION_VERSION
                    + " file" : matches ? HtmlDecisionGold.REVISION_VERSION + " holds exactly the adjudicated labels"
                    : HtmlDecisionGold.REVISION_VERSION + " differs from the adjudicated labels";
        }
        checks.add(new Check("gold.matchesAdjudication", matches, detail));
    }

    private void describe(HtmlKnowledgeCorpus corpus, HtmlDecisionCases cases, HtmlDecisionGold gold,
            Optional<HtmlDecisionAdjudication> adjudication, Path root, Map<String, String> metadata) {
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
        metadata.put("gold.version", gold.version());
        metadata.put("gold.hash", gold.hash());
        metadata.put("gold.origin", gold.origin());
        metadata.put("adjudication.version", adjudication.isPresent() ? HtmlDecisionAdjudication.VERSION
                : "none (missing: the independent adjudication of issue #152 is required)");
        adjudication.ifPresent(value -> {
            metadata.put("adjudication.hash", value.hash());
            metadata.put("adjudication.reviewers", String.join(", ", value.reviewers()));
            metadata.put("adjudication.verdicts", value.agreements() + " agree, " + value.changes() + " change");
        });
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
