package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFixtureManifestTest {

    private static final String HASH = "ab".repeat(32);

    private final FixtureDigests digests = new FixtureDigests();

    private ManifestRow row(String id, int size) {
        return new ManifestRow(id, size, FixtureKind.MASK, FixtureGroup.POSITIVE, 42L, "render(gold)",
                "circle(2048,2048,2048)", "transform(0x0.0p0,0x0.0p0,0x1.8p-1,0x0.0p0)", "{circle}", "{}", "NONE",
                FixtureSplit.TRAIN, HASH, 120L, OptionalLong.empty(), OptionalLong.of(77L));
    }

    private List<String> valid() {
        return new ArrayList<>(VisualFixtureManifest.of(
                List.of(row("m-pos-01", 32), row("m-pos-01", 64), row("m-pos-03", 32))).lines());
    }

    /** Recomputes the header hash, so a test reaches the defect it plants instead of the hash check. */
    private List<String> rehashed(List<String> lines) {
        var body = new StringBuilder();
        for (String line : lines.subList(3, lines.size())) {
            body.append(line).append('\n');
        }
        lines.set(2, "# sha256: " + digests.sha256Hex(body.toString().getBytes(StandardCharsets.UTF_8)));
        return lines;
    }

    private void assertDefect(List<String> lines, String expectedMessage) {
        var defect = assertThrows(IllegalStateException.class, () -> VisualFixtureManifest.parse(lines));
        assertTrue(defect.getMessage().startsWith(expectedMessage), defect.getMessage());
    }

    @Test
    void aWrittenManifestParsesBackToTheSameRowsAndHash() {
        List<String> lines = valid();

        VisualFixtureManifest manifest = VisualFixtureManifest.parse(lines);

        assertEquals("# visual-fixtures/1", lines.get(0));
        assertEquals("# split-key: visual-fixtures/1", lines.get(1));
        assertEquals(String.join("\t", VisualFixtureManifest.COLUMNS), lines.get(3));
        assertEquals(16, VisualFixtureManifest.COLUMNS.size());
        assertEquals("visual-fixtures/1", manifest.version());
        assertEquals("visual-fixtures/1", manifest.splitKey());
        assertEquals(lines.get(2), "# sha256: " + manifest.sha256());
        assertEquals(List.of(row("m-pos-01", 32), row("m-pos-01", 64), row("m-pos-03", 32)), manifest.rows());
        assertEquals(lines, manifest.lines());
    }

    @Test
    void aRowPrintsNaForAnAbsentWebpSizeAndKeepsItsFirstThirteenColumnsReplayable() {
        ManifestRow row = row("m-pos-01", 32);

        assertEquals(List.of("120", "NA", "77"), row.columns().subList(13, 16));
        assertEquals(13, row.replayableColumns().size());
        assertEquals(HASH, row.replayableColumns().getLast());
    }

    @Test
    void aWrongBodyHashIsRejectedAtTheHashLine() {
        List<String> lines = valid();
        lines.set(5, lines.get(5).replace("render(gold)", "render(lead)"));

        assertDefect(lines, "manifest line 3: body hash mismatch");
    }

    @Test
    void aWrongHeaderLineIsRejected() {
        List<String> version = valid();
        version.set(0, "# visual-fixtures/2");
        List<String> splitKey = valid();
        splitKey.set(1, "# split-key: other");
        List<String> hash = valid();
        hash.set(2, "# sha256: XYZ");

        assertDefect(version, "manifest line 1: expected '# visual-fixtures/1'");
        assertDefect(splitKey, "manifest line 2: expected '# split-key: visual-fixtures/1'");
        assertDefect(hash, "manifest line 3: expected '# sha256: <64 lowercase hex digits>'");
        assertDefect(valid().subList(0, 2), "manifest line 3: the file ends before this line");
    }

    @Test
    void unsortedRowsAreRejectedAtTheRowThatIsOutOfOrder() {
        List<String> lines = valid();
        String moved = lines.remove(4);
        lines.add(moved);

        assertDefect(rehashed(lines), "manifest line 7: unsorted rows: (m-pos-01, 32) comes after (m-pos-03, 32)");
    }

    @Test
    void aDuplicateIdAndSizeIsRejected() {
        List<String> lines = valid();
        lines.add(6, lines.get(5));

        assertDefect(rehashed(lines), "manifest line 7: duplicate (id, size) (m-pos-01, 64)");
    }

    @Test
    void anUnknownColumnIsRejectedAtTheColumnLine() {
        List<String> lines = valid();
        lines.set(3, lines.get(3).replace("formula", "recipe"));

        assertDefect(rehashed(lines), "manifest line 4: unknown column 'recipe'");
    }

    @Test
    void theColumnLineMustHoldTheSixteenNamesInOrder() {
        List<String> swapped = valid();
        swapped.set(3, swapped.get(3).replace("id\tsize", "size\tid"));
        List<String> missing = valid();
        missing.set(3, missing.get(3).replace("\twebp_q75_bytes", ""));

        assertDefect(rehashed(swapped), "manifest line 4: column 1 must be 'id', found 'size'");
        assertDefect(rehashed(missing), "manifest line 4: expected 16 columns, found 15");
    }

    @Test
    void aRowWithAWrongColumnCountIsRejected() {
        List<String> lines = valid();
        lines.set(5, lines.get(5) + "\textra");

        assertDefect(rehashed(lines), "manifest line 6: expected 16 columns, found 17");
    }

    @Test
    void aRowWithAnUnreadableValueIsRejected() {
        List<String> size = valid();
        size.set(4, size.get(4).replace("\t32\t", "\t48\t"));
        List<String> split = valid();
        split.set(4, split.get(4).replace("\ttrain\t", "\tholdout\t"));
        List<String> pngBytes = valid();
        pngBytes.set(4, pngBytes.get(4).replace("\t120\t", "\tNA\t"));
        List<String> hash = valid();
        hash.set(4, hash.get(4).replace(HASH, HASH.toUpperCase()));

        assertDefect(rehashed(size), "manifest line 5: size must be 32 or 64, found 48");
        assertDefect(rehashed(split), "manifest line 5: unknown split 'holdout'");
        assertDefect(rehashed(pngBytes), "manifest line 5: png_bytes must be a decimal integer, found 'NA'");
        assertDefect(rehashed(hash), "manifest line 5: pixel_sha256 must be 64 lowercase hex digits");
    }
}
