package consumer.forge;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The runtime version probe of the readiness contract, Section 5.2. The five contract constants are compile time
 * constants, so a comparison written as {@code RoutingRequest.CONTRACT_VERSION.equals(...)} compares this host's own
 * inlined copy with itself and can never fire against a newer core jar. The probe reads the loaded class's field through
 * reflection instead and compares it with the literal this host was written for. Any difference, and any failure of the
 * read, is a version mismatch; the detail goes to the host log only.
 */
public final class VersionProbe {

    /** One contract constant and the value this host expects to find in it. */
    public record Expectation(Class<?> type, String field, String expected) {
        public Expectation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(expected, "expected");
        }
    }

    private VersionProbe() {
    }

    /** The five expectations of a host written against {@code forge-routing/1}, as literals. */
    public static List<Expectation> forgeRoutingV1() {
        return List.of(
                new Expectation(RoutingRequest.class, "CONTRACT_VERSION", "forge-routing/1"),
                new Expectation(TaskFeatures.class, "SCHEMA_VERSION", "task-features/1"),
                new Expectation(RoutingDecision.class, "CONTRACT_VERSION", "routing-decision/1"),
                new Expectation(LexicographicRoutingPolicy.class, "POLICY_ID", "route-lex"),
                new Expectation(LexicographicRoutingPolicy.class, "POLICY_VERSION", "1"));
    }

    /**
     * Probes every expectation in order.
     *
     * @return empty when every loaded field holds its expected value, otherwise the first mismatch or read failure,
     *         worded for the host log
     */
    public static Optional<String> mismatch(List<Expectation> expectations) {
        for (Expectation expectation : expectations) {
            String name = expectation.type().getSimpleName() + "." + expectation.field();
            Object found;
            try {
                var field = expectation.type().getField(expectation.field());
                if (!Modifier.isStatic(field.getModifiers())) {
                    return Optional.of(name + " is not static");
                }
                found = field.get(null);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                return Optional.of(name + " could not be read: " + e.getClass().getSimpleName());
            }
            if (!(found instanceof String value)) {
                return Optional.of(name + " is not a String");
            }
            if (!value.equals(expectation.expected())) {
                return Optional.of(name + " expected " + expectation.expected() + ", found " + value);
            }
        }
        return Optional.empty();
    }
}
