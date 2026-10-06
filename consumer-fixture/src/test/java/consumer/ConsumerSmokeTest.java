package consumer;

import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.resonance.AdaptiveBatchResonanceEvaluator;
import monada.neuron.resonance.BatchResonanceEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerSmokeTest {

    @Test
    void embeddedRuntimeExecutesACycle() {
        var result = ConsumerSmoke.runOneCycle();

        assertEquals(CognitiveCycleTermination.COMPLETED, result.termination());
        assertEquals(1, result.outputSignals().size());
    }

    @Test
    void portablePathWorksWithoutTheVectorModule() {
        assertTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty(),
                "the fixture must run without jdk.incubator.vector");

        var evaluator = BatchResonanceEvaluator.defaultEvaluator();
        var adaptive = assertInstanceOf(AdaptiveBatchResonanceEvaluator.class, evaluator);

        assertTrue(evaluator.isAvailable());
        assertFalse(adaptive.isVectorAvailable());
    }

    @Test
    void evaluationAndBenchmarkCodeAreNotOnTheConsumerClasspath() {
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            assertFalse(entry.contains("monada-neuron-evaluation"), entry);
            assertFalse(entry.contains("jmh"), entry);
        }
    }
}
