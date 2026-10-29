package consumer;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingReasoningStage;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.adaptation.codec.CheckpointCodec;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.comparison.RoutingScenario;
import monada.neuron.routing.comparison.ScenarioComparator;
import monada.neuron.routing.comparison.ScenarioComparison;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.features.TaskFeatureReadiness;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import monada.neuron.routing.review.DecisionReview;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Minimal host that embeds Neuron through the public host API only. */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static CognitiveCycleResult runOneCycle() {
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(new CognitiveBudget(100, 100, 100))
                .build();
        var input = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));
        return runtime.execute(List.of(input));
    }

    /** Level B routing with a host-configured trusted profile, verified against the host's own configuration. */
    public static RoutingDecision routeOneStageVerified() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        var catalog = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
        var policy = LexicographicRoutingPolicy.reference();
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        var stage = new RoutingReasoningStage(policy, TrustedRoutingProfile.lexicographic(policy),
                context -> Optional.of(new RoutingInput(request, catalog, preference)), 0);
        var context = new CognitiveContext(new CognitiveBudget(1, 1, 0),
                Optional.of(HostExecutionContext.of(new HostReference("exec"))));
        return stage.execute(new PrimaryMonad(new UUID(0L, 1L)), List.of(), context).decision();
    }

    /** Level A routing: a pure, opt-in decision over invented routes (no provider, credential or Store type). */
    public static RoutingDecision routeOneStage() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        var catalog = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
        var policy = LexicographicRoutingPolicy.reference();
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        return policy.decide(request, catalog, preference);
    }

    /** Opt-in adaptive state: a caller-owned store binds a cohort and publishes the snapshot a decision reads. */
    public static RoutingDecision routeFromAdaptiveState() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        var catalog = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
        var definition = AdaptiveStateDefinition.reference();
        var store = new RoutingStateStore(definition, new StatePartition("scope", "eval", "1"));
        store.bind(List.of(new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-a", 1))));
        var policy = new LexicographicRoutingPolicy(definition.state(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(),
                Optional.empty());
        return policy.decide(request, catalog, store.snapshot(policy, catalog));
    }

    /** Feature readiness diagnostics: a host preview learns what it knows before any advice is shown. */
    public static FeatureReadinessAssessment assessFeatureReadiness() {
        var features = TaskFeatures.builder().stageKind("implement").languages(List.of("java")).build();
        return new TaskFeatureReadiness().assess(features, "implement",
                Set.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES));
    }

    /** Decision review: a host builds the coded summary a person reads before accepting the Level A advice. */
    public static DecisionReview reviewOneStage() {
        return DecisionReview.from(routeOneStage());
    }

    /** Advice receipt: canonical bytes of a Level A decision, read back strictly by the host. */
    public static DecodeResult receiptRoundTrip() {
        return AdviceReceipt.decode(AdviceReceipt.of(routeOneStage()).bytes());
    }

    /** Checkpoint codec: a host turns a bound store's checkpoint into bytes it owns and reads them back strictly. */
    public static CheckpointDecodeResult checkpointRoundTrip() {
        var store = new RoutingStateStore(AdaptiveStateDefinition.reference(),
                new StatePartition("scope", "eval", "1"));
        store.bind(List.of(new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-a", 1))));
        store.advanceCutoff(0);
        return CheckpointCodec.decode(CheckpointCodec.encode(store.checkpoint("policy", "1")));
    }

    /** Advice disposition: the host records that a person followed the Level A advice, before anything runs. */
    public static DispositionResult dispositionFollowed() {
        var receipt = AdviceReceipt.of(routeOneStage());
        return AdviceDisposition.followed(receipt, receipt.decision().provenance().decisionRef());
    }

    /** Scenario preview: the Level A route becomes unavailable in a considered catalog, compared side by side. */
    public static ScenarioComparison compareUnavailableRoute() {
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        var policy = LexicographicRoutingPolicy.reference();
        var profile = TrustedRoutingProfile.lexicographic(policy);
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        var now = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
        var considered = RouteCatalog.of("catalog-2", List.of(new CatalogEntry(route, Availability.UNAVAILABLE, 1)));
        return new ScenarioComparator().compare(
                new RoutingScenario(policy, profile, new RoutingInput(request, now, preference)),
                new RoutingScenario(policy, profile, new RoutingInput(request, considered, preference)));
    }
}
