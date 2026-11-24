package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay check of gate V1: a fresh generation reproduces every replayable column of the committed manifest, twice
 * in one JVM. The Gradle test JVM is not the JVM that froze the manifest, so this is also the fresh JVM replay. It runs
 * on every split because nothing is selected or scored here (contract Section 8.10).
 */
class VisualFixtureReplayTest {

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final VisualFixtureCorpus corpus = VisualFixtureCorpus.open();

    private List<ManifestRow> regenerate() {
        var renderer = new FixtureRenderer(catalogue);
        var rows = new ArrayList<ManifestRow>(240);
        for (ManifestRow committed : corpus.manifest().rows()) {
            FixtureDefinition definition = catalogue.find(committed.id()).orElseThrow();
            rows.add(renderer.row(definition, committed.size(), renderer.pixels(definition, committed.size()),
                    committed.pngBytes(), committed.webpLosslessBytes(), committed.webpQ75Bytes()));
        }
        return rows;
    }

    @Test
    void aFreshGenerationReproducesEveryReplayableColumnTwice() {
        List<ManifestRow> committed = corpus.manifest().rows();
        List<ManifestRow> first = regenerate();
        List<ManifestRow> second = regenerate();

        assertEquals(240, committed.size());
        for (var i = 0; i < committed.size(); i++) {
            String name = committed.get(i).id() + " at " + committed.get(i).size();
            assertEquals(committed.get(i).replayableColumns(), first.get(i).replayableColumns(), name);
            assertEquals(committed.get(i).replayableColumns(), second.get(i).replayableColumns(), name);
        }
        assertEquals(corpus.manifest().sha256(), VisualFixtureManifest.of(first).sha256());
    }

    @Test
    void theManifestHoldsBothSizesOfEveryCatalogueId() {
        List<ManifestRow> rows = corpus.manifest().rows();
        List<String> expected = new ArrayList<>();
        catalogue.definitions().stream().map(FixtureDefinition::id).sorted()
                .forEach(id -> FixtureCatalogue.SIZES.forEach(size -> expected.add(id + "@" + size)));

        assertEquals(expected, rows.stream().map(row -> row.id() + "@" + row.size()).toList());
    }

    @Test
    void theCorpusStaysWithinItsFrozenBounds() {
        List<LoadedFixture> fixtures = corpus.loadEverySplitForLosslessChecks();
        long decoded = 0;
        var largest = 0;
        for (LoadedFixture fixture : fixtures) {
            byte[] pixels = fixture.pixels();
            decoded += pixels.length;
            largest = Math.max(largest, pixels.length);
            assertTrue(fixture.width() == 32 || fixture.width() == 64, fixture.row().id());
            if (fixture.row().kind() == FixtureKind.MASK) {
                for (byte value : pixels) {
                    assertTrue(value == 0 || value == (byte) 255, fixture.row().id() + " holds only 0 and 255");
                }
            }
        }

        assertEquals(240, fixtures.size());
        assertEquals(1_351_680, decoded);
        assertEquals(16_384, largest);
    }

    @Test
    void noTwoIdsSharePixelsAtEitherSize() {
        var seen = new HashMap<String, String>();
        for (ManifestRow row : corpus.manifest().rows()) {
            String other = seen.put(row.size() + ":" + row.pixelSha256(), row.id());
            assertNull(other, row.id() + " and " + other + " share their pixels at size " + row.size());
        }
    }

    @Test
    void theRecordedColumnsAreIntegersAndTheWebpColumnsAreFilledTogether() {
        List<ManifestRow> rows = corpus.manifest().rows();
        boolean measured = rows.getFirst().webpLosslessBytes().isPresent();

        for (ManifestRow row : rows) {
            assertTrue(row.pngBytes() > 0, row.id());
            assertEquals(measured, row.webpLosslessBytes().isPresent(), row.id());
            assertEquals(measured, row.webpQ75Bytes().isPresent(), row.id());
            assertTrue(positive(row.webpLosslessBytes()) && positive(row.webpQ75Bytes()), row.id());
        }
    }

    private boolean positive(OptionalLong bytes) {
        return bytes.isEmpty() || bytes.getAsLong() > 0;
    }
}
