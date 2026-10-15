package monada.neuron.evaluation.routing;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Comparator;
import java.util.Objects;

/**
 * One routed stage to replay: the caller-supplied request (identity, fingerprints and decision cutoff), the catalog
 * snapshot in force at that cutoff, and what is known about its outcome.
 *
 * @param request the request; its {@code cutoff} is the decision time
 * @param catalog the catalog snapshot the decision sees
 * @param oracle the known outcomes
 */
public record ReplayTask(RoutingRequest request, RouteCatalog catalog, OutcomeOracle oracle) {

    /** Total replay order: decision cutoff, then every {@link DecisionRef} field in code-point order. */
    public static final Comparator<ReplayTask> ORDER = Comparator
            .comparingLong((ReplayTask t) -> t.request().cutoff())
            .thenComparing(t -> t.request().scopeId(), OpaqueTokens::compareCodePoints)
            .thenComparing(t -> t.request().taskId(), OpaqueTokens::compareCodePoints)
            .thenComparing(t -> t.request().executionId(), OpaqueTokens::compareCodePoints)
            .thenComparing(t -> t.request().attemptId(), OpaqueTokens::compareCodePoints)
            .thenComparing(t -> t.request().stageId(), OpaqueTokens::compareCodePoints)
            .thenComparingLong(t -> t.request().requestOrdinal());

    /** Validates that every outcome becomes visible strictly after the decision it answers. */
    public ReplayTask {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(oracle, "oracle must not be null");
        long cutoff = request.cutoff();
        switch (oracle) {
            case OutcomeOracle.KnownForEveryRoute known -> known.outcomes().forEach((route, outcome) -> {
                if (outcome.observedAt() <= cutoff) {
                    throw new IllegalArgumentException("outcome of " + route + " for " + request.executionId()
                            + " is visible at " + outcome.observedAt() + ", not after the decision at " + cutoff);
                }
            });
            case OutcomeOracle.ChosenRouteOnly logged -> {
                if (!logged.loggedDecision().provenance().decisionRef().equals(DecisionRef.of(request))) {
                    throw new IllegalArgumentException("the logged decision does not answer " + request.executionId());
                }
                if (!logged.loggedDecision().provenance().catalogVersion().equals(catalog.catalogVersion())) {
                    throw new IllegalArgumentException("the logged decision used catalog "
                            + logged.loggedDecision().provenance().catalogVersion() + ", not "
                            + catalog.catalogVersion());
                }
                for (RoutingObservation observation : logged.observed()) {
                    if (observation.ledgerSequence() <= cutoff) {
                        throw new IllegalArgumentException("observation " + observation.experienceRef()
                                + " is not after the decision at " + cutoff);
                    }
                }
            }
        }
    }

    /** The decision reference of the request. */
    public DecisionRef decisionRef() {
        return DecisionRef.of(request);
    }
}
