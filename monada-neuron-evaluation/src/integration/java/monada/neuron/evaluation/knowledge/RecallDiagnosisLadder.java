package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeScalingCorpus.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The controlled corpus ladder of the B2 recall diagnosis (Issue #135): four points built with the #111 scaling corpus
 * rule ({@link HtmlKnowledgeScalingCorpus#point}), so the base-corpus prefix and the generated distractors are the same
 * chunks as in {@code scaling-v1}.
 *
 * <ul>
 *   <li>{@code base-25}: the first 25 base chunks, no distractor (identical to #111's N = 25);</li>
 *   <li>{@code base-33}: the complete 33-chunk base, no distractor;</li>
 *   <li>{@code n100} and {@code n500}: the complete base plus 67 and 467 distractors (identical to #111's N = 100 and
 *       N = 500).</li>
 * </ul>
 *
 * <p>Study A ({@code base-25 -> base-33}) changes only the base corpus; study B ({@code base-33 -> n100 -> n500})
 * changes only the number of distractors. Every point scores the fixed #111 curve cohort, so the queries never change
 * along either study.
 */
final class RecallDiagnosisLadder {

    /** One rung of the ladder. */
    enum Rung {
        BASE_25("base-25", 25),
        BASE_33("base-33", 33),
        N100("n100", 100),
        N500("n500", 500);

        private final String label;
        private final int size;

        Rung(String label, int size) {
            this.label = label;
            this.size = size;
        }

        /** Returns the label used in check names, options and reports. */
        String label() {
            return label;
        }

        /** Returns the total number of chunks {@code N}. */
        int size() {
            return size;
        }

        /** Parses a label. */
        static Rung ofLabel(String label) {
            return Arrays.stream(values()).filter(rung -> rung.label.equals(label)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown ladder point '" + label + "'; expected one"
                            + " of " + Arrays.stream(values()).map(Rung::label).toList()));
        }
    }

    /**
     * One built rung.
     *
     * @param rung the rung
     * @param point the point corpus with its base and generated counts
     */
    record LadderPoint(Rung rung, Point point) {

        LadderPoint {
            Objects.requireNonNull(rung, "rung must not be null");
            Objects.requireNonNull(point, "point must not be null");
        }
    }

    /** The two controlled studies, each an ordered list of rungs whose only change is the stated one. */
    static final List<Rung> BASE_GROWTH = List.of(Rung.BASE_25, Rung.BASE_33);
    static final List<Rung> DISTRACTOR_GROWTH = List.of(Rung.BASE_33, Rung.N100, Rung.N500);

    private RecallDiagnosisLadder() {
    }

    /**
     * Builds {@code rungs} (distinct, in ladder order) over {@code base}.
     *
     * @throws IllegalArgumentException when rungs repeat or are out of order
     */
    static List<LadderPoint> build(HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet, List<Rung> rungs,
            long seed) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(querySet, "querySet must not be null");
        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("at least one ladder point is required");
        }
        var points = new ArrayList<LadderPoint>(rungs.size());
        for (var i = 0; i < rungs.size(); i++) {
            if (i > 0 && rungs.get(i).ordinal() <= rungs.get(i - 1).ordinal()) {
                throw new IllegalArgumentException("ladder points must be distinct and in ladder order: " + rungs);
            }
            points.add(new LadderPoint(rungs.get(i),
                    HtmlKnowledgeScalingCorpus.point(base, querySet, rungs.get(i).size(), seed)));
        }
        return List.copyOf(points);
    }
}
