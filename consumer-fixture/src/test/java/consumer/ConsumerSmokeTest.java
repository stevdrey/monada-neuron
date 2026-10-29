package consumer;

import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.resonance.AdaptiveBatchResonanceEvaluator;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.routing.comparison.RouteChange;
import monada.neuron.routing.comparison.ScenarioComparison;
import monada.neuron.routing.comparison.ScenarioDifference;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import monada.neuron.routing.review.DecisionReview;
import monada.neuron.routing.review.ReviewNotice;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerSmokeTest {

    @Test
    void embeddedRuntimeExecutesACycle() {
        var result = ConsumerSmoke.runOneCycle();

        assertEquals(CognitiveCycleTermination.COMPLETED, result.termination());
        assertEquals(1, result.outputSignals().size());
    }

    @Test
    void routingDecisionIsAvailableThroughThePublishedArtifact() {
        var decision = assertInstanceOf(RoutingDecision.Selected.class, ConsumerSmoke.routeOneStage());

        assertEquals("route-a", decision.route().routeId());
    }

    @Test
    void trustedRoutingProfileIsAvailableThroughThePublishedArtifact() {
        var decision = assertInstanceOf(RoutingDecision.Selected.class, ConsumerSmoke.routeOneStageVerified());

        assertEquals("route-a", decision.route().routeId());
    }

    @Test
    void adaptiveRoutingStateIsAvailableThroughThePublishedArtifact() {
        var decision = assertInstanceOf(RoutingDecision.Selected.class, ConsumerSmoke.routeFromAdaptiveState());
        assertEquals("adaptive-routing-state/1", decision.provenance().state().mappingVersion());
    }

    // covers: AC-9
    @Test
    void featureReadinessDiagnosticsAreAvailableThroughThePublishedArtifact() {
        var assessed = assertInstanceOf(FeatureReadinessAssessment.Assessed.class,
                ConsumerSmoke.assessFeatureReadiness());

        assertEquals(8, assessed.diagnostics().size());
        assertEquals(List.of(FeatureDimension.CATEGORY), assessed.missingForHostPreview());
    }

    // covers: AC-11
    @Test
    void decisionReviewIsAvailableThroughThePublishedArtifact() {
        var review = assertInstanceOf(DecisionReview.Selected.class, ConsumerSmoke.reviewOneStage());

        assertEquals("route-a", review.route().routeId());
        assertTrue(review.notices().contains(ReviewNotice.RECHECK_BEFORE_EXECUTION), review.notices().toString());
    }

    // covers: AC-10
    @Test
    void adviceReceiptsAreAvailableThroughThePublishedArtifact() {
        var decoded = assertInstanceOf(DecodeResult.Decoded.class, ConsumerSmoke.receiptRoundTrip());

        assertEquals(AdviceReceipt.of(ConsumerSmoke.routeOneStage()), decoded.receipt());
        assertInstanceOf(RoutingDecision.Selected.class, decoded.receipt().decision());
    }

    @Test
    void checkpointCodecIsAvailableThroughThePublishedArtifact() {
        var decoded = assertInstanceOf(CheckpointDecodeResult.Decoded.class, ConsumerSmoke.checkpointRoundTrip());

        assertEquals(1, decoded.checkpoint().preferenceSnapshot().size());
        assertEquals("route-a", decoded.checkpoint().preferenceSnapshot().getFirst().key().route().routeId());
    }

    // covers: AC-9
    @Test
    void adviceDispositionsAreAvailableThroughThePublishedArtifact() {
        var bound = assertInstanceOf(DispositionResult.Bound.class, ConsumerSmoke.dispositionFollowed());

        var followed = assertInstanceOf(AdviceDisposition.Followed.class, bound.disposition());
        assertTrue(followed.refersTo(AdviceReceipt.of(ConsumerSmoke.routeOneStage())));
        assertEquals("route-a", followed.advice().advisedRoute().orElseThrow().routeId());
    }

    @Test
    void scenarioComparisonsAreAvailableThroughThePublishedArtifact() {
        var compared = assertInstanceOf(ScenarioComparison.Compared.class, ConsumerSmoke.compareUnavailableRoute());

        assertEquals(List.of(ScenarioDifference.CATALOG), compared.differences());
        assertEquals(RouteChange.NO_LONGER_SELECTED, compared.delta().routeChange());
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, compared.after());
    }

    @Test
    void portablePathWorksWithoutTheVectorModule() {
        assertTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty(),
                "the fixture must run without jdk.incubator.vector");

        var evaluator = BatchResonanceEvaluator.defaultEvaluator();
        var adaptive = assertInstanceOf(AdaptiveBatchResonanceEvaluator.class, evaluator);

        assertTrue(evaluator.isAvailable());
        assertFalse(adaptive.isVectorAvailable());
    }

    @Test
    void evaluationAndBenchmarkCodeAreNotOnTheConsumerClasspath() {
        var fileNames = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).getFileName().toString())
                .toList();

        assertTrue(fileNames.stream().anyMatch(name -> name.startsWith("monada-neuron-0.1.0")),
                "the Neuron artifact must be on the consumer classpath: " + fileNames);
        for (String name : fileNames) {
            assertFalse(name.startsWith("monada-neuron-evaluation"), name);
            assertFalse(name.startsWith("jmh-"), name);
        }
    }
}
