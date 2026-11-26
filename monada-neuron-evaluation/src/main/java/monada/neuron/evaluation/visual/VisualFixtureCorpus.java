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
 * It also fails on the forms that reach every split without naming one: {@code FixtureSplit.values()},
 * {@code FixtureSplit.valueOf}, {@code FixtureSplit.class}, {@code ManifestRow::split} and the manifest rows read
 * straight from {@link #manifest()}. A consumer takes its rows from {@link #load(FixtureSplit)} through
 * {@link LoadedFixture#row()}.
 */
public final class VisualFixtureCorpus {

    static final String MANIFEST_RESOURCE = "/visual-fixtures-v1/manifest-v1.tsv";

    private final VisualFixtureManifest manifest;
    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureRenderer renderer = new FixtureRenderer(catalogue);

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
            // The fixture's constructor checks the pixel hash against the row and throws on a mismatch.
            fixtures.add(new LoadedFixture(row, row.size(), row.size(), row.kind().channels(), pixels));
        }
        return List.copyOf(fixtures);
    }
}
