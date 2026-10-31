package consumer.forge;

import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Missing;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Specification;
import consumer.forge.TaskDraft.State;
import consumer.forge.TaskDraft.Toggle;
import consumer.forge.TaskDraft.Workspace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static consumer.forge.SampleInputs.SCOPE;
import static consumer.forge.SampleInputs.SPECIFICATION;
import static consumer.forge.SampleInputs.WORKSPACE;
import static consumer.forge.SampleInputs.reviewedDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reviewed draft stub: prerequisites, confirmation and its withdrawal. */
class TaskDraftTest {

    // covers: AC-2
    @Test
    void emptyDraftLacksEveryPrerequisiteInDeclarationOrder() {
        var draft = TaskDraft.empty();

        assertEquals(List.of(Missing.WORKSPACE, Missing.SPECIFICATION, Missing.SCOPE), draft.missing());
        assertEquals(State.DRAFT, draft.state());
        assertFalse(draft.ready());
    }

    // covers: AC-2
    @Test
    void missingListsOnlyWhatIsStillAbsent() {
        var draft = TaskDraft.empty().withWorkspace(WORKSPACE).withScope(SCOPE);

        assertEquals(List.of(Missing.SPECIFICATION), draft.missing());
    }

    // covers: AC-2
    @Test
    void unsetScopeCannotBeConfirmed() {
        var draft = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION);

        var refused = assertThrows(IllegalStateException.class, draft::confirm);

        assertEquals("cannot confirm a draft that lacks [SCOPE]", refused.getMessage());
    }

    // covers: AC-2
    @Test
    void confirmMakesACompleteDraftReadyWithoutChangingItsContent() {
        var reviewable = TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION).withScope(SCOPE);

        var ready = reviewable.confirm();

        assertTrue(ready.ready());
        assertEquals(State.REVIEWABLE, reviewable.state());
        assertEquals(reviewable.workspace(), ready.workspace());
        assertEquals(reviewable.specification(), ready.specification());
        assertEquals(reviewable.scope(), ready.scope());
        assertEquals(reviewable.review(), ready.review());
        assertEquals(reviewable.context(), ready.context());
    }

    // covers: AC-2
    @Test
    void everyChangeWithdrawsTheConfirmation() {
        var ready = reviewedDraft();

        assertEquals(State.REVIEWABLE, ready.withWorkspace(new Workspace("ws-other", 1, "rev-1")).state());
        assertEquals(State.REVIEWABLE, ready.withSpecification(new Specification("t", "d")).state());
        assertEquals(State.REVIEWABLE, ready.withScope(new Scope.WholeWorkspace()).state());
        assertEquals(State.REVIEWABLE, ready.withReview(Review.none()).state());
        assertEquals(State.REVIEWABLE, ready.withContext(Context.none()).state());
    }

    // covers: AC-2
    @Test
    void unchangedValueStillWithdrawsTheConfirmation() {
        var ready = reviewedDraft();

        assertEquals(State.REVIEWABLE, ready.withScope(SCOPE).state());
    }

    // covers: AC-4
    @Test
    void workspaceIdIsNeverAPath() {
        assertThrows(IllegalArgumentException.class, () -> new Workspace("src/main", 1, "rev"));
        assertThrows(IllegalArgumentException.class, () -> new Workspace("C:\\work", 1, "rev"));
        assertThrows(IllegalArgumentException.class, () -> new Workspace("  ", 1, "rev"));
        assertThrows(NullPointerException.class, () -> new Workspace(null, 1, "rev"));
    }

    // covers: AC-2
    @Test
    void directoryScopeNamesAtLeastOneDirectoryAndCopiesTheList() {
        assertThrows(IllegalArgumentException.class, () -> new Scope.Directories(List.of()));

        var scope = new Scope.Directories(new ArrayList<>(List.of("src", "test")));

        assertEquals(List.of("src", "test"), scope.relativeDirectories());
        assertThrows(UnsupportedOperationException.class, () -> scope.relativeDirectories().add("more"));
    }

    // covers: AC-2
    @Test
    void reviewAndContextRejectNullComponents() {
        assertThrows(NullPointerException.class,
                () -> new Review(null, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new Context(null, Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
    }

    // covers: AC-2
    @Test
    void reviewPicksAreKeptAsGiven() {
        var review = new Review(Optional.of("plan"), Optional.of("bugfix"), Optional.of(Toggle.REQUIRED),
                Optional.of(Toggle.NOT_REQUIRED));

        var draft = reviewedDraft().withReview(review).confirm();

        assertEquals(review, draft.review());
        assertTrue(draft.ready());
    }
}
