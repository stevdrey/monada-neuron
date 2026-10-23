package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionTrace.Arm;
import monada.neuron.evaluation.knowledge.DecisionTrace.Hit;
import monada.neuron.evaluation.knowledge.DecisionTrace.NeuronTrace;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.GroundedAnswerGenerator.Excerpt;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.RecalledEvidence.Failed;
import monada.neuron.evaluation.knowledge.RecalledEvidence.Recall;
import monada.neuron.evaluation.knowledge.RecalledEvidence.Retrieved;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.reasoning.MemoryReferenceEvidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The decision benchmark (Issue #112, extension {@value #EXTENSION}): every case of {@code html-decision-v1} goes
 * through the arms of {@link Arm} over one Store root holding {@code html-kc-v1} with the {@code html-kd-v1}
 * supplement, at {@code k = }{@value #K}.
 *
 * <ul>
 *   <li><b>A</b>: {@link RecalledEvidence#direct} with the case query; knowledge retrieval only.</li>
 *   <li><b>A+R</b>: the selector over the A evidence, the matched reference for B+R.</li>
 *   <li><b>B+R</b>: a Neuron cycle {@code MEMORY_RECALL -> REASONING -> EVALUATION} over the production memory port
 *       ({@link HtmlKnowledgeCycleExperiment}); the host evidence decision ({@link EvidenceDecision}) admits evidence
 *       only when {@code SUPPORTED}, and the selector sees only the chunks the {@code EVALUATION} stage selected. Their
 *       content is the B2c control's for the same atoms ({@link RecalledEvidence#neuron}), which must list exactly the
 *       chunks the cycle admitted.</li>
 *   <li><b>R0</b>: the selector with no evidence; it must abstain.</li>
 *   <li><b>C</b>: an optional {@link GroundedAnswerGenerator} over the B+R evidence, only when one is configured.</li>
 * </ul>
 *
 * <p>The whole matrix runs twice and must be equal. Latency and thread allocation of the Store recall, the Neuron cycle
 * and the selector step are then measured separately with {@link EvaluationMetricsCollector}, each validated against
 * the verified pass after its window. This class reads no gold label: its inputs are the cases (requirement,
 * constraints, candidates) and the Store.
 */
final class HtmlDecisionExperiment {

    /** The protocol extension this experiment delivers. */
    static final String EXTENSION = "decision-v1";

    /** Evidence depth of every arm. */
    static final int K = HtmlKnowledgeRecall.MAX_LIMIT;

    static final int PERF_WARMUPS = 2;
    static final int PERF_ITERATIONS = 5;

    /** The arms whose rows the selector produced. */
    static final Set<Arm> SELECTOR_ARMS = Set.of(Arm.STORE_RULES, Arm.NEURON_RULES, Arm.NO_EVIDENCE_RULES);

    /** The separately measured steps, in measurement order. */
    static final List<String> STEPS = List.of("store.recall", "neuron.cycle", "selector.decide");

    /**
     * One reopened Store root.
     *
     * @param memory the Store handle
     * @param adapter the production memory port over the same root
     * @param evidence the verified evidence reader of the root
     * @param resolver the reference resolver built from this process's readback
     */
    record Root(MonadaMemory memory, ResonanceMemoryPort adapter, RecalledEvidence evidence,
            RecalledReferenceResolver resolver) {

        Root {
            Objects.requireNonNull(memory, "memory must not be null");
            Objects.requireNonNull(adapter, "adapter must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            Objects.requireNonNull(resolver, "resolver must not be null");
        }
    }

    /**
     * Outcome of a run.
     *
     * @param rows the verified rows, in case order then {@link Arm} order
     * @param checks the semantic checks
     * @param metadata the run identity
     * @param perf the measured latency and allocation rows
     */
    record Observation(List<Row> rows, List<Check> checks, Map<String, String> metadata,
            List<BenchmarkRunResult> perf) {

        Observation {
            rows = List.copyOf(rows);
            checks = List.copyOf(checks);
            metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
            perf = List.copyOf(perf);
        }
    }

    private final DecisionSelector selector;
    private final Optional<GroundedAnswerGenerator> generator;
    private final HtmlKnowledgeCycleExperiment cycle = new HtmlKnowledgeCycleExperiment();
    private final HtmlKnowledgeQaExperiment citations = new HtmlKnowledgeQaExperiment(List.of());
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();
    private final UnaryOperator<ResonanceMemoryPort> timedPort;

    HtmlDecisionExperiment(DecisionSelector selector, Optional<GroundedAnswerGenerator> generator) {
        this(selector, generator, UnaryOperator.identity());
    }

    /**
     * Creates the experiment with a test seam: {@code timedPort} wraps the memory port of the timed Neuron passes
     * only, so a test can make a timed pass admit different evidence and observe its row withheld. Verified rows never
     * use it.
     */
    HtmlDecisionExperiment(DecisionSelector selector, Optional<GroundedAnswerGenerator> generator,
            UnaryOperator<ResonanceMemoryPort> timedPort) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.timedPort = Objects.requireNonNull(timedPort, "timedPort must not be null");
    }

    DecisionSelector selector() {
        return selector;
    }

    Optional<GroundedAnswerGenerator> generator() {
        return generator;
    }

    /** Runs the matrix twice, checks it, then measures each step. */
    Observation run(Root root, List<DecisionCase> cases) {
        var verifiedPass = pass(root, cases);
        var first = verifiedPass.rows();
        var second = pass(root, cases).rows();
        var checks = new ArrayList<Check>();
        var failed = first.stream().filter(row -> row.outcome() == Outcome.RECALL_FAILED).toList();
        checks.add(new Check("decision.completed", failed.isEmpty(), failed.isEmpty()
                ? "every arm of every case recalled its evidence; B+R listed exactly the chunks its cycle admitted"
                : failed.size() + " rows failed, first " + failed.getFirst().caseId() + " "
                        + failed.getFirst().arm().label() + ": " + failed.getFirst().detail()));
        var unverified = first.stream().filter(row -> SELECTOR_ARMS.contains(row.arm()))
                .filter(row -> row.citations().stream().anyMatch(span -> !span.eligible())
                        || row.claims().stream().anyMatch(claim -> claim.citations().stream()
                                .noneMatch(CitedSpan::exactQuote)))
                .map(row -> row.caseId() + " " + row.arm().label()).toList();
        checks.add(new Check("decision.selectorCitationsVerified", unverified.isEmpty(), unverified.isEmpty()
                ? "every citation of the selector lies in the evidence it saw, and every claim quotes its span exactly"
                : "unverified citations in " + unverified));
        var guessed = first.stream().filter(row -> row.arm() == Arm.NO_EVIDENCE_RULES)
                .filter(row -> row.outcome() != Outcome.ABSTAINED).map(Row::caseId).toList();
        checks.add(new Check("control.emptyEvidenceAbstains", guessed.isEmpty(), guessed.isEmpty()
                ? "with no evidence the selector abstained on every case"
                : "decided without evidence: " + guessed));
        var deterministic = first.equals(second);
        checks.add(new Check("decision.deterministic", deterministic, deterministic
                ? "a second pass over the same root produced identical rows"
                : "a second pass produced different rows"));
        generator.ifPresent(value -> {
            var labelled = first.stream().filter(row -> row.arm() == Arm.GENERATOR)
                    .allMatch(row -> row.kind().equals(value.kind().name()) && row.recommendation().isEmpty());
            checks.add(new Check("generator.labelled", labelled, labelled
                    ? "every C row carries the generator's kind " + value.kind() + " and no structured decision"
                    : "a C row hides the generator's kind or carries a structured decision"));
        });

        var metadata = new TreeMap<String, String>();
        var perf = measure(root, cases, verifiedPass, checks, metadata);
        describe(cases, metadata);
        return new Observation(first, checks, metadata, perf);
    }

    /**
     * One pass over every case.
     *
     * @param rows the rows, in case order then {@link Arm} order
     * @param neuronEvidence per case, the evidence the B+R selector saw, or empty when the Neuron recall failed
     */
    private record Pass(List<Row> rows, List<Optional<List<EvidenceChunk>>> neuronEvidence) {
    }

    /** Runs every arm of every case once. */
    List<Row> matrix(Root root, List<DecisionCase> cases) {
        return pass(root, cases).rows();
    }

    private Pass pass(Root root, List<DecisionCase> cases) {
        var rows = new ArrayList<Row>();
        var neuronEvidence = new ArrayList<Optional<List<EvidenceChunk>>>();
        for (var decisionCase : cases) {
            var query = decisionCase.query();
            var store = root.evidence().direct(root.memory(), query, K);
            rows.add(switch (store) {
                case Failed failed -> failedRow(decisionCase, Arm.STORE, "-", "-", Optional.empty(), failed.reason());
                case Retrieved retrieved -> new Row(decisionCase.caseId(), Arm.STORE, "-", "-",
                        hits(retrieved.chunks()), Optional.empty(), Outcome.RETRIEVED, Optional.empty(), List.of(),
                        List.of(), "-");
            });
            rows.add(switch (store) {
                case Failed failed -> failedRow(decisionCase, Arm.STORE_RULES, selector.name(), selector.kind().name(),
                        Optional.empty(), failed.reason());
                case Retrieved retrieved -> selectorRow(decisionCase, Arm.STORE_RULES, retrieved.chunks(),
                        Optional.empty(), "-");
            });

            var neuron = neuron(root, query);
            neuronEvidence.add(neuron.usable());
            rows.add(neuron.usable().isPresent()
                    ? selectorRow(decisionCase, Arm.NEURON_RULES, neuron.usable().get(), neuron.trace(),
                            neuron.detail())
                    : failedRow(decisionCase, Arm.NEURON_RULES, selector.name(), selector.kind().name(),
                            neuron.trace(), neuron.detail()));
            rows.add(selectorRow(decisionCase, Arm.NO_EVIDENCE_RULES, List.of(), Optional.empty(), "-"));
            if (generator.isPresent()) {
                var value = generator.get();
                rows.add(neuron.usable().isPresent()
                        ? generatorRow(decisionCase, value, neuron.usable().get(), neuron.trace())
                        : failedRow(decisionCase, Arm.GENERATOR, value.name(), value.kind().name(), neuron.trace(),
                                neuron.detail()));
            }
        }
        return new Pass(rows, neuronEvidence);
    }

    /**
     * The Neuron path of one query.
     *
     * @param trace the cycle trace, when the cycle produced a memory result
     * @param usable the evidence the selector may use, or empty when the recall failed
     * @param detail what happened
     */
    private record NeuronPath(Optional<NeuronTrace> trace, Optional<List<EvidenceChunk>> usable, String detail) {
    }

    private NeuronPath neuron(Root root, String query) {
        var neuronTrace = cycle(root, root.adapter(), query);
        var decision = neuronTrace.decision();
        var admitted = neuronTrace.admitted();
        var selected = neuronTrace.selected();
        var trace = Optional.of(neuronTrace);
        if (decision == EvidenceDecision.RECALL_FAILED || decision == EvidenceDecision.INCOMPLETE
                || decision == EvidenceDecision.FAILED_CLOSED) {
            return new NeuronPath(trace, Optional.empty(), "Neuron cycle decision " + decision);
        }
        Recall content = root.evidence().neuron(root.adapter(), root.memory(), query, K);
        return switch (content) {
            case Failed failed -> new NeuronPath(trace, Optional.empty(), failed.reason());
            case Retrieved retrieved -> {
                var ids = retrieved.chunks().stream().map(EvidenceChunk::chunkId).toList();
                if (!ids.equals(admitted)) {
                    yield new NeuronPath(trace, Optional.empty(), "the B2c content " + ids
                            + " differs from the chunks the cycle admitted " + admitted);
                }
                if (decision != EvidenceDecision.SUPPORTED) {
                    yield new NeuronPath(trace, Optional.of(List.of()), "Neuron decision " + decision
                            + ": no evidence reaches the selector");
                }
                yield new NeuronPath(trace, Optional.of(retrieved.chunks().stream()
                        .filter(chunk -> selected.contains(chunk.chunkId())).toList()), "-");
            }
        };
    }

    /** Runs one Neuron cycle over {@code port} and returns its full trace: signals, decision, admitted and selected. */
    private NeuronTrace cycle(Root root, ResonanceMemoryPort port, String query) {
        var run = cycle.execute(port, query, HtmlKnowledgeCycleExperiment.BUDGET, K);
        var decision = cycle.decide(run, root.resolver());
        var admitted = run.memory().map(memory -> memory.response().results().stream()
                .map(result -> chunkOf(root, result.reference())).toList()).orElse(List.of());
        var selected = run.stageResults().stream().filter(EvaluationCognitiveStageResult.class::isInstance)
                .map(EvaluationCognitiveStageResult.class::cast)
                .flatMap(result -> cycle.selectedEvidence(result).stream())
                .filter(MemoryReferenceEvidence.class::isInstance).map(MemoryReferenceEvidence.class::cast)
                .map(reference -> chunkOf(root, reference.reference())).distinct().toList();
        return new NeuronTrace(run.inputs().size(), decision, admitted, selected);
    }

    private String chunkOf(Root root, String reference) {
        return root.resolver().resolve(reference).chunkId().orElse(EvidenceTrace.UNRESOLVED_PREFIX + reference);
    }

    private Row selectorRow(DecisionCase decisionCase, Arm arm, List<EvidenceChunk> evidence,
            Optional<NeuronTrace> trace, String detail) {
        var recommendation = selector.decide(decisionCase, evidence);
        var claims = switch (recommendation) {
            case Decided decided -> decided.claims();
            case Conditional conditional -> conditional.claims();
            case Abstained _ -> List.<GroundedAnswer.Claim>of();
        };
        var exclusions = switch (recommendation) {
            case Decided decided -> decided.excluded();
            case Conditional conditional -> conditional.excluded();
            case Abstained _ -> List.<DecisionRecommendation.Exclusion>of();
        };
        var exclusionCitations = exclusions.stream().flatMap(exclusion -> exclusion.citations().stream())
                .map(citation -> citations.verify("", citation, evidence)).toList();
        return new Row(decisionCase.caseId(), arm, selector.name(), selector.kind().name(), hits(evidence), trace,
                Row.outcomeOf(recommendation), Optional.of(recommendation), verified(claims, evidence),
                exclusionCitations, detail);
    }

    private Row generatorRow(DecisionCase decisionCase, GroundedAnswerGenerator value, List<EvidenceChunk> evidence,
            Optional<NeuronTrace> trace) {
        var question = decisionCase.requirement() + " Candidates: " + String.join(", ", decisionCase.candidates())
                + ".";
        var excerpts = evidence.stream().limit(GroundedAnswerGenerator.MAX_EXCERPTS).map(Excerpt::of).toList();
        var answer = value.propose(question, excerpts);
        return switch (answer) {
            case GroundedAnswer.Abstained abstained -> new Row(decisionCase.caseId(), Arm.GENERATOR, value.name(),
                    value.kind().name(), hits(evidence), trace, Outcome.ABSTAINED, Optional.empty(), List.of(),
                    List.of(), abstained.status() + ": " + abstained.reason());
            case GroundedAnswer.Answered answered -> new Row(decisionCase.caseId(), Arm.GENERATOR, value.name(),
                    value.kind().name(), hits(evidence), trace, Outcome.FREE_TEXT, Optional.empty(),
                    verified(answered.claims(), evidence), List.of(), "free text; no structured decision");
        };
    }

    private List<ClaimRow> verified(List<GroundedAnswer.Claim> claims, List<EvidenceChunk> evidence) {
        return claims.stream().map(claim -> new ClaimRow(claim.text(), claim.citations().stream()
                .map(citation -> citations.verify(claim.text(), citation, evidence)).toList())).toList();
    }

    private static Row failedRow(DecisionCase decisionCase, Arm arm, String selector, String kind,
            Optional<NeuronTrace> trace, String detail) {
        return new Row(decisionCase.caseId(), arm, selector, kind, List.of(), trace, Outcome.RECALL_FAILED,
                Optional.empty(), List.of(), List.of(), detail);
    }

    private static List<Hit> hits(List<EvidenceChunk> chunks) {
        return chunks.stream().map(chunk -> new Hit(chunk.chunkId(), chunk.rank(), chunk.score())).toList();
    }

    /**
     * Measures the three steps separately over all cases per iteration. A step is timed only when the verified pass
     * passed every check and every verified reference row of that step completed; every warm-up and measured result
     * must then equal the verified one in full (Store hits with scores; the whole Neuron trace with admitted and
     * selected chunks; the whole recommendation), or the row is withheld and {@code perf.matchesVerified} fails.
     */
    private List<BenchmarkRunResult> measure(Root root, List<DecisionCase> cases, Pass verifiedPass,
            List<Check> checks, Map<String, String> metadata) {
        var verified = verifiedPass.rows();
        var perf = new ArrayList<BenchmarkRunResult>();
        var failedChecks = checks.stream().filter(check -> !check.passed()).map(Check::name).toList();
        if (!failedChecks.isEmpty()) {
            for (var name : STEPS) {
                metadata.put("perf." + name, "withheld (the verified pass failed " + failedChecks + ")");
            }
            checks.add(new Check("perf.matchesVerified", false, "not measured: no timed row can be compared with a"
                    + " verified pass that failed " + failedChecks));
            return perf;
        }
        var storeRows = verified.stream().filter(row -> row.arm() == Arm.STORE).toList();
        var neuronRows = verified.stream().filter(row -> row.arm() == Arm.NEURON_RULES).toList();
        var mismatches = new ArrayList<String>();

        var storeReady = storeRows.stream().allMatch(row -> row.outcome() == Outcome.RETRIEVED);
        measured(STEPS.get(0), storeReady, cases, "RecalledEvidence.direct(memory, query, " + K + ") per case, with"
                + " mapping and sha256 fidelity", mismatches, perf, metadata, () -> {
                    var out = new ArrayList<Object>();
                    for (var decisionCase : cases) {
                        out.add(switch (root.evidence().direct(root.memory(), decisionCase.query(), K)) {
                            case Retrieved retrieved -> hits(retrieved.chunks());
                            case Failed failed -> failed;
                        });
                    }
                    return out;
                }, storeRows.stream().map(Row::evidence).toList());

        var neuronReady = neuronRows.stream().allMatch(row -> row.neuron().isPresent()
                && row.outcome() != Outcome.RECALL_FAILED);
        var port = timedPort.apply(root.adapter());
        measured(STEPS.get(1), neuronReady, cases, "HtmlKnowledgeCycleExperiment.execute + decide per case (memory"
                + " port, reasoning and evaluation stages), compared by its full trace", mismatches, perf, metadata,
                () -> {
                    var out = new ArrayList<Object>();
                    for (var decisionCase : cases) {
                        out.add(cycle(root, port, decisionCase.query()));
                    }
                    return out;
                }, neuronRows.stream().map(row -> row.neuron().orElse(null)).toList());

        // The selector is timed on exactly the evidence its verified B+R rows saw, kept from the verified pass.
        var inputs = verifiedPass.neuronEvidence().stream().map(evidence -> evidence.orElse(List.of())).toList();
        var selectorReady = neuronReady && verifiedPass.neuronEvidence().stream().allMatch(Optional::isPresent)
                && neuronRows.stream().allMatch(row -> row.recommendation().isPresent());
        measured(STEPS.get(2), selectorReady, cases, selector.name() + ".decide over the verified B+R evidence per"
                + " case (evaluation-only decision step)",
                mismatches, perf, metadata, () -> {
                    var out = new ArrayList<Object>();
                    for (var index = 0; index < cases.size(); index++) {
                        out.add(selector.decide(cases.get(index), inputs.get(index)));
                    }
                    return out;
                }, neuronRows.stream().map(row -> row.recommendation().orElse(null)).toList());
        checks.add(new Check("perf.matchesVerified", mismatches.isEmpty(), mismatches.isEmpty()
                ? "every timed result equals the verified pass in full" : "timed rows withheld: " + mismatches));
        return perf;
    }

    private void measured(String name, boolean referenceReady, List<DecisionCase> cases, String timed,
            List<String> mismatches, List<BenchmarkRunResult> perf, Map<String, String> metadata,
            Supplier<List<Object>> workload, List<?> expected) {
        if (!referenceReady) {
            mismatches.add(name + ": a verified reference row did not complete");
            metadata.put("perf." + name, "withheld (a verified reference row did not complete)");
            return;
        }
        var results = new ArrayList<List<Object>>();
        var diagnostics = new TreeMap<String, String>();
        diagnostics.put("timed", timed);
        diagnostics.put("operationsPerIteration", Integer.toString(cases.size()));
        diagnostics.put("warmupIterations", Integer.toString(PERF_WARMUPS));
        diagnostics.put("measurementIterations", Integer.toString(PERF_ITERATIONS));
        diagnostics.put("validation", "every warm-up and measured result is compared with the verified pass after the"
                + " window");
        diagnostics.put("gcTelemetryScope", "thread-allocated bytes: workload plus one list append per sample; GC and"
                + " RSS deltas are JVM-wide");
        BenchmarkRunResult row;
        try {
            row = collector.measure("decision." + name, cases.size() + " cases", PERF_WARMUPS, PERF_ITERATIONS,
                    cases.size(), null, () -> results.add(workload.get()), diagnostics);
        } catch (RuntimeException e) {
            mismatches.add(name + " threw " + e);
            metadata.put("perf." + name, "unavailable (the workload threw " + e + ")");
            return;
        }
        var differing = results.stream().filter(result -> !result.equals(expected)).count();
        if (differing > 0) {
            mismatches.add(name + ": " + differing + " of " + results.size() + " passes differ");
            metadata.put("perf." + name, "invalidated (results differ from the verified pass)");
            return;
        }
        perf.add(row);
        metadata.put("perf." + name + ".medianNanosPerIteration", format(row.latency().medianNanos()));
        metadata.put("perf." + name + ".p95NanosPerIteration", format(row.latency().p95Nanos()));
        metadata.put("perf." + name + ".medianNanosPerCase", format(row.latency().medianNanos() / cases.size()));
        metadata.put("perf." + name + ".allocationSource", row.allocation().source().name());
        metadata.put("perf." + name + ".bytesPerOp", format(row.allocation().bytesPerOp()));
        metadata.put("perf." + name + ".timed", timed);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void describe(List<DecisionCase> cases, Map<String, String> metadata) {
        metadata.put("extension", EXTENSION);
        metadata.put("k", Integer.toString(K));
        metadata.put("arms", String.join("; ", Arrays.stream(Arm.values())
                .map(arm -> arm.label() + " = " + arm.description()).toList()));
        metadata.put("selector.name", selector.name());
        metadata.put("selector.kind", selector.kind().name());
        metadata.put("selector.description", selector.description());
        metadata.put("generator", generator.map(value -> value.kind() + " " + value.name() + ": " + value.description())
                .orElse("not configured (arm C did not run)"));
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("cycle.budget", HtmlKnowledgeCycleExperiment.BUDGET.toString());
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("query.rule", "distinct tokens of the candidates then the requirement, without function words,"
                + " at most " + TokenPackingSignalCodec.MAX_QUERY_TOKENS);
        cases.forEach(decisionCase -> metadata.put("query." + decisionCase.caseId(), decisionCase.query()));
    }
}
