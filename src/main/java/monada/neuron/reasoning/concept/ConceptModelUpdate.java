package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link ConceptFeedbackPolicy#apply}: either a new snapshot the caller may approve for the next cycle, or
 * the unchanged one (ADR 0045). The previous snapshot is never modified, so keeping it is the rollback.
 */
public sealed interface ConceptModelUpdate permits ConceptModelUpdate.Updated, ConceptModelUpdate.Unchanged {

    /** Returns the snapshot the batch was applied to. */
    ConceptModel previous();

    /** Returns the snapshot to use next: the new one when updated, the previous one otherwise. */
    ConceptModel current();

    /** Returns the rejected outcomes in batch order. */
    List<Rejected> rejected();

    /** Returns the work of the update. */
    Work work();

    /**
     * One rejected outcome.
     *
     * @param index position in the batch
     * @param outcome the outcome
     * @param reason why it was rejected
     */
    record Rejected(int index, ConceptOutcome outcome, ConceptFeedbackRejection reason) {

        /** Validates the components. */
        public Rejected {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative, got: " + index);
            }
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * Work of one update.
     *
     * @param considered outcomes in the batch
     * @param applied outcomes applied
     * @param positive applied outcomes whose verified leaf lies in the predicted subtree
     * @param negative applied outcomes whose verified leaf lies outside it
     * @param exemplarsAdded concept exemplars added (each non-silent applied outcome adds one per level of its leaf)
     * @param copiedBytes bytes of the learned arrays copied into the new snapshot, {@code 0} when unchanged
     */
    record Work(int considered, int applied, int positive, int negative, int exemplarsAdded, long copiedBytes) {

        /** Validates the counters. */
        public Work {
            if (considered < 0 || applied < 0 || positive < 0 || negative < 0 || exemplarsAdded < 0
                    || copiedBytes < 0 || applied > considered || positive + negative != applied) {
                throw new IllegalArgumentException("inconsistent work counters");
            }
        }
    }

    /**
     * At least one outcome was applied: {@code next} has revision {@code previous.revision() + 1}, the same
     * hierarchy, frame, temperature and thresholds.
     *
     * @param previous the snapshot the batch was applied to
     * @param next the new snapshot
     * @param applied the applied outcomes in batch order
     * @param rejected the rejected outcomes in batch order
     * @param work the work
     */
    record Updated(ConceptModel previous, ConceptModel next, List<ConceptOutcome> applied, List<Rejected> rejected,
                   Work work) implements ConceptModelUpdate {

        /** Validates and copies the components. */
        public Updated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            applied = List.copyOf(applied);
            rejected = List.copyOf(rejected);
            Objects.requireNonNull(work, "work must not be null");
            if (applied.isEmpty() || next.revision() != previous.revision() + 1) {
                throw new IllegalArgumentException("an update applies at least one outcome and advances the revision");
            }
        }

        @Override
        public ConceptModel current() {
            return next;
        }
    }

    /**
     * Nothing was applied; the previous snapshot stays current, with its revision.
     *
     * @param previous the snapshot the batch was applied to
     * @param rejected the rejected outcomes in batch order
     * @param work the work
     */
    record Unchanged(ConceptModel previous, List<Rejected> rejected, Work work) implements ConceptModelUpdate {

        /** Validates and copies the components. */
        public Unchanged {
            Objects.requireNonNull(previous, "previous must not be null");
            rejected = List.copyOf(rejected);
            Objects.requireNonNull(work, "work must not be null");
        }

        @Override
        public ConceptModel current() {
            return previous;
        }
    }
}
