package monada.neuron.routing.outcome;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An exact, non-negative decimal amount in one currency. Neuron carries amounts and never adds, converts or compares
 * them; currencies are never summed across.
 *
 * @param amount exact decimal, at least zero, at most 38 digits of precision; the scale is kept as supplied
 * @param currency three upper-case ASCII letters (ISO 4217 style)
 */
public record Money(BigDecimal amount, String currency) {

    /** Validates the amount and currency. */
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative, got: " + amount);
        }
        if (amount.precision() > 38) {
            throw new IllegalArgumentException("amount precision exceeds 38 digits");
        }
        if (currency.length() != 3 || !currency.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException("currency must be three upper-case ASCII letters, got: " + currency);
        }
    }
}
