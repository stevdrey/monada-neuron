package monada.neuron.resonance;

/** The canonical {@code (level, index)} order of spectral components, shared by the comparison and its result. */
final class BandKeys {

    private BandKeys() {
    }

    /** Compares two keys by level, then by index; negative when the first key comes first. */
    static int compare(int firstLevel, int firstIndex, int secondLevel, int secondIndex) {
        int byLevel = Integer.compare(firstLevel, secondLevel);
        return byLevel != 0 ? byLevel : Integer.compare(firstIndex, secondIndex);
    }
}
