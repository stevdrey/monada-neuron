package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;

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

        /** Copies the map in route order. */
        public KnownForEveryRoute {
            outcomes = Collections.unmodifiableSortedMap(new TreeMap<>(outcomes));
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
     *        exported. They are revisions of one sample: one {@code experienceRef}, strictly increasing revisions and
     *        ledger sequences, and each later revision superseding the one before it
     * @param chain whole-chain attempts and cost of the latest observed revision, as supplied by evaluation tooling
     */
    record ChosenRouteOnly(RoutingDecision.Selected loggedDecision, List<RoutingObservation> observed,
            ChainOutcome chain) implements OutcomeOracle {

        /** Validates that every observation answers the logged decision and that they are revisions of one sample. */
        public ChosenRouteOnly {
            Objects.requireNonNull(loggedDecision, "loggedDecision must not be null");
            observed = observed.stream().sorted(Comparator.comparingLong(RoutingObservation::revision)).toList();
            Objects.requireNonNull(chain, "chain must not be null");
            for (int i = 1; i < observed.size(); i++) {
                RoutingObservation earlier = observed.get(i - 1);
                RoutingObservation later = observed.get(i);
                if (!later.experienceRef().equals(earlier.experienceRef())) {
                    throw new IllegalArgumentException("logged observations " + earlier.experienceRef() + " and "
                            + later.experienceRef() + " are different samples, not revisions of one");
                }
                if (later.revision() == earlier.revision()) {
                    throw new IllegalArgumentException("revision " + later.revision() + " of "
                            + later.experienceRef() + " appears twice");
                }
                if (later.ledgerSequence() <= earlier.ledgerSequence()) {
                    throw new IllegalArgumentException("revision " + later.revision() + " of " + later.experienceRef()
                            + " is not after revision " + earlier.revision() + " in the ledger");
                }
                if (!(later.supersedesSequence() instanceof Feature.Known<Long> supersedes)
                        || supersedes.value() != earlier.ledgerSequence()) {
                    throw new IllegalArgumentException("revision " + later.revision() + " of " + later.experienceRef()
                            + " does not supersede revision " + earlier.revision() + " at "
                            + earlier.ledgerSequence());
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
    }
}
