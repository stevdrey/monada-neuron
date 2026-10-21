package monada.neuron.evaluation.knowledge;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Arm;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Contrast;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Hit;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Row;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Snapshot;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.Evidence;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.MemoryReferenceEvidence;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The L3 evidence-use experiment of {@code html-knowledge-protocol-v1} (Issue #108, extension {@code l3-evidence-v1}):
 * each query text of the labels-free workload goes through a bounded Neuron cycle
 * {@code MEMORY_RECALL -> REASONING -> EVALUATION}, and the trace records query Signals, the port's full recall,
 * the admitted memory-stage result, the evidence the next stage carries and attaches, and the host decision.
 *
 * <p>The cycle is a subset of the canonical stage order, unchanged:
 * <ol>
 *   <li>production {@link ResonanceMemoryCognitiveStage} with limit {@value #MEMORY_LIMIT} over a
 *       {@link RecordingMemoryPort};</li>
 *   <li>the evaluation-only {@link RecalledEvidenceReasoningStage}, the explicit component that attaches
 *       {@code MemoryReferenceEvidence};</li>
 *   <li>production {@link HypothesisEvaluationCognitiveStage} with {@link ReferenceHypothesisEvaluationPolicy},
 *       selecting at most one hypothesis.</li>
 * </ol>
 * There is no {@code PERCEPTION} stage, so the memory request is exactly the codec Signals; {@code ADAPTATION} and
 * {@code ACTION} are left out because they mutate Node state or execute actions, which this experiment must not do.
 *
 * <p>This class reads only query ids and texts. It never loads the query set or any gold label, and every decision is
 * derived from stage results and the {@link RecalledReferenceResolver}.
 */
final class HtmlKnowledgeCycleExperiment {

    /** Result limit of the memory stage, the protocol's largest evaluated {@code k}. */
    static final int MEMORY_LIMIT = 5;

    /** The cycle budget of every arm except {@link Arm#TIGHT_BUDGET}; roomy for 32 query Signals and 5 results. */
    static final CognitiveBudget BUDGET = new CognitiveBudget(16, 512, 256);

    /** The stage kinds of a complete cycle, in canonical order. */
    static final List<CognitiveStageKind> STAGES = List.of(CognitiveStageKind.MEMORY_RECALL,
            CognitiveStageKind.REASONING, CognitiveStageKind.EVALUATION);

    /** The document left out of the {@link Arm#REMOVED} root; outside the conflicting-source pair. */
    static final String REMOVED_DOCUMENT = "doc:links";

    /** The trace key {@code τ}: full value equality of the {@code Signal} record (the protocol default). */
    static final String TRACE_KEY = "Signal record value equality (kind, amplitude, frequency, phase)";

    /** The fixed monad identity: every run starts from a fresh, value-equal monad. */
    static final UUID MONAD_ID = new UUID(0L, 108L);

    private static final String NONE = EvidenceTrace.NO_STATUS;

    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();

    /**
     * One reopened root under test.
     *
     * @param name {@code full} or {@code removed}
     * @param adapter the production adapter over the root
     * @param resolver the root's resolver, built from its manifest and its readback in this process
     */
    record Root(String name, ResonanceMemoryPort adapter, RecalledReferenceResolver resolver) {

        Root {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(adapter, "adapter must not be null");
            Objects.requireNonNull(resolver, "resolver must not be null");
        }
    }

    /** The result of the experiment: the trace rows, the semantic checks and descriptive metadata. */
    record Observation(Snapshot snapshot, List<Check> checks, Map<String, String> metadata) {

        Observation {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }
    }

    /** One executed cycle: the codec Signals, the recorded port calls and the result or the failure. */
    record Run(List<Signal> inputs, List<RecordingMemoryPort.Call> calls, Optional<CognitiveCycleResult> result,
            Optional<CognitiveCycleException> failure) {

        Run {
            inputs = List.copyOf(inputs);
            calls = List.copyOf(calls);
            if (result.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("a run has either a result or a failure");
            }
        }

        List<CognitiveStageResult> stageResults() {
            return result.map(CognitiveCycleResult::stageResults)
                    .orElseGet(() -> failure.orElseThrow().completedStageResults());
        }

        Optional<ResonanceMemoryStageResult> memory() {
            return stageResults().stream().filter(ResonanceMemoryStageResult.class::isInstance)
                    .map(ResonanceMemoryStageResult.class::cast).findFirst();
        }
    }

    /**
     * Runs every arm over {@code queries}, in the fixed order of {@link Arm}: for each arm the queries in workload
     * order. The memory-empty run of a text always follows its real-port run.
     *
     * @param full the full reopened root
     * @param removed the reopened root imported without {@link #REMOVED_DOCUMENT}
     * @param closed a closed adapter over the full root, which answers {@code UNAVAILABLE}
     * @param queryHash the workload's {@code queryHash}
     * @param queries the labels-free workload, in run order
     */
    Observation run(Root full, Root removed, ResonanceMemoryPort closed, String queryHash, List<QueryText> queries) {
        Objects.requireNonNull(full, "full must not be null");
        Objects.requireNonNull(removed, "removed must not be null");
        Objects.requireNonNull(closed, "closed must not be null");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("the workload is empty");
        }
        var rows = new ArrayList<Row>();
        var failures = new LinkedHashMap<String, List<String>>();
        var direct = new DirectRecalls();
        var emptyByText = new HashMap<String, Run>();
        var realByText = new HashMap<String, Run>();
        var empty = (ResonanceMemoryPort) request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of());

        for (var query : queries) {
            var real = executeTwice(Arm.MEMORY, query.queryId(), () -> execute(full.adapter(), query.text(), BUDGET),
                    failures);
            var memoryEmpty = executeTwice(Arm.MEMORY_EMPTY, query.queryId(),
                    () -> execute(empty, query.text(), BUDGET), failures);
            realByText.put(query.queryId(), real);
            emptyByText.put(query.queryId(), memoryEmpty);
            rows.add(row(direct, Arm.MEMORY, query.queryId(), query.queryId(), real, full, memoryEmpty,
                    Optional.of(contrast(real, memoryEmpty))));
            checkIntegrity(Arm.MEMORY, query.queryId(), real, failures);
            checkValueEquality(real, failures);
        }
        for (var query : queries) {
            var memoryEmpty = emptyByText.get(query.queryId());
            rows.add(row(direct, Arm.MEMORY_EMPTY, query.queryId(), query.queryId(), memoryEmpty, full, memoryEmpty,
                    Optional.empty()));
            checkIntegrity(Arm.MEMORY_EMPTY, query.queryId(), memoryEmpty, failures);
        }
        for (var index = 0; index < queries.size(); index++) {
            var slot = queries.get(index);
            var input = queries.get(swapIndex(index, queries.size()));
            var run = executeTwice(Arm.SWAPPED, slot.queryId(), () -> execute(full.adapter(), input.text(), BUDGET),
                    failures);
            rows.add(row(direct, Arm.SWAPPED, slot.queryId(), input.queryId(), run, full, emptyByText.get(input.queryId()),
                    Optional.empty()));
            checkSwapped(run, realByText.get(input.queryId()), slot.queryId(), input.queryId(), failures);
            checkIntegrity(Arm.SWAPPED, slot.queryId(), run, failures);
        }
        for (var query : queries) {
            var run = executeTwice(Arm.REMOVED, query.queryId(),
                    () -> execute(removed.adapter(), query.text(), BUDGET), failures);
            rows.add(row(direct, Arm.REMOVED, query.queryId(), query.queryId(), run, removed,
                    emptyByText.get(query.queryId()), Optional.empty()));
            checkIntegrity(Arm.REMOVED, query.queryId(), run, failures);
        }
        for (var query : queries) {
            var run = executeTwice(Arm.TIGHT_BUDGET, query.queryId(),
                    () -> execute(full.adapter(), query.text(), tightBudget(query.text())), failures);
            rows.add(row(direct, Arm.TIGHT_BUDGET, query.queryId(), query.queryId(), run, full,
                    emptyByText.get(query.queryId()), Optional.empty()));
            checkTight(run, realByText.get(query.queryId()), query.queryId(), failures);
            checkIntegrity(Arm.TIGHT_BUDGET, query.queryId(), run, failures);
        }
        for (var query : queries) {
            var run = executeTwice(Arm.UNAVAILABLE, query.queryId(), () -> execute(closed, query.text(), BUDGET),
                    failures);
            rows.add(row(direct, Arm.UNAVAILABLE, query.queryId(), query.queryId(), run, full,
                    emptyByText.get(query.queryId()), Optional.empty()));
            checkIntegrity(Arm.UNAVAILABLE, query.queryId(), run, failures);
        }
        var injected = new IllegalStateException("injected recall failure");
        for (var query : queries) {
            var run = executeTwice(Arm.THROWING, query.queryId(), () -> execute(request -> {
                throw injected;
            }, query.text(), BUDGET), failures);
            rows.add(row(direct, Arm.THROWING, query.queryId(), query.queryId(), run, full,
                    emptyByText.get(query.queryId()), Optional.empty()));
            checkThrowing(run, injected, query.queryId(), failures);
        }
        var snapshot = new Snapshot(queryHash, rows);
        return new Observation(snapshot, checks(snapshot, queries.size(), failures), metadata());
    }

    /** The index of the query whose text slot {@code index} receives in {@link Arm#SWAPPED}: half the set away. */
    static int swapIndex(int index, int size) {
        return (index + Math.max(1, size / 2)) % size;
    }

    /** A budget that admits the memory stage's inputs, its pass-through and one recalled result, and nothing more. */
    CognitiveBudget tightBudget(String text) {
        var signals = codec.querySignals(text).size();
        return new CognitiveBudget(BUDGET.maxSteps(), 2 * signals + 1, BUDGET.maxTraceEntries());
    }

    /**
     * Runs {@code execution} twice, each time over a fresh cycle, monad and recording port, records an
     * {@code evidence.deterministicReplay} failure when the two runs differ, and returns the first. Every arm goes
     * through here, so replay determinism is checked for all of them.
     */
    private Run executeTwice(Arm arm, String queryId, Supplier<Run> execution, Map<String, List<String>> failures) {
        var run = execution.get();
        if (!sameRun(run, execution.get())) {
            fail(failures, "evidence.deterministicReplay", arm + " " + queryId + " differs on a fresh cycle");
        }
        return run;
    }

    /**
     * Value equality of two runs. A failed run carries an exception, which has no value equality, so it is compared by
     * its inputs, recorded calls, failed stage, completed stage results, snapshot and the identity of its cause.
     */
    static boolean sameRun(Run first, Run second) {
        if (first.failure().isEmpty() || second.failure().isEmpty()) {
            return first.equals(second);
        }
        var left = first.failure().get();
        var right = second.failure().get();
        return first.inputs().equals(second.inputs()) && first.calls().equals(second.calls())
                && left.failedStage() == right.failedStage()
                && left.completedStageResults().equals(right.completedStageResults())
                && left.snapshot().equals(right.snapshot()) && left.getCause() == right.getCause();
    }

    /** Runs one fresh cycle over a fresh monad and a fresh recording port; a cycle failure is captured, not thrown. */
    Run execute(ResonanceMemoryPort port, String text, CognitiveBudget budget) {
        var inputs = codec.querySignals(text);
        var recorder = new RecordingMemoryPort(port);
        var cycle = new DeterministicCognitiveCycle(List.of(
                new ResonanceMemoryCognitiveStage(recorder, MEMORY_LIMIT),
                new RecalledEvidenceReasoningStage(MEMORY_LIMIT),
                new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 1)));
        try {
            var result = cycle.execute(new PrimaryMonad(MONAD_ID), inputs, budget);
            return new Run(inputs, recorder.calls(), Optional.of(result), Optional.empty());
        } catch (CognitiveCycleException e) {
            return new Run(inputs, recorder.calls(), Optional.empty(), Optional.of(e));
        }
    }

    /**
     * Derives the host decision of one run (see {@link EvidenceDecision}). Only stage results and the resolver are
     * read; a selected evidence item that is not an admitted, resolved memory reference fails closed.
     */
    EvidenceDecision decide(Run run, RecalledReferenceResolver resolver) {
        if (run.failure().isPresent()) {
            return EvidenceDecision.INCOMPLETE;
        }
        var memory = run.memory();
        if (memory.isEmpty()) {
            return EvidenceDecision.INCOMPLETE;
        }
        var status = memory.get().response().status();
        if (status == ResonanceMemoryStatus.UNAVAILABLE || status == ResonanceMemoryStatus.TIMED_OUT
                || status == ResonanceMemoryStatus.FAILED) {
            return EvidenceDecision.RECALL_FAILED;
        }
        var evaluation = run.stageResults().stream().filter(EvaluationCognitiveStageResult.class::isInstance)
                .map(EvaluationCognitiveStageResult.class::cast).findFirst();
        if (evaluation.isEmpty()) {
            return EvidenceDecision.INCOMPLETE;
        }
        var admitted = new HashSet<String>(references(memory.get().response().results()));
        var supported = false;
        for (var item : selectedEvidence(evaluation.get())) {
            if (!(item instanceof MemoryReferenceEvidence reference) || !admitted.contains(reference.reference())
                    || !resolver.resolve(reference.reference()).resolved()) {
                return EvidenceDecision.FAILED_CLOSED;
            }
            supported |= reference.relation() == EvidenceRelation.SUPPORTS;
        }
        return supported ? EvidenceDecision.SUPPORTED : EvidenceDecision.UNSUPPORTED;
    }

    private Row row(DirectRecalls direct, Arm arm, String queryId, String inputQueryId, Run run, Root root,
            Run memoryEmpty, Optional<Contrast> contrast) {
        var resolver = root.resolver();
        var request = run.calls().isEmpty() ? Optional.<ResonanceMemoryRequest>empty()
                : Optional.of(run.calls().getFirst().request());
        var requestMatches = run.calls().size() <= 1 && request
                .map(value -> value.equals(new ResonanceMemoryRequest(run.inputs(), MEMORY_LIMIT))).orElse(true);
        var recorded = run.calls().isEmpty() ? List.<Hit>of() : run.calls().getFirst().response().results().stream()
                .map(result -> new Hit(label(result.reference(), resolver), result.score())).toList();
        var memory = run.memory();
        var admittedResults = memory.map(value -> value.response().results()).orElse(List.of());
        var admittedSignals = memory.map(this::admittedSignals).orElse(List.of());
        var carried = postRecallSignals(run);
        var usedBySignal = new ArrayList<String>();
        for (var index = 0; index < admittedResults.size(); index++) {
            if (carried.contains(admittedSignals.get(index))) {
                usedBySignal.add(label(admittedResults.get(index).reference(), resolver));
            }
        }
        var attached = run.stageResults().stream().filter(ReasoningCognitiveStageResult.class::isInstance)
                .map(ReasoningCognitiveStageResult.class::cast)
                .flatMap(result -> result.hypotheses().hypotheses().stream())
                .flatMap(hypothesis -> hypothesis.evidence().stream())
                .map(item -> item instanceof MemoryReferenceEvidence reference
                        ? label(reference.reference(), resolver) : EvidenceTrace.UNRESOLVED_PREFIX + "signal-evidence")
                .toList();
        var selected = run.stageResults().stream().filter(EvaluationCognitiveStageResult.class::isInstance)
                .map(EvaluationCognitiveStageResult.class::cast).flatMap(result -> selectedEvidence(result).stream())
                .map(item -> item instanceof MemoryReferenceEvidence reference
                        ? label(reference.reference(), resolver) : EvidenceTrace.UNRESOLVED_PREFIX + "signal-evidence")
                .toList();
        return new Row(arm, queryId, inputQueryId, run.inputs().size(), requestMatches,
                run.calls().isEmpty() ? NONE : run.calls().getFirst().response().status().name(), recorded,
                memory.map(value -> value.response().status().name()).orElse(NONE),
                admittedResults.stream().map(result -> label(result.reference(), resolver)).toList(), usedBySignal,
                attached, selected, ambiguous(run, admittedSignals, memoryEmpty),
                run.stageResults().stream().map(result -> result.kind().name()).toList(), termination(run),
                run.result().map(result -> result.snapshot().signalBudgetExhausted())
                        .orElseGet(() -> run.failure().orElseThrow().snapshot().signalBudgetExhausted()),
                run.result().map(result -> result.outputSignals().size()).orElse(0), decide(run, resolver), contrast,
                arm.recallsThroughAdapter() ? Optional.of(direct.equalsB2(run, root)) : Optional.empty());
    }

    /**
     * The direct B2 recalls of one {@link #run}: one {@code adapter.recall} per distinct root and request, so arms that
     * send the same text to the same root ({@code SWAPPED}, {@code TIGHT_BUDGET} and {@code MEMORY}) share it. The
     * response is always a direct adapter call, never a recorded one.
     */
    private static final class DirectRecalls {

        private final Map<Root, Map<ResonanceMemoryRequest, ResonanceMemoryResponse>> byRoot = new IdentityHashMap<>();

        /**
         * Whether the run recorded exactly one response and it equals a direct B2 recall of the same request (the
         * codec Signals with the memory-stage limit) through the adapter of the same root.
         */
        boolean equalsB2(Run run, Root root) {
            var request = new ResonanceMemoryRequest(run.inputs(), MEMORY_LIMIT);
            var response = byRoot.computeIfAbsent(root, _ -> new HashMap<>())
                    .computeIfAbsent(request, root.adapter()::recall);
            return run.calls().size() == 1 && run.calls().getFirst().response().equals(response);
        }
    }

    /**
     * The swapped control attributes a difference to the slot, so the swapped run must recall exactly what the
     * {@code MEMORY} run of its input text recalled: the same full response and the same admitted result.
     */
    private void checkSwapped(Run swapped, Run source, String slot, String input, Map<String, List<String>> failures) {
        var admitted = swapped.memory().map(ResonanceMemoryStageResult::response);
        if (!swapped.calls().equals(source.calls()) || admitted.isEmpty()
                || !admitted.equals(source.memory().map(ResonanceMemoryStageResult::response))) {
            fail(failures, "control.swappedMatchesSource", slot + " (text of " + input + ")");
        }
    }

    /** The recalled slice of the memory stage's output: its last {@code r} Signals, one per admitted result. */
    private List<Signal> admittedSignals(ResonanceMemoryStageResult memory) {
        var outputs = memory.outputSignals();
        return outputs.subList(outputs.size() - memory.response().results().size(), outputs.size());
    }

    /** Every Signal in the output of a stage result after {@code MEMORY_RECALL}. */
    private Set<Signal> postRecallSignals(Run run) {
        var signals = new HashSet<Signal>();
        run.stageResults().stream().filter(result -> result.kind() != CognitiveStageKind.MEMORY_RECALL)
                .forEach(result -> signals.addAll(result.outputSignals()));
        return signals;
    }

    /**
     * The protocol's ambiguity rule: two admitted Signals share a key, an admitted Signal shares one with a cycle input,
     * or the memory-empty run of the same text reproduces it after {@code MEMORY_RECALL}. There is no
     * {@code PERCEPTION} stage, so the cycle inputs are the only perception-side Signals.
     */
    private boolean ambiguous(Run run, List<Signal> admittedSignals, Run memoryEmpty) {
        var withoutMemory = postRecallSignals(memoryEmpty);
        memoryEmpty.result().ifPresent(result -> withoutMemory.addAll(result.outputSignals()));
        return !EvidenceTrace.distinct(admittedSignals) || admittedSignals.stream()
                .anyMatch(signal -> run.inputs().contains(signal) || withoutMemory.contains(signal));
    }

    private String termination(Run run) {
        return run.result().map(result -> result.termination().name())
                .orElseGet(() -> "FAILED:" + run.failure().orElseThrow().failedStage().name());
    }

    private List<Evidence> selectedEvidence(EvaluationCognitiveStageResult result) {
        var evaluated = result.evaluation().evaluated().hypotheses();
        return result.evaluation().selected().stream()
                .flatMap(selected -> evaluated.stream().filter(hypothesis -> hypothesis.sequence()
                        == selected.sequence()))
                .flatMap(hypothesis -> hypothesis.evidence().stream()).toList();
    }

    private static List<String> references(List<ResonanceMemoryResult> results) {
        return results.stream().map(ResonanceMemoryResult::reference).toList();
    }

    private static String label(String reference, RecalledReferenceResolver resolver) {
        var resolution = resolver.resolve(reference);
        return resolution.resolved() ? resolution.chunkId().orElseThrow()
                : EvidenceTrace.UNRESOLVED_PREFIX + reference;
    }

    /**
     * The memory-empty contrast: the ordered post-recall stage results of both runs by full value equality, split into
     * Signals and typed artifacts, plus the cycle output Signals and the termination.
     */
    private Contrast contrast(Run real, Run empty) {
        var left = postRecall(real);
        var right = postRecall(empty);
        var differsInSignals = !outputs(left).equals(outputs(right))
                || !real.result().map(CognitiveCycleResult::outputSignals)
                        .equals(empty.result().map(CognitiveCycleResult::outputSignals));
        var differsInArtifacts = !artifacts(left).equals(artifacts(right));
        var first = NONE;
        for (var index = 0; index < Math.max(left.size(), right.size()); index++) {
            if (index >= left.size() || index >= right.size() || !left.get(index).equals(right.get(index))) {
                first = (index < left.size() ? left : right).get(index).kind().name();
                break;
            }
        }
        var terminationDiffers = !termination(real).equals(termination(empty));
        return new Contrast(differsInSignals || differsInArtifacts || left.size() != right.size(), differsInSignals,
                differsInArtifacts, first, real.result().map(result -> result.outputSignals().size()).orElse(0),
                empty.result().map(result -> result.outputSignals().size()).orElse(0), terminationDiffers);
    }

    private static List<CognitiveStageResult> postRecall(Run run) {
        return run.stageResults().stream().filter(result -> result.kind() != CognitiveStageKind.MEMORY_RECALL)
                .toList();
    }

    private static List<List<Signal>> outputs(List<CognitiveStageResult> results) {
        return results.stream().map(CognitiveStageResult::outputSignals).toList();
    }

    /** The typed artifact of each stage result: hypotheses, an evaluation, or the result itself for any other kind. */
    private static List<Object> artifacts(List<CognitiveStageResult> results) {
        return results.stream().map(result -> switch (result) {
            case ReasoningCognitiveStageResult reasoning -> (Object) reasoning.hypotheses();
            case EvaluationCognitiveStageResult evaluation -> evaluation.evaluation();
            default -> result;
        }).toList();
    }

    /**
     * Records the trace-integrity violations of one run of {@code arm} for {@code queryId}. Every arm except
     * {@link Arm#THROWING}, which has no memory result, is checked; its references are resolved on its row against
     * its own root's resolver ({@code evidence.referencesResolved}).
     */
    private void checkIntegrity(Arm arm, String queryId, Run run, Map<String, List<String>> failures) {
        integrityViolations(run).forEach(violation -> fail(failures, "evidence.traceIntegrity", arm + " " + queryId
                + ": " + violation));
    }

    /**
     * The trace-integrity violations of one run ({@code l3-evidence-v1}): exactly one recorded port call and a memory
     * result; the admitted results are an ordered prefix of the recorded response; the recalled slice of the memory
     * output equals their Signals in order; every attached {@code MemoryReferenceEvidence} names an admitted
     * reference. Whether admitted references resolve is {@code evidence.referencesResolved}, judged on the rows.
     */
    List<String> integrityViolations(Run run) {
        var violations = new ArrayList<String>();
        var memory = run.memory();
        if (memory.isEmpty() || run.calls().size() != 1) {
            violations.add("no memory result or not exactly one port call");
            return violations;
        }
        var recorded = run.calls().getFirst().response().results();
        var admitted = memory.get().response().results();
        if (admitted.size() > recorded.size() || !recorded.subList(0, admitted.size()).equals(admitted)) {
            violations.add("admitted results are not a prefix of the recorded response");
        }
        if (!admittedSignals(memory.get()).equals(admitted.stream().map(ResonanceMemoryResult::signal).toList())) {
            violations.add("the recalled output slice differs from the admitted Signals");
        }
        var references = new HashSet<String>(references(admitted));
        run.stageResults().stream().filter(ReasoningCognitiveStageResult.class::isInstance)
                .map(ReasoningCognitiveStageResult.class::cast)
                .flatMap(result -> result.hypotheses().hypotheses().stream())
                .flatMap(hypothesis -> hypothesis.evidence().stream())
                .filter(item -> !(item instanceof MemoryReferenceEvidence reference)
                        || !references.contains(reference.reference()))
                .forEach(item -> violations.add("attached evidence " + item + " is not an admitted reference"));
        return violations;
    }

    /** The memory-empty contrast compares by value only: every compared component must be a record. */
    private void checkValueEquality(Run run, Map<String, List<String>> failures) {
        for (var result : postRecall(run)) {
            if (!result.getClass().isRecord() || artifacts(List.of(result)).stream()
                    .anyMatch(artifact -> !artifact.getClass().isRecord())) {
                fail(failures, "contrast.valueEquality", result.getClass().getName() + " has no value equality");
            }
        }
    }

    private void checkTight(Run run, Run real, String queryId, Map<String, List<String>> failures) {
        var kinds = run.stageResults().stream().map(CognitiveStageResult::kind).toList();
        var memory = run.memory();
        var recorded = run.calls().size() == 1 ? run.calls().getFirst().response().results() : List.of();
        var admitted = memory.map(value -> value.response().results()).orElse(List.of());
        var exhausted = run.result().map(result -> result.termination()
                == CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED && result.snapshot().signalBudgetExhausted())
                .orElse(false);
        var prefix = admitted.size() <= 1 && admitted.size() <= recorded.size()
                && recorded.subList(0, admitted.size()).equals(admitted)
                && real.calls().size() == 1 && real.calls().getFirst().response().results().equals(recorded);
        var partial = recorded.size() < 2 || memory.map(value -> value.response().status()
                == ResonanceMemoryStatus.PARTIAL).orElse(false);
        if (!exhausted || !prefix || !partial || kinds.isEmpty() || kinds.getFirst() != CognitiveStageKind.MEMORY_RECALL
                || kinds.contains(CognitiveStageKind.EVALUATION)) {
            fail(failures, "control.tightBudgetExhausts", queryId + " stages " + kinds + ", exhausted " + exhausted
                    + ", admitted prefix " + prefix + ", partial " + partial);
        }
    }

    private void checkThrowing(Run run, RuntimeException injected, String queryId,
            Map<String, List<String>> failures) {
        var failure = run.failure();
        if (failure.isEmpty() || failure.get().failedStage() != CognitiveStageKind.MEMORY_RECALL
                || failure.get().getCause() != injected || !failure.get().completedStageResults().isEmpty()
                || failure.get().snapshot().outcome() != CognitiveCycleOutcome.FAILURE) {
            fail(failures, "control.failurePropagates", queryId + " " + failure.map(Throwable::toString)
                    .orElse("did not fail"));
        }
    }

    private List<Check> checks(Snapshot snapshot, int queries, Map<String, List<String>> failures) {
        snapshot.rows().stream().filter(row -> !row.arm().recordedStatusConforms(row.recordedStatus()))
                .forEach(row -> fail(failures, "evidence.recallCompleted", row.arm() + " " + row.queryId()
                        + " recorded " + row.recordedStatus() + ", its arm requires "
                        + row.arm().expectedRecordedStatusName()));
        snapshot.rows().stream().filter(row -> !row.requestMatchesCodec())
                .forEach(row -> fail(failures, "evidence.requestMatchesCodec", row.arm() + " " + row.queryId()));
        for (var arm : Arm.values()) {
            if (!arm.expectsFullCycle()) {
                continue; // judged by the arm's own control check
            }
            for (var row : snapshot.rows(arm)) {
                if (!row.stages().equals(STAGES.stream().map(Enum::name).toList())) {
                    fail(failures, "evidence.stageList", arm + " " + row.queryId() + " " + row.stages());
                }
                if (!row.termination().equals(CognitiveCycleTermination.COMPLETED.name())) {
                    fail(failures, "evidence.termination", arm + " " + row.queryId() + " " + row.termination());
                }
            }
        }
        snapshot.rows().forEach(row -> unresolved(row).ifPresent(problem ->
                fail(failures, "evidence.referencesResolved", row.arm() + " " + row.queryId() + " " + problem)));
        snapshot.rows(Arm.MEMORY_EMPTY).stream().filter(row -> row.decision() != EvidenceDecision.UNSUPPORTED
                || !row.attached().isEmpty()).forEach(row -> fail(failures, "control.memoryEmptyUnsupported",
                        row.queryId() + " " + row.decision()));
        snapshot.rows().stream().filter(row -> row.arm().recallsThroughAdapter()
                && !row.recalledEqualsB2().equals(Optional.of(true)))
                .forEach(row -> fail(failures, "evidence.recalledEqualsB2", row.arm() + " " + row.queryId()));
        var removedSlug = REMOVED_DOCUMENT.substring("doc:".length());
        snapshot.rows(Arm.REMOVED).stream().filter(row -> row.recorded().stream()
                .anyMatch(hit -> hit.chunkId().startsWith("chk:" + removedSlug + "#")))
                .forEach(row -> fail(failures, "control.removedExcludesDocument", row.queryId()));
        snapshot.rows(Arm.UNAVAILABLE).stream().filter(row -> row.decision() != EvidenceDecision.RECALL_FAILED
                || !row.attached().isEmpty())
                .forEach(row -> fail(failures, "control.unavailableRecall", row.queryId() + " " + row.decision()));

        var names = List.of("evidence.recallCompleted", "evidence.requestMatchesCodec", "evidence.recalledEqualsB2",
                "evidence.traceIntegrity", "evidence.referencesResolved", "evidence.stageList", "evidence.termination",
                "evidence.deterministicReplay", "contrast.valueEquality", "control.memoryEmptyUnsupported",
                "control.removedExcludesDocument", "control.swappedMatchesSource", "control.tightBudgetExhausts",
                "control.unavailableRecall", "control.failurePropagates");
        var expectedRows = queries * Arm.values().length;
        var checks = new ArrayList<Check>();
        checks.add(new Check("evidence.rows", snapshot.rows().size() == expectedRows, snapshot.rows().size() + " of "
                + expectedRows + " rows (" + Arm.values().length + " arms x " + queries + " queries)"));
        for (var name : names) {
            var failed = failures.getOrDefault(name, List.of());
            checks.add(new Check(name, failed.isEmpty(), failed.isEmpty() ? "held for all " + queries + " queries"
                    : failed.size() + " violations: " + String.join("; ", failed)));
        }
        return checks;
    }

    /**
     * The first reference problem of one row, if any: a {@code FAILED_CLOSED} decision, or an unresolved reference
     * anywhere the cycle carried one (the recorded response, the admitted results, the attached and the selected
     * evidence), against that row's own root.
     */
    static Optional<String> unresolved(Row row) {
        if (row.decision() == EvidenceDecision.FAILED_CLOSED) {
            return Optional.of("failed closed");
        }
        var carried = new ArrayList<String>();
        row.recorded().forEach(hit -> carried.add(hit.chunkId()));
        carried.addAll(row.admitted());
        carried.addAll(row.attached());
        carried.addAll(row.selected());
        return carried.stream().filter(label -> label.startsWith(EvidenceTrace.UNRESOLVED_PREFIX)).findFirst()
                .map(label -> "carries unresolved " + label);
    }

    private static void fail(Map<String, List<String>> failures, String check, String detail) {
        failures.computeIfAbsent(check, _ -> new ArrayList<>()).add(detail);
    }

    private Map<String, String> metadata() {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("cycle.stages", String.join(" -> ", STAGES.stream().map(Enum::name).toList())
                + " (a subset of the canonical order; no PERCEPTION, ADAPTATION or ACTION)");
        metadata.put("cycle.memoryLimit", Integer.toString(MEMORY_LIMIT));
        metadata.put("cycle.budget", "maxSteps " + BUDGET.maxSteps() + ", maxSignals " + BUDGET.maxSignals()
                + ", maxTraceEntries " + BUDGET.maxTraceEntries());
        metadata.put("cycle.tightBudget", "maxSignals 2 * querySignals + 1: the memory inputs, their pass-through and"
                + " one recalled result");
        metadata.put("cycle.reasoning", "RecalledEvidenceReasoningStage: one fixed proposition, one"
                + " MemoryReferenceEvidence(reference, SUPPORTS, " + RecalledEvidenceReasoningStage.EVIDENCE_WEIGHT
                + ") per admitted result; score not read; Signals passed through");
        metadata.put("cycle.evaluation", "HypothesisEvaluationCognitiveStage(ReferenceHypothesisEvaluationPolicy,"
                + " maxSelected 1)");
        metadata.put("cycle.monadId", MONAD_ID.toString());
        metadata.put("trace.key", TRACE_KEY);
        metadata.put("control.removedDocument", REMOVED_DOCUMENT);
        metadata.put("control.swap", "slot i receives the text of query (i + max(1, n / 2)) mod n in workload order");
        metadata.put("control.memoryEmpty", "a port answering COMPLETE with no results, limit echoed");
        metadata.put("control.unavailable", "a closed ResonanceStoreMemoryAdapter over the full root");
        metadata.put("control.throwing", "a port throwing IllegalStateException");
        metadata.put("decision.rule", "SUPPORTED when the selected hypothesis has SUPPORTS evidence whose every"
                + " reference was admitted and resolves to a verified persisted chunk; any other selected evidence"
                + " fails closed");
        return metadata;
    }
}
