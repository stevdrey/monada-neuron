package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SensePartitions.Split;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the step-8 run reads: the items of every split with their offline metadata, the gold of training and
 * validation (open) and of held-out (guarded), the step-7 scored set, and the hashes committed before the run.
 *
 * <p>Arms never see this type. They see {@link SenseItem} inputs and, for training, gold; family ids, splits and
 * metadata stay here, for checks, strata and the bootstrap.
 *
 * @param provenance description of the data: {@code synthetic} for the self-test, else the label provenance
 * @param labelStatus v2 label status: {@code agent-labeled} or {@code agent-labeled, audited}
 * @param training training items in training-file order (pilot families already removed)
 * @param trainingGold gold of the training items
 * @param validation validation items
 * @param validationGold gold of the validation items
 * @param heldOut held-out items, including unseen-lemma check items
 * @param heldOutGold guarded held-out gold
 * @param calibrationFamilies family ids of the calibration reserve
 * @param pilotFamilies family ids of the pilot
 * @param scored the step-7 scored set
 * @param hashes the hashes committed before the run and the bytes they cover
 */
record SenseDataset(String provenance, String labelStatus, List<DatasetItem> training, List<SenseGold> trainingGold,
                    List<DatasetItem> validation, List<SenseGold> validationGold, List<DatasetItem> heldOut,
                    GuardedGold heldOutGold, Set<String> calibrationFamilies, Set<String> pilotFamilies,
                    ScoredSet scored, CommittedHashes hashes) {

    /**
     * One item with its offline metadata.
     *
     * @param occurrenceId v2 occurrence id
     * @param familyId merged family id
     * @param split the family's split
     * @param input the system input
     * @param source {@code tatoeba}, {@code europarl} or {@code synthetic}
     * @param originalSentence whether the source sentence is an original (not a translation); {@code null} if unknown
     * @param translationDivergent whether pass B marked the item translation-divergent (computed offline)
     */
    record DatasetItem(String occurrenceId, String familyId, Split split, SenseItem input, String source,
                       Boolean originalSentence, boolean translationDivergent) {
    }

    /**
     * The step-7 outcome the run is given, read from gold counts offline without displaying held-out items.
     *
     * @param clusters per scored {@code (direction, lemma)} key, its scored cluster ordinals in increasing order
     * @param testableDirections directions that passed every step-7 gate
     * @param abstentionTestable directions where H2 is testable (at least 30 held-out {@code UNA}/{@code OOI} families)
     * @param droppedLemmas {@code (direction, lemma)} keys dropped offline (agreement, projection, unlabeled)
     * @param removedClusters {@code <lemma key>#<cluster>} entries removed offline by the projection gate
     */
    record ScoredSet(Map<String, List<Integer>> clusters, Set<Direction> testableDirections,
                     Set<Direction> abstentionTestable, Set<String> droppedLemmas, Set<String> removedClusters) {

        /** Returns whether a lemma is scored. */
        boolean scoredLemma(String lemmaKey) {
            return clusters.containsKey(lemmaKey);
        }
    }

    /**
     * Hashes committed before the run and the bytes they must match.
     *
     * @param inventorySha256 inventory hash committed before calibration
     * @param inventoryBytes the inventory file read by the run
     * @param guidelineSha256 guideline hash committed when calibration ended
     * @param guidelineBytes the guideline file
     * @param heldOutGoldSha256 held-out gold hash committed at step 7
     * @param sealedLogSha256 sealed raw-output log hash committed at step 7 (v2)
     * @param sealedLogBytes the sealed log file
     */
    record CommittedHashes(String inventorySha256, byte[] inventoryBytes, String guidelineSha256,
                           byte[] guidelineBytes, String heldOutGoldSha256, String sealedLogSha256,
                           byte[] sealedLogBytes) {
    }

    /** Validates sizes, every {@code ANS} gold set against its item's inventory, and the scored clusters. */
    SenseDataset {
        Objects.requireNonNull(provenance, "provenance must not be null");
        Objects.requireNonNull(labelStatus, "labelStatus must not be null");
        training = List.copyOf(training);
        trainingGold = List.copyOf(trainingGold);
        validation = List.copyOf(validation);
        validationGold = List.copyOf(validationGold);
        heldOut = List.copyOf(heldOut);
        if (training.size() != trainingGold.size() || validation.size() != validationGold.size()
                || heldOut.size() != heldOutGold.size()) {
            throw new IllegalArgumentException("every split needs one gold label per item");
        }
        var inventorySizes = new HashMap<String, Integer>();
        for (List<DatasetItem> split : List.of(training, validation, heldOut)) {
            for (DatasetItem item : split) {
                if (item.input().hasInventory()) {
                    inventorySizes.put(item.input().lemmaKey(), item.input().inventory().size());
                }
            }
        }
        requireWithinInventory(training, trainingGold);
        requireWithinInventory(validation, validationGold);
        heldOutGold.requireWithinInventory(heldOut);
        scored.clusters().forEach((lemma, clusters) -> {
            Integer size = inventorySizes.get(lemma);
            if (size == null || clusters.stream().anyMatch(k -> k < 0 || k >= size)) {
                throw new IllegalArgumentException("scored clusters " + clusters + " are outside the inventory of " + lemma);
            }
        });
    }

    private static void requireWithinInventory(List<DatasetItem> items, List<SenseGold> gold) {
        for (int i = 0; i < items.size(); i++) {
            requireWithinInventory(items.get(i), gold.get(i));
        }
    }

    /** Rejects an {@code ANS} gold set whose item has no inventory or that names a cluster outside it. */
    static void requireWithinInventory(DatasetItem item, SenseGold gold) {
        if (gold instanceof SenseGold.Answerable answerable && (!item.input().hasInventory()
                || answerable.mask() >>> item.input().inventory().size() != 0)) {
            throw new IllegalArgumentException("gold outside the inventory of " + item.occurrenceId());
        }
    }

    /** Returns a copy with other held-out gold (check {@code nc.heldout-counterfactual}). */
    SenseDataset withHeldOutGold(GuardedGold gold) {
        return new SenseDataset(provenance, labelStatus, training, trainingGold, validation, validationGold, heldOut,
                gold, calibrationFamilies, pilotFamilies, scored, hashes);
    }

    /** Returns a copy with other training gold (check {@code nc.label-permutation}). */
    SenseDataset withTrainingGold(List<SenseGold> gold) {
        return new SenseDataset(provenance, labelStatus, training, gold, validation, validationGold, heldOut,
                heldOutGold, calibrationFamilies, pilotFamilies, scored, hashes);
    }

    /**
     * Returns the canonical held-out gold text that {@code heldOutGoldSha256} covers: one line per item,
     * {@code <occurrence id>\t<ANS:k,k|UNA|OOI>}, in held-out order. Reads the guarded gold, so it is called only after
     * the gold is open.
     */
    String heldOutGoldText() {
        var text = new StringBuilder();
        for (int i = 0; i < heldOut.size(); i++) {
            text.append(heldOut.get(i).occurrenceId()).append('\t').append(goldText(heldOutGold.get(i))).append('\n');
        }
        return text.toString();
    }

    /** Returns the canonical text of a gold label. */
    static String goldText(SenseGold gold) {
        return switch (gold) {
            case SenseGold.Answerable answerable -> {
                var clusters = new StringBuilder("ANS:");
                for (int k = 0; k < Integer.SIZE; k++) {
                    if (answerable.contains(k)) {
                        clusters.append(clusters.length() > 4 ? "," : "").append(k);
                    }
                }
                yield clusters.toString();
            }
            case SenseGold.Abstain abstain -> abstain.outOfInventory() ? "OOI" : "UNA";
        };
    }

    /** Returns the lowercase hex SHA-256 of bytes. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns the lowercase hex SHA-256 of UTF-8 text. */
    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
