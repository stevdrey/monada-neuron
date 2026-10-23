package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.ScratchImport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gates that withhold a timing row whose verified reference is missing or semantically invalid. */
class HtmlKnowledgeScalingTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeScalingCorpus.corpus(HtmlKnowledgeCorpus.bundled(),
            25, ScalingDistractorGenerator.DEFAULT_SEED);

    private final HtmlKnowledgeScaling scaling = new HtmlKnowledgeScaling(HtmlKnowledgeScaling.Sampling.QUICK);

    @Test
    void aFailedBaselineCheckBlocksItsRows() {
        var passing = new Check("recall.completed", true, "ok");
        assertEquals(Optional.empty(), HtmlKnowledgeScaling.baselineBlocker(List.of(passing)));
        var blocker = HtmlKnowledgeScaling.baselineBlocker(List.of(passing,
                new Check("n25.neuron.b2EqualsB2c", false, "differs")));
        assertTrue(blocker.orElseThrow().contains("n25.neuron.b2EqualsB2c"), blocker.toString());
    }

    @Test
    void aScratchImportIsCheckedAgainstWhatItsRootPersisted(@TempDir Path directory) throws IOException {
        var ingestion = new HtmlKnowledgeIngestion(CORPUS);
        var root = Files.createDirectories(directory.resolve("imported"));
        var memory = MonadaMemory.open(root);
        var atoms = new ArrayList<KnowledgeAtom>();
        CORPUS.chunks().forEach(chunk -> atoms.add(memory.remember(chunk.text())));
        assertEquals(Optional.empty(), HtmlKnowledgeScaling.scratchMismatch(ingestion, CORPUS,
                new ScratchImport(root, atoms)));

        // The right atoms, but a root that persisted nothing: only the readback after the window can tell.
        var empty = Files.createDirectories(directory.resolve("empty"));
        var mismatch = HtmlKnowledgeScaling.scratchMismatch(ingestion, CORPUS, new ScratchImport(empty, atoms));
        assertTrue(mismatch.orElseThrow().startsWith("the scratch root did not read back"), mismatch.toString());

        var repeated = new ArrayList<>(atoms);
        repeated.set(1, atoms.getFirst());
        assertTrue(HtmlKnowledgeScaling.faithfulImport(CORPUS, repeated).isPresent());
        assertTrue(HtmlKnowledgeScaling.faithfulImport(CORPUS, atoms.subList(1, atoms.size())).isPresent());
    }

    @Test
    void missingEvidenceOrAnIneligibleCitationBlocksTheAnswerRows() {
        var chunk = CORPUS.chunks().getFirst();
        var evidence = List.of(new EvidenceChunk(chunk.chunkId(), 1, 1.0, chunk.text()));
        var workload = List.of(new QueryText("q:one", "first"), new QueryText("q:two", "second"));
        var quote = chunk.text().substring(0, 10);
        var valid = Optional.<GroundedAnswer>of(new Answered(List.of(new Claim(quote,
                List.of(new Citation(chunk.chunkId(), 0, 10))))));
        assertEquals(Optional.empty(), scaling.answerBlocker(workload, List.of(valid, valid),
                List.of(evidence, evidence)));

        var missing = scaling.answerBlocker(workload, List.of(valid, Optional.empty()), List.of(evidence, List.of()));
        assertTrue(missing.orElseThrow().contains("q:two"), missing.toString());

        var outside = Optional.<GroundedAnswer>of(new Answered(List.of(new Claim(quote,
                List.of(new Citation("chk:not-in-evidence#00", 0, 10))))));
        var ineligible = scaling.answerBlocker(workload, List.of(valid, outside), List.of(evidence, evidence));
        assertTrue(ineligible.orElseThrow().startsWith("a verified answer cites outside E(q) for q:two"),
                ineligible.toString());
    }
}
