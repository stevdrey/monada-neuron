package monada.neuron.evaluation.visual;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * CLI that regenerates the corpus {@code visual-fixtures/1} (issue #142): {@code <id>-32.png} and {@code <id>-64.png}
 * for every id, {@code manifest-v1.tsv} and {@code report-fragment.md}.
 *
 * <p>Options: {@code --output-dir <dir>} (required) and {@code --cwebp <path>} (optional; fills the two WebP columns,
 * which are {@code NA} without it). Everything is written into {@code <dir>/.staging} first, every PNG is read back
 * and compared with its pixels, and the files move into {@code <dir>} only when the whole run succeeded. Exit status
 * {@code 1} on any failure, with the output directory left exactly as it was. The generator never writes into tracked
 * files: freezing a manifest is one manual copy, described in {@code PROVENANCE.md}.
 */
public final class VisualFixtureGenerator {

    static final String MANIFEST_NAME = "manifest-v1.tsv";
    static final String REPORT_NAME = "report-fragment.md";
    static final String STAGING_NAME = ".staging";

    private static final String USAGE = "Usage: --output-dir <dir> [--cwebp <path>]";

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureRenderer renderer = new FixtureRenderer(catalogue);
    private final MaskRenderer masks = new MaskRenderer();
    private final FixturePng png = new FixturePng();

    /** One fixture of a run: its definition, size, decoded pixels and staged file name. */
    private record Rendered(FixtureDefinition definition, int size, byte[] pixels) {

        String baseName() {
            return definition.id() + "-" + size;
        }
    }

    /** CLI entrypoint; exits with status {@code 1} when the run failed. */
    public static void main(String[] args) {
        int status = new VisualFixtureGenerator().execute(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Parses the arguments and runs; returns the exit status. */
    int execute(String[] args, PrintStream out, PrintStream err) {
        Path outputDir = null;
        Optional<Path> cwebp = Optional.empty();
        for (var i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !(args[i].equals("--output-dir") || args[i].equals("--cwebp"))) {
                err.println("invalid argument: " + args[i]);
                err.println(USAGE);
                return 1;
            }
            if (args[i].equals("--output-dir")) {
                outputDir = Path.of(args[i + 1]);
            } else {
                cwebp = Optional.of(Path.of(args[i + 1]));
            }
        }
        if (outputDir == null) {
            err.println("--output-dir is required");
            err.println(USAGE);
            return 1;
        }
        boolean existed = Files.exists(outputDir);
        Path staging = outputDir.resolve(STAGING_NAME);
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            VisualFixtureManifest manifest = generate(staging, cwebp.map(CwebpTool::new));
            publish(staging, outputDir);
            out.println("Wrote " + manifest.rows().size() + " fixtures and " + MANIFEST_NAME + " (sha256 "
                    + manifest.sha256() + ") to " + outputDir);
            return 0;
        } catch (IllegalStateException | IOException | UncheckedIOException e) {
            discard(existed ? staging : outputDir);
            err.println("visual fixture generation failed: " + e.getMessage());
            return 1;
        }
    }

    private VisualFixtureManifest generate(Path staging, Optional<CwebpTool> cwebp) throws IOException {
        Optional<String> cwebpVersion = cwebp.map(CwebpTool::version);

        long started = System.nanoTime();
        var rendered = new ArrayList<Rendered>(catalogue.definitions().size() * FixtureCatalogue.SIZES.size());
        var hashes = new ArrayList<String>();
        var digests = new FixtureDigests();
        for (FixtureDefinition definition : catalogue.definitions()) {
            for (int size : FixtureCatalogue.SIZES) {
                byte[] pixels = renderer.pixels(definition, size);
                hashes.add(digests.sha256Hex(pixels));
                rendered.add(new Rendered(definition, size, pixels));
            }
        }
        long renderNanos = System.nanoTime() - started;
        rendered.sort(Comparator.comparing((Rendered r) -> r.definition().id()).thenComparingInt(Rendered::size));

        var rows = new ArrayList<ManifestRow>(rendered.size());
        for (Rendered fixture : rendered) {
            Path file = staging.resolve(fixture.baseName() + ".png");
            writeAndReadBack(fixture, file);
            OptionalLong lossless = OptionalLong.empty();
            OptionalLong q75 = OptionalLong.empty();
            if (cwebp.isPresent()) {
                lossless = OptionalLong.of(cwebp.get().lossless(file,
                        staging.resolve(fixture.baseName() + ".lossless.webp")));
                q75 = OptionalLong.of(cwebp.get().q75(file, staging.resolve(fixture.baseName() + ".q75.webp")));
            }
            rows.add(renderer.row(fixture.definition(), fixture.size(), fixture.pixels(), Files.size(file),
                    lossless, q75));
        }
        VisualFixtureManifest manifest = VisualFixtureManifest.of(rows);
        writeLines(staging.resolve(MANIFEST_NAME), manifest.lines());
        writeLines(staging.resolve(REPORT_NAME), report(manifest, rendered, renderNanos, cwebpVersion));
        return manifest;
    }

    private void writeAndReadBack(Rendered fixture, Path file) throws IOException {
        byte[] readBack;
        if (fixture.definition().kind() == FixtureKind.MASK) {
            png.writeMask(file, fixture.pixels(), fixture.size());
            readBack = png.readMask(file);
        } else {
            png.writeRgba(file, fixture.pixels(), fixture.size());
            readBack = png.readRgba(file);
        }
        if (!Arrays.equals(readBack, fixture.pixels())) {
            throw new IllegalStateException(file.getFileName() + " does not decode to the pixels that were written");
        }
    }

    /** Moves the PNG files, the manifest and the report out of staging; the WebP files are deleted with it. */
    private void publish(Path staging, Path outputDir) throws IOException {
        try (Stream<Path> files = Files.list(staging)) {
            for (Path file : files.sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".webp")) {
                    Files.move(file, outputDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        deleteTree(staging);
    }

    private List<String> report(VisualFixtureManifest manifest, List<Rendered> rendered, long renderNanos,
                                Optional<String> cwebpVersion) {
        long decoded = 0;
        var largest = 0;
        for (Rendered fixture : rendered) {
            decoded += fixture.pixels().length;
            largest = Math.max(largest, fixture.pixels().length);
        }
        long pngTotal = manifest.rows().stream().mapToLong(ManifestRow::pngBytes).sum();
        Runtime runtime = Runtime.getRuntime();
        var lines = new ArrayList<String>();
        lines.add("# Generated corpus `" + manifest.version() + "`");
        lines.add("");
        lines.add("- Manifest sha256: `" + manifest.sha256() + "`");
        lines.add("- Split key: `" + manifest.splitKey() + "`");
        lines.add("- Fixtures: " + manifest.rows().size() + " (" + catalogue.definitions().size()
                + " ids at 32x32 and 64x64)");
        lines.add("- Decoded bytes: " + decoded);
        lines.add("- Largest single pixel array: " + largest + " bytes");
        lines.add("- Total PNG bytes: " + pngTotal);
        lines.add("- Total WebP lossless bytes: " + total(manifest, true));
        lines.add("- Total WebP q75 bytes: " + total(manifest, false));
        lines.add("- Generation time (render and hash of every fixture, one run, wall clock): "
                + renderNanos / 1_000_000 + " ms");
        lines.add("- JDK: " + System.getProperty("java.vendor") + " " + Runtime.version());
        lines.add("- OS and architecture: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + ", " + System.getProperty("os.arch"));
        lines.add("- Available processors: " + runtime.availableProcessors());
        lines.add("- Max heap bytes: " + runtime.maxMemory());
        lines.add("- JVM input arguments: `"
                + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()) + "`");
        lines.add("- cwebp version: " + cwebpVersion.orElse("not run (the WebP columns are NA)"));
        lines.add("");
        lines.add("## Clipped (id, pose, size)");
        lines.add("");
        for (Rendered fixture : rendered) {
            FixtureDefinition definition = fixture.definition();
            if (definition.pose().isPresent() && masks.clipped(definition.shape().orElseThrow(),
                    definition.transform().orElseThrow(), fixture.size())) {
                lines.add("- (`" + definition.id() + "`, " + definition.pose().getAsInt() + ", " + fixture.size()
                        + ")");
            }
        }
        lines.add("");
        lines.add("## Edge touching predicates");
        lines.add("");
        for (String id : FixtureCatalogue.EDGE_TOUCHING) {
            lines.add("- `" + id + "`");
        }
        lines.add("");
        lines.add("## Per fixture table");
        lines.add("");
        lines.add("| id | size | split | pixel_sha256 (first 12) | png_bytes | webp_lossless_bytes | webp_q75_bytes |");
        lines.add("|---|---|---|---|---|---|---|");
        for (ManifestRow row : manifest.rows()) {
            List<String> columns = row.columns();
            lines.add("| `" + row.id() + "` | " + row.size() + " | " + row.split().printed() + " | `"
                    + row.pixelSha256().substring(0, 12) + "` | " + row.pngBytes() + " | " + columns.get(14) + " | "
                    + columns.get(15) + " |");
        }
        return lines;
    }

    private String total(VisualFixtureManifest manifest, boolean lossless) {
        long sum = 0;
        for (ManifestRow row : manifest.rows()) {
            OptionalLong bytes = lossless ? row.webpLosslessBytes() : row.webpQ75Bytes();
            if (bytes.isEmpty()) {
                return ManifestRow.NOT_AVAILABLE;
            }
            sum += bytes.getAsLong();
        }
        return Long.toString(sum);
    }

    private void writeLines(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private void discard(Path tree) {
        try {
            deleteTree(tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tree)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
