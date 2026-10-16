package monada.neuron.routing.adaptation;

import monada.neuron.evolution.AdaptationConfig;
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
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.feedback.RevisionReconciler;
import monada.neuron.routing.feedback.RoutingFeedback;
import monada.neuron.routing.feedback.TargetBinding;
import monada.neuron.routing.feedback.TargetBindings;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
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
 * <p>Checkpoints are opt-in. {@link #checkpoint} captures every cohort and the {@link #ledgerDigest()};
 * {@link #compact} drops the ledger entries a checkpoint covers (keeping retryable markers), folds them into the
 * digest and makes its cutoff the <em>compaction horizon</em>, so a full ledger can learn again;
 * {@link #rebuildFrom} replays only observations after a checkpoint. A store that is never compacted behaves exactly
 * as one without checkpoints. A compacted store answers what it can no longer reconcile with
 * {@link StateUpdate.BeyondCheckpoint}.
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
    private final List<AppliedRevision> ledgerView = new LedgerView();
    private long processedCutoff;
    /**
     * The last watermark the caller confirmed complete, or {@value #UNCONFIRMED}; {@link #consume} never moves it. Not 0:
     * sequence 0 is a valid position, so a fresh store must not count as confirmed through it.
     */
    private long confirmedCutoff = UNCONFIRMED;

    private static final long UNCONFIRMED = -1;
    private TargetBindings bindings;
    /** The checkpoint the ledger is compacted to; {@code null} until the first compaction or restore. */
    private RoutingStateCheckpoint compactedTo;
    /** Digest of the entries dropped by compaction, so {@link #ledgerDigest()} does not depend on compaction. */
    private LedgerDigest compacted = LedgerDigest.EMPTY;
    /** Cohorts that dropped entries moved: their effects live only in the checkpoint, so a rebind needs a rebuild. */
    private final TreeSet<CohortKey> compactedCredits = new TreeSet<>();
    /**
     * Cohorts held by any checkpoint this lineage produced, compacted to or restored from: a rebuild from any of them
     * restores them, so while unbound they keep their cohort slots. Rebuilt stores inherit it.
     */
    private final TreeSet<CohortKey> checkpointedCohorts = new TreeSet<>();
    /**
     * Digest of the live ledger as of the last fold, plus the entries added and removed since; a digest is computed only
     * when one is asked for, so consumption and replay never hash. Too many pending changes mark it stale instead.
     */
    private LedgerDigest liveDigest = LedgerDigest.EMPTY;
    private final List<AppliedRevision> addedSinceFold = new ArrayList<>();
    private final List<AppliedRevision> removedSinceFold = new ArrayList<>();
    private boolean liveDigestStale;

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

    /**
     * The watermark the caller confirmed complete, through {@link #advanceCutoff} or a rebuild's export cutoff: every
     * observation at or below it was delivered. {@link #consume} never moves it, because observations may arrive out of
     * order, so {@code processedCutoff} can run ahead of it. A checkpoint needs the two to be equal. Empty until the
     * first confirmation, including on a new store: sequence 0 is a valid position, so even an empty export is confirmed
     * explicitly, with {@code advanceCutoff(0)}.
     */
    public OptionalLong confirmedCutoff() {
        return confirmedCutoff == UNCONFIRMED ? OptionalLong.empty() : OptionalLong.of(confirmedCutoff);
    }

    /** Number of bound cohorts, active and dormant. */
    public int cohortCount() {
        return cohorts.size();
    }

    /**
     * A copy of the live ledger in sample-reference code-point order; after compaction, only the entries above the
     * horizon and the retained markers. {@link #ledgerDigest()} covers the compacted entries too.
     */
    public List<AppliedRevision> ledger() {
        return List.copyOf(ledger.values());
    }

    /**
     * The {@value LedgerDigest#FORMAT} digest of every entry this state has accounted for: the compacted entries plus
     * the live ledger. Compaction does not change it, so it equals the digest of a from-scratch rebuild's ledger.
     * {@code O(L)}.
     */
    public LedgerDigest ledgerDigest() {
        return compacted.plus(liveDigest());
    }

    /**
     * The digest of the live ledger, folding the changes since the last call: one hash per changed entry, or one pass
     * over the ledger when more changed than it holds. Amortized {@code O(changes)}, at most {@code O(L)}.
     */
    private LedgerDigest liveDigest() {
        if (liveDigestStale) {
            liveDigest = LedgerDigest.of(ledger.values());
            liveDigestStale = false;
        } else if (!addedSinceFold.isEmpty() || !removedSinceFold.isEmpty()) {
            liveDigest = liveDigest.plus(LedgerDigest.of(addedSinceFold)).minus(LedgerDigest.of(removedSinceFold));
        }
        addedSinceFold.clear();
        removedSinceFold.clear();
        return liveDigest;
    }

    /** Puts a ledger entry, recording the change for the next digest; the only way an entry enters the ledger. */
    private void putEntry(AppliedRevision entry) {
        recordChange(ledger.put(entry.experienceRef(), entry), entry);
    }

    /** Removes a ledger entry, recording the change for the next digest. */
    private void removeEntry(String experienceRef) {
        recordChange(ledger.remove(experienceRef), null);
    }

    private void recordChange(AppliedRevision removed, AppliedRevision added) {
        if (liveDigestStale) {
            return;
        }
        if (removed != null) {
            removedSinceFold.add(removed);
        }
        if (added != null) {
            addedSinceFold.add(added);
        }
        // keeps the pending changes O(L): past that, one recomputation is cheaper than folding them
        if (addedSinceFold.size() + removedSinceFold.size() > Math.max(ledger.size(), PENDING_CHANGES_FLOOR)) {
            addedSinceFold.clear();
            removedSinceFold.clear();
            liveDigestStale = true;
        }
    }

    /** Pending digest changes always allowed before a recomputation, so a small ledger does not recompute constantly. */
    private static final int PENDING_CHANGES_FLOOR = 64;

    /** The checkpoint the ledger was compacted to or restored from; empty for a store that never was. */
    public Optional<RoutingStateCheckpoint> latestCheckpoint() {
        return Optional.ofNullable(compactedTo);
    }

    /** The compaction horizon, the {@code processedCutoff} of {@link #latestCheckpoint()}; empty when there is none. */
    public OptionalLong compactionHorizon() {
        return compactedTo == null ? OptionalLong.empty() : OptionalLong.of(compactedTo.processedCutoff());
    }

    /**
     * Binds cohorts of this partition's scope, all-or-nothing. A new cohort Node is built from the definition's
     * initial state with its deterministic identity; an already bound key is left as it is.
     *
     * <p>A new key that the ledger still credits (a cohort removed by {@link #unbindDormant} whose applied effects were
     * not yet replayed away) is refused with {@code RebuildRequired}: a baseline Node under a ledger that records those
     * effects as applied would never get them back, because their redelivery is a duplicate. The cohort is restored by
     * one staged {@link #rebuild(Collection, long, Collection, BiFunction)} whose cohort set includes the key.
     *
     * <p>A compacted store refuses the same way a new key whose effects were compacted, and a new key that a retained
     * {@code NO_TARGET_BINDING} marker at or below the horizon names by scope, bucket and route (the marker records no
     * stage kind, so the match is conservative): binding it in place would leave that sample unapplied where a replay
     * from before the horizon would apply it.
     *
     * <p>The capacity also counts the cohorts that any checkpoint of this lineage holds (one it took, compacted to or was
     * restored from, inherited by rebuilt stores) and it no longer binds. A rebuild from that checkpoint restores them,
     * so binding past them would make it unrestorable. Such a cohort keeps its slot until it is bound again. Every
     * operation keeps {@code |bound ∪ reserved| <= }{@value #MAX_COHORTS}: {@code bind} here, a rebuild by refusing a
     * cohort set that would exceed it with the reservations it inherits, and {@code compact} and {@link #rebuildFrom}
     * through the same restorability check. So no checkpoint of the lineage becomes unrestorable and the capacity a
     * refusal reports is never negative.
     *
     * <p>{@code O(k log k + k log C + L log k + C log C)} for {@code k} keys, {@code C} cohorts and {@code L} ledger
     * entries: the ledger is scanned only when a key is new, and binding is rare, so no credited-cohort index is kept.
     *
     * @param keys cohorts to bind
     * @return {@code Bound} with the new keys, or {@code CapacityExhausted} or {@code RebuildRequired} with nothing bound
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
        var credited = new TreeSet<CohortKey>();
        if (!added.isEmpty()) {
            for (AppliedRevision entry : ledger.values()) {
                if (entry.changedState() && entry.cohortKey().filter(added::contains).isPresent()) {
                    credited.add(entry.cohortKey().get());
                }
                if (compactedTo != null && entry.ledgerSequence() <= compactedTo.processedCutoff()
                        && entry.basis() instanceof EntryBasis.NoUpdateMarker marker
                        && marker.reason() == NoUpdateReason.NO_TARGET_BINDING) {
                    for (CohortKey key : added) {
                        if (names(marker, entry, key)) {
                            credited.add(key);
                        }
                    }
                }
            }
            for (CohortKey key : added) {
                if (compactedCredits.contains(key)) {
                    credited.add(key);
                }
            }
        }
        if (!credited.isEmpty()) {
            return new BindResult.RebuildRequired(List.copyOf(credited));
        }
        // a compacted store's checkpoint may hold cohorts no longer bound here; a rebuild from it restores them, so they
        // keep their slots, and a new key that is one of them takes none
        Set<CohortKey> reserved = reservedByCheckpoint();
        int reclaimed = 0;
        for (CohortKey key : added) {
            if (reserved.contains(key)) {
                reclaimed++;
            }
        }
        int available = MAX_COHORTS - cohorts.size() - reserved.size() + reclaimed;
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
     * next rebuild, which, without the binding, records them as {@code NO_TARGET_BINDING} markers; until then the key
     * cannot be bound again ({@link BindResult.RebuildRequired}). A cohort whose effects should be dropped at once is
     * instead removed by a {@link #rebuild(Collection, long, Collection, BiFunction)} whose cohort set omits it.
     *
     * @param key the cohort
     * @param catalog the catalog the route must be absent from
     * @return true when a cohort was removed, false when none was bound
     * @throws IllegalArgumentException if the cohort is bound and its route is in the catalog, so it is active
     */
    public boolean unbindDormant(CohortKey key, RouteCatalog catalog) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        if (!cohorts.containsKey(key)) {
            return false;
        }
        if (inCatalog(key.route(), catalog)) {
            throw new IllegalArgumentException("cohort " + key + " is active in catalog " + catalog.catalogVersion());
        }
        cohorts.remove(key);
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
     * <p>A compacted store first applies the horizon rules. A revision whose {@code supersedesSequence} is at or below
     * the horizon is {@code SUPERSEDES_COMPACTED}, wherever its own sequence lies, when its sample's entry was compacted
     * or when it is newer than the sample's retained marker: the compacted ledger cannot tell a correction examined
     * before the checkpoint from one never delivered, nor show a compacted cost-refined reward that depends on a
     * retained marker, so a re-read of an examined correction asks for a rebuild too, which is never wrong. Any other
     * delivery at or below the horizon of a sample whose entry was compacted is {@code COVERED}: the horizon is a
     * {@linkplain #confirmedCutoff() confirmed} watermark, so the caller asserted it was delivered before the
     * checkpoint. A delivery at or below the horizon that would apply or record
     * something is {@code BEFORE_HORIZON}. All three are
     * {@link StateUpdate.BeyondCheckpoint} and change nothing. A cost-refined derivation whose peer revision was
     * compacted is trusted to have been derived from the same export as the observation, which is later than the
     * horizon; the compacted ledger cannot re-check an older view of that peer.
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
        // reconciled first even when a horizon rule answers: it validates that the derivation answers the observation
        // and agrees with the ledger, so inconsistent caller data is rejected exactly as in an uncompacted store
        Reconciliation advice = reconciler.reconcile(ledgerView, observation, derivation);
        long horizon = compactedTo == null ? -1 : compactedTo.processedCutoff();
        if (horizon >= 0) {
            AppliedRevision retained = ledger.get(observation.experienceRef());
            // a correction first, wherever it lies (contract section 7: a superseded revision at or before the cutoff
            // invalidates the checkpoint). It may itself sit at or below the horizon, and only the compacted ledger
            // could tell an examined correction from a new one; and a newer revision of a retained marker may be the
            // cost peer of a compacted reward, which the reconciler cannot see. A repeat or older revision of a retained
            // sample changes nothing, so the reconciler still answers it.
            if (observation.supersedesSequence() instanceof Feature.Known<Long> known && known.value() <= horizon
                    && (retained == null || observation.revision() > retained.revision())) {
                return new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED,
                        horizon, known.value());
            }
            if (retained == null && observation.ledgerSequence() <= horizon) {
                return new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.COVERED, horizon,
                        observation.ledgerSequence());
            }
        }
        // the compacted entries after this position are invisible to the reconciler, so it cannot place it in order
        if (observation.ledgerSequence() <= horizon
                && (advice instanceof Reconciliation.Apply || advice instanceof Reconciliation.RecordOnly)) {
            return new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.BEFORE_HORIZON, horizon,
                    observation.ledgerSequence());
        }
        return switch (advice) {
            case Reconciliation.Apply ignored -> {
                RoutingFeedback feedback = ((FeedbackDerivation.Derived) derivation).feedback();
                // resolved only when the Node is about to move: a duplicate or stale delivery of a removed cohort's
                // sample is answered by the ledger, not rejected
                Cohort target = target(feedback);
                for (FeedbackInput input : feedback.toFeedbackInputs()) {
                    adaptation.adapt(target.node, input);
                }
                if (target != null && target.support < Integer.MAX_VALUE) {
                    // saturates instead of wrapping: a restored checkpoint may legally hold the maximum, and support
                    // is only compared against small minimums, so a full count cannot change a decision
                    target.support++;
                }
                AppliedRevision entry = AppliedRevision.of(feedback);
                putEntry(entry);
                advance(observation.ledgerSequence());
                yield new StateUpdate.Applied(entry);
            }
            case Reconciliation.RecordOnly record -> {
                putEntry(record.marker());
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
     * Advances the watermark to an export's high-watermark after its observations were consumed, and confirms it: the
     * caller asserts that every observation at or below it was delivered. A checkpoint relies on that assertion, so a
     * sample at or below a confirmed watermark that arrives later is a caller contract violation; a compacted store
     * reports it as {@code COVERED} when its entry was compacted.
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
        confirmedCutoff = watermark;
    }

    /**
     * Publishes an immutable snapshot of the active cohorts for a catalog: a cohort whose route is absent from the
     * catalog is dormant and omitted, so it is never consulted and never counts toward another route's support.
     * {@code O(C log R)} time for {@code C} cohorts and {@code R} routes, {@code O(C)} space.
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
     * initial state with the same identity, an empty ledger, no checkpoint and a zero watermark. This store is not
     * modified.
     */
    public RoutingStateStore resetCopy() {
        var copy = new RoutingStateStore(definition, partition);
        copy.bind(cohorts.keySet());
        return copy;
    }

    /**
     * Rebuilds the state over the current cohorts: {@link #rebuild(Collection, long, Collection, BiFunction)} with
     * this store's cohort keys, deriving against {@link #bindings()} (the staged copy has equal bindings).
     *
     * @param observations every effective observation of the partition, at most {@value RebuildOrder#MAX_OBSERVATIONS}
     * @param exportCutoff the export's high-watermark the rebuilt state has examined
     * @param derive derives one observation; called once per replayed observation, in replay order
     * @return the rebuilt store or where the replay stopped
     * @throws IllegalArgumentException as the general form
     */
    public RebuildResult rebuild(Collection<RoutingObservation> observations, long exportCutoff,
            Function<RoutingObservation, FeedbackDerivation> derive) {
        Objects.requireNonNull(derive, "derive must not be null");
        return rebuild(observations, exportCutoff, cohorts.keySet(), (observation, bindings) -> derive.apply(observation));
    }

    /**
     * Rebuilds the state on a staged store with exactly the given cohorts, every Node at the initial state, by replaying
     * observations in {@link RebuildOrder} order (latest revision per sample, by {@code (ledgerSequence,
     * experienceRef)}), each by its own route, so dormant cohorts are replayed too. This store is never modified: on
     * success the caller swaps in the returned store; on failure the staged copy is discarded.
     *
     * <p>The cohort set is how cohorts enter or leave with their history consistent: including a key the ledger still
     * credits restores it, and omitting a bound key drops it and turns its entries into {@code NO_TARGET_BINDING}
     * markers, in one replay. {@code derive} receives the staged bindings and must derive against them. A replayed
     * observation that is applied, recorded or not recorded continues the replay; anything else stops it.
     * {@code O(N log N + N·L + C log C)} time for {@code N} observations, a ledger of {@code L <= N} entries and
     * {@code C} cohorts.
     *
     * @param observations every effective observation of the partition, at most {@value RebuildOrder#MAX_OBSERVATIONS}
     * @param exportCutoff the export's high-watermark the rebuilt state has examined, not below this store's
     *         {@link #processedCutoff()}
     * @param cohortKeys the cohorts of the rebuilt store, all of this scope; together with the cohorts this lineage's
     *         checkpoints hold, which the rebuilt store keeps reserved, at most {@value #MAX_COHORTS}
     * @param derive derives one observation against the given bindings; called once per replayed observation
     * @return the rebuilt store or where the replay stopped
     * @throws IllegalArgumentException if the export cutoff is below this store's watermark (the rebuilt state would
     *         silently forget examined observations) or below a replayed ledger sequence, the cohort set is oversized
     *         (alone or with the checkpoint reservations it inherits) or foreign, or the observations are inconsistent
     *         or oversized
     */
    public RebuildResult rebuild(Collection<RoutingObservation> observations, long exportCutoff,
            Collection<CohortKey> cohortKeys,
            BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(cohortKeys, "cohortKeys must not be null");
        Objects.requireNonNull(derive, "derive must not be null");
        if (exportCutoff < processedCutoff) {
            throw new IllegalArgumentException("exportCutoff " + exportCutoff + " is below processedCutoff "
                    + processedCutoff + ": the rebuilt state would forget examined observations");
        }
        List<RoutingObservation> ordered = new RebuildOrder().order(observations);
        // the rebuilt store inherits this lineage's checkpoint reservations, so the requested cohorts and the cohorts
        // those checkpoints hold must fit together, or a rebuild from one of them could no longer start
        var union = new TreeSet<CohortKey>();
        for (CohortKey key : cohortKeys) {
            union.add(Objects.requireNonNull(key, "key must not be null"));
        }
        int requested = union.size();
        union.addAll(checkpointedCohorts);
        if (union.size() > MAX_COHORTS) {
            int reservedOnly = union.size() - requested;
            throw new IllegalArgumentException("a rebuilt store allows at most " + MAX_COHORTS + " cohorts, got: "
                    + union.size() + (reservedOnly > 0
                            ? " (" + reservedOnly + " held only by checkpoints of this store)" : ""));
        }
        var staged = new RoutingStateStore(definition, partition);
        // inherited before binding, so the staged bind counts the reservations as every bind does
        staged.checkpointedCohorts.addAll(checkpointedCohorts);
        if (staged.bind(cohortKeys) instanceof BindResult.CapacityExhausted full) {
            throw new IllegalStateException("the cohort union was checked, yet the staged bind found only "
                    + full.available() + " free slots");
        }
        return staged.replay(ordered, exportCutoff, derive);
    }

    /**
     * A checkpoint of this state at its {@code processedCutoff}: every cohort's Node channels and support, active and
     * dormant, the {@link #ledgerDigest()} and the retryable markers. The state is not modified, but the checkpoint's
     * cohorts keep their slots from then on (see {@link #bind}), so a rebuild from it stays possible after cohort churn.
     * {@code O(C)} plus the hashing of the ledger entries changed since the last digest.
     *
     * <p>The watermark must be {@linkplain #confirmedCutoff() confirmed}: a checkpoint stands for everything at or below
     * its cutoff, and compaction and {@link #rebuildFrom} skip what it covers, so a sample still missing below an
     * unconfirmed watermark (observations may arrive out of order) would be lost.
     *
     * @param policyId routing policy the checkpoint is taken for (provenance only)
     * @param policyVersion version of that routing policy
     * @throws IllegalStateException if the watermark was never confirmed, or observations were consumed beyond the
     *         confirmed one; call {@link #advanceCutoff} with the export's high-watermark first
     */
    public RoutingStateCheckpoint checkpoint(String policyId, String policyVersion) {
        if (confirmedCutoff == UNCONFIRMED) {
            throw new IllegalStateException("the watermark was never confirmed: call advanceCutoff with the export's"
                    + " high-watermark (advanceCutoff(0) for an empty one) before a checkpoint");
        }
        if (processedCutoff > confirmedCutoff) {
            throw new IllegalStateException("processedCutoff " + processedCutoff + " is beyond the confirmed watermark "
                    + confirmedCutoff + ": confirm the export's high-watermark with advanceCutoff before a checkpoint");
        }
        var cohortValues = new ArrayList<CheckpointCohort>(cohorts.size());
        for (Map.Entry<CohortKey, Cohort> entry : cohorts.entrySet()) {
            Node node = entry.getValue().node;
            FrequencyState state = node.getFrequencyState();
            cohortValues.add(new CheckpointCohort(entry.getKey(), state.amplitude(), state.frequency(), state.phase(),
                    node.getEnergy(), entry.getValue().support));
        }
        var markers = new ArrayList<AppliedRevision>();
        for (AppliedRevision entry : ledger.values()) {
            if (entry.retryable()) {
                markers.add(entry);
            }
        }
        var checkpoint = new RoutingStateCheckpoint(partition.scopeId(), definition.featureSchemaVersion(),
                partition.evaluationPolicyId(), partition.evaluationPolicyVersion(), definition.mappingVersion(),
                policyId, policyVersion, processedCutoff, cohortValues, ledgerDigest(), markers);
        // the caller may restore from it later, so its cohorts keep their slots while unbound
        checkpointedCohorts.addAll(cohorts.keySet());
        return checkpoint;
    }

    /** {@link #checkpoint(String, String)} with the policy's identity. */
    public RoutingStateCheckpoint checkpoint(RoutingPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return checkpoint(policy.policyId(), policy.policyVersion());
    }

    /**
     * Compacts the ledger to a checkpoint taken earlier from this state, all-or-nothing: every entry at or below its
     * cutoff except retryable markers is folded into the digest and dropped, its cutoff becomes the compaction horizon
     * and it becomes the {@link #latestCheckpoint()}. The Nodes, support counts, watermark and {@link #ledgerDigest()}
     * do not change. Compacting with an older checkpoint keeps the newer entries, so a correction of them can still be
     * reconciled and rebuilt from that checkpoint; compacting with the newest empties the ledger.
     *
     * <p>Refused, changing nothing, when the ledger at or below the cutoff changed since the checkpoint was taken
     * ({@code STALE}), when the cutoff is below the current horizon ({@code BEHIND_HORIZON}), or when a dropped or already
     * compacted entry credits a cohort the checkpoint does not hold ({@code CREDITS_UNBOUND_COHORT}), or when a rebuild
     * could not start from it on this store as it is now ({@code UNRESTORABLE}). {@code O(L log L + C log C)}.
     *
     * @param checkpoint the checkpoint
     * @return what happened
     * @throws IllegalArgumentException if the checkpoint belongs to another partition or definition, retains a marker of
     *         another bucket mapping, holds a Node channel outside the definition's bounds, or lies beyond this
     *         store's watermark
     */
    public CompactionResult compact(RoutingStateCheckpoint checkpoint) {
        requireSamePartition(checkpoint);
        long cutoff = checkpoint.processedCutoff();
        if (cutoff > processedCutoff) {
            throw new IllegalArgumentException("the checkpoint at " + cutoff + " lies beyond processedCutoff "
                    + processedCutoff);
        }
        if (compactedTo != null && cutoff < compactedTo.processedCutoff()) {
            return new CompactionResult.Refused(CompactionResult.Reason.BEHIND_HORIZON);
        }
        var covered = new ArrayList<AppliedRevision>();
        var markers = new ArrayList<AppliedRevision>();
        var dropped = new ArrayList<AppliedRevision>();
        for (AppliedRevision entry : ledger.values()) {
            if (entry.ledgerSequence() <= cutoff) {
                covered.add(entry);
                (entry.retryable() ? markers : dropped).add(entry);
            }
        }
        // each covered entry is hashed at most once: when the checkpoint covers the whole live ledger (compacting to the
        // newest checkpoint) its folded digest is reused, and the dropped part is derived instead of hashed again
        LedgerDigest coveredDigest = covered.size() == ledger.size() ? liveDigest() : LedgerDigest.of(covered);
        if (!compacted.plus(coveredDigest).equals(checkpoint.ledgerDigest())
                || !markers.equals(checkpoint.retainedMarkers())) {
            return new CompactionResult.Refused(CompactionResult.Reason.STALE);
        }
        var credits = new TreeSet<>(compactedCredits);
        for (AppliedRevision entry : dropped) {
            if (entry.changedState()) {
                credits.add(entry.cohortKey().orElseThrow());
            }
        }
        var held = new TreeSet<CohortKey>();
        checkpoint.preferenceSnapshot().forEach(cohort -> held.add(cohort.key()));
        if (!held.containsAll(credits)) {
            return new CompactionResult.Refused(CompactionResult.Reason.CREDITS_UNBOUND_COHORT);
        }
        // compaction drops what the checkpoint covers, so a rebuild must be able to start from it afterwards
        if (restorabilityProblem(checkpoint).isPresent()) {
            return new CompactionResult.Refused(CompactionResult.Reason.UNRESTORABLE);
        }
        // everything that can fail is computed before the first mutation, so compaction stays all-or-nothing
        LedgerDigest folded = compacted.plus(coveredDigest.minus(LedgerDigest.of(markers)));
        for (AppliedRevision entry : dropped) {
            removeEntry(entry.experienceRef());
        }
        compacted = folded;
        compactedCredits.addAll(credits);
        compactedTo = checkpoint;
        checkpoint.preferenceSnapshot().forEach(cohort -> checkpointedCohorts.add(cohort.key()));
        return new CompactionResult.Compacted(dropped.size(), cutoff);
    }

    /**
     * Rebuilds the state on a staged store that starts from a checkpoint instead of the baseline, replaying only what
     * follows it. The staged store holds the checkpoint's cohorts with their Node channels and support plus this
     * store's other cohorts at the baseline, the checkpoint's retained markers as its ledger, the checkpoint's digest
     * and its cutoff as both watermark and compaction horizon. Observations are ordered by {@link RebuildOrder} (the
     * latest revision of each sample). One at or below the cutoff whose sample the checkpoint does not retain is
     * history the checkpoint already contains, because it was taken after examining it, and is skipped; the rest are
     * consumed under the horizon rules. One that supersedes a compacted revision ends the replay as
     * {@link RebuildResult.Failed} with {@code SUPERSEDES_COMPACTED}: the checkpoint is not valid for it, so the caller
     * uses one whose cutoff is strictly below its {@code supersedesSequence}, or rebuilds from scratch. This store is
     * never modified, and it need not be the store the checkpoint was taken from: a host restores a persisted
     * checkpoint into a new, empty store this way.
     *
     * <p>Starting from a valid checkpoint gives a state identical to a rebuild from scratch over the same cohort set:
     * equal cohort channels and support, an equal ledger after the cutoff, an equal {@link #ledgerDigest()} and an
     * equal watermark. {@code O(N log N + N·L + C log C)} time for {@code N} observations, a ledger of {@code L <= N + M}
     * entries ({@code M} retained markers) and {@code C} cohorts.
     *
     * @param checkpoint the checkpoint to start from; it may be older than this store's horizon
     * @param observations the observations after the checkpoint; a whole export is allowed, because its history up to
     *         the cutoff is dropped first, so only the rest (with every revision of a retained marker's sample) counts
     *         against the bound of {@value RebuildOrder#MAX_OBSERVATIONS}
     * @param exportCutoff the export's high-watermark the rebuilt state has examined, not below the checkpoint's cutoff
     *         nor this store's {@link #processedCutoff()}
     * @param derive derives one observation against the staged bindings; called once per replayed observation
     * @return the rebuilt store or where the replay stopped
     * @throws IllegalArgumentException if the checkpoint belongs to another partition or definition, retains a marker
     *         of another bucket mapping, the export cutoff
     *         is below the checkpoint's cutoff or this store's watermark, the checkpoint holds a Node channel outside
     *         the definition's bounds, the union of cohorts is oversized, a cohort bound after the checkpoint is named by
     *         one of its retained {@code NO_TARGET_BINDING} markers, or as
     *         {@link #rebuild(Collection, long, Collection, BiFunction)}
     */
    public RebuildResult rebuildFrom(RoutingStateCheckpoint checkpoint, Collection<RoutingObservation> observations,
            long exportCutoff, BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive) {
        requireSamePartition(checkpoint);
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(derive, "derive must not be null");
        if (exportCutoff < checkpoint.processedCutoff()) {
            throw new IllegalArgumentException("exportCutoff " + exportCutoff + " is below the checkpoint's cutoff "
                    + checkpoint.processedCutoff());
        }
        if (exportCutoff < processedCutoff) {
            throw new IllegalArgumentException("exportCutoff " + exportCutoff + " is below processedCutoff "
                    + processedCutoff + ": the rebuilt state would forget examined observations");
        }
        var retained = new TreeSet<String>(OpaqueTokens.CODE_POINT_ORDER);
        checkpoint.retainedMarkers().forEach(marker -> retained.add(marker.experienceRef()));
        // the history the checkpoint contains is dropped before ordering, so only the tail counts against the replay
        // bound: a sample with revisions on both sides of the cutoff keeps its later one, which ordering would pick
        // anyway, and a retained marker's sample keeps every revision, because it is still reconciled against the marker
        var later = new ArrayList<RoutingObservation>();
        for (RoutingObservation observation : observations) {
            Objects.requireNonNull(observation, "observations must not contain null");
            if (observation.ledgerSequence() > checkpoint.processedCutoff()
                    || retained.contains(observation.experienceRef())) {
                later.add(observation);
            }
        }
        List<RoutingObservation> ordered = new RebuildOrder().order(later);
        Optional<String> problem = restorabilityProblem(checkpoint);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }
        var staged = new RoutingStateStore(definition, partition);
        // the caller's other checkpoints of this lineage stay restorable from the rebuilt store
        staged.checkpointedCohorts.addAll(checkpointedCohorts);
        staged.restore(checkpoint, cohorts.keySet());
        return staged.replay(ordered, exportCutoff, derive);
    }

    /** Sets this new, empty store to a checkpoint plus the given extra cohorts at the baseline. */
    private void restore(RoutingStateCheckpoint checkpoint, Collection<CohortKey> extra) {
        // the channel bounds were checked by requireSamePartition, before anything else
        for (CheckpointCohort value : checkpoint.preferenceSnapshot()) {
            var cohort = new Cohort(node(value.key(),
                    new FrequencyState(value.amplitude(), value.frequency(), value.phase()), value.energy()));
            cohort.support = value.supportingObservations();
            cohorts.put(value.key(), cohort);
            if (value.supportingObservations() > 0) {
                compactedCredits.add(value.key());
            }
        }
        // the receiving store checked restorability (restorabilityProblem) before staging
        for (CohortKey key : extra) {
            if (!cohorts.containsKey(key)) {
                cohorts.put(key, new Cohort(baselineNode(key)));
            }
        }
        for (AppliedRevision marker : checkpoint.retainedMarkers()) {
            ledger.put(marker.experienceRef(), marker);
        }
        // the markers are the whole live ledger, so their digest is the live digest, hashed once
        liveDigest = LedgerDigest.of(checkpoint.retainedMarkers());
        compacted = checkpoint.ledgerDigest().minus(liveDigest);
        compactedTo = checkpoint;
        checkpoint.preferenceSnapshot().forEach(cohort -> checkpointedCohorts.add(cohort.key()));
        processedCutoff = checkpoint.processedCutoff();
        confirmedCutoff = checkpoint.processedCutoff();
        bindings = buildBindings();
    }

    /**
     * Replays ordered observations on this staged store. Applied, recorded and not-recorded observations continue the
     * replay, and so do a repeat of a retained marker and a delivery the checkpoint covers; anything else stops it.
     */
    private RebuildResult replay(List<RoutingObservation> ordered, long exportCutoff,
            BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive) {
        TargetBindings stagedBindings = bindings;
        for (RoutingObservation observation : ordered) {
            StateUpdate update = consume(observation, Objects.requireNonNull(
                    derive.apply(observation, stagedBindings), "derivation must not be null"));
            boolean continues = switch (update) {
                case StateUpdate.Applied ignored -> true;
                case StateUpdate.Recorded ignored -> true;
                case StateUpdate.Unchanged unchanged -> unchanged.advice() instanceof Reconciliation.NotRecorded
                        || unchanged.advice() instanceof Reconciliation.AlreadyApplied;
                case StateUpdate.BeyondCheckpoint beyond -> beyond.kind() == StateUpdate.BeyondCheckpoint.Kind.COVERED;
                default -> false;
            };
            if (!continues) {
                return new RebuildResult.Failed(observation, update);
            }
        }
        advanceCutoff(exportCutoff);
        return new RebuildResult.Rebuilt(this);
    }

    /**
     * The checkpoint must be of this partition and definition, and every retained marker of its bucket mapping; its
     * cutoff is checked by each caller.
     */
    private void requireSamePartition(RoutingStateCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        if (!checkpoint.scopeId().equals(partition.scopeId())
                || !checkpoint.evaluationPolicyId().equals(partition.evaluationPolicyId())
                || !checkpoint.evaluationPolicyVersion().equals(partition.evaluationPolicyVersion())
                || !checkpoint.mappingVersion().equals(definition.mappingVersion())
                || !checkpoint.featureSchemaVersion().equals(definition.featureSchemaVersion())) {
            throw new IllegalArgumentException("the checkpoint belongs to another partition or definition");
        }
        // the record checks every binding part it carries; the bucket mapping is the definition's, so it is checked
        // here, before a retained marker could answer a redelivery that this definition would bucket differently
        String bucketMapping = definition.state().cohortMapping().bucketMappingVersion();
        for (AppliedRevision marker : checkpoint.retainedMarkers()) {
            if (marker.basis() instanceof EntryBasis.NoUpdateMarker basis
                    && !basis.cohortBinding().bucketMappingVersion().equals(bucketMapping)) {
                throw new IllegalArgumentException("marker " + marker.experienceRef() + " was recorded under bucket mapping "
                        + basis.cohortBinding().bucketMappingVersion() + ", not " + bucketMapping);
            }
        }
        // the definition's bounds too, so compact never accepts a checkpoint that a rebuild could not start from
        AdaptationConfig bounds = definition.adaptation();
        for (CheckpointCohort value : checkpoint.preferenceSnapshot()) {
            if (value.amplitude() < bounds.minAmplitude() || value.amplitude() > bounds.maxAmplitude()
                    || value.energy() < bounds.minEnergy() || value.energy() > bounds.maxEnergy()
                    || value.frequency() < bounds.minFrequency() || value.frequency() > bounds.maxFrequency()) {
                throw new IllegalArgumentException("cohort " + value.key()
                        + " holds a Node channel outside the definition's adaptation bounds");
            }
        }
    }

    /**
     * Why {@link #rebuildFrom} on this store could not start from the checkpoint as the store is now, if it could not:
     * a cohort bound here but not held by the checkpoint is named by one of its retained {@code NO_TARGET_BINDING}
     * markers, or its cohorts, this store's and those this store's checkpoints hold together exceed
     * {@value #MAX_COHORTS}. That union is exactly what the store holds and reserves afterwards (a rebuilt store
     * inherits the reservations, a compacted one adds the checkpoint's cohorts), so every checkpoint of the lineage stays
     * restorable. The one rule both {@link #compact} (which refuses {@code UNRESTORABLE}) and {@link #rebuildFrom}
     * (which throws) apply.
     */
    private Optional<String> restorabilityProblem(RoutingStateCheckpoint checkpoint) {
        var held = new TreeSet<CohortKey>();
        checkpoint.preferenceSnapshot().forEach(cohort -> held.add(cohort.key()));
        var union = new TreeSet<>(held);
        for (CohortKey key : cohorts.keySet()) {
            if (held.contains(key)) {
                continue;
            }
            union.add(key);
            for (AppliedRevision marker : checkpoint.retainedMarkers()) {
                if (marker.basis() instanceof EntryBasis.NoUpdateMarker basis
                        && basis.reason() == NoUpdateReason.NO_TARGET_BINDING && names(basis, marker, key)) {
                    return Optional.of("cohort " + key + " was bound after the checkpoint at "
                            + checkpoint.processedCutoff() + ", which records sample " + marker.experienceRef()
                            + " as unbound; rebuild from an earlier checkpoint or from scratch");
                }
            }
        }
        int bound = union.size();
        union.addAll(checkpointedCohorts);
        if (union.size() > MAX_COHORTS) {
            int reservedOnly = union.size() - bound;
            return Optional.of("the checkpoint's cohorts and this store's together are " + union.size()
                    + (reservedOnly > 0 ? ", " + reservedOnly + " of them held only by this store's checkpoints" : "")
                    + ", more than the " + MAX_COHORTS + " a rebuilt store allows");
        }
        return Optional.empty();
    }

    /**
     * Cohorts that a checkpoint of this lineage holds and this store no longer binds: a rebuild from that checkpoint
     * restores them, so they keep their cohort slots. For every such checkpoint {@code C}, {@code |C ∪ bound|} then
     * stays within {@value #MAX_COHORTS}.
     */
    private Set<CohortKey> reservedByCheckpoint() {
        var reserved = new TreeSet<CohortKey>();
        for (CohortKey key : checkpointedCohorts) {
            if (!cohorts.containsKey(key)) {
                reserved.add(key);
            }
        }
        return reserved;
    }

    /** True when a {@code NO_TARGET_BINDING} marker's sample would credit the cohort, ignoring its stage kind. */
    private boolean names(EntryBasis.NoUpdateMarker marker, AppliedRevision entry, CohortKey key) {
        return marker.cohortBinding().cohortBucket().equals(key.cohortBucket())
                && entry.identity().route().equals(key.route())
                && entry.identity().decisionRef().scopeId().equals(key.scopeId());
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
        return node(key, new FrequencyState(initial.baselineAmplitude(), initial.frequency(), initial.phase()),
                initial.energy());
    }

    /** A new cohort Node with the deterministic identity, the definition's type and no history. */
    private Node node(CohortKey key, FrequencyState state, double energy) {
        return new Node.Builder()
                .id(CohortNodeIdentity.of(key, partition.evaluationPolicyId(), partition.evaluationPolicyVersion(),
                        definition.mappingVersion()))
                .type(definition.initial().nodeType())
                .frequencyState(state)
                .energy(energy)
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

    /** Binary search over the catalog's canonical {@code RouteKey} order: {@code O(log R)}, allocation-free. */
    private boolean inCatalog(RouteKey route, RouteCatalog catalog) {
        List<CatalogEntry> entries = catalog.entries();
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = entries.get(middle).key().compareTo(route);
            if (order < 0) {
                low = middle + 1;
            } else if (order > 0) {
                high = middle - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Read-only list view of the ledger values in code-point order, handed to the reconciler, which only reads its size
     * and iterates it, so a consumption no longer copies the whole ledger. Positional access walks the values.
     */
    private final class LedgerView extends AbstractList<AppliedRevision> {

        @Override
        public int size() {
            return ledger.size();
        }

        @Override
        public Iterator<AppliedRevision> iterator() {
            return Collections.unmodifiableCollection(ledger.values()).iterator();
        }

        @Override
        public AppliedRevision get(int index) {
            Objects.checkIndex(index, ledger.size());
            Iterator<AppliedRevision> values = ledger.values().iterator();
            for (int i = 0; i < index; i++) {
                values.next();
            }
            return values.next();
        }
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
