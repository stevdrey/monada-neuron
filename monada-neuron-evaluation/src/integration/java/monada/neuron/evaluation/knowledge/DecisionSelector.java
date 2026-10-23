package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;

import java.util.List;

/**
 * An explicit decision selector (Issue #112): it decides one case from the case's requirement, constraints and
 * candidates and from the evidence one arm retrieved, and from nothing else. A selector never reads gold labels, the
 * corpus or the Store, and never throws for an empty evidence set.
 */
interface DecisionSelector {

    /** What kind of selector produced a decision, so a report can never present a test double as a result. */
    enum Kind {
        /** The deterministic, evaluation-only rule baseline owned by the test host; scored. */
        HOST_RULE_BASELINE,
        /** A canned test double; used only by tests, never scored as a result. */
        TEST_DOUBLE
    }

    /** Returns the report name. */
    String name();

    /** Returns its version and parameters. */
    String description();

    /** Returns its kind. */
    Kind kind();

    /** Decides {@code decisionCase} from {@code evidence} alone. */
    DecisionRecommendation decide(DecisionCase decisionCase, List<EvidenceChunk> evidence);
}
