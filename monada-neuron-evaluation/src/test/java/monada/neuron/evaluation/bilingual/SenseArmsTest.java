package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.NeuronSenseArm.Scored;
import monada.neuron.evaluation.bilingual.NeuronSenseArm.SenseCue;
import monada.neuron.evaluation.bilingual.SenseArm.Prediction;
import monada.neuron.evaluation.bilingual.SenseArm.TrainingExample;
import monada.neuron.reasoning.EvidenceRelation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseArmsTest {

    private static final SenseInventory BANK = new SenseInventory(Direction.EN_ES, "bank",
            List.of(List.of("banco"), List.of("orilla"), List.of("banca")));

    private final SenseWindows windows = new SenseWindows(new SenseTokenizer());

    /** An item whose sentence is "bank" followed by the given context words. */
    private SenseItem item(String context) {
        return new SenseItem(Direction.EN_ES, "bank", BANK, "bank " + context, 0, 4);
    }

    private TrainingExample example(String context, int mask) {
        return new TrainingExample(item(context), new SenseGold.Answerable(mask));
    }

    private List<TrainingExample> training() {
        return List.of(
                example("money loan", 0b001),
                example("money deposit", 0b001),
                example("river water", 0b010),
                example("river fish", 0b010),
                example("money river", 0b011));
    }

    @Test
    void everyRegisteredGridHasNinetyPointsExceptA() {
        assertEquals(1, new MajoritySenseArm().gridSize());
        assertEquals(90, new NearestExampleArm(windows).gridSize());
        assertEquals(90, new ContextualLexicalArm(windows).gridSize());
        assertEquals(90, new NeuronSenseArm(windows).gridSize());
    }

    @Test
    void majorityUsesTrainingWeightsWithLowerOrdinalTies() {
        var arm = new MajoritySenseArm();
        arm.fit(training());
        // Weights: cluster 0 = 2.5, cluster 1 = 2.5, cluster 2 = 0 -> tie to ordinal 0, share 0.5.
        assertEquals(new Prediction(0, 0.5), arm.predict(item("anything"), 0));
        assertTrue(arm.predict(item("anything"), 0).answers(0.0));
    }

    @Test
    void everyArmAbstainsOnALemmaWithoutInventory() {
        var unseen = new SenseItem(Direction.EN_ES, "pitch", null, "pitch dark", 0, 5);
        for (SenseArm arm : List.of(new MajoritySenseArm(), new NearestExampleArm(windows),
                new ContextualLexicalArm(windows), new NeuronSenseArm(windows))) {
            arm.fit(training());
            Prediction prediction = arm.predict(unseen, 0);
            assertEquals(Prediction.NO_INVENTORY, prediction, arm.name());
            assertFalse(prediction.answers(0.0), arm.name());
        }
    }

    @Test
    void nearestExampleVotesWithGoldWeightsAndFallsBackOnZeroSimilarity() {
        var arm = new NearestExampleArm(windows);
        arm.fit(training());
        // Configuration 0: k = 1, w = 3. "river water" is identical to item 2: similarity 1, vote cluster 1.
        assertEquals(new Prediction(1, 1.0), arm.predict(item("river water"), 0));
        // No shared token: every similarity is 0 -> lowest-ordinal supported cluster, s = 0.
        assertEquals(new Prediction(0, 0.0), arm.predict(item("zebra"), 0));
        // k = 1: "loan water" ties items 0 (cluster 0) and 2 (cluster 1) at similarity 1/3 -> the earlier item wins.
        assertEquals(new Prediction(0, 1.0 / 3), arm.predict(item("loan water"), 0));
        // k = 3 (configuration 3): "money" -> items 0, 1 and 4 at similarity 1/2, votes cluster 0 = 2.5 and
        // cluster 1 = 0.5 -> share 2.5/3, s = 2.5/3 x 1/2.
        Prediction three = arm.predict(item("money"), 3);
        assertEquals(0, three.choice());
        assertEquals(2.5 / 3 * 0.5, three.confidence(), 1e-15);
    }

    @Test
    void contextualLexicalGivesAZeroWeightClusterPosteriorZero() {
        var arm = new ContextualLexicalArm(windows);
        arm.fit(training());
        // Configuration 6: alpha = 1, w = 3. Cluster 2 has no training weight, so it is never selected.
        Prediction money = arm.predict(item("money money loan"), 6);
        assertEquals(0, money.choice());
        assertTrue(money.confidence() > 0.5 && money.confidence() < 1.0);
        // Unknown tokens are ignored: the posterior equals the prior shares (2.5 / 5 each), tie to ordinal 0.
        Prediction unknown = arm.predict(item("zebra"), 6);
        assertEquals(new Prediction(0, 0.5), unknown);
    }

    @Test
    void neuronCuesFollowTheRegisteredFormula() {
        var arm = new NeuronSenseArm(windows);
        arm.fit(training());
        // Configuration 3: lambda = 1, w = 3. For "money": n = 3 (items 0, 1, 4), n_0 = 2.5, n_1 = 0.5, n_2 = 0, K = 3.
        Scored scored = arm.score(item("money"), 3);
        double n = 3.0;
        double strength = Math.log(1.0 + n) / Math.log(2.0);
        double d0 = (3 * (2.5 + 1) - (n + 3)) / (2.0 * (n + 3));
        double d1 = (3 * (0.5 + 1) - (n + 3)) / (2.0 * (n + 3));
        double d2 = (3 * (0.0 + 1) - (n + 3)) / (2.0 * (n + 3));
        assertEquals(List.of(new SenseCue(0, 1, EvidenceRelation.SUPPORTS, Math.min(1.0, strength * d0))),
                scored.cues().get(0));
        assertEquals(List.of(new SenseCue(1, 1, EvidenceRelation.CONTRADICTS, Math.min(1.0, strength * -d1))),
                scored.cues().get(1));
        assertEquals(List.of(new SenseCue(2, 1, EvidenceRelation.CONTRADICTS, Math.min(1.0, strength * -d2))),
                scored.cues().get(2));
        double w0 = scored.cues().get(0).getFirst().weight();
        assertEquals(w0 / (w0 + 1.0), scored.scores()[0]);
        assertEquals(0.0, scored.scores()[1]);
        assertEquals(0, scored.choice());
        assertNull(new NeuronPolicyOracle().mismatch(scored));
    }

    @Test
    void neuronIgnoresTokensSeenInFewerThanTwoWeightUnits() {
        var arm = new NeuronSenseArm(windows);
        arm.fit(training());
        // "loan" occurs in one training item (n = 1): no evidence, every score 0, ordinal 0, abstains above tau = 0.
        Scored scored = arm.score(item("loan"), 3);
        assertTrue(scored.cues().stream().allMatch(List::isEmpty));
        assertEquals(0, scored.choice());
        assertFalse(arm.predict(item("loan"), 3).answers(0.1));
        assertTrue(arm.predict(item("loan"), 3).answers(0.0));
    }

    @Test
    void neuronKeepsSixteenCuesPerClusterAndMatchesThePolicy() {
        var examples = new ArrayList<TrainingExample>();
        var context = new StringBuilder();
        for (int t = 0; t < 20; t++) {
            String token = "t" + t;
            context.append(token).append(' ');
            for (int r = 0; r <= t % 4; r++) {
                examples.add(example(token + " filler" + r, 0b001));
            }
            examples.add(example(token, 0b010));
        }
        var arm = new NeuronSenseArm(windows);
        arm.fit(examples);
        Scored scored = arm.score(item(context.toString().strip()), 3 + 2);
        for (List<SenseCue> cues : scored.cues()) {
            assertTrue(cues.size() <= 16);
            for (int i = 1; i < cues.size(); i++) {
                assertTrue(cues.get(i - 1).position() < cues.get(i).position(), "position order");
            }
        }
        assertEquals(16, scored.cues().get(0).size());
        assertNull(new NeuronPolicyOracle().mismatch(scored));
    }

    @Test
    void maskedContextScoresZeroEverywhere() {
        var arm = new NeuronSenseArm(windows);
        arm.fit(training());
        Scored masked = arm.scoreMasked(item("money money"), 0);
        assertEquals(0, masked.choice());
        for (double score : masked.scores()) {
            assertEquals(0.0, score);
        }
    }
}
