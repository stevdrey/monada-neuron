package monada.neuron.reasoning;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HypothesisHandOffTest {

    private static final HypothesisSet SET = new HypothesisSet(
            List.of(new Hypothesis(0, new Proposition(1, 2L), List.of())), HypothesisLimits.DEFAULT);

    /** An extension result that offers hypotheses without being a ReasoningCognitiveStageResult. */
    private record Extension(HypothesisSet hypotheses) implements CognitiveStageResult, HypothesisHandOff {

        @Override
        public CognitiveStageKind kind() {
            return CognitiveStageKind.REASONING;
        }

        @Override
        public CognitiveStageStatus status() {
            return CognitiveStageStatus.COMPLETED;
        }

        @Override
        public List<Signal> outputSignals() {
            return List.of();
        }
    }

    @Test
    void hypothesesOfReadsAnyHandOff() {
        assertSame(SET, ReasoningCognitiveStageResult.hypothesesOf(Optional.of(new Extension(SET))));
        assertSame(SET, ReasoningCognitiveStageResult.hypothesesOf(Optional.of(
                new ReasoningCognitiveStageResult(CognitiveStageStatus.COMPLETED, List.of(), SET))));
    }

    @Test
    void hypothesesOfIsEmptyWithoutAHandOff() {
        CognitiveStageResult plain = new CognitiveStageResult() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.ACTION;
            }

            @Override
            public CognitiveStageStatus status() {
                return CognitiveStageStatus.COMPLETED;
            }

            @Override
            public List<Signal> outputSignals() {
                return List.of();
            }
        };

        assertEquals(HypothesisSet.EMPTY, ReasoningCognitiveStageResult.hypothesesOf(Optional.of(plain)));
        assertEquals(HypothesisSet.EMPTY, ReasoningCognitiveStageResult.hypothesesOf(Optional.empty()));
    }
}
