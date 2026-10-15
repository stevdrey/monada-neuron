package monada.neuron.evaluation.routing;

import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDigestsTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();
    private final ReplayDigests digests = new ReplayDigests();

    @Test
    void repeatedReplaysYieldIdenticalDecisionStateAndReportDigests() {
        var first = new TemporalReplay(fixtures.workload(120)).run(ReplayArm.ADAPTIVE);
        var second = new TemporalReplay(fixtures.workload(120)).run(ReplayArm.ADAPTIVE);

        assertAll(
                () -> assertEquals(digests.fixture(fixtures.workload(120)), digests.fixture(fixtures.workload(120))),
                () -> assertEquals(digests.decisions(first), digests.decisions(second)),
                () -> assertEquals(digests.feedback(first), digests.feedback(second)),
                () -> assertEquals(digests.state(first.state().orElseThrow()),
                        digests.state(second.state().orElseThrow())),
                () -> assertEquals(digests.report(List.of(ReplaySummary.of(first))),
                        digests.report(List.of(ReplaySummary.of(second)))));
    }

    @Test
    void theReportDigestDoesNotDependOnTheCollectionsOrder() {
        var replay = new TemporalReplay(fixtures.improvement(24));
        var summaries = new ArrayList<ReplaySummary>();
        for (ReplayArm arm : ReplayArm.values()) {
            summaries.add(ReplaySummary.of(replay.run(arm)));
        }
        String inOrder = digests.report(summaries);

        assertAll(
                () -> assertEquals(inOrder, digests.report(summaries.reversed())),
                () -> assertEquals(inOrder, digests.report(new HashSet<>(summaries))),
                // two summaries of one arm would collide line by line
                () -> assertThrows(IllegalArgumentException.class,
                        () -> digests.report(List.of(summaries.getFirst(), summaries.getFirst()))));
    }

    @Test
    void separatorsInsideTheFixtureIdentityCannotMergeTwoFixtures() {
        var base = fixtures.improvement(4);
        // "a@b" + "c" and "a" + "b@c" would read the same if concatenated with "@"
        var first = new ReplayFixture("a@b", "c", base.definition(), base.evidencePolicy(), base.policy(),
                base.partition(), base.tasks());
        var second = new ReplayFixture("a", "b@c", base.definition(), base.evidencePolicy(), base.policy(),
                base.partition(), base.tasks());

        assertNotEquals(digests.fixture(first), digests.fixture(second));
    }

    @Test
    void digestsSeparateArmsAndFixtures() {
        var replay = new TemporalReplay(fixtures.improvement(24));
        var adaptive = replay.run(ReplayArm.ADAPTIVE);
        var derivedOnly = replay.run(ReplayArm.DERIVED_NOT_CONSUMED);

        assertAll(
                () -> assertNotEquals(digests.decisions(adaptive), digests.decisions(derivedOnly)),
                () -> assertNotEquals(digests.state(adaptive.state().orElseThrow()),
                        digests.state(derivedOnly.state().orElseThrow())),
                () -> assertNotEquals(digests.fixture(fixtures.improvement(24)),
                        digests.fixture(fixtures.degradation(24))));
    }

    @Test
    void qualityInsertionOrderDoesNotChangeTheFixtureDigest() {
        var forward = new LinkedHashMap<QualityDimension, QualityState>();
        forward.put(QualityDimension.TESTS, QualityState.PASS);
        forward.put(QualityDimension.SECURITY, QualityState.FAIL);
        var backward = new LinkedHashMap<QualityDimension, QualityState>();
        backward.put(QualityDimension.SECURITY, QualityState.FAIL);
        backward.put(QualityDimension.TESTS, QualityState.PASS);
        var base = ((OutcomeOracle.KnownForEveryRoute) fixtures.improvement(1).tasks().getFirst().oracle()).outcomes()
                .get(SyntheticRoutingFixtures.SUB_A);

        assertEquals(withQuality(base, forward).toString(), withQuality(base, backward).toString());
    }

    @Test
    void neutralOnlyConsumptionChangesTheLedgerButNotTheCohorts() {
        var state = new TemporalReplay(fixtures.neutral(16)).run(ReplayArm.ADAPTIVE).state().orElseThrow();

        assertAll(
                () -> assertEquals(digests.cohorts(state.resetCopy()), digests.cohorts(state)),
                () -> assertNotEquals(digests.state(state.resetCopy()), digests.state(state)));
    }

    private static ScriptedOutcome withQuality(ScriptedOutcome base,
            Map<QualityDimension, QualityState> quality) {
        return new ScriptedOutcome(base.observedAt(), base.invocation(), base.status(), base.origin(), quality,
                base.attribution(), base.acceptedByRepair(), base.stage(), base.chain(), base.correction());
    }
}
