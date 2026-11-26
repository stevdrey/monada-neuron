package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        LoadedFixture loaded = corpus.load(FixtureSplit.TRAIN).getFirst();
        byte[] expected = loaded.pixels();
        byte[] source = expected.clone();
        var fixture = new LoadedFixture(loaded.row(), loaded.width(), loaded.height(), loaded.channels(), source);

        source[0] ^= 0x7f;
        fixture.pixels()[1] ^= 0x7f;

        assertArrayEquals(expected, fixture.pixels());
    }

    @Test
    void aLoadedFixtureWhoseDimensionsDisagreeWithItsRowCannotBeBuilt() {
        // covers: issue #217, guard 3
        LoadedFixture loaded = corpus.load(FixtureSplit.TRAIN).getFirst();
        ManifestRow row = loaded.row();
        int size = row.size();
        int channels = row.kind().channels();
        byte[] pixels = loaded.pixels();

        assertEquals("fixture " + row.id() + " at size " + size + " cannot be " + (size + 1) + " by " + size
                        + " with " + channels + " channels",
                assertThrows(IllegalArgumentException.class,
                        () -> new LoadedFixture(row, size + 1, size, channels, pixels)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new LoadedFixture(row, size, size - 1, channels, pixels));
        assertThrows(IllegalArgumentException.class, () -> new LoadedFixture(row, size, size, channels + 1, pixels));
        assertEquals("fixture " + row.id() + " at size " + size + " needs " + pixels.length + " pixel bytes, got "
                        + (pixels.length - 1),
                assertThrows(IllegalArgumentException.class, () -> new LoadedFixture(row, size, size, channels,
                        Arrays.copyOf(pixels, pixels.length - 1))).getMessage());
    }

    @Test
    void aLoadedFixtureWhoseForgedRowOverflowsTheByteCountCannotBeBuilt() {
        ManifestRow row = corpus.load(FixtureSplit.TRAIN).getFirst().row();
        var huge = new ManifestRow(row.id(), 65536, row.kind(), row.group(), row.seed(), row.formula(),
                row.goldGeometry(), row.goldTransform(), row.goldAccept(), row.goldAbstain(), row.goldLayers(),
                row.split(), row.pixelSha256(), row.pngBytes(), row.webpLosslessBytes(), row.webpQ75Bytes());

        assertThrows(ArithmeticException.class,
                () -> new LoadedFixture(huge, 65536, 65536, huge.kind().channels(), new byte[0]));
    }

    @Test
    void aLoadedFixtureWhosePixelsDoNotHashToItsRowCannotBeBuilt() {
        // covers: issue #217, guard 3
        LoadedFixture loaded = corpus.load(FixtureSplit.TRAIN).getFirst();
        byte[] pixels = loaded.pixels();
        pixels[0] ^= 0x7f;

        var mismatch = assertThrows(IllegalStateException.class, () -> new LoadedFixture(loaded.row(),
                loaded.width(), loaded.height(), loaded.channels(), pixels));

        assertEquals("fixture " + loaded.row().id() + " at size " + loaded.row().size()
                + " regenerated to pixel hash " + digests.sha256Hex(pixels) + " but its manifest row says "
                + loaded.row().pixelSha256(), mismatch.getMessage());
    }

    @Test
    void twoLoadedFixturesWithEqualRowsAndEqualBytesAreEqual() {
        // covers: issue #217, guard 3
        List<LoadedFixture> first = corpus.load(FixtureSplit.TRAIN);
        List<LoadedFixture> second = corpus.load(FixtureSplit.TRAIN);

        assertEquals(first, second);
        assertEquals(first.getFirst().hashCode(), second.getFirst().hashCode());
        assertNotEquals(first.get(0), first.get(1));
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
