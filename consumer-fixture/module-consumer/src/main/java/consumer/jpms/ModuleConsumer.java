package consumer.jpms;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.resonance.AdaptiveBatchResonanceEvaluator;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.TrustedRoutingProfile;
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
import monada.neuron.routing.comparison.RouteChange;
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
import monada.neuron.routing.review.ReviewNotice;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Module-path smoke host. Each check throws on failure, so {@code run} fails the build; no test framework is
 * needed and the published repository stays the only one the consumer resolves from.
 */
public final class ModuleConsumer {

    private ModuleConsumer() {
    }

    public static void main(String[] args) {
        Module self = ModuleConsumer.class.getModule();
        Module neuron = Signal.class.getModule();
        require(self.isNamed() && self.getName().equals("monada.neuron.consumer.jpms"),
                "the consumer must run as a named module, was " + self);
        require("monada.neuron".equals(neuron.getName()), "Neuron must resolve as module monada.neuron, was " + neuron);
        require(neuron.getDescriptor().isAutomatic(), "monada.neuron is an automatic module (ADR 0032)");
        // The jar file name derives the same automatic name, so the published manifest is what must be checked.
        require("monada.neuron".equals(manifestModuleName(Signal.class)),
                "the published jar must declare Automatic-Module-Name monada.neuron, declares "
                        + manifestModuleName(Signal.class));
        require(ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty(),
                "the consumer must run without jdk.incubator.vector");
        require(ModuleLayer.boot().modules().stream().map(Module::getName)
                        .noneMatch(name -> name.startsWith("com.monada") || name.contains("evaluation")),
                "no Store or evaluation module may be resolved for a core consumer");

        var evaluator = BatchResonanceEvaluator.defaultEvaluator();
        require(evaluator.isAvailable(), "the portable resonance evaluator must be available");
        require(evaluator instanceof AdaptiveBatchResonanceEvaluator adaptive && !adaptive.isVectorAvailable(),
                "without the Vector module the adaptive evaluator must fall back to scalar");

        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(new CognitiveBudget(100, 100, 100))
                .build();
        var cycle = runtime.execute(List.of(new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0))));
        require(cycle.termination() == CognitiveCycleTermination.COMPLETED, "cycle must complete: " + cycle.termination());

        var request = request();
        var catalog = catalog();
        var policy = LexicographicRoutingPolicy.reference();
        var levelA = policy.decide(request, catalog,
                RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion()));
        require(levelA instanceof RoutingDecision.Selected s && s.route().routeId().equals("route-a"),
                "Level A routing must select route-a: " + levelA);

        var definition = AdaptiveStateDefinition.reference();
        var store = new RoutingStateStore(definition, new StatePartition("scope", "eval", "1"));
        store.bind(List.of(new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-a", 1))));
        var adaptivePolicy = new LexicographicRoutingPolicy(definition.state(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(), Optional.empty());
        var adaptive = adaptivePolicy.decide(request, catalog, store.snapshot(adaptivePolicy, catalog));
        require(adaptive instanceof RoutingDecision.Selected s
                        && s.provenance().state().mappingVersion().equals("adaptive-routing-state/1"),
                "adaptive snapshot routing must select with the reference mapping: " + adaptive);

        store.advanceCutoff(0);
        var checkpoint = store.checkpoint(adaptivePolicy);
        require(CheckpointCodec.decode(CheckpointCodec.encode(checkpoint)) instanceof CheckpointDecodeResult.Decoded c
                        && c.checkpoint().equals(checkpoint),
                "a routing checkpoint must decode to an equal checkpoint: " + checkpoint.processedCutoff());

        var readiness = new TaskFeatureReadiness().assess(
                TaskFeatures.builder().stageKind("implement").languages(List.of("java")).build(), "implement",
                Set.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES));
        require(readiness instanceof FeatureReadinessAssessment.Assessed a && a.diagnostics().size() == 8
                        && a.missingForHostPreview().equals(List.of(FeatureDimension.CATEGORY)),
                "feature readiness must report eight rows with CATEGORY missing: " + readiness);

        var review = DecisionReview.from(levelA);
        require(review instanceof DecisionReview.Selected selected && selected.route().routeId().equals("route-a")
                        && review.notices().contains(ReviewNotice.RECHECK_BEFORE_EXECUTION),
                "a Level A review must be Selected and carry RECHECK_BEFORE_EXECUTION: " + review);

        var receipt = AdviceReceipt.of(levelA);
        require(AdviceReceipt.decode(receipt.bytes()) instanceof DecodeResult.Decoded d && d.receipt().equals(receipt)
                        && d.receipt().decision().equals(levelA),
                "an advice receipt must decode to an equal receipt: " + receipt);

        var disposition = AdviceDisposition.followed(receipt, levelA.provenance().decisionRef());
        require(disposition instanceof DispositionResult.Bound b && b.disposition() instanceof AdviceDisposition.Followed f
                        && f.refersTo(receipt),
                "a followed disposition must bind to the Level A receipt: " + disposition);

        var side = new RoutingScenario(policy, TrustedRoutingProfile.lexicographic(policy), new RoutingInput(request,
                catalog, RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion())));
        var preview = new ScenarioComparator().compare(side, side);
        require(preview instanceof ScenarioComparison.Compared c && c.differences().isEmpty()
                        && c.delta().routeChange() == RouteChange.SAME_ROUTE && c.before().equals(levelA),
                "an identical scenario preview must report no difference: " + preview);

        System.out.println("[module-consumer] " + self.getName() + " reads " + neuron.getName()
                + " (automatic=" + neuron.getDescriptor().isAutomatic() + ", location="
                + neuron.getLayer().configuration().findModule(neuron.getName())
                        .flatMap(m -> m.reference().location()).map(Object::toString).orElse("?") + ")");
        System.out.println("[module-consumer] vector available: false; cycle: " + cycle.termination()
                + "; Level A: " + ((RoutingDecision.Selected) levelA).route()
                + "; adaptive: " + ((RoutingDecision.Selected) adaptive).basis());
    }

    private static RoutingRequest request() {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
    }

    private static RouteCatalog catalog() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        return RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
    }

    /** The Automatic-Module-Name declared by the manifest of the jar that loaded {@code type}, or null. */
    private static String manifestModuleName(Class<?> type) {
        try {
            Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var file = new JarFile(jar.toFile())) {
                var manifest = file.getManifest();
                return manifest == null ? null
                        : manifest.getMainAttributes().getValue(new Attributes.Name("Automatic-Module-Name"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
