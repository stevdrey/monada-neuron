package monada.neuron.evaluation.routing;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A bounded, immutable replay workload: one state partition, one routing policy and the tasks to replay in
 * {@link ReplayTask#ORDER}.
 *
 * <p>Bounds are checked here, before any replay: at most {@value #MAX_TASKS} tasks, at most
 * {@value RoutingStateStore#MAX_COHORTS} cohorts and at most {@value RebuildOrder#MAX_OBSERVATIONS} observations
 * (revisions included), so a full rebuild of the replayed state is always possible. An oversized fixture is rejected,
 * never truncated.
 *
 * @param fixtureId fixture identity
 * @param version fixture version; any content change bumps it
 * @param definition the adaptive state definition
 * @param evidencePolicy the host evidence policy in force
 * @param policy the routing policy; its state definition must be {@code definition.state()}
 * @param partition the state partition every task belongs to
 * @param tasks the tasks, stored in replay order
 * @param horizon the examined watermark of the export (for a Store export, its high-watermark), at or after every task
 *        cutoff and every observation position; the adaptive state ends the replay at this watermark
 */
public record ReplayFixture(
        String fixtureId,
        String version,
        AdaptiveStateDefinition definition,
        EvidencePolicy evidencePolicy,
        LexicographicRoutingPolicy policy,
        StatePartition partition,
        List<ReplayTask> tasks,
        long horizon) {

    /** Maximum tasks per fixture. */
    public static final int MAX_TASKS = 1024;

    /** Validates the bounds and sorts the tasks. */
    public ReplayFixture {
        OpaqueTokens.require(fixtureId, "fixtureId");
        OpaqueTokens.require(version, "version");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(evidencePolicy, "evidencePolicy must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(partition, "partition must not be null");
        if (!policy.definition().equals(definition.state())) {
            throw new IllegalArgumentException("the policy must route over the adaptive definition's state "
                    + definition.mappingVersion() + ", got " + policy.definition().mappingVersion());
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("at most " + MAX_TASKS + " tasks, got: " + tasks.size());
        }
        tasks = tasks.stream().sorted(ReplayTask.ORDER).toList();
        int observations = 0;
        RoutingRequest previous = null;
        for (ReplayTask task : tasks) {
            RoutingRequest request = task.request();
            // contract "Ordinals and cutoff": requestOrdinal is strictly increasing per scope, and a fixture has one
            // scope. Replay order breaks cutoff ties by ordinal, so this rejects only a repeated ordinal or a request
            // issued later but decided at an earlier cutoff
            if (previous != null && request.requestOrdinal() <= previous.requestOrdinal()) {
                throw new IllegalArgumentException("requestOrdinal must be strictly increasing in replay order: "
                        + request.executionId() + " has " + request.requestOrdinal() + " after "
                        + previous.executionId() + " with " + previous.requestOrdinal());
            }
            previous = request;
            if (!request.scopeId().equals(partition.scopeId())
                    || !request.evaluationPolicyId().equals(partition.evaluationPolicyId())
                    || !request.evaluationPolicyVersion().equals(partition.evaluationPolicyVersion())) {
                throw new IllegalArgumentException(request.executionId() + " is outside partition " + partition);
            }
            observations += switch (task.oracle()) {
                case OutcomeOracle.KnownForEveryRoute known -> known.outcomes().isEmpty() ? 0 : 2;
                case OutcomeOracle.ChosenRouteOnly logged -> logged.observed().size();
            };
        }
        if (observations > RebuildOrder.MAX_OBSERVATIONS) {
            throw new IllegalArgumentException("at most " + RebuildOrder.MAX_OBSERVATIONS
                    + " observations (a full rebuild must stay possible), got up to: " + observations);
        }
        long latest = latestPosition(tasks);
        if (horizon < latest) {
            throw new IllegalArgumentException("horizon " + horizon + " is before position " + latest
                    + " of the fixture: the replay would examine beyond its own horizon");
        }
        int cohorts = cohortKeys(definition, tasks).size();
        if (cohorts > RoutingStateStore.MAX_COHORTS) {
            throw new IllegalArgumentException(
                    "at most " + RoutingStateStore.MAX_COHORTS + " cohorts, got: " + cohorts);
        }
    }

    /** A fixture whose horizon is its latest task cutoff or observation position. */
    public ReplayFixture(String fixtureId, String version, AdaptiveStateDefinition definition,
            EvidencePolicy evidencePolicy, LexicographicRoutingPolicy policy, StatePartition partition,
            List<ReplayTask> tasks) {
        this(fixtureId, version, definition, evidencePolicy, policy, partition, tasks, latestPosition(tasks));
    }

    /** The latest decision cutoff, scripted outcome or correction position, or logged observation position. */
    private static long latestPosition(List<ReplayTask> tasks) {
        long latest = 0;
        for (ReplayTask task : tasks) {
            latest = Math.max(latest, task.request().cutoff());
            switch (task.oracle()) {
                case OutcomeOracle.KnownForEveryRoute known -> {
                    for (ScriptedOutcome outcome : known.outcomes().values()) {
                        latest = Math.max(latest, outcome.observedAt());
                        if (outcome.correction().isPresent()) {
                            latest = Math.max(latest, outcome.correction().get().observedAt());
                        }
                    }
                }
                case OutcomeOracle.ChosenRouteOnly logged -> {
                    for (var observation : logged.observed()) {
                        latest = Math.max(latest, observation.ledgerSequence());
                    }
                }
            }
        }
        return latest;
    }

    /**
     * Every cohort a replayed decision or a logged outcome can credit: scope, stage kind, the request's bucket and each
     * catalog route that serves the stage, plus each logged decision's own binding. Bound before replay, so feedback
     * never misses its target because of binding order.
     */
    public SortedSet<CohortKey> cohortKeys() {
        return cohortKeys(definition, tasks);
    }

    private static SortedSet<CohortKey> cohortKeys(AdaptiveStateDefinition definition, List<ReplayTask> tasks) {
        var keys = new TreeSet<CohortKey>();
        for (ReplayTask task : tasks) {
            RoutingRequest request = task.request();
            String bucket = definition.state().cohortMapping().bucketOf(request.features());
            for (CatalogEntry entry : task.catalog().entries()) {
                if (entry.descriptor().stages().contains(request.stageKind())) {
                    keys.add(new CohortKey(request.scopeId(), request.stageKind(), bucket, entry.key()));
                }
            }
            if (task.oracle() instanceof OutcomeOracle.ChosenRouteOnly logged) {
                keys.add(new CohortKey(request.scopeId(), request.stageKind(),
                        logged.loggedDecision().cohortBinding().cohortBucket(), logged.loggedRoute()));
            }
        }
        return keys;
    }
}
