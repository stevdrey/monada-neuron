package monada.neuron.resonance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoVectorModuleFallbackIntegrationTest {

    @Test
    void defaultEvaluatorFallsBackToScalarInFreshJvmWithoutVectorModule() throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NoVectorModuleFallbackProbe.class.getName());
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        processBuilder.environment().remove("JDK_JAVA_OPTIONS");
        processBuilder.environment().remove("_JAVA_OPTIONS");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, "child JVM did not finish within 30 seconds");
        assertEquals(0, process.exitValue(), () -> "child JVM failed:\n" + output);
        assertTrue(output.contains("SCALAR_FALLBACK_OK"), () -> "unexpected child JVM output:\n" + output);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
