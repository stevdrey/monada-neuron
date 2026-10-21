package monada.neuron.evaluation.knowledge;

import monada.neuron.context.CognitiveContext;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.MemoryReferenceEvidence;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The evaluation-only consumer of {@link ResonanceMemoryStageResult} in the evidence-trace experiment (Issue #108). It
 * is the explicit, documented component the protocol requires before a {@link MemoryReferenceEvidence} counts as use.
 * It is not a production reasoning stage and does not interpret any text.
 *
 * <p>When the preceding {@code MEMORY_RECALL} result holds admitted recalled results, the stage proposes the single
 * fixed proposition {@link #RECALLED_EVIDENCE} ("the query has recalled evidence") and attaches one
 * {@code MemoryReferenceEvidence(reference, SUPPORTS, 1.0)} per admitted result, in the adapter's canonical order. The
 * weight is constant: the score is never read, so nothing is tuned. With no admitted result, or no memory result, it
 * proposes nothing. It passes every input Signal through unchanged, so recalled Signals stay traceable by value.
 */
final class RecalledEvidenceReasoningStage implements CognitiveStage {

    /** The only proposition this stage proposes. */
    static final Proposition RECALLED_EVIDENCE = new Proposition(0, 0L);

    /** Weight of every attached evidence item. */
    static final double EVIDENCE_WEIGHT = 1.0;

    private final HypothesisLimits limits;

    /** Creates the stage with hypothesis limits large enough for {@code maxEvidence} admitted results. */
    RecalledEvidenceReasoningStage(int maxEvidence) {
        if (maxEvidence < 1) {
            throw new IllegalArgumentException("maxEvidence must be positive, got: " + maxEvidence);
        }
        this.limits = new HypothesisLimits(1, maxEvidence);
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    @Override
    public CognitiveStageResult execute(PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
        return execute(monad, inputSignals, Optional.empty(), context);
    }

    @Override
    public CognitiveStageResult execute(PrimaryMonad monad, List<Signal> inputSignals,
            Optional<CognitiveStageResult> previousResult, CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        Objects.requireNonNull(context, "context must not be null");
        var stableInputs = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        var hypotheses = previousResult.orElse(null) instanceof ResonanceMemoryStageResult memory
                ? hypotheses(memory)
                : HypothesisSet.EMPTY;
        return new ReasoningCognitiveStageResult(CognitiveStageStatus.COMPLETED, stableInputs, hypotheses);
    }

    private HypothesisSet hypotheses(ResonanceMemoryStageResult memory) {
        var admitted = memory.response().results();
        if (admitted.isEmpty()) {
            return HypothesisSet.EMPTY;
        }
        if (admitted.size() > limits.maxEvidencePerCandidate()) {
            throw new IllegalStateException(admitted.size() + " admitted results exceed the evidence bound "
                    + limits.maxEvidencePerCandidate());
        }
        var builder = new HypothesisSetBuilder(limits);
        var sequence = builder.propose(RECALLED_EVIDENCE).orElseThrow();
        for (var result : admitted) {
            builder.addEvidence(sequence,
                    new MemoryReferenceEvidence(result.reference(), EvidenceRelation.SUPPORTS, EVIDENCE_WEIGHT));
        }
        return builder.build();
    }
}
