package consumer.host;

import consumer.host.EmbeddedRoutingHost.Learning;
import consumer.host.EmbeddedRoutingHost.Routed;
import com.monada.api.execution.ExecutionMemory;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.resonance.adapter.experience.RejectionReason;
import monada.neuron.resonance.adapter.experience.RoutingEnvelope;
import monada.neuron.routing.Basis;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.CohortState;
import monada.neuron.routing.adaptation.PartitionField;
import monada.neuron.routing.adaptation.StateUpdate;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.ScoreRule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the embedded host loop and its negative cases against the published artifacts. Every check throws on
 * failure, so the Gradle {@code run} task fails; the argument says whether the host must be a named module
 * ({@code module}) or the unnamed module ({@code classpath}).
 */
public final class EmbeddedHostMain {

    private static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    private static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    /** The reference policy's support threshold the loop must reach before learned advice may apply. */
    private static final int SUPPORT = LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS;

    private EmbeddedHostMain() {
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length == 1 ? args[0] : "";
        checkModuleMode(mode);
        Path work = Files.createTempDirectory("monada-neuron-embedded-host-");
        try {
            var first = learningLoop(work.resolve("loop-1"));
            var second = learningLoop(work.resolve("loop-2"));
            require(first.equals(second), "two runs over fresh Stores must agree: " + first + " vs " + second);
            invalidQualityIsNeverReinforced(work.resolve("quality"));
            environmentalFailureIsNeutral(work.resolve("environmental"));
            scopesStayIsolated(work.resolve("scopes"));
            tamperedEnvelopeIsRejected(work.resolve("tamper"));
            var advice = (RoutingDecision.Selected) first;
            System.out.println("[embedded-host:" + mode + "] all checks passed; deterministic later advice "
                    + advice.route() + " (" + advice.basis() + ")");
        } finally {
            delete(work);
        }
    }

    private static void checkModuleMode(String mode) {
        Module self = EmbeddedHostMain.class.getModule();
        Module adapter = RoutingEnvelope.class.getModule();
        switch (mode) {
            case "module" -> {
                require(self.isNamed() && self.getName().equals("monada.neuron.consumer.host"),
                        "the module host must be named, was " + self);
                require("monada.neuron.resonance.adapter".equals(adapter.getName()),
                        "the adapter must resolve as monada.neuron.resonance.adapter, was " + adapter);
                require("monada.neuron".equals(RoutingDecision.class.getModule().getName()),
                        "Neuron must resolve as monada.neuron");
                require("com.monada.api".equals(ExecutionMemory.class.getModule().getName()),
                        "the Store API must resolve as com.monada.api");
                for (String name : List.of("monada.neuron", "monada.neuron.resonance.adapter", "com.monada.api",
                        "com.monada.core")) {
                    var module = ModuleLayer.boot().findModule(name).orElseThrow();
                    System.out.println("[embedded-host:module] " + name + " automatic="
                            + module.getDescriptor().isAutomatic() + " location=" + module.getLayer().configuration()
                            .findModule(name).flatMap(m -> m.reference().location()).orElseThrow());
                }
            }
            case "classpath" -> require(!self.isNamed() && !adapter.isNamed(),
                    "the classpath host and the adapter must be in the unnamed module");
            default -> throw new IllegalArgumentException("expected 'module' or 'classpath', got '" + mode + "'");
        }
        require(ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty(),
                "the host must run without jdk.incubator.vector");
    }

    /** Two routes with the same tier and fallback priority, so only learning or route order decides. */
    private static RouteCatalog catalog(Availability subA) {
        String version = subA == Availability.AVAILABLE ? "cat-1" : "cat-1-sub-a-down";
        return RouteCatalog.of(version, List.of(new CatalogEntry(route("sub-a"), subA, 1),
                new CatalogEntry(route("sub-b"), Availability.AVAILABLE, 1)));
    }

    private static RouteDescriptor route(String id) {
        return RouteDescriptor.builder(id, 1).billingMode(BillingMode.SUBSCRIPTION).stages(List.of("implement"))
                .executionModes(List.of("sandboxed")).locality("hosted").build();
    }

    private static FakeWorker worker() {
        return new FakeWorker(Map.of("sub-a", WorkerReport.failingTests(), "sub-b", WorkerReport.passing()));
    }

    private static Routed runOne(EmbeddedRoutingHost host, String execution, RouteCatalog catalog) {
        var advice = host.route("task-" + execution, execution, catalog);
        require(advice.decision() instanceof RoutingDecision.Selected, "expected advice for " + execution);
        host.execute(advice);
        host.completeAndRecord(advice);
        return advice;
    }

    /**
     * route -> fake execute -> delayed evidence -> Store record -> close/reopen -> bounded export -> feedback -> later
     * recommendation. sub-a is cold-start first but fails validated tests; sub-b is tried while sub-a is down and
     * passes. Below the support threshold nothing changes; at it, the later advice moves to sub-b.
     */
    private static RoutingDecision learningLoop(Path root) {
        var host = new EmbeddedRoutingHost(root, "scope-a", worker());
        var full = catalog(Availability.AVAILABLE);
        host.bind(full);
        int applied = 0;
        int repeated = 0;
        for (int round = 0; round < SUPPORT; round++) {
            var cold = host.route("probe", "probe-" + round, full);
            require(cold.selected().route().equals(SUB_A) && cold.selected().basis() == Basis.COLD_START,
                    "below the support threshold the advice stays the cold-start baseline: " + cold.decision());

            require(runOne(host, "a-" + round, full).selected().route().equals(SUB_A), "full catalog advises sub-a");
            require(runOne(host, "b-" + round, catalog(Availability.UNAVAILABLE)).selected().route().equals(SUB_B),
                    "with sub-a down the advice is sub-b");

            Learning learning = host.learn();
            require(learning.imported().rejections().isEmpty(), "rejections: " + learning.imported().rejections());
            require(learning.imported().observations().size() == 2 * (round + 1),
                    "the cumulative export holds every recorded execution");
            for (var delivery : learning.deliveries()) {
                switch (delivery.update()) {
                    case StateUpdate.Applied ignored -> applied++;
                    case StateUpdate.Unchanged ignored -> repeated++;
                    default -> throw new IllegalStateException("unexpected update " + delivery.update());
                }
                if (delivery.derivation() instanceof FeedbackDerivation.Derived derived) {
                    var expected = delivery.observation().outcome().executedRoute().equals(SUB_A)
                            ? FeedbackDisposition.PENALIZE : FeedbackDisposition.REINFORCE;
                    require(derived.feedback().disposition() == expected,
                            "proven failure penalizes and validated success reinforces: " + derived.feedback());
                }
            }
        }
        require(applied == 2 * SUPPORT, "each execution moves its cohort once: " + applied);
        require(repeated > 0, "re-exported samples must be recognised as already applied");
        require(host.acknowledged().size() == 2 * SUPPORT,
                "every delayed completion was perceived and acknowledged");

        var later = host.route("later", "later-1", full);
        require(later.selected().route().equals(SUB_B) && later.selected().basis() == Basis.LEARNED_PREFERENCE,
                "with sufficient validated support the later advice prefers sub-b: " + later.decision());
        System.out.println("[embedded-host] later advice " + later.selected().route() + " basis "
                + later.selected().basis() + " ledger " + host.state().ledgerDigest());
        return later.decision();
    }

    /** An acceptance without a test verdict is recorded and exported, but never reinforces the route. */
    private static void invalidQualityIsNeverReinforced(Path root) {
        var host = new EmbeddedRoutingHost(root, "scope-quality",
                new FakeWorker(Map.of("sub-a", WorkerReport.acceptedWithoutTestVerdict())));
        var full = catalog(Availability.AVAILABLE);
        host.bind(full);
        var before = host.state().cohorts(full);
        runOne(host, "q-1", full);
        var learning = host.learn();
        require(learning.deliveries().size() == 1, "one sample is exported");
        var derivation = learning.deliveries().getFirst().derivation();
        require(!(derivation instanceof FeedbackDerivation.Derived derived)
                        || derived.feedback().disposition() != FeedbackDisposition.REINFORCE,
                "an acceptance with UNKNOWN mandatory evidence must not reinforce: " + derivation);
        require(sameNodes(before, host.state().cohorts(full)), "no cohort Node moved");
        System.out.println("[embedded-host] invalid quality -> " + describe(derivation));
    }

    /** A timed-out invocation with failing evidence is environmental: neutral, and no cohort moves. */
    private static void environmentalFailureIsNeutral(Path root) {
        var host = new EmbeddedRoutingHost(root, "scope-env", worker());
        var full = catalog(Availability.AVAILABLE);
        host.bind(full);
        var before = host.state().cohorts(full);
        var advice = host.route("task-env", "env-1", full);
        var delivery = host.deliver(host.timedOut(advice, 10L), advice);
        var derived = (FeedbackDerivation.Derived) delivery.derivation();
        require(derived.feedback().rule() == ScoreRule.NEUTRAL_ENVIRONMENTAL
                        && derived.feedback().disposition() == FeedbackDisposition.NEUTRAL,
                "an environmental failure is neutral: " + derived.feedback());
        require(sameNodes(before, host.state().cohorts(full)), "no cohort Node moved");
        System.out.println("[embedded-host] environmental failure -> " + describe(delivery.derivation()));
    }

    /** Two scopes in one Store root: exports, bindings and partitions never cross. */
    private static void scopesStayIsolated(Path root) {
        var hostA = new EmbeddedRoutingHost(root, "scope-a", worker());
        var hostB = new EmbeddedRoutingHost(root, "scope-b", worker());
        var full = catalog(Availability.AVAILABLE);
        var subADown = catalog(Availability.UNAVAILABLE);
        hostA.bind(full);
        hostB.bind(full);
        var adviceA = runOne(hostA, "x-a", subADown);
        runOne(hostB, "x-b", full);

        var learnedA = hostA.learn();
        var learnedB = hostB.learn();
        require(learnedA.imported().observations().size() == 1 && learnedB.imported().observations().size() == 1,
                "each scope exports only its own execution");
        require(learnedB.imported().observations().getFirst().outcome().decisionRef().scopeId().equals("scope-b"),
                "scope B's export holds no scope-A sample");

        var bBefore = hostB.state().cohorts(full);
        var ledgerBefore = hostB.state().ledger();
        var observationA = learnedA.deliveries().getFirst().observation();
        var foreign = hostB.derive(observationA, adviceA);
        require(foreign instanceof FeedbackDerivation.NoUpdate noUpdate
                        && noUpdate.reason() == NoUpdateReason.BINDING_SCOPE_MISMATCH,
                "deriving scope-A evidence against scope-B bindings yields nothing: " + foreign);
        var derivedForA = hostA.derive(observationA, adviceA);
        require(hostB.state().consume(observationA, derivedForA)
                        .equals(new StateUpdate.ForeignPartition(List.of(PartitionField.SCOPE))),
                "scope-A feedback handed to scope B is a foreign partition");
        require(bBefore.equals(hostB.state().cohorts(full)) && ledgerBefore.equals(hostB.state().ledger()),
                "scope B's state is unchanged");
        System.out.println("[embedded-host] cross-scope -> " + describe(foreign) + ", ForeignPartition[SCOPE]");
    }

    /** An envelope that no longer matches the digest the Store recorded is rejected, never translated. */
    private static void tamperedEnvelopeIsRejected(Path root) {
        var host = new EmbeddedRoutingHost(root, "scope-tamper", worker());
        var full = catalog(Availability.AVAILABLE);
        host.bind(full);
        runOne(host, "t-1", full);
        host.tamperEnvelope("t-1");
        var learning = host.learn();
        require(learning.imported().observations().isEmpty(), "a tampered sample is not translated");
        require(learning.imported().rejections().size() == 1
                        && learning.imported().rejections().getFirst().reason() == RejectionReason.ENVELOPE_DIGEST_MISMATCH,
                "the tampered envelope is rejected by digest: " + learning.imported().rejections());
        System.out.println("[embedded-host] tampered envelope -> " + RejectionReason.ENVELOPE_DIGEST_MISMATCH);
    }

    private static boolean sameNodes(List<CohortState> before,
            List<CohortState> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            var b = before.get(i);
            var a = after.get(i);
            if (b.amplitude() != a.amplitude() || b.preferenceValue() != a.preferenceValue()
                    || b.energy() != a.energy()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(FeedbackDerivation derivation) {
        return switch (derivation) {
            case FeedbackDerivation.Derived derived ->
                    derived.feedback().disposition() + "/" + derived.feedback().rule();
            case FeedbackDerivation.NoUpdate noUpdate -> "NoUpdate/" + noUpdate.reason();
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
