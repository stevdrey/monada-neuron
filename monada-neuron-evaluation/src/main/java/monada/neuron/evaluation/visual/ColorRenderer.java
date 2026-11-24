package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Renders RGB fixtures: the formulas of contract Section 8.7 and the layer compositing of Section 5. Values are sRGB
 * in {@code [0, 1]} before encoding, and every expression is evaluated in {@code double}, left to right as the contract
 * writes it, with {@link StrictMath} for {@code cos}, {@code sqrt} and {@code pow}. This class is the only definition
 * of these pixels, so the expressions are frozen with the pixel hashes.
 */
final class ColorRenderer {

    private static final int CHANNELS = 4;

    private final MaskRenderer masks;

    ColorRenderer(MaskRenderer masks) {
        this.masks = masks;
    }

    /** Gives the decoded RGBA bytes of another fixture id at one size, for texture fills. */
    @FunctionalInterface
    interface Textures {

        byte[] rgba(String fixtureId, int n);
    }

    /** Writes the sRGB {@code R, G, B, A} values of pixel {@code (x, y)}, each in {@code [0, 1]}. */
    @FunctionalInterface
    private interface Formula {

        void sample(int x, int y, double[] rgba);
    }

    /** The decoded RGBA bytes of an RGB fixture at size {@code n}, in raster order with channels interleaved. */
    byte[] render(FixtureDefinition definition, int n, long seed, Textures textures) {
        return switch (definition.id()) {
            case "c-deg-04" -> noise(n, seed, false);
            case "c-deg-05" -> noise(n, seed, true);
            default -> definition.group() == FixtureGroup.SHAPE_RENDER
                    ? composite(definition.layers(), n, textures)
                    : fill(n, formula(definition.id(), n));
        };
    }

    /** The encode rule of Section 5: {@code clamp(floor(c * 255 + 0.5), 0, 255)}. */
    byte encode(double c) {
        return (byte) Math.clamp((long) Math.floor(c * 255 + 0.5), 0, 255);
    }

    double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4);
    }

    double linearToSrgb(double c) {
        return c <= 0.04045 / 12.92 ? 12.92 * c : 1.055 * StrictMath.pow(c, 1 / 2.4) - 0.055;
    }

    private byte[] fill(int n, Formula formula) {
        var pixels = new byte[n * n * CHANNELS];
        var rgba = new double[CHANNELS];
        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                formula.sample(x, y, rgba);
                int at = (y * n + x) * CHANNELS;
                for (var channel = 0; channel < CHANNELS; channel++) {
                    pixels[at + channel] = encode(rgba[channel]);
                }
            }
        }
        return pixels;
    }

    /** One {@code nextLong()} per channel in {@code R, G, B(, A)} order per pixel, byte {@code draw >>> 56}. */
    private byte[] noise(int n, long seed, boolean withAlpha) {
        var random = new SplittableRandom(seed);
        var pixels = new byte[n * n * CHANNELS];
        for (var at = 0; at < pixels.length; at += CHANNELS) {
            pixels[at] = (byte) (int) (random.nextLong() >>> 56);
            pixels[at + 1] = (byte) (int) (random.nextLong() >>> 56);
            pixels[at + 2] = (byte) (int) (random.nextLong() >>> 56);
            pixels[at + 3] = withAlpha ? (byte) (int) (random.nextLong() >>> 56) : (byte) 255;
        }
        return pixels;
    }

    private Formula formula(String id, int n) {
        return switch (id) {
            case "c-flat-01" -> flat(0, 0, 0, 1);
            case "c-flat-02" -> flat(1, 1, 1, 1);
            case "c-flat-03" -> flat(128 / 255.0, 128 / 255.0, 128 / 255.0, 1);
            case "c-flat-04" -> flat(1, 0, 0, 1);
            case "c-grad-01" -> gray((x, y) -> x / (double) (n - 1));
            case "c-grad-02" -> gray((x, y) -> y / (double) (n - 1));
            case "c-grad-03" -> gray((x, y) -> (x + y) / (double) (2 * n - 2));
            case "c-grad-04" -> gray((x, y) -> {
                double dx = x + 0.5 - n / 2.0;
                double dy = y + 0.5 - n / 2.0;
                return Math.max(0, 1 - StrictMath.sqrt(dx * dx + dy * dy) / (n / 2.0));
            });
            case "c-grad-05" -> (x, y, rgba) -> set(rgba, x / (double) (n - 1), y / (double) (n - 1),
                    (x + y) / (double) (2 * n - 2), 1);
            case "c-grad-06" -> (x, y, rgba) -> set(rgba, 1, 0, 0, x / (double) (n - 1));
            case "c-tone-01" -> tone(n, 0.5, 1, 0, 0);
            case "c-tone-02" -> tone(n, 0.5, 0, 1, 0);
            case "c-tone-03" -> tone(n, 0.5, 3, 0, 0);
            case "c-tone-04" -> tone(n, 0.5, 1, 1, 0);
            case "c-tone-05" -> tone(n, 0.5, 1, 0, Math.PI / 2);
            case "c-tone-06" -> tone(n, 0.5, n / 2, 0, 0);
            case "c-tone-07" -> tone(n, 0.25, 1, 0, 0);
            case "c-tone-08" -> gray((x, y) -> 0.5 + 0.3 * StrictMath.cos(2 * Math.PI * x / (double) n)
                    + 0.2 * StrictMath.cos(2 * Math.PI * (3 * x + 2 * y) / (double) n));
            case "c-imp-01" -> gray((x, y) -> x == n / 2 && y == n / 2 ? 1 : 0);
            case "c-imp-02" -> gray((x, y) -> x == 0 && y == 0 ? 1 : 0);
            case "c-imp-03" -> gray((x, y) -> (x == n / 4 && y == n / 4) || (x == 3 * n / 4 && y == 3 * n / 4)
                    ? 1 : 0);
            case "c-chk-01" -> checker(1);
            case "c-chk-02" -> checker(2);
            case "c-chk-03" -> checker(4);
            case "c-chk-04" -> checker(8);
            case "c-step-01" -> gray((x, y) -> x < n / 2 ? 0 : 1);
            case "c-step-02" -> gray((x, y) -> x < (int) Math.floor(0.3125 * n) ? 0 : 1);
            case "c-step-03" -> gray((x, y) -> y < n / 4 ? 0 : 1);
            case "c-step-04" -> gray((x, y) -> x + y < n ? 0 : 1);
            case "c-chan-01" -> flat(10 / 255.0, 0, 0, 1);
            case "c-chan-02" -> flat(0, 1, 0, 1);
            case "c-chan-03" -> flat(0, 0, 1, 1);
            case "c-chan-04" -> flat(1, 0, 0, 0);
            case "c-chan-05" -> flat(1, 0, 0, 128 / 255.0);
            case "c-chan-06" -> flat(128 / 255.0, 128 / 255.0, 128 / 255.0, 128 / 255.0);
            case "c-deg-01" -> flat(0, 0, 0, 0);
            case "c-deg-02" -> flat(1, 1, 1, 0);
            case "c-deg-03" -> flat(1, 1, 1, 1 / 255.0);
            default -> throw new IllegalArgumentException("no color formula for " + id);
        };
    }

    /** One value of pixel {@code (x, y)} that fills {@code R}, {@code G} and {@code B}; alpha is {@code 1}. */
    @FunctionalInterface
    private interface Value {

        double at(int x, int y);
    }

    private Formula gray(Value value) {
        return (x, y, rgba) -> {
            double c = value.at(x, y);
            set(rgba, c, c, c, 1);
        };
    }

    private Formula flat(double r, double g, double b, double a) {
        return (x, y, rgba) -> set(rgba, r, g, b, a);
    }

    private Formula tone(int n, double amplitude, int u, int v, double phase) {
        return gray((x, y) -> 0.5
                + amplitude * StrictMath.cos(2 * Math.PI * (u * x / (double) n + v * y / (double) n) + phase));
    }

    private Formula checker(int cell) {
        return gray((x, y) -> (x / cell + y / cell) % 2);
    }

    private void set(double[] rgba, double r, double g, double b, double a) {
        rgba[0] = r;
        rgba[1] = g;
        rgba[2] = b;
        rgba[3] = a;
    }

    /**
     * Section 5 compositing: each covered layer is converted to linear, premultiplied and laid source over onto a
     * transparent black canvas in list order; the result is unpremultiplied (zero alpha gives color {@code 0}) and
     * converted back to sRGB. Coverage is the binary center rule, so there is no antialiasing.
     */
    private byte[] composite(List<GoldLayer> layers, int n, Textures textures) {
        var projections = new MaskRenderer.Projection[layers.size()];
        var texels = new byte[layers.size()][];
        for (var i = 0; i < layers.size(); i++) {
            projections[i] = masks.projection(layers.get(i).transform(), n);
            if (layers.get(i).fill() instanceof GoldLayer.Texture texture) {
                texels[i] = textures.rgba(texture.fixtureId(), n);
            }
        }
        var pixels = new byte[n * n * CHANNELS];
        var source = new double[CHANNELS];
        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                double a = 0;
                for (var i = 0; i < layers.size(); i++) {
                    GoldLayer layer = layers.get(i);
                    double u = projections[i].u(x, y);
                    double v = projections[i].v(x, y);
                    if (!masks.covers(layer.geometry(), u, v)) {
                        continue;
                    }
                    sample(layer, texels[i], n, u, v, source);
                    double alpha = source[3];
                    r = source[0] * alpha + r * (1 - alpha);
                    g = source[1] * alpha + g * (1 - alpha);
                    b = source[2] * alpha + b * (1 - alpha);
                    a = alpha + a * (1 - alpha);
                }
                int at = (y * n + x) * CHANNELS;
                pixels[at] = encode(a == 0 ? 0 : linearToSrgb(r / a));
                pixels[at + 1] = encode(a == 0 ? 0 : linearToSrgb(g / a));
                pixels[at + 2] = encode(a == 0 ? 0 : linearToSrgb(b / a));
                pixels[at + 3] = encode(a);
            }
        }
        return pixels;
    }

    /** The straight alpha, linear color of a layer at canonical point {@code (u, v)}. */
    private void sample(GoldLayer layer, byte[] texels, int n, double u, double v, double[] out) {
        switch (layer.fill()) {
            case GoldLayer.Flat flat -> {
                out[0] = srgbToLinear(flat.r() / 255.0);
                out[1] = srgbToLinear(flat.g() / 255.0);
                out[2] = srgbToLinear(flat.b() / 255.0);
                out[3] = layer.alpha();
            }
            case GoldLayer.Texture ignored -> {
                int tx = Math.min((int) Math.floor(u * n), n - 1);
                int ty = Math.min((int) Math.floor(v * n), n - 1);
                int at = (ty * n + tx) * CHANNELS;
                out[0] = srgbToLinear((texels[at] & 0xff) / 255.0);
                out[1] = srgbToLinear((texels[at + 1] & 0xff) / 255.0);
                out[2] = srgbToLinear((texels[at + 2] & 0xff) / 255.0);
                out[3] = layer.alpha() * ((texels[at + 3] & 0xff) / 255.0);
            }
        }
    }
}
