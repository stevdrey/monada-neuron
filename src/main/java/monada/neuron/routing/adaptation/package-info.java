/**
 * Bounded, caller-owned adaptive routing state for the opt-in Forge routing extension (Forge Routing Contract v1,
 * issue #66).
 *
 * <p>A {@link monada.neuron.routing.adaptation.RoutingStateStore} binds bounded cohorts
 * {@code (scopeId, stageKind, cohortBucket, RouteKey)} of one state partition to caller-owned preference
 * {@code Node}s, consumes {@code RoutingFeedback} through the existing
 * {@code DeterministicBaselineAdaptationPolicy}, keeps the applied-revision ledger and publishes immutable
 * {@code RoutingPreference} snapshots that a pure routing policy reads. Deriving feedback never touches the store,
 * so derivation without consumption is a no-op.
 *
 * <p>Opt-in {@link monada.neuron.routing.adaptation.RoutingStateCheckpoint}s (issue #89) capture every cohort and the
 * order-independent {@link monada.neuron.routing.adaptation.LedgerDigest}; compacting to one bounds the live ledger,
 * and a rebuild can start from one and replay only later observations, with a result identical to a rebuild from
 * scratch.
 *
 * <p>The store is mutable and <b>not thread-safe</b>; Neuron keeps no registry, session, singleton, timer,
 * background task or persistence. A Node amplitude is a preference ordinal, <b>not</b> a calibrated success
 * probability. Learned preference only reorders routes that are already eligible; it never changes authorization,
 * mandatory quality gates or API permissions, and there is no exploration.
 */
package monada.neuron.routing.adaptation;
