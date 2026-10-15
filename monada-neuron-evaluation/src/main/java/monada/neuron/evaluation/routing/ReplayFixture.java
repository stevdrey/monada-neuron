package monada.neuron.evaluation.routing;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.HashMap;
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
        if (!evidencePolicy.id().equals(partition.evaluationPolicyId())
                || !evidencePolicy.version().equals(partition.evaluationPolicyVersion())) {
            throw new IllegalArgumentException("evidence policy " + evidencePolicy.id() + "/" + evidencePolicy.version()
                    + " is not the partition's evaluation policy " + partition.evaluationPolicyId() + "/"
                    + partition.evaluationPolicyVersion());
        }
        if (!policy.definition().equals(definition.state())) {
            throw new IllegalArgumentException("the policy must route over the adaptive definition's state "
                    + definition.mappingVersion() + ", got " + policy.definition().mappingVersion());
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("at most " + MAX_TASKS + " tasks, got: " + tasks.size());
        }
        tasks = tasks.stream().sorted(ReplayTask.ORDER).toList();
        int observations = 0;
        var ordinals = new HashMap<Long, String>();
        var refs = new HashMap<String, String>();
        var identities = new ObservationFactory();
        for (ReplayTask task : tasks) {
            RoutingRequest request = task.request();
            // contract "Ordinals and cutoff": requestOrdinal numbers the requests of a scope, strictly increasing in
            // issue order, and a fixture has one scope, so no two tasks share one. Cutoffs are caller-supplied and not
            // tied to issue order: a request issued later may be decided against an earlier snapshot
            String sameOrdinal = ordinals.putIfAbsent(request.requestOrdinal(), request.executionId());
            if (sameOrdinal != null) {
                throw new IllegalArgumentException("requestOrdinal " + request.requestOrdinal() + " numbers both "
                        + sameOrdinal + " and " + request.executionId());
            }
            // the replay joins every observation to its decision by sample reference, so one reference must answer
            // one task: a synthetic outcome's reference is derived from its decision, a logged one is the export's
            for (String ref : switch (task.oracle()) {
                case OutcomeOracle.KnownForEveryRoute ignored -> List.of(identities.experienceRef(request));
                case OutcomeOracle.ChosenRouteOnly logged -> logged.observed().isEmpty() ? List.<String>of()
                        : List.of(logged.observed().getFirst().experienceRef());
            }) {
                String sameRef = refs.putIfAbsent(ref, request.executionId());
                if (sameRef != null) {
                    throw new IllegalArgumentException("sample " + ref + " answers both " + sameRef + " and "
                            + request.executionId());
                }
            }
            if (!request.scopeId().equals(partition.scopeId())
                    || !request.evaluationPolicyId().equals(partition.evaluationPolicyId())
                    || !request.evaluationPolicyVersion().equals(partition.evaluationPolicyVersion())) {
                throw new IllegalArgumentException(request.executionId() + " is outside partition " + partition);
            }
            // the most a task can deliver: a synthetic task executes one route the policy can select (in its catalog,
            // available and serving its stage), an original plus its correction if that route's outcome has one; a
            // logged task delivers exactly its listed revisions
            observations += switch (task.oracle()) {
                case OutcomeOracle.KnownForEveryRoute known -> mostDelivered(task, known);
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

    /**
     * The most observations a synthetic task can deliver: 0 when no selectable route has an outcome, 2 when a
     * selectable route's outcome has a correction, 1 otherwise. A route is selectable when the task's catalog lists it
     * as available for the task's stage; the policy's eligibility filter can only narrow that, so this is an upper
     * bound.
     */
    private static int mostDelivered(ReplayTask task, OutcomeOracle.KnownForEveryRoute known) {
        int most = 0;
        for (CatalogEntry entry : task.catalog().entries()) {
            if (entry.availability() != Availability.AVAILABLE
                    || !entry.descriptor().stages().contains(task.request().stageKind())) {
                continue;
            }
            ScriptedOutcome outcome = known.outcomes().get(entry.key());
            if (outcome != null) {
                most = Math.max(most, outcome.correction().isPresent() ? 2 : 1);
            }
        }
        return most;
    }

    /** A fixture whose horizon is its latest task cutoff, observation position or export cutoff. */
    public ReplayFixture(String fixtureId, String version, AdaptiveStateDefinition definition,
            EvidencePolicy evidencePolicy, LexicographicRoutingPolicy policy, StatePartition partition,
            List<ReplayTask> tasks) {
        this(fixtureId, version, definition, evidencePolicy, policy, partition, tasks, latestPosition(tasks));
    }

    /**
     * The same fixture under another identity with other tasks, keeping its horizon unless a task reaches past it, so
     * a derived fixture never claims to have examined less than the one it came from.
     */
    public ReplayFixture withTasks(String newFixtureId, List<ReplayTask> newTasks) {
        return new ReplayFixture(newFixtureId, version, definition, evidencePolicy, policy, partition, newTasks,
                Math.max(horizon, latestPosition(newTasks)));
    }

    /**
     * The latest decision cutoff, scripted outcome or correction position, or logged observation position or export
     * cutoff: a logged observation was read at its outcome's cutoff, which the replay must have examined too.
     */
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
                        latest = Math.max(latest,
                                Math.max(observation.ledgerSequence(), observation.outcome().cutoff()));
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
