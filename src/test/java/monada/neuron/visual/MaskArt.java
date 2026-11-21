package monada.neuron.visual;

import static monada.neuron.visual.VisualFixtures.ok;

/** Test only text art for masks: {@code #} is foreground, {@code .} is background, one line per row. */
final class MaskArt {

    private MaskArt() {
    }

    static BinaryMask parse(String... rows) {
        int width = rows[0].length();
        long[] words = new long[(width * rows.length + 63) >>> 6];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < width; x++) {
                if (rows[y].charAt(x) == '#') {
                    int bit = y * width + x;
                    words[bit >>> 6] |= 1L << bit;
                }
            }
        }
        return ok(BinaryMask.of(width, rows.length, words));
    }

    static String render(BinaryMask mask) {
        var art = new StringBuilder();
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                art.append(mask.get(x, y) ? '#' : '.');
            }
            art.append('\n');
        }
        return art.toString();
    }

    static String art(String... rows) {
        return String.join("\n", rows) + "\n";
    }
}
