package monada.neuron.aeon.formal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Depth-first enumeration of scheduler choices.
 *
 * <p>One schedule is one sequence of choices. A run replays a forced prefix and then takes the first
 * option at every new choice point. {@link #next()} returns the prefix of the next unexplored
 * schedule, so repeated runs visit every schedule exactly once, provided that a run is deterministic
 * for a given prefix. {@link #requireReplayed()} checks that the prefix was reproduced.
 */
final class ChoicePath {

    private final int[] forced;
    private final List<Integer> taken = new ArrayList<>();
    private final List<Integer> widths = new ArrayList<>();

    ChoicePath(int[] forced) {
        this.forced = forced.clone();
    }

    /** Returns the option, in {@code [0, width)}, this schedule takes at the next choice point. */
    synchronized int choose(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("a choice point needs at least one option, got: " + width);
        }
        int position = taken.size();
        int choice = position < forced.length ? forced[position] : 0;
        if (choice >= width) {
            throw new IllegalStateException("replay diverged at choice " + position + ": option " + choice
                    + " of " + width + " (the run is not deterministic for its prefix)");
        }
        taken.add(choice);
        widths.add(width);
        return choice;
    }

    /** Fails when the run ended before reproducing every forced choice. */
    synchronized void requireReplayed() {
        if (taken.size() < forced.length) {
            throw new IllegalStateException("replay diverged: " + taken.size() + " of " + forced.length
                    + " forced choices were reached (the run is not deterministic for its prefix)");
        }
    }

    /** Returns the choices this run took. */
    synchronized int[] choices() {
        return taken.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns the forced prefix of the next schedule in depth-first order, or empty after the last one. */
    synchronized Optional<int[]> next() {
        for (int position = taken.size() - 1; position >= 0; position--) {
            if (taken.get(position) + 1 < widths.get(position)) {
                int[] prefix = new int[position + 1];
                for (int i = 0; i < position; i++) {
                    prefix[i] = taken.get(i);
                }
                prefix[position] = taken.get(position) + 1;
                return Optional.of(prefix);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized String toString() {
        return Arrays.toString(choices());
    }
}
