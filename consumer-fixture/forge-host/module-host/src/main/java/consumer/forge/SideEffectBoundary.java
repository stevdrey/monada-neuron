package consumer.forge;

import java.util.Objects;

/**
 * The one gate through which this host would ever act: run a route or touch a workspace path. At Forge Phase 2 nothing
 * stands behind it, so every call refuses and counts the attempt, and the fixture asserts that the whole review to
 * disposition flow leaves the count at zero. Advice, a confirmed draft, a receipt and a disposition are information;
 * authorization would be a separate explicit host action here (Forge security invariant 2). This is one possible host
 * policy, not an authorization implementation.
 */
public final class SideEffectBoundary {

    private int reachedCalls;

    /** Refuses to run a route. */
    public void execute(String routeId) {
        Objects.requireNonNull(routeId, "routeId");
        reachedCalls++;
        throw new UnsupportedOperationException("advice is not authorization: this host has no execution path");
    }

    /** Refuses to read or write a workspace path. */
    public void access(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        reachedCalls++;
        throw new UnsupportedOperationException("routing never accesses paths: this host has no file access path");
    }

    /** Calls that reached this boundary, refused or not; the fixture requires zero. */
    public int reachedCalls() {
        return reachedCalls;
    }
}
