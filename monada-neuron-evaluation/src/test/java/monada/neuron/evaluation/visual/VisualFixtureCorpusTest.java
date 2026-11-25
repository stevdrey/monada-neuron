package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFixtureCorpusTest {

    private final FixtureDigests digests = new FixtureDigests();
    private final VisualFixtureCorpus corpus = VisualFixtureCorpus.open();

    private ManifestRow withHash(ManifestRow row, String id, String pixelSha256) {
        return new ManifestRow(id, row.size(), row.kind(), row.group(), row.seed(), row.formula(),
                row.goldGeometry(), row.goldTransform(), row.goldAccept(), row.goldAbstain(), row.goldLayers(),
                row.split(), pixelSha256, row.pngBytes(), row.webpLosslessBytes(), row.webpQ75Bytes());
    }

    @Test
    void theCommittedManifestOpensWithItsVersionAndRows() {
        VisualFixtureManifest manifest = corpus.manifest();

        assertEquals("visual-fixtures/1", manifest.version());
        assertEquals("visual-fixtures/1", manifest.splitKey());
        assertEquals(240, manifest.rows().size());
        assertEquals(128, manifest.rows().stream().filter(row -> row.split() == FixtureSplit.TRAIN).count());
        assertEquals(56, manifest.rows().stream().filter(row -> row.split() == FixtureSplit.VALIDATION).count());
    }

    @Test
    void loadingASplitReturnsItsHashCheckedPixelsByIdThenSize() {
        List<LoadedFixture> fixtures = corpus.load(FixtureSplit.VALIDATION);

        assertEquals(56, fixtures.size());
        for (var i = 0; i < fixtures.size(); i++) {
            LoadedFixture fixture = fixtures.get(i);
            ManifestRow row = fixture.row();
            assertEquals(FixtureSplit.VALIDATION, row.split());
            assertEquals(row.size(), fixture.width());
            assertEquals(row.size(), fixture.height());
            assertEquals(row.kind() == FixtureKind.MASK ? 1 : 4, fixture.channels());
            assertEquals(row.size() * row.size() * fixture.channels(), fixture.pixels().length);
            assertEquals(row.pixelSha256(), digests.sha256Hex(fixture.pixels()));
            if (i > 0) {
                ManifestRow previous = fixtures.get(i - 1).row();
                int byId = previous.id().compareTo(row.id());
                assertTrue(byId < 0 || (byId == 0 && previous.size() < row.size()), row.id());
            }
        }
    }

    @Test
    void aLoadedFixtureCopiesItsPixelsInAndOut() {
        var source = new byte[] {0, (byte) 255, 0, 0};
        var fixture = new LoadedFixture(corpus.manifest().rows().getFirst(), 2, 2, 1, source);

        source[0] = 7;
        fixture.pixels()[1] = 7;

        assertArrayEquals(new byte[] {0, (byte) 255, 0, 0}, fixture.pixels());
    }

    @Test
    void aFixtureWhosePixelsDoNotHashToItsRowIsNeverReturned() {
        var rows = new ArrayList<>(corpus.manifest().rows());
        int at = rows.indexOf(rows.stream().filter(row -> row.id().equals("m-pos-06") && row.size() == 64)
                .findFirst().orElseThrow());
        rows.set(at, withHash(rows.get(at), "m-pos-06", "0".repeat(64)));
        VisualFixtureCorpus tampered = VisualFixtureCorpus.over(VisualFixtureManifest.of(rows));

        var mismatch = assertThrows(IllegalStateException.class, () -> tampered.load(FixtureSplit.VALIDATION));

        assertTrue(mismatch.getMessage().startsWith("fixture m-pos-06 at size 64 regenerated to pixel hash "),
                mismatch.getMessage());
        assertEquals(128, tampered.load(FixtureSplit.TRAIN).size());
    }

    @Test
    void aRowWhoseIdIsNotInTheCatalogueIsRejected() {
        ManifestRow first = corpus.manifest().rows().stream().filter(row -> row.split() == FixtureSplit.TRAIN)
                .findFirst().orElseThrow();
        VisualFixtureCorpus unknown = VisualFixtureCorpus.over(
                VisualFixtureManifest.of(List.of(withHash(first, "m-pos-99", first.pixelSha256()))));

        var missing = assertThrows(IllegalStateException.class, () -> unknown.load(FixtureSplit.TRAIN));

        assertEquals("fixture m-pos-99 at size " + first.size() + " is not in the catalogue", missing.getMessage());
    }

    @Test
    void aLoadMustNameItsSplit() {
        assertThrows(NullPointerException.class, () -> corpus.load(null));
    }
}
