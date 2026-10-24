package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Assembled;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;

/**
 * CLI of the multi-turn HTML follow-up suite (Issue #113, protocol extension {@value HtmlDialogExperiment#EXTENSION}).
 * It imports {@code html-kc-v1} with the {@code html-kd-v1} supplement into a fresh, temporary embedded Store root,
 * reopens it with a new handle and the production memory port, runs every {@code html-dialog-v1} turn through the arms
 * of {@link HtmlDialogExperiment} and scores the rows against the frozen labels in this process, after the experiment
 * has finished. The experiment, the assembler and the selector never receive a gold label.
 *
 * <p>Options: {@code [--output-dir <dir>] [--store-root <dir>]}. No provider, model or network is used.
 *
 * <p>Outputs: {@code html-dialog.json} (verdicts, metadata, the scored payload under {@code dialogs} and the run's
 * {@code semanticDigest}), {@code html-dialog.md} and {@code html-dialog-v1-trace.tsv} (every raw row). The digest
 * covers the trace and the scored payload, which hold no time, pid or host value. Exit status: {@code 0} all checks
 * passed, {@code 1} a semantic check failed (fixture, provenance, isolation or metric integrity, never an accuracy
 * threshold), {@code 2} invalid usage or rejected input.
 */
public final class HtmlDialogRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Multi-Turn Follow-Up Report";

    /** SHA-256 of the frozen {@code html-dialog-v1} gold file. */
    static final String FROZEN_GOLD_HASH = "350006a7e02a88b398f11e85b27b48cfe0313ec5e19c11cc9f38052a50eaae9b";

    static final String REPORT_JSON = "html-dialog.json";
    static final String REPORT_MARKDOWN = "html-dialog.md";
    static final String TRACE = HtmlDialogCases.VERSION + "-trace.tsv";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]";

    private final Options options;
    private final String frozenGold;

    HtmlDialogRunner(Options options, String frozenGold) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.frozenGold = Objects.requireNonNull(frozenGold, "frozenGold must not be null");
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param dialogs the scored payload as JSON and Markdown, when the rows were scored
     * @param semanticDigest SHA-256 of the trace and the scored payload
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeRecallRunner.Rendered> dialogs,
            String semanticDigest, Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
        }

        /** Returns the JSON report with the scored payload as {@code dialogs}. */
        public String json() {
            return report.toJson(Map.of("dialogs", dialogs.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null"), "semanticDigest", string(semanticDigest)));
        }

        /** Returns the Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + "\nSemantic digest: `" + semanticDigest + "`\n"
                    + dialogs.map(rendered -> "\n" + rendered.markdown()).orElse("");
        }
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Outcome outcome;
        try {
            outcome = run(options);
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Dialog input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Dialog outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write dialog outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic dialog checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the suite with the bundled fixture. The temporary work directory, and the Store root unless
     * {@code --store-root} was given, are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus, the dialogs or a label violates its contract
     */
    public static Outcome run(Options options) {
        return new HtmlDialogRunner(options, FROZEN_GOLD_HASH).execute();
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

    /** Assembles every turn with its full carried context and no advice: the reference the labels are checked by. */
    static Map<String, Assembled> reference(HtmlDialogCases dialogs) {
        var assembler = new DialogContextAssembler();
        var reference = new LinkedHashMap<String, Assembled>();
        for (var dialog : dialogs.dialogs()) {
            for (var turn : dialog.turns()) {
                reference.put(turn.turnId(), assembler.assemble(DialogContext.carried(dialog, turn, Map.of())));
            }
        }
        return reference;
    }

    Outcome execute() {
        var outputDir = options.outputDir();
        HtmlKnowledgeIngestionRunner.prepareOutput(outputDir, List.of(outputDir.resolve(REPORT_JSON),
                outputDir.resolve(REPORT_MARKDOWN), outputDir.resolve(TRACE)));
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);
        var corpus = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
        var cases = HtmlDecisionCases.bundled();
        var decisionGold = HtmlDecisionGold.bundledRevision(cases, corpus)
                .orElseGet(() -> HtmlDecisionGold.bundled(cases, corpus));
        var dialogs = HtmlDialogCases.bundled();
        var gold = HtmlDialogGold.bundled(dialogs, cases, decisionGold, corpus, reference(dialogs));

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
            var experiment = new HtmlDialogExperiment(selector, corpus);
            HtmlDialogExperiment.Observation observation;
            try (var adapter = ResonanceStoreMemoryAdapter.open(root, new HtmlKnowledgeRecall(corpus)
                    .adapterConfig())) {
                observation = experiment.run(new HtmlDecisionExperiment.Root(memory, adapter,
                        new RecalledEvidence(corpus, imported.manifest()), resolver), dialogs);
            }
            checks.addAll(observation.checks());
            metadata.putAll(observation.metadata());

            var trace = DialogTrace.toTsv(dialogs.dialogHash(), corpus.corpusHash(), observation.rows());
            Optional<HtmlKnowledgeRecallRunner.Rendered> rendered;
            try {
                var scores = DialogMetrics.score(observation.rows(), dialogs, gold);
                var report = new HtmlDialogReport(scores, dialogs, gold, selector);
                rendered = Optional.of(new HtmlKnowledgeRecallRunner.Rendered(report.toJson(), report.toMarkdown()));
                checks.add(new Check("dialog.scored", true, "the scorer accepted " + observation.rows().size()
                        + " rows"));
            } catch (IllegalArgumentException e) {
                rendered = Optional.empty();
                checks.add(new Check("dialog.scored", false, "the rows were rejected by the scorer; nothing was"
                        + " scored: " + e.getMessage()));
            }
            checks.add(new Check("gold.hashFrozen", gold.hash().equals(frozenGold), gold.hash().equals(frozenGold)
                    ? "the " + gold.version() + " file hashes to its frozen value " + frozenGold
                    : "the " + gold.version() + " file hashes to " + gold.hash() + ", not the frozen " + frozenGold));
            var frozenDecision = decisionGold.version().equals(HtmlDecisionGold.REVISION_VERSION)
                    ? HtmlDecisionRunner.FROZEN_GOLD_REVISION_HASH : HtmlDecisionRunner.FROZEN_GOLD_HASH;
            checks.add(new Check("gold.reusedHashFrozen", decisionGold.hash().equals(frozenDecision),
                    decisionGold.hash().equals(frozenDecision) ? "the reused " + decisionGold.version()
                            + " labels hash to their frozen value " + frozenDecision : "the reused "
                            + decisionGold.version() + " labels hash to " + decisionGold.hash() + ", not the frozen "
                            + frozenDecision));
            var digest = CorpusDigests.sha256(trace + "\n" + rendered.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null"));

            describe(corpus, dialogs, gold, root, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, HtmlDecisionExperiment.PERF_WARMUPS,
                            HtmlDecisionExperiment.PERF_ITERATIONS, "one JVM: import, reopen through a new handle,"
                            + " two verified passes over every dialog in fixed turn order, then separately measured"
                            + " steps; quality and latency are reported separately"),
                    observation.perf(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Dialog Metadata", evaluation, checks, metadata);
            files.put(TRACE, "# raw dialog rows, not a verified result on their own: allChecksPassed "
                    + report.allPassed() + " (see " + REPORT_JSON + ")\n" + trace);
            return new Outcome(report, rendered, digest, files, outputDir);
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work);
        }
    }

    private void describe(HtmlKnowledgeCorpus corpus, HtmlDialogCases dialogs, HtmlDialogGold gold, Path root,
            Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", HtmlDialogExperiment.EXTENSION);
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        metadata.put("dialogs.version", dialogs.version());
        metadata.put("dialogs.hash", dialogs.dialogHash());
        metadata.put("dialogs.count", Integer.toString(dialogs.dialogs().size()));
        metadata.put("dialogs.turns", Integer.toString(dialogs.turns().size()));
        metadata.put("gold.version", gold.version());
        metadata.put("gold.hash", gold.hash());
        metadata.put("gold.reused", gold.decisionGoldVersion());
        metadata.put("gold.inlineLabels", Long.toString(gold.labels().stream()
                .filter(label -> label.goldRef().isEmpty()).count()));
        metadata.put("gold.inlineAdjudication", "none: the inline labels are drafted by "
                + String.join(", ", gold.inlineDrafters()) + " and not independently adjudicated");
        metadata.put("rules.version", DecisionRuleBaseline.VERSION);
        metadata.put("seed", "none (no randomness)");
        HtmlKnowledgePersistenceRunner.describeStore(root, options.storeRoot(), metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + "; profile html-knowledge-protocol-v1 " + HtmlDialogExperiment.EXTENSION);
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", root, metadata);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-dialog-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
