package consumer.forge;

import consumer.forge.ForgeHost.Cancellation;
import consumer.forge.ForgeHost.Task;
import consumer.forge.ForgeRoutingAdapter.Fingerprints;
import consumer.forge.ForgeRoutingAdapter.PolicyInForce;
import consumer.forge.HostGuard.Check;
import consumer.forge.HostGuard.Verdict;
import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Specification;
import consumer.forge.TaskDraft.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static consumer.forge.SampleInputs.CATALOG_VERSION;
import static consumer.forge.SampleInputs.adapter;
import static consumer.forge.Fixtures.advised;
import static consumer.forge.SampleInputs.host;
import static consumer.forge.SampleInputs.reviewedDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The staleness rule of readiness contract Section 6.1, one check per captured input. */
class HostGuardTest {

    private PolicyInForce policy;
    private Advice advice;

    @BeforeEach
    void advise() {
        policy = adapter().policyInForce();
        advice = advised(host().request(reviewedDraft(), new Task("task-1", "exec-1"), Cancellation.NONE));
    }

    private Verdict check(TaskDraft current) {
        return check(current, Optional.of(CATALOG_VERSION));
    }

    private Verdict check(TaskDraft current, Optional<String> catalogVersion) {
        return HostGuard.check(advice, current, Fingerprints.of(current, policy), catalogVersion);
    }

    private static List<Check> failed(Verdict verdict) {
        return assertInstanceOf(Verdict.Stale.class, verdict).failed();
    }

    // covers: AC-2
    @Test
    void unchangedInputsAreFresh() {
        assertInstanceOf(Verdict.Fresh.class, check(reviewedDraft()));
    }

    // covers: AC-2
    @Test
    void aWithdrawnConfirmationFailsDraftReady() {
        assertEquals(List.of(Check.DRAFT_READY), failed(check(reviewedDraft().withContext(SampleInputs.JAVA_ONLY))));
    }

    // covers: AC-2
    @Test
    void aReplacedWorkspaceFailsTheEpochCheck() {
        var replaced = reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 5, "rev-c0ffee")).confirm();

        assertEquals(List.of(Check.EPOCH), failed(check(replaced)));
    }

    // covers: AC-2
    @Test
    void aMissingWorkspaceFailsEpochAndSource() {
        var missing = new TaskDraft(Optional.empty(), reviewedDraft().specification(), reviewedDraft().scope(),
                reviewedDraft().review(), reviewedDraft().context(), TaskDraft.State.READY);

        assertEquals(List.of(Check.EPOCH, Check.SOURCE_FINGERPRINT), failed(check(missing)));
    }

    // covers: AC-2
    @Test
    void aNewSourceRevisionFailsTheSourceFingerprint() {
        var revised = reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 4, "rev-1")).confirm();

        assertEquals(List.of(Check.SOURCE_FINGERPRINT), failed(check(revised)));
    }

    // covers: AC-2
    @Test
    void aNewContextCollectionFailsTheContextFingerprint() {
        var collected = reviewedDraft().withContext(Context.none()).confirm();

        assertEquals(List.of(Check.CONTEXT_FINGERPRINT), failed(check(collected)));
    }

    // covers: AC-2
    @Test
    void aTextEditFailsTheConstraintsFingerprint() {
        var edited = reviewedDraft().withSpecification(new Specification("Other", "text")).confirm();

        assertEquals(List.of(Check.CONSTRAINTS_FINGERPRINT), failed(check(edited)));
    }

    // covers: AC-2
    @Test
    void aScopeEditFailsTheConstraintsFingerprint() {
        var widened = reviewedDraft().withScope(new Scope.WholeWorkspace()).confirm();

        assertEquals(List.of(Check.CONSTRAINTS_FINGERPRINT), failed(check(widened)));
    }

    // covers: AC-2
    @Test
    void aLaterPickFailsTheConstraintsFingerprint() {
        var picked = reviewedDraft().withReview(new Review(Optional.of("implement"), Optional.of("feature"),
                Optional.empty(), Optional.empty())).confirm();

        assertEquals(List.of(Check.CONSTRAINTS_FINGERPRINT), failed(check(picked)));
    }

    // covers: AC-2
    @Test
    void anotherPolicyInForceFailsTheConstraintsFingerprint() {
        var current = reviewedDraft();

        var otherPolicy = new PolicyInForce("other-policy", policy.policyVersion(), policy.preview());

        var verdict = HostGuard.check(advice, current, Fingerprints.of(current, otherPolicy),
                Optional.of(CATALOG_VERSION));

        assertEquals(List.of(Check.CONSTRAINTS_FINGERPRINT), failed(verdict));
    }

    // covers: AC-2
    @Test
    void aChangedCatalogVersionFailsTheCatalogCheck() {
        assertEquals(List.of(Check.CATALOG_VERSION), failed(check(reviewedDraft(), Optional.of("preview-catalog-2026-11"))));
    }

    // covers: AC-2
    @Test
    void aRemovedCatalogFailsTheCatalogCheck() {
        assertEquals(List.of(Check.CATALOG_VERSION), failed(check(reviewedDraft(), Optional.empty())));
    }

    // covers: AC-2
    @Test
    void failedChecksAreListedInSectionOrder() {
        var everything = reviewedDraft().withWorkspace(new Workspace("ws-7f3a", 9, "rev-9"))
                .withSpecification(new Specification("Other", "text")).withContext(Context.none());

        assertEquals(List.of(Check.DRAFT_READY, Check.EPOCH, Check.SOURCE_FINGERPRINT, Check.CONTEXT_FINGERPRINT,
                Check.CONSTRAINTS_FINGERPRINT, Check.CATALOG_VERSION), failed(check(everything, Optional.empty())));
    }

    // covers: AC-2
    @Test
    void staleNamesAtLeastOneCheck() {
        assertThrows(IllegalArgumentException.class, () -> new Verdict.Stale(List.of()));
    }

    // covers: AC-2
    @Test
    void nullArgumentsAreRejected() {
        var current = reviewedDraft();
        var fingerprints = Fingerprints.of(current, policy);

        assertThrows(NullPointerException.class, () -> HostGuard.check(null, current, fingerprints, Optional.empty()));
        assertThrows(NullPointerException.class, () -> HostGuard.check(advice, null, fingerprints, Optional.empty()));
        assertThrows(NullPointerException.class, () -> HostGuard.check(advice, current, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> HostGuard.check(advice, current, fingerprints, null));
    }
}
