package consumer.host;

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
import monada.neuron.resonance.adapter.experience.RoutingEnvelope;
import monada.neuron.routing.features.Feature;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The host's explicit Store write: one routed execution is appended through the published Store API, and the memory
 * is closed again before Neuron reads anything. Neuron never writes to the Store; the adapter only reads exports.
 *
 * <p>Timestamps are caller-supplied ({@code at}); nothing reads the wall clock.
 */
final class StoreRecorder {

    private final Path root;
    private final ScopeId scope;

    StoreRecorder(Path root, String scope) {
        this.root = root;
        this.scope = ScopeId.of(scope);
    }

    /**
     * Opens the scope for writing, appends the routed execution, and closes it.
     *
     * @param envelope the routing envelope the host published for this execution
     * @param envelopeRef the host's opaque reference to that envelope
     * @param report the worker's evidence and the host's acceptance judgment
     * @param at caller-supplied time of the first event; later events are one second apart
     * @return the Store sequence of the last appended event
     */
    long record(RoutingEnvelope envelope, String envelopeRef, WorkerReport report, Instant at) {
        var ref = envelope.decisionRef();
        var task = TaskId.of(ref.taskId());
        var execution = ExecutionId.of(ref.executionId());
        var attempt = AttemptId.of(ref.attemptId());
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
            var events = new Events(memory, execution.value(), at);
            events.append(ExecutionEvent.executionStarted(events.nextId(), scope, task, execution,
                    new SourceProvenance(EmbeddedRoutingHost.SOURCE, EmbeddedRoutingHost.CONTEXT,
                            EmbeddedRoutingHost.CONSTRAINTS),
                    new EvaluationPolicy(EmbeddedRoutingHost.EVALUATION_POLICY_ID,
                            EmbeddedRoutingHost.EVALUATION_POLICY_VERSION,
                            Set.of(QualityDimension.TESTS, QualityDimension.SECURITY)),
                    "Route " + execution.value(), events.next()));
            Instant t = events.next();
            events.append(new AttemptStarted(EventEnvelope.original(events.nextId(), scope, task, execution, attempt, t, t),
                    1, Optional.empty(), AttemptReason.INITIAL));
            t = events.next();
            events.append(StageRecorded.measured(
                    EventEnvelope.original(events.nextId(), scope, task, execution, attempt, t, t), ref.stageId(),
                    storeDescriptor(envelope), t, t, List.of(),
                    List.of(ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, envelopeRef, envelope.digest()))));
            t = events.next();
            events.append(new EvidenceRecorded(
                    EventEnvelope.original(events.nextId(), scope, task, execution, attempt, t, t),
                    List.of(quality(QualityDimension.TESTS, report.tests()),
                            quality(QualityDimension.SECURITY, report.security())),
                    List.of()));
            t = events.next();
            events.append(new AttemptFinished(
                    EventEnvelope.original(events.nextId(), scope, task, execution, attempt, t, t),
                    AttemptResult.COMPLETED, Optional.of("fake worker result"), Optional.empty()));
            Outcome outcome = report.accepted()
                    ? Outcome.accepted(OutcomeOrigin.VALIDATED, attempt)
                    : Outcome.rejected(OutcomeOrigin.VALIDATED);
            return events.append(ExecutionEvent.outcomeRecorded(events.nextId(), scope, task, execution, outcome,
                    events.next()));
        }
    }

    /** The Store-side descriptor of the executed route: the same worker facts the envelope carries. */
    private static RouteDescriptor storeDescriptor(RoutingEnvelope envelope) {
        var executed = envelope.descriptor();
        return new RouteDescriptor(executed.worker(), optional(executed.provider()), optional(executed.model()),
                optional(executed.effort()), BillingMode.valueOf(executed.billingMode().name()));
    }

    private static Optional<String> optional(Feature<String> feature) {
        return feature instanceof Feature.Known<String> known ? Optional.of(known.value()) : Optional.empty();
    }

    private static QualityObservation quality(QualityDimension dimension, ObservationState state) {
        return new QualityObservation(dimension, state, OptionalDouble.empty(), Optional.empty(), "fake-runner", "1",
                EmbeddedRoutingHost.EVALUATION_POLICY_ID, EmbeddedRoutingHost.EVALUATION_POLICY_VERSION,
                ObservationSource.TOOL, List.of(ArtifactRef.of("report", "report-" + dimension.name().toLowerCase())),
                Optional.empty());
    }

    /** Appends events with deterministic ids and caller-derived times, failing on anything but a fresh append. */
    private static final class Events {
        private final ExecutionMemory memory;
        private final String prefix;
        private final Instant base;
        private int count;
        private int seconds;

        Events(ExecutionMemory memory, String prefix, Instant base) {
            this.memory = memory;
            this.prefix = prefix;
            this.base = base;
        }

        EventId nextId() {
            return EventId.of(prefix + "-event-" + (++count));
        }

        Instant next() {
            return base.plusSeconds(seconds++);
        }

        long append(ExecutionEvent event) {
            RecordResult result = memory.record(event);
            if (result.status() != RecordResult.Status.APPENDED) {
                throw new IllegalStateException("Store did not append " + event + ": " + result);
            }
            return result.sequence();
        }
    }
}
