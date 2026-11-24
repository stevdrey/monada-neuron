package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.OptionalLong;

/**
 * One row of {@code manifest-v1.tsv}: one fixture id at one size (contract Section 3.3). The first thirteen columns
 * replay exactly from the generator; {@code pngBytes} and the two WebP sizes were recorded once at freeze time and
 * depend on the PNG encoder build and on an external {@code cwebp} binary.
 *
 * @param id the fixture id
 * @param size the raster size, {@code 32} or {@code 64}
 * @param kind mask or RGB
 * @param group the group of contract Section 8.10
 * @param seed the first 8 bytes of SHA-256 of the id, big endian
 * @param formula the short key of the pixel formula; a label, never parsed
 * @param goldGeometry the gold geometry in the Section 8.3 grammar, or {@code NONE}
 * @param goldTransform the gold pose in hex doubles, or {@code NONE}
 * @param goldAccept the acceptable top hypothesis kinds, or {@code NONE} for an RGB id
 * @param goldAbstain the acceptable abstain reasons, or {@code NONE} for an RGB id
 * @param goldLayers the layers of a shape render id, or {@code NONE}
 * @param split the frozen partition of the id
 * @param pixelSha256 SHA-256 of the decoded pixel bytes in raster order, channels interleaved, lowercase hex
 * @param pngBytes the size of the PNG the freeze run wrote
 * @param webpLosslessBytes the size {@code cwebp -lossless -exact} gave; empty when the freeze ran without it
 * @param webpQ75Bytes the size {@code cwebp -q 75 -exact} gave; empty when the freeze ran without it
 */
public record ManifestRow(String id, int size, FixtureKind kind, FixtureGroup group, long seed, String formula,
                          String goldGeometry, String goldTransform, String goldAccept, String goldAbstain,
                          String goldLayers, FixtureSplit split, String pixelSha256, long pngBytes,
                          OptionalLong webpLosslessBytes, OptionalLong webpQ75Bytes) {

    static final String NOT_AVAILABLE = "NA";

    /** The sixteen printed column values, in manifest order. */
    public List<String> columns() {
        return List.of(id, Integer.toString(size), kind.printed(), group.printed(), Long.toString(seed), formula,
                goldGeometry, goldTransform, goldAccept, goldAbstain, goldLayers, split.printed(), pixelSha256,
                Long.toString(pngBytes), printed(webpLosslessBytes), printed(webpQ75Bytes));
    }

    /** The thirteen printed values a fresh generation must reproduce, in manifest order. */
    public List<String> replayableColumns() {
        return columns().subList(0, 13);
    }

    private String printed(OptionalLong bytes) {
        return bytes.isPresent() ? Long.toString(bytes.getAsLong()) : NOT_AVAILABLE;
    }
}
