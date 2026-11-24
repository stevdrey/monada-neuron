package monada.neuron.evaluation.jmh;

import monada.neuron.visual.BinaryMask;
import monada.neuron.visual.Result;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * JMH comparison of three layouts for a small binary mask (issue #143, spec 0012 AC-10): a flat {@code boolean[]}
 * plane, a flat {@code byte[]} plane and the packed {@code long[]} words that {@link BinaryMask} uses.
 *
 * <p>Each layout runs the same five operations on the same disc at 32x32 and 64x64: build from a caller array with
 * the defensive copy, a full {@code get(x, y)} scan, a foreground count, {@code equals} against an equal mask and
 * {@code hashCode}. The flat planes are measured as bare arrays with the same row major index, which is their best
 * case; the packed layout is measured through the published class. Run with {@code -prof gc} for bytes per
 * operation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class BinaryMaskLayoutBenchmark {

    @Param({"32", "64"})
    private int size;

    private boolean[] booleanPlane;
    private boolean[] booleanTwin;
    private byte[] bytePlane;
    private byte[] byteTwin;
    private long[] words;
    private BinaryMask packed;
    private BinaryMask packedTwin;

    @Setup(Level.Trial)
    public void createDisc() {
        booleanPlane = new boolean[size * size];
        bytePlane = new byte[size * size];
        words = new long[(size * size + 63) >>> 6];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = (x + 0.5) / size - 0.5;
                double v = (y + 0.5) / size - 0.5;
                if (u * u + v * v <= 0.25) {
                    int bit = y * size + x;
                    booleanPlane[bit] = true;
                    bytePlane[bit] = 1;
                    words[bit >>> 6] |= 1L << bit;
                }
            }
        }
        booleanTwin = booleanPlane.clone();
        byteTwin = bytePlane.clone();
        packed = build(words);
        packedTwin = build(words);
        requireSameDisc();
    }

    /**
     * Fails the trial unless the three layouts hold the same disc: every pixel agrees and each counts the same
     * number of lit pixels by every route that is timed. Runs once per trial, outside the timed region.
     */
    private void requireSameDisc() {
        int lit = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean expected = booleanPlane[y * size + x];
                if ((bytePlane[y * size + x] != 0) != expected || packed.get(x, y) != expected) {
                    throw new IllegalStateException("the layouts disagree at (" + x + ", " + y + ")");
                }
                if (expected) {
                    lit++;
                }
            }
        }
        int[] counts = {scanBoolean(), scanByte(), scanPacked(), countBoolean(), countByte(), countPacked()};
        for (int count : counts) {
            if (count != lit) {
                throw new IllegalStateException("lit counts " + Arrays.toString(counts) + " differ from " + lit);
            }
        }
        if (!equalsBoolean() || !equalsByte() || !equalsPacked()) {
            throw new IllegalStateException("a layout is not equal to its twin");
        }
        System.out.println("BinaryMaskLayoutBenchmark size=" + size + " lit=" + lit + " layoutsAgree=true");
    }

    private BinaryMask build(long[] source) {
        if (BinaryMask.of(size, size, source) instanceof Result.Ok<BinaryMask>(BinaryMask mask)) {
            return mask;
        }
        throw new IllegalStateException("a " + size + "x" + size + " mask must build");
    }

    @Benchmark
    public boolean[] buildBoolean() {
        return booleanPlane.clone();
    }

    @Benchmark
    public byte[] buildByte() {
        return bytePlane.clone();
    }

    @Benchmark
    public Result<BinaryMask> buildPacked() {
        return BinaryMask.of(size, size, words);
    }

    @Benchmark
    public int scanBoolean() {
        int lit = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (booleanPlane[y * size + x]) {
                    lit++;
                }
            }
        }
        return lit;
    }

    @Benchmark
    public int scanByte() {
        int lit = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (bytePlane[y * size + x] != 0) {
                    lit++;
                }
            }
        }
        return lit;
    }

    @Benchmark
    public int scanPacked() {
        int lit = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (packed.get(x, y)) {
                    lit++;
                }
            }
        }
        return lit;
    }

    @Benchmark
    public int countBoolean() {
        int lit = 0;
        for (boolean pixel : booleanPlane) {
            if (pixel) {
                lit++;
            }
        }
        return lit;
    }

    @Benchmark
    public int countByte() {
        int lit = 0;
        for (byte pixel : bytePlane) {
            lit += pixel;
        }
        return lit;
    }

    @Benchmark
    public int countPacked() {
        return packed.cardinality();
    }

    @Benchmark
    public boolean equalsBoolean() {
        return Arrays.equals(booleanPlane, booleanTwin);
    }

    @Benchmark
    public boolean equalsByte() {
        return Arrays.equals(bytePlane, byteTwin);
    }

    @Benchmark
    public boolean equalsPacked() {
        return packed.equals(packedTwin);
    }

    @Benchmark
    public int hashBoolean() {
        return Arrays.hashCode(booleanPlane);
    }

    @Benchmark
    public int hashByte() {
        return Arrays.hashCode(bytePlane);
    }

    @Benchmark
    public int hashPacked() {
        return packed.hashCode();
    }
}
