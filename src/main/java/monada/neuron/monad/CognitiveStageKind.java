package monada.neuron.monad;

import monada.neuron.aeon.AeonPurpose;

import java.util.Optional;

/** Canonical optional stage positions of the deterministic reference cognitive cycle. */
public enum CognitiveStageKind {
    PERCEPTION(AeonPurpose.PERCEPTION),
    MEMORY_RECALL(null),
    REASONING(AeonPurpose.REASONING),
    EVALUATION(AeonPurpose.EVALUATION),
    ADAPTATION(AeonPurpose.EVOLUTION),
    ACTION(AeonPurpose.ACTION);

    private final AeonPurpose aeonPurpose;

    CognitiveStageKind(AeonPurpose aeonPurpose) {
        this.aeonPurpose = aeonPurpose;
    }

    /** Returns the Aeon purpose required by the built-in Aeon stage, when applicable. */
    public Optional<AeonPurpose> aeonPurpose() {
        return Optional.ofNullable(aeonPurpose);
    }
}
