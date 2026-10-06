package consumer;

import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.resonance.AdaptiveBatchResonanceEvaluator;
import monada.neuron.resonance.BatchResonanceEvaluator;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

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
        var fileNames = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).getFileName().toString())
                .toList();

        assertTrue(fileNames.stream().anyMatch(name -> name.startsWith("monada-neuron-0.1.0")),
                "the Neuron artifact must be on the consumer classpath: " + fileNames);
        for (String name : fileNames) {
            assertFalse(name.startsWith("monada-neuron-evaluation"), name);
            assertFalse(name.startsWith("jmh-"), name);
        }
    }
}
