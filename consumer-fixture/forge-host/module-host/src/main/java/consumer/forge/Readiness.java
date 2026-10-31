package consumer.forge;

import java.util.List;
import java.util.Objects;

/**
 * The host readiness outcomes of the readiness contract, Section 5.1, in host vocabulary. None is a Neuron type.
 * {@code STALE} is not here: it is the verdict of the {@link HostGuard} over advice that already exists.
 */
public sealed interface Readiness permits Readiness.NotReady, Readiness.Unavailable, Readiness.Advised {

    /** What a draft or the host still lacks before a request can be built. */
    enum Prerequisite { WORKSPACE, SPECIFICATION, SCOPE, CONFIRMATION, STAGE }

    /** Why a host that could build a request did not call Neuron. */
    enum Reason { INTEGRATION_OFF, NO_CATALOG, VERSION_MISMATCH, CANCELLED }

    /** The host cannot build a request yet; no Neuron object was built. */
    record NotReady(List<Prerequisite> missing) implements Readiness {
        public NotReady {
            missing = List.copyOf(missing);
            if (missing.isEmpty()) {
                throw new IllegalArgumentException("NotReady names at least one prerequisite");
            }
        }
    }

    /** The host could build a request but did not call Neuron; {@code decide} did not run. */
    record Unavailable(Reason reason) implements Readiness {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code decide} was called and returned; the advice is of any of the three decision variants. */
    record Advised(Advice advice) implements Readiness {
        public Advised {
            Objects.requireNonNull(advice, "advice");
        }
    }
}
