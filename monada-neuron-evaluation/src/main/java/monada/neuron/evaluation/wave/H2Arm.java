package monada.neuron.evaluation.wave;

/** Scoring arms of the H2 evaluation, as pre-registered in {@code docs/benchmarks/spectral-resonance-h2.md}. */
enum H2Arm {

    /** {@code SpectralResonanceDynamics} aggregate: the system under test. */
    AGGREGATE("aggregate", true),
    /** {@code ScalarResonanceMetric} on the dominant-component projection: the control (contract section 14.2). */
    SCALAR("scalar", true),
    /** {@code SpectralResonanceDynamics} coherence. */
    COHERENCE("coherence", false),
    /** Cosine of the shared magnitudes, phase ignored. */
    MAGNITUDE("magnitude", false),
    /** Aggregate after replacing every complex-bin phase of both patterns with a seeded uniform phase. */
    AGGREGATE_PHASE_RANDOMIZED("aggregate.phase-randomized", false),
    /**
     * Aggregate with the second pattern's complex bins moved up by {@code 32} bins. The shift does not clear every
     * band: a HIGH dominant tone (64–80 Hz) lands at 96–112 Hz, on the chord tones of HIGH_CHORD windows (96–120 Hz),
     * so those pairs still share bands, with independent phases.
     */
    AGGREGATE_FREQUENCY_SHIFTED("aggregate.frequency-shifted", false);

    private final String label;
    private final boolean gate;

    H2Arm(String label, boolean gate) {
        this.label = label;
        this.gate = gate;
    }

    /** Returns the arm's report label. */
    String label() {
        return label;
    }

    /** Returns whether the gate reads this arm; informational arms never decide it. */
    boolean gate() {
        return gate;
    }
}
