package monada.neuron.evaluation.routing;

import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.ScoreRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What every delivery of one run derived, recorded at each delivery whether or not events are recorded, so an
 * unrecorded (measured) run can be checked to have derived exactly what its recorded reference derived, observation by
 * observation. A rebuild's re-applications are not deliveries and are not recorded.
 *
 * <p>{@link #sequence} holds one code per delivery, in delivery order: {@code ((rule ordinal << 1) | credits) + 1}
 * for derived feedback (positive), {@code -(reason ordinal + 1)} for a no-update (negative). Two runs that derive the
 * same rules in a different order, or credit a cohort on a different delivery, therefore differ, even when their
 * per-rule counts agree. The counts per rule and reason are computed from the sequence, its only source, so they can
 * never contradict it.
 *
 * @param sequence one valid code per delivery, in delivery order
 */
public record Derivations(int[] sequence) {

    private static final ScoreRule[] RULES = ScoreRule.values();
    private static final NoUpdateReason[] REASONS = NoUpdateReason.values();

    /** Validates every code and copies the sequence. */
    public Derivations {
        sequence = Objects.requireNonNull(sequence, "sequence must not be null").clone();
        for (int i = 0; i < sequence.length; i++) {
            int code = sequence[i];
            boolean valid = code > 0 ? (code - 1) >>> 1 < RULES.length : code < 0 && -code - 1 < REASONS.length;
            if (!valid) {
                throw new IllegalArgumentException("delivery " + i + " has no derivation code: " + code);
            }
        }
    }

    /** No derivation at all: the static arm's. */
    public static Derivations none() {
        return new Derivations(new int[0]);
    }

    /** A copy of the per-delivery codes. */
    @Override
    public int[] sequence() {
        return sequence.clone();
    }

    /** Derivations per score rule, in rule order; rules never derived are absent. */
    public Map<ScoreRule, Integer> rules() {
        var byRule = new EnumMap<ScoreRule, Integer>(ScoreRule.class);
        for (int code : sequence) {
            if (code > 0) {
                byRule.merge(RULES[(code - 1) >>> 1], 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(byRule);
    }

    /** Derivations per no-update reason, in reason order; reasons never derived are absent. */
    public Map<NoUpdateReason, Integer> noUpdates() {
        var byReason = new EnumMap<NoUpdateReason, Integer>(NoUpdateReason.class);
        for (int code : sequence) {
            if (code < 0) {
                byReason.merge(REASONS[-code - 1], 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(byReason);
    }

    /** Total derivations. */
    public int total() {
        return sequence.length;
    }

    /** Index of the first delivery whose derivation differs from {@code other}'s, or -1 when they are equal. */
    public int firstDifference(Derivations other) {
        return Arrays.mismatch(sequence, other.sequence);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Derivations that && Arrays.equals(sequence, that.sequence);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sequence);
    }

    @Override
    public String toString() {
        return "Derivations[rules=" + rules() + ", noUpdates=" + noUpdates() + ", deliveries=" + sequence.length + "]";
    }

    /** The mutable recorder of one run: one array write per delivery, growing only past its presized capacity. */
    static final class Tally {

        private int[] sequence;
        private int size;

        /** A recorder sized for {@code expected} deliveries. */
        Tally(int expected) {
            this.sequence = new int[Math.max(expected, 1)];
        }

        void count(FeedbackDerivation derivation) {
            if (size == sequence.length) {
                sequence = Arrays.copyOf(sequence, sequence.length * 2);
            }
            sequence[size++] = switch (derivation) {
                case FeedbackDerivation.Derived derived -> ((derived.feedback().rule().ordinal() << 1)
                        | (derived.feedback().entries().isEmpty() ? 0 : 1)) + 1;
                case FeedbackDerivation.NoUpdate none -> -(none.reason().ordinal() + 1);
            };
        }

        Derivations build() {
            return new Derivations(Arrays.copyOf(sequence, size));
        }
    }
}
