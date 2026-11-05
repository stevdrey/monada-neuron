package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import monada.neuron.evaluation.bilingual.SenseDataset.ScoredSet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The frozen data gates of {@code bilingual-sense/1} and the gate added by v2, as pure functions over counts and gold
 * weights. They read no system output. Steps 2 to 7 apply them offline and record every outcome in the feasibility
 * report; the harness re-applies the step-7 gates to the gold it is given, so a disagreement with the recorded outcome
 * is visible.
 */
final class SenseGates {

    /** Text gate minimum candidate families per split: training, validation, held-out. */
    static final int TEXT_TRAINING = 80;
    static final int TEXT_VALIDATION = 40;
    static final int TEXT_HELD_OUT = 60;
    /** Held-out annotation cap used by the projection gate. */
    static final int HELD_OUT_CAP = 120;
    /** Minimum projected and actual held-out families per scored cluster. */
    static final int MIN_PER_SENSE = 30;
    /** Two-sided 80% normal quantile of the projection gate's Wilson interval. */
    static final double Z80 = 1.2815515655446004;
    /** Minimum pass-A agreement (inter-agent consistency under v2). */
    static final double MIN_ALPHA = 0.67;
    /** Low-frequency share threshold. */
    static final double LOW_FREQUENCY = 0.20;
    /** Minimum held-out {@code UNA} or {@code OOI} families of scored lemmas for H2. */
    static final int MIN_ABSTENTION_ITEMS = 30;
    /** Minimum {@code ANS} training weight per scored cluster. */
    static final double MIN_TRAINING_WEIGHT = 10.0;
    /** Minimum {@code ANS} validation items per scored lemma and per direction. */
    static final int MIN_VALIDATION_PER_LEMMA = 10;
    static final int MIN_VALIDATION_PER_DIRECTION = 60;
    /** Minimum scored lemmas per direction, each with at least two scored clusters. */
    static final int MIN_LEMMAS = 4;
    /** v2 {@code annotation.unlabeled}: maximum share of {@code UNLABELED} items. */
    static final double MAX_UNLABELED = 0.02;

    /** Text gate: at least 80 training, 40 validation and 60 held-out candidate families. */
    boolean text(int training, int validation, int heldOut) {
        return training >= TEXT_TRAINING && validation >= TEXT_VALIDATION && heldOut >= TEXT_HELD_OUT;
    }

    /**
     * Returns the upper bound of the two-sided 80% Wilson interval of {@code successes / n}; {@code successes} may be
     * fractional (an item with gold set {@code G} counts {@code 1/|G|}). Empty when {@code n = 0}.
     */
    OptionalDouble wilsonUpper80(double successes, int n) {
        if (n <= 0) {
            return OptionalDouble.empty();
        }
        double p = successes / n;
        double z2 = Z80 * Z80;
        double centre = p + z2 / (2.0 * n);
        double spread = Z80 * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * (double) n));
        return OptionalDouble.of(Math.min(1.0, (centre + spread) / (1.0 + z2 / n)));
    }

    /**
     * Projection gate of one cluster: {@code min(heldOutFamilies, 120) x p+ >= 30}, {@code p+} the Wilson upper bound
     * of its pilot share among pilot {@code ANS} items. False when the pilot holds no {@code ANS} item (the lemma is
     * then dropped with that reason).
     */
    boolean projection(int heldOutFamilies, double pilotWeight, int pilotAnswerable) {
        OptionalDouble upper = wilsonUpper80(pilotWeight, pilotAnswerable);
        return upper.isPresent() && Math.min(heldOutFamilies, HELD_OUT_CAP) * upper.getAsDouble() >= MIN_PER_SENSE;
    }

    /** Agreement gate: {@code alpha >= 0.67}; an undefined {@code alpha} (no label variation) drops the lemma. */
    boolean agreement(OptionalDouble alpha) {
        return alpha.isPresent() && alpha.getAsDouble() >= MIN_ALPHA;
    }

    /** Minimum per sense: at least 30 held-out families whose gold set contains the cluster. */
    boolean minimumPerSense(int heldOutFamilies) {
        return heldOutFamilies >= MIN_PER_SENSE;
    }

    /** Low-frequency: the cluster holds less than 20% of its lemma's gold {@code ANS} weight. */
    boolean lowFrequency(double clusterWeight, double lemmaWeight) {
        return lemmaWeight > 0.0 && clusterWeight / lemmaWeight < LOW_FREQUENCY;
    }

    /** Abstention items: H2 is testable only with at least 30 held-out {@code UNA} or {@code OOI} families. */
    boolean abstentionItems(int families) {
        return families >= MIN_ABSTENTION_ITEMS;
    }

    /** Training data: a scored cluster stays scored only with {@code ANS} training weight of at least 10. */
    boolean trainingWeight(double weight) {
        return weight >= MIN_TRAINING_WEIGHT;
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
     * direction, abstention items) to the given splits and returns the scored set. The agreement, projection and
     * {@code annotation.unlabeled} gates read pass-A labels, the pilot and step-6 records, which are offline; lemmas they
     * dropped are passed in {@code dropped}.
     */
    ScoredSet scoredSet(List<DatasetItem> training, List<SenseGold> trainingGold, List<DatasetItem> validation,
                        List<SenseGold> validationGold, List<DatasetItem> heldOut, List<SenseGold> heldOutGold,
                        Set<String> dropped) {
        var trainingWeight = new HashMap<String, double[]>();
        var inventories = new LinkedHashMap<String, SenseInventory>();
        for (int i = 0; i < training.size(); i++) {
            SenseItem input = training.get(i).input();
            if (input.hasInventory() && trainingGold.get(i) instanceof SenseGold.Answerable gold) {
                inventories.putIfAbsent(input.lemmaKey(), input.inventory());
                double[] weights = trainingWeight.computeIfAbsent(input.lemmaKey(),
                        _ -> new double[input.inventory().size()]);
                for (int k = 0; k < weights.length; k++) {
                    if (gold.contains(k)) {
                        weights[k] += 1.0 / gold.size();
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
                if (minimumPerSense(families) && trainingWeight(trainingWeight.get(key)[k])) {
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
        return new ScoredSet(clusters, testable, abstention);
    }

    /** v2 {@code annotation.unlabeled}: a lemma with more than 2% {@code UNLABELED} step-6 items is dropped. */
    boolean unlabeled(int unlabeled, int items) {
        return items > 0 && unlabeled <= MAX_UNLABELED * items;
    }
}
