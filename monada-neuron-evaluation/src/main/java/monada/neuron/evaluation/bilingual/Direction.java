package monada.neuron.evaluation.bilingual;

/**
 * Language direction of protocol {@code bilingual-sense/2}. Directions are gated, scored and decided independently and
 * never pooled (ADR 0051 decision 4).
 */
enum Direction {

    /** English source sentence, Spanish target expressions. */
    EN_ES("EN>ES"),
    /** Spanish source sentence, English target expressions. */
    ES_EN("ES>EN");

    private final String label;

    Direction(String label) {
        this.label = label;
    }

    /** Returns the label used in occurrence ids ({@code EN>ES} or {@code ES>EN}, v2 R14). */
    String label() {
        return label;
    }
}
