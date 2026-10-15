package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What the replay may know about a task's outcome.
 *
 * <p>A synthetic fixture knows the outcome of every route ({@link KnownForEveryRoute}), so any replayed choice can be
 * scored. A real export only holds the outcome of the route that was actually executed ({@link ChosenRouteOnly}); a
 * replayed choice of any other route has no outcome, and the replay reports that gap instead of inventing a
 * counterfactual success or cost.
 */
public sealed interface OutcomeOracle {

    /**
     * Known outcomes per route, for synthetic fixtures only.
     *
     * @param outcomes outcome per route; a route without an entry has no known outcome
     */
    record KnownForEveryRoute(SortedMap<RouteKey, ScriptedOutcome> outcomes) implements OutcomeOracle {

        /**
         * Copies the map in route order. The copy never keeps the caller's comparator, which could make two route
         * versions one key and score a route with another version's outcome.
         */
        public KnownForEveryRoute {
            var ordered = new TreeMap<RouteKey, ScriptedOutcome>();
            ordered.putAll(outcomes);
            outcomes = Collections.unmodifiableSortedMap(ordered);
        }

        /** Builds the oracle from any map. */
        public static KnownForEveryRoute of(Map<RouteKey, ScriptedOutcome> outcomes) {
            return new KnownForEveryRoute(new TreeMap<>(outcomes));
        }

        /** The known outcome of a route, if any. */
        public Optional<ScriptedOutcome> outcomeOf(RouteKey route) {
            return Optional.ofNullable(outcomes.get(route));
        }
    }

    /**
     * The logged decision of an exported execution and every revision observed for it.
     *
     * @param loggedDecision the decision that was actually executed
     * @param observed observed revisions of the executed route's outcome, in revision order; empty when none was
     *        exported. They are revisions of one sample: one {@code experienceRef}, strictly increasing revisions (gaps
     *        allowed) and ledger sequences, and every corrected revision superseding the same original position, which
     *        is revision 0's ledger sequence when revision 0 is listed. This is how the Store translator versions a
     *        sample: every revision after the first carries the sample's first translatable sequence. A revision read
     *        again at a later export cutoff is the same claim, as the core rebuild order treats it: the copies are
     *        merged into the one read at the later cutoff, and two different claims of one revision are rejected
     * @param chain whole-chain attempts and cost of the latest observed revision, as supplied by evaluation tooling
     */
    record ChosenRouteOnly(RoutingDecision.Selected loggedDecision, List<RoutingObservation> observed,
            ChainOutcome chain) implements OutcomeOracle {

        /** Validates that every observation answers the logged decision and that they are revisions of one sample. */
        public ChosenRouteOnly {
            Objects.requireNonNull(loggedDecision, "loggedDecision must not be null");
            observed = sameClaimsMerged(observed.stream()
                    .sorted(Comparator.comparingLong(RoutingObservation::revision)).toList());
            Objects.requireNonNull(chain, "chain must not be null");
            for (int i = 1; i < observed.size(); i++) {
                RoutingObservation earlier = observed.get(i - 1);
                RoutingObservation later = observed.get(i);
                if (!later.experienceRef().equals(earlier.experienceRef())) {
                    throw new IllegalArgumentException("logged observations " + earlier.experienceRef() + " and "
                            + later.experienceRef() + " are different samples, not revisions of one");
                }
                if (later.ledgerSequence() <= earlier.ledgerSequence()) {
                    throw new IllegalArgumentException("revision " + later.revision() + " of " + later.experienceRef()
                            + " is not after revision " + earlier.revision() + " in the ledger");
                }
            }
            // every corrected revision replaces the same original position; revision 0, when listed, is that position
            Long original = observed.isEmpty() || observed.getFirst().revision() > 0 ? null
                    : observed.getFirst().ledgerSequence();
            for (RoutingObservation observation : observed) {
                if (!(observation.supersedesSequence() instanceof Feature.Known<Long> supersedes)) {
                    continue;
                }
                if (original == null) {
                    original = supersedes.value();
                } else if (!original.equals(supersedes.value())) {
                    throw new IllegalArgumentException("revision " + observation.revision() + " of "
                            + observation.experienceRef() + " supersedes " + supersedes.value()
                            + ", not the original position " + original);
                }
            }
            for (RoutingObservation observation : observed) {
                if (!observation.outcome().executedRoute().equals(loggedDecision.route())
                        || !observation.outcome().decisionRef().equals(loggedDecision.provenance().decisionRef())) {
                    throw new IllegalArgumentException("observation " + observation.experienceRef()
                            + " does not answer the logged decision");
                }
            }
        }

        /** The logged route. */
        public RouteKey loggedRoute() {
            return loggedDecision.route();
        }

        /**
         * Merges consecutive copies of one revision that are the same claim, keeping the one read at the later export
         * cutoff (the first of an equal pair); a different claim of the same revision is left in place and rejected.
         */
        private static List<RoutingObservation> sameClaimsMerged(List<RoutingObservation> byRevision) {
            var merged = new ArrayList<RoutingObservation>(byRevision.size());
            for (RoutingObservation next : byRevision) {
                if (!merged.isEmpty()) {
                    RoutingObservation last = merged.getLast();
                    if (last.experienceRef().equals(next.experienceRef()) && last.revision() == next.revision()) {
                        long cutoff = Math.max(last.outcome().cutoff(), next.outcome().cutoff());
                        if (!asOf(last, cutoff).equals(asOf(next, cutoff))) {
                            throw new IllegalArgumentException("revision " + next.revision() + " of "
                                    + next.experienceRef() + " is claimed twice with different content");
                        }
                        if (next.outcome().cutoff() > last.outcome().cutoff()) {
                            merged.set(merged.size() - 1, next);
                        }
                        continue;
                    }
                }
                merged.add(next);
            }
            return List.copyOf(merged);
        }

        /** The observation as read at {@code cutoff}: the export cutoff is a read's context, not the claim. */
        private static RoutingObservation asOf(RoutingObservation observation, long cutoff) {
            RoutingOutcome o = observation.outcome();
            RoutingOutcome moved = new RoutingOutcome(o.contractVersion(), o.decisionRef(), o.executedRoute(),
                    o.cohortBinding(), o.catalogVersion(), o.routingPolicyId(), o.routingPolicyVersion(),
                    o.sourceFingerprint(), o.contextFingerprint(), o.constraintsFingerprint(), o.evaluationPolicyId(),
                    o.evaluationPolicyVersion(), cutoff, o.invocation(), o.outcomeStatus(), o.outcomeOrigin(),
                    o.acceptedAttemptId(), o.acceptedAttemptStatus(), o.outcomeSequence(), o.observations(),
                    o.judgedRevisions(), o.resources(), o.attribution());
            return new RoutingObservation(moved, observation.experienceRef(), observation.revision(),
                    observation.ledgerSequence(), observation.supersedesSequence());
        }
    }
}
