package monada.neuron.runtime.selection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Map<String, String> diagnosticSummary;

    private BackendCapabilities(
            boolean vectorApiAvailable,
            int vectorLaneWidth,
            String vectorSpeciesDescription,
            int availableProcessors,
            boolean ffmAvailable) {
        this.vectorApiAvailable = vectorApiAvailable;
        this.vectorLaneWidth = vectorLaneWidth;
        this.vectorSpeciesDescription = vectorSpeciesDescription;
        this.availableProcessors = availableProcessors;
        this.ffmAvailable = ffmAvailable;

        var map = new LinkedHashMap<String, String>();
        map.put("vectorApiAvailable", String.valueOf(vectorApiAvailable));
        map.put("vectorLaneWidth", String.valueOf(vectorLaneWidth));
        map.put("vectorSpecies", vectorSpeciesDescription);
        map.put("availableProcessors", String.valueOf(availableProcessors));
        map.put("ffmAvailable", String.valueOf(ffmAvailable));
        this.diagnosticSummary = Collections.unmodifiableMap(map);
    }

    /** Returns the cached capability snapshot of the current JVM runtime. */
    public static BackendCapabilities current() {
        return CURRENT;
    }

    private static BackendCapabilities detectCurrent() {
        boolean vectorAvailable = false;
        int laneWidth = 1;
        String speciesDesc = "none";

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
        } catch (Throwable ignored) {
            // Incubator module not resolved or reflection inaccessible
            vectorAvailable = false;
            laneWidth = 1;
            speciesDesc = "unavailable";
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());

        boolean ffm;
        try {
            Class.forName("java.lang.foreign.Arena", false, BackendCapabilities.class.getClassLoader());
            ffm = true;
        } catch (Throwable ignored) {
            ffm = false;
        }

        return new BackendCapabilities(vectorAvailable, laneWidth, speciesDesc, processors, ffm);
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

    /** Returns an immutable diagnostic map of detected capabilities. */
    public Map<String, String> diagnosticSummary() {
        return diagnosticSummary;
    }
}
