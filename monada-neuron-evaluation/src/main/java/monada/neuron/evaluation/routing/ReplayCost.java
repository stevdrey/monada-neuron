package monada.neuron.evaluation.routing;

import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.ResourceSummary;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Whole-chain hypothetical cost to an accepted outcome, as supplied by Store evaluation tooling ({@code
 * CostToAcceptedOutcome}, execution-memory contract v1 section 8, Store #99) or by a hand-calculated fixture.
 *
 * <p>The replay only carries and reports it: Neuron routing never reads it and never computes it. {@code AVAILABLE}
 * is a complete hypothetical API-equivalent amount, {@code PARTIAL} is a lower bound because some chain stage is not
 * covered, and {@code UNAVAILABLE} carries no amount (no validated acceptance, or no usage recorded). Amounts are per
 * currency and are never summed across currencies. The cost covers the whole attempt chain up to acceptance, so it is
 * attributed once per execution and never split among stages or routes.
 *
 * @param status completeness of the amount
 * @param amounts one amount per currency in currency order, at most {@value ResourceSummary#MAX_CURRENCIES}; empty
 *        when unavailable, and possibly empty for a partial cost with no priced stage
 * @param reasons why the cost is partial or unavailable (Store {@code CostReason} codes or fixture notes), sorted
 */
public record ReplayCost(Status status, List<Money> amounts, List<String> reasons) {

    /** The only cost basis: what the chain would have cost at API list prices, whatever the billing mode was. */
    public static final String BASIS = "HYPOTHETICAL_API_EQUIVALENT";

    /** Completeness of a cost, mirroring the Store's {@code CostToAcceptedOutcome.Status}. */
    public enum Status {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE
    }

    /** Validates and copies the lists in canonical order. */
    public ReplayCost {
        Objects.requireNonNull(status, "status must not be null");
        amounts = amounts.stream().sorted(Comparator.comparing(Money::currency)).toList();
        reasons = reasons.stream().sorted().toList();
        if (amounts.size() > ResourceSummary.MAX_CURRENCIES) {
            throw new IllegalArgumentException(
                    "at most " + ResourceSummary.MAX_CURRENCIES + " currencies, got: " + amounts.size());
        }
        var currencies = new HashSet<String>();
        for (Money amount : amounts) {
            if (!currencies.add(amount.currency())) {
                throw new IllegalArgumentException("currency " + amount.currency() + " appears more than once");
            }
        }
        if (status == Status.UNAVAILABLE && !amounts.isEmpty()) {
            throw new IllegalArgumentException("an unavailable cost carries no amount");
        }
        if (status != Status.AVAILABLE && reasons.isEmpty()) {
            throw new IllegalArgumentException("a " + status + " cost must say why");
        }
    }

    /** A complete cost. */
    public static ReplayCost available(Money... amounts) {
        return new ReplayCost(Status.AVAILABLE, List.of(amounts), List.of());
    }

    /** A lower bound: the listed stages were not covered. */
    public static ReplayCost partial(List<Money> lowerBound, String... reasons) {
        return new ReplayCost(Status.PARTIAL, lowerBound, List.of(reasons));
    }

    /** No amount at all. */
    public static ReplayCost unavailable(String... reasons) {
        return new ReplayCost(Status.UNAVAILABLE, List.of(), List.of(reasons));
    }
}
