package consumer.forge;

import consumer.forge.ForgeHost.Cancellation;
import consumer.forge.ForgeHost.CatalogConfiguration;
import consumer.forge.ForgeHost.Choice;
import consumer.forge.ForgeHost.DispositionOutcome;
import consumer.forge.ForgeHost.Integration;
import consumer.forge.ForgeHost.Refusal;
import consumer.forge.ForgeHost.Task;
import consumer.forge.HostGuard.Check;
import consumer.forge.HostGuard.Verdict;
import consumer.forge.Readiness.Prerequisite;
import consumer.forge.Readiness.Reason;
import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Toggle;
import consumer.forge.TaskDraft.Workspace;
import consumer.forge.VersionProbe.Expectation;
import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.AlternativeStanding;
import monada.neuron.routing.disposition.DispositionRejection;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static consumer.forge.SampleInputs.API_LARGE;
import static consumer.forge.SampleInputs.CATALOG_VERSION;
import static consumer.forge.SampleInputs.LOCAL_SMALL;
import static consumer.forge.SampleInputs.SCOPE;
import static consumer.forge.SampleInputs.SPECIFICATION;
import static consumer.forge.SampleInputs.WORKSPACE;
import static consumer.forge.SampleInputs.adapter;
import static consumer.forge.Fixtures.advised;
import static consumer.forge.SampleInputs.catalog;
import static consumer.forge.SampleInputs.INSTRUCTION_TOKENS;
import static consumer.forge.SampleInputs.host;
import static consumer.forge.SampleInputs.loudDraft;
import static consumer.forge.SampleInputs.reviewedDraft;
import static consumer.forge.Fixtures.selected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Forge-like host: readiness order, re request, the guard in use, dispositions and the side effect boundary. */
class ForgeHostTest {

    private static final Review FULL_PICKS = new Review(Optional.of("implement"), Optional.of("feature"),
            Optional.of(Toggle.REQUIRED), Optional.empty());

    private static TaskDraft fullyPickedDraft() {
        return reviewedDraft().withReview(FULL_PICKS).confirm();
    }

    private static ForgeHost hostWith(LexicographicRoutingPolicy policy, HardRequirements preview) {
        return new ForgeHost(adapter(policy, preview), Integration.ON, Optional.of(catalog()), VersionProbe.forgeRoutingV1());
    }

    // covers: AC-2
    @Test
    void readyDraftIsAdvisedAndNothingElseHappens() {
        var host = host();

        var advice = advised(host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));

        assertEquals(LOCAL_SMALL, selected(advice).route());
        assertEquals("attempt-1", advice.request().attemptId());
        assertEquals(1, advice.request().requestOrdinal());
        assertEquals(WORKSPACE.epoch(), advice.capturedEpoch());
        assertEquals(List.of(advice), host.history());
        assertTrue(host.dispositions().isEmpty());
        assertEquals(0, host.boundary().reachedCalls());
        assertInstanceOf(Verdict.Fresh.class, host.recheck(advice, reviewedDraft()));
    }

    // covers: AC-1
    @Test
    void twoHostsOverTheSameDraftProduceTheSameReceipt() {
        var first = advised(host().request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        var second = advised(host().request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));

        assertEquals(first.receipt().sha256(), second.receipt().sha256());
        assertEquals(first.ref(), second.ref());
    }

    // covers: AC-2
    @Test
    void ordinalsCountPerScopeAndAttemptsPerHost() {
        var host = host();
        var other = reviewedDraft().withWorkspace(new Workspace("ws-other", 1, "rev-1")).confirm();

        var a = advised(host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        var b = advised(host.request(reviewedDraft(), new Task("task-2", "exec-2"), Cancellation.NONE));
        var c = advised(host.request(other, new Task("task-3", "exec-3"), Cancellation.NONE));

        assertEquals(List.of(1L, 2L, 1L), List.of(a.request().requestOrdinal(), b.request().requestOrdinal(),
                c.request().requestOrdinal()));
        assertEquals(List.of("attempt-1", "attempt-2", "attempt-3"), List.of(a.request().attemptId(),
                b.request().attemptId(), c.request().attemptId()));
    }

    // covers: AC-2
    @Test
    void emptyDraftIsNotReadyForEverything() {
        var host = host();

        assertEquals(new Readiness.NotReady(List.of(Prerequisite.WORKSPACE, Prerequisite.SPECIFICATION,
                Prerequisite.SCOPE, Prerequisite.CONFIRMATION, Prerequisite.STAGE)),
                host.request(TaskDraft.empty(), new Task("task-1", "exec-1"), Cancellation.NONE));
        assertTrue(host.history().isEmpty());
    }

    // covers: AC-2
    @Test
    void unsetScopeIsNotReady() {
        var draft = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION)
                .withReview(SampleInputs.IMPLEMENT);

        assertEquals(new Readiness.NotReady(List.of(Prerequisite.SCOPE, Prerequisite.CONFIRMATION)),
                host().request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));
    }

    // covers: AC-2
    @Test
    void confirmedDraftWithoutAStagePickIsNotReady() {
        var draft = reviewedDraft().withReview(Review.none()).confirm();

        assertEquals(new Readiness.NotReady(List.of(Prerequisite.STAGE)), host().request(draft,
                new Task("task-1", "exec-1"), Cancellation.NONE));
    }

    // covers: AC-2
    @Test
    void withdrawnConfirmationIsNotReady() {
        var draft = reviewedDraft().withScope(new Scope.WholeWorkspace());

        assertEquals(new Readiness.NotReady(List.of(Prerequisite.CONFIRMATION)),
                host().request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));
    }

    // covers: AC-2
    @Test
    void noCatalogAndZeroRoutesAreBothNoCatalog() {
        var absent = host(Optional.empty());
        var empty = host(Optional.of(new CatalogConfiguration(CATALOG_VERSION, List.of())));

        assertEquals(new Readiness.Unavailable(Reason.NO_CATALOG), absent.request(reviewedDraft(),
                new Task("task-1", "exec-1"), Cancellation.NONE));
        assertEquals(new Readiness.Unavailable(Reason.NO_CATALOG), empty.request(reviewedDraft(),
                new Task("task-1", "exec-1"), Cancellation.NONE));
        assertTrue(absent.history().isEmpty());
        assertTrue(empty.history().isEmpty());
    }

    // covers: AC-2
    @Test
    void integrationOffNeverCalls() {
        var host = new ForgeHost(adapter(), Integration.OFF, Optional.of(catalog()), VersionProbe.forgeRoutingV1());

        assertEquals(new Readiness.Unavailable(Reason.INTEGRATION_OFF),
                host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        assertTrue(host.history().isEmpty());
    }

    // covers: AC-2
    @Test
    void cancellationIsCheckedLastBeforeTheCall() {
        var host = host();

        assertEquals(new Readiness.Unavailable(Reason.CANCELLED), host.request(reviewedDraft(),
                new Task("task-1", "exec-1"), Cancellation.REQUESTED));
        assertTrue(host.history().isEmpty());
        assertEquals(1, advised(host.request(reviewedDraft(),
                new Task("task-1", "exec-1"), Cancellation.NONE)).request().requestOrdinal());
    }

    // covers: AC-2
    @Test
    void versionMismatchIsRememberedAndLogged() {
        var host = new ForgeHost(adapter(), Integration.ON, Optional.of(catalog()), List.of(
                new Expectation(TaskFeatures.class, "SCHEMA_VERSION", "task-features/2")));

        assertEquals(new Readiness.Unavailable(Reason.VERSION_MISMATCH),
                host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        assertEquals(List.of("version probe: TaskFeatures.SCHEMA_VERSION expected task-features/2, found task-features/1"),
                host.log());
        assertTrue(host.history().isEmpty());
    }

    // covers: AC-2
    @Test
    void readinessChecksRunInTheContractOrder() {
        var futureSchema = List.of(new Expectation(TaskFeatures.class, "SCHEMA_VERSION", "task-features/2"));
        var everythingWrong = new ForgeHost(adapter(), Integration.OFF, Optional.empty(), futureSchema);
        var onWithoutCatalog = new ForgeHost(adapter(), Integration.ON, Optional.empty(), futureSchema);
        var mismatchWithCatalog = new ForgeHost(adapter(), Integration.ON, Optional.of(catalog()), futureSchema);
        var task = new Task("task-1", "exec-1");

        // Section 5.3: prerequisites, then integration off, then no catalog, then version mismatch, then cancelled.
        assertInstanceOf(Readiness.NotReady.class,
                everythingWrong.request(TaskDraft.empty(), task, Cancellation.REQUESTED));
        assertEquals(new Readiness.Unavailable(Reason.INTEGRATION_OFF),
                everythingWrong.request(reviewedDraft(), task, Cancellation.REQUESTED));
        assertEquals(new Readiness.Unavailable(Reason.NO_CATALOG),
                onWithoutCatalog.request(reviewedDraft(), task, Cancellation.REQUESTED));
        assertEquals(new Readiness.Unavailable(Reason.VERSION_MISMATCH),
                mismatchWithCatalog.request(reviewedDraft(), task, Cancellation.REQUESTED));
        assertEquals(new Readiness.Unavailable(Reason.CANCELLED),
                host().request(reviewedDraft(), task, Cancellation.REQUESTED));
    }

    // covers: AC-2
    @Test
    void scopeEditMakesAdviceStaleAndTheReRequestKeepsItsIds() {
        var host = host();
        var advice = advised(host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        var edited = reviewedDraft().withScope(new Scope.WholeWorkspace());

        assertEquals(List.of(Check.DRAFT_READY, Check.CONSTRAINTS_FINGERPRINT),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, edited)).failed());
        assertEquals(new DispositionOutcome.Refused(Refusal.ADVICE_STALE), host.dispose(advice, edited, new Choice.Follow()));

        var reconfirmed = edited.confirm();
        assertEquals(List.of(Check.CONSTRAINTS_FINGERPRINT),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, reconfirmed)).failed());

        var again = advised(host.reRequest(advice, reconfirmed, Cancellation.NONE));

        assertEquals("task-1", again.request().taskId());
        assertEquals("exec-1", again.request().executionId());
        assertEquals("attempt-2", again.request().attemptId());
        assertEquals(2, again.request().requestOrdinal());
        assertNotEquals(advice.ref(), again.ref());
        assertInstanceOf(Verdict.Fresh.class, host.recheck(again, reconfirmed));
        assertEquals(List.of(advice, again), host.history());
        assertTrue(host.log().contains("discarded stale " + advice.ref()));
    }

    // covers: AC-2
    @Test
    void reRequestedAdviceNeverReceivesADisposition() {
        var host = host();
        var advice = advised(host.request(fullyPickedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
        advised(host.reRequest(advice, fullyPickedDraft(), Cancellation.NONE));

        assertEquals(new DispositionOutcome.Refused(Refusal.ADVICE_RE_REQUESTED),
                host.dispose(advice, fullyPickedDraft(), new Choice.Defer()));
        assertTrue(host.dispositions().isEmpty());
    }

    // covers: AC-2
    @Test
    void cancelledReRequestStillDiscardsTheStaleAdvice() {
        var host = host();
        var advice = advised(host.request(fullyPickedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));

        assertEquals(new Readiness.Unavailable(Reason.CANCELLED), host.reRequest(advice, fullyPickedDraft(), Cancellation.REQUESTED));
        assertEquals(new DispositionOutcome.Refused(Refusal.ADVICE_RE_REQUESTED),
                host.dispose(advice, fullyPickedDraft(), new Choice.Decline()));
    }

    // covers: AC-2
    @Test
    void epochSourceContextAndCatalogChangesEachFailOneCheck() {
        var host = host();
        var advice = advised(host.request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));

        var bumped = reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 5, "rev-c0ffee")).confirm();
        assertEquals(List.of(Check.EPOCH), assertInstanceOf(Verdict.Stale.class, host.recheck(advice, bumped)).failed());

        var revised = reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 4, "rev-1")).confirm();
        assertEquals(List.of(Check.SOURCE_FINGERPRINT),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, revised)).failed());

        var collected = reviewedDraft().withContext(Context.none()).confirm();
        assertEquals(List.of(Check.CONTEXT_FINGERPRINT),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, collected)).failed());

        host.replaceCatalog(Optional.of(new CatalogConfiguration("preview-catalog-2026-11", catalog().routes())));
        assertEquals(List.of(Check.CATALOG_VERSION),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, reviewedDraft())).failed());

        host.replaceCatalog(Optional.empty());
        assertEquals(List.of(Check.CATALOG_VERSION),
                assertInstanceOf(Verdict.Stale.class, host.recheck(advice, reviewedDraft())).failed());
    }

    // covers: AC-2
    @Test
    void followAndAlternativeWaitForTheRequiredFeaturesWhileDeferIsRecorded() {
        var host = host();
        var draft = reviewedDraft().withContext(Context.none()).confirm();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        assertEquals(List.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES), advice.missingFeatures());
        assertEquals(new DispositionOutcome.Refused(Refusal.FEATURES_UNCONFIRMED), host.dispose(advice, draft, new Choice.Follow()));
        assertEquals(new DispositionOutcome.Refused(Refusal.FEATURES_UNCONFIRMED),
                host.dispose(advice, draft, new Choice.Alternative(API_LARGE)));
        assertInstanceOf(AdviceDisposition.Deferred.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Defer())).disposition());
        assertInstanceOf(AdviceDisposition.Declined.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Decline())).disposition());
    }

    // covers: AC-3
    @Test
    void selectedAdviceTakesEveryDispositionWithoutASideEffect() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        var followed = assertInstanceOf(AdviceDisposition.Followed.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Follow())).disposition());
        assertEquals(Optional.of(LOCAL_SMALL), followed.advice().advisedRoute());

        var proposed = assertInstanceOf(AdviceDisposition.AlternativeProposed.class,
                assertInstanceOf(DispositionOutcome.Recorded.class,
                        host.dispose(advice, draft, new Choice.Alternative(API_LARGE))).disposition());
        assertEquals(API_LARGE, proposed.route());
        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, proposed.standing());

        assertEquals(new DispositionOutcome.Rejected(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE),
                host.dispose(advice, draft, new Choice.Alternative(LOCAL_SMALL)));
        assertInstanceOf(AdviceDisposition.Deferred.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Defer())).disposition());
        assertInstanceOf(AdviceDisposition.Declined.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Decline())).disposition());
        assertEquals(0, host.boundary().reachedCalls());
    }

    // covers: AC-3
    @Test
    void laterDispositionReplacesTheEarlierOneForTheSameAdvice() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        host.dispose(advice, draft, new Choice.Follow());
        host.dispose(advice, draft, new Choice.Decline());

        assertEquals(1, host.dispositions().size());
        assertInstanceOf(AdviceDisposition.Declined.class, host.dispositions().get(advice.ref()));
    }

    // covers: AC-3
    @Test
    void rejectedDispositionIsNotRecorded() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        host.dispose(advice, draft, new Choice.Alternative(LOCAL_SMALL));

        assertTrue(host.dispositions().isEmpty());
    }

    // covers: AC-3
    @Test
    void abstainedAdviceCannotBeFollowedButCanBeRedirectedOrDeferred() {
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
        var longContext = new HardRequirements(List.of("long-context"), List.of(), List.of("local", "remote"),
                List.of("review"), 0L);
        var host = hostWith(capped, longContext);
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        var abstain = assertInstanceOf(RoutingDecision.Abstain.class, advice.decision());
        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, abstain.reason());
        assertEquals(List.of(API_LARGE), abstain.eligible());
        assertEquals(new DispositionOutcome.Rejected(DispositionRejection.ADVICE_NOT_SELECTED),
                host.dispose(advice, draft, new Choice.Follow()));
        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, standing(host.dispose(advice, draft,
                new Choice.Alternative(API_LARGE))));
        assertEquals(AlternativeStanding.EXCLUDED_IN_DECISION, standing(host.dispose(advice, draft,
                new Choice.Alternative(LOCAL_SMALL))));
        assertInstanceOf(AdviceDisposition.Deferred.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Defer())).disposition());
        assertEquals(0, host.boundary().reachedCalls());
    }

    // covers: AC-3
    @Test
    void noEligibleRouteTakesAHostClaimOrADecline() {
        var gpu = new HardRequirements(List.of("gpu"), List.of(), List.of("local", "remote"), List.of("review"), 0L);
        var host = hostWith(LexicographicRoutingPolicy.reference(), gpu);
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, advice.decision());
        assertEquals(AlternativeStanding.HOST_CLAIM, standing(host.dispose(advice, draft,
                new Choice.Alternative(new RouteKey("offline-llm", 1)))));
        assertInstanceOf(AdviceDisposition.Declined.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, host.dispose(advice, draft, new Choice.Decline())).disposition());
        assertEquals(0, host.boundary().reachedCalls());
    }

    // covers: AC-4
    @Test
    void instructionLikeTextAndScopeEntriesNeverCrossTheBoundary() {
        var plain = advised(host().request(reviewedDraft(), new Task("task-10", "exec-10"), Cancellation.NONE));
        var host = host();
        var advice = advised(host.request(loudDraft(), new Task("task-10", "exec-10"), Cancellation.NONE));

        assertEquals(selected(plain).route(), selected(advice).route());
        assertEquals(selected(plain).candidates(), selected(advice).candidates());
        assertEquals(plain.request().features(), advice.request().features());
        assertNotEquals(plain.request().constraintsFingerprint(), advice.request().constraintsFingerprint());
        String visible = String.join("\n", String.valueOf(advice.request()), String.valueOf(advice.decision()),
                String.valueOf(advice.review()), String.valueOf(advice.assessment()),
                new String(advice.receipt().bytes(), StandardCharsets.ISO_8859_1), String.join("\n", host.log()));
        for (String token : INSTRUCTION_TOKENS) {
            assertFalse(visible.contains(token), token);
        }
        assertFalse(advice.request().scopeId().contains("/"));
    }

    // covers: AC-2
    @Test
    void logRecordsEveryAdviceAndDisposition() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));
        host.dispose(advice, draft, new Choice.Follow());

        assertEquals(List.of("advised " + advice.ref() + " -> Selected", "disposed " + advice.ref() + " -> Followed"),
                host.log());
    }

    // covers: AC-2
    @Test
    void historyAndDispositionsAreCopies() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));
        host.dispose(advice, draft, new Choice.Follow());

        assertThrows(UnsupportedOperationException.class, () -> host.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> host.dispositions().clear());
        assertThrows(UnsupportedOperationException.class, () -> host.log().clear());
    }

    // covers: AC-2
    @Test
    void nullArgumentsAreRejected() {
        var host = host();
        var draft = fullyPickedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));

        assertThrows(NullPointerException.class, () -> host.request(null,
                new Task("task-1", "exec-1"), Cancellation.NONE));
        assertThrows(NullPointerException.class, () -> host.request(draft, null, Cancellation.NONE));
        assertThrows(NullPointerException.class, () -> host.request(draft, new Task("task-1", "exec-1"), null));
        assertThrows(NullPointerException.class, () -> new Task(null, "exec-1"));
        assertThrows(NullPointerException.class, () -> new Task("task-1", null));
        assertThrows(NullPointerException.class, () -> host.dispose(null, draft, new Choice.Follow()));
        assertThrows(NullPointerException.class, () -> host.dispose(advice, null, new Choice.Follow()));
        assertThrows(NullPointerException.class, () -> host.dispose(advice, draft, null));
        assertThrows(NullPointerException.class, () -> host.replaceCatalog(null));
        assertThrows(NullPointerException.class, () -> new Choice.Alternative(null));
    }

    private static AlternativeStanding standing(DispositionOutcome disposal) {
        return assertInstanceOf(AdviceDisposition.AlternativeProposed.class,
                assertInstanceOf(DispositionOutcome.Recorded.class, disposal).disposition()).standing();
    }
}
