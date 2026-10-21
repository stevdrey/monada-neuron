package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.OpaqueReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The L4 evidence of one root (Issue #109): the limit-5 result list of B1 or B2 for one query, with the content the
 * Store returned for every chunk, so an answer component reads Store output and never corpus text.
 *
 * <ul>
 *   <li><b>B1</b>: {@code MonadaMemory.resonate(q).topK(5).threshold(t)}; each result's atom must map through the
 *       manifest, and its content must hash to the corpus {@code sha256} of that chunk (source fidelity).</li>
 *   <li><b>B2</b>: the adapter's recall returns opaque references and scores only. Its content is the B2c control's
 *       content for the same atoms, as the protocol requires; B2 and B2c must agree reference by reference with
 *       bit-exact scores, and the content passes the same fidelity rule as B1.</li>
 * </ul>
 *
 * <p>Any failure (a throw, a non-finite score, a non-{@code COMPLETE} recall, an unmapped atom, a content mismatch or a
 * B2/B2c disagreement) yields {@link Failed}: the query gets no L4 row for that layer, and corpus text is never
 * substituted. Reads no gold label. Cost per query: one Store call (B1), or one adapter recall plus one Store call per
 * query token (B2), and one SHA-256 per returned chunk.
 */
final class RecalledEvidence {

    /** The outcome of one recall. */
    sealed interface Recall permits Retrieved, Failed {
    }

    /**
     * A completed recall.
     *
     * @param chunks the limit-5 list in rank order, each with its Store-returned text
     */
    record Retrieved(List<EvidenceChunk> chunks) implements Recall {

        Retrieved {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * A failed recall.
     *
     * @param reason what failed
     */
    record Failed(String reason) implements Recall {

        Failed {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    private final HtmlKnowledgeCorpus corpus;
    private final IngestionManifest manifest;
    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();
    private final OpaqueReference opaqueReference = new OpaqueReference();

    RecalledEvidence(HtmlKnowledgeCorpus corpus, IngestionManifest manifest) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.manifest = Objects.requireNonNull(manifest, "manifest must not be null");
    }

    /** B1 for {@code question}. */
    Recall direct(MonadaMemory memory, String question) {
        try {
            var results = memory.resonate(question).topK(HtmlKnowledgeRecall.MAX_LIMIT)
                    .threshold(HtmlKnowledgeRecall.THRESHOLD).execute().results();
            var rejected = HtmlKnowledgeRecall.rejectStoreResults(results);
            return rejected.<Recall>map(reason -> new Failed("B1 " + reason)).orElseGet(() -> verified("B1", results));
        } catch (RuntimeException e) {
            return new Failed("B1 threw " + e);
        }
    }

    /** B2 for {@code question}, with the content of the B2c control for the same atoms. */
    Recall neuron(ResonanceMemoryPort adapter, MonadaMemory memory, String question) {
        try {
            var signals = codec.querySignals(question);
            var response = adapter.recall(new ResonanceMemoryRequest(signals, HtmlKnowledgeRecall.MAX_LIMIT));
            if (response.status() != ResonanceMemoryStatus.COMPLETE) {
                return new Failed("B2 answered " + response.status());
            }
            var perSignal = new ArrayList<List<ResonanceResult>>(signals.size());
            for (var signal : signals) {
                var results = memory.resonate(codec.encode(signal)).topK(HtmlKnowledgeRecall.MAX_LIMIT)
                        .threshold(HtmlKnowledgeRecall.THRESHOLD).execute().results();
                var rejected = HtmlKnowledgeRecall.rejectStoreResults(results);
                if (rejected.isPresent()) {
                    return new Failed("B2c " + rejected.get());
                }
                perSignal.add(results);
            }
            var control = HtmlKnowledgeRecall.mergeCodecResults(perSignal, HtmlKnowledgeRecall.MAX_LIMIT);
            var neuron = response.results();
            if (neuron.size() != control.size()) {
                return new Failed("B2 returned " + neuron.size() + " results, B2c " + control.size());
            }
            for (var rank = 0; rank < neuron.size(); rank++) {
                var reference = opaqueReference.of(control.get(rank).atom().id());
                if (!neuron.get(rank).reference().equals(reference)
                        || !HtmlKnowledgeRecall.sameScore(neuron.get(rank).score(), control.get(rank).score())) {
                    return new Failed("B2 differs from B2c at rank " + (rank + 1));
                }
            }
            return verified("B2", control);
        } catch (RuntimeException e) {
            return new Failed("B2 threw " + e);
        }
    }

    /** Maps and verifies every result; the first unmapped atom, content mismatch or repeated chunk fails the recall. */
    private Recall verified(String layer, List<ResonanceResult> results) {
        var chunks = new ArrayList<EvidenceChunk>(results.size());
        var seen = new HashSet<String>();
        for (var rank = 0; rank < results.size(); rank++) {
            var result = results.get(rank);
            var chunkId = manifest.chunkIdForAtom(result.atom().id());
            if (chunkId.isEmpty()) {
                return new Failed(layer + " atom " + result.atom().id() + " is not in the manifest");
            }
            var content = result.atom().content();
            var expected = corpus.chunk(chunkId.get()).orElseThrow().sha256();
            if (!CorpusDigests.sha256(content).equals(expected)) {
                return new Failed(layer + " content of " + chunkId.get() + " does not hash to its corpus sha256");
            }
            if (!seen.add(chunkId.get())) {
                return new Failed(layer + " returned " + chunkId.get() + " twice");
            }
            chunks.add(new EvidenceChunk(chunkId.get(), rank + 1, result.score(), content));
        }
        return new Retrieved(chunks);
    }
}
