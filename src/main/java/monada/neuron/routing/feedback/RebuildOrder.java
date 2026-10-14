package monada.neuron.routing.feedback;

import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The deterministic replay order for a reset-and-rebuild (contract section 7).
 *
 * <p>When a newer revision supersedes an applied reward or penalty, the caller resets the partition's Nodes to the
 * baseline and replays the <em>effective</em> observations: the latest revision of each sample, in the applied order
 * {@code (ledgerSequence, experienceRef by code point)}, the Store's total order. Deriving each one with
 * {@link RoutingFeedbackPolicy} and applying the results in this order gives a state equal to one built from scratch
 * from the same corrected export. This class only orders; it keeps nothing.
 *
 * <p>Stateless. {@code O(N log N)} time and {@code O(N)} space for {@code N <= MAX_OBSERVATIONS} observations; the
 * hash maps are point-queried and never iterated, so it cannot influence the order.
 */
public final class RebuildOrder {

    /** Maximum observations replayed per rebuild call (contract section 4); the caller pages beyond it. */
    public static final int MAX_OBSERVATIONS = 4096;

    private static final Comparator<RoutingObservation> APPLIED_ORDER =
            Comparator.comparingLong(RoutingObservation::ledgerSequence)
                    .thenComparing(RoutingObservation::experienceRef, OpaqueTokens::compareCodePoints);

    /**
     * Orders the effective observations.
     *
     * @param observations at most {@value #MAX_OBSERVATIONS} observations, in any order, possibly with several
     *         revisions of one sample
     * @return the highest revision of each sample in applied order
     * @throws IllegalArgumentException if more than {@value #MAX_OBSERVATIONS} are given, or two different
     *         observations claim the same sample revision (an identical repeat is collapsed), or a higher revision of a
     *         sample is not at a strictly later ledger position than the lower one
     */
    public List<RoutingObservation> order(Collection<RoutingObservation> observations) {
        Objects.requireNonNull(observations, "observations must not be null");
        if (observations.size() > MAX_OBSERVATIONS) {
            throw new IllegalArgumentException("a rebuild call allows at most " + MAX_OBSERVATIONS
                    + " observations, got: " + observations.size());
        }
        // Every claim is checked before an older revision is discarded, so validation never depends on input order.
        Map<String, TreeMap<Long, RoutingObservation>> bySample = new HashMap<>(observations.size() * 2);
        for (RoutingObservation observation : observations) {
            Objects.requireNonNull(observation, "observations must not contain null");
            TreeMap<Long, RoutingObservation> revisions =
                    bySample.computeIfAbsent(observation.experienceRef(), ref -> new TreeMap<>());
            RoutingObservation claimed = revisions.putIfAbsent(observation.revision(), observation);
            if (claimed != null && !claimed.equals(observation)) {
                throw new IllegalArgumentException("two different observations claim " + observation.experienceRef()
                        + "@" + observation.revision());
            }
        }
        Map<String, RoutingObservation> latest = new HashMap<>(bySample.size() * 2);
        for (Map.Entry<String, TreeMap<Long, RoutingObservation>> sample : bySample.entrySet()) {
            RoutingObservation previous = null;
            for (RoutingObservation revision : sample.getValue().values()) {
                if (previous != null && revision.ledgerSequence() <= previous.ledgerSequence()) {
                    throw new IllegalArgumentException("revision " + revision.revision() + " of " + sample.getKey()
                            + " at sequence " + revision.ledgerSequence() + " must come after revision "
                            + previous.revision() + " at sequence " + previous.ledgerSequence());
                }
                previous = revision;
            }
            latest.put(sample.getKey(), previous);
        }
        List<RoutingObservation> ordered = new ArrayList<>(latest.values());
        ordered.sort(APPLIED_ORDER);
        return List.copyOf(ordered);
    }
}
