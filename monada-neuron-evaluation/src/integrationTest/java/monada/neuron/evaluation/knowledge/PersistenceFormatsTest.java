package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Row;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probe codec and the files that carry a phase's state to the next JVM. */
class PersistenceFormatsTest {

    private final HtmlKnowledgeCorpus corpus = HtmlKnowledgeCorpus.bundled();
    private final List<PersistenceProbes.Probe> probes = PersistenceProbes.of(corpus);
    private final ChunkTextProbeCodec codec = new ChunkTextProbeCodec(corpus, probes);

    @Test
    void probeCodecDecodesEveryChunkToAUniqueSignalAndBack() {
        var signals = new HashSet<Signal>();
        for (var chunk : corpus.chunks()) {
            var signal = codec.decode(chunk.text());
            assertTrue(signals.add(signal), chunk.chunkId());
            assertEquals(chunk.chunkId(), codec.chunkIdOf(signal).orElseThrow());
        }
    }

    @Test
    void probeCodecRejectsContentOfNoChunkInsteadOfMatchingIt() {
        var text = corpus.chunks().getFirst().text();

        assertThrows(IllegalArgumentException.class, () -> codec.decode(text + " "));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("unrelated content"));
        assertTrue(codec.chunkIdOf(new Signal(SignalKind.INTERMEDIATE, new FrequencyState(0.5, 9.0, 0.0))).isEmpty());
    }

    @Test
    void probeCodecEncodesEachProbeSignalToItsQueryText() {
        for (var probe : probes) {
            assertEquals(probe.query(), codec.encode(codec.querySignal(probe.probeId())));
        }
        assertEquals(ChunkTextProbeCodec.UNMAPPED_QUERY,
                codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 1.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> codec.querySignal("readback:none"));
    }

    @Test
    void probesAreOneReadbackPerChunkPlusParaphrases() {
        assertEquals(corpus.chunks().size(), probes.stream().filter(p -> p.probeId().startsWith("readback:")).count());
        assertTrue(probes.stream().anyMatch(p -> p.probeId().startsWith("paraphrase:")));
        assertTrue(probes.size() <= PersistenceProbes.MAX_PROBES);
    }

    @Test
    void probeHashChangesWithAQueryTextEvenWhenIdsAndCountStay() {
        var persistence = new HtmlKnowledgePersistence(corpus);
        var hash = persistence.probeHash();

        assertTrue(CorpusDigests.isSha256(hash));
        assertEquals(hash, new HtmlKnowledgePersistence(HtmlKnowledgeCorpus.bundled()).probeHash());
        // The canonical form is length-prefixed, so moving text between an id and its query changes the hash.
        var shifted = new CanonicalRecordDigest().field(PersistenceProbes.VERSION).field(corpus.corpusHash())
                .field(probes.size());
        for (var probe : probes) {
            shifted.field(probe.probeId() + probe.query().charAt(0)).field(probe.query().substring(1));
        }
        assertNotEquals(hash, shifted.hex());
    }

    @Test
    void manifestTsvRoundTripsAndRejectsAnotherCorpusOrMalformedRows() {
        var manifest = new IngestionManifest(corpus.version(), corpus.corpusHash(), List.of(
                new IngestionManifest.Entry("chk:a#00", "atom-1", "rs-1"),
                new IngestionManifest.Entry("chk:a#01", "atom-2", "rs-2")));
        var tsv = manifest.toTsv();

        var parsed = IngestionManifest.parseTsv(tsv, corpus.version(), corpus.corpusHash());
        assertEquals(manifest.entries(), parsed.entries());
        assertThrows(IllegalArgumentException.class,
                () -> IngestionManifest.parseTsv(tsv, corpus.version(), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> IngestionManifest.parseTsv(
                tsv.replace(IngestionManifest.HEADER, "chunk\tatom"), corpus.version(), corpus.corpusHash()));
        assertThrows(IllegalArgumentException.class, () -> IngestionManifest.parseTsv(
                tsv + "chk:a#02\tatom-3\n", corpus.version(), corpus.corpusHash()));
        assertThrows(IllegalArgumentException.class, () -> IngestionManifest.parseTsv(
                tsv + "chk:a#02\tatom-1\trs-3\n", corpus.version(), corpus.corpusHash()));
    }

    @Test
    void snapshotTsvKeepsScoresBitExact() {
        var score = Math.nextUp(0.1);
        var snapshot = new Snapshot("profile", "0".repeat(64),
                List.of(new Row("readback:chk:a#00", "direct", 0, "chk:a#00", score)));

        var parsed = Snapshot.parseTsv(snapshot.toTsv());
        assertEquals(snapshot, parsed);
        assertEquals(Double.doubleToRawLongBits(score), Double.doubleToRawLongBits(parsed.rows().getFirst().score()));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.parseTsv("no header"));
    }

    @Test
    void phaseResultRoundTripsWithFlattenedDetails() {
        var text = new Result(List.of(new Check("a.check", false, "line one\nline\ttwo")), Map.of("pid", "42")).render();

        var parsed = Result.parse(text);
        assertEquals(List.of(new Check("a.check", false, "line one line two")), parsed.checks());
        assertEquals(Map.of("pid", "42"), parsed.metadata());
        assertThrows(IllegalArgumentException.class, () -> Result.parse("check\tonly-two"));
    }

    @Test
    void anEmptyTruncatedOrInconsistentPhaseResultIsRejected() {
        var text = new Result(List.of(new Check("a", true, "x"), new Check("b", true, "y")), Map.of("pid", "1")).render();

        assertThrows(IllegalArgumentException.class, () -> Result.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Result.parse(text.substring(0, text.indexOf("check\tb"))));
        assertThrows(IllegalArgumentException.class, () -> Result.parse(text.replace("end\t2\t1", "end\t3\t1")));
        assertThrows(IllegalArgumentException.class, () -> Result.parse(new Result(List.of(), Map.of()).render()),
                "a result without any check is not a completed phase");
        assertEquals(2, Result.parse(text).checks().size());
    }

    @Test
    void anEmptySeedResultIsReportedAsAnIncompletePhase(@TempDir Path directory) throws IOException {
        var work = new WorkDirectory(directory);
        Files.writeString(work.result("seed"), "");
        Files.writeString(work.result("reopen"),
                new Result(List.of(new Check("ok", true, "fine")), Map.of()).render());
        var seed = new PhaseRun("seed", 1L, 1, 0L, "");
        var reopen = new PhaseRun("reopen", 2L, 0, 0L, "");
        var checks = new ArrayList<Check>();

        HtmlKnowledgePersistenceRunner.collect(seed, work, checks, new TreeMap<>());
        var restart = HtmlKnowledgePersistenceRunner.restartCheck(seed, reopen, work);

        assertEquals(List.of("seed.completed"), checks.stream().map(Check::name).toList());
        assertFalse(checks.getFirst().passed());
        assertFalse(restart.passed(), restart.detail());
    }

    @Test
    void aTruncatedManifestIsRejected() {
        var corpus = HtmlKnowledgeCorpus.bundled();
        var entries = corpus.chunks().stream()
                .map(chunk -> new IngestionManifest.Entry(chunk.chunkId(), "atom-" + chunk.chunkId(), "rs-" + chunk.chunkId()))
                .toList();
        var complete = new IngestionManifest(corpus.version(), corpus.corpusHash(), entries);
        var truncated = new IngestionManifest(corpus.version(), corpus.corpusHash(), entries.subList(0, 10));

        assertEquals(complete, complete.requireComplete(corpus));
        assertThrows(IngestionInputException.class, () -> truncated.requireComplete(corpus));
    }
}
