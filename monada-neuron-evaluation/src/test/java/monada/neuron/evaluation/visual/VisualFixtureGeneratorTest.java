package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFixtureGeneratorTest {

    @TempDir
    Path work;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new VisualFixtureGenerator().execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private VisualFixtureManifest manifest(Path directory) throws IOException {
        return VisualFixtureManifest.parse(Files.readAllLines(directory.resolve("manifest-v1.tsv")));
    }

    /** A stand in for {@code cwebp}, written at test time: {@code -version} runs {@code onVersion}. */
    private Path fakeCwebp(String onVersion) throws IOException {
        Path script = work.resolve("fake-cwebp");
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then
                %s
                fi
                while [ $# -gt 1 ]; do
                  if [ "$1" = "-o" ]; then printf 'webp' > "$2"; fi
                  shift
                done
                """.formatted(onVersion));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    @Test
    void aRunWritesEveryPngTheManifestAndTheReportAndLeavesNoStagingFolder() throws IOException {
        Path output = work.resolve("corpus");

        assertEquals(0, run("--output-dir", output.toString()));

        List<String> names = names(output);
        assertEquals(240 + 2, names.size());
        assertTrue(names.containsAll(List.of("manifest-v1.tsv", "report-fragment.md", "m-pos-01-32.png",
                "m-pos-01-64.png", "c-flat-04-32.png", "c-deg-05-64.png")));
        assertEquals("", errors());

        VisualFixtureManifest written = manifest(output);
        List<ManifestRow> committed = VisualFixtureCorpus.open().manifest().rows();
        assertEquals(240, written.rows().size());
        for (var i = 0; i < committed.size(); i++) {
            ManifestRow row = written.rows().get(i);
            assertEquals(committed.get(i).replayableColumns(), row.replayableColumns());
            assertEquals(Files.size(output.resolve(row.id() + "-" + row.size() + ".png")), row.pngBytes());
            assertTrue(row.webpLosslessBytes().isEmpty() && row.webpQ75Bytes().isEmpty(), "NA without --cwebp");
        }
    }

    @Test
    void theWrittenPngFilesDecodeToThePixelsOfTheirRows() throws IOException {
        Path output = work.resolve("corpus");
        var png = new FixturePng();
        var digests = new FixtureDigests();

        assertEquals(0, run("--output-dir", output.toString()));

        for (ManifestRow row : manifest(output).rows()) {
            Path file = output.resolve(row.id() + "-" + row.size() + ".png");
            byte[] pixels = row.kind() == FixtureKind.MASK ? png.readMask(file) : png.readRgba(file);
            assertEquals(row.pixelSha256(), digests.sha256Hex(pixels), row.id() + " at " + row.size());
        }
    }

    @Test
    void theReportFragmentNamesTheBoundsTheClippedPosesAndTheEdgeTouchingIds() throws IOException {
        Path output = work.resolve("corpus");

        assertEquals(0, run("--output-dir", output.toString()));

        List<String> report = Files.readAllLines(output.resolve("report-fragment.md"));
        assertTrue(report.contains("- Manifest sha256: `" + manifest(output).sha256() + "`"));
        assertTrue(report.contains("- Decoded bytes: 1351680"));
        assertTrue(report.contains("- Largest single pixel array: 16384 bytes"));
        assertTrue(report.contains("- Total WebP lossless bytes: NA"));
        assertTrue(report.contains("- cwebp version: not run (the WebP columns are NA)"));
        assertTrue(report.contains("- (`m-pos-08`, 8, 32)"));
        assertFalse(report.contains("- (`m-pos-01`, 1, 32)"));
        assertTrue(report.contains("- `m-amb-10`"));
        assertEquals(240, report.stream().filter(line -> line.startsWith("| `")).count());
    }

    @Test
    void withCwebpTheRunRecordsBothWebpSizesAndTheVersionLine() throws IOException {
        Path output = work.resolve("corpus");
        Path cwebp = fakeCwebp("  echo 'fake 1.2.3'; echo 'libsharpyuv: 0.4'; exit 0");

        assertEquals(0, run("--output-dir", output.toString(), "--cwebp", cwebp.toString()));

        for (ManifestRow row : manifest(output).rows()) {
            assertEquals(4, row.webpLosslessBytes().orElseThrow());
            assertEquals(4, row.webpQ75Bytes().orElseThrow());
        }
        assertTrue(Files.readAllLines(output.resolve("report-fragment.md")).contains("- cwebp version: fake 1.2.3"));
        assertTrue(names(output).stream().noneMatch(name -> name.endsWith(".webp") || name.equals(".staging")));
    }

    @Test
    void aCwebpThatExitsNonZeroFailsTheRunAndLeavesNoOutputDirectory() throws IOException {
        Path output = work.resolve("corpus");
        Path cwebp = fakeCwebp("  exit 3");

        assertEquals(1, run("--output-dir", output.toString(), "--cwebp", cwebp.toString()));

        assertFalse(Files.exists(output));
        assertTrue(errors().contains("command '" + cwebp + " -version' exited with code 3"), errors());
    }

    @Test
    void aFailedRunLeavesAnExistingOutputDirectoryExactlyAsItWas() throws IOException {
        Path output = Files.createDirectories(work.resolve("corpus"));
        Files.writeString(output.resolve("manifest-v1.tsv"), "kept");
        Files.writeString(output.resolve("m-pos-01-32.png"), "kept too");

        assertEquals(1, run("--output-dir", output.toString(), "--cwebp", fakeCwebp("  exit 0").toString()));
        assertTrue(errors().contains("printed no version line"), errors());
        assertEquals(1, run("--output-dir", output.toString(), "--cwebp", work.resolve("absent").toString()));
        assertTrue(errors().contains("could not run"), errors());

        assertEquals(List.of("m-pos-01-32.png", "manifest-v1.tsv"), names(output));
        assertEquals("kept", Files.readString(output.resolve("manifest-v1.tsv")));
        assertArrayEquals("kept too".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(output.resolve("m-pos-01-32.png")));
    }

    @Test
    void aStaleStagingFolderIsDeletedBeforeARun() throws IOException {
        Path output = work.resolve("corpus");
        Path stale = Files.createDirectories(output.resolve(".staging/old"));
        Files.writeString(stale.resolve("left-over.png"), "stale");

        assertEquals(0, run("--output-dir", output.toString()));

        assertFalse(Files.exists(output.resolve(".staging")));
        assertEquals(240 + 2, names(output).size());
    }

    @Test
    void invalidArgumentsExitWithStatusOneAndAUsageLine() {
        assertEquals(1, run());
        assertEquals(1, run("--output-dir"));
        assertEquals(1, run("--colour", "yes"));

        assertTrue(errors().contains("--output-dir is required"), errors());
        assertTrue(errors().contains("invalid argument: --colour"), errors());
        assertTrue(errors().contains("Usage: --output-dir <dir> [--cwebp <path>]"), errors());
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }
}
