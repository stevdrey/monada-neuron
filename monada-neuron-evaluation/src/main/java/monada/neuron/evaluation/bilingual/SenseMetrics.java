package monada.neuron.evaluation.bilingual;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The registered metrics of {@code bilingual-sense/1} over primitive arrays. Every method takes the items to score as
 * an index array that may repeat indices, so the paired cluster bootstrap reuses the same code. A ratio with a zero
 * denominator is {@link OptionalDouble#empty()} (reported {@code n/a}), never 0 or 1, and enters no gate.
 */
final class SenseMetrics {

    /** Gold kind of an {@code ANS} item. */
    static final byte ANS = 0;
    /** Gold kind of a {@code UNA} item. */
    static final byte UNA = 1;
    /** Gold kind of an {@code OOI} item. */
    static final byte OOI = 2;

    /** Equal-mass calibration bins. */
    static final int ECE_BINS = 10;

    /**
     * Gold of a set of items.
     *
     * @param lemma lemma index of each item
     * @param kind {@link #ANS}, {@link #UNA} or {@link #OOI}
     * @param mask the gold cluster mask of an {@code ANS} item, else 0
     */
    record Labels(int[] lemma, byte[] kind, int[] mask) {

        /** Returns whether item {@code i}'s forced choice is in its gold set. */
        boolean correct(int i, int choice) {
            return kind[i] == ANS && choice >= 0 && choice < Integer.SIZE && (mask[i] & (1 << choice)) != 0;
        }
    }

    /**
     * Outputs of one arm at its selected grid point.
     *
     * @param choice forced choice {@code k*} of each item ({@code -1} without an inventory)
     * @param confidence {@code s} of each item
     * @param answered whether each item is answered (not {@code ABSTAIN})
     */
    record Outputs(int[] choice, double[] confidence, boolean[] answered) {
    }

    /**
     * A scored sense.
     *
     * @param lemma lemma index
     * @param cluster cluster ordinal
     */
    record Sense(int lemma, int cluster) {
    }

    /** Forced accuracy on {@code ANS}: {@code k* in G}. */
    OptionalDouble forcedAccuracy(Labels labels, Outputs outputs, int[] items) {
        int n = 0;
        int correct = 0;
        for (int i : items) {
            if (labels.kind()[i] == ANS) {
                n++;
                if (labels.correct(i, outputs.choice()[i])) {
                    correct++;
                }
            }
        }
        return ratio(correct, n);
    }

    /**
     * Sense-macro forced accuracy: an {@code ANS} item contributes {@code 1/|G|} to each scored cluster in {@code G};
     * per-sense accuracy is the weighted mean of forced correctness; {@code Macro} is the unweighted mean over scored
     * senses. A sense with no weight among {@code items} (possible in a bootstrap resample) is left out; with none
     * left, {@code Macro} is undefined.
     */
    OptionalDouble macro(Labels labels, Outputs outputs, int[] items, List<Sense> senses) {
        double sum = 0.0;
        int defined = 0;
        for (Sense sense : senses) {
            double weight = 0.0;
            double correct = 0.0;
            for (int i : items) {
                if (labels.lemma()[i] == sense.lemma() && labels.kind()[i] == ANS
                        && (labels.mask()[i] & (1 << sense.cluster())) != 0) {
                    double share = 1.0 / Integer.bitCount(labels.mask()[i]);
                    weight += share;
                    if (labels.correct(i, outputs.choice()[i])) {
                        correct += share;
                    }
                }
            }
            if (weight > 0.0) {
                sum += correct / weight;
                defined++;
            }
        }
        return defined == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / defined);
    }

    /**
     * The constant bound {@code Macro_const}: for each lemma, the best sense-macro accuracy of always choosing one of
     * its clusters, averaged over the scored senses as {@code Macro} is. It reads held-out gold, so it is used only by
     * checks, after scoring.
     *
     * @param clusters cluster count of each lemma index
     */
    OptionalDouble macroConstant(Labels labels, int[] items, List<Sense> senses, int[] clusters) {
        double sum = 0.0;
        int defined = 0;
        for (int lemma = 0; lemma < clusters.length; lemma++) {
            double best = Double.NEGATIVE_INFINITY;
            int definedHere = 0;
            for (int constant = 0; constant < clusters[lemma]; constant++) {
                double total = 0.0;
                int count = 0;
                for (Sense sense : senses) {
                    if (sense.lemma() != lemma) {
                        continue;
                    }
                    double weight = 0.0;
                    double correct = 0.0;
                    for (int i : items) {
                        if (labels.lemma()[i] == lemma && labels.kind()[i] == ANS
                                && (labels.mask()[i] & (1 << sense.cluster())) != 0) {
                            double share = 1.0 / Integer.bitCount(labels.mask()[i]);
                            weight += share;
                            if ((labels.mask()[i] & (1 << constant)) != 0) {
                                correct += share;
                            }
                        }
                    }
                    if (weight > 0.0) {
                        total += correct / weight;
                        count++;
                    }
                }
                definedHere = count;
                best = Math.max(best, total);
            }
            if (definedHere > 0) {
                sum += best;
                defined += definedHere;
            }
        }
        return defined == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / defined);
    }

    /** Coverage on {@code ANS}: answered {@code ANS} items over {@code ANS} items. */
    OptionalDouble coverage(Labels labels, Outputs outputs, int[] items) {
        int n = 0;
        int answered = 0;
        for (int i : items) {
            if (labels.kind()[i] == ANS) {
                n++;
                if (outputs.answered()[i]) {
                    answered++;
                }
            }
        }
        return ratio(answered, n);
    }

    /** Selective accuracy on answered {@code ANS} items. */
    OptionalDouble selectiveAccuracy(Labels labels, Outputs outputs, int[] items) {
        int answered = 0;
        int correct = 0;
        for (int i : items) {
            if (labels.kind()[i] == ANS && outputs.answered()[i]) {
                answered++;
                if (labels.correct(i, outputs.choice()[i])) {
                    correct++;
                }
            }
        }
        return ratio(correct, answered);
    }

    /**
     * Selective risk over all items, as the selection rule defines it: ({@code ANS} answered wrongly plus
     * {@code UNA}/{@code OOI} answered) over answered items, and {@code 0} when nothing is answered.
     */
    double selectionRisk(Labels labels, Outputs outputs, int[] items) {
        int answered = 0;
        int wrong = 0;
        for (int i : items) {
            if (outputs.answered()[i]) {
                answered++;
                if (!labels.correct(i, outputs.choice()[i])) {
                    wrong++;
                }
            }
        }
        return answered == 0 ? 0.0 : (double) wrong / answered;
    }

    /**
     * Abstention precision for the gold kinds in {@code kinds} (a bit set over {@link #UNA} and {@link #OOI}):
     * abstained items of those kinds over abstained items.
     */
    OptionalDouble abstentionPrecision(Labels labels, Outputs outputs, int[] items, int kinds) {
        int abstained = 0;
        int hits = 0;
        for (int i : items) {
            if (!outputs.answered()[i]) {
                abstained++;
                if ((kinds & (1 << labels.kind()[i])) != 0) {
                    hits++;
                }
            }
        }
        return ratio(hits, abstained);
    }

    /** Abstention recall for the gold kinds in {@code kinds}: abstained items of those kinds over items of them. */
    OptionalDouble abstentionRecall(Labels labels, Outputs outputs, int[] items, int kinds) {
        int total = 0;
        int abstained = 0;
        for (int i : items) {
            if ((kinds & (1 << labels.kind()[i])) != 0) {
                total++;
                if (!outputs.answered()[i]) {
                    abstained++;
                }
            }
        }
        return ratio(abstained, total);
    }

    /**
     * AURC over the given items ranked by decreasing {@code s}, where risk counts wrong answers on {@code ANS} and any
     * answer on {@code UNA}/{@code OOI}. Tied scores form one block: the curve steps once, to the block's combined
     * coverage and the cumulative risk after it, so the result does not depend on item order.
     * {@code AURC = sum over blocks (|block| / n) x risk(after block)}.
     */
    OptionalDouble aurc(Labels labels, Outputs outputs, int[] items) {
        if (items.length == 0) {
            return OptionalDouble.empty();
        }
        int[] order = byDecreasingScore(outputs.confidence(), items);
        double area = 0.0;
        int seen = 0;
        int errors = 0;
        int b = 0;
        while (b < order.length) {
            double score = outputs.confidence()[order[b]];
            int end = b;
            while (end < order.length && outputs.confidence()[order[end]] == score) {
                if (!labels.correct(order[end], outputs.choice()[order[end]])) {
                    errors++;
                }
                end++;
            }
            seen += end - b;
            area += (double) (end - b) / order.length * ((double) errors / seen);
            b = end;
        }
        return OptionalDouble.of(area);
    }

    /**
     * Abstention AUROC: {@code 1 - s} as a detector of {@code UNA} or {@code OOI} against {@code ANS}; a tied pair counts
     * one half.
     */
    OptionalDouble auroc(Labels labels, Outputs outputs, int[] items) {
        int positives = 0;
        int negatives = 0;
        for (int i : items) {
            if (labels.kind()[i] == ANS) {
                negatives++;
            } else {
                positives++;
            }
        }
        if (positives == 0 || negatives == 0) {
            return OptionalDouble.empty();
        }
        // Rank-sum (Mann-Whitney) with mid-ranks over 1 - s, ascending.
        int[] order = byDecreasingScore(outputs.confidence(), items);
        double positiveRankSum = 0.0;
        int b = 0;
        while (b < order.length) {
            double score = outputs.confidence()[order[b]];
            int end = b;
            int positivesInBlock = 0;
            while (end < order.length && outputs.confidence()[order[end]] == score) {
                if (labels.kind()[order[end]] != ANS) {
                    positivesInBlock++;
                }
                end++;
            }
            double midRank = (b + 1 + end) / 2.0;
            positiveRankSum += positivesInBlock * midRank;
            b = end;
        }
        double u = positiveRankSum - positives * (positives + 1) / 2.0;
        return OptionalDouble.of(u / ((double) positives * negatives));
    }

    /**
     * ECE of {@code s} against forced correctness on {@code ANS}, over {@value #ECE_BINS} equal-mass bins of items
     * ordered by increasing {@code s} (ties by position in {@code items}): bin {@code b} holds positions
     * {@code [floor(b n / 10), floor((b + 1) n / 10))}.
     */
    OptionalDouble ece(Labels labels, Outputs outputs, int[] items) {
        int[] answerable = Arrays.stream(items).filter(i -> labels.kind()[i] == ANS).toArray();
        int n = answerable.length;
        if (n == 0) {
            return OptionalDouble.empty();
        }
        int[] order = byDecreasingScore(outputs.confidence(), answerable);
        reverseByIncreasing(order, outputs.confidence());
        double ece = 0.0;
        for (int bin = 0; bin < ECE_BINS; bin++) {
            int from = (int) ((long) bin * n / ECE_BINS);
            int to = (int) ((long) (bin + 1) * n / ECE_BINS);
            if (to == from) {
                continue;
            }
            double confidence = 0.0;
            double accuracy = 0.0;
            for (int p = from; p < to; p++) {
                confidence += outputs.confidence()[order[p]];
                accuracy += labels.correct(order[p], outputs.choice()[order[p]]) ? 1.0 : 0.0;
            }
            ece += (double) (to - from) / n * Math.abs(confidence / (to - from) - accuracy / (to - from));
        }
        return OptionalDouble.of(ece);
    }

    /** Brier score of {@code s} against forced correctness on {@code ANS}. */
    OptionalDouble brier(Labels labels, Outputs outputs, int[] items) {
        double sum = 0.0;
        int n = 0;
        for (int i : items) {
            if (labels.kind()[i] == ANS) {
                double target = labels.correct(i, outputs.choice()[i]) ? 1.0 : 0.0;
                double error = outputs.confidence()[i] - target;
                sum += error * error;
                n++;
            }
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / n);
    }

    /** Returns {@code 0..n-1}, the index of every item. */
    int[] all(int n) {
        var items = new int[n];
        for (int i = 0; i < n; i++) {
            items[i] = i;
        }
        return items;
    }

    private OptionalDouble ratio(int numerator, int denominator) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }

    /** Items ordered by decreasing score, ties by their position in {@code items} (stable). */
    private int[] byDecreasingScore(double[] scores, int[] items) {
        Integer[] positions = new Integer[items.length];
        for (int p = 0; p < positions.length; p++) {
            positions[p] = p;
        }
        Arrays.sort(positions, (a, b) -> {
            int byScore = Double.compare(scores[items[b]], scores[items[a]]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        var order = new int[items.length];
        for (int p = 0; p < order.length; p++) {
            order[p] = items[positions[p]];
        }
        return order;
    }

    /** Rewrites a decreasing-score order as increasing score, keeping the stable position order inside ties. */
    private void reverseByIncreasing(int[] order, double[] scores) {
        int b = 0;
        var result = new int[order.length];
        int write = order.length;
        while (b < order.length) {
            int end = b;
            while (end < order.length && scores[order[end]] == scores[order[b]]) {
                end++;
            }
            write -= end - b;
            System.arraycopy(order, b, result, write, end - b);
            b = end;
        }
        System.arraycopy(result, 0, order, 0, order.length);
    }
}
