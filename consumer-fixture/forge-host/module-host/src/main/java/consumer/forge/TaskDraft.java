package consumer.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A small local stand in for Forge's reviewed task draft. Forge has no stable published host API yet, so this fixture
 * carries only the shape its host adapter reads: a host minted workspace id with an epoch and a source revision, free
 * text the adapter never forwards, a scope selection, the characteristics a person confirmed from fixed lists, and the
 * observations the context collection measured. It imports nothing from Neuron; {@link ForgeRoutingAdapter} is the
 * only place where this type and a Neuron type meet (readiness contract, Section 4.1).
 *
 * <p>Confirming a draft is an in memory state change and nothing more. Any later change to the workspace, the
 * specification, the scope, the review or the context withdraws it (Forge security invariant 3), which is what the host
 * guard later detects as stale advice.
 *
 * @param workspace the registered workspace, absent before one is selected
 * @param specification the manually entered task text, absent before one is entered
 * @param scope the path authorization the person selected; {@link Scope.Unset} permits nothing
 * @param review the characteristics a person picked from fixed host lists
 * @param context what the context collection measured, each value absent when it did not run or could not measure
 * @param state the review state
 */
public record TaskDraft(
        Optional<Workspace> workspace,
        Optional<Specification> specification,
        Scope scope,
        Review review,
        Context context,
        State state) {

    /** Review states of a draft; only {@link #READY} may feed a request. */
    public enum State { DRAFT, REVIEWABLE, READY }

    /** Prerequisites a draft can lack, in Forge's own vocabulary. */
    public enum Missing { WORKSPACE, SPECIFICATION, SCOPE }

    /** A human toggle; absent in {@link Review} means the person did not toggle it. */
    public enum Toggle { REQUIRED, NOT_REQUIRED }

    /**
     * A registered workspace. The id is minted by the host per registration and is never the path nor a digest of it;
     * the epoch bumps when the workspace is replaced; the source revision is the host's own revision token.
     */
    public record Workspace(String scopeId, int epoch, String sourceRevision) {
        public Workspace {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(sourceRevision, "sourceRevision");
            if (scopeId.isBlank() || scopeId.contains("/") || scopeId.contains("\\")) {
                throw new IllegalArgumentException("scopeId is an opaque host id, never a path: " + scopeId);
            }
        }
    }

    /** The task text as entered. It is data only and never leaves the host (readiness contract, Section 4.3). */
    public record Specification(String title, String description) {
        public Specification {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
        }
    }

    /** The person's path selection; the entries are Forge authorization data and never enter Neuron. */
    public sealed interface Scope permits Scope.Unset, Scope.WholeWorkspace, Scope.Directories {
        /** Nothing selected yet: permits nothing. */
        record Unset() implements Scope { }

        /** The whole workspace, selected explicitly. */
        record WholeWorkspace() implements Scope { }

        /** Selected directories, relative to the workspace root. */
        record Directories(List<String> relativeDirectories) implements Scope {
            public Directories {
                relativeDirectories = List.copyOf(relativeDirectories);
                if (relativeDirectories.isEmpty()) {
                    throw new IllegalArgumentException("a directory scope names at least one directory");
                }
            }
        }
    }

    /** What a person confirmed from the host's fixed pick lists; absent means not picked. */
    public record Review(
            Optional<String> stageKind,
            Optional<String> category,
            Optional<Toggle> tests,
            Optional<Toggle> security) {
        public Review {
            Objects.requireNonNull(stageKind, "stageKind");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(tests, "tests");
            Objects.requireNonNull(security, "security");
        }

        public static Review none() {
            return new Review(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** What the context collection measured; absent means it did not run or could not measure the value. */
    public record Context(
            Optional<List<String>> languages,
            Optional<List<String>> domains,
            OptionalLong changeSize,
            OptionalLong contextSize) {
        public Context {
            languages = Objects.requireNonNull(languages, "languages").map(List::copyOf);
            domains = Objects.requireNonNull(domains, "domains").map(List::copyOf);
            Objects.requireNonNull(changeSize, "changeSize");
            Objects.requireNonNull(contextSize, "contextSize");
        }

        public static Context none() {
            return new Context(Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
        }
    }

    public TaskDraft {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(state, "state");
    }

    /** A draft with nothing selected, entered or reviewed. */
    public static TaskDraft empty() {
        return new TaskDraft(Optional.empty(), Optional.empty(), new Scope.Unset(), Review.none(), Context.none(),
                State.DRAFT);
    }

    /** The prerequisites this draft still lacks, in declaration order; empty does not mean confirmed. */
    public List<Missing> missing() {
        var missing = new ArrayList<Missing>(3);
        if (workspace.isEmpty()) {
            missing.add(Missing.WORKSPACE);
        }
        if (specification.isEmpty()) {
            missing.add(Missing.SPECIFICATION);
        }
        if (scope instanceof Scope.Unset) {
            missing.add(Missing.SCOPE);
        }
        return List.copyOf(missing);
    }

    public boolean ready() {
        return state == State.READY;
    }

    /**
     * Confirms the review. An in memory state change only: it authorizes nothing and is withdrawn by any later change.
     *
     * @throws IllegalStateException if a prerequisite is missing
     */
    public TaskDraft confirm() {
        var missing = missing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("cannot confirm a draft that lacks " + missing);
        }
        return new TaskDraft(workspace, specification, scope, review, context, State.READY);
    }

    public TaskDraft withWorkspace(Workspace value) {
        return withdrawn(Optional.of(value), specification, scope, review, context);
    }

    public TaskDraft withSpecification(Specification value) {
        return withdrawn(workspace, Optional.of(value), scope, review, context);
    }

    public TaskDraft withScope(Scope value) {
        return withdrawn(workspace, specification, value, review, context);
    }

    public TaskDraft withReview(Review value) {
        return withdrawn(workspace, specification, scope, value, context);
    }

    public TaskDraft withContext(Context value) {
        return withdrawn(workspace, specification, scope, review, value);
    }

    /** Every change withdraws a confirmation: the person reviews again before the draft is READY. */
    private static TaskDraft withdrawn(Optional<Workspace> workspace, Optional<Specification> specification,
            Scope scope, Review review, Context context) {
        return new TaskDraft(workspace, specification, scope, review, context, State.REVIEWABLE);
    }
}
