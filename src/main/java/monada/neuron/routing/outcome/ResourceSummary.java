package monada.neuron.routing.outcome;

import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.features.Feature;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resource data of an outcome, carried and never computed: usage counters with provenance, hypothetical API cost and
 * actual billing as separate quantities, each currency alone. There is no arithmetic here, and missing data is never
 * zero, never cheap and never a pass.
 *
 * @param declaration measured usage, or an explicit declaration that nothing billable was consumed
 * @param noBillableJustification required for {@code NO_BILLABLE_USAGE}, otherwise unknown
 * @param counters at most one counter per kind, canonical order
 * @param actualBilling amounts the host explicitly recorded as billed, at most one per currency
 * @param hypotheticalCosts hypothetical costs, at most one per currency
 */
public record ResourceSummary(
        UsageDeclaration declaration,
        Feature<String> noBillableJustification,
        List<UsageCounter> counters,
        List<Money> actualBilling,
        List<HypotheticalCost> hypotheticalCosts) {

    /** Maximum currencies reported for each of billing and hypothetical cost. */
    public static final int MAX_CURRENCIES = 4;

    /** Validates the declaration against the counters and copies the lists in canonical order. */
    public ResourceSummary {
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(noBillableJustification, "noBillableJustification must not be null");
        counters = Ordering.canonical(counters, Comparator.comparing(UsageCounter::kind), UsageKind.values().length,
                "counters");
        actualBilling = Ordering.canonical(actualBilling, Comparator.comparing(Money::currency), MAX_CURRENCIES,
                "actualBilling");
        hypotheticalCosts = Ordering.canonical(hypotheticalCosts,
                Comparator.comparing((HypotheticalCost c) -> c.amount().currency()), MAX_CURRENCIES,
                "hypotheticalCosts");
        boolean declared = noBillableJustification instanceof Feature.Known<String>;
        if (declaration == UsageDeclaration.NO_BILLABLE_USAGE) {
            if (!declared) {
                throw new IllegalArgumentException("NO_BILLABLE_USAGE requires a justification");
            }
            if (!counters.isEmpty()) {
                throw new IllegalArgumentException("NO_BILLABLE_USAGE must not carry usage counters");
            }
        } else if (declared) {
            throw new IllegalArgumentException("only NO_BILLABLE_USAGE carries a justification");
        }
        if (noBillableJustification instanceof Feature.Known<String> known) {
            OutcomeTokens.text(known.value(), "noBillableJustification");
        }
    }

    /** Usage was not recorded at all; nothing is claimed. */
    public static ResourceSummary unrecorded() {
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(), List.of(), List.of(), List.of());
    }

    /**
     * Kinds in {@code required} that have no known value: absent, unknown or not measured. A declared
     * {@code NO_BILLABLE_USAGE} needs none. This reports gaps; it never substitutes zero.
     */
    public List<UsageKind> unmeasured(Set<UsageKind> required) {
        Objects.requireNonNull(required, "required must not be null");
        if (declaration == UsageDeclaration.NO_BILLABLE_USAGE) {
            return List.of();
        }
        return required.stream().sorted()
                .filter(kind -> counters.stream()
                        .noneMatch(c -> c.kind() == kind && c.value() instanceof ResourceValue.Known))
                .toList();
    }
}
