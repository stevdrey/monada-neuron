package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import monada.neuron.evaluation.bilingual.SenseDataset.ScoredSet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The frozen data gates of {@code bilingual-sense/1} that read gold counts, as pure functions. The gates that read
 * pass-A labels, the pilot and step-6 records are offline tooling; their outcomes arrive as dropped lemmas and removed
 * clusters. {@code BilingualSenseEvaluation} re-applies these gates to the gold it is given and fails check
 * {@code gates.step7-consistency} when the recorded step-7 outcome disagrees.
 */
final class SenseGates {

    /** Minimum held-out families per scored cluster. */
    static final int MIN_PER_SENSE = 30;
    /** Minimum held-out {@code UNA} or {@code OOI} families of scored lemmas for H2. */
    static final int MIN_ABSTENTION_ITEMS = 30;
    /** Minimum {@code ANS} training weight per scored cluster. */
    static final int MIN_TRAINING_WEIGHT = 10;
    /** Minimum {@code ANS} validation items per scored lemma and per direction. */
    static final int MIN_VALIDATION_PER_LEMMA = 10;
    static final int MIN_VALIDATION_PER_DIRECTION = 60;
    /** Minimum scored lemmas per direction, each with at least two scored clusters. */
    static final int MIN_LEMMAS = 4;

    /** Minimum per sense: at least 30 held-out families whose gold set contains the cluster. */
    boolean minimumPerSense(int heldOutFamilies) {
        return heldOutFamilies >= MIN_PER_SENSE;
    }

    /** Low-frequency: the cluster holds less than 20% of its lemma's gold {@code ANS} weight, compared exactly. */
    boolean lowFrequency(long clusterUnits, long lemmaUnits) {
        return lemmaUnits > 0 && clusterUnits * 5 < lemmaUnits;
    }

    /** Abstention items: H2 is testable only with at least 30 held-out {@code UNA} or {@code OOI} families. */
    boolean abstentionItems(int families) {
        return families >= MIN_ABSTENTION_ITEMS;
    }

    /**
     * Training data: a scored cluster stays scored only with {@code ANS} training weight of at least 10, compared in
     * exact gold units ({@link LemmaTraining#unitsPerItem}).
     */
    boolean trainingWeight(long units, long unitsPerItem) {
        return units >= MIN_TRAINING_WEIGHT * unitsPerItem;
    }

    /** Validation data per lemma: at least 10 {@code ANS} validation items. */
    boolean validationPerLemma(int answerable) {
        return answerable >= MIN_VALIDATION_PER_LEMMA;
    }

    /** Validation data per direction: at least 60 {@code ANS} validation items over its scored lemmas. */
    boolean validationPerDirection(int answerable) {
        return answerable >= MIN_VALIDATION_PER_DIRECTION;
    }

    /** Minimum per direction: at least 4 lemmas with at least two scored clusters. */
    boolean minimumPerDirection(int lemmasWithTwoScoredClusters) {
        return lemmasWithTwoScoredClusters >= MIN_LEMMAS;
    }

    /**
     * Applies the step-7 gates that read gold counts (minimum per sense, training data, validation data, minimum per
     * direction, abstention items) to the given splits and returns the scored set.
     *
     * @param dropped {@code (direction, lemma)} keys dropped offline (agreement, projection, unlabeled)
     * @param removedClusters {@code <lemma key>#<cluster>} entries the projection gate removed; never scored again
     */
    ScoredSet scoredSet(List<DatasetItem> training, List<SenseGold> trainingGold, List<DatasetItem> validation,
                        List<SenseGold> validationGold, List<DatasetItem> heldOut, List<SenseGold> heldOutGold,
                        Set<String> dropped, Set<String> removedClusters) {
        var trainingUnits = new HashMap<String, long[]>();
        var inventories = new LinkedHashMap<String, SenseInventory>();
        for (int i = 0; i < training.size(); i++) {
            SenseItem input = training.get(i).input();
            if (input.hasInventory() && trainingGold.get(i) instanceof SenseGold.Answerable gold) {
                inventories.putIfAbsent(input.lemmaKey(), input.inventory());
                long[] units = trainingUnits.computeIfAbsent(input.lemmaKey(),
                        _ -> new long[input.inventory().size()]);
                long share = LemmaTraining.unitsPerItem(units.length) / gold.size();
                for (int k = 0; k < units.length; k++) {
                    if (gold.contains(k)) {
                        units[k] += share;
                    }
                }
            }
        }
        var heldOutFamilies = new HashMap<String, Set<String>>();
        for (int i = 0; i < heldOut.size(); i++) {
            SenseItem input = heldOut.get(i).input();
            if (input.hasInventory() && heldOutGold.get(i) instanceof SenseGold.Answerable gold) {
                for (int k = 0; k < input.inventory().size(); k++) {
                    if (gold.contains(k)) {
                        heldOutFamilies.computeIfAbsent(input.lemmaKey() + "#" + k, _ -> new HashSet<>())
                                .add(heldOut.get(i).familyId());
                    }
                }
            }
        }
        var validationAnswerable = new HashMap<String, Integer>();
        for (int i = 0; i < validation.size(); i++) {
            if (validation.get(i).input().hasInventory() && validationGold.get(i).answerable()) {
                validationAnswerable.merge(validation.get(i).input().lemmaKey(), 1, Integer::sum);
            }
        }
        var clusters = new LinkedHashMap<String, List<Integer>>();
        for (var entry : inventories.entrySet()) {
            String key = entry.getKey();
            if (dropped.contains(key) || !validationPerLemma(validationAnswerable.getOrDefault(key, 0))) {
                continue;
            }
            var scored = new ArrayList<Integer>();
            for (int k = 0; k < entry.getValue().size(); k++) {
                int families = heldOutFamilies.getOrDefault(key + "#" + k, Set.of()).size();
                if (!removedClusters.contains(key + "#" + k) && minimumPerSense(families) && trainingWeight(trainingUnits.get(key)[k],
                        LemmaTraining.unitsPerItem(entry.getValue().size()))) {
                    scored.add(k);
                }
            }
            if (scored.size() >= 2) {
                clusters.put(key, List.copyOf(scored));
            }
        }
        var testable = EnumSet.noneOf(Direction.class);
        var abstention = EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            int lemmas = 0;
            int answerable = 0;
            for (String key : clusters.keySet()) {
                if (key.startsWith(direction.label() + "|")) {
                    lemmas++;
                    answerable += validationAnswerable.getOrDefault(key, 0);
                }
            }
            if (minimumPerDirection(lemmas) && validationPerDirection(answerable)) {
                testable.add(direction);
            }
            var abstainFamilies = new HashSet<String>();
            for (int i = 0; i < heldOut.size(); i++) {
                SenseItem input = heldOut.get(i).input();
                if (input.direction() == direction && clusters.containsKey(input.lemmaKey())
                        && !heldOutGold.get(i).answerable()) {
                    abstainFamilies.add(heldOut.get(i).familyId());
                }
            }
            if (abstentionItems(abstainFamilies.size())) {
                abstention.add(direction);
            }
        }
        return new ScoredSet(clusters, testable, abstention, dropped, removedClusters);
    }
}
