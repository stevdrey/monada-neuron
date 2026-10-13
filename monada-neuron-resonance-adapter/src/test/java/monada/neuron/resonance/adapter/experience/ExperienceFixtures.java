package monada.neuron.resonance.adapter.experience;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Records routed executions into a real temporary execution memory and builds the matching Forge-side envelopes. */
final class ExperienceFixtures {

    static final ScopeId SCOPE = ScopeId.of("scope-1");
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final AttemptId A1 = AttemptId.of("a1");
    static final AttemptId A2 = AttemptId.of("a2");
    static final RouteDescriptor WORKER = new RouteDescriptor("worker-a", Optional.of("prov"), Optional.of("small"),
            Optional.of("low"), BillingMode.API_METERED);
    static final CohortBinding COHORT = new CohortBinding("bucket-1", "bucket-map-1", "mapping-1", "task-features/1");

    private final ExecutionMemory memory;
    private final Map<String, RoutingEnvelope> envelopes = new HashMap<>();
    private int tick;
    private int counter;

    ExperienceFixtures(ExecutionMemory memory) {
        this.memory = memory;
    }

    RoutingEnvelopeResolver resolver() {
        return (scopeId, ref) -> Optional.ofNullable(envelopes.get(scopeId + "/" + ref));
    }

    void publish(String envelopeRef, RoutingEnvelope envelope) {
        envelopes.put(SCOPE.value() + "/" + envelopeRef, envelope);
    }

    static RoutingEnvelope envelope(String execution, String attempt, String stage) {
        return new RoutingEnvelope(RoutingRequest.CONTRACT_VERSION,
                new DecisionRef(SCOPE.value(), "task-" + execution, execution, attempt, stage, 1),
                new RouteKey("route-a", 1),
                new ExecutedRouteDescriptor("worker-a", Feature.known("prov"), Feature.known("small"),
                        Feature.known("low"), monada.neuron.routing.catalog.BillingMode.API_METERED),
                "catalog-1", "policy", "1", "gates", "1", Feature.known(COHORT), "local");
    }

    private Instant advance() {
        return T0.plusSeconds(++tick);
    }

    private EventId id(String kind) {
        return EventId.of(kind + "-" + (++counter));
    }

    long record(ExecutionEvent event) {
        RecordResult result = memory.record(event);
        assertEquals(RecordResult.Status.APPENDED, result.status(), String.valueOf(result.reason()));
        return result.sequence();
    }

    /** One execution recorded step by step. */
    final class Exec {
        final TaskId task;
        final ExecutionId id;

        Exec(String execution, Set<QualityDimension> mandatory) {
            this.task = TaskId.of("task-" + execution);
            this.id = ExecutionId.of(execution);
            record(ExecutionEvent.executionStarted(id("start"), SCOPE, task, id,
                    new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                    new EvaluationPolicy("gates", "1", mandatory), "Fix " + execution, advance()));
        }

        Exec attempt(AttemptId attempt, int ordinal, Optional<AttemptId> previous) {
            Instant t = advance();
            record(new AttemptStarted(EventEnvelope.original(id("att"), SCOPE, task, id, attempt, t, t), ordinal,
                    previous, ordinal == 1 ? AttemptReason.INITIAL : AttemptReason.REPAIR));
            return this;
        }

        /** Routed stage: carries an envelope reference whose digest is the envelope's SHA-256. */
        EventId routedStage(AttemptId attempt, RoutingEnvelope envelope, UsageCounter... usage) {
            Instant t = advance();
            EventId eventId = id("stage");
            String ref = "env-" + eventId.value();
            publish(ref, envelope);
            record(StageRecorded.measured(EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t),
                    envelope.decisionRef().stageId(), WORKER, t, t, List.of(usage),
                    List.of(ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, ref, envelope.digest()))));
            return eventId;
        }

        EventId stageWithReference(AttemptId attempt, String stage, String ref, Optional<String> digest) {
            Instant t = advance();
            EventId eventId = id("stage");
            ArtifactRef artifact = digest.map(d -> ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, ref, d))
                    .orElse(ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, ref));
            record(StageRecorded.measured(EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t), stage,
                    WORKER, t, t, List.of(), List.of(artifact)));
            return eventId;
        }

        /** Corrects the routed stage {@code target}: a full replacement that keeps the envelope reference. */
        EventId correctRoutedStage(EventId target, int revision, AttemptId attempt, RoutingEnvelope envelope,
                                   UsageCounter... usage) {
            Instant t = advance();
            EventId eventId = id("corr");
            String ref = "env-" + eventId.value();
            publish(ref, envelope);
            EventEnvelope outer = new EventEnvelope(eventId, SCOPE, task, id, Optional.of(attempt), revision,
                    Optional.of(target), t, t);
            record(new Correction(outer, StageRecorded.measured(
                    EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t), envelope.decisionRef().stageId(),
                    WORKER, t, t, List.of(usage),
                    List.of(ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, ref, envelope.digest()))), "usage fixed"));
            return eventId;
        }

        EventId plainStage(AttemptId attempt, String stage) {
            Instant t = advance();
            EventId eventId = id("stage");
            record(StageRecorded.measured(EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t), stage,
                    WORKER, t, t, List.of(), List.of()));
            return eventId;
        }

        EventId evidence(AttemptId attempt, QualityDimension dimension, ObservationState state) {
            Instant t = advance();
            EventId eventId = id("ev");
            record(new EvidenceRecorded(EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t),
                    List.of(observation(dimension, state)), List.of()));
            return eventId;
        }

        EventId correctEvidence(EventId target, int revision, AttemptId attempt, QualityDimension dimension,
                                ObservationState state) {
            Instant t = advance();
            EventId eventId = id("corr");
            EventEnvelope outer = new EventEnvelope(eventId, SCOPE, task, id, Optional.of(attempt), revision,
                    Optional.of(target), t, t);
            record(new Correction(outer, new EvidenceRecorded(
                    EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t),
                    List.of(observation(dimension, state)), List.of()), "re-run"));
            return eventId;
        }

        EventId finish(AttemptId attempt, AttemptResult result) {
            Instant t = advance();
            EventId eventId = id("fin");
            record(new AttemptFinished(EventEnvelope.original(eventId, SCOPE, task, id, attempt, t, t), result,
                    Optional.of("solution"), Optional.of("lesson")));
            return eventId;
        }

        long outcome(Outcome outcome) {
            return record(ExecutionEvent.outcomeRecorded(id("out"), SCOPE, task, id, outcome, advance()));
        }
    }

    Exec exec(String execution) {
        return new Exec(execution, Set.of(QualityDimension.TESTS));
    }

    static QualityObservation observation(QualityDimension dimension, ObservationState state) {
        return new QualityObservation(dimension, state, OptionalDouble.empty(), Optional.empty(), "junit", "5", "gates",
                "1", ObservationSource.TOOL, List.of(ArtifactRef.of("report", "r-" + dimension)),
                state == ObservationState.NOT_APPLICABLE ? Optional.of("no tests apply") : Optional.empty());
    }

    static UsageCounter reported(UsageKind kind, long value) {
        return UsageCounter.reported(kind, value, "provider");
    }

    /** Records a validated, accepted single-stage execution. */
    Exec accepted(String execution) {
        Exec exec = exec(execution).attempt(A1, 1, Optional.empty());
        exec.routedStage(A1, envelope(execution, "a1", "implement"), reported(UsageKind.INPUT_TOKENS, 1000),
                UsageCounter.unknown(UsageKind.OUTPUT_TOKENS, "provider"));
        exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        exec.finish(A1, AttemptResult.COMPLETED);
        exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return exec;
    }

    /** Records a validated rejection. */
    Exec rejected(String execution) {
        Exec exec = exec(execution).attempt(A1, 1, Optional.empty());
        exec.routedStage(A1, envelope(execution, "a1", "implement"));
        exec.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
        exec.finish(A1, AttemptResult.COMPLETED);
        exec.outcome(Outcome.rejected(OutcomeOrigin.VALIDATED));
        return exec;
    }

    /** SHA-256 of every file below the root, keyed by relative path in a deterministic order. */
    static Map<String, String> digests(Path root) {
        Map<String, String> digests = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                digests.put(root.relativize(file).toString(),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return digests;
    }
}
