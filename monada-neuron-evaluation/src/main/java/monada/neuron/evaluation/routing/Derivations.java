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
 * per-rule counts agree. The counts per rule and reason are kept for reporting.
 *
 * @param rules derivations per score rule; rules never derived are absent
 * @param noUpdates derivations per no-update reason; reasons never derived are absent
 * @param sequence one code per delivery, in delivery order
 */
public record Derivations(Map<ScoreRule, Integer> rules, Map<NoUpdateReason, Integer> noUpdates, int[] sequence) {

    /** Copies the maps in enum order and the sequence. */
    public Derivations {
        rules = Collections.unmodifiableMap(rules.isEmpty() ? new EnumMap<>(ScoreRule.class) : new EnumMap<>(rules));
        noUpdates = Collections.unmodifiableMap(noUpdates.isEmpty() ? new EnumMap<>(NoUpdateReason.class)
                : new EnumMap<>(noUpdates));
        sequence = Objects.requireNonNull(sequence, "sequence must not be null").clone();
    }

    /** No derivation at all: the static arm's. */
    public static Derivations none() {
        return new Derivations(Map.of(), Map.of(), new int[0]);
    }

    /** A copy of the per-delivery codes. */
    @Override
    public int[] sequence() {
        return sequence.clone();
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
        return other instanceof Derivations that && rules.equals(that.rules) && noUpdates.equals(that.noUpdates)
                && Arrays.equals(sequence, that.sequence);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * rules.hashCode() + noUpdates.hashCode()) + Arrays.hashCode(sequence);
    }

    @Override
    public String toString() {
        return "Derivations[rules=" + rules + ", noUpdates=" + noUpdates + ", deliveries=" + sequence.length + "]";
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
            ScoreRule[] allRules = ScoreRule.values();
            NoUpdateReason[] allReasons = NoUpdateReason.values();
            int[] perRule = new int[allRules.length];
            int[] perReason = new int[allReasons.length];
            for (int i = 0; i < size; i++) {
                int code = sequence[i];
                if (code > 0) {
                    perRule[(code - 1) >>> 1]++;
                } else {
                    perReason[-code - 1]++;
                }
            }
            var byRule = new EnumMap<ScoreRule, Integer>(ScoreRule.class);
            for (ScoreRule rule : allRules) {
                if (perRule[rule.ordinal()] > 0) {
                    byRule.put(rule, perRule[rule.ordinal()]);
                }
            }
            var byReason = new EnumMap<NoUpdateReason, Integer>(NoUpdateReason.class);
            for (NoUpdateReason reason : allReasons) {
                if (perReason[reason.ordinal()] > 0) {
                    byReason.put(reason, perReason[reason.ordinal()]);
                }
            }
            return new Derivations(byRule, byReason, Arrays.copyOf(sequence, size));
        }
    }
}
