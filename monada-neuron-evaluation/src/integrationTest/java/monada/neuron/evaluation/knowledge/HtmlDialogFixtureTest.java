package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Hold;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.HtmlDialogGold.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the {@code html-dialog-v1} fixture and its labels (Issue #113) without a Store. */
class HtmlDialogFixtureTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final HtmlDecisionGold DECISION_GOLD = HtmlDecisionGold.bundledRevision(CASES, CORPUS)
            .orElseThrow();
    private static final HtmlDialogCases DIALOGS = HtmlDialogCases.bundled();
    private static final String TURNS = HtmlKnowledgeCorpus.bundledTable(HtmlDialogCases.VERSION, "turns.tsv");
    private static final String GOLD = HtmlKnowledgeCorpus.bundledTable(HtmlDialogCases.VERSION, "gold.tsv");

    @Test
    void theBundledDialogsAreBoundedAndOrdered() {
        assertEquals(7, DIALOGS.dialogs().size());
        assertEquals(24, DIALOGS.turns().size());
        for (var dialog : DIALOGS.dialogs()) {
            assertTrue(dialog.turns().size() <= HtmlDialogCases.MAX_TURNS, dialog.dialogId());
        }
        assertEquals(DIALOGS.dialogs().stream().map(HtmlDialogCases.Dialog::dialogId).sorted().toList(),
                DIALOGS.dialogs().stream().map(HtmlDialogCases.Dialog::dialogId).toList());
    }

    @Test
    void theReferenceAssemblyCoversEveryHoldAndReadyTurn() {
        var reference = HtmlDialogRunner.reference(DIALOGS);
        var holds = reference.values().stream().filter(Held.class::isInstance).map(Held.class::cast)
                .map(Held::hold).collect(Collectors.toCollection(() -> EnumSet.noneOf(Hold.class)));
        assertEquals(EnumSet.complementOf(EnumSet.of(Hold.REJECTED)), holds);
        assertEquals(Hold.BUDGET_EXHAUSTED, ((Held) reference.get("dialog:lost-context#4")).hold());
        assertEquals(Hold.MISSING_ANTECEDENT, ((Held) reference.get("dialog:lost-context#3")).hold());
        var switched = (Ready) reference.get("dialog:topic-switch#2");
        assertEquals(List.of(), switched.used(), "a new family carries nothing of the list turn");
        assertTrue(reference.values().stream().filter(Ready.class::isInstance).map(Ready.class::cast)
                .allMatch(ready -> HtmlTokens.distinct(ready.query()).size()
                        <= TokenPackingSignalCodec.MAX_QUERY_TOKENS));
    }

    @Test
    void theLabelsLoadReuseTheAdjudicatedGoldAndCoverEveryChange() {
        var gold = gold(GOLD);
        assertEquals(HtmlDialogRunner.FROZEN_GOLD_HASH, gold.hash());
        assertEquals(HtmlDecisionGold.REVISION_VERSION, gold.decisionGoldVersion());
        assertEquals(EnumSet.allOf(Change.class), gold.labels().stream().map(HtmlDialogGold.TurnGold::change)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Change.class))));
        var reused = gold.labels().stream().filter(label -> label.goldRef().isPresent()).toList();
        assertEquals(16, reused.size());
        reused.forEach(label -> assertEquals(DECISION_GOLD.of(label.goldRef().get()), label.gold(), label.turnId()));
        assertEquals(Set.of("claude-opus-5-5"), gold.inlineDrafters());
        assertTrue(gold.labels().stream().filter(label -> DIALOGS.turn(label.turnId()).family().isEmpty())
                .allMatch(label -> label.withoutContext() == HtmlDialogGold.WithoutContext.ABSTAIN));
    }

    @Test
    void aMalformedDialogIsRejected() {
        var fifth = TURNS.stripTrailing() + "\ndialog:script-intro\t5\t-\tAnd one more?\t-\t-\t*\t-\t-\n";
        assertRejected(() -> parse(fifth), "turns exceed");
        var gap = TURNS.replace("dialog:list-order\t3\t", "dialog:list-order\t4\t");
        assertRejected(() -> parse(gap), "numbered");
        var later = TURNS.replace("change:orderMatters=no\t-\t*\t1", "change:orderMatters=no\t-\t3\t1");
        assertRejected(() -> parse(later), "earlier turns only");
        var foreign = TURNS.replace("set:orderMatters=yes\tol|ul\t*\t-\t-\ndialog:list-order",
                "set:moduleCode=no\tol|ul\t*\t-\t-\ndialog:list-order");
        assertRejected(() -> parse(foreign), "constraint of list");
        var orphan = TURNS.replace("dialog:orphan-ellipsis\t1\t-\tAnd if it is external?\t-",
                "dialog:orphan-ellipsis\t1\t-\tAnd if it is external?\tset:moduleCode=no");
        assertRejected(() -> parse(orphan), "need a family");
        var lostFamily = TURNS.replace("new file?\tchange:externalFileAllowed=no\t-\t*\t2\t-",
                "new file?\tchange:externalFileAllowed=no\t-\t1\t-\t-");
        assertRejected(() -> parse(lostFamily), "constraint of list");
        var kind = TURNS.replace("change:reusedAcrossPages=yes", "maybe:reusedAcrossPages=yes");
        assertRejected(() -> parse(kind), "set: or change:");
    }

    @Test
    void aLabelThatDisagreesWithTheDialogIsRejected() {
        var wrongRef = GOLD.replace("dialog:list-order\t2\tcase:list-ingredients-01",
                "dialog:list-order\t2\tcase:list-install-01");
        assertRejected(() -> gold(wrongRef), "does not resolve to");
        var wrongChange = GOLD.replace("dialog:embed-partner\t3\tcase:embed-video-01\tqualify",
                "dialog:embed-partner\t3\tcase:embed-video-01\trevise");
        assertRejected(() -> gold(wrongChange), "the labels imply qualify");
        var understood = GOLD.replace("dialog:script-intro\t3\tcase:script-shared-01\tkeep\tabstain",
                "dialog:script-intro\t3\tcase:script-shared-01\tkeep\tsame");
        assertRejected(() -> gold(understood), "elliptical turn must abstain");
        var missing = GOLD.lines().filter(line -> !line.startsWith("dialog:topic-switch\t3"))
                .collect(Collectors.joining("\n", "", "\n"));
        assertRejected(() -> gold(missing), "without a gold label");
        var labelled = GOLD.replace("case:list-ranking-01\tfirst\tsame\t-",
                "case:list-ranking-01\tfirst\tsame\tDECIDE");
        assertRejected(() -> gold(labelled), "label column");
    }

    @Test
    void theSystemSideReferencesNoGoldLabelType() throws IOException {
        for (var type : List.of("HtmlDialogExperiment", "DialogContextAssembler", "DialogContext",
                "DialogContext$PriorTurn", "DialogContext$Advice", "DialogTrace", "DialogTrace$TurnRow",
                "HtmlDialogCases", "HtmlDialogCases$Turn", "HtmlDecisionExperiment", "DecisionRuleBaseline")) {
            try (var input = HtmlDialogExperiment.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (var gold : List.of("HtmlDialogGold", "DialogMetrics", "HtmlDecisionGold", "DecisionMetrics",
                        "HtmlDecisionAdjudication")) {
                    assertFalse(bytes.contains(gold), type + " references " + gold);
                }
            }
        }
    }

    private static HtmlDialogCases parse(String text) {
        return HtmlDialogCases.parse(HtmlDialogCases.VERSION, text, "test");
    }

    private static HtmlDialogGold gold(String text) {
        return HtmlDialogGold.parse(text, "test", DIALOGS, CASES, DECISION_GOLD, CORPUS,
                HtmlDialogRunner.reference(DIALOGS));
    }

    private static void assertRejected(Executable load, String fragment) {
        var message = assertThrows(CorpusValidationException.class, load).getMessage();
        assertTrue(message.contains(fragment), message);
    }
}
