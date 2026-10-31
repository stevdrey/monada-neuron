package consumer.forge;

import consumer.forge.ForgeRoutingAdapter.Fingerprints;
import consumer.forge.ForgeRoutingAdapter.Ids;
import consumer.forge.ForgeRoutingAdapter.PolicyInForce;
import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Specification;
import consumer.forge.TaskDraft.Toggle;
import consumer.forge.TaskDraft.Workspace;
import monada.neuron.routing.Basis;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.features.Requirement;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import monada.neuron.routing.review.DecisionReview;
import monada.neuron.routing.review.ReviewNotice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static consumer.forge.SampleInputs.API_LARGE;
import static consumer.forge.SampleInputs.LOCAL_SMALL;
import static consumer.forge.SampleInputs.PREVIEW;
import static consumer.forge.SampleInputs.SCOPE;
import static consumer.forge.SampleInputs.SPECIFICATION;
import static consumer.forge.SampleInputs.WORKSPACE;
import static consumer.forge.SampleInputs.adapter;
import static consumer.forge.SampleInputs.catalog;
import static consumer.forge.SampleInputs.reviewedDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transformation boundary: draft metadata to features, fingerprints, request and advice. */
class ForgeRoutingAdapterTest {

    private static final Ids IDS = new Ids("task-1", "exec-1", "attempt-1", ForgeHost.STAGE_ID);

    // covers: AC-2
    @Test
    void picksBecomeKnownAndAbsentPicksStayUnknown() {
        var draft = reviewedDraft().withReview(new Review(Optional.of("plan"), Optional.of("feature"),
                Optional.of(Toggle.REQUIRED), Optional.empty()));

        var features = adapter().features(draft);

        assertEquals(ForgeRoutingAdapter.SCHEMA_VERSION, features.schemaVersion());
        assertEquals(Feature.known("plan"), features.stageKind());
        assertEquals(Feature.known("feature"), features.category());
        assertEquals(Feature.known(Requirement.REQUIRED), features.tests());
        assertInstanceOf(Feature.Unknown.class, features.security());
    }

    // covers: AC-2
    @Test
    void anEmptyContextLeavesEveryObservationUnknown() {
        var draft = reviewedDraft().withContext(Context.none());

        var features = adapter().features(draft);

        assertInstanceOf(Feature.Unknown.class, features.languages());
        assertInstanceOf(Feature.Unknown.class, features.domains());
        assertInstanceOf(Feature.Unknown.class, features.changeSize());
        assertInstanceOf(Feature.Unknown.class, features.contextSize());
    }

    // covers: AC-2
    @Test
    void validObservationsBecomeKnown() {
        var draft = reviewedDraft().withContext(new Context(Optional.of(List.of("java", "kotlin")),
                Optional.of(List.of("storage")), OptionalLong.of(120), OptionalLong.of(0)));

        var features = adapter().features(draft);

        assertEquals(Feature.known(List.of("java", "kotlin")), features.languages());
        assertEquals(Feature.known(List.of("storage")), features.domains());
        assertEquals(Feature.known(120L), features.changeSize());
        assertEquals(Feature.known(0L), features.contextSize());
    }

    // covers: AC-2
    @Test
    void tooManyTagsAreUnknownNotTruncated() {
        var nine = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i");
        assertTrue(nine.size() > TaskFeatures.MAX_TAGS);
        var draft = reviewedDraft().withContext(new Context(Optional.of(nine), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty()));

        assertInstanceOf(Feature.Unknown.class, adapter().features(draft).languages());
    }

    // covers: AC-2
    @Test
    void exactlyMaxTagsIsStillKnown() {
        var eight = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        var draft = reviewedDraft().withContext(new Context(Optional.of(eight), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty()));

        assertEquals(Feature.known(eight), adapter().features(draft).languages());
    }

    // covers: AC-2
    @Test
    void repeatedAndMalformedTagsAreUnknown() {
        var adapter = adapter();

        for (List<String> tags : List.of(List.of("java", "java"), List.of(""), List.of(" java"), List.of("ja\tva"),
                List.of("x".repeat(129)))) {
            var draft = reviewedDraft().withContext(new Context(Optional.of(tags), Optional.of(tags),
                    OptionalLong.empty(), OptionalLong.empty()));

            var features = adapter.features(draft);

            assertInstanceOf(Feature.Unknown.class, features.languages(), "languages " + tags);
            assertInstanceOf(Feature.Unknown.class, features.domains(), "domains " + tags);
        }
    }

    // covers: AC-2
    @Test
    void negativeSizesAreUnknown() {
        var draft = reviewedDraft().withContext(new Context(Optional.empty(), Optional.empty(),
                OptionalLong.of(-1), OptionalLong.of(-7)));

        var features = adapter().features(draft);

        assertInstanceOf(Feature.Unknown.class, features.changeSize());
        assertInstanceOf(Feature.Unknown.class, features.contextSize());
    }

    // covers: AC-2
    @Test
    void assessmentNamesTheRequiredDimensionsTheDraftDidNotSupply() {
        var adapter = adapter();
        var features = adapter.features(reviewedDraft().withContext(Context.none()));

        var assessment = assertInstanceOf(FeatureReadinessAssessment.Assessed.class,
                adapter.assess(features, "implement"));

        assertEquals(List.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES),
                assessment.missingForHostPreview());
    }

    // covers: AC-1
    @Test
    void fingerprintsAreDeterministicAndSixtyFourHexCharacters() {
        var policy = adapter().policyInForce();

        var first = Fingerprints.of(reviewedDraft(), policy);
        var second = Fingerprints.of(reviewedDraft(), policy);

        assertEquals(first, second);
        assertEquals(WORKSPACE.sourceRevision(), first.source());
        assertTrue(first.context().matches("[0-9a-f]{64}"), first.context());
        assertTrue(first.constraints().matches("[0-9a-f]{64}"), first.constraints());
    }

    // covers: AC-2
    @Test
    void eachInputChangesExactlyItsOwnFingerprint() {
        var policy = adapter().policyInForce();
        var base = Fingerprints.of(reviewedDraft(), policy);

        var newRevision = Fingerprints.of(reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 4, "rev-1")), policy);
        assertNotEquals(base.source(), newRevision.source());
        assertEquals(base.context(), newRevision.context());
        assertEquals(base.constraints(), newRevision.constraints());

        var newContext = Fingerprints.of(reviewedDraft().withContext(Context.none()), policy);
        assertEquals(base.source(), newContext.source());
        assertNotEquals(base.context(), newContext.context());
        assertEquals(base.constraints(), newContext.constraints());

        var newText = Fingerprints.of(reviewedDraft().withSpecification(new Specification("Other", "text")), policy);
        assertEquals(base.source(), newText.source());
        assertEquals(base.context(), newText.context());
        assertNotEquals(base.constraints(), newText.constraints());
    }

    // covers: AC-2
    @Test
    void scopeReviewPicksAndPolicyAreCoveredByTheConstraintsFingerprint() {
        var policy = adapter().policyInForce();
        var base = Fingerprints.of(reviewedDraft(), policy).constraints();

        assertNotEquals(base, Fingerprints.of(reviewedDraft().withScope(new Scope.WholeWorkspace()), policy).constraints());
        assertNotEquals(base, Fingerprints.of(reviewedDraft().withScope(
                new Scope.Directories(List.of("src/main/java", "src/test/java"))), policy).constraints());
        assertNotEquals(base, Fingerprints.of(reviewedDraft().withReview(new Review(Optional.of("implement"),
                Optional.of("feature"), Optional.empty(), Optional.empty())), policy).constraints());
        assertNotEquals(base, Fingerprints.of(reviewedDraft(),
                new PolicyInForce("other-policy", policy.policyVersion(), policy.preview())).constraints());
    }

    // covers: AC-2
    @Test
    void aListEntryHoldingTheSeparatorDigestsDifferentlyFromTwoEntries() {
        var policy = adapter().policyInForce();

        var oneEntry = Fingerprints.of(reviewedDraft().withScope(new Scope.Directories(List.of("a, b"))), policy);
        var twoEntries = Fingerprints.of(reviewedDraft().withScope(new Scope.Directories(List.of("a", "b"))), policy);
        assertNotEquals(oneEntry.constraints(), twoEntries.constraints());

        var oneTag = Fingerprints.of(reviewedDraft().withContext(new Context(Optional.of(List.of("a, b")),
                Optional.empty(), OptionalLong.empty(), OptionalLong.empty())), policy);
        var twoTags = Fingerprints.of(reviewedDraft().withContext(new Context(Optional.of(List.of("a", "b")),
                Optional.empty(), OptionalLong.empty(), OptionalLong.empty())), policy);
        assertNotEquals(oneTag.context(), twoTags.context());
    }

    // covers: AC-2
    @Test
    void textMovedBetweenTitleAndDescriptionDigestsDifferently() {
        var policy = adapter().policyInForce();

        var split = Fingerprints.of(reviewedDraft().withSpecification(new Specification("a", "b")), policy);
        var joined = Fingerprints.of(reviewedDraft().withSpecification(new Specification("a\nb", "")), policy);

        assertNotEquals(split.constraints(), joined.constraints());
    }

    // covers: AC-2
    @Test
    void anAbsentListAndAnEmptyListDigestDifferently() {
        var policy = adapter().policyInForce();

        var absent = Fingerprints.of(reviewedDraft().withContext(Context.none()), policy);
        var empty = Fingerprints.of(reviewedDraft().withContext(new Context(Optional.of(List.of()),
                Optional.of(List.of()), OptionalLong.empty(), OptionalLong.empty())), policy);

        assertNotEquals(absent.context(), empty.context());
    }

    // covers: AC-2
    @Test
    void aMissingWorkspaceGivesAnEmptySourceFingerprint() {
        var draft = TaskDraft.empty().withSpecification(SPECIFICATION).withScope(SCOPE);

        assertEquals("", Fingerprints.of(draft, adapter().policyInForce()).source());
    }

    // covers: AC-4
    @Test
    void requestCarriesIdsFingerprintsAndFixedPreviewValuesOnly() {
        var adapter = adapter();
        var draft = reviewedDraft();
        var features = adapter.features(draft);
        var fingerprints = Fingerprints.of(draft, adapter.policyInForce());

        var request = adapter.request(draft, IDS, 3, fingerprints, features, "implement");

        assertEquals(ForgeRoutingAdapter.CONTRACT_VERSION, request.contractVersion());
        assertEquals(WORKSPACE.scopeId(), request.scopeId());
        assertEquals("task-1", request.taskId());
        assertEquals("exec-1", request.executionId());
        assertEquals("attempt-1", request.attemptId());
        assertEquals(ForgeHost.STAGE_ID, request.stageId());
        assertEquals("implement", request.stageKind());
        assertEquals(3, request.requestOrdinal());
        assertEquals(fingerprints.source(), request.sourceFingerprint());
        assertEquals(fingerprints.context(), request.contextFingerprint());
        assertEquals(fingerprints.constraints(), request.constraintsFingerprint());
        assertEquals(ForgeRoutingAdapter.EVALUATION_POLICY_ID, request.evaluationPolicyId());
        assertEquals(ForgeRoutingAdapter.EVALUATION_POLICY_VERSION, request.evaluationPolicyVersion());
        assertEquals(features, request.features());
        assertEquals(PREVIEW, request.requirements());
        assertFalse(request.overflowPermitted());
        assertEquals(0, request.cutoff());
    }

    // covers: AC-4
    @Test
    void requestNeverCarriesTaskTextOrScopeEntries() {
        var adapter = adapter();
        var draft = reviewedDraft();
        var request = adapter.request(draft, IDS, 1, Fingerprints.of(draft, adapter.policyInForce()),
                adapter.features(draft), "implement");

        String visible = request.toString();

        assertFalse(visible.contains(SPECIFICATION.title()));
        assertFalse(visible.contains(SPECIFICATION.description()));
        assertFalse(visible.contains("src/main/java"));
    }

    // covers: AC-2
    @Test
    void requestWithoutAWorkspaceIsAProgrammingError() {
        var adapter = adapter();
        var draft = TaskDraft.empty().withSpecification(SPECIFICATION).withScope(SCOPE);

        assertThrows(IllegalStateException.class, () -> adapter.request(draft, IDS, 1,
                Fingerprints.of(draft, adapter.policyInForce()), adapter.features(draft), "implement"));
    }

    // covers: AC-2
    @Test
    void adviseSelectsByHostPriorityAndProjectsReviewAndReceipt() {
        var adapter = adapter();
        var draft = reviewedDraft();
        var features = adapter.features(draft);
        var assessment = assertInstanceOf(FeatureReadinessAssessment.Assessed.class,
                adapter.assess(features, "implement"));
        var request = adapter.request(draft, IDS, 1, Fingerprints.of(draft, adapter.policyInForce()), features,
                "implement");

        var advice = adapter.advise(request, catalog().catalog().orElseThrow(), assessment, WORKSPACE.epoch());

        var selected = Fixtures.selected(advice);
        assertEquals(LOCAL_SMALL, selected.route());
        assertEquals("review", selected.executionMode());
        assertEquals(Basis.HOST_PRIORITY, selected.basis());
        assertEquals(List.of(LOCAL_SMALL, API_LARGE), selected.candidates().stream().map(RankedCandidate::key).toList());
        assertEquals(request, advice.request());
        assertEquals(WORKSPACE.epoch(), advice.capturedEpoch());
        assertEquals(List.of(FeatureDimension.CATEGORY), advice.missingFeatures());
        var review = assertInstanceOf(DecisionReview.Selected.class, advice.review());
        assertTrue(review.notices().contains(ReviewNotice.RECHECK_BEFORE_EXECUTION));
        var decoded = assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(advice.receipt().bytes()));
        assertEquals(advice.decision(), decoded.receipt().decision());
    }

    // covers: AC-1
    @Test
    void policyInForceNamesThePolicyAndThePreview() {
        var reference = LexicographicRoutingPolicy.reference();

        assertEquals(new PolicyInForce(reference.policyId(), reference.policyVersion(), PREVIEW),
                adapter(reference, PREVIEW).policyInForce());
    }

    // covers: AC-1
    @Test
    void literalsMatchTheContractTheProbeChecks() {
        assertEquals("forge-routing/1", ForgeRoutingAdapter.CONTRACT_VERSION);
        assertEquals("task-features/1", ForgeRoutingAdapter.SCHEMA_VERSION);
        assertEquals(List.of("plan", "implement", "review"), ForgeRoutingAdapter.STAGE_KINDS);
    }
}
