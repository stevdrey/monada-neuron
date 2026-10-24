package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.DecisionTrace.NeuronTrace;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Assembled;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.DialogTrace.Arm;
import monada.neuron.evaluation.knowledge.DialogTrace.TurnRow;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionExperiment.Root;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Turn;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.RecalledEvidence.Failed;
import monada.neuron.evaluation.knowledge.RecalledEvidence.Retrieved;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The multi-turn suite (Issue #113, extension {@value #EXTENSION}): every turn of {@code html-dialog-v1}, in fixed
 * dialog and turn order, goes through the arms of {@link Arm} over one reopened Store root holding {@code html-kc-v1}
 * with the {@code html-kd-v1} supplement, at {@code k = }{@value HtmlDecisionExperiment#K}.
 *
 * <p>Per turn the host builds an explicit {@link DialogContext} from the carried turns and the advice the same arm gave
 * them, the fixed {@link DialogContextAssembler} turns it into a single-turn case or holds it, the Store is queried
 * with the assembled query and the unchanged #112 Neuron path and selector ({@link HtmlDecisionExperiment}) answer a
 * ready case. A held turn becomes a host abstention, still recorded with its Store and Neuron evidence. Nothing is
 * kept between turns but the host's own advice map, which lives in one pass and never reaches Neuron or the Store.
 *
 * <p>The whole run happens twice and must be equal. The assembler and the Neuron cycle are then measured separately,
 * each validated against the verified pass. This class reads no gold label.
 */
final class HtmlDialogExperiment {

    /** The protocol extension this experiment delivers. */
    static final String EXTENSION = "dialog-v1";

    /** Corpus topics whose chunks the evidence-removed control drops: every topic the three families decide from. */
    static final Set<String> DECISION_TOPICS = Set.of("scripts", "embedding", "structure", "lists");

    /** The separately measured steps, in measurement order. */
    static final List<String> STEPS = List.of("context.assemble", "turn.neuron");

    /**
     * Outcome of a run.
     *
     * @param rows the verified rows, in dialog, turn and {@link Arm} order
     * @param checks the semantic checks
     * @param metadata the run identity
     * @param perf the measured latency and allocation rows
     */
    record Observation(List<TurnRow> rows, List<Check> checks, Map<String, String> metadata,
            List<BenchmarkRunResult> perf) {

        Observation {
            rows = List.copyOf(rows);
            checks = List.copyOf(checks);
            metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
            perf = List.copyOf(perf);
        }
    }

    private final HtmlDecisionExperiment decision;
    private final HtmlKnowledgeCorpus corpus;
    private final DialogContextAssembler assembler = new DialogContextAssembler();
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();
    private final UnaryOperator<DialogContext> contextTamper;

    HtmlDialogExperiment(DecisionSelector selector, HtmlKnowledgeCorpus corpus) {
        this(selector, corpus, UnaryOperator.identity());
    }

    /**
     * Creates the experiment with a test seam: {@code contextTamper} rewrites every full-context {@link DialogContext}
     * before assembly, so a test can inject a turn of another dialog and observe that it is rejected, not used.
     */
    HtmlDialogExperiment(DecisionSelector selector, HtmlKnowledgeCorpus corpus,
            UnaryOperator<DialogContext> contextTamper) {
        this.decision = new HtmlDecisionExperiment(selector, Optional.empty());
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.contextTamper = Objects.requireNonNull(contextTamper, "contextTamper must not be null");
    }

    DecisionSelector selector() {
        return decision.selector();
    }

    /** Runs every dialog twice, checks the rows, then measures each step. */
    Observation run(Root root, HtmlDialogCases dialogs) {
        var verified = pass(root, dialogs);
        var first = verified.rows();
        var second = pass(root, dialogs).rows();
        var checks = new ArrayList<Check>();
        var failed = first.stream().filter(row -> row.row().outcome() == Outcome.RECALL_FAILED).toList();
        checks.add(new Check("dialog.completed", failed.isEmpty(), failed.isEmpty()
                ? "every arm of every turn recalled its evidence from the reopened Store"
                : failed.size() + " rows failed, first " + failed.getFirst().turnId() + " "
                        + failed.getFirst().arm().label() + ": " + failed.getFirst().row().detail()));
        var unverified = first.stream().filter(row -> row.arm() != Arm.STORE)
                .filter(row -> row.row().citations().stream().anyMatch(span -> !span.eligible())
                        || row.row().claims().stream().anyMatch(claim -> claim.citations().stream()
                                .noneMatch(CitedSpan::exactQuote)))
                .map(row -> row.turnId() + " " + row.arm().label()).toList();
        checks.add(new Check("dialog.selectorCitationsVerified", unverified.isEmpty(), unverified.isEmpty()
                ? "every citation lies in the evidence its arm saw, and every claim quotes its span exactly"
                : "unverified citations in " + unverified));
        var leaks = first.stream().flatMap(row -> leaks(dialogs, row).stream()).toList();
        checks.add(new Check("context.noLeakage", leaks.isEmpty(), leaks.isEmpty()
                ? "every resolved constraint came from the current turn or a carried earlier turn of the same dialog"
                : "context leaked: " + leaks));
        var understood = first.stream().filter(row -> row.arm() == Arm.NO_CONTEXT)
                .filter(row -> dialogs.turn(row.turnId()).family().isEmpty())
                .filter(row -> row.row().outcome() != Outcome.ABSTAINED).map(TurnRow::turnId).toList();
        checks.add(new Check("control.noContextElliptical", understood.isEmpty(), understood.isEmpty()
                ? "without carried context every elliptical turn abstained"
                : "answered an elliptical turn without context: " + understood));
        var kept = first.stream().filter(row -> row.arm() == Arm.EVIDENCE_REMOVED)
                .filter(row -> row.row().evidence().stream().anyMatch(hit -> decisionTopic(hit.chunkId())))
                .map(TurnRow::turnId).toList();
        checks.add(new Check("control.evidenceRemoved", kept.isEmpty(), kept.isEmpty()
                ? "the evidence-removed control saw no chunk of " + new TreeSet<>(DECISION_TOPICS)
                : "decision-topic evidence reached the control in " + kept));
        var deterministic = first.equals(second);
        checks.add(new Check("dialog.deterministic", deterministic, deterministic
                ? "a second pass over the same root produced identical rows"
                : "a second pass produced different rows"));

        var metadata = new TreeMap<String, String>();
        var perf = measure(root, verified, checks, metadata);
        describe(dialogs, verified, metadata);
        return new Observation(first, checks, metadata, perf);
    }

    /** Runs every turn of every dialog once and returns the rows. */
    List<TurnRow> rows(Root root, HtmlDialogCases dialogs) {
        return pass(root, dialogs).rows();
    }

    /**
     * One pass.
     *
     * @param rows the rows, in dialog, turn and arm order
     * @param contexts the full contexts supplied to the B+R arm, in turn order
     * @param assembled the B+R assembly of each of those contexts
     * @param traces the B+R Neuron trace of each turn, empty when the cycle produced none
     */
    private record Pass(List<TurnRow> rows, List<DialogContext> contexts, List<Assembled> assembled,
            List<Optional<NeuronTrace>> traces) {
    }

    private Pass pass(Root root, HtmlDialogCases dialogs) {
        var rows = new ArrayList<TurnRow>();
        var contexts = new ArrayList<DialogContext>();
        var assembledTurns = new ArrayList<Assembled>();
        var traces = new ArrayList<Optional<NeuronTrace>>();
        for (var dialog : dialogs.dialogs()) {
            var advice = new EnumMap<Arm, Map<Integer, DialogContext.Advice>>(Arm.class);
            var previous = new EnumMap<Arm, Row>(Arm.class);
            var families = new EnumMap<Arm, Optional<Family>>(Arm.class);
            for (var arm : Arm.values()) {
                advice.put(arm, new HashMap<>());
                families.put(arm, Optional.empty());
            }
            for (var turn : dialog.turns()) {
                var full = new EnumMap<Arm, DialogContext>(Arm.class);
                var assembled = new EnumMap<Arm, Assembled>(Arm.class);
                for (var arm : Arm.values()) {
                    var context = arm == Arm.NO_CONTEXT ? DialogContext.alone(turn)
                            : contextTamper.apply(DialogContext.carried(dialog, turn, advice.get(arm)));
                    full.put(arm, context);
                    assembled.put(arm, assembler.assemble(context));
                }
                contexts.add(full.get(Arm.NEURON_RULES));
                assembledTurns.add(assembled.get(Arm.NEURON_RULES));

                var store = root.evidence().direct(root.memory(), assembled.get(Arm.STORE_RULES).query(),
                        HtmlDecisionExperiment.K);
                var neuron = decision.neuron(root, assembled.get(Arm.NEURON_RULES).query());
                traces.add(neuron.trace());
                var alone = decision.neuron(root, assembled.get(Arm.NO_CONTEXT).query());
                for (var arm : Arm.values()) {
                    var row = switch (arm) {
                        case STORE -> switch (store) {
                            case Failed failed -> HtmlDecisionExperiment.failedRow(probe(turn), arm.base(), "-", "-",
                                    Optional.empty(), failed.reason());
                            case Retrieved retrieved -> new Row(turn.turnId(), arm.base(), "-", "-",
                                    HtmlDecisionExperiment.hits(retrieved.chunks()), Optional.empty(),
                                    Outcome.RETRIEVED, Optional.empty(), List.of(), List.of(), "-");
                        };
                        case STORE_RULES -> switch (store) {
                            case Failed failed -> failed(turn, arm, Optional.empty(), failed.reason());
                            case Retrieved retrieved -> answer(turn, arm, assembled.get(arm), retrieved.chunks(),
                                    Optional.empty(), "-");
                        };
                        case NEURON_RULES -> neuronRow(turn, arm, assembled.get(arm), neuron, List.of());
                        case NO_CONTEXT -> neuronRow(turn, arm, assembled.get(arm), alone, List.of());
                        case EVIDENCE_REMOVED -> neuronRow(turn, arm, assembled.get(arm), neuron,
                                DECISION_TOPICS.stream().sorted().toList());
                    };
                    var family = familyOf(assembled.get(arm));
                    var first = turn.turn() == 1 || (turn.family().isPresent()
                            && !turn.family().equals(families.get(arm)));
                    var change = DialogTrace.change(Optional.ofNullable(previous.get(arm)), row, first);
                    rows.add(new TurnRow(dialog.dialogId(), turn.turn(), arm, turn.utterance(),
                            full.get(arm).render(), assembled.get(arm), row, change));
                    previous.put(arm, row);
                    families.put(arm, family);
                    if (arm != Arm.STORE) {
                        advice.get(arm).put(turn.turn(), DialogTrace.advice(row));
                    }
                }
            }
        }
        return new Pass(rows, contexts, assembledTurns, traces);
    }

    private Row neuronRow(Turn turn, Arm arm, Assembled assembled, HtmlDecisionExperiment.NeuronPath neuron,
            List<String> removedTopics) {
        if (neuron.usable().isEmpty()) {
            return failed(turn, arm, neuron.trace(), neuron.detail());
        }
        var evidence = neuron.usable().get();
        var detail = neuron.detail();
        if (!removedTopics.isEmpty()) {
            var kept = evidence.stream().filter(chunk -> !decisionTopic(chunk.chunkId())).toList();
            detail = "removed " + (evidence.size() - kept.size()) + " chunks of " + removedTopics
                    + (detail.equals("-") ? "" : "; " + detail);
            evidence = kept;
        }
        return answer(turn, arm, assembled, evidence, neuron.trace(), detail);
    }

    private Row answer(Turn turn, Arm arm, Assembled assembled, List<EvidenceChunk> evidence,
            Optional<NeuronTrace> trace, String detail) {
        var recommendation = switch (assembled) {
            case Ready ready -> decision.selector().decide(ready.decisionCase(), evidence);
            case Held held -> held.abstention();
        };
        return decision.recommendationRow(turn.turnId(), arm.base(), recommendation, evidence, trace, detail);
    }

    private Row failed(Turn turn, Arm arm, Optional<NeuronTrace> trace, String detail) {
        return HtmlDecisionExperiment.failedRow(probe(turn), arm.base(), selector().name(), selector().kind().name(),
                trace, detail);
    }

    /** A case that only carries the turn id, for rows that never reached a selector. */
    private static HtmlDecisionCases.DecisionCase probe(Turn turn) {
        return new HtmlDecisionCases.DecisionCase(turn.turnId(), turn.family().orElse(Family.SCRIPT), List.of(),
                turn.utterance(), new EnumMap<>(Constraint.class), List.of());
    }

    private static Optional<Family> familyOf(Assembled assembled) {
        return switch (assembled) {
            case Ready ready -> Optional.of(ready.decisionCase().family());
            case Held held -> held.family();
        };
    }

    private boolean decisionTopic(String chunkId) {
        return corpus.chunk(chunkId).map(chunk -> DECISION_TOPICS.contains(chunk.topic())).orElse(false);
    }

    /**
     * The leaks of one row: a resolved constraint whose source is not the current turn or a turn the host carried for
     * this arm, a used turn that was not carried, or a no-context row that used any earlier turn.
     */
    private static List<String> leaks(HtmlDialogCases dialogs, TurnRow row) {
        var turn = dialogs.turn(row.turnId());
        var allowed = row.arm() == Arm.NO_CONTEXT ? List.<Integer>of() : turn.carried();
        var leaks = new ArrayList<String>();
        row.assembled().used().stream().filter(used -> !allowed.contains(used))
                .forEach(used -> leaks.add(row.turnId() + " " + row.arm().label() + " used turn " + used));
        if (row.assembled() instanceof Ready ready) {
            for (var source : ready.provenance()) {
                var turnId = source.substring(source.indexOf('@') + 1);
                var ok = turnId.equals(row.turnId()) || allowed.stream()
                        .anyMatch(earlier -> HtmlDialogCases.turnId(row.dialogId(), earlier).equals(turnId));
                if (!ok) {
                    leaks.add(row.turnId() + " " + row.arm().label() + " resolved " + source);
                }
            }
        }
        return leaks;
    }

    /**
     * Measures the assembler and the Neuron cycle separately over every turn per iteration, gated and validated like
     * #112: nothing is timed when a verified check failed, and every warm-up and measured result must equal the
     * verified one in full, or the row is withheld and {@code perf.matchesVerified} fails.
     */
    private List<BenchmarkRunResult> measure(Root root, Pass verified, List<Check> checks,
            Map<String, String> metadata) {
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
        var mismatches = new ArrayList<String>();
        var turns = verified.contexts().size();
        measured(STEPS.get(0), true, turns, "DialogContextAssembler.assemble per turn over the verified B+R contexts",
                mismatches, perf, metadata, () -> {
                    var out = new ArrayList<Object>();
                    for (var context : verified.contexts()) {
                        out.add(assembler.assemble(context));
                    }
                    return out;
                }, verified.assembled());
        var ready = verified.traces().stream().allMatch(Optional::isPresent);
        measured(STEPS.get(1), ready, turns, "HtmlKnowledgeCycleExperiment.execute + decide per turn for the assembled"
                + " B+R query, compared by its full trace", mismatches, perf, metadata, () -> {
                    var out = new ArrayList<Object>();
                    for (var assembled : verified.assembled()) {
                        out.add(Optional.of(decision.cycle(root, root.adapter(), assembled.query())));
                    }
                    return out;
                }, verified.traces());
        checks.add(new Check("perf.matchesVerified", mismatches.isEmpty(), mismatches.isEmpty()
                ? "every timed result equals the verified pass in full" : "timed rows withheld: " + mismatches));
        return perf;
    }

    private void measured(String name, boolean referenceReady, int turns, String timed, List<String> mismatches,
            List<BenchmarkRunResult> perf, Map<String, String> metadata, Supplier<List<Object>> workload,
            List<?> expected) {
        if (!referenceReady) {
            mismatches.add(name + ": a verified reference row did not complete");
            metadata.put("perf." + name, "withheld (a verified reference row did not complete)");
            return;
        }
        var results = new ArrayList<List<Object>>();
        var diagnostics = new TreeMap<String, String>();
        diagnostics.put("timed", timed);
        diagnostics.put("operationsPerIteration", Integer.toString(turns));
        diagnostics.put("warmupIterations", Integer.toString(HtmlDecisionExperiment.PERF_WARMUPS));
        diagnostics.put("measurementIterations", Integer.toString(HtmlDecisionExperiment.PERF_ITERATIONS));
        diagnostics.put("validation", "every warm-up and measured result is compared with the verified pass after the"
                + " window");
        diagnostics.put("gcTelemetryScope", "thread-allocated bytes: workload plus one list append per sample; GC and"
                + " RSS deltas are JVM-wide");
        BenchmarkRunResult row;
        try {
            row = collector.measure("dialog." + name, turns + " turns", HtmlDecisionExperiment.PERF_WARMUPS,
                    HtmlDecisionExperiment.PERF_ITERATIONS, turns, null, () -> results.add(workload.get()),
                    diagnostics);
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
        metadata.put("perf." + name + ".medianNanosPerTurn", format(row.latency().medianNanos() / turns));
        metadata.put("perf." + name + ".allocationSource", row.allocation().source().name());
        metadata.put("perf." + name + ".bytesPerOp", format(row.allocation().bytesPerOp()));
        metadata.put("perf." + name + ".timed", timed);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void describe(HtmlDialogCases dialogs, Pass verified, Map<String, String> metadata) {
        metadata.put("extension", EXTENSION);
        metadata.put("k", Integer.toString(HtmlDecisionExperiment.K));
        metadata.put("arms", String.join("; ", Arrays.stream(Arm.values())
                .map(arm -> arm.label() + " = " + arm.description()).toList()));
        metadata.put("selector.name", selector().name());
        metadata.put("selector.kind", selector().kind().name());
        metadata.put("selector.description", selector().description());
        metadata.put("context.version", DialogContext.VERSION);
        metadata.put("context.assembler", DialogContextAssembler.VERSION);
        metadata.put("context.maxTurns", Integer.toString(DialogContextAssembler.MAX_TURNS));
        metadata.put("context.maxBytes", Integer.toString(DialogContextAssembler.MAX_CONTEXT_BYTES));
        metadata.put("context.owner", "the evaluation host: an explicit, immutable DialogContext per turn; Neuron runs"
                + " one request-scoped cycle per turn and keeps nothing between turns");
        metadata.put("control.removedTopics", String.join(", ", DECISION_TOPICS.stream().sorted().toList()));
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("cycle.budget", HtmlKnowledgeCycleExperiment.BUDGET.toString());
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        var index = 0;
        for (var turn : dialogs.turns()) {
            metadata.put("query." + turn.turnId(), verified.assembled().get(index++).query());
        }
    }
}
