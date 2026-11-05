package monada.neuron.evaluation.bilingual;

import java.util.List;

/**
 * One arm of {@code bilingual-sense/1}. Every arm receives the same inputs (the {@link SenseItem} system input only),
 * the same training items with gold and the same tokenization, and is deterministic and single-threaded when scoring.
 *
 * <p>An arm's grid is {@code configurations() x thresholds()}, threshold innermost, in the registered order. The forced
 * choice and confidence depend only on the configuration; the threshold only decides answer or {@code ABSTAIN}
 * (abstain when {@code s < tau}), so selection scores each configuration once.
 */
interface SenseArm {

    /** Window half-widths of the registered grids, {@code w in {3, 5, S}}, in grid order. */
    int[] WINDOWS = {3, 5, SenseTokenizer.SENTENCE};

    /** Thresholds {@code {0, 0.1, ..., 0.9}} of arms B, N and W. */
    double[] TENTHS = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};

    /** Returns the registered label of window index {@code w}: {@code 3}, {@code 5} or {@code S}. */
    static String windowLabel(int w) {
        return WINDOWS[w] == SenseTokenizer.SENTENCE ? "S" : Integer.toString(WINDOWS[w]);
    }

    /** Returns the arm's short name (A, B, C, N). */
    String name();

    /** Returns the number of non-threshold configurations. */
    int configurations();

    /** Returns a readable description of a configuration, for the report. */
    String describe(int configuration);

    /** Returns the registered thresholds, in grid order. */
    double[] thresholds();

    /** Fits the arm on {@code ANS} training items in training-file order (no {@code UNA}/{@code OOI} item is used). */
    void fit(List<TrainingExample> training);

    /** Returns the forced choice and confidence of an item under a configuration. */
    Prediction predict(SenseItem item, int configuration);

    /** Returns an estimate of the retained model state in bytes (protocol limit 64 MiB). */
    long retainedBytes();

    /** Returns the grid size {@code configurations() x thresholds()}. */
    default int gridSize() {
        return configurations() * thresholds().length;
    }

    /**
     * One {@code ANS} training item.
     *
     * @param input the system input
     * @param gold its gold set; each of its {@code m} clusters receives weight {@code 1/m}
     */
    record TrainingExample(SenseItem input, SenseGold.Answerable gold) {
    }

    /**
     * Forced output of one item.
     *
     * @param choice the forced cluster ordinal {@code k*}, or {@code -1} for a lemma with no inventory
     * @param confidence {@code s in [0, 1]}
     */
    record Prediction(int choice, double confidence) {

        /** The output for a lemma with no inventory: no cluster, always {@code ABSTAIN}. */
        static final Prediction NO_INVENTORY = new Prediction(-1, 0.0);

        /** Returns whether the item is answered at threshold {@code tau}. */
        boolean answers(double tau) {
            return choice >= 0 && confidence >= tau;
        }
    }
}
