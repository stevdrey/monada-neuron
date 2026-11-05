package monada.neuron.evaluation.bilingual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BilingualSenseRunnerTest {

    @Test
    void stepEightIsRefusedUntilTheV2PrerequisitesHold() {
        assertEquals(BilingualSenseRunner.NOT_READY, new BilingualSenseRunner().execute(new String[0]));
    }

    @Test
    void invalidArgumentsAreRejected() {
        var runner = new BilingualSenseRunner();
        assertEquals(BilingualSenseRunner.INVALID_ARGUMENTS, runner.execute(new String[] {"--held-out"}));
        assertEquals(BilingualSenseRunner.INVALID_ARGUMENTS, runner.execute(new String[] {"--seed"}));
    }
}
