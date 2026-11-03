package monada.neuron.evaluation.routing;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.features.FeatureDimension;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the scripted host saw and did for one decision, in replay order. The disposition is kept here and passed to
 * nothing else: it never reaches feedback derivation or the adaptive state.
 *
 * @param taskIndex the task's position in the fixture
 * @param stageKind the routed stage kind
 * @param kind what the policy returned
 * @param abstain why it abstained, exactly for an abstention
 * @param receiptSha256 SHA-256 of the decision's {@code routing-advice-receipt/1} bytes
 * @param receiptBytes size of those bytes
 * @param missingForHostPreview required preview dimensions the task's features leave unknown
 * @param action what the person chose
 * @param disposition the bound or rejected disposition; empty only when the person chose an alternative and the
 *        decision names no route at all to propose
 * @param execution what the host ran
 */
record HostDecisionRecord(
        int taskIndex,
        String stageKind,
        ReplaySample.DecisionKind kind,
        Optional<AbstainReason> abstain,
        String receiptSha256,
        int receiptBytes,
        List<FeatureDimension> missingForHostPreview,
        HostAction action,
        Optional<DispositionResult> disposition,
        HostExecution execution) {

    /** Validates and copies. */
    HostDecisionRecord {
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(abstain, "abstain must not be null");
        Objects.requireNonNull(receiptSha256, "receiptSha256 must not be null");
        missingForHostPreview = List.copyOf(missingForHostPreview);
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        if ((kind == ReplaySample.DecisionKind.ABSTAINED) != abstain.isPresent()) {
            throw new IllegalArgumentException("an abstention reason is recorded exactly for an abstention");
        }
        if (kind != ReplaySample.DecisionKind.SELECTED && !(execution instanceof HostExecution.NotExecuted)) {
            throw new IllegalArgumentException("only a selection executes");
        }
    }
}
