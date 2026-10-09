package monada.neuron.routing.catalog;

import java.util.Collection;
import java.util.List;

/**
 * Host-policy hard constraints of one routing request. They never originate from task-generated text.
 *
 * <p>An empty required set is always satisfied. {@code permittedModes} and {@code allowedLocalities} are
 * allow-lists: empty means nothing is permitted, so every route fails closed.
 *
 * @param requiredCapabilities capabilities every route must offer
 * @param requiredTools tools every route must offer
 * @param allowedLocalities localities a route may have
 * @param permittedModes execution modes the host permits
 * @param requiredContextSize context size the route must accommodate in host units; {@code 0} means no requirement
 */
public record HardRequirements(
        List<String> requiredCapabilities,
        List<String> requiredTools,
        List<String> allowedLocalities,
        List<String> permittedModes,
        long requiredContextSize) {

    /** Validates bounds and canonicalizes the sets. */
    public HardRequirements {
        requiredCapabilities = RouteTokens.canonicalSet(requiredCapabilities, "requiredCapabilities");
        requiredTools = RouteTokens.canonicalSet(requiredTools, "requiredTools");
        allowedLocalities = RouteTokens.canonicalSet(allowedLocalities, "allowedLocalities");
        permittedModes = RouteTokens.canonicalSet(permittedModes, "permittedModes");
        if (requiredContextSize < 0) {
            throw new IllegalArgumentException("requiredContextSize must be non-negative, got: " + requiredContextSize);
        }
    }

    /** Creates requirements with no required capability, tool or context size. */
    public static HardRequirements allowing(Collection<String> allowedLocalities, Collection<String> permittedModes) {
        return new HardRequirements(List.of(), List.of(), RouteTokens.canonicalSet(allowedLocalities, "allowedLocalities"),
                RouteTokens.canonicalSet(permittedModes, "permittedModes"), 0L);
    }
}
