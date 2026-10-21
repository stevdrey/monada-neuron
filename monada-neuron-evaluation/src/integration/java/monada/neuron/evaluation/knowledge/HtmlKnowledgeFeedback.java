package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Observation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The phase side of the relevance-feedback experiment (Issue #110): imports the arms, writes the feedback plan into the
 * treatment arms through the Store's public {@code MonadaMemory.feedback(query, atomId, signal)}, and observes every
 * arm with {@link HtmlKnowledgeRecall}. Like the recall side it never sees a gold label: its inputs are the
 * labels-free workload and {@link FeedbackEvents}.
 */
final class HtmlKnowledgeFeedback {

    /** Name of the per-observation check that feedback-aware ranking cannot be expected to keep (see below). */
    static final String PREFIX_STABILITY = "recall.prefixStability";

    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeRecall recall;

    HtmlKnowledgeFeedback(HtmlKnowledgeCorpus corpus) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.recall = new HtmlKnowledgeRecall(corpus);
    }

    /** Returns the root of {@code arm} under {@code armsRoot}. */
    static Path root(Path armsRoot, FeedbackArm arm) {
        return armsRoot.resolve(arm.directory());
    }

    /** Imports the corpus into the fresh root of {@code arm}, exactly like every other arm (default options). */
    HtmlKnowledgeIngestion.Result ingest(Path armsRoot, FeedbackArm arm) {
        return new HtmlKnowledgeIngestion(corpus).ingest(root(armsRoot, arm));
    }

    /**
     * Records every planned event in the root of a treatment arm, in plan order, through a handle opened with the arm's
     * own key strategy. An unknown chunk or a throwing call fails {@code <arm>.feedback.applied}; the remaining events
     * are still attempted, so the read-back reports exactly what was persisted.
     *
     * @param durations receives the duration of each {@code feedback} call that returned
     */
    Check apply(Path armsRoot, FeedbackArm arm, IngestionManifest manifest, FeedbackEvents plan,
            List<Long> durations) {
        if (!arm.treatment()) {
            throw new IllegalArgumentException("the control arm never receives feedback");
        }
        var root = root(armsRoot, arm);
        HtmlKnowledgePersistence.requireExistingStore(root);
        var memory = MonadaMemory.open(root, arm.options(true));
        var failures = new ArrayList<String>();
        for (var event : plan.events()) {
            var entry = manifest.entryForChunk(event.chunkId());
            if (entry.isEmpty()) {
                failures.add(event.queryId() + " " + event.chunkId() + ": chunk is not in the manifest");
                continue;
            }
            try {
                var start = System.nanoTime();
                memory.feedback(event.text(), entry.get().atomId(), event.signal());
                durations.add(System.nanoTime() - start);
            } catch (RuntimeException e) {
                failures.add(event.queryId() + " " + event.chunkId() + ": threw " + e);
            }
        }
        return new Check(arm.label() + ".feedback.applied", failures.isEmpty(), failures.isEmpty()
                ? "all " + plan.events().size() + " planned events recorded through MonadaMemory.feedback(query,"
                        + " atomId, signal) with the " + arm.strategy().label() + " key strategy"
                : failures.size() + " of " + plan.events().size() + " events not recorded: "
                        + String.join("; ", failures.subList(0, Math.min(5, failures.size()))));
    }

    /**
     * {@code profile.strategyNeutral}: every arm root opens with every arm's options, with feedback-aware ranking on and
     * off. The Store rejects an open whose options do not match the persisted encoding profile, so this shows that the
     * key strategy and the ranking flag change nothing the roots were encoded with.
     */
    Check strategyNeutral(Path armsRoot) {
        var failures = new ArrayList<String>();
        var opens = 0;
        for (var arm : FeedbackArm.values()) {
            var root = root(armsRoot, arm);
            try {
                // MonadaMemory.open would create an empty store on a missing root and pass for the wrong reason.
                HtmlKnowledgePersistence.requireExistingStore(root);
            } catch (IngestionInputException e) {
                failures.add(arm.label() + ": no Store to open (" + e.getMessage() + ")");
                continue;
            }
            for (var options : FeedbackArm.values()) {
                for (var feedbackAware : List.of(true, false)) {
                    opens++;
                    try {
                        MonadaMemory.open(root, options.options(feedbackAware));
                    } catch (RuntimeException e) {
                        failures.add(arm.label() + " with " + options.strategy().label() + " keys, feedback-aware "
                                + feedbackAware + ": " + e.getMessage());
                    }
                }
            }
        }
        return new Check("profile.strategyNeutral", failures.isEmpty(), failures.isEmpty()
                ? "all " + opens + " opens (every arm root with every key strategy, feedback-aware ranking on and off)"
                        + " matched the persisted encoding profile"
                : String.join("; ", failures));
    }

    /**
     * Observes the root of {@code arm} with its own options, feedback-aware ranking on or off. Checks are prefixed with
     * {@code label}. When {@code prefixDiagnostic} is set, {@value #PREFIX_STABILITY} is moved to the metadata entry
     * {@code diag.<label>.prefixStability}: with a positive adjustment the Store widens its candidate pool with the
     * requested limit, so a boosted atom can enter a limit-5 list without entering the limit-1 list, and that Store
     * behavior must be reported, not fail the run.
     */
    Observed observe(Path armsRoot, FeedbackArm arm, boolean feedbackAware, String label, IngestionManifest manifest,
            String queryHash, List<QueryText> workload, boolean prefixDiagnostic) {
        var observation = recall.observe(root(armsRoot, arm), arm.options(feedbackAware), manifest, queryHash,
                workload);
        var checks = new ArrayList<Check>();
        var metadata = new LinkedHashMap<String, String>();
        for (var check : observation.checks()) {
            if (prefixDiagnostic && check.name().equals(PREFIX_STABILITY)) {
                metadata.put("diag." + label + ".prefixStability", (check.passed() ? "stable: " : "unstable: ")
                        + check.detail());
            } else {
                checks.add(new Check(label + "." + check.name(), check.passed(), check.detail()));
            }
        }
        observation.metadata().forEach((key, value) -> metadata.put(label + "." + key, value));
        return new Observed(observation, checks, metadata);
    }

    /** Reads the persistence checks of {@code arm}'s root back in a restarted process, prefixed with the arm label. */
    List<Check> readback(Path armsRoot, FeedbackArm arm, IngestionManifest manifest) {
        return recall.readback(root(armsRoot, arm), manifest).stream()
                .map(check -> new Check(arm.label() + "." + check.name(), check.passed(), check.detail())).toList();
    }

    /**
     * One observation with its checks prefixed and, when requested, the prefix-stability check moved to metadata.
     *
     * @param observation the raw observation, whose snapshot is the evidence
     * @param checks the prefixed checks
     * @param metadata the prefixed metadata
     */
    record Observed(Observation observation, List<Check> checks, Map<String, String> metadata) {

        Observed {
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }
    }
}
