package monada.neuron.evaluation.visual;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The fixture corpus {@code visual-fixtures/1}. No image is stored: every load regenerates the pixels and checks them
 * against the frozen manifest, so a caller never receives pixels that do not hash to their row (spec 0013).
 *
 * <p>Every read names its split. The test split is single use and belongs to issue #151; a leakage test fails when
 * another test source names {@link FixtureSplit#TEST}, a test split id or {@link #loadEverySplitForLosslessChecks()}.
 */
public final class VisualFixtureCorpus {

    static final String MANIFEST_RESOURCE = "/visual-fixtures-v1/manifest-v1.tsv";

    private final VisualFixtureManifest manifest;
    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureRenderer renderer = new FixtureRenderer(catalogue);
    private final FixtureDigests digests = new FixtureDigests();

    private VisualFixtureCorpus(VisualFixtureManifest manifest) {
        this.manifest = manifest;
    }

    /**
     * Opens the corpus over the committed manifest.
     *
     * @return the corpus
     * @throws IllegalStateException naming the manifest line when the committed manifest is defective
     */
    public static VisualFixtureCorpus open() {
        try (InputStream in = VisualFixtureCorpus.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + MANIFEST_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!text.endsWith("\n")) {
                throw new IllegalStateException("manifest: the last line must end in LF");
            }
            return over(VisualFixtureManifest.parse(
                    Arrays.asList(text.substring(0, text.length() - 1).split("\n", -1))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A corpus over a given manifest, so tests can load against a tampered one. */
    static VisualFixtureCorpus over(VisualFixtureManifest manifest) {
        return new VisualFixtureCorpus(Objects.requireNonNull(manifest, "manifest"));
    }

    /** The frozen manifest. */
    public VisualFixtureManifest manifest() {
        return manifest;
    }

    /**
     * Regenerates and checks the fixtures of one split.
     *
     * @param split the split to load
     * @return the fixtures, by id then size
     * @throws IllegalStateException naming the id and size when regenerated pixels do not hash to their row
     */
    public List<LoadedFixture> load(FixtureSplit split) {
        Objects.requireNonNull(split, "split");
        return load(manifest.rows().stream().filter(row -> row.split() == split).toList());
    }

    /**
     * Regenerates and checks all 240 fixtures. Only for checks that select nothing: the byte replay and the lossless
     * round trips of contract Section 8.10. Scoring anything from this call reads the test split.
     *
     * @return every fixture, by id then size
     * @throws IllegalStateException naming the id and size when regenerated pixels do not hash to their row
     */
    public List<LoadedFixture> loadEverySplitForLosslessChecks() {
        return load(manifest.rows());
    }

    private List<LoadedFixture> load(List<ManifestRow> rows) {
        var fixtures = new ArrayList<LoadedFixture>(rows.size());
        for (ManifestRow row : rows) {
            FixtureDefinition definition = catalogue.find(row.id()).orElseThrow(() -> new IllegalStateException(
                    "fixture " + row.id() + " at size " + row.size() + " is not in the catalogue"));
            byte[] pixels = renderer.pixels(definition, row.size());
            String actual = digests.sha256Hex(pixels);
            if (!actual.equals(row.pixelSha256())) {
                throw new IllegalStateException("fixture " + row.id() + " at size " + row.size()
                        + " regenerated to pixel hash " + actual + " but its manifest row says " + row.pixelSha256());
            }
            fixtures.add(new LoadedFixture(row, row.size(), row.size(), row.kind().channels(), pixels));
        }
        return List.copyOf(fixtures);
    }
}
