package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;

import java.util.Arrays;
import java.util.Objects;

/**
 * A collision-free, immutable record of every decision of one replay, kept whether or not samples are recorded, so an
 * unrecorded (measured) replay can be compared with its reference outside the timed window.
 *
 * <p>Per decision it keeps the advised {@link RouteKey} (compared by full id and version, never by hash), a code
 * packing the decision kind, basis, abstention reason and coverage ordinals, and the snapshot watermark. A run fills a
 * {@link Builder} whose arrays are sized once, so recording stores existing references and primitives and allocates
 * nothing per decision; {@link Builder#build()} copies them once into this record. The record copies its arrays on
 * construction and its accessors return copies, so a finished trace can never change.
 *
 * @param routes advised route per decision, {@code null} when none was selected
 * @param codes packed kind, basis, abstention and coverage per decision
 * @param watermarks snapshot {@code processedCutoff} per decision
 */
public record DecisionTrace(RouteKey[] routes, int[] codes, long[] watermarks) {

    private static final int NONE = 0xff;

    /** Validates that the three arrays describe the same decisions and copies them. */
    public DecisionTrace {
        Objects.requireNonNull(routes, "routes must not be null");
        Objects.requireNonNull(codes, "codes must not be null");
        Objects.requireNonNull(watermarks, "watermarks must not be null");
        if (routes.length != codes.length || codes.length != watermarks.length) {
            throw new IllegalArgumentException("routes, codes and watermarks must have one entry per decision");
        }
        routes = routes.clone();
        codes = codes.clone();
        watermarks = watermarks.clone();
    }

    /** A copy of the advised routes. */
    @Override
    public RouteKey[] routes() {
        return routes.clone();
    }

    /** A copy of the packed codes. */
    @Override
    public int[] codes() {
        return codes.clone();
    }

    /** A copy of the snapshot watermarks. */
    @Override
    public long[] watermarks() {
        return watermarks.clone();
    }

    /** Number of decisions. */
    public int size() {
        return codes.length;
    }

    /** Index of the first decision that differs from {@code other}, or -1 when the traces are equal. */
    public int firstDifference(DecisionTrace other) {
        int common = Math.min(size(), other.size());
        for (int i = 0; i < common; i++) {
            if (!Objects.equals(routes[i], other.routes[i]) || codes[i] != other.codes[i]
                    || watermarks[i] != other.watermarks[i]) {
                return i;
            }
        }
        return size() == other.size() ? -1 : common;
    }

    /** A readable rendering of decision {@code index}, for error messages; digests use {@link CanonicalEncoder}. */
    String line(int index) {
        return (routes[index] == null ? "-" : routes[index].toString()) + "|" + Integer.toHexString(codes[index]) + "|"
                + watermarks[index];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DecisionTrace that && Arrays.equals(routes, that.routes)
                && Arrays.equals(codes, that.codes) && Arrays.equals(watermarks, that.watermarks);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(routes) + Arrays.hashCode(codes)) + Arrays.hashCode(watermarks);
    }

    @Override
    public String toString() {
        return "DecisionTrace[decisions=" + size() + "]";
    }

    /** The mutable recorder of one run; never shared and never exposed. */
    static final class Builder {

        private final RouteKey[] routes;
        private final int[] codes;
        private final long[] watermarks;

        /** A recorder for {@code decisions} decisions. */
        Builder(int decisions) {
            this.routes = new RouteKey[decisions];
            this.codes = new int[decisions];
            this.watermarks = new long[decisions];
        }

        /** Records decision {@code index}; allocates nothing. */
        void record(int index, RoutingDecision decision, ReplaySample.Coverage coverage, long watermark) {
            int kind;
            int basis = NONE;
            int reason = NONE;
            RouteKey route = null;
            switch (decision) {
                case RoutingDecision.Selected selected -> {
                    kind = 1;
                    route = selected.route();
                    basis = selected.basis().ordinal();
                }
                case RoutingDecision.Abstain abstain -> {
                    kind = 2;
                    reason = abstain.reason().ordinal();
                }
                case RoutingDecision.NoEligibleRoute ignored -> kind = 3;
            }
            routes[index] = route;
            codes[index] = kind << 24 | basis << 16 | reason << 8 | coverage.ordinal();
            watermarks[index] = watermark;
        }

        /** The immutable trace; copies the arrays once. */
        DecisionTrace build() {
            return new DecisionTrace(routes, codes, watermarks);
        }
    }
}
