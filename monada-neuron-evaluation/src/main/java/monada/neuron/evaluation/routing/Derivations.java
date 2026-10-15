package monada.neuron.evaluation.routing;

import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.ScoreRule;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * How many feedback derivations of one run produced each rule and each no-update reason, counted at every delivery
 * whether or not events are recorded, so an unrecorded (measured) run can be checked to have derived exactly what its
 * recorded reference derived. A rebuild's re-applications are not deliveries and are not counted.
 *
 * @param rules derivations per score rule; rules never derived are absent
 * @param noUpdates derivations per no-update reason; reasons never derived are absent
 */
public record Derivations(Map<ScoreRule, Integer> rules, Map<NoUpdateReason, Integer> noUpdates) {

    /** Copies the maps in enum order. */
    public Derivations {
        rules = Collections.unmodifiableMap(rules.isEmpty() ? new EnumMap<>(ScoreRule.class) : new EnumMap<>(rules));
        noUpdates = Collections.unmodifiableMap(noUpdates.isEmpty() ? new EnumMap<>(NoUpdateReason.class)
                : new EnumMap<>(noUpdates));
    }

    /** No derivation at all: the static arm's. */
    public static Derivations none() {
        return new Derivations(Map.of(), Map.of());
    }

    /** Total derivations. */
    public int total() {
        return rules.values().stream().mapToInt(Integer::intValue).sum()
                + noUpdates.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The mutable counter of one run: one array increment per delivery, no allocation. */
    static final class Tally {

        private final int[] rules = new int[ScoreRule.values().length];
        private final int[] noUpdates = new int[NoUpdateReason.values().length];

        void count(FeedbackDerivation derivation) {
            switch (derivation) {
                case FeedbackDerivation.Derived derived -> rules[derived.feedback().rule().ordinal()]++;
                case FeedbackDerivation.NoUpdate none -> noUpdates[none.reason().ordinal()]++;
            }
        }

        Derivations build() {
            var byRule = new EnumMap<ScoreRule, Integer>(ScoreRule.class);
            for (ScoreRule rule : ScoreRule.values()) {
                if (rules[rule.ordinal()] > 0) {
                    byRule.put(rule, rules[rule.ordinal()]);
                }
            }
            var byReason = new EnumMap<NoUpdateReason, Integer>(NoUpdateReason.class);
            for (NoUpdateReason reason : NoUpdateReason.values()) {
                if (noUpdates[reason.ordinal()] > 0) {
                    byReason.put(reason, noUpdates[reason.ordinal()]);
                }
            }
            return new Derivations(byRule, byReason);
        }
    }
}
