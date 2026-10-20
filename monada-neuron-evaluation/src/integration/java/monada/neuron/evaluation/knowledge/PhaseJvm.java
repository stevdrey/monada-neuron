package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one experiment phase in a child JVM, so a {@code reopen} phase shares no heap, handle or cache with the
 * {@code seed} phase that wrote the Store root. Used by the persistence (Issue #106) and judged recall (Issue #107)
 * orchestrators.
 */
final class PhaseJvm {

    /** Wall-clock bound of one child phase. */
    static final long PHASE_TIMEOUT_SECONDS = 300;

    private static final int STDERR_TAIL_LINES = 20;

    /**
     * Runs {@code mainClass phase <root> <work-dir>} with this JVM's {@code java} executable and class path, so the
     * child loads exactly the classes under test, and with this JVM's heap, {@code -XX} and module flags (see
     * {@link #forwardedJvmArguments()}), so both run with the same reported configuration.
     */
    PhaseRun launch(Class<?> mainClass, String phase, Path root, WorkDirectory work) {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var stdout = work.path().resolve(phase + ".out");
        var stderr = work.path().resolve(phase + ".err");
        var command = new ArrayList<String>();
        command.add(java);
        command.addAll(forwardedJvmArguments());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), mainClass.getName(), phase,
                root.toAbsolutePath().toString(), work.path().toAbsolutePath().toString()));
        var start = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the " + phase + " phase JVM", e);
        }
        try {
            var exit = process.waitFor(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ? process.exitValue() : -1;
            if (exit == -1) {
                terminate(process);
            }
            return new PhaseRun(phase, process.pid(), exit, System.nanoTime() - start, tail(stderr));
        } catch (InterruptedException e) {
            // Never leave a phase JVM writing into the work directory (or a kept root) that is about to be deleted.
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the " + phase + " phase", e);
        }
    }

    /**
     * Returns the orchestrator's heap ({@code -Xms}, {@code -Xmx}, {@code -Xss}), {@code -XX:} and module
     * ({@code --add-modules}, {@code --enable-preview}) arguments. System properties, agents and tool-specific
     * arguments of a host such as a Gradle test worker are not forwarded.
     */
    List<String> forwardedJvmArguments() {
        var input = ManagementFactory.getRuntimeMXBean().getInputArguments();
        var forwarded = new ArrayList<String>();
        for (var index = 0; index < input.size(); index++) {
            var argument = input.get(index);
            if (argument.equals("--add-modules") && index + 1 < input.size()) {
                forwarded.add(argument);
                forwarded.add(input.get(++index));
            } else if (argument.startsWith("--add-modules=") || argument.equals("--enable-preview")
                    || argument.startsWith("-Xms") || argument.startsWith("-Xmx") || argument.startsWith("-Xss")
                    || argument.startsWith("-XX:")) {
                forwarded.add(argument);
            }
        }
        return forwarded;
    }

    /** Kills {@code process} and waits for it to exit, even if this thread is interrupted meanwhile. */
    private void terminate(Process process) {
        process.destroyForcibly();
        var interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String tail(Path file) {
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - STDERR_TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "<stderr unreadable: " + e.getMessage() + ">";
        }
    }
}
