package monada.neuron.routing.adaptation;

import monada.neuron.evolution.AdaptationPolicy;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackInput;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.CohortPreference;
import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.feedback.RevisionReconciler;
import monada.neuron.routing.feedback.RoutingFeedback;
import monada.neuron.routing.feedback.TargetBinding;
import monada.neuron.routing.feedback.TargetBindings;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Caller-owned, bounded, mutable preference state of one {@link StatePartition} under one
 * {@link AdaptiveStateDefinition} (contract section 7).
 *
 * <p>It holds the cohort Nodes, their supporting-observation counts, the applied-revision ledger and the
 * {@code processedCutoff} watermark, and publishes immutable {@link RoutingPreference} snapshots. A routing policy only
 * ever reads a snapshot, so later consumption can never change a decision already made. Feedback is consumed through
 * the existing {@link DeterministicBaselineAdaptationPolicy}; the {@link RevisionReconciler} decides how each
 * derivation relates to the ledger, and every consumption is all-or-nothing: an outcome that is not applied leaves the
 * Nodes, counts, ledger and watermark unchanged.
 *
 * <p>Bounds: at most {@value #MAX_COHORTS} cohorts (active and dormant) and {@value #MAX_LEDGER} ledger entries;
 * exceeding either is reported as a value, never evicted and never learned into another scope. Cohort Nodes keep no
 * history ({@link InitialNodeState#HISTORY_LIMIT}), so the retained state is {@code O(C + L)}.
 *
 * <p>Data structures: cohorts are point-queried by key on every consumption, iterated in canonical order for
 * snapshots and rarely added, so a {@link TreeMap} keyed by {@link CohortKey} gives {@code O(log C)} lookup and a
 * deterministic order without a separate sort; the ledger is keyed by sample reference in code-point order for the
 * same reason. Neither order depends on hashing or on insertion order.
 *
 * <p><b>Not thread-safe.</b> The caller owns the instance, its lifetime and any persistence; Neuron keeps no registry,
 * session, timer or background task. A Node amplitude is a preference ordinal, not a calibrated success probability.
 */
public final class RoutingStateStore {

    /** Maximum cohorts, active and dormant (contract section 4). */
    public static final int MAX_COHORTS = RoutingPreference.MAX_COHORTS;

    /** Maximum applied-revision ledger entries (contract section 4). */
    public static final int MAX_LEDGER = RevisionReconciler.MAX_LEDGER;

    private final AdaptiveStateDefinition definition;
    private final StatePartition partition;
    private final AdaptationPolicy adaptation;
    private final RevisionReconciler reconciler = new RevisionReconciler();
    private final TreeMap<CohortKey, Cohort> cohorts = new TreeMap<>();
    private final TreeMap<String, AppliedRevision> ledger = new TreeMap<>(OpaqueTokens.CODE_POINT_ORDER);
    private long processedCutoff;
    private TargetBindings bindings;

    /**
     * An empty state: no cohorts, an empty ledger and a zero watermark.
     *
     * @param definition composite state definition
     * @param partition scope and evaluation policy the state learns for
     */
    public RoutingStateStore(AdaptiveStateDefinition definition, StatePartition partition) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.partition = Objects.requireNonNull(partition, "partition must not be null");
        this.adaptation = new DeterministicBaselineAdaptationPolicy(definition.adaptation());
        this.bindings = buildBindings();
    }

    /** The composite definition. */
    public AdaptiveStateDefinition definition() {
        return definition;
    }

    /** The partition. */
    public StatePartition partition() {
        return partition;
    }

    /** The greatest ledger sequence or export watermark examined. */
    public long processedCutoff() {
        return processedCutoff;
    }

    /** Number of bound cohorts, active and dormant. */
    public int cohortCount() {
        return cohorts.size();
    }

    /** A copy of the ledger in sample-reference code-point order. */
    public List<AppliedRevision> ledger() {
        return List.copyOf(ledger.values());
    }

    /**
     * Binds cohorts of this partition's scope, all-or-nothing. A new cohort Node is built from the definition's
     * initial state with its deterministic identity; an already bound key is left as it is.
     *
     * @param keys cohorts to bind
     * @return {@code Bound} with the new keys, or {@code CapacityExhausted} with nothing bound
     * @throws IllegalArgumentException if a key belongs to another scope
     */
    public BindResult bind(Collection<CohortKey> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        var added = new TreeSet<CohortKey>();
        for (CohortKey key : keys) {
            Objects.requireNonNull(key, "key must not be null");
            if (!key.scopeId().equals(partition.scopeId())) {
                throw new IllegalArgumentException(
                        "cohort " + key + " does not belong to scope " + partition.scopeId());
            }
            if (!cohorts.containsKey(key)) {
                added.add(key);
            }
        }
        int available = MAX_COHORTS - cohorts.size();
        if (added.size() > available) {
            return new BindResult.CapacityExhausted(added.size(), available);
        }
        for (CohortKey key : added) {
            cohorts.put(key, new Cohort(baselineNode(key)));
        }
        if (!added.isEmpty()) {
            bindings = buildBindings();
        }
        return new BindResult.Bound(List.copyOf(added));
    }

    /**
     * Removes a dormant cohort: the only way a cohort leaves the state. Ledger entries that credited it stay until the
     * next rebuild, which, without the binding, records them as {@code NO_TARGET_BINDING} markers.
     *
     * @param key the cohort
     * @param catalog the catalog the route must be absent from
     * @return true when a cohort was removed, false when none was bound
     * @throws IllegalArgumentException if the route is in the catalog, so the cohort is active
     */
    public boolean unbindDormant(CohortKey key, RouteCatalog catalog) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        if (inCatalog(key.route(), catalog)) {
            throw new IllegalArgumentException("cohort " + key + " is active in catalog " + catalog.catalogVersion());
        }
        if (cohorts.remove(key) == null) {
            return false;
        }
        bindings = buildBindings();
        return true;
    }

    /**
     * The immutable Node bindings to derive feedback against. Deriving never touches the store, so derivation without
     * consumption leaves it bit-identical. A {@link #resetCopy()} has equal bindings.
     */
    public TargetBindings bindings() {
        return bindings;
    }

    /**
     * Consumes one derivation of one observation, all-or-nothing.
     *
     * <p>A derivation of another partition is {@code Unchanged(NotRecorded)} or {@code ForeignPartition}. Otherwise
     * the {@link RevisionReconciler} advice is followed: {@code Apply} adapts exactly the bound cohort Node of a reward
     * or penalty, increments its support count and records the entry; {@code RecordOnly} records the marker; a
     * duplicate or stale delivery changes nothing. Those outcomes advance {@code processedCutoff} to the observation's
     * ledger sequence, because the watermark records what was examined. {@code RebuildRequired}, a conflict, a stale
     * cost peer and a full ledger change nothing at all.
     *
     * @param observation the observation
     * @param derivation what the feedback policy derived for it, against {@link #bindings()}
     * @return what happened
     * @throws IllegalArgumentException if the feedback does not answer the observation, credits a Node this store does
     *         not bind to its cohort, or the observation's position is inconsistent with the ledger
     */
    public StateUpdate consume(RoutingObservation observation, FeedbackDerivation derivation) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(derivation, "derivation must not be null");
        if (derivation instanceof FeedbackDerivation.NoUpdate none && !none.reason().recordable()) {
            return new StateUpdate.Unchanged(new Reconciliation.NotRecorded(none.reason()));
        }
        List<PartitionField> foreign = foreignFields(observation.outcome());
        if (!foreign.isEmpty()) {
            return new StateUpdate.ForeignPartition(foreign);
        }
        Cohort target = derivation instanceof FeedbackDerivation.Derived derived ? target(derived.feedback()) : null;
        Reconciliation advice = reconciler.reconcile(List.copyOf(ledger.values()), observation, derivation);
        return switch (advice) {
            case Reconciliation.Apply ignored -> {
                RoutingFeedback feedback = ((FeedbackDerivation.Derived) derivation).feedback();
                for (FeedbackInput input : feedback.toFeedbackInputs()) {
                    adaptation.adapt(target.node, input);
                }
                if (target != null) {
                    target.support++;
                }
                AppliedRevision entry = AppliedRevision.of(feedback);
                ledger.put(observation.experienceRef(), entry);
                advance(observation.ledgerSequence());
                yield new StateUpdate.Applied(entry);
            }
            case Reconciliation.RecordOnly record -> {
                ledger.put(observation.experienceRef(), record.marker());
                advance(observation.ledgerSequence());
                yield new StateUpdate.Recorded(record.marker());
            }
            case Reconciliation.AlreadyApplied ignored -> {
                advance(observation.ledgerSequence());
                yield new StateUpdate.Unchanged(advice);
            }
            case Reconciliation.StaleRevision ignored -> {
                advance(observation.ledgerSequence());
                yield new StateUpdate.Unchanged(advice);
            }
            case Reconciliation.NotRecorded ignored -> new StateUpdate.Unchanged(advice);
            case Reconciliation.RebuildRequired rebuild -> new StateUpdate.RebuildRequired(rebuild);
            case Reconciliation.ConflictingRevision ignored -> new StateUpdate.Blocked(advice);
            case Reconciliation.StalePeerDependency ignored -> new StateUpdate.Blocked(advice);
            case Reconciliation.CapacityExhausted ignored -> new StateUpdate.Blocked(advice);
        };
    }

    /**
     * Advances the watermark to an export's high-watermark after its observations were consumed.
     *
     * @param watermark the new watermark, not below the current one
     * @throws IllegalArgumentException if the watermark would move backwards
     */
    public void advanceCutoff(long watermark) {
        if (watermark < processedCutoff) {
            throw new IllegalArgumentException(
                    "processedCutoff cannot move backwards from " + processedCutoff + " to " + watermark);
        }
        processedCutoff = watermark;
    }

    /**
     * Publishes an immutable snapshot of the active cohorts for a catalog: a cohort whose route is absent from the
     * catalog is dormant and omitted, so it is never consulted and never counts toward another route's support.
     * {@code O(C·R)} time for {@code C} cohorts and {@code R} routes, {@code O(C)} space.
     *
     * @param policyId producing routing policy id (provenance only)
     * @param policyVersion producing routing policy version
     * @param catalog catalog the snapshot accompanies
     */
    public RoutingPreference snapshot(String policyId, String policyVersion, RouteCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        var published = new ArrayList<CohortPreference>(cohorts.size());
        for (Map.Entry<CohortKey, Cohort> entry : cohorts.entrySet()) {
            CohortKey key = entry.getKey();
            if (inCatalog(key.route(), catalog)) {
                Cohort cohort = entry.getValue();
                published.add(new CohortPreference(key.stageKind(), key.cohortBucket(), key.route(),
                        preferenceOf(cohort.node), cohort.support));
            }
        }
        return new RoutingPreference(partition.scopeId(), definition.featureSchemaVersion(),
                partition.evaluationPolicyId(), partition.evaluationPolicyVersion(), definition.mappingVersion(),
                policyId, policyVersion, processedCutoff, published);
    }

    /** {@link #snapshot(String, String, RouteCatalog)} with the policy's identity. */
    public RoutingPreference snapshot(RoutingPolicy policy, RouteCatalog catalog) {
        Objects.requireNonNull(policy, "policy must not be null");
        return snapshot(policy.policyId(), policy.policyVersion(), catalog);
    }

    /** Diagnostic view of every cohort, active and dormant, in canonical order. */
    public List<CohortState> cohorts(RouteCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        var views = new ArrayList<CohortState>(cohorts.size());
        for (Map.Entry<CohortKey, Cohort> entry : cohorts.entrySet()) {
            CohortKey key = entry.getKey();
            Node node = entry.getValue().node;
            FrequencyState state = node.getFrequencyState();
            views.add(new CohortState(key, node.getId(), state.amplitude(), state.frequency(), state.phase(),
                    node.getEnergy(), preferenceOf(node), entry.getValue().support,
                    inCatalog(key.route(), catalog) ? CohortStatus.ACTIVE : CohortStatus.DORMANT));
        }
        return List.copyOf(views);
    }

    /**
     * A new store with the same definition, partition and cohort keys, every Node replaced by a new one built from the
     * initial state with the same identity, an empty ledger and a zero watermark. This store is not modified.
     */
    public RoutingStateStore resetCopy() {
        var copy = new RoutingStateStore(definition, partition);
        copy.bind(cohorts.keySet());
        return copy;
    }

    /**
     * Rebuilds the state on a staged {@link #resetCopy()} by replaying observations in {@link RebuildOrder} order
     * (latest revision per sample, by {@code (ledgerSequence, experienceRef)}), each by its own route, so dormant
     * cohorts are replayed too. This store is never modified: on success the caller swaps in the returned store; on
     * failure the staged copy is discarded.
     *
     * <p>{@code derive} must derive against {@link #bindings()} (a reset copy has equal bindings). A replayed
     * observation that is applied, recorded or not recorded continues the replay; anything else stops it.
     * {@code O(N log N + N·L)} time for {@code N} observations and a ledger of {@code L <= N} entries.
     *
     * @param observations every effective observation of the partition, at most {@value RebuildOrder#MAX_OBSERVATIONS}
     * @param exportCutoff the export's high-watermark the rebuilt state has examined
     * @param derive derives one observation; called once per replayed observation, in replay order
     * @return the rebuilt store or where the replay stopped
     * @throws IllegalArgumentException if the observations are inconsistent or oversized, or the export cutoff is below
     *         a replayed ledger sequence
     */
    public RebuildResult rebuild(Collection<RoutingObservation> observations, long exportCutoff,
            Function<RoutingObservation, FeedbackDerivation> derive) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(derive, "derive must not be null");
        if (exportCutoff < 0) {
            throw new IllegalArgumentException("exportCutoff must be non-negative, got: " + exportCutoff);
        }
        List<RoutingObservation> ordered = new RebuildOrder().order(observations);
        RoutingStateStore staged = resetCopy();
        for (RoutingObservation observation : ordered) {
            StateUpdate update = staged.consume(observation,
                    Objects.requireNonNull(derive.apply(observation), "derivation must not be null"));
            boolean continues = update instanceof StateUpdate.Applied || update instanceof StateUpdate.Recorded
                    || (update instanceof StateUpdate.Unchanged unchanged
                            && unchanged.advice() instanceof Reconciliation.NotRecorded);
            if (!continues) {
                return new RebuildResult.Failed(observation, update);
            }
        }
        staged.advanceCutoff(exportCutoff);
        return new RebuildResult.Rebuilt(staged);
    }

    private Cohort target(RoutingFeedback feedback) {
        if (feedback.entries().isEmpty()) {
            return null;
        }
        UUID credited = feedback.entries().getFirst().targetNodeId();
        Cohort cohort = cohorts.get(feedback.cohortKey());
        if (cohort == null || !cohort.node.getId().equals(credited)) {
            throw new IllegalArgumentException("the feedback credits Node " + credited + " for cohort "
                    + feedback.cohortKey() + ", which this store does not bind to it");
        }
        return cohort;
    }

    private List<PartitionField> foreignFields(RoutingOutcome outcome) {
        var fields = new ArrayList<PartitionField>(PartitionField.values().length);
        if (!outcome.decisionRef().scopeId().equals(partition.scopeId())) {
            fields.add(PartitionField.SCOPE);
        }
        if (!outcome.evaluationPolicyId().equals(partition.evaluationPolicyId())
                || !outcome.evaluationPolicyVersion().equals(partition.evaluationPolicyVersion())) {
            fields.add(PartitionField.EVALUATION_POLICY);
        }
        CohortBinding binding = outcome.cohortBinding();
        if (!binding.mappingVersion().equals(definition.mappingVersion())) {
            fields.add(PartitionField.MAPPING_VERSION);
        }
        if (!binding.featureSchemaVersion().equals(definition.featureSchemaVersion())) {
            fields.add(PartitionField.FEATURE_SCHEMA);
        }
        if (!binding.bucketMappingVersion().equals(definition.state().cohortMapping().bucketMappingVersion())) {
            fields.add(PartitionField.BUCKET_MAPPING);
        }
        return fields;
    }

    private void advance(long ledgerSequence) {
        processedCutoff = Math.max(processedCutoff, ledgerSequence);
    }

    private double preferenceOf(Node node) {
        return node.getFrequencyState().amplitude() - definition.initial().baselineAmplitude();
    }

    private Node baselineNode(CohortKey key) {
        InitialNodeState initial = definition.initial();
        return new Node.Builder()
                .id(CohortNodeIdentity.of(key, partition.evaluationPolicyId(), partition.evaluationPolicyVersion(),
                        definition.mappingVersion()))
                .type(initial.nodeType())
                .frequencyState(new FrequencyState(initial.baselineAmplitude(), initial.frequency(), initial.phase()))
                .energy(initial.energy())
                .historyLimit(InitialNodeState.HISTORY_LIMIT)
                .build();
    }

    private TargetBindings buildBindings() {
        var list = new ArrayList<TargetBinding>(cohorts.size());
        for (Map.Entry<CohortKey, Cohort> entry : cohorts.entrySet()) {
            list.add(new TargetBinding(entry.getKey(), entry.getValue().node.getId()));
        }
        return new TargetBindings(partition.scopeId(), partition.evaluationPolicyId(),
                partition.evaluationPolicyVersion(), definition.mappingVersion(), definition.featureSchemaVersion(),
                list);
    }

    private boolean inCatalog(RouteKey route, RouteCatalog catalog) {
        for (CatalogEntry entry : catalog.entries()) {
            if (entry.key().equals(route)) {
                return true;
            }
        }
        return false;
    }

    /** One cohort: its Node and the number of applied observations that moved it. */
    private static final class Cohort {
        final Node node;
        int support;

        Cohort(Node node) {
            this.node = node;
        }
    }
}
