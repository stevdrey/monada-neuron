package monada.neuron.resonance.adapter.experience;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCursor;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterException;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.AttemptStatus;
import monada.neuron.routing.outcome.EvidenceRef;
import monada.neuron.routing.outcome.EvidenceRevision;
import monada.neuron.routing.outcome.EvidenceSource;
import monada.neuron.routing.outcome.HypotheticalCost;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityObservation;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.StageAttribution;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;
import monada.neuron.routing.outcome.UsageKind;
import monada.neuron.routing.token.OpaqueTokens;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates bounded {@code execution-sample/1} exports from a caller-owned {@link ExecutionMemory} into Neuron's neutral
 * {@link RoutingObservation}s. This is the only class of the routing integration that imports Store types.
 *
 * <p>The translator only reads: it never records, never calls {@code feedback()}, never closes the memory and keeps no
 * state between imports. It does not learn, and it does not treat recalled similarity, a Store label or a cost as
 * quality: the evidence the Store exports is carried over as typed {@link QualityObservation}s and judged later by
 * {@link monada.neuron.routing.outcome.OutcomeEvaluator}. Corrections yield one effective observation per execution.
 *
 * <p>Bounds: at most {@link ExperienceExportRequest#MAX_TOTAL_SAMPLES} samples per import; time and retained space are
 * {@code O(samples)} plus {@code O(pageSize)}, with one extra point lookup per corrected routed stage. Ordering is total
 * and deterministic. The translator performs no clock or random lookup. One instance is not thread-safe, like the Store
 * facade it reads.
 *
 * <p>Known limits: one routed stage per execution (a second routed stage is refused as ambiguous); the invocation is
 * derived from the routed attempt's result only, so {@code UNAVAILABLE} and {@code TIMED_OUT} are never produced; only
 * the routed stage's usage is carried as {@link ResourceSummary}; stage-local attribution is claimed only when the routed
 * attempt has exactly one stage and recorded evidence.
 */
public final class StoreExperienceTranslator {

    /** The only export schema this translator understands. */
    public static final String SUPPORTED_SCHEMA = ExperienceSamplePage.SCHEMA;

    private static final String EVIDENCE_EVENT_KIND = "store-evidence-event";

    private final Reads reads;
    private final RoutingEnvelopeResolver resolver;
    private final HypotheticalCostAnnotations annotations;

    /** Narrow view of the Store reads the translator needs; package-private so tests can inject crafted pages. */
    interface Reads {
        ScopeId scope();

        ExperienceSamplePage export(ExportRequest request);

        Optional<HistoryEntry> loadEvent(ScopeId scope, EventId eventId, int revision);

        long highWatermark(ScopeId scope);
    }

    /**
     * Creates a translator over a caller-owned memory. The caller keeps ownership: this class never closes it.
     *
     * @param memory open execution memory (writable or read-only)
     * @param resolver host resolver for routing envelopes
     * @param annotations optional hypothetical-cost seam
     */
    public StoreExperienceTranslator(
            ExecutionMemory memory, RoutingEnvelopeResolver resolver, HypotheticalCostAnnotations annotations) {
        this(new MemoryReads(Objects.requireNonNull(memory, "memory must not be null")), resolver, annotations);
    }

    /**
     * Creates a translator without cost annotations.
     *
     * @param memory open execution memory
     * @param resolver host resolver for routing envelopes
     */
    public StoreExperienceTranslator(ExecutionMemory memory, RoutingEnvelopeResolver resolver) {
        this(memory, resolver, HypotheticalCostAnnotations.none());
    }

    StoreExperienceTranslator(Reads reads, RoutingEnvelopeResolver resolver, HypotheticalCostAnnotations annotations) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.annotations = Objects.requireNonNull(annotations, "annotations must not be null");
    }

    /**
     * Last ledger sequence of the memory's scope: a convenient explicit cutoff for a full snapshot.
     *
     * @return the current high-watermark
     */
    public long highWatermark() {
        try {
            return reads.highWatermark(reads.scope());
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ResonanceStoreAdapterException("could not read the execution ledger high-watermark", e);
        }
    }

    /**
     * Reads one pinned snapshot and translates it.
     *
     * @param request explicit scope, cutoff, page size and sample cap
     * @return observations, refusals and the snapshot; never partial on a Store failure
     * @throws ResonanceStoreAdapterException if the Store rejects the read (closed memory, cutoff beyond the ledger,
     *     tampered ledger)
     */
    public ExperienceImport importSamples(ExperienceExportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!reads.scope().value().equals(request.scopeId())) {
            return new ExperienceImport(List.of(), List.of(), List.of(ImportDiagnostic.SCOPE_MISMATCH), Optional.empty());
        }
        var run = new Run(request);
        run.execute();
        return run.result();
    }

    private final class Run {
        private final ExperienceExportRequest request;
        private final List<RoutingObservation> observations = new ArrayList<>();
        private final List<ExperienceRejection> rejections = new ArrayList<>();
        private final Set<ImportDiagnostic> diagnostics = new LinkedHashSet<>();
        private final Set<ExecutionKey> seenExecutions = new HashSet<>();
        private ExperienceImport.Snapshot snapshot;
        private int pages;
        private int examined;

        Run(ExperienceExportRequest request) {
            this.request = request;
        }

        void execute() {
            ScopeId scope = reads.scope();
            Set<com.monada.core.execution.QualityDimension> gates = request.additionalGates().stream()
                    .map(d -> com.monada.core.execution.QualityDimension.valueOf(d.name()))
                    .collect(Collectors.toSet());
            ExportEvidencePolicy policy = gates.isEmpty() ? ExportEvidencePolicy.executionPolicyOnly()
                    : new ExportEvidencePolicy(gates);
            ExportCursor cursor = null;
            while (true) {
                int size = Math.min(request.pageSize(), request.maxTotalSamples() - examined);
                ExportRequest storeRequest = cursor == null
                        ? ExportRequest.first(scope, ExportCutoff.atSequence(request.cutoffSequence()), size,
                                ExportView.ADAPTER_READY, policy)
                        : ExportRequest.resume(cursor, size);
                ExperienceSamplePage page = fetch(storeRequest);
                if (!SUPPORTED_SCHEMA.equals(page.schema())) {
                    diagnostics.add(ImportDiagnostic.UNSUPPORTED_SCHEMA);
                    return;
                }
                if (!scope.equals(page.scope())) {
                    diagnostics.add(ImportDiagnostic.SCOPE_MISMATCH);
                    return;
                }
                pages++;
                if (page.projection().state() != com.monada.api.execution.ProjectionStatus.State.CURRENT
                        || !page.projection().coversCutoff()) {
                    diagnostics.add(ImportDiagnostic.PROJECTION_NOT_CURRENT);
                }
                for (ExperienceSample sample : page.samples()) {
                    examined++;
                    translate(sample, page);
                }
                snapshot = new ExperienceImport.Snapshot(page.schema(), page.ledger().cutoffSequence(),
                        page.ledger().prefixDigest(), pages, examined);
                if (!page.hasMore()) {
                    return;
                }
                if (examined >= request.maxTotalSamples()) {
                    diagnostics.add(ImportDiagnostic.SAMPLE_CAP_REACHED);
                    return;
                }
                cursor = page.next().orElseThrow(() -> new ResonanceStoreAdapterException(
                        "the Store reported more samples but returned no cursor", null));
            }
        }

        private ExperienceSamplePage fetch(ExportRequest storeRequest) {
            try {
                return reads.export(storeRequest);
            } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
                throw new ResonanceStoreAdapterException("the Store refused the sample export", e);
            }
        }

        void translate(ExperienceSample sample, ExperienceSamplePage page) {
            ExperienceRef first = sample.execution();
            String executionRef = ExperienceRefs.format(first);
            try {
                if (!request.scopeId().equals(first.scope().value())) {
                    throw new Refusal(RejectionReason.SCOPE_MISMATCH, "sample belongs to scope " + first.scope().value());
                }
                if (!seenExecutions.add(new ExecutionKey(first.task().value(), first.execution().value()))) {
                    throw new Refusal(RejectionReason.DUPLICATE_OR_CONFLICTING_VERSION,
                            "execution " + first.execution().value() + " of task " + first.task().value()
                                    + " was already seen in this import");
                }
                observations.add(build(sample, page.ledger().cutoffSequence()));
            } catch (Refusal refusal) {
                rejections.add(new ExperienceRejection(executionRef, refusal.reason, refusal.getMessage()));
            } catch (IllegalArgumentException e) {
                rejections.add(new ExperienceRejection(executionRef, RejectionReason.MALFORMED_SAMPLE,
                        bounded(String.valueOf(e.getMessage()))));
            }
        }

        ExperienceImport result() {
            observations.sort(Comparator.comparingLong(RoutingObservation::ledgerSequence)
                    .thenComparing(RoutingObservation::experienceRef, OpaqueTokens::compareCodePoints));
            return new ExperienceImport(observations, rejections, List.copyOf(diagnostics),
                    Optional.ofNullable(snapshot));
        }
    }

    private RoutingObservation build(ExperienceSample sample, long cutoff) {
        ExperienceRef sampleRef = sample.execution();
        RoutedStage routed = routedStage(sample);
        RoutingEnvelope envelope = verifiedEnvelope(sampleRef, routed);

        ExperienceSample.OutcomeFact outcomeFact = sample.outcome().orElseThrow(
                () -> new Refusal(RejectionReason.MISSING_OUTCOME, "the execution has no recorded outcome"));
        ExperienceSample.SampleAttempt routedAttempt = sample.attempts().stream()
                .filter(a -> a.id().value().equals(envelope.decisionRef().attemptId()))
                .findFirst()
                .orElseThrow(() -> new Refusal(RejectionReason.ENVELOPE_IDENTITY_MISMATCH,
                        "the routed attempt is not part of the execution"));
        var result = routedAttempt.result().orElseThrow(() -> new Refusal(RejectionReason.ROUTED_ATTEMPT_UNFINISHED,
                "routed attempt " + routedAttempt.id().value() + " has not finished"));
        InvocationStatus invocation = sameName(result, InvocationStatus.class);

        var outcome = outcomeFact.outcome();
        Optional<String> acceptedId = outcome.acceptedAttempt().map(a -> a.value());
        Feature<AttemptStatus> acceptedStatus = Feature.unknown();
        if (acceptedId.isPresent() && !acceptedId.get().equals(routedAttempt.id().value())) {
            acceptedStatus = sample.attempts().stream()
                    .filter(a -> a.id().value().equals(acceptedId.get()))
                    .findFirst()
                    .flatMap(ExperienceSample.SampleAttempt::result)
                    .<Feature<AttemptStatus>>map(r -> Feature.known(sameName(r, AttemptStatus.class)))
                    .orElse(Feature.unknown());
        }

        List<QualityObservation> evidence = new ArrayList<>();
        Set<EvidenceRevision> judged = new LinkedHashSet<>();
        for (ExperienceSample.SampleAttempt attempt : sample.attempts()) {
            for (ExperienceSample.SampleEvidence event : attempt.evidence()) {
                String eventRef = ExperienceRefs.format(event.ref());
                long revision = event.ref().revision() - 1L;
                String attemptId = event.ref().attempt().orElseThrow(
                        () -> new Refusal(RejectionReason.MALFORMED_SAMPLE, "evidence event without an attempt")).value();
                for (ExperienceSample.SampleObservation entry : event.observations()) {
                    evidence.add(quality(entry.observation(), eventRef, revision, attemptId, event.sequence()));
                }
                judged.add(new EvidenceRevision(eventRef, revision));
            }
        }

        List<HypotheticalCost> costs = annotations.costsFor(
                sampleRef.scope().value(), sampleRef.task().value(), sampleRef.execution().value());
        requireValidCosts(costs);
        ResourceSummary resources = resources(routed.stage, costs);

        var provenance = sample.provenance();
        var policy = sample.policy();
        RoutingOutcome routingOutcome = new RoutingOutcome(
                RoutingRequest.CONTRACT_VERSION, envelope.decisionRef(), envelope.route(),
                ((Feature.Known<CohortBinding>) envelope.cohortBinding()).value(),
                envelope.catalogVersion(), envelope.routingPolicyId(), envelope.routingPolicyVersion(),
                provenance.sourceRevision(), provenance.contextFingerprint(), provenance.constraintsFingerprint(),
                policy.id(), policy.version(), cutoff, invocation,
                sameName(outcome.status(), OutcomeStatus.class), sameName(outcome.origin(), OutcomeOrigin.class),
                acceptedId.<Feature<String>>map(Feature::known).orElse(Feature.unknown()), acceptedStatus,
                outcomeFact.sequence(), evidence, List.copyOf(judged), resources,
                attribution(sample, routedAttempt, acceptedId));

        ExperienceRef stageRef = routed.stage.ref();
        long revision = 0;
        Feature<Long> supersedes = Feature.unknown();
        for (ExperienceSample.Revision corrected : sample.revisions()) {
            revision += corrected.effective().revision() - 1L;
            long replaced = sequenceOfEarliestSuperseded(corrected);
            supersedes = Feature.known(supersedes instanceof Feature.Known<Long> known
                    ? Math.min(known.value(), replaced) : replaced);
        }
        return new RoutingObservation(routingOutcome, ExperienceRefs.format(stageRef), revision,
                sample.lastSequence(), supersedes);
    }

    /** Any corrected fact of the sample (stage, evidence, finish or outcome) advances the observation's version. */
    private long sequenceOfEarliestSuperseded(ExperienceSample.Revision corrected) {
        if (corrected.superseded().isEmpty()) {
            throw new Refusal(RejectionReason.MALFORMED_SAMPLE,
                    "a corrected fact lists no superseded revisions");
        }
        ExperienceRef earliest = corrected.superseded().getFirst();
        Optional<HistoryEntry> entry;
        try {
            entry = reads.loadEvent(earliest.scope(), earliest.eventId(), earliest.revision());
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ResonanceStoreAdapterException("could not read a superseded revision from the Store", e);
        }
        return entry.map(HistoryEntry::sequence).orElseThrow(() -> new Refusal(RejectionReason.MALFORMED_SAMPLE,
                "the superseded revision " + earliest.revision() + " is not readable"));
    }

    private record ExecutionKey(String task, String execution) {
    }

    private record RoutedStage(ExperienceSample.SampleStage stage, ArtifactRef envelopeArtifact) {
    }

    private RoutedStage routedStage(ExperienceSample sample) {
        List<RoutedStage> found = new ArrayList<>(1);
        for (ExperienceSample.SampleAttempt attempt : sample.attempts()) {
            for (ExperienceSample.SampleStage stage : attempt.stages()) {
                for (ArtifactRef artifact : stage.artifacts()) {
                    if (RoutingEnvelope.ARTIFACT_KIND.equals(artifact.kind())) {
                        found.add(new RoutedStage(stage, artifact));
                    }
                }
            }
        }
        if (found.isEmpty()) {
            throw new Refusal(RejectionReason.MISSING_ENVELOPE_REFERENCE,
                    "no stage carries a " + RoutingEnvelope.ARTIFACT_KIND + " reference");
        }
        if (found.size() > 1) {
            throw new Refusal(RejectionReason.AMBIGUOUS_ROUTED_STAGE,
                    found.size() + " routing envelope references; v1 translates one routed stage per execution");
        }
        return found.getFirst();
    }

    private RoutingEnvelope verifiedEnvelope(ExperienceRef sampleRef, RoutedStage routed) {
        ExperienceRef stageRef = routed.stage.ref();
        RoutingEnvelope envelope = resolver.resolve(stageRef.scope().value(), routed.envelopeArtifact.reference())
                .orElseThrow(() -> new Refusal(RejectionReason.ENVELOPE_UNRESOLVED,
                        "the host has no envelope for the recorded reference"));
        // 1. integrity
        if (!routed.envelopeArtifact.digest().map(envelope.digest()::equals).orElse(false)) {
            throw new Refusal(RejectionReason.ENVELOPE_DIGEST_MISMATCH,
                    "the envelope does not match the recorded digest");
        }
        // 2. identity
        var ref = envelope.decisionRef();
        boolean identical = ref.scopeId().equals(stageRef.scope().value())
                && ref.taskId().equals(stageRef.task().value())
                && ref.executionId().equals(stageRef.execution().value())
                && stageRef.attempt().map(a -> a.value().equals(ref.attemptId())).orElse(false)
                && ref.stageId().equals(routed.stage.stage());
        if (!identical) {
            throw new Refusal(RejectionReason.ENVELOPE_IDENTITY_MISMATCH,
                    "the envelope names another scope, task, execution, attempt or stage");
        }
        // 3. descriptor
        if (!matches(envelope.descriptor(), routed.stage.route())) {
            throw new Refusal(RejectionReason.ENVELOPE_ROUTE_MISMATCH,
                    "the envelope descriptor differs from the recorded route");
        }
        if (!(envelope.cohortBinding() instanceof Feature.Known<CohortBinding>)) {
            throw new Refusal(RejectionReason.MISSING_COHORT_BINDING, "the envelope has no cohort binding");
        }
        return envelope;
    }

    private boolean matches(ExecutedRouteDescriptor expected, RouteDescriptor recorded) {
        return expected.worker().equals(recorded.worker())
                && sameOptional(expected.provider(), recorded.provider())
                && sameOptional(expected.model(), recorded.model())
                && sameOptional(expected.effort(), recorded.effort())
                && expected.billingMode().name().equals(recorded.billingMode().name());
    }

    private boolean sameOptional(Feature<String> expected, Optional<String> recorded) {
        return switch (expected) {
            case Feature.Known<String> known -> recorded.map(known.value()::equals).orElse(false);
            case Feature.Unknown<String> unknown -> recorded.isEmpty();
        };
    }

    private QualityObservation quality(
            com.monada.core.execution.QualityObservation observation, String eventRef, long revision,
            String attemptId, long sequence) {
        List<EvidenceRef> refs = observation.evidence().stream()
                .map(a -> new EvidenceRef(a.kind(), a.reference(),
                        a.digest().<Feature<String>>map(Feature::known).orElse(Feature.unknown())))
                .toList();
        return new QualityObservation(eventRef, revision, sameName(observation.dimension(), QualityDimension.class),
                sameName(observation.state(), QualityState.class),
                observation.justification().<Feature<String>>map(Feature::known).orElse(Feature.unknown()),
                observation.evaluatorId(), observation.evaluatorVersion(), observation.policyId(),
                observation.policyVersion(), sameName(observation.source(), EvidenceSource.class), attemptId, sequence,
                refs);
    }

    private void requireValidCosts(List<HypotheticalCost> costs) {
        Set<String> currencies = new HashSet<>();
        if (costs.size() > ResourceSummary.MAX_CURRENCIES
                || !costs.stream().allMatch(c -> currencies.add(c.amount().currency()))) {
            throw new Refusal(RejectionReason.INVALID_ANNOTATION,
                    "hypothetical costs must be at most " + ResourceSummary.MAX_CURRENCIES
                            + " entries with one entry per currency");
        }
    }

    private ResourceSummary resources(ExperienceSample.SampleStage stage, List<HypotheticalCost> costs) {
        if (stage.usageDeclaration() == com.monada.core.execution.UsageDeclaration.NO_BILLABLE_USAGE) {
            String justification = stage.justification().orElseThrow(() -> new Refusal(
                    RejectionReason.MALFORMED_SAMPLE, "a no-billable-usage stage lacks its justification"));
            return new ResourceSummary(UsageDeclaration.NO_BILLABLE_USAGE, Feature.known(justification), List.of(),
                    List.of(), costs);
        }
        List<UsageCounter> counters = new ArrayList<>(stage.usage().size());
        for (ExperienceSample.SampleUsage usage : stage.usage()) {
            ResourceValue value = switch (usage.state()) {
                case REPORTED -> new ResourceValue.Known(usage.value().orElseThrow(), ResourceValue.Provenance.REPORTED);
                case ESTIMATED -> new ResourceValue.Known(usage.value().orElseThrow(), ResourceValue.Provenance.ESTIMATED);
                case UNKNOWN -> new ResourceValue.Unknown();
                case NOT_MEASURED -> new ResourceValue.NotMeasured();
                case DECLARED_NO_BILLABLE_USAGE -> throw new Refusal(RejectionReason.MALFORMED_SAMPLE,
                        "a measured stage reports a no-billable-usage counter");
            };
            counters.add(new UsageCounter(sameName(usage.kind(), UsageKind.class), value));
        }
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(), counters, List.of(), costs);
    }

    /**
     * Stage-local attribution only when the verdict is about the routed attempt itself: no other attempt was accepted,
     * no later repair attempt exists, and the attempt has exactly one stage and recorded evidence. A repair chain
     * (routed attempt A, accepted attempt B) never credits or blames A's route with B's evidence.
     */
    private StageAttribution attribution(
            ExperienceSample sample, ExperienceSample.SampleAttempt routedAttempt, Optional<String> acceptedId) {
        int chainStages = sample.attempts().stream().mapToInt(a -> a.stages().size()).sum();
        boolean evidenceIsTheRoutedAttempts = acceptedId.map(routedAttempt.id().value()::equals).orElse(true);
        boolean last = sample.attempts().getLast().id().equals(routedAttempt.id());
        if (evidenceIsTheRoutedAttempts && last && routedAttempt.stages().size() == 1
                && !routedAttempt.evidence().isEmpty()) {
            List<EvidenceRef> refs = routedAttempt.evidence().stream()
                    .limit(StageAttribution.MAX_REFS)
                    .map(e -> new EvidenceRef(EVIDENCE_EVENT_KIND, ExperienceRefs.format(e.ref()), Feature.unknown()))
                    .toList();
            return new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, chainStages, refs);
        }
        return new StageAttribution(chainStages >= 2 ? StageAttribution.Basis.CHAIN_ONLY : StageAttribution.Basis.NONE,
                Math.max(chainStages, 1), List.of());
    }

    private <E extends Enum<E>> E sameName(Enum<?> source, Class<E> target) {
        try {
            return Enum.valueOf(target, source.name());
        } catch (IllegalArgumentException e) {
            throw new Refusal(RejectionReason.MALFORMED_SAMPLE,
                    "unsupported " + target.getSimpleName() + " value " + source.name());
        }
    }

    private String bounded(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return "invalid sample";
        }
        return trimmed.length() <= 256 ? trimmed : trimmed.substring(0, 256);
    }

    /** Internal control flow for one refused sample; carries no stack trace. */
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient RejectionReason reason;

        Refusal(RejectionReason reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }

    private record MemoryReads(ExecutionMemory memory) implements Reads {
        @Override
        public ScopeId scope() {
            return memory.scope();
        }

        @Override
        public ExperienceSamplePage export(ExportRequest request) {
            return memory.exportSamples(request);
        }

        @Override
        public Optional<HistoryEntry> loadEvent(ScopeId scope, EventId eventId, int revision) {
            return memory.loadEvent(scope, eventId, revision);
        }

        @Override
        public long highWatermark(ScopeId scope) {
            return memory.history(scope).highWatermark();
        }
    }
}
