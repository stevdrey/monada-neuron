package consumer.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The staleness rule of the readiness contract, Section 6.1, placed where that section puts it: in host code, run
 * immediately before advice is shown as actionable or receives a disposition, never inside Neuron. Checks 1 to 4 of the
 * section are the four kinds here; check 5 (revalidation before executing) does not exist at Phase 2 because nothing
 * executes. The guard is pure over its arguments and keeps nothing.
 */
public final class HostGuard {

    /** The checks of Section 6.1, in its order. */
    public enum Check {
        DRAFT_READY,
        EPOCH,
        SOURCE_FINGERPRINT,
        CONTEXT_FINGERPRINT,
        CONSTRAINTS_FINGERPRINT,
        CATALOG_VERSION
    }

    /** Whether advice may still be used. */
    public sealed interface Verdict permits Verdict.Fresh, Verdict.Stale {
        /** Every check held. */
        record Fresh() implements Verdict { }

        /** At least one check failed; the advice is discarded and re requested. */
        record Stale(List<Check> failed) implements Verdict {
            public Stale {
                failed = List.copyOf(failed);
                if (failed.isEmpty()) {
                    throw new IllegalArgumentException("Stale names at least one failed check");
                }
            }
        }
    }

    private HostGuard() {
    }

    /**
     * Compares the advice's captured inputs with the host's current ones.
     *
     * @param advice the advice about to be used
     * @param current the draft as it is now
     * @param fingerprints the fingerprints freshly recomputed from {@code current}
     * @param catalogVersion the current catalog snapshot's version, absent when no catalog is configured
     */
    public static Verdict check(Advice advice, TaskDraft current, ForgeRoutingAdapter.Fingerprints fingerprints,
            Optional<String> catalogVersion) {
        Objects.requireNonNull(advice, "advice");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(fingerprints, "fingerprints");
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        var request = advice.request();
        var failed = new ArrayList<Check>(Check.values().length);
        if (!current.ready()) {
            failed.add(Check.DRAFT_READY);
        }
        if (current.workspace().map(TaskDraft.Workspace::epoch).orElse(-1) != advice.capturedEpoch()) {
            failed.add(Check.EPOCH);
        }
        if (!fingerprints.source().equals(request.sourceFingerprint())) {
            failed.add(Check.SOURCE_FINGERPRINT);
        }
        if (!fingerprints.context().equals(request.contextFingerprint())) {
            failed.add(Check.CONTEXT_FINGERPRINT);
        }
        if (!fingerprints.constraints().equals(request.constraintsFingerprint())) {
            failed.add(Check.CONSTRAINTS_FINGERPRINT);
        }
        if (!catalogVersion.map(advice.catalogVersion()::equals).orElse(false)) {
            failed.add(Check.CATALOG_VERSION);
        }
        return failed.isEmpty() ? new Verdict.Fresh() : new Verdict.Stale(failed);
    }
}
