package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DialogContext.Advice;
import monada.neuron.evaluation.knowledge.DialogContext.PriorTurn;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Assembled;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Hold;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Kind;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Statement;
import monada.neuron.evaluation.knowledge.HtmlDialogCases.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host context rules of the multi-turn suite (Issue #113), on hand-built contexts; no Store. */
class DialogContextAssemblerTest {

    private static final String DIALOG = "dialog:test";
    private static final DialogContextAssembler ASSEMBLER = new DialogContextAssembler();

    @Test
    void anInvalidContextVersionIsRejected() {
        var context = new DialogContext("dialog-context-v0", DIALOG, turn(1, Family.LIST, "set:orderMatters=yes"),
                List.of());
        var held = held(ASSEMBLER.assemble(context), Hold.REJECTED);
        assertTrue(held.detail().contains("dialog-context-v0"), held.detail());
    }

    @Test
    void anUnexpectedTurnCountIsRejected() {
        var fifth = new DialogContext(DialogContext.VERSION, DIALOG, turn(5, null, "-"), List.of());
        assertTrue(held(ASSEMBLER.assemble(fifth), Hold.REJECTED).detail().startsWith("unexpected turn count"));
        var priors = new ArrayList<PriorTurn>();
        for (var number = 1; number <= 4; number++) {
            priors.add(prior(turn(number, Family.LIST, "-")));
        }
        var tooMany = new DialogContext(DialogContext.VERSION, DIALOG, turn(4, null, "-"), priors);
        held(ASSEMBLER.assemble(tooMany), Hold.REJECTED);
    }

    @Test
    void aTurnOfAnotherDialogOrOutOfOrderIsRejectedNotUsed() {
        var foreign = new Turn("dialog:other", 1, Optional.of(Family.LIST), "Order matters.",
                statements("set:orderMatters=yes"), List.of(), List.of(), Optional.empty(), List.of());
        var leaked = new DialogContext(DialogContext.VERSION, DIALOG, turn(2, null, "-"), List.of(prior(foreign)));
        assertTrue(held(ASSEMBLER.assemble(leaked), Hold.REJECTED).detail().contains("dialog:other"));
        var foreignAlone = new DialogContext(DialogContext.VERSION, DIALOG, foreign, List.of());
        assertTrue(held(ASSEMBLER.assemble(foreignAlone), Hold.REJECTED).detail().contains("current turn"),
                "a foreign first turn with an empty history is rejected too");
        var reversed = new DialogContext(DialogContext.VERSION, DIALOG, turn(3, null, "-"),
                List.of(prior(turn(2, Family.LIST, "-")), prior(turn(1, Family.LIST, "-"))));
        held(ASSEMBLER.assemble(reversed), Hold.REJECTED);
        var same = new DialogContext(DialogContext.VERSION, DIALOG, turn(2, null, "-"),
                List.of(prior(turn(2, Family.LIST, "-"))));
        held(ASSEMBLER.assemble(same), Hold.REJECTED);
    }

    @Test
    void aContextAboveTheByteBudgetIsExhaustedNotTruncated() {
        var long1 = new Turn(DIALOG, 1, Optional.of(Family.LIST), "a".repeat(400), statements("set:orderMatters=yes"),
                List.of(), List.of(), Optional.empty(), List.of());
        var long2 = new Turn(DIALOG, 2, Optional.empty(), "b".repeat(400), List.of(), List.of(), List.of(1),
                Optional.empty(), List.of());
        var current = new Turn(DIALOG, 3, Optional.empty(), "c".repeat(300), List.of(), List.of(), List.of(1, 2),
                Optional.empty(), List.of());
        var context = new DialogContext(DialogContext.VERSION, DIALOG, current, List.of(prior(long1),
                prior(long2)));
        var held = held(ASSEMBLER.assemble(context), Hold.BUDGET_EXHAUSTED);
        assertTrue(held.detail().contains(Integer.toString(DialogContextAssembler.MAX_CONTEXT_BYTES)));
        var alone = ASSEMBLER.assemble(DialogContext.alone(current));
        assertEquals(Hold.NO_FAMILY, ((Held) alone).hold(), "the same turn alone fits the budget");
    }

    @Test
    void contradictingConstraintsAskInsteadOfGuessing() {
        var first = turn(1, Family.LIST, "set:orderMatters=no");
        var second = turn(2, null, "set:orderMatters=yes");
        var held = held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, second,
                List.of(prior(first)))), Hold.CONTRADICTION);
        assertTrue(held.question().contains(Constraint.ORDER_MATTERS.question()), held.question());
        var inOneTurn = turn(1, Family.LIST, "set:orderMatters=yes|change:orderMatters=no");
        held(ASSEMBLER.assemble(DialogContext.alone(inOneTurn)), Hold.CONTRADICTION);
    }

    @Test
    void anExplicitChangeRevisesTheValueWithItsProvenance() {
        var first = turn(1, Family.LIST, "set:orderMatters=yes");
        var second = turn(2, null, "change:orderMatters=no");
        var repeated = turn(3, null, "set:orderMatters=no");
        var ready = assertInstanceOf(Ready.class, ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG,
                repeated, List.of(prior(first), prior(second)))));
        assertEquals(Tri.NO, ready.decisionCase().value(Constraint.ORDER_MATTERS));
        assertEquals(List.of("orderMatters=no@dialog:test#3"), ready.provenance());
        assertEquals(List.of(1, 2), ready.used());
        assertEquals(Family.LIST.choices(), ready.decisionCase().candidates());
        assertTrue(ready.decisionCase().requirement().startsWith("turn 3 "), "the current turn leads the query");
    }

    @Test
    void aFamilySwitchCarriesNothingOfTheOtherFamily() {
        var list = turn(1, Family.LIST, "set:orderMatters=yes");
        var script = turn(2, Family.SCRIPT, "set:moduleCode=yes");
        var ready = assertInstanceOf(Ready.class, ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG,
                script, List.of(prior(list)))));
        assertEquals(Family.SCRIPT, ready.decisionCase().family());
        assertEquals(List.of(), ready.used());
        assertTrue(ready.provenance().stream().allMatch(source -> source.endsWith("#2")), ready.provenance()
                .toString());
        assertFalse(ready.decisionCase().requirement().contains("turn 1"));
    }

    @Test
    void aStatementOrCandidateOfAFamilyThatWasNotCarriedIsRejected() {
        var list = turn(1, Family.LIST, "set:orderMatters=yes");
        for (var statements : List.of("set:moduleCode=no", "change:moduleCode=yes")) {
            var script = new Turn(DIALOG, 3, Optional.empty(), "And if we cannot add files?", statements(statements),
                    List.of(), List.of(1), Optional.empty(), List.of());
            var held = held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, script,
                    List.of(prior(list)))), Hold.REJECTED);
            assertTrue(held.detail().contains("moduleCode") && held.detail().contains("list"), held.detail());
        }
        var candidates = new Turn(DIALOG, 3, Optional.empty(), "Inline or external?", List.of(),
                List.of("inline", "external"), List.of(1), Optional.empty(), List.of());
        assertTrue(held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, candidates,
                List.of(prior(list)))), Hold.REJECTED).detail().contains("candidates"));
    }

    @Test
    void aRequirementOutsideTheRubricStaysUnsupportedOnLaterTurns() {
        var first = turn(1, Family.EMBED, "set:separateDocument=yes");
        var security = new Turn(DIALOG, 2, Optional.empty(), "The partner is untrusted.", List.of(), List.of(),
                List.of(1), Optional.empty(), List.of("untrustedThirdParty"));
        var camera = turn(3, null, "set:powerfulFeatures=yes");
        var held = held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, camera,
                List.of(prior(first), prior(security)))), Hold.UNSUPPORTED);
        assertTrue(held.detail().contains("untrustedThirdParty@dialog:test#2"), held.detail());
    }

    @Test
    void aDialogReturningToAFamilyKeepsItsEarlierTurns() {
        var list = turn(1, Family.LIST, "set:orderMatters=yes");
        var script = turn(2, Family.SCRIPT, "set:moduleCode=no");
        var again = turn(3, Family.LIST, "-");
        var ready = assertInstanceOf(Ready.class, ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG,
                again, List.of(prior(list), prior(script)))));
        assertEquals(List.of(1), ready.used());
        assertEquals(List.of("orderMatters=yes@dialog:test#1"), ready.provenance());
        var contradicting = turn(3, Family.LIST, "set:orderMatters=no");
        held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, contradicting,
                List.of(prior(list), prior(script)))), Hold.CONTRADICTION);
    }

    @Test
    void anEllipsisWithoutAntecedentOrFamilyAsks() {
        var orphan = turn(1, null, "-");
        var held = held(ASSEMBLER.assemble(DialogContext.alone(orphan)), Hold.NO_FAMILY);
        assertEquals(DialogContextAssembler.WHICH_CHOICE, held.question());
        var first = turn(1, Family.EMBED, "set:separateDocument=no");
        var second = turn(2, null, "-");
        var third = new Turn(DIALOG, 3, Optional.empty(), "turn 3", List.of(), List.of(), List.of(2), Optional.of(1),
                List.of());
        held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, third, List.of(prior(second)))),
                Hold.MISSING_ANTECEDENT);
        assertInstanceOf(Ready.class, ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, third,
                List.of(prior(first), prior(second)))));
    }

    @Test
    void aRequirementOutsideTheRubricIsUnsupported() {
        var first = turn(1, Family.EMBED, "set:separateDocument=yes");
        var security = new Turn(DIALOG, 2, Optional.empty(), "What about security?", List.of(), List.of(),
                List.of(1), Optional.empty(), List.of("untrustedThirdParty"));
        var held = held(ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, security,
                List.of(prior(first)))), Hold.UNSUPPORTED);
        assertEquals(Optional.of(Family.EMBED), held.family());
    }

    @Test
    void adviceIsReportedButNeverDecides() {
        var first = turn(1, Family.LIST, "set:orderMatters=yes");
        var second = turn(2, null, "-");
        var without = ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, second,
                List.of(prior(first))));
        var with = ASSEMBLER.assemble(new DialogContext(DialogContext.VERSION, DIALOG, second, List.of(
                new PriorTurn(first, Optional.of(new Advice("DECIDED", List.of("ul"), "L2"))))));
        assertEquals(without, with);
        assertEquals(new DialogContext(DialogContext.VERSION, DIALOG, second, List.of(prior(first))),
                new DialogContext(DialogContext.VERSION, DIALOG, second, List.of(new PriorTurn(first,
                        Optional.of(new Advice("DECIDED", List.of("ul"), "L2"))))).withoutAdvice());
    }

    @Test
    void theContextIsImmutable() {
        var priors = new ArrayList<>(List.of(prior(turn(1, Family.LIST, "set:orderMatters=yes"))));
        var context = new DialogContext(DialogContext.VERSION, DIALOG, turn(2, null, "-"), priors);
        priors.clear();
        assertEquals(List.of(1), context.carriedTurns());
        var advice = new HashMap<Integer, Advice>();
        var dialog = new HtmlDialogCases.Dialog(DIALOG, List.of(turn(1, Family.LIST, "-"), turn(2, null, "-")));
        var built = DialogContext.carried(dialog, dialog.turns().get(1), advice);
        advice.put(1, new Advice("DECIDED", List.of("ol"), "L1"));
        assertEquals(Optional.empty(), built.priors().getFirst().advice());
    }

    private static Held held(Assembled assembled, Hold hold) {
        var held = assertInstanceOf(Held.class, assembled);
        assertEquals(hold, held.hold(), held.detail());
        return held;
    }

    private static PriorTurn prior(Turn turn) {
        return new PriorTurn(turn, Optional.empty());
    }

    private static Turn turn(int number, Family family, String statements) {
        var carried = new ArrayList<Integer>();
        for (var earlier = 1; earlier < number && earlier <= 4; earlier++) {
            carried.add(earlier);
        }
        return new Turn(DIALOG, number, Optional.ofNullable(family), "turn " + number, statements(statements),
                List.of(), carried, Optional.empty(), List.of());
    }

    private static List<Statement> statements(String rendered) {
        if (rendered.equals("-")) {
            return List.of();
        }
        var statements = new ArrayList<Statement>();
        for (var item : rendered.split("\\|")) {
            var kind = item.startsWith("set:") ? Kind.SET : Kind.CHANGE;
            var parts = item.substring(item.indexOf(':') + 1).split("=");
            statements.add(new Statement(kind, Constraint.ofLabel("test", parts[0]), Tri.ofLabel("test", parts[1])));
        }
        return statements;
    }
}
