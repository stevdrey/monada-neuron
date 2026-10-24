package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDialogCases.Dialog;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The explicit, immutable conversation context a host supplies for one turn of the multi-turn suite (Issue #113),
 * version {@value #VERSION}. It is evaluation-only host metadata: the earlier user turns the host chose to carry, each
 * with the advice the system under test gave for it, and the current turn. It is not authorization, not a Neuron
 * session and not memory: Neuron runs one request-scoped cycle per turn and keeps nothing between turns, and the
 * Store is only read. It never holds a gold label.
 *
 * @param version the context contract version; the assembler rejects any other
 * @param dialogId the dialog every turn must belong to
 * @param current the turn being answered
 * @param priors the carried earlier turns, in turn order
 */
record DialogContext(String version, String dialogId, Turn current, List<PriorTurn> priors) {

    /** Version of the context contract. */
    static final String VERSION = "dialog-context-v1";

    DialogContext {
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(dialogId, "dialogId must not be null");
        Objects.requireNonNull(current, "current must not be null");
        priors = List.copyOf(priors);
    }

    /**
     * What the system under test answered for an earlier turn, as the host recorded it.
     *
     * @param outcome the outcome name
     * @param stated the choices the answer stated as valid, in answer order
     * @param ruleId the rule or context rule that produced it
     */
    record Advice(String outcome, List<String> stated, String ruleId) {

        Advice {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            stated = List.copyOf(stated);
        }

        String render() {
            return outcome + (stated.isEmpty() ? "" : " " + String.join("|", stated)) + " by " + ruleId;
        }
    }

    /**
     * One carried earlier turn.
     *
     * @param turn the user turn as stated
     * @param advice the system's answer to it, when the host has one
     */
    record PriorTurn(Turn turn, Optional<Advice> advice) {

        PriorTurn {
            Objects.requireNonNull(turn, "turn must not be null");
            Objects.requireNonNull(advice, "advice must not be null");
        }
    }

    /**
     * Builds the context the host supplies for {@code current}: the earlier turns its {@code carried} column names,
     * each with the advice recorded for it in {@code advice}.
     */
    static DialogContext carried(Dialog dialog, Turn current, Map<Integer, Advice> advice) {
        var priors = new ArrayList<PriorTurn>();
        for (var earlier : current.carried()) {
            priors.add(new PriorTurn(dialog.turns().get(earlier - 1), Optional.ofNullable(advice.get(earlier))));
        }
        return new DialogContext(VERSION, dialog.dialogId(), current, priors);
    }

    /** Builds the no-context control: the current turn alone. */
    static DialogContext alone(Turn current) {
        return new DialogContext(VERSION, current.dialogId(), current, List.of());
    }

    /** Returns this context with the advice of every carried turn removed: what the assembler decides from. */
    DialogContext withoutAdvice() {
        return new DialogContext(version, dialogId, current, priors.stream()
                .map(prior -> new PriorTurn(prior.turn(), Optional.empty())).toList());
    }

    /** Returns the carried turn numbers, in turn order. */
    List<Integer> carriedTurns() {
        return priors.stream().map(prior -> prior.turn().turn()).toList();
    }

    /** Renders the carried context for traces: each turn's statements and the advice recorded for it. */
    String render() {
        return priors.isEmpty() ? "-" : String.join("|", priors.stream().map(prior -> prior.turn().turn() + ":"
                + prior.turn().renderStatements().replace("|", ",") + prior.advice().map(advice -> " => "
                        + advice.render().replace("|", ",")).orElse("")).toList());
    }
}
