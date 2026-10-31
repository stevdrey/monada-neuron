package consumer.forge;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.DispositionRejection;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.features.FeatureReadinessAssessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A Forge-like host at Phase 2: it begins with a reviewed draft and ends with advice and a disposition, and nothing
 * runs. The host owns every id, ordinal and attempt counter, the static catalog configuration, the history of advice,
 * the dispositions and the {@link SideEffectBoundary} that no part of this flow reaches. It is sequential: one instance
 * is never used from two threads. The guards here are one possible host policy, not an authorization implementation.
 */
public final class ForgeHost {

    /** The one reviewed plan stage this fixture's person picks; a real host mints one id per stage. */
    public static final String STAGE_ID = "plan-stage-1";

    /**
     * The host's static preview catalog as configured, kept as routes so that zero routes are detected before any
     * {@link RouteCatalog} is built: an empty catalog is constructible and would yield {@code NoEligibleRoute}, which
     * never stands in for {@code no-catalog} (readiness contract, Section 5.1).
     */
    public record CatalogConfiguration(String version, List<CatalogEntry> routes) {
        public CatalogConfiguration {
            Objects.requireNonNull(version, "version");
            routes = List.copyOf(routes);
        }

        Optional<RouteCatalog> catalog() {
            return routes.isEmpty() ? Optional.empty() : Optional.of(RouteCatalog.of(version, routes));
        }
    }

    /** The host's own ids of one task; the attempt and stage ids are minted here, not supplied. */
    public record Task(String taskId, String executionId) {
        public Task {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(executionId, "executionId");
        }
    }

    /** The host's integration switch (readiness contract, Section 5.1): off means Neuron is never called. */
    public enum Integration { ON, OFF }

    /** Whether the person cancelled while the host was preparing the request. */
    public enum Cancellation { NONE, REQUESTED }

    /** What the person did with the advice. */
    public sealed interface Choice permits Choice.Follow, Choice.Alternative, Choice.Defer, Choice.Decline {
        record Follow() implements Choice { }

        record Alternative(RouteKey route) implements Choice {
            public Alternative {
                Objects.requireNonNull(route, "route");
            }
        }

        record Defer() implements Choice { }

        record Decline() implements Choice { }
    }

    /** Why this host refused to record a disposition before asking Neuron to bind one. */
    public enum Refusal { ADVICE_STALE, ADVICE_RE_REQUESTED, FEATURES_UNCONFIRMED }

    /** The outcome of a disposition attempt, in host vocabulary. */
    public sealed interface DispositionOutcome
            permits DispositionOutcome.Recorded, DispositionOutcome.Refused, DispositionOutcome.Rejected {
        /** Neuron bound the disposition and the host recorded it. */
        record Recorded(AdviceDisposition disposition) implements DispositionOutcome { }

        /** The host's own guard refused before any binding was attempted. */
        record Refused(Refusal refusal) implements DispositionOutcome { }

        /** Neuron refused to bind, with its coded reason. */
        record Rejected(DispositionRejection reason) implements DispositionOutcome { }
    }

    private final ForgeRoutingAdapter adapter;
    private final Optional<String> versionMismatch;
    private final Integration integration;
    private final SideEffectBoundary boundary = new SideEffectBoundary();
    private final Map<String, Long> ordinals = new HashMap<>();
    private final List<Advice> history = new ArrayList<>();
    /** One disposition per advice; which one is current is a lookup by ref, never an order. */
    private final Map<DecisionRef, AdviceDisposition> dispositions = new HashMap<>();
    private final Set<DecisionRef> reRequested = new HashSet<>();
    private final List<String> log = new ArrayList<>();
    private Optional<CatalogConfiguration> catalog;
    private int mintedAttempts;

    /**
     * Runs the version probe once, before any Neuron object is built (readiness contract, Section 5.2). A mismatch is
     * remembered and every later request reports {@code UNAVAILABLE(version-mismatch)}; the detail goes to the log.
     */
    public ForgeHost(ForgeRoutingAdapter adapter, Integration integration, Optional<CatalogConfiguration> catalog,
            List<VersionProbe.Expectation> expectations) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.versionMismatch = VersionProbe.mismatch(Objects.requireNonNull(expectations, "expectations"));
        versionMismatch.ifPresent(detail -> log.add("version probe: " + detail));
    }

    /**
     * Obtains advice for a draft, or reports why not. The checks run in the order of the readiness contract, Section
     * 5.3: the draft's prerequisites, the integration switch, the catalog configuration, the version probe (its cached
     * result, then the feature schema), then cancellation right before the request is built. Only the last step builds
     * the request and calls {@code decide}.
     */
    public Readiness request(TaskDraft draft, Task task, Cancellation cancellation) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(cancellation, "cancellation");
        var missing = prerequisites(draft);
        if (!missing.isEmpty()) {
            return new Readiness.NotReady(missing);
        }
        if (integration == Integration.OFF) {
            return new Readiness.Unavailable(Readiness.Reason.INTEGRATION_OFF);
        }
        var routes = catalog.flatMap(CatalogConfiguration::catalog);
        if (routes.isEmpty()) {
            return new Readiness.Unavailable(Readiness.Reason.NO_CATALOG);
        }
        if (versionMismatch.isPresent()) {
            return new Readiness.Unavailable(Readiness.Reason.VERSION_MISMATCH);
        }
        String stageKind = draft.review().stageKind().orElseThrow();
        var features = adapter.features(draft);
        if (!(adapter.assess(features, stageKind) instanceof FeatureReadinessAssessment.Assessed assessment)) {
            log.add("feature schema mismatch for " + task.taskId());
            return new Readiness.Unavailable(Readiness.Reason.VERSION_MISMATCH);
        }
        if (cancellation == Cancellation.REQUESTED) {
            return new Readiness.Unavailable(Readiness.Reason.CANCELLED);
        }
        var workspace = draft.workspace().orElseThrow();
        long ordinal = ordinals.merge(workspace.scopeId(), 1L, Long::sum);
        var ids = new ForgeRoutingAdapter.Ids(task.taskId(), task.executionId(), "attempt-" + ++mintedAttempts,
                STAGE_ID);
        var request = adapter.request(draft, ids, ordinal, adapter.fingerprints(draft), features, stageKind);
        var advice = adapter.advise(request, routes.get(), assessment, workspace.epoch());
        history.add(advice);
        log.add("advised " + advice.ref() + " -> " + advice.decision().getClass().getSimpleName());
        return new Readiness.Advised(advice);
    }

    /**
     * Discards stale advice and asks again (readiness contract, Section 6.2): the same task and execution ids, a new
     * attempt id, the next ordinal for the scope, features and fingerprints rebuilt from the current draft. The stale
     * decision stays in history and may never receive a disposition.
     */
    public Readiness reRequest(Advice stale, TaskDraft current, Cancellation cancellation) {
        Objects.requireNonNull(stale, "stale");
        reRequested.add(stale.ref());
        log.add("discarded stale " + stale.ref());
        return request(current, new Task(stale.request().taskId(), stale.request().executionId()), cancellation);
    }

    /** The host guard over advice about to be shown as actionable, with the fingerprints recomputed from the draft now. */
    public HostGuard.Verdict recheck(Advice advice, TaskDraft current) {
        return HostGuard.check(advice, current, adapter.fingerprints(current),
                catalog.map(CatalogConfiguration::version));
    }

    /**
     * Records what the person did with the advice. The host refuses before Neuron is asked when the advice was already
     * re requested, when the guard finds it stale, and, as this host's own conservative rule, when the person would
     * follow the advice or propose a route while a feature the preview needs is still unknown; deferring or declining
     * is always allowed. A later disposition for the same advice replaces the earlier one. Nothing here reaches the
     * {@link SideEffectBoundary}: a recorded disposition is information, never execution.
     */
    public DispositionOutcome dispose(Advice advice, TaskDraft current, Choice choice) {
        Objects.requireNonNull(advice, "advice");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(choice, "choice");
        if (reRequested.contains(advice.ref())) {
            return new DispositionOutcome.Refused(Refusal.ADVICE_RE_REQUESTED);
        }
        if (recheck(advice, current) instanceof HostGuard.Verdict.Stale) {
            return new DispositionOutcome.Refused(Refusal.ADVICE_STALE);
        }
        boolean commits = choice instanceof Choice.Follow || choice instanceof Choice.Alternative;
        if (commits && !advice.missingFeatures().isEmpty()) {
            return new DispositionOutcome.Refused(Refusal.FEATURES_UNCONFIRMED);
        }
        var receipt = advice.receipt();
        var ref = advice.ref();
        DispositionResult result = switch (choice) {
            case Choice.Follow ignored -> AdviceDisposition.followed(receipt, ref);
            case Choice.Alternative alternative -> AdviceDisposition.alternative(receipt, ref, alternative.route());
            case Choice.Defer ignored -> AdviceDisposition.deferred(receipt, ref);
            case Choice.Decline ignored -> AdviceDisposition.declined(receipt, ref);
        };
        return switch (result) {
            case DispositionResult.Bound bound -> {
                dispositions.put(ref, bound.disposition());
                log.add("disposed " + ref + " -> " + bound.disposition().getClass().getSimpleName());
                yield new DispositionOutcome.Recorded(bound.disposition());
            }
            case DispositionResult.Rejected rejected -> new DispositionOutcome.Rejected(rejected.reason());
        };
    }

    /** Replaces the static catalog configuration; any outstanding advice over the old version becomes stale. */
    public void replaceCatalog(Optional<CatalogConfiguration> configuration) {
        this.catalog = Objects.requireNonNull(configuration, "configuration");
    }

    public List<Advice> history() {
        return List.copyOf(history);
    }

    public Map<DecisionRef, AdviceDisposition> dispositions() {
        return Map.copyOf(dispositions);
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public SideEffectBoundary boundary() {
        return boundary;
    }

    /** The draft's missing parts in this host's vocabulary, then the host's own two prerequisites. */
    private List<Readiness.Prerequisite> prerequisites(TaskDraft draft) {
        var missing = new ArrayList<Readiness.Prerequisite>(Readiness.Prerequisite.values().length);
        for (TaskDraft.Missing value : draft.missing()) {
            missing.add(switch (value) {
                case WORKSPACE -> Readiness.Prerequisite.WORKSPACE;
                case SPECIFICATION -> Readiness.Prerequisite.SPECIFICATION;
                case SCOPE -> Readiness.Prerequisite.SCOPE;
            });
        }
        if (!draft.ready()) {
            missing.add(Readiness.Prerequisite.CONFIRMATION);
        }
        if (draft.review().stageKind().isEmpty()) {
            missing.add(Readiness.Prerequisite.STAGE);
        }
        return List.copyOf(missing);
    }
}
