package monada.neuron.reasoning.concept;

import java.util.Arrays;
import java.util.Objects;

/**
 * Full score table of one prediction, per concept ordinal (ADR 0043). It lets a caller audit a prediction and lets an
 * evaluation build controls from exactly the same scoring inputs (for example a flat ranking over the leaves).
 *
 * <ul>
 *   <li>{@link #similarity(int)}: cosine of the evidence's normalized magnitude spectrum with the concept's
 *       centroid, in {@code [0, 1]}; {@code 0} for a concept without exemplars.</li>
 *   <li>{@link #currentScore(int)}: posterior mass of the concept's subtree for the <b>current</b> window, from the
 *       per-level spectral evidence and the prior given by the previous concept.</li>
 *   <li>{@link #nextScore(int)}: mass of the concept's subtree for the <b>next</b> window after the transition
 *       table. Root masses sum to {@code 1} within rounding.</li>
 * </ul>
 */
public final class ConceptScores {

    private final double[] similarity;
    private final double[] current;
    private final double[] next;

    ConceptScores(double[] similarity, double[] current, double[] next) {
        this.similarity = similarity;
        this.current = current;
        this.next = next;
    }

    /** Returns the number of concepts. */
    public int size() {
        return next.length;
    }

    /** Returns the spectral similarity of a concept's centroid with the evidence. */
    public double similarity(int ordinal) {
        return similarity[Objects.checkIndex(ordinal, similarity.length)];
    }

    /** Returns the current-window subtree mass of a concept. */
    public double currentScore(int ordinal) {
        return current[Objects.checkIndex(ordinal, current.length)];
    }

    /** Returns the next-window subtree mass of a concept. */
    public double nextScore(int ordinal) {
        return next[Objects.checkIndex(ordinal, next.length)];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConceptScores that && Arrays.equals(similarity, that.similarity)
                && Arrays.equals(current, that.current) && Arrays.equals(next, that.next);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(next);
    }

    @Override
    public String toString() {
        return "ConceptScores[next=" + Arrays.toString(next) + "]";
    }
}
