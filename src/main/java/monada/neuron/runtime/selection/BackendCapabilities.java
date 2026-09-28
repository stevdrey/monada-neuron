package monada.neuron.runtime.selection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, cached snapshot of platform and hardware capabilities relevant for runtime backend selection.
 *
 * <p>Capability detection is performed once and cached statically to ensure control-plane selection
 * incurs zero repeated reflection, JVM inspection, or allocation overhead in hot paths.
 */
public final class BackendCapabilities {

    private static final BackendCapabilities CURRENT = detectCurrent();

    private final boolean vectorApiAvailable;
    private final int vectorLaneWidth;
    private final String vectorSpeciesDescription;
    private final int availableProcessors;
    private final boolean ffmAvailable;
    private final Throwable vectorInitializationFailure;
    private final Map<String, String> diagnosticSummary;

    private BackendCapabilities(
            boolean vectorApiAvailable,
            int vectorLaneWidth,
            String vectorSpeciesDescription,
            int availableProcessors,
            boolean ffmAvailable,
            Throwable vectorInitializationFailure) {
        this.vectorApiAvailable = vectorApiAvailable;
        this.vectorLaneWidth = vectorLaneWidth;
        this.vectorSpeciesDescription = vectorSpeciesDescription;
        this.availableProcessors = availableProcessors;
        this.ffmAvailable = ffmAvailable;
        this.vectorInitializationFailure = vectorInitializationFailure;

        var map = new LinkedHashMap<String, String>();
        map.put("vectorApiAvailable", String.valueOf(vectorApiAvailable));
        map.put("vectorLaneWidth", String.valueOf(vectorLaneWidth));
        map.put("vectorSpecies", vectorSpeciesDescription);
        map.put("availableProcessors", String.valueOf(availableProcessors));
        map.put("ffmAvailable", String.valueOf(ffmAvailable));
        if (vectorInitializationFailure != null) {
            map.put("vectorInitializationFailure", vectorInitializationFailure.toString());
        }
        this.diagnosticSummary = Map.copyOf(map);
    }

    /** Returns the cached capability snapshot of the current JVM runtime. */
    public static BackendCapabilities current() {
        return CURRENT;
    }

    private static BackendCapabilities detectCurrent() {
        boolean vectorAvailable = false;
        int laneWidth = 1;
        String speciesDesc = "none";
        Throwable vectorInitFailure = null;

        try {
            Class<?> vectorEvaluatorClass = Class.forName(
                    "monada.neuron.resonance.VectorBatchResonanceEvaluator",
                    true,
                    BackendCapabilities.class.getClassLoader());
            Object instance = vectorEvaluatorClass.getField("INSTANCE").get(null);
            var isAvailableMethod = vectorEvaluatorClass.getMethod("isAvailable");
            var isAvailable = (Boolean) isAvailableMethod.invoke(instance);
            if (Boolean.TRUE.equals(isAvailable)) {
                vectorAvailable = true;
                var widthMethod = vectorEvaluatorClass.getMethod("vectorWidth");
                laneWidth = (Integer) widthMethod.invoke(instance);
                var speciesMethod = vectorEvaluatorClass.getMethod("species");
                Object species = speciesMethod.invoke(instance);
                speciesDesc = species != null ? species.toString() : "unknown";
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Expected when jdk.incubator.vector module is not resolved
            vectorAvailable = false;
            laneWidth = 1;
            speciesDesc = "unavailable";
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            vectorAvailable = false;
            laneWidth = 1;
            speciesDesc = "failed: " + failure.getClass().getSimpleName();
            vectorInitFailure = failure;
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());

        boolean ffm;
        try {
            Class.forName("java.lang.foreign.Arena", false, BackendCapabilities.class.getClassLoader());
            ffm = true;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            ffm = false;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            ffm = false;
        }

        return new BackendCapabilities(vectorAvailable, laneWidth, speciesDesc, processors, ffm, vectorInitFailure);
    }

    /** Returns whether the Java 26 Vector API incubator module is loaded and multi-lane SIMD is supported. */
    public boolean isVectorApiAvailable() {
        return vectorApiAvailable;
    }

    /** Returns the vector lane count for double-precision floats, or 1 if scalar. */
    public int vectorLaneWidth() {
        return vectorLaneWidth;
    }

    /** Returns a human-readable description of the preferred vector species. */
    public String vectorSpeciesDescription() {
        return vectorSpeciesDescription;
    }

    /** Returns the number of available CPU processors reported by the runtime. */
    public int availableProcessors() {
        return availableProcessors;
    }

    /** Returns whether the Foreign Function & Memory API is available. */
    public boolean isFfmAvailable() {
        return ffmAvailable;
    }

    /** Returns the initialization failure cause if Vector API detection encountered an error, or empty. */
    public Optional<Throwable> vectorInitializationFailure() {
        return Optional.ofNullable(vectorInitializationFailure);
    }

    /** Returns an immutable diagnostic map of detected capabilities. */
    public Map<String, String> diagnosticSummary() {
        return diagnosticSummary;
    }
}
