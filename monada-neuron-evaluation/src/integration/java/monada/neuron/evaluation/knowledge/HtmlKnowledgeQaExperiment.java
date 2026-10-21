package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.AnswerRow;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.AnswerTrace.Outcome;
import monada.neuron.evaluation.knowledge.AnswerTrace.Snapshot;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.memory.ResonanceMemoryPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The L4 grounded-answer experiment of Issue #109 (protocol extension {@code l4-answer-v1}): for every root, evidence
 * layer ({@code B1}, {@code B2}), {@code answerK} (1, 3, 5), query and component, it binds the evidence set
 * {@code E(q)} to the first {@code answerK} chunks that layer returned, with their Store-returned text, runs the
 * component on the question and {@code E(q)} alone, and records every claim and citation with its mechanical checks.
 *
 * <p>It never reads gold labels: its inputs are the labels-free workload, the Store roots and their manifests. Scoring
 * against the gold answers and the human judgments happens in the orchestrator. The whole run is executed twice in this
 * process; any difference fails {@code answer.deterministic}. Answer latency is a diagnostic only.
 */
final class HtmlKnowledgeQaExperiment {

    /** The document removed from the {@code removed} root, as in #108. */
    static final String REMOVED_DOCUMENT = HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT;

    /**
     * One Store root under test.
     *
     * @param name {@code full} or {@code removed}
     * @param memory the direct handle (B1, and the B2c content of B2)
     * @param adapter the Neuron memory port (B2)
     * @param evidence the root's verified evidence reader
     */
    record Root(String name, MonadaMemory memory, ResonanceMemoryPort adapter, RecalledEvidence evidence) {

        Root {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(memory, "memory must not be null");
            Objects.requireNonNull(adapter, "adapter must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /**
     * Result of one run.
     *
     * @param snapshot every answer row of the first execution
     * @param checks the semantic checks
     * @param metadata components, run shape and answer-latency diagnostics
     */
    record Observation(Snapshot snapshot, List<Check> checks, Map<String, String> metadata) {

        Observation {
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }
    }

    private final List<AnswerComponent> components;

    /** The #109 components: B3, B3c and the generator test double. */
    HtmlKnowledgeQaExperiment() {
        this(List.of(new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3),
                new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3C),
                new TestDoubleAnswerGenerator().asComponent()));
    }

    HtmlKnowledgeQaExperiment(List<AnswerComponent> components) {
        this.components = List.copyOf(components);
    }

    /** Returns the components, in report order. */
    List<AnswerComponent> components() {
        return components;
    }

    /** Runs every root, layer, {@code answerK}, query and component twice and checks the result. */
    Observation run(List<Root> roots, String queryHash, List<QueryText> queries) {
        var latency = new LinkedHashMap<String, List<Long>>();
        components.forEach(component -> latency.put(component.name(), new ArrayList<>()));
        var first = execute(roots, queries, latency);
        var second = execute(roots, queries, new LinkedHashMap<>());
        var checks = new ArrayList<Check>();
        var failed = first.stream().filter(row -> row.outcome() == Outcome.RECALL_FAILED)
                .map(row -> row.root() + "/" + row.layer() + "/" + row.queryId() + ": " + row.detail())
                .distinct().toList();
        checks.add(new Check("completed", failed.isEmpty(), failed.isEmpty()
                ? "every bound recall completed with verified Store content, for every root, layer and query"
                : failed.size() + " recalls failed and are not scored: " + head(failed)));
        var ineligible = first.stream().filter(row -> row.citations().stream()
                        .anyMatch(span -> !span.eligible() || !span.exactQuote()))
                .map(row -> row.component() + " " + row.root() + "/" + row.layer() + "/k" + row.answerK() + "/"
                        + row.queryId()).toList();
        checks.add(new Check("citationsEligible", ineligible.isEmpty(), ineligible.isEmpty()
                ? "every citation of every component names a chunk of E(q), whose Store-returned bytes hash to the"
                        + " corpus sha256, with valid bounds, and quotes its claim exactly"
                : ineligible.size() + " answers carry an ineligible or inexact citation: " + head(ineligible)));
        checks.add(new Check("deterministic", first.equals(second), first.equals(second)
                ? "a second execution in this process reproduced all " + first.size() + " answer rows"
                : "a second execution differs from the first"));
        var removedCitations = first.stream().filter(row -> row.root().equals(HtmlKnowledgeAnswerPhase.REMOVED))
                .flatMap(row -> row.citations().stream())
                .filter(span -> span.citation().chunkId().startsWith(chunkPrefix(REMOVED_DOCUMENT))).toList();
        checks.add(new Check("control.removedNeverCited", removedCitations.isEmpty(), removedCitations.isEmpty()
                ? "no answer over the root without " + REMOVED_DOCUMENT + " cites one of its chunks"
                : removedCitations.size() + " citations name a removed chunk"));
        var answeredEmpty = new ArrayList<String>();
        for (var component : components) {
            for (var query : queries) {
                if (!(component.answer(query.text(), List.of()) instanceof Abstained)) {
                    answeredEmpty.add(component.name() + "/" + query.queryId());
                }
            }
        }
        checks.add(new Check("control.emptyEvidenceAbstains", answeredEmpty.isEmpty(), answeredEmpty.isEmpty()
                ? "every component abstains on every query when E(q) is empty"
                : answeredEmpty.size() + " answers without evidence: " + head(answeredEmpty)));
        var mislabelled = first.stream().filter(row -> !row.kind().equals(kind(row.component()))).count();
        var doubles = first.stream().filter(row -> row.kind() == AnswerComponent.Kind.TEST_DOUBLE).count();
        checks.add(new Check("generator.labelledTestDouble", mislabelled == 0, mislabelled == 0
                ? "all " + doubles + " generator rows are labelled TEST_DOUBLE and are never judged or scored for"
                        + " answer quality"
                : mislabelled + " rows carry a kind other than their component's"));

        var metadata = new LinkedHashMap<String, String>();
        metadata.put("components", components.stream().map(AnswerComponent::name)
                .collect(Collectors.joining(", ")));
        for (var component : components) {
            metadata.put("component." + component.name(), component.kind() + "; " + component.description()
                    + "; tuning: " + component.tuning());
            LatencyRow.wallClock(LatencyRow.samples(latency.get(component.name())), 1, "one " + component.name()
                    + " answer over E(q) of B1 at answerK 5 on the full root, one per query, recall excluded", false)
                    .describe("perf." + component.name(), metadata);
        }
        metadata.put("layers", String.join(", ", AnswerTrace.LAYERS));
        metadata.put("answerKs", AnswerTrace.ANSWER_KS.stream().map(String::valueOf)
                .collect(Collectors.joining(", ")));
        metadata.put("roots", roots.stream().map(Root::name).collect(Collectors.joining(", ")));
        metadata.put("rows", Integer.toString(first.size()));
        return new Observation(new Snapshot(queryHash, first), checks, metadata);
    }

    private List<AnswerRow> execute(List<Root> roots, List<QueryText> queries, Map<String, List<Long>> latency) {
        var rows = new ArrayList<AnswerRow>();
        for (var root : roots) {
            for (var query : queries) {
                for (var layer : AnswerTrace.LAYERS) {
                    var recall = recall(root, layer, query, HtmlKnowledgeRecall.MAX_LIMIT);
                    var lookahead = recall(root, layer, query, HtmlKnowledgeRecall.LOOKAHEAD_LIMIT);
                    for (var answerK : AnswerTrace.ANSWER_KS) {
                        for (var component : components) {
                            if (recall instanceof RecalledEvidence.Retrieved retrieved
                                    && lookahead instanceof RecalledEvidence.Retrieved ahead) {
                                var evidence = retrieved.chunks().subList(0,
                                        Math.min(answerK, retrieved.chunks().size()));
                                var scores = (answerK < HtmlKnowledgeRecall.MAX_LIMIT ? retrieved : ahead).chunks()
                                        .stream().map(EvidenceChunk::score).toList();
                                var timed = root.name().equals(HtmlKnowledgeAnswerPhase.FULL)
                                        && layer.equals("B1") && answerK == HtmlKnowledgeRecall.MAX_LIMIT;
                                rows.add(answer(root.name(), layer, answerK, query, component, evidence,
                                        tieAtCutoff(scores, answerK),
                                        timed ? latency.get(component.name()) : null));
                            } else {
                                var reason = recall instanceof RecalledEvidence.Failed failed ? failed.reason()
                                        : "lookahead: " + ((RecalledEvidence.Failed) lookahead).reason();
                                rows.add(new AnswerRow(root.name(), layer, answerK, query.queryId(), component.name(),
                                        component.kind(), List.of(), Optional.empty(), Outcome.RECALL_FAILED, reason,
                                        List.of()));
                            }
                        }
                    }
                }
            }
        }
        return rows;
    }

    /**
     * One recall of {@code layer} at {@code limit}: the limit-5 list binds {@code E(q)}, and the limit-6 lookahead serves
     * only {@code tieAtCutoff} at {@code answerK = 5}, never as evidence.
     */
    private RecalledEvidence.Recall recall(Root root, String layer, QueryText query, int limit) {
        return layer.equals("B1") ? root.evidence().direct(root.memory(), query.text(), limit)
                : root.evidence().neuron(root.adapter(), root.memory(), query.text(), limit);
    }

    private AnswerRow answer(String root, String layer, int answerK, QueryText query, AnswerComponent component,
            List<EvidenceChunk> evidence, boolean tieAtCutoff, List<Long> samples) {
        var start = System.nanoTime();
        var answer = component.answer(query.text(), evidence);
        var elapsed = System.nanoTime() - start;
        if (samples != null) {
            samples.add(elapsed);
        }
        var hits = evidence.stream().map(chunk -> new AnswerTrace.Hit(chunk.chunkId(), chunk.score())).toList();
        var tie = Optional.of(tieAtCutoff);
        return switch (answer) {
            case Abstained abstained -> new AnswerRow(root, layer, answerK, query.queryId(), component.name(),
                    component.kind(), hits, tie, Outcome.ABSTAINED, abstained.status() + ": " + abstained.reason(),
                    List.of());
            case Answered answered -> new AnswerRow(root, layer, answerK, query.queryId(), component.name(),
                    component.kind(), hits, tie, Outcome.ANSWERED, "", answered.claims().stream()
                    .map(claim -> new ClaimRow(claim.text(), claim.citations().stream()
                            .map(citation -> verify(claim.text(), citation, evidence)).toList()))
                    .toList());
        };
    }

    /** Checks one citation against the Store-returned text of {@code E(q)}. */
    CitedSpan verify(String claimText, Citation citation, List<EvidenceChunk> evidence) {
        var chunk = evidence.stream().filter(candidate -> candidate.chunkId().equals(citation.chunkId())).findFirst();
        if (chunk.isEmpty()) {
            return new CitedSpan(citation, false, false);
        }
        var text = chunk.get().text();
        var length = text.codePointCount(0, text.length());
        if (citation.end() > length) {
            return new CitedSpan(citation, false, false);
        }
        var cited = text.substring(text.offsetByCodePoints(0, citation.start()),
                text.offsetByCodePoints(0, citation.end()));
        return new CitedSpan(citation, true, cited.equals(claimText));
    }

    private AnswerComponent.Kind kind(String name) {
        return components.stream().filter(component -> component.name().equals(name)).findFirst().orElseThrow()
                .kind();
    }

    /**
     * The protocol's {@code tieAtCutoff}: the scores at ranks {@code k} and {@code k + 1} are bit-exactly equal, so the
     * cutoff splits a tie group. The same rule as the scorer of judged recall, applied here without any gold label.
     */
    static boolean tieAtCutoff(List<Double> scores, int k) {
        return scores.size() > k && HtmlKnowledgeRecall.sameScore(scores.get(k - 1), scores.get(k));
    }

    /** Returns the chunk id prefix of {@code documentId}: {@code doc:links} gives {@code chk:links#}. */
    static String chunkPrefix(String documentId) {
        return "chk:" + documentId.substring("doc:".length()) + "#";
    }

    private static String head(List<String> values) {
        return String.join("; ", values.subList(0, Math.min(5, values.size())))
                + (values.size() > 5 ? "; ..." : "");
    }
}
