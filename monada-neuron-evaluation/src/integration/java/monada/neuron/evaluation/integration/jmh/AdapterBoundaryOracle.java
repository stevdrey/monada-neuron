package monada.neuron.evaluation.integration.jmh;

import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.OpaqueReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validation-only reference for the adapter's merge contract, used in benchmark setup and tests.
 *
 * <p>It deliberately uses the simplest possible formulation (collect all candidates, keep the best
 * per atom, sort everything) so it can check the production bounded-heap merge. It is never invoked
 * from a measured method.
 */
public final class AdapterBoundaryOracle {

    private static final Pattern REFERENCE = Pattern.compile("rs-[0-9a-f]{32}");

    private record Candidate(String atomId, double score, int signalIndex, int rank) {
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparingDouble(Candidate::score).reversed()
            .thenComparingInt(Candidate::signalIndex)
            .thenComparingInt(Candidate::rank);

    /** Expected {@code (reference, score)} sequence for the scenario, best first and cut to the limit. */
    List<ResonanceMemoryResult> expectedKeys(AdapterBoundaryScenario scenario) {
        var best = new HashMap<String, Candidate>();
        for (var signalIndex = 0; signalIndex < scenario.querySignals(); signalIndex++) {
            var results = scenario.storeResults(signalIndex);
            for (var rank = 0; rank < results.size(); rank++) {
                var result = results.get(rank);
                var candidate = new Candidate(result.atom().id(), result.score(), signalIndex, rank);
                best.merge(candidate.atomId(), candidate, (left, right) -> ORDER.compare(left, right) <= 0 ? left : right);
            }
        }
        var sorted = new ArrayList<>(best.values());
        sorted.sort(ORDER);
        var reference = new OpaqueReference();
        var expected = new ArrayList<ResonanceMemoryResult>();
        for (var candidate : sorted.subList(0, Math.min(sorted.size(), scenario.maxResults()))) {
            expected.add(new ResonanceMemoryResult(
                    reference.of(candidate.atomId()), AdapterBoundaryScenario.CONSTANT_SIGNAL, candidate.score()));
        }
        return expected;
    }

    /** Throws {@link IllegalStateException} when the response violates the adapter contract. */
    public void validate(AdapterBoundaryScenario scenario, ResonanceMemoryResponse response) {
        require(response.status() == ResonanceMemoryStatus.COMPLETE, "status must be COMPLETE");
        var results = response.results();
        require(results.size() <= scenario.maxResults(), "result count exceeds maxResults");
        var expected = expectedKeys(scenario);
        require(results.size() == expected.size(), "result count differs from the reference");
        var previous = Double.POSITIVE_INFINITY;
        for (var index = 0; index < results.size(); index++) {
            var actual = results.get(index);
            require(Double.isFinite(actual.score()), "score must be finite");
            require(actual.score() <= previous, "scores must be non-increasing");
            previous = actual.score();
            require(REFERENCE.matcher(actual.reference()).matches(), "reference must be rs- plus 32 hex");
            require(actual.reference().equals(expected.get(index).reference()),
                    "reference differs from the reference order at index " + index);
            require(Double.compare(actual.score(), expected.get(index).score()) == 0,
                    "score differs from the reference at index " + index);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Adapter boundary validation failed: " + message);
        }
    }
}
