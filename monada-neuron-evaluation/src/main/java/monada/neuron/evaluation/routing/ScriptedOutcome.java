package monada.neuron.evaluation.routing;

import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.StageAttribution;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What would be observed if one route executed one task: a synthetic fixture's known outcome for that route.
 *
 * <p>The replay turns it into a {@code RoutingObservation} of whichever decision actually advised the route, so the
 * outcome always answers the replayed decision. Positions are caller-supplied ledger sequences; nothing reads a clock.
 * The routed stage's usage ({@link #stage}) is kept apart from the whole chain's attempts and cost ({@link #chain}).
 *
 * @param observedAt ledger sequence at which the outcome becomes visible; strictly after the decision's cutoff
 * @param invocation how the routed invocation ended
 * @param status host outcome status
 * @param origin validated or imported claim
 * @param quality judged state per dimension
 * @param attribution whether the evidence is attributable to the routed stage alone
 * @param acceptedByRepair whether a later repair attempt, not the routed attempt, was accepted
 * @param stage the routed stage's own usage
 * @param chain whole-chain attempts and cost
 * @param correction an optional later revision of the same sample
 */
public record ScriptedOutcome(
        long observedAt,
        InvocationStatus invocation,
        OutcomeStatus status,
        OutcomeOrigin origin,
        Map<QualityDimension, QualityState> quality,
        StageAttribution.Basis attribution,
        boolean acceptedByRepair,
        ResourceSummary stage,
        ChainOutcome chain,
        Optional<Correction> correction) {

    /**
     * A later revision of the same sample that replaces the earlier one; everything not listed is unchanged.
     *
     * @param observedAt ledger sequence of the revision, after the original
     * @param status corrected outcome status
     * @param quality corrected judged states
     * @param chain corrected whole-chain facts
     */
    public record Correction(long observedAt, OutcomeStatus status, Map<QualityDimension, QualityState> quality,
            ChainOutcome chain) {

        /** Validates and copies the fields. */
        public Correction {
            Objects.requireNonNull(status, "status must not be null");
            quality = canonical(quality);
            Objects.requireNonNull(chain, "chain must not be null");
        }
    }

    /** Validates the fields. */
    public ScriptedOutcome {
        if (observedAt < 2) {
            throw new IllegalArgumentException("observedAt must leave room for the evidence before it, got: "
                    + observedAt);
        }
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        quality = canonical(quality);
        Objects.requireNonNull(attribution, "attribution must not be null");
        if (acceptedByRepair && status != OutcomeStatus.ACCEPTED) {
            throw new IllegalArgumentException("only an accepted outcome can be accepted by a repair");
        }
        // contract "StageAttribution": a repair chain is CHAIN_ONLY or NONE, so the repair's evidence never credits or
        // blames the routed attempt's route
        if (acceptedByRepair && attribution == StageAttribution.Basis.STAGE_LOCAL) {
            throw new IllegalArgumentException("an outcome accepted by a repair cannot be attributed to the routed stage");
        }
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(chain, "chain must not be null");
        Objects.requireNonNull(correction, "correction must not be null");
        if (correction.isPresent() && correction.get().observedAt() <= observedAt) {
            throw new IllegalArgumentException("a correction must be observed after the original");
        }
    }

    /** A validated, stage-local acceptance with every listed dimension passing. */
    public static ScriptedOutcome passing(long observedAt, List<QualityDimension> dimensions, ResourceSummary stage,
            ChainOutcome chain) {
        var quality = new EnumMap<QualityDimension, QualityState>(QualityDimension.class);
        dimensions.forEach(d -> quality.put(d, QualityState.PASS));
        return new ScriptedOutcome(observedAt, InvocationStatus.COMPLETED, OutcomeStatus.ACCEPTED,
                OutcomeOrigin.VALIDATED, quality, StageAttribution.Basis.STAGE_LOCAL, false, stage, chain,
                Optional.empty());
    }

    /** A validated, stage-local rejection with {@code failed} proven to fail and the other dimensions passing. */
    public static ScriptedOutcome failing(long observedAt, List<QualityDimension> dimensions, QualityDimension failed,
            ResourceSummary stage, ChainOutcome chain) {
        var quality = new EnumMap<QualityDimension, QualityState>(QualityDimension.class);
        dimensions.forEach(d -> quality.put(d, QualityState.PASS));
        quality.put(failed, QualityState.FAIL);
        return new ScriptedOutcome(observedAt, InvocationStatus.COMPLETED, OutcomeStatus.REJECTED,
                OutcomeOrigin.VALIDATED, quality, StageAttribution.Basis.STAGE_LOCAL, false, stage, chain,
                Optional.empty());
    }

    /** The same outcome accepted through a later repair attempt, attributable only to the whole chain. */
    public ScriptedOutcome repaired() {
        return new ScriptedOutcome(observedAt, invocation, OutcomeStatus.ACCEPTED, origin, quality,
                StageAttribution.Basis.CHAIN_ONLY, true, stage, chain, correction);
    }

    /** The same outcome with a later revision. */
    public ScriptedOutcome corrected(Correction newCorrection) {
        return new ScriptedOutcome(observedAt, invocation, status, origin, quality, attribution, acceptedByRepair,
                stage, chain, Optional.of(newCorrection));
    }

    /** The same outcome visible at another ledger sequence (and its correction shifted by the same amount). */
    public ScriptedOutcome observedAt(long newObservedAt) {
        long shift = newObservedAt - observedAt;
        return new ScriptedOutcome(newObservedAt, invocation, status, origin, quality, attribution, acceptedByRepair,
                stage, chain, correction.map(c -> new Correction(c.observedAt() + shift, c.status(), c.quality(),
                        c.chain())));
    }

    /** An unmodifiable copy in dimension order, so iteration and rendering are deterministic. */
    private static Map<QualityDimension, QualityState> canonical(Map<QualityDimension, QualityState> quality) {
        var ordered = new EnumMap<QualityDimension, QualityState>(QualityDimension.class);
        ordered.putAll(quality);
        return Collections.unmodifiableMap(ordered);
    }
}
