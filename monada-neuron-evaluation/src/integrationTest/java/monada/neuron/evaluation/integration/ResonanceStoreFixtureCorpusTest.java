package monada.neuron.evaluation.integration;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceStoreFixtureCorpusTest {

    @Test
    void fixtureIsInternallyConsistentAndVersioned() {
        assertDoesNotThrow(ResonanceStoreFixtureCorpus::validate);
        assertEquals("rs-integration-v1", ResonanceStoreFixtureCorpus.VERSION);
        assertTrue(ResonanceStoreFixtureCorpus.documents().size() >= 10);
    }

    @Test
    void codecRoundTripsEveryDocumentToADistinctSignal() {
        var codec = new FixtureSignalCodec();
        var seen = new HashSet<>();

        for (var document : ResonanceStoreFixtureCorpus.documents()) {
            var signal = codec.decode(document.text());
            assertEquals(document.label(), codec.labelOf(signal).orElseThrow());
            assertTrue(seen.add(signal), "duplicate decoded signal for " + document.label());
        }
    }

    @Test
    void codecEncodesQueriesByFrequencyAndRejectsForeignContent() {
        var codec = new FixtureSignalCodec();
        var query = ResonanceStoreFixtureCorpus.query("solar-energy");

        assertEquals(query.text(), codec.encode(query.signal()));
        assertEquals(FixtureSignalCodec.UNMAPPED_QUERY, codec.encode(ResonanceStoreFixtureCorpus.querySignal(99.0)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not in the corpus"));
    }
}
