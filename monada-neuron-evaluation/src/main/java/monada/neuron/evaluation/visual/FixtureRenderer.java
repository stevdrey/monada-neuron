package monada.neuron.evaluation.visual;

import java.util.Map;
import java.util.OptionalLong;

/**
 * Turns a catalogue definition into its decoded pixels and its manifest row. Deterministic: the same definition and
 * size give the same bytes on every call, because each render starts a fresh generator from the id's seed.
 */
final class FixtureRenderer {

    private final FixtureCatalogue catalogue;
    private final FixtureDigests digests = new FixtureDigests();
    private final MaskRenderer masks = new MaskRenderer();
    private final ColorRenderer colors = new ColorRenderer(masks);
    private final Map<String, FixtureSplit> splits;

    FixtureRenderer(FixtureCatalogue catalogue) {
        this.catalogue = catalogue;
        this.splits = digests.splits(catalogue.definitions());
    }

    /** The decoded pixel bytes of a definition at one size, in raster order with channels interleaved. */
    byte[] pixels(FixtureDefinition definition, int size) {
        long seed = digests.seed(definition.id());
        return switch (definition.kind()) {
            case MASK -> masks.render(definition, size, seed);
            case RGB -> colors.render(definition, size, seed, this::texture);
        };
    }

    /** The manifest row of a definition at one size; the three recorded columns are given by the caller. */
    ManifestRow row(FixtureDefinition definition, int size, byte[] pixels, long pngBytes,
                    OptionalLong webpLosslessBytes, OptionalLong webpQ75Bytes) {
        return new ManifestRow(definition.id(), size, definition.kind(), definition.group(),
                digests.seed(definition.id()), definition.formula(), definition.goldGeometry(),
                definition.goldTransform(), definition.goldAccept(), definition.goldAbstain(),
                definition.goldLayers(), split(definition.id()), digests.sha256Hex(pixels), pngBytes,
                webpLosslessBytes, webpQ75Bytes);
    }

    FixtureSplit split(String id) {
        return splits.get(id);
    }

    private byte[] texture(String fixtureId, int size) {
        FixtureDefinition texture = catalogue.find(fixtureId)
                .orElseThrow(() -> new IllegalStateException("unknown texture fixture " + fixtureId));
        return pixels(texture, size);
    }
}
