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
import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Specification;
import consumer.forge.TaskDraft.Workspace;
import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.AlternativeStanding;
import monada.neuron.routing.disposition.DispositionRejection;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import monada.neuron.routing.review.DecisionReview;
import monada.neuron.routing.review.ReviewNotice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.jar.Manifest;

import static consumer.forge.SampleInputs.API_LARGE;
import static consumer.forge.SampleInputs.CATALOG_VERSION;
import static consumer.forge.SampleInputs.IMPLEMENT;
import static consumer.forge.SampleInputs.INSTRUCTION_TOKENS;
import static consumer.forge.SampleInputs.LOCAL_SMALL;
import static consumer.forge.SampleInputs.PREVIEW;
import static consumer.forge.SampleInputs.SCOPE;
import static consumer.forge.SampleInputs.SPECIFICATION;
import static consumer.forge.SampleInputs.WORKSPACE;
import static consumer.forge.SampleInputs.adapter;
import static consumer.forge.SampleInputs.catalog;
import static consumer.forge.SampleInputs.entry;
import static consumer.forge.SampleInputs.host;
import static consumer.forge.SampleInputs.loudDraft;
import static consumer.forge.SampleInputs.reviewedDraft;

/**
 * Runs the Forge-like review to disposition flow and its negative cases against the published core artifact. Every
 * check throws on failure, so the Gradle {@code run} task fails; the argument says whether the host must be a named
 * module ({@code module}) or the unnamed module ({@code classpath}). The flow mirrors the examples of the readiness
 * contract, Section 10: the host starts from reviewed task metadata, never from a path or the task text, obtains
 * unverified preview advice, and ends at a disposition. Nothing executes and no path is read.
 */
public final class ForgeHostMain {

    private ForgeHostMain() {
    }

    public static void main(String[] args) {
        String mode = args.length == 1 ? args[0] : "";
        checkModuleMode(mode);
        versionProbe();
        var first = reviewedIntake();
        var second = reviewedIntake();
        require(first.receipt().sha256().equals(second.receipt().sha256()),
                "two hosts over the same draft must produce the same receipt");
        unknownFeaturesStayUnknown();
        unsetScopeIsNotReady();
        scopeEditInvalidatesReview();
        otherInputsInvalidateToo();
        hostDoesNotCallWithoutCatalogIntegrationOrWhenCancelled();
        dispositionsHaveNoSideEffect();
        taskTextAndPathsNeverEnterNeuron();
        var selected = (RoutingDecision.Selected) first.decision();
        System.out.println("[forge-host:" + mode + "] all checks passed; preview advice " + selected.route()
                + " (" + selected.basis() + "), nothing executed, no path read");
    }

    private static void checkModuleMode(String mode) {
        Module self = ForgeHostMain.class.getModule();
        Module core = RoutingDecision.class.getModule();
        switch (mode) {
            case "module" -> {
                require(self.isNamed() && self.getName().equals("monada.neuron.consumer.forge"),
                        "the module host must be named, was " + self);
                require("monada.neuron".equals(core.getName()) && core.getDescriptor().isAutomatic(),
                        "Neuron must resolve as the automatic module monada.neuron, was " + core);
                System.out.println("[forge-host:module] monada.neuron automatic=" + core.getDescriptor().isAutomatic()
                        + " location=" + core.getLayer().configuration().findModule("monada.neuron")
                        .flatMap(m -> m.reference().location()).orElseThrow());
                for (String absent : List.of("monada.neuron.resonance.adapter", "com.monada.api", "com.monada.core",
                        "jdk.incubator.vector")) {
                    require(ModuleLayer.boot().findModule(absent).isEmpty(), absent + " must not be resolved");
                }
            }
            case "classpath" -> {
                require(!self.isNamed() && !core.isNamed(), "the classpath host and the core must be in the unnamed module");
                requireManifestName("monada.neuron");
            }
            default -> throw new IllegalArgumentException("expected 'module' or 'classpath', got '" + mode + "'");
        }
        // Core-only in both modes: the adapter, the Store and any provider are absent, not merely unused.
        for (String absent : List.of("monada.neuron.resonance.adapter.experience.RoutingEnvelope",
                "com.monada.api.execution.ExecutionMemory")) {
            try {
                Class.forName(absent);
                throw new IllegalStateException(absent + " must not be on the host's path");
            } catch (ClassNotFoundException expected) {
                // the core artifact is alone
            }
        }
    }

    /** Requires some manifest on the class path to declare {@code expected} as its Automatic-Module-Name. */
    private static void requireManifestName(String expected) {
        try {
            var manifests = ForgeHostMain.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (var stream = manifests.nextElement().openStream()) {
                    String name = new Manifest(stream).getMainAttributes().getValue("Automatic-Module-Name");
                    if (expected.equals(name)) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalStateException("no jar on the class path declares Automatic-Module-Name " + expected);
    }

    /** Section 5.2: the five constants read through reflection match this host's literals; a wrong literal is caught. */
    private static void versionProbe() {
        require(VersionProbe.mismatch(VersionProbe.forgeRoutingV1()).isEmpty(), "the published core must match forge-routing/1");
        var future = VersionProbe.mismatch(List.of(
                new VersionProbe.Expectation(TaskFeatures.class, "SCHEMA_VERSION",
                        "task-features/2")));
        require(future.equals(Optional.of("TaskFeatures.SCHEMA_VERSION expected task-features/2, found task-features/1")),
                "the probe must name the field, the expected and the found value, got " + future);
        var unreadable = VersionProbe.mismatch(List.of(
                new VersionProbe.Expectation(RoutingDecision.class, "NO_SUCH_FIELD", "x")));
        require(unreadable.isPresent() && unreadable.get().contains("could not be read"),
                "an unreadable field must be a mismatch, got " + unreadable);
        System.out.println("[forge-host] version probe passed for forge-routing/1; a future schema reads as "
                + future.orElseThrow());
    }

    /** Section 10.1: a READY draft with a human picked stage and one observed language gives advice and nothing else. */
    private static Advice reviewedIntake() {
        var host = host(Optional.of(catalog()));
        var draft = reviewedDraft();
        var advice = advised(host.request(draft, new Task("task-1", "exec-1"), Cancellation.NONE));
        var selected = selected(advice.decision());
        require(selected.route().equals(LOCAL_SMALL) && selected.executionMode().equals("review")
                && selected.basis() == Basis.HOST_PRIORITY && !selected.overflowUsed(),
                "the preview must select local-small/1 by host priority, got " + selected);
        require(selected.candidates().stream().map(RankedCandidate::key).toList().equals(List.of(LOCAL_SMALL, API_LARGE)),
                "both routes are candidates in tier order, got " + selected.candidates());
        var request = advice.request();
        require(request.scopeId().equals(WORKSPACE.scopeId()) && request.cutoff() == 0 && !request.overflowPermitted(),
                "the request carries the host's opaque scope id, cutoff 0 and no overflow, got " + request);
        var features = request.features();
        require(features.stageKind().equals(Feature.known("implement")) && features.category() instanceof Feature.Unknown
                && features.languages().equals(Feature.known(List.of("java"))) && features.tests() instanceof Feature.Unknown,
                "picked and observed values are Known, the rest Unknown, got " + features);
        require(advice.missingFeatures().equals(List.of(FeatureDimension.CATEGORY)),
                "the assessment names the category the person did not pick, got " + advice.missingFeatures());
        require(advice.review() instanceof DecisionReview.Selected review
                && review.notices().contains(ReviewNotice.RECHECK_BEFORE_EXECUTION)
                && review.notices().contains(ReviewNotice.LEARNED_PREFERENCE_NOT_APPLIED),
                "the review of cold start advice says recheck and no learned preference, got " + advice.review());
        require(AdviceReceipt.decode(advice.receipt().bytes()) instanceof DecodeResult.Decoded(var decoded)
                && decoded.decision().equals(advice.decision()), "the receipt must decode to the same decision");
        require(advice.capturedEpoch() == WORKSPACE.epoch(), "the epoch is captured with the request");
        require(host.recheck(advice, draft) instanceof Verdict.Fresh, "unchanged inputs keep the advice fresh");
        require(host.dispositions().isEmpty() && host.boundary().reachedCalls() == 0,
                "advice alone records no disposition and reaches no side effect");
        System.out.println("[forge-host] reviewed intake -> " + selected.route() + " " + selected.basis()
                + ", missing for preview " + advice.missingFeatures() + ", notices "
                + ((DecisionReview.Selected) advice.review()).notices());
        return advice;
    }

    /**
     * Section 10.4: nothing was observed or picked beyond the stage, every field stays Unknown and the advice is the
     * same route on configuration alone. This host's rule is conservative: it records a deferral but refuses to record
     * that the person followed or redirected advice while a field the preview needs is unknown; once the person picks
     * the field the advice is stale and is re requested.
     */
    private static void unknownFeaturesStayUnknown() {
        var host = host(Optional.of(catalog()));
        var draft = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION).withScope(SCOPE)
                .withReview(IMPLEMENT).withContext(Context.none()).confirm();
        var advice = advised(host.request(draft, new Task("task-2", "exec-2"), Cancellation.NONE));
        var features = advice.request().features();
        require(features.category() instanceof Feature.Unknown && features.languages() instanceof Feature.Unknown
                && features.domains() instanceof Feature.Unknown && features.changeSize() instanceof Feature.Unknown
                && features.contextSize() instanceof Feature.Unknown && features.tests() instanceof Feature.Unknown
                && features.security() instanceof Feature.Unknown, "nothing is defaulted, got " + features);
        require(selected(advice.decision()).route().equals(LOCAL_SMALL), "eligibility ignores unknown features");
        require(advice.missingFeatures().equals(List.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES)),
                "two required dimensions are missing, got " + advice.missingFeatures());
        require(host.dispose(advice, draft, new Choice.Follow()).equals(new DispositionOutcome.Refused(Refusal.FEATURES_UNCONFIRMED))
                && host.dispose(advice, draft, new Choice.Alternative(API_LARGE))
                        .equals(new DispositionOutcome.Refused(Refusal.FEATURES_UNCONFIRMED)),
                "following or redirecting waits for the missing features");
        require(host.dispose(advice, draft, new Choice.Defer()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Deferred ignored),
                "deferring is always allowed");
        // Observations that break the rules are reported as Unknown, never truncated or allowed to throw.
        var noisy = draft.withContext(new Context(Optional.of(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")),
                Optional.of(List.of("web", "web")), OptionalLong.of(-1), OptionalLong.of(12))).confirm();
        var noisyFeatures = host(Optional.of(catalog())).request(noisy,
                new Task("task-2", "exec-2"), Cancellation.NONE);
        var observed = advised(noisyFeatures).request().features();
        require(observed.languages() instanceof Feature.Unknown && observed.domains() instanceof Feature.Unknown
                && observed.changeSize() instanceof Feature.Unknown && observed.contextSize().equals(Feature.known(12L)),
                "nine tags, a repeated tag and a negative size stay Unknown, got " + observed);
        // The person picks the category: the confirmed characteristics changed, so the old advice is stale.
        var picked = draft.withReview(new Review(Optional.of("implement"), Optional.of("feature"), Optional.empty(),
                Optional.empty())).withContext(new Context(Optional.of(List.of("java")), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty())).confirm();
        require(host.recheck(advice, picked) instanceof Verdict.Stale(var failed)
                && failed.equals(List.of(Check.CONTEXT_FINGERPRINT, Check.CONSTRAINTS_FINGERPRINT)),
                "a later pick changes the constraints, got " + host.recheck(advice, picked));
        var again = advised(host.reRequest(advice, picked, Cancellation.NONE));
        require(again.missingFeatures().isEmpty(), "every required dimension is known now");
        require(host.dispose(again, picked, new Choice.Follow()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Followed f)
                && f.advice().advisedRoute().equals(Optional.of(LOCAL_SMALL)), "the confirmed advice can be followed");
        require(host.boundary().reachedCalls() == 0, "no side effect");
        System.out.println("[forge-host] unknown features -> " + selected(advice.decision()).route()
                + " on configuration alone; follow refused until " + advice.missingFeatures() + " are picked");
    }

    /** An unset scope permits nothing: the draft cannot be confirmed and the host builds no request. */
    private static void unsetScopeIsNotReady() {
        var host = host(Optional.of(catalog()));
        var unset = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION).withReview(IMPLEMENT);
        try {
            unset.confirm();
            throw new IllegalStateException("a draft without a scope must not confirm");
        } catch (IllegalStateException e) {
            require(e.getMessage().contains("SCOPE"), "the refusal names the scope, got " + e.getMessage());
        }
        require(host.request(unset, new Task("task-3", "exec-3"), Cancellation.NONE).equals(new Readiness.NotReady(
                List.of(Readiness.Prerequisite.SCOPE, Readiness.Prerequisite.CONFIRMATION))),
                "an unset scope is NOT_READY(scope, confirmation)");
        require(host.request(TaskDraft.empty(),
                new Task("task-3", "exec-3"), Cancellation.NONE).equals(new Readiness.NotReady(
                List.of(Readiness.Prerequisite.WORKSPACE, Readiness.Prerequisite.SPECIFICATION,
                        Readiness.Prerequisite.SCOPE, Readiness.Prerequisite.CONFIRMATION, Readiness.Prerequisite.STAGE))),
                "an empty draft lacks everything");
        var noStage = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION).withScope(SCOPE)
                .confirm();
        require(host.request(noStage, new Task("task-3", "exec-3"), Cancellation.NONE).equals(new Readiness.NotReady(
                List.of(Readiness.Prerequisite.STAGE))), "a confirmed draft without a stage pick is NOT_READY(stage)");
        require(host.history().isEmpty() && host.boundary().reachedCalls() == 0, "NOT_READY builds nothing and acts on nothing");
        System.out.println("[forge-host] unset scope -> NOT_READY[SCOPE, CONFIRMATION], no request built");
    }

    /**
     * Section 10.3: a scope edit withdraws the confirmation and changes the constraints fingerprint, so the guard
     * reports stale advice before it can be acted on; the re request keeps the task and execution ids and takes a new
     * attempt id and the next ordinal; the stale decision never receives a disposition.
     */
    private static void scopeEditInvalidatesReview() {
        var host = host(Optional.of(catalog()));
        var draft = reviewedDraft();
        var first = advised(host.request(draft, new Task("task-4", "exec-4"), Cancellation.NONE));
        var narrowed = draft.withScope(new Scope.Directories(List.of("src/main/java/uploader")));
        require(narrowed.state() == TaskDraft.State.REVIEWABLE, "a scope edit withdraws the confirmation");
        require(host.recheck(first, narrowed).equals(new Verdict.Stale(List.of(Check.DRAFT_READY, Check.CONSTRAINTS_FINGERPRINT))),
                "the guard fails checks 1 and 3, got " + host.recheck(first, narrowed));
        require(host.dispose(first, narrowed, new Choice.Follow()).equals(new DispositionOutcome.Refused(Refusal.ADVICE_STALE)),
                "stale advice cannot be followed");
        var confirmed = narrowed.confirm();
        require(host.recheck(first, confirmed).equals(new Verdict.Stale(List.of(Check.CONSTRAINTS_FINGERPRINT))),
                "re confirming does not revive advice computed over the old scope");
        var second = advised(host.reRequest(first, confirmed, Cancellation.NONE));
        var before = first.request();
        var after = second.request();
        require(after.taskId().equals(before.taskId()) && after.executionId().equals(before.executionId())
                && !after.attemptId().equals(before.attemptId()) && after.requestOrdinal() == before.requestOrdinal() + 1
                && !after.constraintsFingerprint().equals(before.constraintsFingerprint()),
                "a re request keeps task and execution, mints an attempt and takes the next ordinal");
        require(host.recheck(second, confirmed) instanceof Verdict.Fresh, "the re requested advice is fresh");
        require(host.dispose(first, confirmed, new Choice.Defer()).equals(new DispositionOutcome.Refused(Refusal.ADVICE_RE_REQUESTED)),
                "a re requested decision may never receive a disposition");
        require(!host.dispositions().containsKey(first.ref()), "the stale decision is history only");
        require(host.boundary().reachedCalls() == 0, "no side effect");
        System.out.println("[forge-host] scope edit -> STALE" + ((Verdict.Stale) host.recheck(first, confirmed)).failed()
                + ", re requested as " + second.ref());
    }

    /** Checks 2, 3 and 4 fail one at a time: a workspace replacement, a text edit, a context change, a catalog change. */
    private static void otherInputsInvalidateToo() {
        var host = host(Optional.of(catalog()));
        var draft = reviewedDraft();
        var advice = advised(host.request(draft, new Task("task-5", "exec-5"), Cancellation.NONE));
        var replaced = draft.withWorkspace(new Workspace(WORKSPACE.scopeId(), WORKSPACE.epoch() + 1,
                WORKSPACE.sourceRevision())).confirm();
        require(host.recheck(advice, replaced).equals(new Verdict.Stale(List.of(Check.EPOCH))),
                "a replaced workspace is an epoch change, got " + host.recheck(advice, replaced));
        var committed = draft.withWorkspace(new Workspace(WORKSPACE.scopeId(), WORKSPACE.epoch(), "rev-d00d"))
                .confirm();
        require(host.recheck(advice, committed).equals(new Verdict.Stale(List.of(Check.SOURCE_FINGERPRINT))),
                "a new source revision changes the source fingerprint");
        var edited = draft.withSpecification(new Specification(SPECIFICATION.title(), "Also log every retry."))
                .confirm();
        require(host.recheck(advice, edited).equals(new Verdict.Stale(List.of(Check.CONSTRAINTS_FINGERPRINT))),
                "a text edit changes the constraints fingerprint, though the text itself never left the host");
        var recollected = draft.withContext(new Context(Optional.of(List.of("java", "kotlin")), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty())).confirm();
        require(host.recheck(advice, recollected).equals(new Verdict.Stale(List.of(Check.CONTEXT_FINGERPRINT))),
                "a new context collection changes the context fingerprint");
        host.replaceCatalog(Optional.of(new CatalogConfiguration("preview-catalog-2026-11",
                List.of(entry(LOCAL_SMALL, 1, "local", List.of(), 1), entry(API_LARGE, 2, "remote",
                        List.of("long-context"), 2, Availability.UNAVAILABLE)))));
        require(host.recheck(advice, draft).equals(new Verdict.Stale(List.of(Check.CATALOG_VERSION))),
                "an availability change is a new catalog version and makes the advice stale");
        host.replaceCatalog(Optional.empty());
        require(host.recheck(advice, draft).equals(new Verdict.Stale(List.of(Check.CATALOG_VERSION))),
                "a removed catalog fails the catalog check");
        System.out.println("[forge-host] epoch, source, text, context and catalog changes each -> STALE");
    }

    /** Sections 10.5, 10.7 and 10.9 plus the integration switch: the host does not call, and nothing is built. */
    private static void hostDoesNotCallWithoutCatalogIntegrationOrWhenCancelled() {
        var draft = reviewedDraft();
        var noCatalog = host(Optional.empty());
        require(noCatalog.request(draft,
                new Task("task-6", "exec-6"), Cancellation.NONE).equals(unavailable(Readiness.Reason.NO_CATALOG)),
                "no configured catalog is UNAVAILABLE(no-catalog)");
        var emptyCatalog = host(Optional.of(new CatalogConfiguration(CATALOG_VERSION, List.of())));
        require(emptyCatalog.request(draft,
                new Task("task-6", "exec-6"), Cancellation.NONE).equals(unavailable(Readiness.Reason.NO_CATALOG)),
                "zero routes is UNAVAILABLE(no-catalog), never NoEligibleRoute");
        var off = new ForgeHost(adapter(LexicographicRoutingPolicy.reference(), PREVIEW), Integration.OFF,
                Optional.of(catalog()), VersionProbe.forgeRoutingV1());
        require(off.request(draft,
                new Task("task-6", "exec-6"), Cancellation.NONE).equals(unavailable(Readiness.Reason.INTEGRATION_OFF)),
                "integration off is UNAVAILABLE(integration-off)");
        var cancelled = host(Optional.of(catalog()));
        require(cancelled.request(draft,
                new Task("task-6", "exec-6"), Cancellation.REQUESTED).equals(unavailable(Readiness.Reason.CANCELLED)),
                "a withdrawn request is UNAVAILABLE(cancelled)");
        var future = new ForgeHost(adapter(LexicographicRoutingPolicy.reference(), PREVIEW), Integration.ON,
                Optional.of(catalog()), List.of(new VersionProbe.Expectation(
                        TaskFeatures.class, "SCHEMA_VERSION", "task-features/2")));
        require(future.request(draft,
                new Task("task-6", "exec-6"), Cancellation.NONE).equals(unavailable(Readiness.Reason.VERSION_MISMATCH)),
                "a probe mismatch is UNAVAILABLE(version-mismatch)");
        require(future.log().equals(List.of(
                "version probe: TaskFeatures.SCHEMA_VERSION expected task-features/2, found task-features/1")),
                "the detail is in the host log only, got " + future.log());
        for (ForgeHost host : List.of(noCatalog, emptyCatalog, off, cancelled, future)) {
            require(host.history().isEmpty() && host.boundary().reachedCalls() == 0, "UNAVAILABLE builds nothing");
        }
        System.out.println("[forge-host] no catalog, integration off, cancelled, version mismatch -> UNAVAILABLE, decide not called");
    }

    /**
     * Every disposition variant over selected, abstaining and no eligible route advice is recorded without reaching
     * the side effect boundary. The abstention needs a tier cap, which the readiness contract places at Phase 3 and
     * later (Section 10.2); it is exercised here only to show the disposition path over every advice kind.
     */
    private static void dispositionsHaveNoSideEffect() {
        var draft = reviewedDraft().withReview(new Review(Optional.of("implement"), Optional.of("feature"),
                Optional.of(TaskDraft.Toggle.REQUIRED), Optional.empty())).confirm();
        var selectedHost = host(Optional.of(catalog()));
        var selected = advised(selectedHost.request(draft, new Task("task-7", "exec-7"), Cancellation.NONE));
        require(selectedHost.dispose(selected, draft, new Choice.Follow()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Followed f)
                && f.advice().advisedRoute().equals(Optional.of(LOCAL_SMALL)), "a selection can be followed");
        require(selectedHost.dispose(selected, draft, new Choice.Alternative(API_LARGE))
                instanceof DispositionOutcome.Recorded(AdviceDisposition.AlternativeProposed proposed)
                && proposed.route().equals(API_LARGE) && proposed.standing() == AlternativeStanding.ELIGIBLE_IN_DECISION,
                "the other candidate is an eligible alternative");
        require(selectedHost.dispose(selected, draft, new Choice.Alternative(LOCAL_SMALL))
                .equals(new DispositionOutcome.Rejected(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE)),
                "the advised route is not an alternative");
        require(selectedHost.dispose(selected, draft, new Choice.Defer()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Deferred d1)
                && selectedHost.dispose(selected, draft, new Choice.Decline()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Declined d2),
                "deferring and declining a selection are recorded");

        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
        var longContext = new HardRequirements(List.of("long-context"), List.of(), List.of("local", "remote"),
                List.of("review"), 0L);
        var abstainingHost = new ForgeHost(adapter(capped, longContext), Integration.ON, Optional.of(catalog()),
                VersionProbe.forgeRoutingV1());
        var abstained = advised(abstainingHost.request(draft, new Task("task-8", "exec-8"), Cancellation.NONE));
        require(abstained.decision() instanceof RoutingDecision.Abstain abstain
                && abstain.reason() == AbstainReason.POLICY_TRADEOFF_UNRESOLVED && abstain.eligible().equals(List.of(API_LARGE)),
                "the only eligible route sits above the cap, got " + abstained.decision());
        require(abstainingHost.dispose(abstained, draft, new Choice.Follow())
                .equals(new DispositionOutcome.Rejected(DispositionRejection.ADVICE_NOT_SELECTED)), "an abstention cannot be followed");
        require(abstainingHost.dispose(abstained, draft, new Choice.Alternative(API_LARGE))
                instanceof DispositionOutcome.Recorded(AdviceDisposition.AlternativeProposed eligible)
                && eligible.standing() == AlternativeStanding.ELIGIBLE_IN_DECISION, "the eligible route may be proposed");
        require(abstainingHost.dispose(abstained, draft, new Choice.Alternative(LOCAL_SMALL))
                instanceof DispositionOutcome.Recorded(AdviceDisposition.AlternativeProposed excluded)
                && excluded.standing() == AlternativeStanding.EXCLUDED_IN_DECISION, "an excluded route is a proposal with that standing");
        require(abstainingHost.dispose(abstained, draft, new Choice.Defer()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Deferred d3),
                "an abstention can be deferred");

        var gpu = new HardRequirements(List.of("gpu"), List.of(), List.of("local", "remote"), List.of("review"), 0L);
        var noneHost = new ForgeHost(adapter(LexicographicRoutingPolicy.reference(), gpu), Integration.ON, Optional.of(catalog()),
                VersionProbe.forgeRoutingV1());
        var none = advised(noneHost.request(draft, new Task("task-9", "exec-9"), Cancellation.NONE));
        require(none.decision() instanceof RoutingDecision.NoEligibleRoute, "no route declares gpu");
        require(noneHost.dispose(none, draft, new Choice.Alternative(new RouteKey("offline-llm", 1)))
                instanceof DispositionOutcome.Recorded(AdviceDisposition.AlternativeProposed claim)
                && claim.standing() == AlternativeStanding.HOST_CLAIM, "a route the catalog never saw is a host claim");
        require(noneHost.dispose(none, draft, new Choice.Decline()) instanceof DispositionOutcome.Recorded(AdviceDisposition.Declined d4),
                "no eligible route can be declined");

        for (ForgeHost host : List.of(selectedHost, abstainingHost, noneHost)) {
            require(host.boundary().reachedCalls() == 0, "no disposition reaches the side effect boundary");
        }
        var boundary = new SideEffectBoundary();
        try {
            boundary.execute(LOCAL_SMALL.routeId());
            throw new IllegalStateException("the boundary must refuse");
        } catch (UnsupportedOperationException expected) {
            require(boundary.reachedCalls() == 1, "a refused attempt is still counted");
        }
        System.out.println("[forge-host] followed, alternative, deferred, declined over selected, abstained and no eligible route"
                + " -> recorded, side effects 0");
    }

    /**
     * Forge security invariant 1 and readiness contract Section 4.3: task text that reads like an instruction and the
     * scope entries change nothing Neuron sees except a digest, and no token of either appears in any Neuron input or
     * output.
     */
    private static void taskTextAndPathsNeverEnterNeuron() {
        var plain = advised(host(Optional.of(catalog())).request(reviewedDraft(),
                new Task("task-10", "exec-10"), Cancellation.NONE));
        var advice = advised(host(Optional.of(catalog())).request(loudDraft(),
                new Task("task-10", "exec-10"), Cancellation.NONE));
        var plainSelected = selected(plain.decision());
        var loudSelected = selected(advice.decision());
        require(loudSelected.route().equals(plainSelected.route()) && loudSelected.basis() == plainSelected.basis()
                && loudSelected.candidates().equals(plainSelected.candidates())
                && advice.request().features().equals(plain.request().features()),
                "the text changes no feature and no advice");
        require(!advice.request().constraintsFingerprint().equals(plain.request().constraintsFingerprint()),
                "the text is covered by the constraints digest");
        String visible = String.join("\n", String.valueOf(advice.request()), String.valueOf(advice.decision()),
                String.valueOf(advice.review()), String.valueOf(advice.assessment()),
                new String(advice.receipt().bytes(), StandardCharsets.ISO_8859_1));
        for (String token : INSTRUCTION_TOKENS) {
            require(!visible.contains(token), "'" + token + "' must not appear in any Neuron input or output");
        }
        require(!advice.request().scopeId().contains("/"), "the scope id is an opaque id, not a path");
        System.out.println("[forge-host] instruction-like text and scope entries -> same advice, no token crosses the boundary");
    }

    private static Advice advised(Readiness readiness) {
        if (readiness instanceof Readiness.Advised(Advice advice)) {
            return advice;
        }
        throw new IllegalStateException("expected ADVISED, got " + readiness);
    }

    private static RoutingDecision.Selected selected(RoutingDecision decision) {
        if (decision instanceof RoutingDecision.Selected selected) {
            return selected;
        }
        throw new IllegalStateException("expected Selected, got " + decision);
    }

    private static Readiness unavailable(Readiness.Reason reason) {
        return new Readiness.Unavailable(reason);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
