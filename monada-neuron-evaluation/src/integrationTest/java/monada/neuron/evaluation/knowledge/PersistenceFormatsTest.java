package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Row;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        var snapshot = new Snapshot("profile", List.of(new Row("readback:chk:a#00", "direct", 0, "chk:a#00", score)));

        var parsed = Snapshot.parseTsv(snapshot.toTsv());
        assertEquals(snapshot, parsed);
        assertEquals(Double.doubleToRawLongBits(score), Double.doubleToRawLongBits(parsed.rows().getFirst().score()));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.parseTsv("no header"));
    }

    @Test
    void phaseResultRoundTripsWithFlattenedDetails() {
        var text = Result.render(List.of(new Check("a.check", false, "line one\nline\ttwo")), Map.of("pid", "42"));

        var parsed = Result.parse(text);
        assertEquals(List.of(new Check("a.check", false, "line one line two")), parsed.checks());
        assertEquals(Map.of("pid", "42"), parsed.metadata());
        assertThrows(IllegalArgumentException.class, () -> Result.parse("check\tonly-two"));
    }
}
