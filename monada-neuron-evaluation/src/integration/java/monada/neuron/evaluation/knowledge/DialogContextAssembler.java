package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Kind;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Turn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fixed, evaluation-only <b>host context assembler</b> {@value #VERSION} of the multi-turn suite (Issue #113). It
 * turns an explicit {@link DialogContext} into the single-turn {@link DecisionCase} the unchanged #112 selector
 * decides, or holds the turn with a typed reason instead of inventing an assumption. It is test-harness code, not
 * Neuron cognition and not a chat session: it keeps no state between calls, and its rules, printed verbatim by
 * {@link #RULES}, were written by hand.
 *
 * <p>It reads only the context: the carried user turns with their typed statements and the recorded advice. Advice is
 * never used to decide; it is reported. No gold label, Store or corpus is consulted. Cost: one pass over at most
 * {@value #MAX_TURNS} turns of at most a few statements each.
 */
final class DialogContextAssembler {

    /** Version of the rule table; any change to a rule or bound is a new version. */
    static final String VERSION = "host-context-v1";

    /** Most turns a context may reach; equal to the dialog bound. */
    static final int MAX_TURNS = HtmlDialogCases.MAX_TURNS;

    /** Largest context, in UTF-8 bytes of the carried and current utterances and statements. */
    static final int MAX_CONTEXT_BYTES = 1024;

    /** The clarifying question when no family can be resolved. */
    static final String WHICH_CHOICE = "Which HTML choice do you mean: div or iframe, ol or ul, or how to include a"
            + " script?";

    /** Why a turn was held instead of being decided. */
    enum Hold {
        /** The context breaks its contract: version, dialog, turn order or count. */
        REJECTED,
        /** The context is larger than {@link #MAX_CONTEXT_BYTES}; nothing is truncated silently. */
        BUDGET_EXHAUSTED,
        /** The ellipsis refers to an earlier turn the host did not carry. */
        MISSING_ANTECEDENT,
        /** Neither the turn nor the carried context names a family. */
        NO_FAMILY,
        /** A {@code set} statement contradicts a value stated before without marking a change. */
        CONTRADICTION,
        /** The turn adds a requirement outside the html-decision-v1 rubric. */
        UNSUPPORTED;

        /** The rule id a held turn reports as its producing rule. */
        String ruleId() {
            return "context:" + name();
        }
    }

    /** The assembled turn. */
    sealed interface Assembled {

        /** Returns the retrieval query of the turn, by the #112 query rule. */
        String query();

        /** Returns the carried turn numbers the assembler used, in turn order. */
        List<Integer> used();
    }

    /**
     * A turn ready for the selector.
     *
     * @param decisionCase the single-turn case: resolved family, constraints and candidates, and the requirement text
     * @param used the carried turns of the resolved family
     * @param provenance every known constraint value with the turn that stated it, as {@code name=value@turnId}
     */
    record Ready(DecisionCase decisionCase, List<Integer> used, List<String> provenance) implements Assembled {

        Ready {
            Objects.requireNonNull(decisionCase, "decisionCase must not be null");
            used = List.copyOf(used);
            provenance = List.copyOf(provenance);
        }

        @Override
        public String query() {
            return decisionCase.query();
        }
    }

    /**
     * A turn held with a reason.
     *
     * @param hold the reason
     * @param detail what happened
     * @param question the clarifying question, or {@code "-"}
     * @param family the resolved family, when known
     * @param query the retrieval query of what is known of the turn
     * @param used the carried turns used
     */
    record Held(Hold hold, String detail, String question, Optional<Family> family, String query, List<Integer> used)
            implements Assembled {

        Held {
            Objects.requireNonNull(hold, "hold must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(query, "query must not be null");
            used = List.copyOf(used);
        }

        /** Returns the host abstention reported for the held turn. */
        DecisionRecommendation.Abstained abstention() {
            return new DecisionRecommendation.Abstained(hold + ": " + detail, question, hold.ruleId());
        }
    }

    /** The rules, in application order, printed verbatim in every report. */
    static final List<String> RULES = List.of(
            "C1 version: a context whose version is not " + DialogContext.VERSION + " is REJECTED",
            "C2 count: a current turn above " + MAX_TURNS + " or more than " + (MAX_TURNS - 1) + " carried turns is"
                    + " REJECTED",
            "C3 scope: the current turn and every carried turn must belong to the context's dialog, and carried turns"
                    + " must come before the current turn in ascending order without repeats, or the context is"
                    + " REJECTED (no cross-dialog carry-over)",
            "C4 budget: when the UTF-8 bytes of the carried and current utterances and statements exceed "
                    + MAX_CONTEXT_BYTES + " the turn is BUDGET_EXHAUSTED; nothing is truncated",
            "C5 antecedent: an ellipsis whose antecedent turn was not carried is MISSING_ANTECEDENT and asks",
            "C6 family: the current turn's family, else that of the latest carried turn naming one; a turn naming"
                    + " another family than the carried one starts afresh and carries nothing of the other family;"
                    + " no family is NO_FAMILY and asks",
            "C7 constraints: every constraint of the family starts unknown; the statements of the carried turns of"
                    + " that family, then of the current turn, apply in order; change: overrides, set: on an already"
                    + " known different value, or two statements of one constraint in one turn, is CONTRADICTION and"
                    + " asks which holds",
            "C8 rubric: a current turn naming a requirement outside the html-decision-v1 constraints is UNSUPPORTED",
            "C9 case: candidates are the current turn's, else the latest carried, else the family's; the requirement"
                    + " is the current utterance then the carried utterances newest first; the query is the #112"
                    + " query rule over them; advice is reported and never used to decide");

    /** Assembles {@code context}. */
    Assembled assemble(DialogContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var current = context.current();
        var used = context.carriedTurns();
        var bare = HtmlDecisionCases.query(current.candidates(), current.utterance());
        if (!context.version().equals(DialogContext.VERSION)) {
            return held(Hold.REJECTED, "context version '" + context.version() + "' is not " + DialogContext.VERSION,
                    "-", Optional.empty(), bare, used);
        }
        if (current.turn() < 1 || current.turn() > MAX_TURNS || context.priors().size() > MAX_TURNS - 1) {
            return held(Hold.REJECTED, "unexpected turn count: turn " + current.turn() + " with "
                    + context.priors().size() + " carried turns, at most " + MAX_TURNS + " turns", "-",
                    Optional.empty(), bare, used);
        }
        if (!current.dialogId().equals(context.dialogId())) {
            return held(Hold.REJECTED, "the current turn " + current.turnId() + " is not a turn of "
                    + context.dialogId(), "-", Optional.empty(), bare, used);
        }
        var previous = 0;
        for (var prior : context.priors()) {
            var turn = prior.turn();
            if (!turn.dialogId().equals(context.dialogId())) {
                return held(Hold.REJECTED, "turn " + turn.turnId() + " is not a turn of " + context.dialogId(), "-",
                        Optional.empty(), bare, used);
            }
            if (turn.turn() <= previous || turn.turn() >= current.turn()) {
                return held(Hold.REJECTED, "carried turn " + turn.turn() + " is out of order before turn "
                        + current.turn(), "-", Optional.empty(), bare, used);
            }
            previous = turn.turn();
        }
        var bytes = bytes(current) + context.priors().stream().mapToInt(prior -> bytes(prior.turn())).sum();
        if (bytes > MAX_CONTEXT_BYTES) {
            return held(Hold.BUDGET_EXHAUSTED, "the context holds " + bytes + " bytes, above the "
                    + MAX_CONTEXT_BYTES + "-byte budget", "-", Optional.empty(), bare, used);
        }
        if (current.antecedent().isPresent() && !used.contains(current.antecedent().get())) {
            return held(Hold.MISSING_ANTECEDENT, "the turn refers to turn " + current.antecedent().get()
                    + ", which the host did not carry", "Which earlier request do you mean? Please restate it.",
                    Optional.empty(), bare, used);
        }

        // C6: the carried turns of the active family, restarted whenever a turn names another family.
        Optional<Family> active = Optional.empty();
        var segment = new ArrayList<Turn>();
        for (var prior : context.priors()) {
            var turn = prior.turn();
            if (turn.family().isPresent() && !turn.family().equals(active)) {
                active = turn.family();
                segment.clear();
            }
            if (active.isPresent()) {
                segment.add(turn);
            }
        }
        if (current.family().isPresent() && !current.family().equals(active)) {
            active = current.family();
            segment.clear();
        }
        var segmentUsed = segment.stream().map(Turn::turn).toList();
        var requirement = new StringBuilder(current.utterance());
        for (var index = segment.size() - 1; index >= 0; index--) {
            requirement.append(' ').append(segment.get(index).utterance());
        }
        if (active.isEmpty()) {
            return held(Hold.NO_FAMILY, "neither the turn nor the carried context names an HTML choice",
                    WHICH_CHOICE, Optional.empty(), HtmlDecisionCases.query(List.of(), requirement.toString()),
                    segmentUsed);
        }
        var family = active.get();
        var candidates = current.candidates();
        for (var index = segment.size() - 1; candidates.isEmpty() && index >= 0; index--) {
            candidates = segment.get(index).candidates();
        }
        if (candidates.isEmpty()) {
            candidates = family.choices();
        }
        var query = HtmlDecisionCases.query(candidates, requirement.toString());

        // C7: apply the statements in turn order.
        var values = new EnumMap<Constraint, Tri>(Constraint.class);
        var sources = new EnumMap<Constraint, String>(Constraint.class);
        family.constraints().forEach(constraint -> values.put(constraint, Tri.UNKNOWN));
        var ordered = new ArrayList<>(segment);
        ordered.add(current);
        for (var turn : ordered) {
            var seen = new HashSet<Constraint>();
            for (var statement : turn.statements()) {
                var constraint = statement.constraint();
                var before = values.get(constraint);
                if (!seen.add(constraint) || (statement.kind() == Kind.SET && before != Tri.UNKNOWN
                        && before != statement.value())) {
                    var earlier = sources.getOrDefault(constraint, turn.turnId());
                    return held(Hold.CONTRADICTION, earlier + " stated " + constraint.label() + "=" + before.label()
                            + " and " + turn.turnId() + " states " + constraint.label() + "=" + statement.value()
                            .label() + " without marking a change", "Which holds: " + constraint.question(),
                            active, query, segmentUsed);
                }
                values.put(constraint, statement.value());
                sources.put(constraint, turn.turnId());
            }
        }
        if (!current.unmodeled().isEmpty()) {
            return held(Hold.UNSUPPORTED, "the turn adds " + current.unmodeled() + ", which no "
                    + HtmlDecisionCases.VERSION + " constraint or " + DecisionRuleBaseline.VERSION + " rule covers",
                    "-", active, query, segmentUsed);
        }
        var provenance = family.constraints().stream().filter(sources::containsKey)
                .map(constraint -> constraint.label() + "=" + values.get(constraint).label() + "@"
                        + sources.get(constraint)).toList();
        return new Ready(new DecisionCase(current.turnId(), family, List.of(), requirement.toString(), values,
                candidates), segmentUsed, provenance);
    }

    private static int bytes(Turn turn) {
        return (turn.utterance() + turn.renderStatements()).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Held held(Hold hold, String detail, String question, Optional<Family> family, String query,
            List<Integer> used) {
        return new Held(hold, detail, question, family, query, used);
    }
}
