package monada.neuron.evaluation.routing;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.features.TaskFeatureReadiness;
import monada.neuron.routing.receipt.AdviceReceipt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A scripted person between each decision and its execution: the same choice per task index in every arm, bound
 * through the public {@link AdviceDisposition} factories over the decision's receipt, and one
 * {@link HostDecisionRecord} per decision. The disposition goes to the record only.
 *
 * <p>An alternative is resolved per decision, because two arms may advise different routes for the same task. Over a
 * selection it is the first ranked candidate that is not the advised route; with none, the person proposes the advised
 * route itself, which binds {@code Rejected(ALTERNATIVE_EQUALS_ADVICE)} and runs nothing. Over an abstention, which
 * advises no route, it is the first ranked candidate, or the first eligible route when nothing was ranked
 * ({@code eligible()} is in key order, so it is only the fallback); the disposition binds but nothing runs, because v1
 * feedback answers only a selection and a host asks for fresh advice before it runs a route nobody advised.
 *
 * <p>Stateful: give each run a fresh instance. Not thread-safe.
 */
final class HostScript implements HostStep {

    private final Map<DecisionRef, Integer> indexes = new HashMap<>();
    private final Map<Integer, HostAction> actions;
    private final Set<FeatureDimension> requiredForPreview;
    private final TaskFeatureReadiness readiness = new TaskFeatureReadiness();
    private final List<HostDecisionRecord> records = new ArrayList<>();

    /**
     * Creates a script over a fixture.
     *
     * @param fixture the fixture whose task positions the actions are keyed by
     * @param actions the person's choice by task index; a task without one is followed
     * @param requiredForPreview the dimensions the host's preview requires, for the readiness record
     */
    HostScript(ReplayFixture fixture, Map<Integer, HostAction> actions, Set<FeatureDimension> requiredForPreview) {
        List<ReplayTask> tasks = fixture.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            indexes.put(DecisionRef.of(tasks.get(i).request()), i);
        }
        this.actions = Map.copyOf(actions);
        this.requiredForPreview = Set.copyOf(requiredForPreview);
    }

    /** Every decision's record so far, in replay order. */
    List<HostDecisionRecord> records() {
        return List.copyOf(records);
    }

    @Override
    public HostExecution execute(ReplayTask task, RoutingDecision decision) {
        DecisionRef ref = DecisionRef.of(task.request());
        Integer index = indexes.get(ref);
        if (index == null) {
            throw new IllegalArgumentException("the task " + task.request().executionId() + " is not in the fixture");
        }
        HostAction action = actions.getOrDefault(index, HostAction.FOLLOW);
        AdviceReceipt receipt = AdviceReceipt.of(decision);
        Optional<RouteKey> alternative = action == HostAction.ALTERNATIVE ? alternativeOf(decision) : Optional.empty();
        Optional<DispositionResult> disposition = switch (action) {
            case FOLLOW -> Optional.of(AdviceDisposition.followed(receipt, ref));
            case ALTERNATIVE -> alternative.map(route -> AdviceDisposition.alternative(receipt, ref, route));
            case DEFER -> Optional.of(AdviceDisposition.deferred(receipt, ref));
            case DECLINE -> Optional.of(AdviceDisposition.declined(receipt, ref));
        };
        boolean bound = disposition.orElse(null) instanceof DispositionResult.Bound;
        HostExecution execution = new HostExecution.NotExecuted(action);
        if (bound && decision instanceof RoutingDecision.Selected) {
            // a bound alternative over a selection is never the advised route: that binding is rejected
            execution = switch (action) {
                case FOLLOW -> new HostExecution.Advised();
                case ALTERNATIVE -> new HostExecution.Alternative(alternative.orElseThrow());
                case DEFER, DECLINE -> execution;
            };
        }
        records.add(new HostDecisionRecord(index, task.request().stageKind(), kindOf(decision),
                decision instanceof RoutingDecision.Abstain abstain ? Optional.of(abstain.reason()) : Optional.empty(),
                receipt.sha256(), receipt.size(), missing(task), action, disposition, execution));
        return execution;
    }

    /**
     * The route the person proposes instead of the advice; the advised route itself when a selection ranks no other,
     * and nothing when the decision names no route at all.
     */
    private Optional<RouteKey> alternativeOf(RoutingDecision decision) {
        return switch (decision) {
            case RoutingDecision.Selected selected -> Optional.of(selected.candidates().stream()
                    .map(RankedCandidate::key).filter(k -> !k.equals(selected.route())).findFirst()
                    .orElse(selected.route()));
            case RoutingDecision.Abstain abstain -> abstain.candidates().isEmpty()
                    ? Optional.of(abstain.eligible().getFirst())
                    : Optional.of(abstain.candidates().getFirst().key());
            case RoutingDecision.NoEligibleRoute ignored -> Optional.empty();
        };
    }

    private List<FeatureDimension> missing(ReplayTask task) {
        return switch (readiness.assess(task.request().features(), task.request().stageKind(), requiredForPreview)) {
            case FeatureReadinessAssessment.Assessed assessed -> assessed.missingForHostPreview();
            case FeatureReadinessAssessment.SchemaMismatch mismatch -> throw new IllegalStateException(
                    "a host fixture task carries feature schema " + mismatch.actual() + ", not "
                            + mismatch.expected());
        };
    }

    private ReplaySample.DecisionKind kindOf(RoutingDecision decision) {
        return switch (decision) {
            case RoutingDecision.Selected ignored -> ReplaySample.DecisionKind.SELECTED;
            case RoutingDecision.Abstain ignored -> ReplaySample.DecisionKind.ABSTAINED;
            case RoutingDecision.NoEligibleRoute ignored -> ReplaySample.DecisionKind.NO_ELIGIBLE_ROUTE;
        };
    }
}
