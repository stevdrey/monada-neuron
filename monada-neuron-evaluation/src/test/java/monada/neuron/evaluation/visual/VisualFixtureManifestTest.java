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

    @Test
    void aRowWithAnUnknownKindOrGroupIsRejected() {
        // covers: AC-5
        List<String> kind = valid();
        kind.set(4, kind.get(4).replace("\tmask\t", "\tvector\t"));
        List<String> group = valid();
        group.set(4, group.get(4).replace("\tpositive\t", "\tneutral\t"));

        assertDefect(rehashed(kind), "manifest line 5: unknown kind 'vector'");
        assertDefect(rehashed(group), "manifest line 5: unknown group 'neutral'");
    }

    @Test
    void aSeedOrWebpSizeThatIsNotAnIntegerIsRejected() {
        // covers: AC-5, AC-7. A WebP column is an integer or exactly NA.
        List<String> seed = valid();
        seed.set(4, seed.get(4).replace("\t42\t", "\t4x2\t"));
        List<String> lossless = valid();
        lossless.set(4, lossless.get(4).replace("\tNA\t", "\tna\t"));
        List<String> q75 = valid();
        q75.set(4, q75.get(4).substring(0, q75.get(4).length() - 2) + "7.5");

        assertDefect(rehashed(seed), "manifest line 5: seed must be a decimal integer, found '4x2'");
        assertDefect(rehashed(lossless), "manifest line 5: webp_lossless_bytes must be a decimal integer, found 'na'");
        assertDefect(rehashed(q75), "manifest line 5: webp_q75_bytes must be a decimal integer, found '7.5'");
    }

    @Test
    void theTwoSizesOfOneIdMustComeSmallerFirst() {
        // covers: AC-5
        List<String> lines = valid();
        String size32 = lines.get(4);
        lines.set(4, lines.get(5));
        lines.set(5, size32);

        assertDefect(rehashed(lines), "manifest line 6: unsorted rows: (m-pos-01, 32) comes after (m-pos-01, 64)");
    }

    @Test
    void aFileThatEndsAfterItsHeaderIsRejectedAtTheColumnLine() {
        // covers: AC-5
        List<String> headerOnly = new ArrayList<>(valid().subList(0, 3));

        assertDefect(rehashed(headerOnly), "manifest line 4: the file ends before this line");
    }

    @Test
    void aSizeBeyondTheIntRangeIsRejectedAndNotWrappedIntoAValidSize() {
        // covers: AC-5. 4294967328 is 2^32 + 32, so a narrowing cast would read it as 32.
        List<String> lines = valid();
        lines.set(4, lines.get(4).replace("\t32\t", "\t4294967328\t"));

        assertDefect(rehashed(lines), "manifest line 5: size must be 32 or 64, found 4294967328");
    }

    @Test
    void anIntegerMustBePrintedTheWayTheManifestWriterPrintsIt() {
        // covers: AC-5. A sign or a leading zero would parse and then print as another text with another hash.
        List<String> plusSize = valid();
        plusSize.set(4, plusSize.get(4).replace("\t32\t", "\t+32\t"));
        List<String> paddedSize = valid();
        paddedSize.set(4, paddedSize.get(4).replace("\t32\t", "\t032\t"));
        List<String> plusSeed = valid();
        plusSeed.set(4, plusSeed.get(4).replace("\t42\t", "\t+42\t"));
        List<String> minusZeroSeed = valid();
        minusZeroSeed.set(4, minusZeroSeed.get(4).replace("\t42\t", "\t-0\t"));
        List<String> paddedPng = valid();
        paddedPng.set(4, paddedPng.get(4).replace("\t120\t", "\t0120\t"));

        assertDefect(rehashed(plusSize), "manifest line 5: size must be a decimal integer, found '+32'");
        assertDefect(rehashed(paddedSize), "manifest line 5: size must be a decimal integer, found '032'");
        assertDefect(rehashed(plusSeed), "manifest line 5: seed must be a decimal integer, found '+42'");
        assertDefect(rehashed(minusZeroSeed), "manifest line 5: seed must be a decimal integer, found '-0'");
        assertDefect(rehashed(paddedPng), "manifest line 5: png_bytes must be a decimal integer, found '0120'");
    }

    @Test
    void aNegativeSeedParsesButANegativeByteCountIsRejected() {
        // covers: AC-5
        List<String> seed = valid();
        seed.set(4, seed.get(4).replace("\t42\t", "\t-42\t"));
        List<String> pngBytes = valid();
        pngBytes.set(4, pngBytes.get(4).replace("\t120\t", "\t-120\t"));
        List<String> q75 = valid();
        q75.set(4, q75.get(4).substring(0, q75.get(4).length() - 2) + "-7");

        assertEquals(-42L, VisualFixtureManifest.parse(rehashed(seed)).rows().getFirst().seed());
        assertDefect(rehashed(pngBytes), "manifest line 5: png_bytes must not be negative, found -120");
        assertDefect(rehashed(q75), "manifest line 5: webp_q75_bytes must not be negative, found -7");
    }

    @Test
    void aKindThatDoesNotBelongToTheGroupIsRejected() {
        // covers: AC-5. The loader reads its channel count from the kind, so a mask group must stay a mask.
        List<String> lines = valid();
        lines.set(4, lines.get(4).replace("\tmask\t", "\trgb\t"));

        assertDefect(rehashed(lines), "manifest line 5: kind 'rgb' does not match group 'positive', which is 'mask'");
    }
}
