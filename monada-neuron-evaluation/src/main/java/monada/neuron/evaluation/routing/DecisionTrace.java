package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;

import java.util.Arrays;
import java.util.Objects;

/**
 * A collision-free record of every decision of one replay, kept whether or not samples are recorded, so an unrecorded
 * (measured) replay can be compared with its reference outside the timed window.
 *
 * <p>Per decision it keeps the advised {@link RouteKey} (compared by full id and version, never by hash), a code
 * packing the decision kind, basis, abstention reason and coverage ordinals, and the snapshot watermark. The arrays are
 * sized once per run, so filling them stores existing references and primitives and allocates nothing per decision.
 * Not thread-safe while it is being filled; it is filled by one run and read only after that run returns.
 *
 * @param routes advised route per decision, {@code null} when none was selected
 * @param codes packed kind, basis, abstention and coverage per decision
 * @param watermarks snapshot {@code processedCutoff} per decision
 */
public record DecisionTrace(RouteKey[] routes, int[] codes, long[] watermarks) {

    private static final int NONE = 0xff;

    /** Validates that the three arrays describe the same decisions. */
    public DecisionTrace {
        Objects.requireNonNull(routes, "routes must not be null");
        Objects.requireNonNull(codes, "codes must not be null");
        Objects.requireNonNull(watermarks, "watermarks must not be null");
        if (routes.length != codes.length || codes.length != watermarks.length) {
            throw new IllegalArgumentException("routes, codes and watermarks must have one entry per decision");
        }
    }

    /** An empty trace for {@code decisions} decisions, filled by {@link #record}. */
    static DecisionTrace sized(int decisions) {
        return new DecisionTrace(new RouteKey[decisions], new int[decisions], new long[decisions]);
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

    /** One canonical line for decision {@code index}, for digests and error messages. */
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
}
