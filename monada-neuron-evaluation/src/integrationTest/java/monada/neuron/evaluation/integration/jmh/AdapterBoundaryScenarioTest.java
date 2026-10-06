package monada.neuron.evaluation.integration.jmh;

import monada.neuron.evaluation.integration.jmh.AdapterBoundaryScenario.DuplicateProfile;
import monada.neuron.memory.ResonanceMemoryStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the benchmark fixtures against the production adapter over the whole parameter matrix. */
final class AdapterBoundaryScenarioTest {

    private static final int[] QUERY_SIGNALS = {1, 3, 8};
    private static final int[] MAX_RESULTS = {1, 5, 10, 32};

    @Test
    void productionAdapterMatchesTheNaiveReferenceAcrossTheParameterMatrix() {
        var oracle = new AdapterBoundaryOracle();
        for (var signals : QUERY_SIGNALS) {
            for (var limit : MAX_RESULTS) {
                for (var profile : DuplicateProfile.values()) {
                    var scenario = new AdapterBoundaryScenario(signals, limit, profile);
                    var merge = scenario.mergeOnlyAdapter().recall(scenario.request());
                    var boundary = scenario.boundaryAdapter().recall(scenario.request());
                    assertEquals(ResonanceMemoryStatus.COMPLETE, merge.status());
                    oracle.validate(scenario, merge);
                    oracle.validate(scenario, boundary);
                }
            }
        }
    }

    @Test
    void candidateCountIsBoundedBySignalsTimesLimit() {
        for (var signals : QUERY_SIGNALS) {
            for (var limit : MAX_RESULTS) {
                var scenario = new AdapterBoundaryScenario(signals, limit, DuplicateProfile.NONE);
                for (var index = 0; index < signals; index++) {
                    assertEquals(limit, scenario.storeResults(index).size());
                }
                assertEquals(signals * limit, scenario.distinctAtoms());
            }
        }
    }

    @Test
    void duplicateProfilesCollapseCandidatesAsDocumented() {
        var none = new AdapterBoundaryScenario(8, 10, DuplicateProfile.NONE);
        var moderate = new AdapterBoundaryScenario(8, 10, DuplicateProfile.MODERATE);
        var high = new AdapterBoundaryScenario(8, 10, DuplicateProfile.HIGH);

        assertEquals(80, none.distinctAtoms());
        assertEquals(5 + 8 * 5, moderate.distinctAtoms());
        assertEquals(10, high.distinctAtoms());
    }

    @Test
    void highDuplicationStillYieldsMaxResultsAtomsAndTiesResolveBySignalOrder() {
        var scenario = new AdapterBoundaryScenario(8, 5, DuplicateProfile.HIGH);
        var response = scenario.mergeOnlyAdapter().recall(scenario.request());

        assertEquals(5, response.results().size());
        assertTrue(response.results().stream().allMatch(result -> Double.isFinite(result.score())));
    }

    @Test
    void substituteStoreAnswersEachRecallFromTheFirstSignalAgain() {
        var scenario = new AdapterBoundaryScenario(3, 5, DuplicateProfile.MODERATE);
        var adapter = scenario.mergeOnlyAdapter();

        assertEquals(adapter.recall(scenario.request()), adapter.recall(scenario.request()));
    }

    @Test
    void oracleRejectsAWrongResponse() {
        var scenario = new AdapterBoundaryScenario(3, 5, DuplicateProfile.NONE);
        var other = new AdapterBoundaryScenario(3, 5, DuplicateProfile.HIGH);
        var wrong = other.mergeOnlyAdapter().recall(other.request());

        assertThrows(IllegalStateException.class,
                () -> new AdapterBoundaryOracle().validate(scenario, wrong));
        assertFalse(wrong.results().isEmpty());
    }
}
