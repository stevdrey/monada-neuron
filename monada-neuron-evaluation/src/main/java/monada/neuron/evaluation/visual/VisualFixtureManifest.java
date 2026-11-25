package monada.neuron.evaluation.visual;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The frozen manifest of {@code visual-fixtures/1} (contract Section 8.3): three header lines, the column name line and
 * one row per {@code (id, size)}, sorted by id in byte order and then by size.
 *
 * @param version the manifest version, {@code visual-fixtures/1}
 * @param splitKey the key the split byte of every id is derived with
 * @param sha256 SHA-256 of the body: every line after the {@code # sha256:} header, each ending in LF
 * @param rows the rows, in manifest order
 */
public record VisualFixtureManifest(String version, String splitKey, String sha256, List<ManifestRow> rows) {

    static final String VERSION = "visual-fixtures/1";

    static final List<String> COLUMNS = List.of("id", "size", "kind", "group", "seed", "formula", "gold_geometry",
            "gold_transform", "gold_accept", "gold_abstain", "gold_layers", "split", "pixel_sha256", "png_bytes",
            "webp_lossless_bytes", "webp_q75_bytes");

    private static final String VERSION_LINE = "# " + VERSION;
    private static final String SPLIT_KEY_LINE = "# split-key: " + FixtureDigests.SPLIT_KEY;
    private static final String SHA_PREFIX = "# sha256: ";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    // The only form the writer prints, so that parsing and printing a row give back the same text and hash.
    private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]*");
    private static final int FIRST_ROW_LINE = 5;

    public VisualFixtureManifest {
        rows = List.copyOf(rows);
    }

    /** A manifest over rows that are already in manifest order, with the header hash computed from them. */
    static VisualFixtureManifest of(List<ManifestRow> rows) {
        return new VisualFixtureManifest(VERSION, FixtureDigests.SPLIT_KEY, bodySha256(bodyLines(rows)), rows);
    }

    /**
     * Parses the lines of a manifest file.
     *
     * @param lines the file's lines, without their LF
     * @return the manifest
     * @throws IllegalStateException naming the line, counted from {@code 1} over the whole file, on a wrong header,
     *         a wrong body hash, a wrong column line, a wrong column count, an unreadable value, unsorted rows or a
     *         duplicate {@code (id, size)}
     */
    static VisualFixtureManifest parse(List<String> lines) {
        expectLine(lines, 1, VERSION_LINE);
        expectLine(lines, 2, SPLIT_KEY_LINE);
        String shaLine = line(lines, 3);
        if (!shaLine.startsWith(SHA_PREFIX) || !SHA256.matcher(shaLine.substring(SHA_PREFIX.length())).matches()) {
            throw defect(3, "expected '" + SHA_PREFIX + "<64 lowercase hex digits>'");
        }
        String sha256 = shaLine.substring(SHA_PREFIX.length());
        String actual = bodySha256(lines.subList(3, lines.size()));
        if (!actual.equals(sha256)) {
            throw defect(3, "body hash mismatch: the header says " + sha256 + " but the body hashes to " + actual);
        }
        columnLine(line(lines, 4));
        var rows = new ArrayList<ManifestRow>(lines.size() - 4);
        for (int number = FIRST_ROW_LINE; number <= lines.size(); number++) {
            ManifestRow row = row(lines.get(number - 1), number);
            if (!rows.isEmpty()) {
                int order = compare(rows.getLast(), row);
                if (order == 0) {
                    throw defect(number, "duplicate (id, size) (" + row.id() + ", " + row.size() + ")");
                }
                if (order > 0) {
                    throw defect(number, "unsorted rows: (" + row.id() + ", " + row.size() + ") comes after ("
                            + rows.getLast().id() + ", " + rows.getLast().size() + ")");
                }
            }
            rows.add(row);
        }
        return new VisualFixtureManifest(VERSION, FixtureDigests.SPLIT_KEY, sha256, rows);
    }

    /** The file's lines, without their LF. */
    List<String> lines() {
        var lines = new ArrayList<String>(rows.size() + 4);
        lines.add(VERSION_LINE);
        lines.add("# split-key: " + splitKey);
        lines.add(SHA_PREFIX + sha256);
        lines.addAll(bodyLines(rows));
        return lines;
    }

    private static List<String> bodyLines(List<ManifestRow> rows) {
        var body = new ArrayList<String>(rows.size() + 1);
        body.add(String.join("\t", COLUMNS));
        for (ManifestRow row : rows) {
            body.add(String.join("\t", row.columns()));
        }
        return body;
    }

    private static String bodySha256(List<String> body) {
        var text = new StringBuilder();
        for (String line : body) {
            text.append(line).append('\n');
        }
        return new FixtureDigests().sha256Hex(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void columnLine(String line) {
        String[] names = line.split("\t", -1);
        for (var i = 0; i < names.length; i++) {
            if (!COLUMNS.contains(names[i])) {
                throw defect(4, "unknown column '" + names[i] + "'");
            }
        }
        if (names.length != COLUMNS.size()) {
            throw defect(4, "expected " + COLUMNS.size() + " columns, found " + names.length);
        }
        for (var i = 0; i < names.length; i++) {
            if (!COLUMNS.get(i).equals(names[i])) {
                throw defect(4, "column " + (i + 1) + " must be '" + COLUMNS.get(i) + "', found '" + names[i] + "'");
            }
        }
    }

    private static ManifestRow row(String line, int number) {
        String[] values = line.split("\t", -1);
        if (values.length != COLUMNS.size()) {
            throw defect(number, "expected " + COLUMNS.size() + " columns, found " + values.length);
        }
        // Checked as a long: a narrowing cast would read 2^32 + 32 as 32.
        long printedSize = integer(values[1], "size", number);
        if (printedSize != (int) printedSize || !FixtureCatalogue.SIZES.contains((int) printedSize)) {
            throw defect(number, "size must be 32 or 64, found " + printedSize);
        }
        int size = (int) printedSize;
        FixtureKind kind = Arrays.stream(FixtureKind.values()).filter(k -> k.printed().equals(values[2])).findFirst()
                .orElseThrow(() -> defect(number, "unknown kind '" + values[2] + "'"));
        FixtureGroup group = Arrays.stream(FixtureGroup.values()).filter(g -> g.printed().equals(values[3]))
                .findFirst().orElseThrow(() -> defect(number, "unknown group '" + values[3] + "'"));
        if (kind != group.kind()) {
            throw defect(number, "kind '" + kind.printed() + "' does not match group '" + group.printed()
                    + "', which is '" + group.kind().printed() + "'");
        }
        FixtureSplit split = Arrays.stream(FixtureSplit.values()).filter(s -> s.printed().equals(values[11]))
                .findFirst().orElseThrow(() -> defect(number, "unknown split '" + values[11] + "'"));
        if (!SHA256.matcher(values[12]).matches()) {
            throw defect(number, "pixel_sha256 must be 64 lowercase hex digits");
        }
        return new ManifestRow(values[0], size, kind, group, integer(values[4], "seed", number), values[5],
                values[6], values[7], values[8], values[9], values[10], split, values[12],
                count(values[13], "png_bytes", number), webpBytes(values[14], "webp_lossless_bytes", number),
                webpBytes(values[15], "webp_q75_bytes", number));
    }

    private static OptionalLong webpBytes(String value, String column, int number) {
        return value.equals(ManifestRow.NOT_AVAILABLE) ? OptionalLong.empty()
                : OptionalLong.of(count(value, column, number));
    }

    private static long count(String value, String column, int number) {
        long count = integer(value, column, number);
        if (count < 0) {
            throw defect(number, column + " must not be negative, found " + count);
        }
        return count;
    }

    private static long integer(String value, String column, int number) {
        if (!INTEGER.matcher(value).matches()) {
            throw defect(number, column + " must be a decimal integer, found '" + value + "'");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw defect(number, column + " must be a decimal integer, found '" + value + "'");
        }
    }

    /** Id in byte order, then size. */
    private static int compare(ManifestRow left, ManifestRow right) {
        int byId = Arrays.compareUnsigned(left.id().getBytes(StandardCharsets.UTF_8),
                right.id().getBytes(StandardCharsets.UTF_8));
        return byId != 0 ? byId : Integer.compare(left.size(), right.size());
    }

    private static void expectLine(List<String> lines, int number, String expected) {
        if (!line(lines, number).equals(expected)) {
            throw defect(number, "expected '" + expected + "'");
        }
    }

    private static String line(List<String> lines, int number) {
        if (lines.size() < number) {
            throw defect(number, "the file ends before this line");
        }
        return lines.get(number - 1);
    }

    private static IllegalStateException defect(int number, String message) {
        return new IllegalStateException("manifest line " + number + ": " + message);
    }
}
